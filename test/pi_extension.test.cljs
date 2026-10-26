(ns pi-extension.test
  "A pi extension loads into nyma unchanged, as ThinkRail's rails (pi-todos,
   spec-graph, the workflow rail) use the API: the one-argument
   registerTool with pi's execute(toolCallId, params, signal, onUpdate, ctx),
   ctx.cwd and ctx.sessionManager.getSessionId(), and before_agent_start
   writing systemPromptOptions.sections[name]. Loaded through `--extension`'s
   path, a pi package directory."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-loader :refer [discover-and-load deactivate-all extension-targets]]
            [agent.loop :refer [run]]
            [agent.extension-context :refer [create-extension-context]]
            [test-util.agent-harness :refer [make-test-agent]]))

(def ^:private index-ts
  "export default (pi) => {
  pi.registerTool({
    name: \"probe_list\", label: \"Probe\", description: \"probe\",
    parameters: { type: \"object\", properties: { x: { type: \"string\" } } },
    async execute(id, params, signal, onUpdate, ctx) {
      return { content: [{ type: \"text\", text: [id, params.x, ctx.cwd === process.cwd(), ctx.sessionManager.getSessionId()].join(\"|\") }],
               details: { ok: true } };
    },
  });
  pi.on(\"before_agent_start\", (event) => { event.systemPromptOptions.sections[\"probe\"] = \"PROBE RULE\"; });
};
")

(defn- pi-package!
  "A pi package in a temp dir: package.json with `pi.extensions`/`pi.skills`."
  []
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "pi-pkg-"))]
    (fs/writeFileSync (path/join dir "index.ts") index-ts)
    (fs/mkdirSync (path/join dir "skills" "probe") #js {:recursive true})
    (fs/writeFileSync (path/join dir "skills" "probe" "SKILL.md") "---\nname: probe\ndescription: p\n---\nbody\n")
    (fs/writeFileSync (path/join dir "package.json")
                      (js/JSON.stringify #js {:name "probe" :pi #js {:extensions #js ["./index.ts"] :skills #js ["./skills"]}}))
    dir))

(describe "pi extensions"
          (fn []
            (it "extension-targets reads a pi package's extensions and skills"
                (fn []
                  (let [dir (pi-package!)
                        t   (extension-targets [dir])]
                    (-> (expect (vec (:files t))) (.toEqual #js [(path/join dir "index.ts")]))
                    (-> (expect (vec (:skill-dirs t))) (.toEqual #js [(path/join dir "skills")]))
                    (fs/rmSync dir #js {:recursive true :force true}))))

            (it "a pi tool registers under its own name and runs with pi's arguments"
                ^:async (fn []
                          (let [dir    (pi-package!)
                                agent  (create-agent {:model #js {:modelId "test"} :system-prompt "test" :settings #js {}})
                                api    (create-extension-api agent)
                                _      (set! (.-extension-api agent) api)
                                _      (reset! (:session agent) {:get-file-path (fn [] "/s/2026-10-26_abc.jsonl")})
                                loaded (js-await (discover-and-load [] api [] (:files (extension-targets [dir]))))
                                td     (.getTool api "probe_list")
                                ;; what the middleware hands a tool, plus the call id it sets
                                ctx    (doto (create-extension-context agent) (aset "toolCallId" "call_9"))
                                r      (js-await ((.-execute td) #js {:x "hi"} ctx))]
                            (-> (expect (some? td)) (.toBe true))
                            (-> (expect (.. r -content (at 0) -text)) (.toBe "call_9|hi|true|2026-10-26_abc"))
                            (-> (expect (.. r -details -ok)) (.toBe true))
                            (js-await (deactivate-all loaded))
                            (-> (expect (nil? (.getTool api "probe_list"))) (.toBe true))
                            (fs/rmSync dir #js {:recursive true :force true}))))

            (it "a pi tool may not replace an existing tool"
                ^:async (fn []
                          (let [dir   (fs/mkdtempSync (path/join (os/tmpdir) "pi-clash-"))
                                f     (path/join dir "clash.ts")
                                _     (fs/writeFileSync f "export default (pi) => { pi.registerTool({ name: \"read\", description: \"x\", parameters: { type: \"object\", properties: {} }, async execute() { return { content: [] }; } }); };")
                                agent (create-agent {:model #js {:modelId "test"} :system-prompt "test" :settings #js {}})
                                api   (create-extension-api agent)
                                _     (set! (.-extension-api agent) api)
                                native (.getTool api "read")
                                loaded (js-await (discover-and-load [] api [] [f]))]
                            (-> (expect (count loaded)) (.toBe 0))
                            (-> (expect (identical? native (.getTool api "read"))) (.toBe true))
                            (fs/rmSync dir #js {:recursive true :force true}))))

            (it "systemPromptOptions.sections written in before_agent_start reach the system prompt"
                ^:async (fn []
                          (let [agent (make-test-agent)
                                seen  (atom nil)]
                            ((:on (:events agent)) "before_agent_start"
                                                   (fn [e] (aset (.. e -systemPromptOptions -sections) "probe" "PROBE RULE") nil))
                            ((:on (:events agent)) "before_provider_request"
                                                   (fn [cfg] (reset! seen (.-system cfg)) #js {:block true}))
                            (js-await (run agent "hi"))
                            (-> (expect @seen) (.toContain "PROBE RULE")))))))
