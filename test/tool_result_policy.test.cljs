(ns tool-result-policy.test
  "Tests for agent.tool-result-policy — per-tool result normalization and
   truncation. Covers:

     - policy-for    (default, builtin, metadata, ext-override precedence)
     - apply-policy  (nil/empty, string, isError map, truncation, error-kind)
     - model-string  (envelope → model-visible string extraction)
     - register-policy! / unregister-policy! lifecycle
     - tool_metadata :result-policy bridge
     - middleware integration (policy applied in tool-tracking-leave)
     - handle-backed truncation + paged recall via store-read"
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["./agent/middleware.mjs" :refer [create-pipeline]]
            [agent.events :refer [create-event-bus]]
            [agent.tool-result-policy :refer [policy-for
                                              apply-policy
                                              model-string
                                              register-policy!
                                              unregister-policy!
                                              reset-policies!
                                              store-read
                                              reset-store!]]
            [agent.tool-metadata :refer [register-metadata!
                                         unregister-metadata!
                                         reset-extension-metadata!]]))

;;; ─── helpers ────────────────────────────────────────────

(defn- repeat-str [s n]
  (.repeat s n))

;;; ─── policy-for ─────────────────────────────────────────

(describe "policy-for — default policy"
          (fn []
            (it "returns 12000 max-string-length for an unknown tool"
                (fn []
                  (let [p (policy-for "unknown_tool_xyz")]
                    (-> (expect (:max-string-length p)) (.toBe 12000)))))

            (it "includes :prefer-summary-only false for an unknown tool"
                (fn []
                  (-> (expect (:prefer-summary-only (policy-for "unknown_tool_xyz")))
                      (.toBe false))))))

(describe "policy-for — builtin overrides"
          (fn []
            (it "ls cap is 4000"
                (fn []
                  (-> (expect (:max-string-length (policy-for "ls"))) (.toBe 4000))))

            (it "glob cap is 4000"
                (fn []
                  (-> (expect (:max-string-length (policy-for "glob"))) (.toBe 4000))))

            (it "grep cap is 8000"
                (fn []
                  (-> (expect (:max-string-length (policy-for "grep"))) (.toBe 8000))))

            (it "bash falls back to default 12000 when output_handling extension not loaded"
                ;; bash limit is registered dynamically by bash_suite/output_handling;
                ;; without that extension, the default-policy (12000) applies.
                (fn []
                  (-> (expect (:max-string-length (policy-for "bash"))) (.toBe 12000))))

            (it "read cap is a large backstop (read is line-capped at the tool)"
                (fn []
                  ;; Must comfortably exceed 2000 numbered lines (~7 chars of
                  ;; prefix each) or every default read loses its tail + the
                  ;; "read with range" continuation hint.
                  (-> (expect (:max-string-length (policy-for "read"))) (.toBe 200000))))

            (it "web_search cap is 8000"
                (fn []
                  (-> (expect (:max-string-length (policy-for "web_search"))) (.toBe 8000))))

            (it "think cap is 4000"
                (fn []
                  (-> (expect (:max-string-length (policy-for "think"))) (.toBe 4000))))))

(describe "policy-for — register-policy! / unregister-policy!"
          (fn []
            (beforeEach (fn [] (reset-policies!)))
            (afterEach  (fn [] (reset-policies!)))

            (it "registered policy overrides builtin"
                (fn []
                  (register-policy! "grep" {:max-string-length 999})
                  (-> (expect (:max-string-length (policy-for "grep"))) (.toBe 999))))

            (it "registered policy adds to unknown tool"
                (fn []
                  (register-policy! "my_ext_tool" {:max-string-length 500})
                  (-> (expect (:max-string-length (policy-for "my_ext_tool"))) (.toBe 500))))

            (it "unregister-policy! removes the override and reverts to builtin"
                (fn []
                  (register-policy! "grep" {:max-string-length 777})
                  (unregister-policy! "grep")
                  (-> (expect (:max-string-length (policy-for "grep"))) (.toBe 8000))))

            (it "unregister-policy! on unknown name does not throw"
                (fn []
                  (-> (expect (fn [] (unregister-policy! "nonexistent"))) (.not.toThrow))))))

