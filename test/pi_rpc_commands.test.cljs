(ns pi-rpc-commands.test
  "The pi RPC commands past the Emacs frontend's set, as pi 1.2's rpc-types
   define their responses: dispositions on prompt/steer/follow_up, models,
   thinking levels, queue modes, stats, compaction setting."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.modes.pi-rpc :as pi]))

(defn- make-agent []
  (let [cfg       #js {:model "m1"}
        overrides (atom {})
        switched  (atom [])
        agent     {:config         cfg
                   :model-registry {:context-window (fn [_] 200000) :get (fn [_] nil)}
                   :provider-registry {:list (fn [] {"p" {:models [{:id "m1"} {:id "m2" :name "Model 2"}]}})}
                   :thinking-level (atom "off")
                   :state          (atom {:messages [{:role "user" :content "hi"}
                                                     {:role "assistant" :content "yo"}]
                                          :total-input-tokens 100 :total-output-tokens 20
                                          :total-cache-read-tokens 50 :total-cost 0.5})
                   :steer-queue    (atom [])
                   :follow-queue   (atom [])
                   :settings       {:get (fn [] (merge {:compaction {:enabled true :threshold 0.8}} @overrides))
                                    :set-override (fn [k v] (swap! overrides assoc k v))}
                   :abort-controller (atom (js/AbortController.))
                   :commands       (atom {})
                   :__switched     switched}]
    (aset cfg "active-provider-name" "p")
    ;; what cli.cljs installs; switching updates config like the real one
    (aset agent "extension_api"
          #js {:setModel (fn [spec]
                           (swap! switched conj spec)
                           (set! (.-model cfg) (second (.split spec "/"))))})
    agent))

