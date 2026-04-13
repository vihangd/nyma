(ns ext-ast-tools.test
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            [clojure.string :as str]
            [agent.extensions.ast-tools.index :as ast-tools]
            [agent.tool-result-policy :as policy]))

;;; ─── mock api ────────────────────────────────────────────────

(defn- make-mock-api
  "Minimal mock API for ast_tools. exec-stdout is what the sg command returns.
   `which sg` always returns a non-empty path so sg-available? = true."
  ([] (make-mock-api ""))
  ([exec-stdout]
   (let [registered-tools (atom {})]
     #js {:exec            (fn [cmd _args]
                             (js/Promise.resolve
                              ;; sg-available? calls (.exec api "which" ["sg"])
                              ;; Actual sg runs use exec-stdout
                              #js {:stdout (if (= cmd "which") "/usr/bin/sg" (or exec-stdout ""))
                                   :stderr ""}))
          :registerTool    (fn [name opts] (swap! registered-tools assoc name opts))
          :unregisterTool  (fn [name] (swap! registered-tools dissoc name))
          :_tools          registered-tools})))

;;; ─── fixtures ────────────────────────────────────────────────

(beforeEach (fn [] (policy/reset-policies!)))
(afterEach  (fn [] (policy/reset-policies!)))

;;; ─── policy registration ─────────────────────────────────────

(describe "ast-tools:policy-registration" (fn []
                                            (it "registers ast_grep policy at activation"
                                                (fn []
                                                  (let [api     (make-mock-api)
                                                        cleanup ((.-default ast-tools) api)]
                                                    (let [p (policy/policy-for "ast_grep")]
                                                      (-> (expect (:max-string-length p)) (.toBe 5000)))
                                                    (cleanup))))

                                            (it "registers ast_edit policy at activation"
                                                (fn []
                                                  (let [api     (make-mock-api)
                                                        cleanup ((.-default ast-tools) api)]
                                                    (let [p (policy/policy-for "ast_edit")]
                                                      (-> (expect (:max-string-length p)) (.toBe 5000)))
                                                    (cleanup))))

                                            (it "unregisters ast_grep policy at deactivation — falls back to default 12000"
                                                (fn []
                                                  (let [api     (make-mock-api)
                                                        cleanup ((.-default ast-tools) api)]
                                                    (cleanup)
                                                    (let [p (policy/policy-for "ast_grep")]
                                                      (-> (expect (:max-string-length p)) (.toBe 12000))))))

                                            (it "unregisters ast_edit policy at deactivation — falls back to default 12000"
                                                (fn []
                                                  (let [api     (make-mock-api)
                                                        cleanup ((.-default ast-tools) api)]
                                                    (cleanup)
                                                    (let [p (policy/policy-for "ast_edit")]
                                                      (-> (expect (:max-string-length p)) (.toBe 12000))))))))

;;; ─── tool registration ───────────────────────────────────────

(describe "ast-tools:tool-registration" (fn []
                                          (it "registers ast_grep and ast_edit on activation"
                                              (fn []
                                                (let [api     (make-mock-api)
                                                      cleanup ((.-default ast-tools) api)]
                                                  (-> (expect (contains? @(.-_tools api) "ast_grep")) (.toBe true))
                                                  (-> (expect (contains? @(.-_tools api) "ast_edit")) (.toBe true))
                                                  (cleanup))))

                                          (it "unregisters both tools on deactivation"
                                              (fn []
                                                (let [api     (make-mock-api)
                                                      cleanup ((.-default ast-tools) api)]
                                                  (cleanup)
                                                  (-> (expect (contains? @(.-_tools api) "ast_grep")) (.toBe false))
                                                  (-> (expect (contains? @(.-_tools api) "ast_edit")) (.toBe false)))))))

;;; ─── execute: truncation via policy ─────────────────────────

(describe "ast-tools:truncation" (fn []
                                   (it "short output passes through unchanged"
                                       (fn []
      ;; sg-available? checks .exec "which sg" — non-empty stdout means available
                                         (let [short   "match: line 1\nmatch: line 2"
                                               api     (make-mock-api short)
                                               _       ((.-default ast-tools) api)
                                               tool    (get @(.-_tools api) "ast_grep")]
                                           (-> ((:execute tool) #js {:pattern "foo" :path "." :language nil :json false})
                                               (.then (fn [result]
                                                        (-> (expect result) (.toBe short))))))))

                                   (it "output over 5000 chars is truncated to policy limit"
                                       (fn []
                                         (let [big-output (str/join "\n" (map (fn [i] (str "match-line-" i ": " (str/join (repeat 20 "x"))))
                                                                              (range 300)))
                                               api        (make-mock-api big-output)
                                               _          ((.-default ast-tools) api)
                                               tool       (get @(.-_tools api) "ast_grep")]
                                           (-> ((:execute tool) #js {:pattern "foo" :path "." :language nil :json false})
                                               (.then (fn [result]
                                                        (-> (expect (count result)) (.toBeLessThanOrEqual 5100))
                                                        (-> (expect result) (.toContain "truncated"))))))))

                                   (it "truncation marker appended when output exceeds limit"
                                       (fn []
                                         (let [big-output (str/join "" (repeat 6000 "x"))
                                               api        (make-mock-api big-output)
                                               _          ((.-default ast-tools) api)
                                               tool       (get @(.-_tools api) "ast_grep")]
                                           (-> ((:execute tool) #js {:pattern "foo" :path "." :language nil :json false})
                                               (.then (fn [result]
                                                        (-> (expect result) (.toContain "bytes truncated"))))))))

                                   (it "empty output returns 'No matches found.' without policy path"
                                       (fn []
                                         (let [api  (make-mock-api "")
                                               _    ((.-default ast-tools) api)
                                               tool (get @(.-_tools api) "ast_grep")]
                                           (-> ((:execute tool) #js {:pattern "foo" :path "." :language nil :json false})
                                               (.then (fn [result]
                                                        (-> (expect result) (.toBe "No matches found."))))))))

                                   (it "ast_edit empty output returns 'No matches found...' message"
                                       (fn []
                                         (let [api  (make-mock-api "")
                                               _    ((.-default ast-tools) api)
                                               tool (get @(.-_tools api) "ast_edit")]
                                           (-> ((:execute tool) #js {:pattern "foo" :replacement "bar" :path "." :language nil :dry_run false})
                                               (.then (fn [result]
                                                        (-> (expect result) (.toContain "No matches found"))))))))))