(describe "policy-for — tool-metadata :result-policy bridge"
          (fn []
            (beforeEach (fn [] (reset-policies!) (reset-extension-metadata!)))
            (afterEach  (fn [] (reset-policies!) (reset-extension-metadata!)))

            (it "metadata :result-policy is picked up by policy-for"
                (fn []
                  (register-metadata! "ext_tool" {:result-policy {:max-string-length 2222}})
                  (-> (expect (:max-string-length (policy-for "ext_tool"))) (.toBe 2222))))

            (it "ext-policies atom overrides metadata :result-policy"
                (fn []
                  ;; Both metadata policy and explicit register-policy! set — atom wins
                  (register-metadata! "ext_tool" {:result-policy {:max-string-length 2222}})
                  (register-policy!   "ext_tool" {:max-string-length 3333})
                  (-> (expect (:max-string-length (policy-for "ext_tool"))) (.toBe 3333))))

            (it "metadata policy without :result-policy key does not affect policy-for"
                (fn []
                  (register-metadata! "ext_tool" {:read-only? true})
                  ;; Falls through to default
                  (-> (expect (:max-string-length (policy-for "ext_tool"))) (.toBe 12000))))))

;;; ─── apply-policy — nil / empty ─────────────────────────

(describe "apply-policy — nil and empty input"
          (fn []
            (it "nil input → :ok true, empty summary, nil data"
                (fn []
                  (let [e (apply-policy nil "bash")]
                    (-> (expect (:ok e))      (.toBe true))
                    (-> (expect (:summary e)) (.toBe ""))
                    (-> (expect (nil? (:data e)))  (.toBe true))
                    (-> (expect (nil? (:error e))) (.toBe true)))))

            (it "empty string input → :ok true, empty summary, nil data"
                (fn []
                  (let [e (apply-policy "" "bash")]
                    (-> (expect (:ok e))      (.toBe true))
                    (-> (expect (:summary e)) (.toBe ""))
                    (-> (expect (nil? (:data e))) (.toBe true)))))))

;;; ─── apply-policy — success strings ─────────────────────

(describe "apply-policy — normal string input"
          (fn []
            (it ":ok true with :data set to the input string"
                (fn []
                  (let [e (apply-policy "hello world" "read")]
                    (-> (expect (:ok e))   (.toBe true))
                    (-> (expect (:data e)) (.toBe "hello world")))))

            (it ":summary is the first line (≤200 chars)"
                (fn []
                  (let [e (apply-policy "first line\nsecond line" "read")]
                    (-> (expect (:summary e)) (.toBe "first line")))))

            (it ":summary of a multi-line result is trimmed"
                (fn []
                  (let [e (apply-policy "  hello  \nrest" "read")]
                    (-> (expect (:summary e)) (.toBe "hello")))))

            (it ":error and :error-kind are nil for ok results"
                (fn []
                  (let [e (apply-policy "ok result" "bash")]
                    (-> (expect (nil? (:error e)))      (.toBe true))
                    (-> (expect (nil? (:error-kind e))) (.toBe true)))))))

;;; ─── apply-policy — truncation ───────────────────────────

(describe "apply-policy — maxStringLength truncation"
          (fn []
            (it "string at exactly the limit is not truncated"
                (fn []
                  ;; ls limit = 4000
                  (let [s (repeat-str "x" 4000)
                        e (apply-policy s "ls")]
                    (-> (expect (:data e)) (.toBe s))
                    (-> (expect (.includes (:data e) "truncated")) (.toBe false)))))

            (it "string below the limit is not truncated"
                (fn []
                  (let [s (repeat-str "a" 100)
                        e (apply-policy s "ls")]
                    (-> (expect (:data e)) (.toBe s)))))

            (it "string above the limit is truncated with a byte-count note"
                (fn []
                  ;; ls limit = 4000
                  (let [s   (repeat-str "z" 5000)
                        e   (apply-policy s "ls")
                        dat (:data e)]
                    (-> (expect (.includes dat "truncated")) (.toBe true))
                    ;; The truncated marker says how many bytes were cut
                    (-> (expect (.includes dat "1000 bytes")) (.toBe true))
                    ;; Preserved prefix is exactly 4000 chars before the note
                    (-> (expect (.startsWith dat (repeat-str "z" 4000))) (.toBe true)))))

            (it "custom ext policy limit is respected"
                (fn []
                  (register-policy! "my_tool" {:max-string-length 10})
                  (let [s (repeat-str "q" 50)
                        e (apply-policy s "my_tool")]
                    (-> (expect (.includes (:data e) "truncated")) (.toBe true))
                    (-> (expect (.includes (:data e) "40 bytes")) (.toBe true)))
                  (unregister-policy! "my_tool")))))

