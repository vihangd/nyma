(ns agent.extensions.small-model.stream-rules
  "Correct a turn while it is still being written.

   `quality_monitor` watches for the same failures — repetition, invented
   tools, empty turns — but only once the turn is over, by which point the
   tokens are paid for and the bad text is in the transcript. oh-my-pi calls the
   during-the-turn version 'time-travelling stream rules': match the stream as
   it arrives, abort mid-token, inject a reminder, and re-run from the same
   point. The correction costs a retry instead of a whole wasted turn.

   nyma already had every moving part. `loop.cljs:449` runs the stream inside a
   retry loop bounded at 2 attempts, and a `stream_filter` handler that returns
   `{abort true, reason, inject [messages]}` makes it re-run with those messages
   appended. What was missing was anything that decides *when* to do it.

   So this is a table, not a behaviour: rules come from settings, and shipping
   none means nothing fires.

     {\"small-model\": {\"stream-rules\":
       {\"rules\": [{\"pattern\": \"(?i)as an ai\",
                   \"reminder\": \"Answer directly; skip the preamble.\"}]}}}

   Each rule fires at most once per turn. A rule that could fire on the text it
   injects would otherwise spend both retries re-triggering itself, and the
   retry budget is shared with everything else that can abort a stream."
  (:require [agent.debug :as d]))

(defn compile-rule
  "{:pattern :flags :reminder} → {:re :reminder :id}, or nil when the pattern
   does not compile. A bad regex in settings disables that rule and says so —
   it must not take the turn down with it."
  [idx r]
  (let [pattern  (:pattern r)
        flags    (or (get r "flags") (:flags r) "i")
        reminder (:reminder r)]
    (when (and pattern reminder)
      (try
        {:id       (str "rule-" idx)
         :re       (js/RegExp. (str pattern) (str flags))
         :reminder (str reminder)}
        (catch :default e
          (d/warn "[small-model/stream-rules] ignoring rule with an invalid pattern"
                  #js {:pattern (str pattern) :error (str e)})
          nil)))))

(defn compile-rules [rules]
  (vec (keep-indexed compile-rule (or rules []))))

(defn first-match
  "The first rule matching `text` that has not already fired, or nil."
  [rules fired text]
  (when (and (seq rules) (string? text) (seq text))
    (first (filter (fn [{:keys [id re]}]
                     (and (not (contains? fired id))
                          (.test re text)))
                   rules))))

(defn abort-result
  "The shape `stream_filter` must return to make loop.cljs retry.

   The reminder goes in as a `user` message: a `system` one would need
   allowSystemInMessages and reads as configuration rather than as feedback on
   what the model just did."
  [rule]
  #js {:abort  true
       :reason (str "stream-rule " (:id rule))
       :inject #js [#js {:role "user" :content (:reminder rule)}]})

(defn activate
  "Subscribe the rule table to `stream_filter`. Returns a cleanup fn."
  [api config]
  (let [cfg   (:stream-rules config)
        rules (compile-rules (:rules cfg))]
    (if (empty? rules)
      (fn [])
      (let [fired    (atom #{})
            ;; Per turn, not per stream: an aborted stream re-runs, and a rule
            ;; that fired must stay fired across that retry or it will spend
            ;; the whole budget on itself.
            on-turn  (fn [_] (reset! fired #{}) nil)
            on-delta (fn [data]
                       (let [text (str (or (.-delta data) ""))]
                         (when-let [rule (first-match rules @fired text)]
                           (swap! fired conj (:id rule))
                           (d/debug "[small-model/stream-rules] aborting on" (:id rule))
                           (abort-result rule))))]
        (.on api "turn_start" on-turn)
        (.on api "stream_filter" on-delta)
        (fn []
          (try (.off api "turn_start" on-turn) (catch :default _ nil))
          (try (.off api "stream_filter" on-delta) (catch :default _ nil)))))))
