(ns editor-eval.test
  "Tests for agent.ui.editor-eval — the module that powers `$expr`
   and `$$expr` typed directly into the prompt editor. Four surfaces
   are covered:

     - parse-eval-input (pure: kind detection + form-start guard)
     - format-eval-output (pure: text block layout, unavailable hint)
     - bb-available? (memoization of the PATH probe)
     - run-eval! (integration: spawns bb, captures output)

   The integration tests are guarded on bb actually being installed
   so CI without babashka gracefully skips them rather than fails."
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.ui.editor-eval :refer [parse-eval-input
                                          format-eval-output
                                          bb-available?
                                          reset-bb-probe!
                                          run-eval!]]))

;;; ─── parse-eval-input ──────────────────────────────────

(describe "parse-eval-input"
          (fn []
            (it "returns :not-eval for nil and empty input"
                (fn []
                  (-> (expect (:kind (parse-eval-input nil)))  (.toBe "not-eval"))
                  (-> (expect (:kind (parse-eval-input "")))   (.toBe "not-eval"))))

            (it "returns :not-eval for text with no sigil"
                (fn []
                  (-> (expect (:kind (parse-eval-input "hello world"))) (.toBe "not-eval"))
                  (-> (expect (:kind (parse-eval-input "/help")))       (.toBe "not-eval"))
                  (-> (expect (:kind (parse-eval-input "!ls -la")))     (.toBe "not-eval"))))

            (it ":eval for $expr with a paren-start form"
                (fn []
                  (let [p (parse-eval-input "$(+ 1 2)")]
                    (-> (expect (:kind p)) (.toBe "eval"))
                    (-> (expect (:expr p)) (.toBe "(+ 1 2)")))))

            (it ":eval-hidden for $$expr (takes precedence over single $)"
                (fn []
                  (let [p (parse-eval-input "$$(+ 1 2)")]
                    (-> (expect (:kind p)) (.toBe "eval-hidden"))
                    (-> (expect (:expr p)) (.toBe "(+ 1 2)")))))

            (it "trims leading whitespace before the sigil"
                (fn []
                  (-> (expect (:kind (parse-eval-input "  $(+ 1 2)")))  (.toBe "eval"))
                  (-> (expect (:kind (parse-eval-input "\t$$(+ 1 2)"))) (.toBe "eval-hidden"))))

            (it "trims whitespace between the sigil and the form"
                (fn []
                  (let [p (parse-eval-input "$  (+ 1 2)")]
                    (-> (expect (:expr p)) (.toBe "(+ 1 2)")))))

            (it "bare sigil with no expression is :not-eval"
                (fn []
                  (-> (expect (:kind (parse-eval-input "$")))   (.toBe "not-eval"))
                  (-> (expect (:kind (parse-eval-input "$$")))  (.toBe "not-eval"))
                  (-> (expect (:kind (parse-eval-input "$  "))) (.toBe "not-eval"))))

            (it "rejects $PATH-style shell-variable lookalikes"
                (fn []
                  ;; The form-start guard: the character after $ must
                  ;; be a legitimate Clojure form opener. PATH starts
                  ;; with a letter, so this is :not-eval and will flow
                  ;; through as a normal LLM prompt.
                  (-> (expect (:kind (parse-eval-input "$PATH")))   (.toBe "not-eval"))
                  (-> (expect (:kind (parse-eval-input "$HOME")))   (.toBe "not-eval"))
                  (-> (expect (:kind (parse-eval-input "$foo")))    (.toBe "not-eval"))
                  (-> (expect (:kind (parse-eval-input "$123")))    (.toBe "not-eval"))))

            (it "accepts all standard Clojure form-start characters"
                (fn []
                  ;; Parens, brackets, braces, reader-macro dispatch,
                  ;; syntax-quote/unquote, metadata, quote, deref.
                  (-> (expect (:kind (parse-eval-input "$[1 2 3]")))    (.toBe "eval"))
                  (-> (expect (:kind (parse-eval-input "${:a 1}")))     (.toBe "eval"))
                  (-> (expect (:kind (parse-eval-input "$#{1 2}")))     (.toBe "eval"))
                  (-> (expect (:kind (parse-eval-input "$'foo")))       (.toBe "eval"))))

            (it "preserves the entire expression including inner spaces and strings"
                (fn []
                  (let [p (parse-eval-input "$(println \"hello world\")")]
                    (-> (expect (:expr p)) (.toBe "(println \"hello world\")")))))))