;;; ─── apply-policy — isError maps ─────────────────────────

(describe "apply-policy — isError map input"
          (fn []
            (it "map with :isError truthy → :ok false"
                (fn []
                  (let [e (apply-policy {:isError true :content "TOOL_FAILED"} "bash")]
                    (-> (expect (:ok e)) (.toBe false)))))

            (it ":error field contains the error message"
                (fn []
                  (let [e (apply-policy {:isError true :content "INVALID_path"} "bash")]
                    (-> (expect (.includes (:error e) "INVALID_path")) (.toBe true)))))

            (it ":error falls back to :error key on the map when :content is absent"
                (fn []
                  (let [e (apply-policy {:isError true :error "something went wrong"} "bash")]
                    (-> (expect (.includes (:error e) "something went wrong")) (.toBe true)))))

            (it ":data is nil on error envelopes"
                (fn []
                  (let [e (apply-policy {:isError true :content "oops"} "bash")]
                    (-> (expect (nil? (:data e))) (.toBe true)))))))

;;; ─── apply-policy — error-kind inference ─────────────────

(describe "apply-policy — :error-kind inference"
          (fn []
            (it "INVALID_ prefix → :invalid"
                (fn []
                  (let [e (apply-policy {:isError true :content "INVALID_tool_args"} "bash")]
                    (-> (expect (:error-kind e)) (.toBe :invalid)))))

            (it "_NOT_FOUND suffix → :not-found"
                (fn []
                  (let [e (apply-policy {:isError true :content "FILE_NOT_FOUND"} "read")]
                    (-> (expect (:error-kind e)) (.toBe :not-found)))))

            (it "RG_NOT_FOUND → :rg-not-found"
                (fn []
                  (let [e (apply-policy {:isError true :content "RG_NOT_FOUND"} "grep")]
                    (-> (expect (:error-kind e)) (.toBe :rg-not-found)))))

            (it "Not found (mixed-case) → :not-found"
                (fn []
                  (let [e (apply-policy {:isError true :content "Not found on disk"} "read")]
                    (-> (expect (:error-kind e)) (.toBe :not-found)))))

            (it "_FAILED suffix → :failed"
                (fn []
                  (let [e (apply-policy {:isError true :content "EXEC_FAILED"} "bash")]
                    (-> (expect (:error-kind e)) (.toBe :failed)))))

            (it "unknown error string → :unknown"
                (fn []
                  (let [e (apply-policy {:isError true :content "something blew up"} "bash")]
                    (-> (expect (:error-kind e)) (.toBe :unknown)))))

            (it ":error-kind nil for nil content"
                (fn []
                  (let [e (apply-policy {:isError true} "bash")]
                    ;; :content and :error are both absent — message falls back to "tool error"
                    (-> (expect (:error-kind e)) (.toBe :unknown)))))))

;;; ─── model-string ────────────────────────────────────────

(describe "model-string — envelope to string extraction"
          (fn []
            (it "returns :data on success envelopes"
                (fn []
                  (let [e {:ok true :summary "s" :data "full content" :error nil :error-kind nil}]
                    (-> (expect (model-string e)) (.toBe "full content")))))

            (it "returns :error on error envelopes"
                (fn []
                  (let [e {:ok false :summary "err" :data nil :error "boom" :error-kind :unknown}]
                    (-> (expect (model-string e)) (.toBe "boom")))))

            (it "falls back to :summary when data and error are nil"
                (fn []
                  (let [e {:ok true :summary "summary only" :data nil :error nil :error-kind nil}]
                    (-> (expect (model-string e)) (.toBe "summary only")))))

            (it "returns empty string when all fields are nil"
                (fn []
                  (let [e {:ok true :summary nil :data nil :error nil :error-kind nil}]
                    (-> (expect (model-string e)) (.toBe "")))))))

