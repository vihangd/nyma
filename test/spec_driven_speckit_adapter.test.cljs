(ns spec-driven-speckit-adapter.test
  "Tests for the spec-kit convention adapter — the centralized layer
   that parses/produces the textual conventions nyma shares with
   github/spec-kit (clarify and analyze phases)."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs"   :as fs]
            ["node:os"   :as os]
            ["node:path" :as path]
            ["./agent/extensions/spec_driven/speckit_adapter.mjs" :as adapter]))

(defn- mktmp []
  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-speckit-")))

;; ── Constants ──────────────────────────────────────────────────

(describe "speckit-adapter/constants"
          (fn []
            (it "speckit-version pinned to 0.8.5"
                (fn []
                  (-> (expect adapter/speckit-version) (.toBe "0.8.5"))))

            (it "clarifications heading matches spec-kit verbatim"
                (fn []
                  (-> (expect adapter/clarifications-heading) (.toBe "## Clarifications"))))

            (it "session-heading produces ### Session <iso-date>"
                (fn []
                  (-> (expect (adapter/session-heading "2026-05-05"))
                      (.toBe "### Session 2026-05-05"))))

            (it "severity levels match spec-kit's 4-level scale"
                (fn []
                  (let [s (vec adapter/severity-levels)]
                    (-> (expect (count s)) (.toBe 4))
                    (-> (expect (first s)) (.toBe "CRITICAL"))
                    (-> (expect (last s))  (.toBe "LOW")))))))

;; ── parse-clarifications ───────────────────────────────────────

(describe "adapter/parse-clarifications"
          (fn []
            (it "no Clarifications section → empty vec"
                (fn []
                  (-> (expect (count (adapter/parse-clarifications "# Spec\n\nbody")))
                      (.toBe 0))))

            (it "section with no sessions → empty vec"
                (fn []
                  (-> (expect (count (adapter/parse-clarifications
                                      "# Spec\n## Clarifications\nbody")))
                      (.toBe 0))))

            (it "single session with two Q&A pairs"
                (fn []
                  (let [content (str "# Spec\n\n"
                                     "## Clarifications\n\n"
                                     "### Session 2026-05-05\n"
                                     "- Q: What auth? → A: OAuth 2.0\n"
                                     "- Q: Max upload size? → A: 25 MB\n")
                        sessions (adapter/parse-clarifications content)]
                    (-> (expect (count sessions)) (.toBe 1))
                    (let [s (first sessions)]
                      (-> (expect (:date s)) (.toBe "2026-05-05"))
                      (-> (expect (count (:qa s))) (.toBe 2))
                      (-> (expect (:q (first (:qa s)))) (.toBe "What auth?"))
                      (-> (expect (:a (first (:qa s)))) (.toBe "OAuth 2.0"))))))

            (it "multiple sessions preserved in document order"
                (fn []
                  (let [content (str "## Clarifications\n\n"
                                     "### Session 2026-04-22\n"
                                     "- Q: A? → A: a\n\n"
                                     "### Session 2026-04-29\n"
                                     "- Q: B? → A: b\n")
                        sessions (adapter/parse-clarifications content)]
                    (-> (expect (count sessions)) (.toBe 2))
                    (-> (expect (:date (first sessions))) (.toBe "2026-04-22"))
                    (-> (expect (:date (second sessions))) (.toBe "2026-04-29")))))

            (it "stops at next level-2 heading"
                (fn []
                  ;; Anything after another `## ...` is NOT in the section.
                  (let [content (str "## Clarifications\n"
                                     "### Session 2026-05-05\n"
                                     "- Q: real? → A: yes\n"
                                     "## Other\n"
                                     "### Session 2026-05-06\n"
                                     "- Q: bogus? → A: no\n")
                        sessions (adapter/parse-clarifications content)]
                    (-> (expect (count sessions)) (.toBe 1)))))))

;; ── append-clarification-session ───────────────────────────────

(describe "adapter/append-clarification-session"
          (fn []
            (it "creates the section when absent — appends at end if only one h2"
                (fn []
                  (let [orig (str "# Spec\n\n"
                                  "Some intro.\n\n"
                                  "## Requirements\n"
                                  "- FR-001: do thing\n")
                        out (adapter/append-clarification-session
                             orig "2026-05-05"
                             [{:q "auth?" :a "OAuth"}])]
                    (-> (expect (.includes out "## Clarifications")) (.toBe true))
                    (-> (expect (.includes out "### Session 2026-05-05")) (.toBe true))
                    (-> (expect (.includes out "- Q: auth? → A: OAuth")) (.toBe true))
                    ;; Only one h2 (## Requirements), so Clarifications goes
                    ;; AFTER it (at end), not before.
                    (let [c-idx (.indexOf out "## Clarifications")
                          r-idx (.indexOf out "## Requirements")]
                      (-> (expect (> c-idx r-idx)) (.toBe true))))))

            (it "with multiple h2s, inserts AFTER the first overview section"
                (fn []
                  ;; spec-kit rule: 'just after the highest-level
                  ;; contextual/overview section.' With Overview + Reqs,
                  ;; Clarifications lands between them.
                  (let [orig (str "# Spec\n\n"
                                  "## Overview\n"
                                  "Background here.\n\n"
                                  "## Requirements\n"
                                  "- FR-001: do thing\n")
                        out (adapter/append-clarification-session
                             orig "2026-05-05" [{:q "x?" :a "y"}])
                        o-idx (.indexOf out "## Overview")
                        c-idx (.indexOf out "## Clarifications")
                        r-idx (.indexOf out "## Requirements")]
                    (-> (expect (and (< o-idx c-idx) (< c-idx r-idx)))
                        (.toBe true)))))

            (it "extends an existing section append-only"
                (fn []
                  (let [orig (str "## Clarifications\n\n"
                                  "### Session 2026-04-22\n"
                                  "- Q: old? → A: yes\n")
                        out (adapter/append-clarification-session
                             orig "2026-05-05"
                             [{:q "new?" :a "no"}])]
                    ;; Both sessions present
                    (-> (expect (.includes out "Session 2026-04-22")) (.toBe true))
                    (-> (expect (.includes out "Session 2026-05-05")) (.toBe true))
                    (-> (expect (.includes out "old?")) (.toBe true))
                    (-> (expect (.includes out "new?")) (.toBe true))
                    ;; Old session BEFORE new session
                    (let [old-idx (.indexOf out "Session 2026-04-22")
                          new-idx (.indexOf out "Session 2026-05-05")]
                      (-> (expect (< old-idx new-idx)) (.toBe true))))))

            (it "appends at end when there are no other level-2 headings"
                (fn []
                  (let [orig "# Spec\n\nIntro.\n"
                        out (adapter/append-clarification-session
                             orig "2026-05-05" [{:q "x?" :a "y"}])]
                    (-> (expect (.includes out "## Clarifications")) (.toBe true))
                    (-> (expect (.includes out "Intro.")) (.toBe true)))))))

;; ── replace-needs-clarification ────────────────────────────────

(describe "adapter/replace-needs-clarification"
          (fn []
            (it "swaps a marker with the resolution text"
                (fn []
                  (let [orig (str "FR-001: System MUST authenticate users via "
                                  "[NEEDS CLARIFICATION: auth method] for SSO.")
                        out  (adapter/replace-needs-clarification
                              orig "auth method" "OAuth 2.0 with PKCE")]
                    (-> (expect (.includes out "OAuth 2.0 with PKCE")) (.toBe true))
                    (-> (expect (.includes out "[NEEDS CLARIFICATION:")) (.toBe false)))))

            (it "no match → returns content unchanged"
                (fn []
                  (let [orig "no markers here"
                        out  (adapter/replace-needs-clarification
                              orig "missing" "replacement")]
                    (-> (expect out) (.toBe orig)))))

            (it "trims whitespace on the marker text for matching"
                (fn []
                  (let [orig "Pre [NEEDS CLARIFICATION: foo bar] post"
                        out  (adapter/replace-needs-clarification
                              orig "  foo bar  " "RESOLVED")]
                    (-> (expect (.includes out "RESOLVED")) (.toBe true)))))))

;; ── extract-needs-clarifications ───────────────────────────────

(describe "adapter/extract-needs-clarifications"
          (fn []
            (it "no markers → empty vec"
                (fn []
                  (-> (expect (count (adapter/extract-needs-clarifications "no markers")))
                      (.toBe 0))))

            (it "returns marker text trimmed and the full context line"
                (fn []
                  (let [content (str "Line one\n"
                                     "FR-006: [NEEDS CLARIFICATION: auth method - OAuth or SAML?] should X.\n"
                                     "FR-007: standard.")
                        markers (adapter/extract-needs-clarifications content)]
                    (-> (expect (count markers)) (.toBe 1))
                    (let [m (first markers)]
                      (-> (expect (:text m))
                          (.toBe "auth method - OAuth or SAML?"))
                      (-> (expect (:line-idx m)) (.toBe 1))
                      (-> (expect (.includes (:context m) "FR-006:")) (.toBe true))))))

            (it "multiple markers in order"
                (fn []
                  (let [content (str "[NEEDS CLARIFICATION: a]\n"
                                     "filler\n"
                                     "[NEEDS CLARIFICATION: b]\n")
                        markers (adapter/extract-needs-clarifications content)]
                    (-> (expect (count markers)) (.toBe 2))
                    (-> (expect (:text (first markers))) (.toBe "a"))
                    (-> (expect (:text (second markers))) (.toBe "b"))
                    (-> (expect (:line-idx (first markers))) (.toBe 0))
                    (-> (expect (:line-idx (second markers))) (.toBe 2)))))))

;; ── parse-fr-ids / parse-sc-ids ────────────────────────────────

(describe "adapter/parse-fr-ids and parse-sc-ids"
          (fn []
            (it "matches 3+ digit zero-padded IDs"
                (fn []
                  (let [content (str "FR-001 first\n"
                                     "FR-006 sixth\n"
                                     "FR-200 two hundred\n"
                                     "FR-1 too short — should NOT match\n"
                                     "FR-1234 four-digit OK\n")
                        ids (adapter/parse-fr-ids content)
                        seen (set (map :id ids))]
                    (-> (expect (contains? seen "FR-001")) (.toBe true))
                    (-> (expect (contains? seen "FR-006")) (.toBe true))
                    (-> (expect (contains? seen "FR-200")) (.toBe true))
                    (-> (expect (contains? seen "FR-1234")) (.toBe true))
                    (-> (expect (contains? seen "FR-1")) (.toBe false)))))

            (it "preserves document order with line-idx"
                (fn []
                  (let [content "FR-001\nfiller\nFR-002\n"
                        ids (adapter/parse-fr-ids content)]
                    (-> (expect (count ids)) (.toBe 2))
                    (-> (expect (:line-idx (first ids))) (.toBe 0))
                    (-> (expect (:line-idx (second ids))) (.toBe 2)))))

            (it "parse-sc-ids matches SC-### only"
                (fn []
                  (let [content "FR-001 should not match\nSC-100 success criterion"
                        ids (adapter/parse-sc-ids content)]
                    (-> (expect (count ids)) (.toBe 1))
                    (-> (expect (:id (first ids))) (.toBe "SC-100")))))))

;; ── interpolate-template ──────────────────────────────────────

(describe "adapter/interpolate-template"
          (fn []
            (it "single-pass — user values cannot consume downstream placeholders"
                (fn []
                  ;; Regression: chained `.replace` had a substitution-leakage
                  ;; bug where a user value containing literal "%s" would eat
                  ;; the next placeholder. Single-pass interpolate fixes this.
                  (let [t "A=%s B=%s C=%s"
                        out (adapter/interpolate-template t ["x %s y" "P" "Q"])]
                    ;; A gets the user's "x %s y" verbatim; B gets "P", not consumed.
                    (-> (expect (.includes out "A=x %s y")) (.toBe true))
                    (-> (expect (.includes out "B=P"))      (.toBe true))
                    (-> (expect (.includes out "C=Q"))      (.toBe true)))))

            (it "throws when value count doesn't match placeholder count"
                (fn []
                  (let [threw? (atom false)]
                    (try (adapter/interpolate-template "%s %s" ["only one"])
                         (catch :default _ (reset! threw? true)))
                    (-> (expect @threw?) (.toBe true)))))

            (it "no placeholders + no values → identity"
                (fn []
                  (-> (expect (adapter/interpolate-template "no slots here" []))
                      (.toBe "no slots here"))))

            (it "consecutive placeholders with empty separator"
                (fn []
                  (-> (expect (adapter/interpolate-template "%s%s" ["a" "b"]))
                      (.toBe "ab"))))))

;; ── read-constitution ──────────────────────────────────────────

(describe "adapter/read-constitution"
          (fn []
            (it "returns nil when no constitution file exists"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (-> (expect (adapter/read-constitution tmp)) (.toBeNil))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))

            (it "returns the file contents when present"
                (fn []
                  (let [tmp (mktmp)]
                    (try
                      (fs/mkdirSync (path/join tmp ".specify/memory")
                                    #js {:recursive true})
                      (fs/writeFileSync (path/join tmp ".specify/memory/constitution.md")
                                        "# Constitution\n- MUST be excellent\n")
                      (let [c (adapter/read-constitution tmp)]
                        (-> (expect (.includes c "MUST be excellent")) (.toBe true)))
                      (finally
                        (fs/rmSync tmp #js {:recursive true :force true}))))))))
