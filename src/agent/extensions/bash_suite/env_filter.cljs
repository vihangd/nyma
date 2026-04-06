(ns agent.extensions.bash-suite.env-filter
  (:require [agent.extensions.bash-suite.shared :as shared]
            [clojure.string :as str]))

;; ── Preamble generation ──────────────────────────────────────

(defn build-preamble
  "Build an unset preamble string from a list of var names/patterns.
   Outputs: 'unset VAR1 VAR2 ... 2>/dev/null ; '"
  [strip-vars]
  (if (empty? strip-vars)
    ""
    (str "unset " (str/join " " strip-vars) " 2>/dev/null ; ")))

;; ── Extension activation ─────────────────────────────────────

(defn activate [api]
  (let [config     (shared/load-config)
        env-config (:env-filter config)
        preamble   (build-preamble (:strip-vars env-config))]

    (.on api "before_tool_call"
      (fn [evt-ctx]
        (when (and (:enabled env-config)
                   (shared/is-bash-tool? (.-toolName evt-ctx))
                   (not (.-cancelled evt-ctx))
                   (not (str/blank? preamble)))
          (let [input (.-input evt-ctx)
                cmd   (.-command input)]
            (aset input "command" (str preamble cmd))
            (swap! shared/suite-stats update :env-filter
              (fn [s] (-> s
                          (update :vars-stripped + (count (:strip-vars env-config)))
                          (update :commands-filtered inc)))))))
      80)

    (fn [] nil)))
