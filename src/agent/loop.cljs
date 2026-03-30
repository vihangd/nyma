(ns agent.loop
  (:require ["ai" :refer [streamText]]
            [agent.context :refer [build-context get-active-tools]]))

(defn- event-type [chunk]
  (case (.-type chunk)
    "text-delta"        "message_update"
    "tool-call"         "tool_call"
    "tool-result"       "tool_result"
    "step-finish"       "turn_end"
    "finish"            "agent_end"
    "message_update"))

(defn- inject-steer-messages!
  "Move steer queue messages into agent state between tool steps."
  [agent]
  (let [queued @(:steer-queue agent)]
    (when (seq queued)
      (swap! (:state agent) update :messages into queued)
      (reset! (:steer-queue agent) []))))

(defn ^:async run
  "Execute the agent loop. Yields events via the event bus.
   Returns when the LLM stops calling tools and all queues are empty."
  [agent user-message]
  (let [{:keys [events config state tool-registry]} agent]

    ;; Append user message
    (swap! state update :messages conj
      {:role "user" :content user-message})

    ((:emit events) "agent_start" {})

    ;; Main loop — re-enters for follow-up messages
    (loop []
      (let [messages (build-context agent)
            tools    (get-active-tools agent)]

        ((:emit events) "turn_start" {})

        ;; === AI SDK does everything here ===
        (let [result (js-await
                       (streamText
                         #js {:model      (:model config)
                              :system     (:system-prompt config)
                              :messages   (clj->js messages)
                              :tools      (clj->js tools)
                              :maxSteps   (:max-steps config)
                              :onStepFinish
                              (fn [step]
                                ((:emit events) "turn_end" step)
                                (inject-steer-messages! agent))}))]

          ;; Stream events to subscribers
          (let [stream (.-fullStream result)]
            (loop []
              (let [chunk (js-await (.next (js-iterator stream)))]
                (when-not (.-done chunk)
                  ((:emit events) (event-type (.-value chunk)) (.-value chunk))
                  (recur)))))

          ;; Capture final state
          (let [final-text (js-await (.-text result))
                usage      (js-await (.-usage result))]
            (swap! state update :messages conj
              {:role "assistant" :content final-text})
            ((:emit events) "agent_end" {:text final-text :usage usage}))

          ;; Process follow-up queue
          (when-let [next (first @(:follow-queue agent))]
            (swap! (:follow-queue agent) rest)
            (swap! state update :messages conj
              {:role "user" :content (:content next)})
            (recur)))))))

(defn steer
  "Queue a steering message — delivered after current tool execution."
  [agent message]
  (swap! (:steer-queue agent) conj message))

(defn follow-up
  "Queue a follow-up message — delivered after agent finishes all work."
  [agent message]
  (swap! (:follow-queue agent) conj message))