(defn- capture [f]
  (let [lines (atom [])
        orig  js/console.log]
    (set! js/console.log (fn [s] (swap! lines conj s)))
    (-> (js/Promise.resolve (f))
        (.finally (fn [] (set! js/console.log orig)))
        (.then (fn [_] (mapv #(js/JSON.parse %) @lines))))))

(defn- call [agent st cmd]
  (-> (capture (fn [] (pi/handle-line agent st (js/JSON.stringify (clj->js cmd)))))
      (.then (fn [out] (first (filter #(= "response" (.-type %)) out))))))

(describe "pi-rpc commands"
          (fn []
            (it "steer and follow_up while running answer queued and fill their queues"
                ^:async (fn []
                          (let [a  (make-agent)
                                st (pi/new-st)]
                            (reset! (:running? st) true)
                            (let [s (js-await (call a st {:type "steer" :message "left"}))
                                  f (js-await (call a st {:type "follow_up" :message "then"}))
                                  p (js-await (call a st {:type "prompt" :message "now" :streamingBehavior "steer"}))]
                              (-> (expect (.. s -data -disposition)) (.toBe "queued"))
                              (-> (expect (.. f -data -disposition)) (.toBe "queued"))
                              (-> (expect (.. p -data -disposition)) (.toBe "queued"))
                              (-> (expect (count @(:steer-queue a))) (.toBe 2))
                              (-> (expect (count @(:follow-queue a))) (.toBe 1))))))

            (it "get_available_models lists the catalogue in pi's Model shape"
                ^:async (fn []
                          (let [r  (js-await (call (make-agent) (pi/new-st) {:type "get_available_models"}))
                                ms (.. r -data -models)]
                            (-> (expect (mapv #(.-id %) ms)) (.toEqual #js ["m1" "m2"]))
                            (-> (expect (.-provider (aget ms 1))) (.toBe "p"))
                            (-> (expect (.-contextWindow (aget ms 1))) (.toBe 200000)))))

            (it "cycle_model switches to the next model through setModel"
                ^:async (fn []
                          (let [a (make-agent)
                                r (js-await (call a (pi/new-st) {:type "cycle_model"}))]
                            (-> (expect (vec @(:__switched a))) (.toEqual #js ["p/m2"]))
                            (-> (expect (.. r -data -model -id)) (.toBe "m2")))))

            (it "set_model goes through setModel with provider/id"
                ^:async (fn []
                          (let [a (make-agent)]
                            (js-await (call a (pi/new-st) {:type "set_model" :provider "p" :modelId "m2"}))
                            (-> (expect (vec @(:__switched a))) (.toEqual #js ["p/m2"])))))

            (it "set_model without a provider leaves the choice to the registry path"
                ^:async (fn []
                          (let [a (make-agent)]
                            (js-await (call a (pi/new-st) {:type "set_model" :modelId "m2"}))
                            (-> (expect (count @(:__switched a))) (.toBe 0))
                            (-> (expect (.-model (:config a))) (.toBe "m2")))))

            (it "queue modes accept only what the loop does"
                ^:async (fn []
                          (let [a (make-agent) st (pi/new-st)]
                            (-> (expect (.-success (js-await (call a st {:type "set_steering_mode" :mode "all"})))) (.toBe true))
                            (-> (expect (.-success (js-await (call a st {:type "set_steering_mode" :mode "one-at-a-time"})))) (.toBe false))
                            (-> (expect (.-success (js-await (call a st {:type "set_follow_up_mode" :mode "one-at-a-time"})))) (.toBe true))
                            (-> (expect (.-success (js-await (call a st {:type "set_auto_retry" :enabled false})))) (.toBe false)))))

            (it "set_auto_compaction overrides the setting and get_state reports it"
                ^:async (fn []
                          (let [a (make-agent) st (pi/new-st)]
                            (js-await (call a st {:type "set_auto_compaction" :enabled false}))
                            (let [r (js-await (call a st {:type "get_state"}))]
                              (-> (expect (.. r -data -autoCompactionEnabled)) (.toBe false))
                              (-> (expect (.. r -data -steeringMode)) (.toBe "all"))))))

            (it "get_state's sessionId is the session file's name"
                ^:async (fn []
                          (let [a (assoc (make-agent) :session (atom {:get-file-path (fn [] "/x/2026-10-26_abc.jsonl")
                                                                      :get-session-name (fn [] nil)}))
                                r (js-await (call a (pi/new-st) {:type "get_state"}))]
                            (-> (expect (.. r -data -sessionId)) (.toBe "2026-10-26_abc")))))

            (it "get_session_stats reports the running totals"
                ^:async (fn []
                          (let [r (js-await (call (make-agent) (pi/new-st) {:type "get_session_stats"}))
                                d (.-data r)]
                            (-> (expect (.. d -tokens -input)) (.toBe 100))
                            (-> (expect (.. d -tokens -cacheRead)) (.toBe 50))
                            (-> (expect (.-cost d)) (.toBe 0.5))
                            (-> (expect (.-userMessages d)) (.toBe 1)))))

            (it "get_available_thinking_levels lists nyma's levels"
                ^:async (fn []
                          (let [r (js-await (call (make-agent) (pi/new-st) {:type "get_available_thinking_levels"}))]
                            (-> (expect (.. r -data -levels)) (.toContain "high")))))))

(describe "extension ui over RPC"
          (fn []
            (it "select, input and confirm round-trip through extension_ui_request"
                ^:async (fn []
                          (let [st   (pi/new-st)
                                ui   (pi/rpc-ui st)
                                lines (atom [])
                                orig js/console.log]
                            (set! js/console.log (fn [s] (swap! lines conj (js/JSON.parse s))))
                            (let [sel (.select ui "Pick" #js ["a" "b"] nil)
                                  inp (.input ui "Name" "x" nil)
                                  cf  (.confirm ui "Sure?" nil)
                                  [r1 r2 r3] @lines]
                              (.notify ui "careful" "warn")
                              (set! js/console.log orig)
                              (-> (expect (.-method r1)) (.toBe "select"))
                              (-> (expect (.-options r1)) (.toEqual #js ["a" "b"]))
                              (-> (expect (.-placeholder r2)) (.toBe "x"))
                              (-> (expect (.-notifyType (nth @lines 3))) (.toBe "warning"))
                              (js-await (pi/handle-line {} st (js/JSON.stringify #js {:type "extension_ui_response" :id (.-id r1) :value "b"})))
                              (js-await (pi/handle-line {} st (js/JSON.stringify #js {:type "extension_ui_response" :id (.-id r2) :cancelled true})))
                              (js-await (pi/handle-line {} st (js/JSON.stringify #js {:type "extension_ui_response" :id (.-id r3) :confirmed true})))
                              (-> (expect (js-await sel)) (.toBe "b"))
                              (-> (expect (nil? (js-await inp))) (.toBe true))
                              (-> (expect (js-await cf)) (.toBe true))
                              (-> (expect (count @(:pending-ui st))) (.toBe 0))))))))
