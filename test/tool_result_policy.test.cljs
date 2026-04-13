(ns tool-result-policy.test
  "Tests for agent.tool-result-policy — per-tool result normalization and
   truncation. Covers:

     - policy-for    (default, builtin, metadata, ext-override precedence)
     - apply-policy  (nil/empty, string, isError map, truncation, error-kind)
     - model-string  (envelope → model-visible string extraction)
     - register-policy! / unregister-policy! lifecycle
     - tool_metadata :result-policy bridge
     - middleware integration (policy applied in tool-tracking-leave)"
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            [agent.tool-result-policy :refer [policy-for
                                              apply-policy
                                              model-string
                                              register-policy!
                                              unregister-policy!
                                              reset-policies!]]
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

            (it "bash cap is 10000"
                (fn []
                  (-> (expect (:max-string-length (policy-for "bash"))) (.toBe 10000))))

            (it "read cap is 12000"
                (fn []
                  (-> (expect (:max-string-length (policy-for "read"))) (.toBe 12000))))

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
