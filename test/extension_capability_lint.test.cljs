(ns extension-capability-lint.test
  "Every gated API an extension calls must be declared in its manifest.

   `spec_driven` called `sendUserMessage` — the follow-up that drives the phase
   loop's every `:continue` and `:advance` — without declaring `messages`. The
   scoped API replaces an undeclared method with a thrower, so the loop threw
   inside its `agent_end` handler on the first turn it tried to advance:

     [spec-driven] Error in agent_end handler:
       Extension missing capability: messages

   Nothing caught it. The handler's own try/catch logged and swallowed, the
   command surface worked, the decomposition worked, the role bound — and the
   loop had simply never run a turn. That is not something a unit test of the
   pure decision core can see, and it is a whole class: any extension can call
   any gated method and only find out in front of a user.

   So this walks the real manifests against the real sources."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def ^:private ext-root
  (path/resolve (js/process.cwd) "src" "agent" "extensions"))

(def gated-methods
  "method name → capability it needs. Mirrors the `gate` calls in
   extension_scope.cljs. Only methods whose NAME is unambiguous are listed:
   `on`/`off` are gated too but are far too common as bare words to match by
   text without false positives, and every extension declares `events` anyway."
  {"registerTool" "tools" "unregisterTool" "tools" "overrideTool" "tools-override"
   "unoverrideTool" "tools-override" "getActiveTools" "tools" "getAllTools" "tools"
   "getTool" "tools" "setActiveTools" "tools"
   "registerCommand" "commands" "unregisterCommand" "commands" "getCommands" "commands"
   "registerShortcut" "shortcuts" "unregisterShortcut" "shortcuts"
   "sendMessage" "messages" "sendUserMessage" "messages"
   "addMiddleware" "middleware" "removeMiddleware" "middleware"
   "exec" "exec" "spawn" "spawn"
   "appendEntry" "session" "setSessionName" "session" "getSessionName" "session"
   "setLabel" "session"
   "registerBlockRenderer" "renderers" "unregisterBlockRenderer" "renderers"
   "registerToolRenderer" "renderers" "unregisterToolRenderer" "renderers"
   "registerStatusSegment" "ui" "unregisterStatusSegment" "ui"
   "registerCompletionProvider" "ui" "unregisterCompletionProvider" "ui"
   "registerMentionProvider" "ui" "unregisterMentionProvider" "ui"
   "registerProvider" "providers" "unregisterProvider" "providers"
   "setModel" "model" "getActiveModelSpec" "model" "getThinkingLevel" "model"})

(def ui-property-capability
  "`api.ui` is NOT a gated method — extension_scope defines it as a getter that
   returns `#js {:available false}` when :ui is absent, instead of the thrower
   `gate` installs. So an extension without the capability gets a silent stub
   and every notify/setWidget quietly does nothing.

   Three shipped extensions were in exactly that state: token_suite's live token
   widget could never render, workspace_config's entire /alias output was
   swallowed, and claude_hook_bridge's detect-mode read `.-ui` and so reported
   \"sdk\" in interactive sessions. The method-name lint could not see any of
   it, because `ui` is a property."
  "ui")

