(ns gateway.pipelines
  "Auth and approval pipelines for the gateway.

   Both pipelines are sequences of async check functions that run in registration
   order. The first check that returns a deny short-circuits the rest.

   ─── Auth pipeline ────────────────────────────────────────────────────────
   Fires before a message is processed. Use it to:
     - Restrict which users/channels can talk to the bot
     - Enforce rate limits per user
     - Validate API keys / signatures on webhook payloads

   Check-fn signature:
     (fn [request-map]) → {:allow? true} | {:allow? false :reason str}
     May be async (return a Promise).

   request-map keys:
     :conversation-id  string
     :user-id          string?
     :channel-name     string
     :text             string
     :raw              any?    original platform event

   ─── Approval pipeline ────────────────────────────────────────────────────
   Fires before a tool call executes. Use it to:
     - Require human confirmation via a Slack button, Telegram inline keyboard, etc.
     - Block certain tools in certain contexts

   Check-fn signature:
     (fn [tool-map]) → {:allow? true} | {:allow? false :reason str}
     May be async (return a Promise).

   tool-map keys:
     :tool-name   string
     :args        map

   The approval pipeline integrates with gateway.middleware via
   `wire-approval-pipeline!` which registers a single composite check-fn
   on the agent's middleware pipeline.")

(defn create-pipeline
  "Create an ordered pipeline of async check functions.
   Returns a map with :add!, :remove!, :clear!, :run!"
  []
  (let [checks (atom [])]
    {:checks checks

     :add!
     (fn [check-fn]
       (swap! checks conj check-fn))

     :remove!
     (fn [check-fn]
       (swap! checks (fn [cs] (vec (remove #{check-fn} cs)))))

     :clear!
     (fn []
       (reset! checks []))

     :run!
     (fn [request]
       ;; Run checks sequentially. First deny wins.
       (reduce
        (fn [p check-fn]
          (.then p
                 (fn [result]
                   (if (not (:allow? result))
                     result   ;; already denied — skip remaining checks
                     ;; Call check-fn inside .then so sync throws route to .catch
                     ;; (Promise.resolve with a direct call only wraps async errors).
                     (.. (js/Promise.resolve nil)
                         (then (fn [_] (check-fn (clj->js request))))
                         (then (fn [r]
                                 (if (some? r)
                                   (if (or (.-allow? r) (:allow? r))
                                     {:allow? true}
                                     {:allow? false
                                      :reason (or (.-reason r) (:reason r)
                                                  "Denied by pipeline")})
                                   {:allow? true})))  ;; nil return = allow
                         (catch (fn [e]
                                  (js/console.error "[gateway] Pipeline check error:" e)
                                  {:allow? true}))))))) ;; check errors default to allow
        (js/Promise.resolve {:allow? true})
        @checks))}))

(defn create-auth-pipeline
  "Create a pipeline for authorizing inbound messages."
  []
  (create-pipeline))

(defn create-approval-pipeline
  "Create a pipeline for approving tool calls."
  []
  (create-pipeline))

(defn wire-approval-pipeline!
  "Register the approval pipeline as a check-fn on an agent middleware pipeline.
   The middleware pipeline is the map returned by agent.middleware/create-pipeline.
   Call this once after both the agent and gateway are created."
  [agent-middleware approval-pipeline]
  (let [check-fn (fn [tool-js]
                   (let [tool-map {:tool-name (.-toolName tool-js)
                                   :args      (js->clj (.-args tool-js) :keywordize-keys true)}]
                     (.. ((:run! approval-pipeline) tool-map)
                         (then (fn [result]
                                 (if (:allow? result)
                                   #js {:allow true}
                                   #js {:deny true :reason (:reason result)}))))))]
    ((:add-approval-check! agent-middleware) check-fn)
    ;; Return a cleanup fn to deregister when the gateway stops
    (fn [] ((:remove-approval-check! agent-middleware) check-fn))))

(defn ^:async run-auth
  "Convenience: run the auth pipeline for an inbound message map.
   Returns {:allow? bool :reason? str}."
  [auth-pipeline msg]
  (js-await ((:run! auth-pipeline) msg)))
