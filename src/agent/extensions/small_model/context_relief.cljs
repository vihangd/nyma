(ns agent.extensions.small-model.context-relief
  "Emergency context relief — recover from 'Prompt too long' errors.

   When a context-length error fires, the loop has already exited and
   every subsequent message will hit the same wall. This module hooks
   provider_error, detects length errors, prunes the message list
   in-place, and returns {:retry true} so the loop retries automatically.

   Strategy: keep the first user message (task anchor) + the most recent
   N messages (default 20), drop everything in between. A synthetic
   'compaction' notice is inserted at the cut point so the model knows
   context was pruned.

   This is a panic prune, not a semantic summary. It fires once per
   error — if the pruned context is still too large the error surfaces
   normally rather than looping.
  "
  (:require [clojure.string :as str]))

;; ── Error detection ───────────────────────────────────────────────

(defn- context-length-error? [msg]
  (let [m (str/lower-case (str msg))]
    (or (str/includes? m "prompt too long")
        (str/includes? m "context window")
        (str/includes? m "context_length")
        (str/includes? m "context length")
        (str/includes? m "max_tokens")
        (str/includes? m "tokens exceeds")
        (str/includes? m "token limit")
        (str/includes? m "exceeds max"))))

;; ── Pruning ───────────────────────────────────────────────────────

(defn- prune-messages
  "Keep the first `keep-head` messages (task anchor) and the last
   `keep-tail` messages (recent context), dropping the middle.
   Inserts a compaction notice at the cut point."
  [messages keep-head keep-tail]
  (let [total (count messages)]
    (if (<= total (+ keep-head keep-tail))
      messages  ; already short enough, don't prune
      (let [head     (subvec messages 0 keep-head)
            tail     (subvec messages (- total keep-tail))
            dropped  (- total keep-head keep-tail)
            notice   {:role    "user"
                      :content (str "[context-relief: " dropped
                                    " messages dropped due to context-length limit. "
                                    "Continuing from recent context.]")}]
        (vec (concat head [notice] tail))))))

;; ── Activation ───────────────────────────────────────────────────

(defn activate
  "Wire provider_error context-relief hook. Returns a cleanup fn."
  [api _config]
  (let [fired    (atom false)  ; only prune once per session to avoid loops
        handlers (atom [])

        on-error
        (fn [data _ctx]
          (let [msg (str (or (.-message data) ""))]
            (when (and (context-length-error? msg) (not @fired))
              (reset! fired true)
              ;; Read messages directly from state atom
              (when-let [state-atom (.-__state_atom api)]
                (let [msgs    (:messages @state-atom)
                      pruned  (prune-messages (vec msgs) 2 20)]
                  (when (< (count pruned) (count msgs))
                    (swap! state-atom assoc :messages pruned)
                    (js/console.warn
                     (str "[small-model/context-relief] Pruned "
                          (- (count msgs) (count pruned))
                          " messages to recover from context-length error. Retrying."))
                    ;; Signal the loop to retry with pruned context
                    #js {:retry true}))))))]

    (.on api "provider_error" on-error)
    (swap! handlers conj ["provider_error" on-error])

    (fn []
      (doseq [[event handler] @handlers]
        (.off api event handler)))))