(defn- cljs-files
  "Every .cljs under `dir`, recursively."
  [dir]
  (reduce
   (fn [acc e]
     (let [p (path/join dir (.-name e))]
       (cond
         (.isDirectory e) (into acc (cljs-files p))
         (.endsWith (.-name e) ".cljs") (conj acc p)
         :else acc)))
   []
   (vec (fs/readdirSync dir #js {:withFileTypes true}))))

(defn- extensions
  "[{:name :dir :capabilities #{} :source \"…\"}] for every built-in extension
   that has a manifest."
  []
  (->> (vec (fs/readdirSync ext-root #js {:withFileTypes true}))
       (filter (fn [e] (.isDirectory e)))
       (keep (fn [e]
               (let [dir (path/join ext-root (.-name e))
                     mf  (path/join dir "extension.json")]
                 (when (fs/existsSync mf)
                   (let [json (js/JSON.parse (fs/readFileSync mf "utf8"))
                         caps (vec (or (aget json "capabilities") #js []))]
                     {:name (.-name e)
                      :dir  dir
                      :capabilities (set (map str caps))
                      :source (->> (cljs-files dir)
                                   (map (fn [f] (fs/readFileSync f "utf8")))
                                   (str/join "\n"))})))))
       vec))

(defn missing-ui-capability
  "Does `ext` touch `api.ui` without declaring the capability?"
  [ext]
  (when (and (not (contains? (:capabilities ext) ui-property-capability))
             (or (.includes (:source ext) "(.-ui api)")
                 (.includes (:source ext) "(.-ui api")))
    {:method "api.ui" :capability ui-property-capability}))

(defn missing-capabilities
  "Capabilities `ext` calls into but does not declare.

   Matches `(.method api` and `(.-method api)` — the two shapes the scoped API
   is reached through — so a method NAME appearing in a docstring or a comment
   does not count."
  [ext]
  (->> gated-methods
       (keep (fn [[method capability]]
               (when (and (not (contains? (:capabilities ext) capability))
                          (or (.includes (:source ext) (str "(." method " api"))
                              (.includes (:source ext) (str "(.-" method " api)"))))
                 {:method method :capability capability})))
       (concat (when-let [u (missing-ui-capability ext)] [u]))
       vec))

(describe "extension manifests declare what the code calls" (fn []

                                                              (it "finds the built-in extensions"
                                                                  (fn []
        ;; Guard the guard: a path typo would make every assertion below pass
        ;; vacuously, which is the classic way a lint test rots.
                                                                    (-> (expect (> (count (extensions)) 10)) (.toBe true))))

                                                              (it "declares every gated API it uses"
                                                                  (fn []
                                                                    (let [bad (->> (extensions)
                                                                                   (keep (fn [e]
                                                                                           (when-let [m (seq (missing-capabilities e))]
                                                                                             (str (:name e) " calls "
                                                                                                  (str/join ", " (map (fn [x]
                                                                                                                        (str (:method x) " (needs "
                                                                                                                             (:capability x) ")"))
                                                                                                                      m))))))
                                                                                   vec)]
          ;; Named in the message: a bare count tells you nothing at 4am.
                                                                      (-> (expect (str/join "; " bad)) (.toBe "")))))

                                                              (it "spec_driven declares messages, because the phase loop is follow-ups"
                                                                  (fn []
        ;; The regression that prompted this file. Pinned by name so a manifest
        ;; edit that drops it fails here rather than in a live run.
                                                                    (let [sd (first (filter (fn [e] (= "spec_driven" (:name e))) (extensions)))]
                                                                      (-> (expect (contains? (:capabilities sd) "messages")) (.toBe true)))))

                                                              (it "detects a missing capability when there is one"
                                                                  (fn []
        ;; The detector itself, against a synthetic extension — otherwise a
        ;; broken matcher reports a clean repo forever.
                                                                    (-> (expect (count (missing-capabilities
                                                                                        {:capabilities #{"events"}
                                                                                         :source "(.sendUserMessage api \"go\")"})))
                                                                        (.toBe 1))
                                                                    (-> (expect (count (missing-capabilities
                                                                                        {:capabilities #{"messages"}
                                                                                         :source "(.sendUserMessage api \"go\")"})))
                                                                        (.toBe 0))
        ;; …and does not fire on prose that merely mentions the name.
                                                                    (-> (expect (count (missing-capabilities
                                                                                        {:capabilities #{"events"}
                                                                                         :source ";; we could call sendUserMessage here"})))
                                                                        (.toBe 0))))

                                                              (it "detects an undeclared api.ui, which is a PROPERTY not a gated method"
                                                                  (fn []
        ;; Why three extensions shipped with silent UI: extension_scope hands
        ;; out `#js {:available false}` rather than a thrower, so the feature
        ;; simply stops working. A method-name table cannot see it.
                                                                    (-> (expect (:capability (missing-ui-capability
                                                                                              {:capabilities #{"commands"}
                                                                                               :source "(when (.-ui api) (.notify (.-ui api) \"hi\"))"})))
                                                                        (.toBe "ui"))
                                                                    (-> (expect (missing-ui-capability
                                                                                 {:capabilities #{"commands" "ui"}
                                                                                  :source "(when (.-ui api) (.notify (.-ui api) \"hi\"))"}))
                                                                        (.toBeNil))
                                                                    (-> (expect (missing-ui-capability
                                                                                 {:capabilities #{"commands"} :source ";; no ui here"}))
                                                                        (.toBeNil))))))
