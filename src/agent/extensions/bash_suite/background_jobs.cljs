(ns agent.extensions.bash-suite.background-jobs
  (:require ["ai" :refer [tool]]
            ["zod" :as z]
            [agent.extensions.bash-suite.shared :as shared]
            [agent.utils.sandbox :as sandbox]
            [clojure.string :as str]))

;; ── State ────────────────────────────────────────────────────

(def jobs (atom {}))
(def next-job-id (atom 0))

;; ── Ring buffer ──────────────────────────────────────────────

(defn- ring-push
  "Append a line to a ring buffer atom, capping at max-lines."
  [buf-atom line max-lines]
  (swap! buf-atom
    (fn [buf]
      (let [new-buf (conj buf line)]
        (if (> (count new-buf) max-lines)
          (vec (drop (- (count new-buf) max-lines) new-buf))
          new-buf)))))

;; ── Background spawning ──────────────────────────────────────

(defn- matches-bg-pattern?
  "Check if command matches any auto-detect background pattern (prefix match)."
  [cmd patterns]
  (let [cmd-str (str/trim (str cmd))]
    (some #(.startsWith cmd-str %) patterns)))

(defn- format-duration [ms]
  (let [seconds (js/Math.floor (/ ms 1000))
        minutes (js/Math.floor (/ seconds 60))
        secs    (mod seconds 60)]
    (if (> minutes 0)
      (str minutes "m " secs "s")
      (str secs "s"))))

(defn- start-background-job
  "Spawn a command in the background. Returns job info map."
  [cmd {:keys [argv env]} config]
  (let [id           (swap! next-job-id inc)
        max-lines    (:output-buffer-lines config)
        stdout-buf   (atom [])
        stderr-buf   (atom [])
        exit-code    (atom nil)
        proc         (js/Bun.spawn (clj->js argv)
                       (cond-> #js {:stdout "pipe" :stderr "pipe"}
                         env (doto (aset "env" env))))
        job          {:id id
                      :command (str cmd)
                      :process proc
                      :status (atom :running)
                      :started-at (js/Date.now)
                      :exit-code exit-code
                      :stdout-buffer stdout-buf
                      :stderr-buffer stderr-buf}]

    ;; Set up stdout reader
    (when (.-stdout proc)
      (let [reader (.getReader (.-stdout proc))]
        (letfn [(read-loop []
                  (-> (.read reader)
                      (.then (fn [result]
                               (when-not (.-done result)
                                 (let [text (.decode (js/TextDecoder.) (.-value result))]
                                   (doseq [line (.split text "\n")]
                                     (when-not (str/blank? line)
                                       (ring-push stdout-buf line max-lines))))
                                 (read-loop))))))]
          (read-loop))))

    ;; Set up stderr reader
    (when (.-stderr proc)
      (let [reader (.getReader (.-stderr proc))]
        (letfn [(read-loop []
                  (-> (.read reader)
                      (.then (fn [result]
                               (when-not (.-done result)
                                 (let [text (.decode (js/TextDecoder.) (.-value result))]
                                   (doseq [line (.split text "\n")]
                                     (when-not (str/blank? line)
                                       (ring-push stderr-buf line max-lines))))
                                 (read-loop))))))]
          (read-loop))))

    ;; Monitor for exit
    (.then (.-exited proc)
      (fn [code]
        (reset! exit-code code)
        ;; a kill already said why it ended
        (swap! (:status job) #(if (= % :killed) % :exited))
        (swap! shared/suite-stats update-in [:background-jobs :jobs-completed] inc)))

    ;; Register the job
    (swap! jobs assoc id job)
    (swap! shared/suite-stats update-in [:background-jobs :jobs-started] inc)
    job))

;; ── Tools ────────────────────────────────────────────────────

(defn jobs-table
  "Every background job as a table, or \"No background jobs.\". Shared by the
   model's list_jobs tool and the user's /jobs."
  []
  (let [all-jobs @jobs]
    (if (empty? all-jobs)
      "No background jobs."
      (let [header "ID | Command | Status | Duration"
            sep    "---|---------|--------|--------"
            rows   (map (fn [[id job]]
                          (let [status  @(:status job)
                                dur     (format-duration (- (js/Date.now) (:started-at job)))
                                exit-str (when (= status :exited)
                                           (str " (exit: " @(:exit-code job) ")"))]
                            (str id " | " (:command job) " | "
                                 (str status) (or exit-str "") " | " dur)))
                        all-jobs)]
        (str/join "\n" (into [header sep] rows))))))

(defn kill-job!
  "Kill job `job-id`; a sentence saying what happened. Shared by kill_job and
   /jobs kill."
  [job-id]
  (let [job (get @jobs job-id)]
    (cond
      (not job) (str "No job found with ID: " job-id)
      (not= @(:status job) :running)
      (str "Job #" job-id " is not running (status: " (str @(:status job)) ")")
      :else (do (.kill (:process job))
                (reset! (:status job) :killed)
                (swap! shared/suite-stats update-in [:background-jobs :jobs-killed] inc)
                (str "Job #" job-id " killed: " (:command job))))))

(defn job-output
  "Recent stdout/stderr of `job-id`, `lines` from each."
  [job-id lines]
  (if-let [job (get @jobs job-id)]
    (let [stdout (vec (take-last lines @(:stdout-buffer job)))
          stderr (vec (take-last lines @(:stderr-buffer job)))
          parts  (cond-> []
                   (seq stdout) (conj (str "=== stdout ===\n" (str/join "\n" stdout)))
                   (seq stderr) (conj (str "=== stderr ===\n" (str/join "\n" stderr))))]
      (if (empty? parts)
        (str "Job #" job-id " has no output yet.")
        (str/join "\n\n" parts)))
    (str "No job found with ID: " job-id)))

(defn jobs-command
  "/jobs [output <id> | kill <id>] → text. Pure over `args` and the registry.
   The jobs were the model's alone: a dev server it started could be seen and
   stopped only by asking the model to do it."
  [args]
  (let [[sub id-str] (vec args)
        id (when id-str (js/parseInt id-str 10))]
    (cond
      (empty? args)                                  (jobs-table)
      (and (= sub "kill") (js/Number.isInteger id))   (kill-job! id)
      (and (= sub "output") (js/Number.isInteger id)) (job-output id 30)
      :else "Usage: /jobs | /jobs output <id> | /jobs kill <id>")))

(defn- make-list-jobs-tool []
  (tool
    #js {:safety #js {:read-only? true :category "shell"}
         :description "List all background jobs with their status and duration."
         :inputSchema (.object z #js {})
         :execute (fn [_args] (jobs-table))}))

(defn- make-job-output-tool []
  (tool
    #js {:safety #js {:read-only? true :category "shell"}
         :description "Get recent output from a background job."
         :inputSchema (.object z
                        #js {:job_id (-> (.number z) (.describe "Job ID"))
                             :lines  (-> (.number z) (.optional)
                                         (.describe "Number of recent lines (default: 50)"))})
         :execute (fn [args] (job-output (.-job_id args) (or (.-lines args) 50)))}))

(defn- make-kill-job-tool []
  (tool
    #js {;; Kills a process: "exec" for the permission gate, like bash itself.
         :safety #js {:destructive? true :capabilities #js ["shell"] :category "shell"}
         :description "Kill a background job."
         :inputSchema (.object z
                        #js {:job_id (-> (.number z) (.describe "Job ID to kill"))
                             :signal (-> (.string z) (.optional)
                                         (.describe "Signal to send (default: SIGTERM)"))})
         :execute (fn [args] (kill-job! (.-job_id args)))}))

