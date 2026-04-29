(ns sessions-compaction-anchored.test
  "Tests for anchored iterative summarization in sessions/compaction.

   Two pieces under test:
   1. new-span-after-last-compaction — slices to the new span only.
   2. build-compact-user-prompt — produces explicit MERGE instructions
      when a previous-summary is supplied."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.sessions.compaction :refer [new-span-after-last-compaction
                                               build-compact-user-prompt]]))

;;; ─── new-span-after-last-compaction ───────────────────────────────────────

(describe "new-span-after-last-compaction" (fn []
                                             (it "returns the full slice when no prior compaction exists"
                                                 (fn []
                                                   (let [slice [{:role "user" :content "q1"}
                                                                {:role "assistant" :content "a1"}
                                                                {:role "user" :content "q2"}]
                                                         {:keys [span prev-compaction]} (new-span-after-last-compaction slice)]
                                                     (-> (expect (count span)) (.toBe 3))
                                                     (-> (expect prev-compaction) (.toBeNil)))))

                                             (it "drops messages up to and including the most recent compaction"
                                                 (fn []
                                                   (let [slice [{:role "user" :content "q1"}
                                                                {:role "assistant" :content "a1"}
                                                                {:role "compaction" :content "summary v1"}
                                                                {:role "user" :content "q2"}
                                                                {:role "assistant" :content "a2"}
                                                                {:role "user" :content "q3"}]
                                                         {:keys [span prev-compaction]} (new-span-after-last-compaction slice)]
                                                     (-> (expect (count span)) (.toBe 3))
                                                     (-> (expect (:content (first span))) (.toBe "q2"))
                                                     (-> (expect (:content prev-compaction)) (.toBe "summary v1")))))

                                             (it "uses the LAST compaction when multiple exist"
                                                 (fn []
                                                   (let [slice [{:role "compaction" :content "summary v1"}
                                                                {:role "user" :content "q1"}
                                                                {:role "compaction" :content "summary v2"}
                                                                {:role "user" :content "q2"}
                                                                {:role "assistant" :content "a2"}]
                                                         {:keys [span prev-compaction]} (new-span-after-last-compaction slice)]
                                                     (-> (expect (count span)) (.toBe 2))
                                                     (-> (expect (:content prev-compaction)) (.toBe "summary v2")))))

                                             (it "returns empty span when slice ends with compaction"
                                                 (fn []
                                                   (let [slice [{:role "user" :content "q"}
                                                                {:role "compaction" :content "summary"}]
                                                         {:keys [span prev-compaction]} (new-span-after-last-compaction slice)]
                                                     (-> (expect (count span)) (.toBe 0))
                                                     (-> (expect (:content prev-compaction)) (.toBe "summary")))))

                                             (it "handles empty slice"
                                                 (fn []
                                                   (let [{:keys [span prev-compaction]} (new-span-after-last-compaction [])]
                                                     (-> (expect (count span)) (.toBe 0))
                                                     (-> (expect prev-compaction) (.toBeNil)))))))

;;; ─── build-compact-user-prompt — fresh summary path ──────────────────────

(describe "build-compact-user-prompt/fresh" (fn []
                                              (it "omits the merge block when no previous-summary is supplied"
                                                  (fn []
                                                    (let [prompt (build-compact-user-prompt
                                                                  {:to-summarize [{:role "user" :content "hi"}]})]
                                                      (-> (expect (.includes prompt "<previous-summary>")) (.toBe false))
                                                      (-> (expect (.includes prompt "MERGE")) (.toBe false))
                                                      (-> (expect (.includes prompt "<conversation>")) (.toBe true)))))))

;;; ─── build-compact-user-prompt — anchored iterative path ─────────────────

(describe "build-compact-user-prompt/anchored" (fn []
                                                 (it "wraps the prior summary in <previous-summary>"
                                                     (fn []
                                                       (let [prompt (build-compact-user-prompt
                                                                     {:previous-summary "old summary content"
                                                                      :to-summarize [{:role "user" :content "new"}]})]
                                                         (-> (expect (.includes prompt "<previous-summary>\nold summary content\n</previous-summary>"))
                                                             (.toBe true)))))

                                                 (it "uses the word MERGE explicitly"
                                                     (fn []
                                                       (let [prompt (build-compact-user-prompt
                                                                     {:previous-summary "x"
                                                                      :to-summarize    []})]
                                                         (-> (expect (.includes prompt "MERGE")) (.toBe true)))))

                                                 (it "tells the model not to regenerate unaffected sections"
                                                     (fn []
                                                       (let [prompt (build-compact-user-prompt
                                                                     {:previous-summary "x"
                                                                      :to-summarize    []})]
                                                         (-> (expect (.includes prompt "Do NOT regenerate")) (.toBe true)))))

                                                 (it "preserves verbatim quote / file path discipline"
                                                     (fn []
                                                       (let [prompt (build-compact-user-prompt
                                                                     {:previous-summary "x"
                                                                      :to-summarize    []})]
                                                         (-> (expect (.includes prompt "Preserve verbatim quotes"))
                                                             (.toBe true)))))

                                                 (it "instructs how to handle contradictions"
                                                     (fn []
                                                       (let [prompt (build-compact-user-prompt
                                                                     {:previous-summary "x"
                                                                      :to-summarize    []})]
                                                         (-> (expect (.includes prompt "contradicts")) (.toBe true))
                                                         (-> (expect (.includes prompt "Problem Solving")) (.toBe true)))))

                                                 (it "demands all 6 sections in the output"
                                                     (fn []
                                                       (let [prompt (build-compact-user-prompt
                                                                     {:previous-summary "x"
                                                                      :to-summarize    []})]
                                                         (-> (expect (.includes prompt "all 6 sections")) (.toBe true)))))

                                                 (it "still includes files-read and files-modified blocks"
                                                     (fn []
                                                       (let [prompt (build-compact-user-prompt
                                                                     {:previous-summary "x"
                                                                      :to-summarize    []
                                                                      :files-read       ["src/a.cljs"]
                                                                      :files-modified   ["src/b.cljs"]})]
                                                         (-> (expect (.includes prompt "<files-read>\nsrc/a.cljs")) (.toBe true))
                                                         (-> (expect (.includes prompt "<files-modified>\nsrc/b.cljs")) (.toBe true)))))))
