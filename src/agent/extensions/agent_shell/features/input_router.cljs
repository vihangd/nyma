(ns agent.extensions.agent-shell.features.input-router
  "Intercepts user input and forwards to the active ACP agent.
   Streams response chunks to the UI in real-time."
  (:require [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.acp.client :as client]
            [clojure.string :as str]))

(def ^:private prompt-id
  "Unique ID for the current prompt. Each new prompt increments this
   so append-chunk knows when to start a new assistant message."
  (atom 0))

(defn append-chunk
  "Append a text chunk to the current prompt's assistant message.
   Uses prompt-id to distinguish messages from different prompts."
  [prev text-delta current-prompt-id]
  (let [all      (vec prev)
        last-msg (last all)]
    (if (and (= (:role last-msg) "assistant")
             (= (:prompt-id last-msg) current-prompt-id))
      ;; Same prompt — append to existing message
      (conj (vec (butlast all))
            (update last-msg :content str text-delta))
      ;; New prompt or no assistant msg — create new message
      (conj all {:role "assistant"
                 :content (or text-delta "")
                 :prompt-id current-prompt-id}))))

(defn append-thought
  "Append a thinking chunk to the current prompt's thinking message.
   Uses prompt-id to distinguish messages from different prompts."
  [prev thought-text current-prompt-id]
  (let [all      (vec prev)
        last-msg (last all)]
    (if (and (= (:role last-msg) "thinking")
             (= (:prompt-id last-msg) current-prompt-id))
      (conj (vec (butlast all))
            (update last-msg :content str thought-text))
      (conj all {:role "thinking"
                 :content (or thought-text "")
                 :prompt-id current-prompt-id}))))

(defn append-plan
  "Replace or create a plan message for the current prompt."
  [prev plan-data current-prompt-id]
  (let [all       (vec prev)
        formatted (str/join "\n"
                    (mapv (fn [e]
                            (str (case (:status e)
                                   "done"   "✓"
                                   "active" "→"
                                   " ")
                                 " " (:content e)))
                          plan-data))
        plan-msg  {:role "plan" :content formatted :prompt-id current-prompt-id}]
    ;; Replace existing plan message or append new one
    (let [idx (some (fn [[i m]] (when (and (= (:role m) "plan")
                                           (= (:prompt-id m) current-prompt-id))
                                  i))
                    (map-indexed vector all))]
      (if idx
        (assoc all idx plan-msg)
        (conj all plan-msg)))))

(defn- clear-callbacks! []
  (reset! shared/stream-callback nil)
  (reset! shared/thought-callback nil)
  (reset! shared/plan-callback nil))

(defn- make-stream-handler
  "Create a streaming handler object.
   The user's prompt is prefixed inline on the first chunk."
  [conn agent-key text api user-text]
  (let [first?    (atom true)
        pid       (swap! prompt-id inc)]
    #js
     {:handle    true
      :streaming true
      :subscribe
      (fn [set-messages]
        ;; Wire text streaming callback
        (reset! shared/stream-callback
          (fn [text-delta]
            (let [delta (if (compare-and-set! first? true false)
                          (str "❯ " user-text "\n" text-delta)
                          text-delta)]
              (set-messages (fn [prev] (append-chunk prev delta pid))))))
        ;; Wire thinking callback (skip if thinking-renderer extension is active)
        (when-not (.getGlobalFlag api "thinking-renderer__active")
          (reset! shared/thought-callback
            (fn [thought-text]
              (set-messages (fn [prev] (append-thought prev thought-text pid))))))
        ;; Wire plan callback
        (reset! shared/plan-callback
          (fn [plan-data]
            (set-messages (fn [prev] (append-plan prev plan-data pid)))))
        (-> (client/send-prompt conn text)
            (.then
              (fn [result]
                (clear-callbacks!)
                (when @first?
                  (set-messages
                    (fn [prev]
                      (append-chunk prev
                        (str "❯ " user-text "\n(no response)") pid))))
                (when-let [usage (:usage result)]
                  (shared/update-agent-state! agent-key :turn-usage usage))))
            (.catch
              (fn [e]
                (clear-callbacks!)
                (when (and (.-ui api) (.-available (.-ui api)))
                  (.notify (.-ui api)
                    (str "ACP error: " (.-message e)) "error"))))))}))

(defn activate
  "Hook the 'input' event at high priority. When an agent is active:
   - Plain text → forwarded to ACP agent as a prompt (streamed)
   - //command  → forwarded to ACP agent as '/command' (streamed)
   - /command   → passed through to nyma's command handler"
  [api]
  (let [handler
        (fn [data _ctx]
          (let [text      (.-input data)
                agent-key @shared/active-agent]
            (when (and agent-key (seq text))
              (let [conn (get @shared/connections agent-key)]
                (cond
                  (.startsWith text "//")
                  (when conn
                    (make-stream-handler conn agent-key (.slice text 1) api text))

                  (.startsWith text "/")
                  nil

                  :else
                  (when conn
                    (make-stream-handler conn agent-key text api text)))))))]
    (.on api "input" handler 100)
    (fn [] (.off api "input" handler))))
