(ns manifest-capabilities.test
  "Lints every bundled extension: any scoped-api method the code calls must be
   covered by a capability declared in extension.json. Un-declared calls hit a
   throwing gate stub at runtime — and call sites that wrap in try/catch turn
   that into a silent no-op (this is exactly how claude_hook_bridge's hook
   context injection was dropped in production)."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

;; Gated methods → required capability (from extension_scope.cljs).
(def ^:private method->cap
  {"on" "events" "off" "events" "emitGlobal" "events"
   "registerTool" "tools" "unregisterTool" "tools"
   "getActiveTools" "tools" "getAllTools" "tools" "getTool" "tools" "setActiveTools" "tools"
   "overrideTool" "tools-override" "unoverrideTool" "tools-override"
   "registerCommand" "commands" "unregisterCommand" "commands" "getCommands" "commands"
   "registerShortcut" "shortcuts" "unregisterShortcut" "shortcuts"
   "sendMessage" "messages" "sendUserMessage" "messages"
   "addMiddleware" "middleware" "removeMiddleware" "middleware"
   "exec" "exec" "spawn" "spawn"
   "appendEntry" "session" "setSessionName" "session" "getSessionName" "session"
   "registerBlockRenderer" "renderers" "unregisterBlockRenderer" "renderers"
   "registerToolRenderer" "renderers" "unregisterToolRenderer" "renderers"
   "registerStatusSegment" "ui" "unregisterStatusSegment" "ui"
   "registerCompletionProvider" "ui" "unregisterCompletionProvider" "ui"
   "registerMentionProvider" "ui" "unregisterMentionProvider" "ui"
   "registerProvider" "providers" "unregisterProvider" "providers"
   "setModel" "model" "getThinkingLevel" "model" "setThinkingLevel" "model" "resolveModel" "model"
   "registerContextProvider" "context" "unregisterContextProvider" "context" "getTokenBudget" "context"
   "registerFlag" "flags" "getFlag" "flags"
   "getState" "state" "dispatch" "state" "onStateChange" "state"})

(def ^:private ext-root "src/agent/extensions")

;; Matches `(.method api ...)` where the receiver symbol mentions api —
;; conservative: sub-module locals are consistently named `api`/`base-api`.
(def ^:private call-rx
  (js/RegExp. "\\(\\.([a-zA-Z]+)\\s+[a-z-]*api\\b" "g"))

(defn- cljs-files [dir]
  (mapcat (fn [entry]
            (let [p (path/join dir entry)]
              (if (.isDirectory (fs/statSync p))
                (cljs-files p)
                (when (.endsWith p ".cljs") [p]))))
          (fs/readdirSync dir)))

(defn- required-caps [ext-dir]
  (reduce (fn [acc f]
            (let [content (fs/readFileSync f "utf8")]
              (loop [acc acc]
                (if-let [m (.exec call-rx content)]
                  (recur (if-let [cap (get method->cap (aget m 1))]
                           (update acc cap (fnil conj #{}) (path/basename f))
                           acc))
                  acc))))
          {}
          (cljs-files ext-dir)))

(describe "extension manifest capability coverage" (fn []

                                                     (it "actually finds extensions to check"
                                                         (fn []
        ;; Guard the guard. This asserted a joined violation string is "" with
        ;; no floor check, so a bad ext-root would certify a clean repo forever.
        ;; Its sibling extension_capability_lint has had this check from the
        ;; start; this one did not.
                                                           (-> (expect (> (count (vec (fs/readdirSync ext-root))) 10))
                                                               (.toBe true))))

                                                     (it "every gated api call is covered by a declared capability"
                                                         (fn []
                                                           (let [violations (atom [])]
                                                             (doseq [entry (fs/readdirSync ext-root)]
                                                               (let [ext-dir  (path/join ext-root entry)
                                                                     manifest (path/join ext-dir "extension.json")]
                                                                 (when (and (.isDirectory (fs/statSync ext-dir))
                                                                            (fs/existsSync manifest))
                                                                   (let [declared (set (vec (or (aget (js/JSON.parse (fs/readFileSync manifest "utf8"))
                                                                                                      "capabilities")
                                                                                                #js [])))]
                                                                     (doseq [[cap files] (required-caps ext-dir)]
                                                                       (when-not (contains? declared cap)
                                                                         (swap! violations conj
                                                                                (str entry ": uses \"" cap "\" (" (str/join ", " (sort files))
                                                                                     ") but manifest declares " (str/join "/" (sort declared))))))))))
                                                             (-> (expect (str/join "\n" (sort @violations))) (.toBe "")))))))
