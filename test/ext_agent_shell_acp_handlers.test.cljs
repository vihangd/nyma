(ns ext-agent-shell-acp-handlers.test
  "Unit tests for ACP reverse-request handlers (handlers.cljs).

   Tests pin the response shapes that were fixed in earlier sessions to prevent
   regressions:
     - handle-permission-request nested outcome shape
     - handle-permission-request auto-approve preference order
     - handle-fs-write path-traversal rejection
     - handle-terminal-output truncated:false invariant
     - handle-elicitation decline/cancel paths"
  (:require ["bun:test" :refer [describe it expect]]
            ["node:path" :as path]
            ["node:os" :as os]
            ["node:fs" :as fs]
            [agent.extensions.agent-shell.acp.handlers :as handlers]))

;;; ─── Test helpers ────────────────────────────────────────────

(defn- make-conn
  "Build a minimal mock connection that captures all JSON-RPC responses."
  ([] (make-conn {}))
  ([opts]
   (let [writes (atom [])
         stdin  #js {:write (fn [data] (swap! writes conj data) nil)
                     :flush (fn [] nil)}]
     (merge {:stdin   stdin
             :state   (atom {:terminals {}})
             :_writes writes}
            opts))))

(defn- last-response
  "Parse the last JSON-RPC message written to conn.stdin."
  [conn]
  (when-let [w (last @(:_writes conn))]
    (js/JSON.parse (.trim w))))

(defn- make-parsed
  "Build a mock parsed JSON-RPC request object."
  [id method params]
  (clj->js {:id id :method method :params params}))

