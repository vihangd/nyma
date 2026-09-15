(ns test-util.fake-model-server
  "A local OpenAI-compatible model server for driving the real CLI.

   Serves `/v1/models` and `/v1/chat/completions` on a random port, records
   every request body it receives, and answers each `/chat/completions` call
   from a scripted queue. Extracted from cli_oneshot_exit.test so the CLI
   end-to-end tests share one server instead of copying the SSE plumbing.

   Scripts (one map per request, popped in order; an empty queue answers with
   `default-reply`):
     {:text \"…\"}                        one assistant text turn
     {:tool-call {:name \"bash\" :args {…}}} one OpenAI function tool call
     {:fail-after n}                     n SSE chunks, then an in-stream error
                                         object and the connection ends
                                         (n = 0 → a plain HTTP 500 response)"
  (:require [test-util.sse :refer [sse-response]]
             ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def default-reply "ok-from-fake-model")

(defn- chunk [obj]
  (str "data: " (js/JSON.stringify (clj->js obj)) "\n\n"))

(defn- delta-chunk [delta finish]
  (chunk {:id "1" :object "chat.completion.chunk" :model "m1"
          :choices [{:index 0 :delta delta :finish_reason finish}]}))

(defn- final-chunk
  "The finish chunk carries usage so print mode's per-step `usage` line has
   something to report."
  [finish]
  (chunk {:id "1" :object "chat.completion.chunk" :model "m1"
          :choices [{:index 0 :delta {} :finish_reason finish}]
          :usage {:prompt_tokens 3 :completion_tokens 2 :total_tokens 5}}))

(defn text-chunks [text]
  [(delta-chunk {:role "assistant"} nil)
   (delta-chunk {:content text} nil)
   (final-chunk "stop")
   "data: [DONE]\n\n"])

(defn tool-call-chunks [{:keys [name args id]}]
  [(delta-chunk {:role "assistant"
                 :tool_calls [{:index 0 :id (or id "call_1") :type "function"
                               :function {:name name
                                          :arguments (js/JSON.stringify (clj->js args))}}]}
                nil)
   (final-chunk "tool_calls")
   "data: [DONE]\n\n"])

(defn- failing-stream-response
  "The first n text chunks, then the error object OpenAI-compatible servers
   emit when the upstream dies mid-response, then the connection ends with
   no [DONE]. The status is already 200 by then — a real failure looks
   exactly like this."
  [n]
  (sse-response (concat (take n (text-chunks "partial-"))
                        [(chunk {:error {:message "upstream died" :type "server_error"}})])))

(defn- script->response [script]
  (cond
    (nil? script)              (sse-response (text-chunks default-reply))
    (:text script)             (sse-response (text-chunks (:text script)))
    (:tool-call script)        (sse-response (tool-call-chunks (:tool-call script)))
    (some? (:fail-after script))
    (if (zero? (:fail-after script))
      (js/Response. "{\"error\":{\"message\":\"boom\"}}"
                    #js {:status 500 :headers #js {"content-type" "application/json"}})
      (failing-stream-response (:fail-after script)))
    :else (throw (js/Error. (str "fake-model-server: unknown script " (pr-str script))))))

(defn start!
  "Start the server. Returns
     {:base-url  http://localhost:<port>/v1
      :requests  atom of parsed /chat/completions bodies, in order
      :queue     atom of scripts (see ns doc); `script!` appends to it
      :script!   (fn [& scripts]) — enqueue responses
      :reset!    clear requests and queue
      :stop      (fn []) — stop the server}"
  []
  (let [requests (atom [])
        queue    (atom [])
        server
        (js/Bun.serve
         #js {:port 0
              :fetch (fn [req]
                       (let [url (js/URL. (.-url req))]
                         (cond
                           (.endsWith (.-pathname url) "/models")
                           (js/Response.json #js {:object "list"
                                                  :data #js [#js {:id "m1" :object "model"}]})

                           (.endsWith (.-pathname url) "/chat/completions")
                           (-> (.json req)
                               (.then (fn [body]
                                        (swap! requests conj body)
                                        (let [script (first @queue)]
                                          (swap! queue #(vec (rest %)))
                                          (script->response script)))))

                           :else (js/Response. "not found" #js {:status 404}))))})]
    {:server   server
     :base-url (str "http://localhost:" (.-port server) "/v1")
     :requests requests
     :queue    queue
     :script!  (fn [& scripts] (swap! queue into scripts))
     :reset!   (fn [] (reset! requests []) (reset! queue []))
     :stop     (fn [] (.stop server true))}))

(defn temp-home!
  "A HOME whose settings register the fake server as local provider
   `faketest` with model `m1` — so `--model faketest/m1` resolves to it.
   Provider retries are off: the server is deterministic, so a retry can only
   turn a scripted failure into the default reply after a 2s backoff."
  [base-url]
  (let [dir  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-fake-"))
        nyma (path/join dir ".nyma")]
    (fs/mkdirSync nyma #js {:recursive true})
    (fs/writeFileSync
     (path/join nyma "settings.json")
     (js/JSON.stringify
      #js {"retry" #js {"enabled" false}
           "local-models" #js [#js {"name" "faketest"
                                    "baseUrl" base-url
                                    "models" #js [#js {"id" "m1" "ctx" 8192}]}]}))
    dir))

(defn request-messages
  "The `messages` array of the nth recorded request (plain JS objects —
   `(:role m)` reads them directly under Squint)."
  [server n]
  (vec (.-messages (nth @(:requests server) n))))
