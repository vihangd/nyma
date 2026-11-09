(ns background-jobs-gate-test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.events :refer [create-event-bus]]
            [agent.middleware :refer [create-pipeline]]
            [agent.extensions.bash-suite.background-jobs :as bg]))

(defn ^:async run [decision & [extra]]
  (let [d      (fs/mkdtempSync (path/join (os/tmpdir) "nyma-bg-"))
        marker (path/join d "ran")
        events (create-event-bus)
        pl     (create-pipeline events)
        mw     (atom nil)
        noop   (fn [& _] nil)
        stop   (bg/activate #js {:addMiddleware #(reset! mw %) :removeMiddleware noop :registerTool noop :registerCommand noop :unregisterTool noop :unregisterCommand noop})]
    ((:on events) "permission_request" (fn [_] #js {:decision decision}))
    ((:add pl) {:name :bg :enter (.-enter @mw)})
    (try
      (let [ctx (js-await ((:execute pl) "bash" #js {:execute (fn [_] "foreground")}
                                         (merge {:command (str "nodemon nyma-test-none.js; touch " marker)} extra)))]
        (js-await (js/Promise. (fn [r] (js/setTimeout r 800))))
        {:ran (fs/existsSync marker) :cancelled (boolean (:cancelled ctx)) :result (str (:result ctx))
         :statuses (mapv (fn [[_ j]] (str @(:status j))) @bg/jobs)})
      (finally
        (when (fn? stop) (stop))
        (fs/rmSync d #js {:recursive true :force true})))))

(defn ^:async test-gate []
  (let [denied  (js-await (run "deny"))
        allowed (js-await (run "allow"))]
    ;; the gate comes first: a denied command never starts in the background
    (-> (expect #js [(:ran denied) (:cancelled denied)]) (.toEqual #js [false true]))
    (-> (expect (:result allowed)) (.toMatch #"Started background job"))))

(defn ^:async test-timeout []
  ;; a timeout the model gave still ends the job
  (let [r (js-await (run "allow" {:command "nodemon nyma-test-none.js; sleep 30" :timeout 200}))]
    (-> (expect (clj->js (:statuses r))) (.toEqual #js ["killed"]))))

(describe "background jobs and the permission gate" (fn []
  (it "kills a job at the timeout the call asked for" test-timeout)
  (it "starts a backgrounded command only after the gate allows it" test-gate)))