(defn- no-ui-api
  "Mock API with no available UI — triggers auto-approve in permission handler."
  []
  #js {:ui #js {:available false}})

;;; ─── handle-permission-request ───────────────────────────────

(describe "handlers/handle-permission-request"
          (fn []
            (it "auto-approves and prefers allow_always over allow_once"
                (fn []
                  (let [conn   (make-conn)
                        parsed (make-parsed 1 "session/request_permission"
                                            {:options [{:kind "allow_once"   :optionId "once-id"}
                                                       {:kind "allow_always" :optionId "always-id"}]})]
                    (handlers/handle-permission-request conn parsed (no-ui-api))
                    (-> (expect (.. (last-response conn) -result -outcome -optionId))
                        (.toBe "always-id")))))

            (it "falls back to allow_once when no allow_always present"
                (fn []
                  (let [conn   (make-conn)
                        parsed (make-parsed 1 "session/request_permission"
                                            {:options [{:kind "allow_once" :optionId "once-id"}]})]
                    (handlers/handle-permission-request conn parsed (no-ui-api))
                    (-> (expect (.. (last-response conn) -result -outcome -optionId))
                        (.toBe "once-id")))))

            (it "falls back to first option when no allow_always or allow_once"
                (fn []
                  (let [conn   (make-conn)
                        parsed (make-parsed 1 "session/request_permission"
                                            {:options [{:kind "deny"  :optionId "deny-id"}
                                                       {:kind "other" :optionId "other-id"}]})]
                    (handlers/handle-permission-request conn parsed (no-ui-api))
                    (-> (expect (.. (last-response conn) -result -outcome -optionId))
                        (.toBe "deny-id")))))

            (it "sends cancelled when options list is empty"
                (fn []
                  (let [conn   (make-conn)
                        parsed (make-parsed 1 "session/request_permission" {:options []})]
                    (handlers/handle-permission-request conn parsed (no-ui-api))
                    (-> (expect (.. (last-response conn) -result -outcome -outcome))
                        (.toBe "cancelled")))))

            (it "response shape is result.outcome.outcome (doubly nested — ACP schema)"
                (fn []
                  ;; This pins the regression: the outer {:outcome {...}} wrapper is required
                  ;; by the ACP Zod schema. A flat {:outcome "selected"} is rejected.
                  (let [conn   (make-conn)
                        parsed (make-parsed 42 "session/request_permission"
                                            {:options [{:kind "allow_always" :optionId "x"}]})]
                    (handlers/handle-permission-request conn parsed (no-ui-api))
                    (let [resp (last-response conn)]
                      (-> (expect (.-result resp)) (.toBeTruthy))
                      (-> (expect (.. resp -result -outcome)) (.toBeTruthy))
                      (-> (expect (.. resp -result -outcome -outcome)) (.toBe "selected"))
                      (-> (expect (.. resp -result -outcome -optionId)) (.toBe "x"))))))))

;;; ─── handle-fs-write ─────────────────────────────────────────

(describe "handlers/handle-fs-write"
          (fn []
            (it "rejects a path outside the project root (path traversal)"
                (fn []
                  (let [tmp    (path/join (os/tmpdir) (str "nyma-fsw-" (js/Date.now)))
                        _      (fs/mkdirSync tmp #js {:recursive true})
                        conn   (make-conn {:project-root tmp})
                        parsed (make-parsed 1 "fs/write_text_file"
                                            {:path "/etc/passwd" :content "x"})]
                    (handlers/handle-fs-write conn parsed)
                    (let [resp (last-response conn)]
                      (-> (expect (.-error resp)) (.toBeTruthy))
                      (-> (expect (.. resp -error -message)) (.toContain "outside project root")))
                    (fs/rmSync tmp #js {:recursive true :force true}))))

            (it "writes a file inside the project root"
                (fn []
                  (let [tmp    (path/join (os/tmpdir) (str "nyma-fsw2-" (js/Date.now)))
                        _      (fs/mkdirSync tmp #js {:recursive true})
                        file   (path/join tmp "out.txt")
                        conn   (make-conn {:project-root tmp})
                        parsed (make-parsed 2 "fs/write_text_file"
                                            {:path file :content "hello"})]
                    (handlers/handle-fs-write conn parsed)
                    (let [resp (last-response conn)]
                      (-> (expect (.-error resp)) (.toBeFalsy))
                      (-> (expect (fs/readFileSync file "utf-8")) (.toBe "hello")))
                    (fs/rmSync tmp #js {:recursive true :force true}))))

            (it "allows write when no project-root is set"
                (fn []
                  (let [tmp    (path/join (os/tmpdir) (str "nyma-fsw3-" (js/Date.now)))
                        _      (fs/mkdirSync tmp #js {:recursive true})
                        file   (path/join tmp "any.txt")
                        conn   (make-conn)   ; no :project-root
                        parsed (make-parsed 3 "fs/write_text_file"
                                            {:path file :content "open"})]
                    (handlers/handle-fs-write conn parsed)
                    (let [resp (last-response conn)]
                      (-> (expect (.-error resp)) (.toBeFalsy)))
                    (fs/rmSync tmp #js {:recursive true :force true}))))))

;;; ─── handle-terminal-output ──────────────────────────────────

(describe "handlers/handle-terminal-output"
          (fn []
            (it "includes truncated:false in the response (ACP schema invariant)"
                (fn []
                  (let [conn   (make-conn)
                        tid    "t-999"
                        _      (swap! (:state conn) assoc-in [:terminals tid]
                                      {:proc nil :output (atom "line1\nline2")})
                        parsed (make-parsed 1 "terminal/output" {:terminalId tid})]
                    (handlers/handle-terminal-output conn parsed)
                    (let [resp (last-response conn)]
                      (-> (expect (.. resp -result -truncated)) (.toBe false))
                      (-> (expect (.. resp -result -output)) (.toBe "line1\nline2"))))))

            (it "returns an error when terminalId is not found"
                (fn []
                  (let [conn   (make-conn)
                        parsed (make-parsed 1 "terminal/output" {:terminalId "bogus"})]
                    (handlers/handle-terminal-output conn parsed)
                    (-> (expect (.-error (last-response conn))) (.toBeTruthy)))))))

;;; ─── handle-elicitation ──────────────────────────────────────

(describe "handlers/handle-elicitation"
          (fn []
            (it "returns action:decline when no UI is available (form mode)"
                (fn []
                  (let [conn   (make-conn)
                        parsed (make-parsed 1 "session/elicitation"
                                            {:mode "form"
                                             :title "Auth"
                                             :jsonSchema #js {:type "object"
                                                              :properties #js {:token #js {:type "string"}}
                                                              :required   #js []}})]
                    (handlers/handle-elicitation conn parsed (no-ui-api))
                    (-> (expect (.. (last-response conn) -result -action)) (.toBe "decline")))))

            (it "returns action:cancel for unknown mode"
                (fn []
                  (let [conn   (make-conn)
                        parsed (make-parsed 2 "session/elicitation"
                                            {:mode "future-mode-not-yet-defined"})]
                    (handlers/handle-elicitation conn parsed (no-ui-api))
                    (-> (expect (.. (last-response conn) -result -action)) (.toBe "cancel")))))

            (it "returns action:accept for url mode (shows URL, no UI block needed)"
                (fn []
                  (let [conn   (make-conn)
                        parsed (make-parsed 3 "session/elicitation"
                                            {:mode "url" :url "https://example.com/oauth"})]
                    (handlers/handle-elicitation conn parsed (no-ui-api))
                    (-> (expect (.. (last-response conn) -result -action)) (.toBe "accept")))))))

;;; ─── dispatch-reverse-request ────────────────────────────────

(describe "handlers/dispatch-reverse-request"
          (fn []
            (it "returns error -32601 for unknown method"
                (fn []
                  (let [conn   (make-conn)
                        parsed (make-parsed 1 "unknown/method" {})]
                    (handlers/dispatch-reverse-request conn parsed (no-ui-api))
                    (let [resp (last-response conn)]
                      (-> (expect (.. resp -error -code)) (.toBe -32601))))))

            (it "routes fs/read_text_file to the read handler"
                (fn []
                  (let [tmp    (path/join (os/tmpdir) (str "nyma-fsr-" (js/Date.now)))
                        _      (fs/mkdirSync tmp #js {:recursive true})
                        file   (path/join tmp "r.txt")
                        _      (fs/writeFileSync file "content" "utf-8")
                        conn   (make-conn)
                        parsed (make-parsed 1 "fs/read_text_file" {:path file})]
                    (handlers/dispatch-reverse-request conn parsed (no-ui-api))
                    (let [resp (last-response conn)]
                      (-> (expect (.. resp -result -content)) (.toBe "content")))
                    (fs/rmSync tmp #js {:recursive true :force true}))))))
