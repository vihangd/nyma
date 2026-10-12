(ns mcp-server-options-test
  "Per-server MCP options from .mcp.json, against a real stdio server.

   - `env` never reached the server: discovery turns it into ACP
     [{name, value}] and the client walked that as an object.
   - Timeouts were global, so one slow server (a cold Ghidra start) could not
     get longer without every server getting longer.
   - There was no way to keep a server's dangerous tools away from the model —
     `hidden-tools` hides, but mcp_call reaches anything.
   - A tool the server marks `destructiveHint` ran unasked."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:path" :as path]
            ["node:fs" :as fs]
            ["node:os" :as os]
            [agent.extensions.mcp-client.client :as client]
            [agent.extensions.mcp-client.tool-bridge :as bridge]
            [agent.extensions.agent-shell.features.mcp-discovery :as disc]
            [agent.extensions.agent-shell.shared :as shared]))

(def ^:private fixture (path/resolve (js/process.cwd) "test" "fixtures" "echo_mcp_server.mjs"))

(defn- entry
  "A .mcp.json server entry through discovery, as the client gets it."
  [m]
  (first (disc/object->array (clj->js {"echo" (merge {:command "bun" :args [fixture]} m)}))))

(describe "discovery carries nyma's per-server options"
          (fn []
            (it "under :nyma, stripped for ACP agents"
                (fn []
                  (let [e (entry {:callTimeoutMs 360000 :denyTools ["wipe_*"] :env {:A "1"}})]
                    (-> (expect (get-in e [:nyma :call-timeout-ms])) (.toBe 360000))
                    (-> (expect (clj->js (get-in e [:nyma :deny-tools]))) (.toEqual #js ["wipe_*"]))
                    (-> (expect (:nyma (first (shared/acp-servers [e])))) (.toBeUndefined))
                    ;; an entry without options carries no :nyma at all
                    (-> (expect (:nyma (entry {}))) (.toBeUndefined)))))
            (it "allow/deny globs"
                (fn []
                  (-> (expect (client/tool-allowed? {:deny-tools ["wipe_*"]} "wipe_device")) (.toBe false))
                  (-> (expect (client/tool-allowed? {:deny-tools ["wipe_*"]} "get_env")) (.toBe true))
                  (-> (expect (client/tool-allowed? {:allow-tools ["get_*"]} "plain")) (.toBe false))
                  (-> (expect (client/tool-allowed? {} "anything")) (.toBe true))))
            (it "MCP hints become permission metadata; silence stays silent"
                (fn []
                  (-> (expect (.-category (bridge/hint-safety #js {:readOnlyHint true}))) (.toBe "mcp"))
                  (-> (expect (aget (bridge/hint-safety #js {:readOnlyHint true}) "read-only?")) (.toBe true))
                  (-> (expect (vec (.-capabilities (bridge/hint-safety #js {:destructiveHint true})))) (.toEqual ["shell"]))
                  (-> (expect (bridge/hint-safety nil)) (.toBeFalsy))))))

(defn ^:async test-live []
  (let [e (entry {:env {:ECHO_PROBE "from-mcp-json"} :denyTools ["wipe_*"]})
        c (client/create (assoc e
                                :startup-timeout-ms 15000
                                :allow-tools (get-in e [:nyma :allow-tools])
                                :deny-tools (get-in e [:nyma :deny-tools])))]
    (try
      (js-await (client/start! c))
      ;; env from .mcp.json arrived
      (let [r (js-await (client/call-tool! c {:tool-name "get_env" :arguments #js {:name "ECHO_PROBE"}}))]
        (-> (expect (.-text (aget (clj->js (:content r)) 0))) (.toBe "from-mcp-json")))
      ;; the denied tool is not listed, and calling it by name is refused
      (-> (expect (some #(= "wipe_device" (:name %)) (client/list-tools c))) (.toBeFalsy))
      (-> (expect (some #(= "get_env" (:name %)) (client/list-tools c))) (.toBe true))
      (let [err (try (js-await (client/call-tool! c {:tool-name "wipe_device" :arguments #js {}})) nil
                     (catch :default x (.-message x)))]
        (-> (expect (str err)) (.toContain "denied")))
      ;; annotations survive into the tool list
      (-> (expect (.-readOnlyHint (:annotations (first (filter #(= "get_env" (:name %)) (client/list-tools c))))))
          (.toBe true))
      (finally (js-await (client/stop! c))))))

(describe "a real stdio server"
          (fn [] (it "gets its env, and its denied tool is unreachable" test-live 30000)))

(describe "/mcp add · /mcp remove"
          (fn []
            (it "parses flags before the command; the server's own argv passes through"
                (fn []
                  (let [r (disc/parse-add-args ["rea" "--env" "REA_MCP_INPUT_SCHEMA_PROFILE=compact" "--timeout" "360000"
                                                "--deny" "*adb*" "npx" "-y" "rea-agents@6.4.0" "mcp" "--json"])]
                    (-> (expect (:name r)) (.toBe "rea"))
                    (-> (expect (clj->js (:entry r)))
                        (.toEqual (clj->js {"command" "npx" "args" ["-y" "rea-agents@6.4.0" "mcp" "--json"]
                                            "env" {"REA_MCP_INPUT_SCHEMA_PROFILE" "compact"}
                                            "callTimeoutMs" 360000 "startupTimeoutMs" 360000
                                            "denyTools" ["*adb*"]}))))
                  (-> (expect (clj->js (:entry (disc/parse-add-args ["c7" "--url" "https://x/mcp" "--header" "A=b"]))))
                      (.toEqual (clj->js {"url" "https://x/mcp" "headers" {"A" "b"}})))
                  (-> (expect (:project? (disc/parse-add-args ["x" "--project" "--" "cmd" "--flag"]))) (.toBe true))
                  (doseq [bad [[] ["x"] ["x" "--env" "NOEQ" "cmd"] ["x" "--timeout" "soon" "cmd"] ["x" "--bogus" "1" "cmd"]]]
                    (-> (expect (:error (disc/parse-add-args bad))) (.toBeTruthy)))))

            (it "writes into an existing file, keeps other servers, refuses a broken one; remove deletes one"
                (fn []
                  (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-mcp-add-"))
                        f (path/join d "mcp.json")]
                    (fs/writeFileSync f "{\"mcpServers\": {\"lean-ctx\": {\"command\": \"lean-ctx\", \"args\": [\"mcp\"]}}, \"other\": 1}")
                    (-> (expect (disc/add-server! f "rea" {"command" "npx" "args" ["rea-agents" "mcp"]})) (.toBe false))
                    (let [j (js/JSON.parse (fs/readFileSync f "utf8"))]
                      (-> (expect (.. j -mcpServers -rea -command)) (.toBe "npx"))
                      (-> (expect (aget (.-mcpServers j) "lean-ctx")) (.toBeTruthy))
                      (-> (expect (.-other j)) (.toBe 1)))
                    (-> (expect (disc/remove-server! f "rea")) (.toBe true))
                    (-> (expect (disc/remove-server! f "rea")) (.toBe false))
                    (-> (expect (aget (.-mcpServers (js/JSON.parse (fs/readFileSync f "utf8"))) "lean-ctx")) (.toBeTruthy))
                    (fs/writeFileSync f "{broken")
                    (-> (expect (fn [] (disc/add-server! f "x" {"command" "y"}))) (.toThrow "does not parse"))
                    (-> (expect (fs/readFileSync f "utf8")) (.toBe "{broken"))
                    (fs/rmSync d #js {:recursive true}))))))
