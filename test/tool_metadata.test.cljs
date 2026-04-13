(ns tool-metadata.test
  "Unit tests for agent.tool-metadata. Every built-in tool name is
   round-tripped through tool-safety so a typo in either the source
   or a rename breaks the test rather than ships silently."
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.tool-metadata :as tm]))

(defn- reset! []
  (tm/reset-extension-metadata!))

;;; ─── Defaults on unknown tools ────────────────────────

(describe "tool-safety: unknown tool defaults"
          (fn []
            (beforeEach (fn [] (reset!)))

            (it "returns the all-false default map for an unknown name"
                (fn []
                  (let [s (tm/tool-safety "no-such-tool-xyz")]
                    (-> (expect (:read-only? s))             (.toBe false))
                    (-> (expect (:destructive? s))           (.toBe false))
                    (-> (expect (:requires-confirmation? s)) (.toBe false))
                    (-> (expect (:network? s))               (.toBe false))
                    (-> (expect (:long-running? s))          (.toBe false))
                    (-> (expect (nil? (:category s)))        (.toBe true)))))

            (it "convenience predicates all return false for unknown names"
                (fn []
                  (-> (expect (tm/read-only? "nope"))             (.toBe false))
                  (-> (expect (tm/destructive? "nope"))           (.toBe false))
                  (-> (expect (tm/requires-confirmation? "nope")) (.toBe false))
                  (-> (expect (tm/network? "nope"))               (.toBe false))
                  (-> (expect (tm/long-running? "nope"))          (.toBe false))))))

;;; ─── Built-in tools ───────────────────────────────────
;;; Every tool shipped in agent.tools is classified here. Drift
;;; between the source and this test file means either a tool was
;;; renamed or a metadata entry was lost — either way, caller code
;;; would silently fall back to the 'unknown' default, which is
;;; exactly the silent-failure class we're trying to prevent.

(describe "tool-safety: read-only tools"
          (fn []
            (beforeEach (fn [] (reset!)))

            (it "read is read-only + :file"
                (fn []
                  (-> (expect (tm/read-only? "read")) (.toBe true))
                  (-> (expect (tm/destructive? "read")) (.toBe false))
                  (-> (expect (tm/category "read")) (.toBe "file"))))

            (it "ls is read-only + :file"
                (fn []
                  (-> (expect (tm/read-only? "ls")) (.toBe true))
                  (-> (expect (tm/category "ls")) (.toBe "file"))))

            (it "glob is read-only + :search"
                (fn []
                  (-> (expect (tm/read-only? "glob")) (.toBe true))
                  (-> (expect (tm/category "glob")) (.toBe "search"))))

            (it "grep is read-only + :search"
                (fn []
                  (-> (expect (tm/read-only? "grep")) (.toBe true))))

            (it "think is read-only + :meta and has no category side-effects"
                (fn []
                  (-> (expect (tm/read-only? "think")) (.toBe true))
                  (-> (expect (tm/destructive? "think")) (.toBe false))))))

(describe "tool-safety: destructive tools"
          (fn []
            (beforeEach (fn [] (reset!)))

            (it "write is destructive and requires confirmation"
                (fn []
                  (-> (expect (tm/destructive? "write")) (.toBe true))
                  (-> (expect (tm/requires-confirmation? "write")) (.toBe true))
                  (-> (expect (tm/read-only? "write")) (.toBe false))))

            (it "edit is destructive and requires confirmation"
                (fn []
                  (-> (expect (tm/destructive? "edit")) (.toBe true))
                  (-> (expect (tm/requires-confirmation? "edit")) (.toBe true))))

            (it "bash is destructive, requires confirmation, AND long-running"
                (fn []
                  (-> (expect (tm/destructive? "bash")) (.toBe true))
                  (-> (expect (tm/requires-confirmation? "bash")) (.toBe true))
                  (-> (expect (tm/long-running? "bash")) (.toBe true))
                  (-> (expect (tm/category "bash")) (.toBe "shell"))))))

(describe "tool-safety: network tools"
          (fn []
            (beforeEach (fn [] (reset!)))

            (it "web_fetch is read-only AND network"
                (fn []
                  (-> (expect (tm/read-only? "web_fetch")) (.toBe true))
                  (-> (expect (tm/network? "web_fetch")) (.toBe true))
                  (-> (expect (tm/destructive? "web_fetch")) (.toBe false))))

            (it "web_search is read-only AND network"
                (fn []
                  (-> (expect (tm/read-only? "web_search")) (.toBe true))
                  (-> (expect (tm/network? "web_search")) (.toBe true))))))

;;; ─── Extension metadata overrides ─────────────────────

(describe "register-metadata! / unregister-metadata!"
          (fn []
            (beforeEach (fn [] (reset!)))

            (it "registers a new tool's safety profile"
                (fn []
                  (tm/register-metadata! "git-push"
                                         {:destructive? true
                                          :requires-confirmation? true
                                          :network? true
                                          :category :network})
                  (-> (expect (tm/destructive? "git-push")) (.toBe true))
                  (-> (expect (tm/network? "git-push")) (.toBe true))
                  (-> (expect (tm/requires-confirmation? "git-push")) (.toBe true))))

            (it "extension metadata OVERRIDES a built-in entry"
                (fn []
        ;; An extension may wrap 'bash' to add a sandbox — it can
        ;; then re-declare bash as non-destructive for that scope.
                  (tm/register-metadata! "bash"
                                         {:destructive? false :requires-confirmation? false})
                  (-> (expect (tm/destructive? "bash")) (.toBe false))
                  (-> (expect (tm/requires-confirmation? "bash")) (.toBe false))))

            (it "unregister-metadata! removes the override, falling back to built-in"
                (fn []
                  (tm/register-metadata! "bash"
                                         {:destructive? false :requires-confirmation? false})
                  (tm/unregister-metadata! "bash")
        ;; Back to the built-in profile: destructive + needs confirmation.
                  (-> (expect (tm/destructive? "bash")) (.toBe true))
                  (-> (expect (tm/requires-confirmation? "bash")) (.toBe true))))

            (it "reset-extension-metadata! wipes every extension override"
                (fn []
                  (tm/register-metadata! "a" {:destructive? true})
                  (tm/register-metadata! "b" {:destructive? true})
                  (tm/reset-extension-metadata!)
                  (-> (expect (tm/destructive? "a")) (.toBe false))
                  (-> (expect (tm/destructive? "b")) (.toBe false))))

            (it "register-metadata! coerces the tool name to a string"
                (fn []
                  (tm/register-metadata! :keyword-name {:destructive? true})
                  (-> (expect (tm/destructive? "keyword-name")) (.toBe true))))))

;;; ─── Shape guarantees ─────────────────────────────────

(describe "tool-safety: return shape"
          (fn []
            (it "always includes every safety key (even for unknown tools)"
                (fn []
                  (let [s (tm/tool-safety "random")]
          ;; Contract: callers can destructure :read-only? :destructive?
          ;; etc. without worrying about missing fields.
                    (-> (expect (contains? s :read-only?)) (.toBe true))
                    (-> (expect (contains? s :destructive?)) (.toBe true))
                    (-> (expect (contains? s :requires-confirmation?)) (.toBe true))
                    (-> (expect (contains? s :network?)) (.toBe true))
                    (-> (expect (contains? s :long-running?)) (.toBe true))
                    (-> (expect (contains? s :category)) (.toBe true)))))))
