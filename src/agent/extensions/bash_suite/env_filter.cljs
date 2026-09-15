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
      (fn [data]
        (when (and (:enabled env-config)
                   (shared/is-bash-tool? (.-name data))
                   (not (str/blank? preamble)))
          (let [args (.-args data)
                cmd  (or (.-command args) (aget args "command") "")]
            (swap! shared/suite-stats update :env-filter
              (fn [s] (-> s
                          (update :vars-stripped + (count (:strip-vars env-config)))
                          (update :commands-filtered inc))))
            ;; Carry every other argument through. This built a fresh
            ;; single-key object, and because before_tool_call REPLACES ctx args
            ;; wholesale (middleware.cljs) that silently dropped the bash
            ;; tool's `timeout` on every call — a timeout that never fires looks
            ;; exactly like one that works, until something hangs.
            #js {:args (shared/with-arg args "command" (str preamble cmd))})))
      80)

    (fn [] nil)))
