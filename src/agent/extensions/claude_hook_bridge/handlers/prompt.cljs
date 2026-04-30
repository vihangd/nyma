(ns agent.extensions.claude-hook-bridge.handlers.prompt
  "Prompt handler — uses a small/fast LLM to evaluate the hook event.

   Per the CC spec:
     - The `prompt` field is a template; `$ARGUMENTS` is replaced with
       the JSON event payload before sending to the model.
     - Response is expected to be JSON with `decision: \"allow\"|\"block\"`
       (other fields per CC's response schema may be included).
     - Default timeout 30 seconds.
     - Default model: a fast eval model. We resolve via the agent's
       configured roles; falls back to whatever the active model is.

   We translate the LLM's raw text into the same envelope shape the
   command handler returns, so response/parse-one handles both."
  (:require ["ai" :refer [generateText]]))

(def default-timeout-ms 30000)

(defn- substitute-args [template event-json]
  (let [json-str (js/JSON.stringify (clj->js event-json) nil 2)]
    (.replaceAll (str template) "$ARGUMENTS" json-str)))

(defn- resolve-model
  "Pick a model spec for the prompt eval. Order of preference:
     1. spec's :model field (literal model id)
     2. agent's :fast role
     3. agent's active model
     4. nil (caller will fall back to non-blocking error)"
  [api spec-model]
  (or spec-model
      ;; Best-effort: ask the api for the active model.
      (try (.getActiveModel api) (catch :default _e nil))
      nil))

(defn ^:async run-prompt
  "Args:
     :api          extension api (used to resolve model)
     :prompt       string template (with $ARGUMENTS placeholder)
     :model        optional model id string
     :timeout-ms   int — default 30s
     :stdin-json   the event payload (substituted into $ARGUMENTS)
     :abort-signal AbortSignal — fires on agent abort

   Returns {:exit-code :stdout :stderr ...} envelope."
  [{:keys [api prompt model timeout-ms stdin-json abort-signal]}]
  (let [text     (substitute-args prompt stdin-json)
        m        (resolve-model api model)
        timeout  (or timeout-ms default-timeout-ms)]
    (if-not m
      (js/Promise.resolve
       {:exit-code 1 :stdout "" :stderr "[hook-bridge] prompt handler — no model available"
        :timed-out? false :aborted? false :error nil})
      (try
        (let [opts #js {:model    m
                        :messages #js [#js {:role "user" :content text}]}
              ;; Race against timeout / abort.
              timeout-promise
              (js/Promise. (fn [_resolve reject]
                             (js/setTimeout
                              (fn [] (reject (js/Error. "timeout")))
                              timeout)))
              abort-promise
              (when abort-signal
                (js/Promise. (fn [_resolve reject]
                               (.addEventListener abort-signal "abort"
                                                  (fn [] (reject (js/Error. "aborted")))))))
              gen-promise (generateText opts)
              resolved (js-await
                        (js/Promise.race
                         (clj->js (filter some? [gen-promise timeout-promise abort-promise]))))
              text-out (.-text resolved)]
          {:exit-code 0
           :stdout    (or text-out "")
           :stderr    ""
           :timed-out? false
           :aborted?   false
           :error     nil})
        (catch :default e
          (let [msg (or (.-message e) (str e))
                timeout? (= msg "timeout")
                aborted? (= msg "aborted")]
            {:exit-code 1
             :stdout    ""
             :stderr    (str "prompt handler: " msg)
             :timed-out? timeout?
             :aborted?   aborted?
             :error     e}))))))
