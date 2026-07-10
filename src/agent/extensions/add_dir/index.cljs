(ns agent.extensions.add-dir
  "Multi-root context — `/add-dir <path>` registers extra project roots and
   injects them into the system prompt so the agent reads/greps across repos
   (SOTA: 'a repo boundary is a context wall'). Scoping is preserved because
   read/grep run on those paths as usual (rg honours .gitignore), so the SOTA
   'must scope or the agent drowns' requirement holds without extra machinery.

   Deeper auto-search (grep/glob defaulting to cwd+roots) is deferred; the
   injected roots let the agent target them explicitly today."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(defn ^:export default [api]
  (let [roots    (atom [])             ; session-scoped absolute paths
        handlers (atom [])

        add-agents-md
        (fn [root]
          (let [f (path/join root "AGENTS.md")]
            (when (fs/existsSync f)
              (str "\n  (has AGENTS.md)"))))

        on-before-start
        (fn [_data _ctx]
          (when (seq @roots)
            #js {:system-prompt-additions
                 #js [(str "# Additional project roots\n"
                           "Beyond the cwd, also read/grep these directories when relevant:\n"
                           (str/join "\n" (map (fn [r] (str "- " r (or (add-agents-md r) ""))) @roots)))]}))]

    (.on api "before_agent_start" on-before-start)
    (swap! handlers conj ["before_agent_start" on-before-start])

    (.registerCommand api "add-dir"
                      #js {:description "Add an extra project directory to the session (agent reads/greps it too). Usage: /add-dir <path> | /add-dir (list) | /add-dir remove <path>"
                           :handler
                           (fn [args ctx]
                             (let [notify (fn [m l] (when-let [ui (.-ui ctx)] (when (.-notify ui) (.notify ui m l))))
                                   sub    (some-> (first args) str)]
                               (cond
                                 (nil? sub)
                                 (notify (if (seq @roots)
                                           (str "Extra roots:\n" (str/join "\n" (map #(str "  " %) @roots)))
                                           "No extra roots. Usage: /add-dir <path>")
                                         "info")

                                 (= sub "remove")
                                 (let [p (some-> (second args) str)
                                       abs (when p (path/resolve p))]
                                   (swap! roots (fn [rs] (vec (remove #(= % abs) rs))))
                                   (notify (str "Removed root: " abs) "info"))

                                 :else
                                 (let [abs (path/resolve sub)]
                                   (cond
                                     (not (fs/existsSync abs)) (notify (str "No such directory: " abs) "error")
                                     (not (.isDirectory (fs/statSync abs))) (notify (str "Not a directory: " abs) "error")
                                     (some #(= % abs) @roots) (notify (str "Already added: " abs) "warning")
                                     :else (do (swap! roots conj abs)
                                               (notify (str "Added root: " abs " (fires on your next message)") "info")))))))})

    (fn []
      (.unregisterCommand api "add-dir")
      (doseq [[e h] @handlers] (.off api e h)))))
