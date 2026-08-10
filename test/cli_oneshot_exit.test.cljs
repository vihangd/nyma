(ns cli-oneshot-exit.test
  "A one-shot run must EXIT after it prints its answer.

   `main` used to return after dispatching to print/json mode and leave it
   there. The `exit` handler that performs cleanup only fires once the process
   is already exiting, and the process never exited — a loaded extension still
   held the event loop open. So `nyma -p` printed the answer and hung forever:
   harmless interactively, fatal in a script or CI step, which is the entire
   point of one-shot mode.

   Only the SUCCESS path was affected, which is why `cli_print_stdin.test` never
   caught it — those runs use an unresolvable model and die on the error path.
   So this test needs a turn that actually succeeds, and therefore a model
   server. It serves its own: a local OpenAI-compatible SSE endpoint, so the
   test needs no credentials and touches no network."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def ^:private reply-text "ok-from-fake-model")

(defn- chunk [obj]
  (str "data: " (js/JSON.stringify (clj->js obj)) "\n\n"))

(defn- sse-body []
  (str (chunk {:id "1" :object "chat.completion.chunk" :model "m1"
               :choices [{:index 0 :delta {:role "assistant"} :finish_reason nil}]})
       (chunk {:id "1" :object "chat.completion.chunk" :model "m1"
               :choices [{:index 0 :delta {:content reply-text} :finish_reason nil}]})
       (chunk {:id "1" :object "chat.completion.chunk" :model "m1"
               :choices [{:index 0 :delta {} :finish_reason "stop"}]})
       "data: [DONE]\n\n"))

(defn- start-fake-model-server []
  (js/Bun.serve
   #js {:port 0
        :fetch (fn [req]
                 (let [url (js/URL. (.-url req))]
                   (cond
                     (.endsWith (.-pathname url) "/models")
                     (js/Response.json #js {:object "list"
                                            :data #js [#js {:id "m1" :object "model"}]})

                     (.endsWith (.-pathname url) "/chat/completions")
                     (js/Response. (sse-body)
                                   #js {:headers #js {"content-type" "text/event-stream"}})

                     :else (js/Response. "not found" #js {:status 404}))))}))

(defn- temp-home!
  "A HOME whose settings register the fake server as a local provider."
  [base-url]
  (let [dir  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-oneshot-"))
        nyma (path/join dir ".nyma")]
    (fs/mkdirSync nyma #js {:recursive true})
    (fs/writeFileSync
     (path/join nyma "settings.json")
     (js/JSON.stringify
      #js {"local-models" #js [#js {"name" "faketest"
                                    "baseUrl" base-url
                                    "models" #js [#js {"id" "m1" "ctx" 8192}]}]}))
    dir))

(defn ^:async run-one-shot
  "Spawn the real CLI and wait for it to exit. Returns {:exited :out}, or
   {:exited nil} when it had to be killed — which is the bug."
  [args timeout-ms]
  (let [server (start-fake-model-server)
        base   (str "http://localhost:" (.-port server) "/v1")
        home   (temp-home! base)]
    (try
      (let [cli  (path/resolve (js/process.cwd) "dist" "agent" "cli.mjs")
            proc (js/Bun.spawn
                  (clj->js (concat ["bun" cli] args
                                   ["--model" "faketest/m1" "--tools" "read" "--no-session"]))
                  ;; cwd is the temp dir, not the repo: settings are merged
                  ;; SHALLOWLY, so the repo's own .nyma/settings.json would
                  ;; replace `local-models` wholesale and hide the fake server.
                  #js {:cwd home
                       :stdin "pipe" :stdout "pipe" :stderr "pipe"
                       :env (js/Object.assign
                             #js {} js/process.env
                             #js {"HOME" home
                                  "NYMA_NO_MODEL_DISCOVERY" "1"})})]
        ;; Close stdin: print mode reads it whenever it isn't a TTY.
        (.end (.-stdin proc))
        (let [out-p  (js/Bun.readableStreamToText (.-stdout proc))
              timer  (js/Promise. (fn [res] (js/setTimeout #(res :timeout) timeout-ms)))
              raced  (js-await (js/Promise.race #js [(.-exited proc) timer]))]
          (if (= raced :timeout)
            (do (.kill proc) {:exited nil :out (js-await out-p)})
            {:exited raced :out (js-await out-p)})))
      (finally
        (.stop server true)))))

(defn ^:async test-print-mode-exits []
  (let [{:keys [exited out]} (js-await (run-one-shot ["-p" "say ok"] 90000))]
    ;; The answer must actually have been produced — otherwise "it exited"
    ;; would pass for a run that merely crashed.
    (-> (expect (.includes (str out) reply-text)) (.toBe true))
    ;; And then the process must be gone. nil means it had to be killed.
    (-> (expect exited) (.toBe 0))))

(defn ^:async test-json-output-exits []
  (let [{:keys [exited out]} (js-await
                              (run-one-shot ["-p" "say ok" "--output-format" "json"] 90000))]
    (-> (expect (.includes (str out) reply-text)) (.toBe true))
    (-> (expect exited) (.toBe 0))))

(defn ^:async test-json-mode-exits []
  (let [{:keys [exited out]} (js-await (run-one-shot ["--mode" "json" "say ok"] 90000))]
    (-> (expect (.includes (str out) reply-text)) (.toBe true))
    (-> (expect exited) (.toBe 0))))

(describe "one-shot modes exit after a successful turn"
          (fn []
            ;; Generous timeouts: each case spawns a CLI that loads every
            ;; built-in extension.
            (it "-p exits" test-print-mode-exits 120000)
            (it "-p --output-format json exits" test-json-output-exits 120000)
            (it "--mode json exits" test-json-mode-exits 120000)))