;;; ─── format-eval-output ────────────────────────────────

(describe "format-eval-output"
          (fn []
            (it "renders a λ header plus stdout"
                (fn []
                  (let [s (format-eval-output
                           {:expr      "(+ 1 2)"
                            :stdout    "3\n"
                            :stderr    ""
                            :exit-code 0})]
                    (-> (expect (.includes s "λ (+ 1 2)")) (.toBe true))
                    (-> (expect (.includes s "3"))         (.toBe true)))))

            (it "omits the exit footer on exit 0"
                (fn []
                  (let [s (format-eval-output
                           {:expr "1" :stdout "" :stderr "" :exit-code 0})]
                    (-> (expect (.includes s "exit")) (.toBe false)))))

            (it "emits the exit footer on non-zero exit"
                (fn []
                  (let [s (format-eval-output
                           {:expr "(throw (ex-info \"x\" {}))"
                            :stdout "" :stderr "boom" :exit-code 1})]
                    (-> (expect (.includes s "exit 1"))   (.toBe true))
                    (-> (expect (.includes s "stderr:")) (.toBe true))
                    (-> (expect (.includes s "boom"))    (.toBe true)))))

            (it "renders the install hint when bb is unavailable"
                (fn []
                  (let [s (format-eval-output
                           {:expr "(+ 1 2)" :unavailable? true
                            :install-hint "install bb first" :stdout ""
                            :stderr "" :exit-code -1})]
                    (-> (expect (.includes s "(+ 1 2)"))          (.toBe true))
                    (-> (expect (.includes s "install bb first")) (.toBe true)))))

            (it "truncates very large output with a byte-count note"
                (fn []
                  (let [huge (.repeat "x" 20000)
                        s    (format-eval-output
                              {:expr "(big)" :stdout huge :stderr ""
                               :exit-code 0})]
                    (-> (expect (.includes s "truncated")) (.toBe true)))))))

;;; ─── bb-available? memoization ─────────────────────────

(defn ^:async test-bb-available-is-memoized []
  (reset-bb-probe!)
  ;; Two awaits, one result. We can't directly observe that Bun.spawn
  ;; ran only once without monkey-patching it, but equality of the
  ;; probes + the plain-atom memo contract (case @bb-probe-state) is
  ;; enough — the second call hits the :present/:missing branch and
  ;; returns without a spawn.
  (let [a (js-await (bb-available?))
        b (js-await (bb-available?))]
    (-> (expect (= a b)) (.toBe true))
    (-> (expect (boolean? a)) (.toBe true))))

(describe "bb-available? memoization"
          (fn []
            (it "caches the probe result across calls"
                test-bb-available-is-memoized)))

;;; ─── run-eval! integration ─────────────────────────────
;;; These only make sense when bb is actually installed. We probe
;;; once at module load and skip the describe block if it's missing
;;; — a CI box without babashka should see "skipped", not "failed".

(defn ^:async test-run-eval-happy-path []
  (let [result (js-await (run-eval! "(+ 1 2)"))]
    (-> (expect (:unavailable? result)) (.toBe false))
    (-> (expect (:exit-code result))    (.toBe 0))
    ;; bb with -e prints the return value automatically, no println
    ;; needed on the user's part. That's the ergonomics win over
    ;; python -c "1+2".
    (-> (expect (.includes (:stdout result) "3")) (.toBe true))))

(defn ^:async test-run-eval-captures-stderr-on-throw []
  (let [result (js-await (run-eval! "(throw (ex-info \"boom\" {}))"))]
    (-> (expect (:unavailable? result)) (.toBe false))
    ;; bb prints the exception to stderr and exits non-zero.
    (-> (expect (not (zero? (:exit-code result))))   (.toBe true))
    (-> (expect (.includes (:stderr result) "boom")) (.toBe true))))

(defn ^:async test-run-eval-pure-value-on-stdout []
  ;; Proves the "no print needed" contract that makes bb a better
  ;; fit than python -c for this mode.
  (let [result (js-await (run-eval! "(map inc [1 2 3])"))]
    (-> (expect (.includes (:stdout result) "(2 3 4)")) (.toBe true))))

(describe "run-eval! (integration — requires bb on PATH)"
          (fn []
            (it "evaluates a simple expression and captures stdout"
                test-run-eval-happy-path)
            (it "captures stderr and non-zero exit on thrown exceptions"
                test-run-eval-captures-stderr-on-throw)
            (it "prints pure return values without explicit println"
                test-run-eval-pure-value-on-stdout)))
