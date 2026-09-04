(ns openwiki.test
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.extensions.openwiki.shared :as shared]
            [agent.extensions.openwiki.tools :as tools]
            [agent.extensions.openwiki.metadata :as md]
            [agent.extensions.openwiki.git :as git]
            [agent.extensions.openwiki.commands :as commands]))

;; ── shared/config ────────────────────────────────────────────────

(describe "openwiki config" (fn []
                              (it "defaults when no settings (off by default)"
                                  (fn []
                                    (-> (expect (:dir (shared/config nil))) (.toBe "openwiki"))
                                    (-> (expect (:enabled (shared/config nil))) (.toBe false))))
                              (it "overrides dir/enabled from settings#openwiki"
                                  (fn []
                                    (let [c (shared/config #js {:openwiki #js {:dir "docs" :enabled true}})]
                                      (-> (expect (:dir c)) (.toBe "docs"))
                                      (-> (expect (:enabled c)) (.toBe true))
                                      (-> (expect (:sections c)) (.toEqual (:sections shared/default-config))))))
                              (it "has no dead :model key — the verbs never call a model"
                                  (fn []
                                    (-> (expect (contains? shared/default-config :model)) (.toBe false))
                                    (-> (expect (contains? (shared/config #js {:openwiki #js {:model "x"}}) :model))
                                        (.toBe false))))))

;; ── under-dir? is a prefix check, not a substring one ─────────────

(describe "openwiki under-dir?" (fn []
                                  (it "matches paths inside the wiki dir"
                                      (fn []
                                        (-> (expect (shared/under-dir? "openwiki" "openwiki/index.md")) (.toBe true))
                                        (-> (expect (shared/under-dir? "openwiki" "./openwiki/a/b.md")) (.toBe true))
                                        (-> (expect (shared/under-dir? "openwiki" "openwiki/a.md")) (.toBe true))))
                                  (it "does not match a same-named source directory"
                                      (fn []
                                        (-> (expect (shared/under-dir? "openwiki" "src/agent/extensions/openwiki/git.cljs"))
                                            (.toBe false))
                                        (-> (expect (shared/under-dir? "openwiki" "notopenwiki/x.md")) (.toBe false))
                                        (-> (expect (shared/under-dir? "openwiki" nil)) (.toBe false))))))

;; ── OKF v0.2 conformance ─────────────────────────────────────────

(def ^:private page "---\ntype: architecture\ntitle: Loop\n---\n\n# Loop\n")

(describe "openwiki OKF conformance" (fn []
                                       (it "parses a frontmatter block and its body"
                                           (fn []
                                             (let [r (shared/parse-frontmatter page)]
                                               (-> (expect (get (:fields r) "type")) (.toBe "architecture"))
                                               (-> (expect (get (:fields r) "title")) (.toBe "Loop"))
                                               (-> (expect (.includes (:body r) "# Loop")) (.toBe true)))))

                                       (it "reports no frontmatter at all"
                                           (fn []
                                             (let [r (shared/parse-frontmatter "# Just a heading")]
                                               (-> (expect (:fields r)) (.toBeNil)))))

                                       (it "a conformant page has no violations"
                                           (fn []
                                             (-> (expect (count (shared/page-violations "architecture/loop.md" page)))
                                                 (.toBe 0))))

                                       (it "flags a page with no frontmatter"
                                           (fn []
                                             (-> (expect (count (shared/page-violations "a.md" "# no fm")))
                                                 (.toBe 1))))

                                       (it "flags an empty or missing type — the one required field"
                                           (fn []
                                             (-> (expect (count (shared/page-violations "a.md" "---\ntitle: x\n---\nbody")))
                                                 (.toBe 1))
                                             (-> (expect (count (shared/page-violations "a.md" "---\ntype:   \n---\nbody")))
                                                 (.toBe 1))))

                                       (it "root index.md must declare okf_version; other indexes carry none"
                                           (fn []
                                             (-> (expect (count (shared/page-violations "index.md" "---\nokf_version: \"0.2\"\n---\n# Wiki")))
                                                 (.toBe 0))
                                             (-> (expect (count (shared/page-violations "index.md" "# Wiki")))
                                                 (.toBe 0))
                                             (-> (expect (count (shared/page-violations "index.md" "---\ntype: x\n---\n")))
                                                 (.toBe 1))
                                             (-> (expect (count (shared/page-violations "sub/index.md" "---\ntype: x\n---\n")))
                                                 (.toBe 1))))

                                       (it "log.md is reserved and carries no frontmatter"
                                           (fn []
                                             (-> (expect (count (shared/page-violations "log.md" "## 2026-08-27\n")))
                                                 (.toBe 0))
                                             (-> (expect (count (shared/page-violations "log.md" "---\ntype: x\n---\n")))
                                                 (.toBe 1))))

                                       (it "conformance-report is nil for a clean bundle, a string otherwise"
                                           (fn []
                                             (-> (expect (shared/conformance-report [["a.md" page]])) (.toBeNil))
                                             (-> (expect (.includes (shared/conformance-report [["a.md" "# x"]]) "a.md"))
                                                 (.toBe true))))))

;; ── tools/ensure-section (AGENTS.md idempotency + staleness) ─────

(describe "openwiki ensure-section" (fn []
                                      (let [section (tools/agents-section "openwiki" ["architecture" "testing"])]
                                        (it "creates when file is missing (nil)"
                                            (fn [] (-> (expect (tools/ensure-section nil section)) (.toBe section))))
                                        (it "appends once to an existing file without the section"
                                            (fn []
                                              (let [out (tools/ensure-section "# Repo\n" section)]
                                                (-> (expect (.startsWith out "# Repo")) (.toBe true))
                                                (-> (expect (.includes out "## OpenWiki")) (.toBe true)))))
                                        (it "is a no-op when the identical section already exists"
                                            (fn []
                                              (-> (expect (tools/ensure-section (str "stuff\n\n" section) section))
                                                  (.toBeNil))))
                                        (it "replaces a stale block pointing at the old dir"
                                            (fn []
                                              (let [old (tools/agents-section "docs" ["architecture" "testing"])
                                                    out (tools/ensure-section (str "# Repo\n\n" old) section)]
                                                (-> (expect (.includes out "openwiki/index.md")) (.toBe true))
                                                (-> (expect (.includes out "docs/index.md")) (.toBe false))
                                                (-> (expect (.includes out "# Repo")) (.toBe true)))))
                                        (it "preserves content that follows the block"
                                            (fn []
                                              (let [old (tools/agents-section "docs" ["a"])
                                                    out (tools/ensure-section (str old "\n## Later\nkeep me\n") section)]
                                                (-> (expect (.includes out "keep me")) (.toBe true))
                                                (-> (expect (.includes out "## Later")) (.toBe true))))))))

;; ── agents-section derives from configured :sections ─────────────

(describe "openwiki agents-section" (fn []
                                      (it "describes the configured sections, not a hardcoded list"
                                          (fn []
                                            (let [s (tools/agents-section "openwiki" ["frontend" "backend"])]
                                              (-> (expect (.includes s "frontend, backend")) (.toBe true))
                                              (-> (expect (.includes s "domain concepts")) (.toBe false)))))
                                      (it "points at index.md, the OKF entry point"
                                          (fn []
                                            (-> (expect (.includes (tools/agents-section "openwiki" ["a"]) "openwiki/index.md"))
                                                (.toBe true))))))

;; ── command handler: args is a CLJS SEQ (run-command! gives (rest parts)) ──
;; This is the regression guard for the args-as-JS-array crash.

(defn- fake-ctx []
  (let [fq (atom []) notes (atom [])]
    {:ctx #js {:agent {:follow-queue fq}
               :ui    #js {:notify (fn [m _l] (swap! notes conj m))}}
     :fq fq :notes notes}))

(describe "openwiki command handler (seq args)" (fn []
                                                  (let [handler (commands/make-handler {:dir "openwiki" :sections ["a"]})]

                                                    (it "init primes a follow-up from a real cljs seq without throwing"
                                                        (fn []
                                                          (let [{:keys [ctx fq notes]} (fake-ctx)]
                                                            (handler (list "init") ctx)
                                                            (-> (expect (count @fq)) (.toBe 1))
                                                            (-> (expect (.includes (:content (first @fq)) "OpenWiki")) (.toBe true))
                                                            (-> (expect (count @notes)) (.toBe 1)))))

                                                    (it "the removed chat verb is just an unknown verb now"
                                                        (fn []
                                                          (let [{:keys [ctx fq notes]} (fake-ctx)]
                                                            (handler (list "chat" "where" "is" "x") ctx)
                                                            (-> (expect (count @fq)) (.toBe 0))
                                                            (-> (expect (.includes (first @notes) "init | update")) (.toBe true)))))

                                                    (it "empty/unknown verb notifies usage, does not prime or throw"
                                                        (fn []
                                                          (let [{:keys [ctx fq notes]} (fake-ctx)]
                                                            (handler (list) ctx)
                                                            (handler (list "bogus") ctx)
                                                            (-> (expect (count @fq)) (.toBe 0))
                                                            (-> (expect (count @notes)) (.toBe 2))))))))

;; ── git ──────────────────────────────────────────────────────────

(describe "openwiki git" (fn []
                           (it "changes-since is empty for a blank/nil sha"
                               (fn []
                                 (-> (expect (git/changes-since "")) (.toBe ""))
                                 (-> (expect (git/changes-since nil)) (.toBe ""))))

                           (it "repo-tree summarizes the whole repo instead of an alphabetical prefix"
                               (fn []
                                 (let [t (git/repo-tree)]
                                   ;; Guard for the `ls-files | head -200` truncation: on this repo
                                   ;; that stopped inside src/agent/extensions/claude_hook_bridge,
                                   ;; so anything later in the alphabet proves the cut is gone.
                                   (-> (expect (.includes t "src/gateway")) (.toBe true))
                                   (-> (expect (.includes t "src/agent/providers")) (.toBe true))
                                   (-> (expect (.includes t "tracked files")) (.toBe true)))))))

;; ── metadata: snapshot hash + gitHead always advancing ───────────

(def ^:private tmp (atom nil))
(beforeEach (fn []
              (let [d (path/join (os/tmpdir) (str "nyma-ow-" (js/Date.now)))]
                (fs/mkdirSync d #js {:recursive true})
                (reset! tmp d))))
(afterEach (fn []
             (when @tmp (try (fs/rmSync @tmp #js {:recursive true :force true}) (catch :default _)))))

(describe "openwiki metadata" (fn []
                                (it "snapshot-hash changes with content and is stable otherwise"
                                    (fn []
                                      (let [dir (path/join @tmp "openwiki")]
                                        (fs/mkdirSync dir #js {:recursive true})
                                        (fs/writeFileSync (path/join dir "a.md") "hello")
                                        (let [h1 (md/snapshot-hash dir)]
                                          (-> (expect (> (count h1) 0)) (.toBe true))
                                          (-> (expect (md/snapshot-hash dir)) (.toBe h1))       ; stable
                                          (fs/writeFileSync (path/join dir "a.md") "changed")
                                          (-> (expect (= (md/snapshot-hash dir) h1)) (.toBe false))))))

                                (it "excludes .last-update.json from the hash"
                                    (fn []
                                      (let [dir (path/join @tmp "openwiki")]
                                        (fs/mkdirSync dir #js {:recursive true})
                                        (fs/writeFileSync (path/join dir "a.md") "hello")
                                        (let [h1 (md/snapshot-hash dir)]
                                          (fs/writeFileSync (path/join dir ".last-update.json") "{\"x\":1}")
                                          (-> (expect (md/snapshot-hash dir)) (.toBe h1))))))

                                ;; The regression that matters: an unchanged wiki must still
                                ;; rewrite metadata. Skipping the write pinned gitHead, so every
                                ;; later /openwiki update replayed the same commits and primed
                                ;; another full agent run, forever.
                                (it "always writes metadata, reporting :unchanged rather than skipping"
                                    (fn []
                                      (let [dir (path/join @tmp "openwiki")]
                                        (fs/mkdirSync dir #js {:recursive true})
                                        (fs/writeFileSync (path/join dir "a.md") "hello")
                                        (let [r1 (md/save-metadata dir "init" "m" ["arch"])]
                                          (-> (expect (:unchanged r1)) (.toBe false))
                                          (-> (expect (fs/existsSync (shared/metadata-file dir))) (.toBe true))
                                          (let [t1 (aget (md/load-metadata dir) "updatedAt")
                                                r2 (md/save-metadata dir "update" "m" ["arch"])
                                                t2 (aget (md/load-metadata dir) "updatedAt")]
                                            (-> (expect (:unchanged r2)) (.toBe true))
                                            ;; written anyway — this is the whole point
                                            (-> (expect (aget (:metadata r2) "updatedAt")) (.toBe t2))
                                            (-> (expect (>= t2 t1)) (.toBe true)))
                                          (fs/writeFileSync (path/join dir "b.md") "new page")
                                          (-> (expect (:unchanged (md/save-metadata dir "update" "m" ["arch"])))
                                              (.toBe false))))))

                                (it "records okfVersion and the section taxonomy"
                                    (fn []
                                      (let [dir (path/join @tmp "openwiki")]
                                        (fs/mkdirSync dir #js {:recursive true})
                                        (md/save-metadata dir "init" "m" ["arch" "testing"])
                                        (let [m (md/load-metadata dir)]
                                          (-> (expect (aget m "okfVersion")) (.toBe shared/okf-version))
                                          (-> (expect (vec (aget m "sections"))) (.toEqual ["arch" "testing"]))))))

                                (it "okf-report finds violations in a real bundle and nil when clean"
                                    (fn []
                                      (let [dir (path/join @tmp "openwiki")]
                                        (fs/mkdirSync dir #js {:recursive true})
                                        (fs/writeFileSync (path/join dir "index.md") "# Wiki")
                                        (fs/writeFileSync (path/join dir "bad.md") "# no frontmatter")
                                        (-> (expect (.includes (md/okf-report dir) "bad.md")) (.toBe true))
                                        (fs/writeFileSync (path/join dir "bad.md") page)
                                        (-> (expect (md/okf-report dir)) (.toBeNil)))))))

;; ── Regressions from code review ─────────────────────────────────

(describe "openwiki — review regressions" (fn []

  (it "never reports the user's INSTRUCTIONS.md as an OKF violation"
      (fn []
        ;; It is user-authored scope the agent is told never to rewrite, and
        ;; the prompt says "if it reports OKF violations, fix them" — so
        ;; flagging it invited the agent to edit the one file it must not touch.
        (-> (expect (count (shared/page-violations "INSTRUCTIONS.md" "Only document src/.\n")))
            (.toBe 0))
        (-> (expect (shared/conformance-report
                     [["index.md" "# Wiki"]
                      ["INSTRUCTIONS.md" "prose, no frontmatter"]]))
            (.toBeNil))))

  (it "keeps user content under a ### heading when refreshing the AGENTS block"
      (fn []
        ;; Terminating the block only on h1/h2 spliced out a following `### …`
        ;; and everything under it — new data loss in a file the user owns.
        (let [new-sec (tools/agents-section "openwiki" ["a"])
              old-sec (tools/agents-section "docs" ["a"])
              out (tools/ensure-section
                   (str "# Repo\n\n" old-sec "\n### Notes\nKEEP THIS\n\n## Later\nkeep\n")
                   new-sec)]
          (-> (expect (.includes out "KEEP THIS")) (.toBe true))
          (-> (expect (.includes out "### Notes")) (.toBe true))
          (-> (expect (.includes out "## Later")) (.toBe true))
          ;; and it still repoints the stale link
          (-> (expect (.includes out "openwiki/index.md")) (.toBe true))
          (-> (expect (.includes out "docs/index.md")) (.toBe false)))))))