;;; ─── summary truncation ──────────────────────────────────

(describe "apply-policy — summary length"
          (fn []
            (it ":summary is ≤200 chars even for very long first lines"
                (fn []
                  (let [long-line (repeat-str "a" 500)
                        e (apply-policy long-line "read")]
                    (-> (expect (<= (count (:summary e)) 200)) (.toBe true)))))

            (it ":summary is the first line, not the whole content"
                (fn []
                  (let [e (apply-policy "line1\nline2\nline3" "read")]
                    (-> (expect (:summary e)) (.toBe "line1")))))))

;;; ─── round-trip / model-string contract ─────────────────

(describe "apply-policy + model-string round-trip"
          (fn []
            (it "normal string within limit → model-string returns the original"
                (fn []
                  (let [s "hello"
                        e (apply-policy s "read")]
                    (-> (expect (model-string e)) (.toBe s)))))

            (it "oversized string → model-string returns the truncated+annotated form"
                (fn []
                  (let [s   (repeat-str "x" 5000)
                        e   (apply-policy s "ls")   ; limit 4000
                        ms  (model-string e)]
                    (-> (expect (.includes ms "truncated")) (.toBe true))
                    (-> (expect (< (count ms) (count s))) (.toBe true)))))

            (it "error map → model-string returns the error text"
                (fn []
                  (let [e (apply-policy {:isError true :content "TOOL_FAILED: oops"} "bash")]
                    (-> (expect (.includes (model-string e) "TOOL_FAILED")) (.toBe true)))))))

;;; ─── output_handling dynamic bash policy ──────────────────

(describe "bash policy — dynamic registration (output_handling integration)"
          (fn []
            (it "register-policy! for bash overrides the default"
                (fn []
                  (register-policy! "bash" {:max-string-length 30000})
                  (-> (expect (:max-string-length (policy-for "bash"))) (.toBe 30000))
                  (unregister-policy! "bash")))

            (it "registered limit matches the configured value"
                (fn []
                  ;; Simulate output_handling registering its max-bytes config value
                  (let [configured-max 20000]
                    (register-policy! "bash" {:max-string-length configured-max})
                    (-> (expect (:max-string-length (policy-for "bash"))) (.toBe configured-max))
                    (unregister-policy! "bash"))))

            (it "unregister-policy! restores bash to default 12000"
                (fn []
                  (register-policy! "bash" {:max-string-length 30000})
                  (unregister-policy! "bash")
                  (-> (expect (:max-string-length (policy-for "bash"))) (.toBe 12000))))))

;;; ─── handle-backed truncation ────────────────────────────
;;; Truncation used to destroy the tail outright: the model could not ask for
;;; what was cut. Everything except bash (which brings its own handle) lost
;;; content with no way back, including every MCP and extension tool on the
;;; bare default cap.

