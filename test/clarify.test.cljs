(ns clarify-test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.model-roles.features.clarify :as clarify]
            [agent.utils.decide :as decide]))

(defn ^:async run
  "The before_agent_start result after `submitted` was typed, with Jev
   answering `p` and `judge` as the settings."
  [judge p submitted & [starts]]
  (let [handlers (atom {})
        api      #js {:on (fn [ev f] (swap! handlers assoc ev f)) :off (fn [_ _] nil)}
        orig     js/globalThis.fetch
        key      (.. js/process -env -TYPESAFE_API_KEY)
        calls    (atom 0)]
    (aset (.-env js/process) "TYPESAFE_API_KEY" "k")
    (set! js/globalThis.fetch
          (fn [_ _]
            (swap! calls inc)
            (js/Promise.resolve #js {:ok true :json (fn [] (js/Promise.resolve #js {:answers #js {:vague #js {:type "noul" :noul p}}}))})))
    (decide/configure! judge)
    (decide/reset-session!)
    (try
      (clarify/activate api)
      (when submitted ((get @handlers "input_submit") #js {:text submitted}))
      (let [rs (loop [n (or starts 1) acc []]
                 (if (zero? n) acc
                     (recur (dec n) (conj acc (js-await ((get @handlers "before_agent_start") #js {}))))))]
        {:results rs :calls @calls})
      (finally
        (set! js/globalThis.fetch orig)
        (if key (aset (.-env js/process) "TYPESAFE_API_KEY" key) (js-delete (.-env js/process) "TYPESAFE_API_KEY"))
        (decide/configure! nil)
        (decide/reset-session!)))))

(defn- added? [r] (boolean (some-> r (aget "volatile-additions") first)))

(defn ^:async test-clarify []
  (let [on {:judge {:clarify true}}
        vague "make the dashboard better please"]
    ;; vague and sure: the model is told to ask, once
    (let [{:keys [results]} (js-await (run on 0.9 vague 2))]
      (-> (expect (mapv added? results)) (.toEqual #js [true false])))
    ;; not sure enough
    (-> (expect (added? (first (:results (js-await (run on 0.5 vague)))))) (.toBe false))
    ;; off by default; a short reply and a command are never judged
    (doseq [[cfg text] [[{:judge {}} vague] [on "yes do it"] [on "/review the whole repo now"] [on nil]]]
      (-> (expect (:calls (js-await (run cfg 0.9 text)))) (.toBe 0)))))

(describe "clarify" (fn []
  (it "tells the model to ask on a typed, vague request only, when switched on" test-clarify)))