;; ── Extension activation ─────────────────────────────────────

(defn activate [api]
  (let [config (shared/load-config)
        bg-cfg (:background-jobs config)]

    ;; Middleware enter phase to detect and background long-running commands
    (.addMiddleware api
      #js {:name  "bash-suite/background-jobs"
           :enter (fn [ctx]
                    (let [tool-name (aget ctx "tool-name")]
                      (if (and (:enabled bg-cfg)
                               (shared/is-bash-tool? tool-name)
                               (not (.-cancelled ctx)))
                        (let [args    (.-args ctx)
                              cmd     (.-command args)
                              running (count (filter (fn [[_ j]] (= @(:status j) :running))
                                                    @jobs))]
                          (if (and (matches-bg-pattern? cmd (:auto-detect-patterns bg-cfg))
                                   (< running (:max-concurrent bg-cfg)))
                            ;; Start background job
                            ;; Swap the executor, not the gates: starting it here
                            ;; ran the command before the denylist, the hooks and
                            ;; the permission ask, all of which come later in the
                            ;; chain. The job starts where bash would have run,
                            ;; with the args those gates settled on.
                            (do (aset ctx "tool"
                                      #js {:execute
                                           (fn [args _]
                                             (let [c (str (:command args))]
                                               ;; as bash would: out of the sandbox only
                                               ;; on the gate's approval
                                               (.then (sandbox/wrap-call c (:unsandboxed args))
                                                      (fn [w]
                                                        (let [job (start-background-job c w bg-cfg)]
                                                          ;; a timeout the model asked for still holds
                                                          (when-let [t (:timeout args)]
                                                            (js/setTimeout #(when (= @(:status job) :running)
                                                                              (kill-job! (:id job)))
                                                                           t))
                                                          (str "Started background job #" (:id job) ": `"
                                                               (:command job) "`\n"
                                                               "Use list_jobs to see status, job_output to get output, "
                                                               "kill_job to stop."))))))})
                                ctx)
                            ctx))
                        ctx)))})

    ;; Register tools
    (.registerTool api "list_jobs" (make-list-jobs-tool))
    (.registerTool api "job_output" (make-job-output-tool))
    (.registerTool api "kill_job" (make-kill-job-tool))
    (.registerCommand api "jobs"
                      #js {:description "Background jobs: list, `output <id>`, `kill <id>`"
                           :handler (fn [args ctx]
                                      (let [text (jobs-command (vec (or args [])))]
                                        (when (and ctx (.-ui ctx) (.-available (.-ui ctx)))
                                          (.notify (.-ui ctx) text "info"))
                                        text))})

    ;; Process exit cleanup
    (let [cleanup-fn (fn []
                       (doseq [[_ job] @jobs]
                         (when (= @(:status job) :running)
                           (try (.kill (:process job))
                             (catch :default _e nil)))))]
      (.on js/process "exit" cleanup-fn)

      ;; Return deactivator
      (fn []
        (.removeMiddleware api "bash-suite/background-jobs")
        (.unregisterTool api "list_jobs")
        (.unregisterTool api "job_output")
        (.unregisterTool api "kill_job")
        (.unregisterCommand api "jobs")
        ;; Kill running jobs
        (cleanup-fn)
        (.removeListener js/process "exit" cleanup-fn)
        (reset! jobs {})
        (reset! next-job-id 0)))))
