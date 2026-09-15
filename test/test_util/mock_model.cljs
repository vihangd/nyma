(ns test-util.mock-model
  "A scripted AI SDK language model for in-process runs.

   `scripted-model` streams the given text chunks as one assistant turn and
   records every prompt it was called with, so a test can drive `run` through
   the real loop and still inspect what reached the provider."
  (:require ["ai/test" :refer [MockLanguageModelV4 convertArrayToReadableStream]]))

(defn scripted-model
  "Model whose every call streams `chunks` (strings) then finishes.
   Returns {:model m :calls atom<[prompt-opts ...]>}."
  [chunks]
  (let [calls (atom [])
        parts (-> [#js {:type "text-start" :id "t1"}]
                  (into (map (fn [c] #js {:type "text-delta" :id "t1" :delta c}) chunks))
                  (conj #js {:type "text-end" :id "t1"}
                        #js {:type "finish" :finishReason "stop"
                             :usage #js {:inputTokens 1 :outputTokens 2 :totalTokens 3}}))
        model (new MockLanguageModelV4
                   #js {:doStream (fn [opts]
                                    (swap! calls conj opts)
                                    (js/Promise.resolve
                                     #js {:stream (convertArrayToReadableStream (clj->js parts))}))})]
    {:model model :calls calls}))

(defn system-text
  "The system message text of a recorded doStream call, or nil."
  [opts]
  (some (fn [m] (when (= (.-role m) "system") (.-content m)))
        (js/Array.from (or (.-prompt opts) #js []))))

(defn ^:async wait-for
  "Poll `pred` every 10 ms until truthy or `timeout-ms` elapses. Returns the
   final pred value so an assertion afterwards names what never happened."
  [pred & [timeout-ms]]
  (let [deadline (+ (js/Date.now) (or timeout-ms 3000))]
    (loop []
      (let [v (pred)]
        (if (or v (> (js/Date.now) deadline))
          v
          (do (js-await (js/Promise. (fn [res _] (js/setTimeout res 10))))
              (recur)))))))
