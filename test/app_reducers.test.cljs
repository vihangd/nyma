(ns app-reducers.test
  "Tests for the tool-lifecycle message reducers in agent.ui.app-reducers.

   These are the bridge between middleware's `tool_execution_*` payloads and
   what chat-renderer draws, so a field middleware emits and the reducer drops
   is invisible until somebody stares at a wrong transcript."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.app-reducers :as r]))

(def ^:private verbosity "collapsed")
(def ^:private max-lines 500)

(defn- start! [msgs data] (r/apply-tool-start msgs data verbosity max-lines))
(defn- end!   [msgs data] (r/apply-tool-end   msgs data verbosity max-lines))

;;; ─── a failed tool keeps its failure ──────────────────────────────────────
;;
;; middleware.cljs emits :isError on tool_execution_end. apply-tool-end never
;; copied it, so by the time chat-renderer saw the message there was nothing
;; left to distinguish a tool that threw from one that succeeded — and it drew
;; "✓" on both.

(describe "app-reducers/failed tool keeps its failure"
          (fn []
            (it "copies :isError onto the tool-end message"
                (fn []
                  (let [msgs (-> []
                                 (start! {:toolName "bash" :execId "e1" :args {:command "false"}})
                                 (end!   {:toolName "bash" :execId "e1" :result "exit 1"
                                          :duration 12 :isError true}))
                        m    (first msgs)]
                    (-> (expect (:role m))     (.toBe "tool-end"))
                    (-> (expect (:is-error m)) (.toBe true)))))

            (it "leaves :is-error absent on a successful call"
                (fn []
                  (let [msgs (-> []
                                 (start! {:toolName "read" :execId "e2" :args {:path "/f"}})
                                 (end!   {:toolName "read" :execId "e2" :result "a\nb"
                                          :duration 3 :isError false}))]
                    (-> (expect (boolean (:is-error (first msgs)))) (.toBe false)))))

            (it "keeps the failure on an end with no matching start"
                (fn []
                  (let [msgs (end! [] {:toolName "glob" :execId "orphan"
                                       :result "boom" :isError true})]
                    (-> (expect (:is-error (first msgs))) (.toBe true)))))))

;;; ─── in-flight progress ───────────────────────────────────────────────────

(describe "app-reducers/in-flight tool progress"
          (fn []
            (it "writes the update's text onto the running tool-start"
                (fn []
                  (let [msgs (-> []
                                 (start! {:toolName "web_fetch" :execId "e3" :args {:url "u"}})
                                 (r/apply-tool-update {:execId "e3" :data "downloading 40%"}))]
                    (-> (expect (:custom-status-text (first msgs)))
                        (.toBe "downloading 40%")))))

            (it "the end event replaces the message, so the status cannot go stale"
                (fn []
                  (let [msgs (-> []
                                 (start! {:toolName "web_fetch" :execId "e4" :args {:url "u"}})
                                 (r/apply-tool-update {:execId "e4" :data "downloading 40%"})
                                 (end!   {:toolName "web_fetch" :execId "e4" :result "ok"}))]
                    (-> (expect (:custom-status-text (first msgs))) (.toBeUndefined)))))))

;;; ─── large pastes are pi-tui's job ────────────────────────────────────────
;;
;; This namespace used to carry a bracketed-paste state machine
;; (make-guarded-setter / make-ink-paste-fn / make-stdin-paste-handler) left
;; over from the ink UI, appending a `[paste #N +X lines]` marker by hand.
;; Nothing called it: pi-tui's Editor already collapses a >10-line paste into
;; exactly that marker (pi-tui README, "Large paste handling"). The code was
;; deleted; this test exists so it does not come back.

(describe "app-reducers/large paste handling belongs to pi-tui's editor"
          (fn []
            (it "exports no bracketed-paste helpers of its own"
                (fn []
                  (-> (expect (fn? r/make-guarded-setter))      (.toBe false))
                  (-> (expect (fn? r/make-ink-paste-fn))        (.toBe false))
                  (-> (expect (fn? r/make-stdin-paste-handler)) (.toBe false))))))
