(ns jobs-command-test
  "/jobs — the user can see and stop background jobs, not only the model."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            [agent.extensions.bash-suite.background-jobs :as bg]))

(defn- fake-job [id cmd status]
  {:id id :command cmd :started-at (- (js/Date.now) 61000)
   :status (atom status) :exit-code (atom nil)
   :stdout-buffer (atom ["ready on :3000"]) :stderr-buffer (atom [])
   :process #js {:kill (fn [] nil)}})

(afterEach (fn [] (reset! bg/jobs {})))

(describe "/jobs"
          (fn []
            (it "lists jobs, or says there are none"
                (fn []
                  (-> (expect (bg/jobs-command [])) (.toBe "No background jobs."))
                  (swap! bg/jobs assoc 1 (fake-job 1 "bun run dev" :running))
                  (let [t (bg/jobs-command [])]
                    (-> (expect t) (.toContain "bun run dev"))
                    (-> (expect t) (.toContain "running")))))

            (it "shows a job's output and kills it"
                (fn []
                  (swap! bg/jobs assoc 1 (fake-job 1 "bun run dev" :running))
                  (-> (expect (bg/jobs-command ["output" "1"])) (.toContain "ready on :3000"))
                  (-> (expect (bg/jobs-command ["kill" "1"])) (.toContain "Job #1 killed"))
                  (-> (expect (str @(:status (get @bg/jobs 1)))) (.toBe "killed"))
                  (-> (expect (bg/jobs-command ["kill" "1"])) (.toContain "not running"))))

            (it "explains itself on a bad argument"
                (fn []
                  (doseq [a [["kill"] ["kill" "x"] ["nope"]]]
                    (-> (expect (bg/jobs-command a)) (.toContain "Usage: /jobs")))))))