(describe "apply-policy — truncated content stays recoverable"
  (fn []
    (beforeEach (fn [] (reset-store!) (reset-policies!)))

    ;; The whole contract in one comparison: head + every page must rebuild the
    ;; original exactly. Fails on an unstable id, an evicted entry, a notice
    ;; offset that disagrees with the cut, an off-by-one, a lost line-boundary
    ;; cut-back, or an eof that fires early or never.
    (it "pages back to the exact original"
        (fn []
          (let [raw   (repeat-str "abcdefghij klmnopqrst\n" 900)
                head  (model-string (apply-policy raw "grep"))   ;; cap 8000
                id    (second (re-find #"id=\"([^\"]+)\"" head))]
            (-> (expect (some? id)) (.toBe true))
            (loop [offset 8000 acc "" pages 0]
              (let [r (store-read id offset 8000)]
                (if (:eof? r)
                  (do (-> (expect (str (.slice raw 0 8000) acc (:body r))) (.toBe raw))
                      (-> (expect (> pages 0)) (.toBe true)))
                  (do (-> (expect (> (:end r) offset)) (.toBe true))
                      (recur (:end r) (str acc (:body r)) (inc pages)))))))))

    ;; The notice is re-sent on every internal step of the turn. A drifting id
    ;; would change the prompt prefix each step and break the provider cache.
    (it "mints a byte-identical notice for identical content"
        (fn []
          (let [raw (repeat-str "same content here\n" 900)]
            (-> (expect (model-string (apply-policy raw "grep")))
                (.toBe (model-string (apply-policy raw "grep")))))))

    (it "leaves a result that fits untouched, and mints nothing"
        (fn []
          (let [e (apply-policy "short result" "grep")]
            (-> (expect (:data e)) (.toBe "short result"))
            (-> (expect (.includes (:data e) "retrieve_result")) (.toBe false)))))

    ;; Load-bearing: the recall tool's own output comes back through here, and
    ;; an overshooting page must not mint a handle for a handle.
    (it "never mints a handle for the recall tool itself"
        (fn []
          (let [raw (repeat-str "x" 20000)
                out (model-string (apply-policy raw "retrieve_result"))]
            (-> (expect (.includes out "retrieve_result(id=")) (.toBe false)))))

    (it "reports a miss for an unknown id rather than throwing"
        (fn []
          (-> (expect (:found? (store-read "nope" 0 100))) (.toBe false))))

    ;; A tool author opts out through the existing four-layer policy
    ;; precedence; no new config mechanism.
    (it "honours :handle-on-truncate false from a registered policy"
        (fn []
          (register-policy! "quiet_tool" {:max-string-length 100
                                          :handle-on-truncate false})
          (let [out (model-string (apply-policy (repeat-str "y" 500) "quiet_tool"))]
            (-> (expect (.includes out "truncated")) (.toBe true))
            (-> (expect (.includes out "retrieve_result")) (.toBe false)))
          (unregister-policy! "quiet_tool")))))

;;; ─── the pre-emption trap ────────────────────────────────
;;; apply-policy runs LAST in the leave chain: `create-pipeline` seeds
;;; [tool-tracking tool-persistence] and `addMiddleware` APPENDS, so an
;;; extension's :leave runs first and apply-policy sees whatever it left
;;; behind. token_suite's tool_truncation used to cut every result to ~3k
;;; before this was reached — under every cap, so no handle was ever minted
;;; and the content was destroyed exactly as before. Unit-testing apply-policy
;;; directly cannot see that; only running the real pipeline can.

(describe "pipeline — the model-visible result is recoverable end to end"
  (fn []
    (beforeEach (fn [] (reset-store!) (reset-policies!)))

    (it "mints a handle that recalls the untruncated tool output"
        (fn []
          (let [pipeline (create-pipeline (create-event-bus))
                 raw      (repeat-str "some grep hit line here\n" 3000)
                 tool     #js {:execute (fn [_] (js/Promise.resolve raw))}]
             (-> ((:execute pipeline) "grep" tool #js {})
                 (.then (fn [out]
                          (let [shown (str (or (.-result out) out))
                                id    (second (re-find #"id=\"([^\"]+)\"" shown))]
                            (-> (expect (some? id)) (.toBe true))
                            (loop [offset 8000 acc ""]
                              (let [r (store-read id offset 8000)]
                                (if (:eof? r)
                                  (-> (expect (str (.slice raw 0 8000) acc (:body r))) (.toBe raw))
                                  (recur (:end r) (str acc (:body r)))))))))))))

    ;; A middleware that shortens the result before apply-policy sees it takes
    ;; the content beyond recovery. Guards the ordering, not any one module.
    (it "a :leave that pre-shortens the result destroys recoverability"
        (fn []
          (let [pipeline (create-pipeline (create-event-bus))
                 raw      (repeat-str "some grep hit line here\n" 3000)
                 tool     #js {:execute (fn [_] (js/Promise.resolve raw))}]
             ((:add pipeline) #js {:name "pre-shortener"
                                   :leave (fn [ctx]
                                            (aset ctx "result" (.slice (str (.-result ctx)) 0 2000))
                                            ctx)})
             (-> ((:execute pipeline) "grep" tool #js {})
                 (.then (fn [out]
                          (let [shown (str (or (.-result out) out))]
                            ;; Documents the trap: no handle, content gone.
                            (-> (expect (.includes shown "retrieve_result(id=")) (.toBe false)))))))))))
