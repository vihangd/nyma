(ns autocomplete-provider.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.ui.autocomplete-provider
             :refer [detect-trigger
                     active-trigger?
                     should-open-picker?
                     replace-trigger-token
                     create-provider-registry
                     complete-all
                     readdir-cached
                     clear-readdir-cache!]]))

;;; ─── detect-trigger ─────────────────────────────────────

(describe "detect-trigger" (fn []
                             (it "detects :slash when text starts with /"
                                 (fn []
                                   (let [t (detect-trigger "/clear")]
                                     (-> (expect (contains? t :slash)) (.toBe true)))))

                             (it "detects :at when text ends with @"
                                 (fn []
                                   (let [t (detect-trigger "hello @")]
                                     (-> (expect (contains? t :at)) (.toBe true)))))

  ;; Phase 21: @ must be at a word boundary (start of text or after
  ;; whitespace). Prevents `email@foo.com` style mid-word @ from
  ;; firing the mention picker.
                             (it ":at fires when @ is at the very start of text"
                                 (fn []
                                   (-> (expect (contains? (detect-trigger "@") :at)) (.toBe true))))

                             (it ":at does NOT fire for @ embedded mid-word (e.g. email)"
                                 (fn []
                                   (-> (expect (contains? (detect-trigger "user@") :at)) (.toBe false))
                                   (-> (expect (contains? (detect-trigger "foo.bar@") :at)) (.toBe false))))

                             (it ":at fires after a tab or newline, not just space"
                                 (fn []
                                   (-> (expect (contains? (detect-trigger "hi\t@") :at)) (.toBe true))
                                   (-> (expect (contains? (detect-trigger "hi\n@") :at)) (.toBe true))))

  ;; Phase 21: :path trigger was dropped. File references now go
  ;; exclusively through @-mentions. Bare `/` and `//` used to fire
  ;; :path because of `.endsWith '/'`, which mixed file listings into
  ;; the slash-command picker. The new behavior: text that looks
  ;; path-like (src/, ./foo, /foo/bar) no longer opens ANY picker
  ;; unless it also matches :slash or :at.
                             (it ":path trigger no longer exists (phase 21 removal)"
                                 (fn []
                                   (-> (expect (contains? (detect-trigger "src/") :path)) (.toBe false))
                                   (-> (expect (contains? (detect-trigger "./foo") :path)) (.toBe false))
                                   (-> (expect (contains? (detect-trigger "/foo/bar") :path)) (.toBe false))))

                             (it "bare / only fires :slash, not :path"
                                 (fn []
                                   (let [t (detect-trigger "/")]
                                     (-> (expect (contains? t :slash)) (.toBe true))
                                     (-> (expect (contains? t :path)) (.toBe false)))))

                             (it ":any trigger is always present"
                                 (fn []
                                   (let [t (detect-trigger "plain text")]
                                     (-> (expect (contains? t :any)) (.toBe true)))))

  ;; Regression: after a slash command is selected, the editor value becomes
  ;; "/agent " (with trailing space). Before the fix, detect-trigger still
  ;; returned :slash, so the picker reopened and stole the argument keystrokes
  ;; the user was trying to type (e.g. "qwen" in "/agent qwen").
                             (it ":slash does NOT fire once a space has been typed after the command"
                                 (fn []
                                   (-> (expect (contains? (detect-trigger "/agent ") :slash)) (.toBe false))
                                   (-> (expect (contains? (detect-trigger "/agent qwen") :slash)) (.toBe false))))

                             (it ":slash still fires while typing the command name"
                                 (fn []
                                   (-> (expect (contains? (detect-trigger "/") :slash)) (.toBe true))
                                   (-> (expect (contains? (detect-trigger "/ag") :slash)) (.toBe true))
                                   (-> (expect (contains? (detect-trigger "/agent") :slash)) (.toBe true))))

  ;; Phase 21: `//` is the literal agent-forward trigger. Must fire
  ;; :slash-forward (for the agent command picker) and MUST NOT also
  ;; fire :slash (which would race two pickers).
                             (it ":slash-forward fires when text starts with //"
                                 (fn []
                                   (-> (expect (contains? (detect-trigger "//") :slash-forward)) (.toBe true))
                                   (-> (expect (contains? (detect-trigger "//plan") :slash-forward)) (.toBe true))))

                             (it ":slash is suppressed when :slash-forward fires"
                                 (fn []
                                   (-> (expect (contains? (detect-trigger "//") :slash)) (.toBe false))
                                   (-> (expect (contains? (detect-trigger "//plan") :slash)) (.toBe false))))

                             (it ":slash-forward does not fire once a space is typed"
                                 (fn []
                                   (-> (expect (contains? (detect-trigger "//plan ") :slash-forward)) (.toBe false))
                                   (-> (expect (contains? (detect-trigger "//plan arg") :slash-forward)) (.toBe false))))))

;;; ─── active-trigger? ───────────────────────────────────
;;; Thin wrapper around detect-trigger — excludes :any (which is
;;; always present) so the caller gets a clear yes/no for 'should
;;; a picker open here'.

(describe "active-trigger?"
          (fn []
            (it "returns true for slash and at triggers"
                (fn []
                  (-> (expect (active-trigger? "/")) (.toBe true))
                  (-> (expect (active-trigger? "/agent")) (.toBe true))
                  (-> (expect (active-trigger? "hello @")) (.toBe true))))

            (it "returns false for path-like text (phase 21: :path removed)"
                (fn []
                  (-> (expect (active-trigger? "src/")) (.toBe false))
                  (-> (expect (active-trigger? "./foo")) (.toBe false))))

            (it "returns false for plain text (only :any is active)"
                (fn []
                  (-> (expect (active-trigger? "plain text")) (.toBe false))
                  (-> (expect (active-trigger? "hello world")) (.toBe false))))

            (it "returns false after a slash command has been completed with a space"
                (fn []
                  (-> (expect (active-trigger? "/agent ")) (.toBe false))
                  (-> (expect (active-trigger? "/agent qwen")) (.toBe false))))))

;;; ─── should-open-picker? ───────────────────────────────
;;; The decision function the app.cljs useEffect runs before opening
;;; the autocomplete picker. Regression target for the 'Escape
;;; snaps the picker back' bug — the dismissed-value gate has to
;;; short-circuit.

(describe "should-open-picker?"
          (fn []
            (it "returns true for a fresh slash trigger with no dismissal"
                (fn []
                  (-> (expect (should-open-picker? "/cl"
                                                   {:overlay? false
                                                    :streaming? false
                                                    :editor-hidden? false
                                                    :dismissed-value nil}))
                      (.toBe true))))

            (it "returns false when an overlay is already open"
                (fn []
                  (-> (expect (should-open-picker? "/cl" {:overlay? true}))
                      (.toBe false))))

            (it "returns false while streaming"
                (fn []
                  (-> (expect (should-open-picker? "/cl" {:streaming? true}))
                      (.toBe false))))

            (it "returns false when the editor is hidden"
                (fn []
                  (-> (expect (should-open-picker? "/cl" {:editor-hidden? true}))
                      (.toBe false))))

            (it "returns false for empty text"
                (fn []
                  (-> (expect (should-open-picker? "" {}))             (.toBe false))
                  (-> (expect (should-open-picker? nil {}))            (.toBe false))))

            (it "returns false for plain text that has no opening trigger"
                (fn []
                  (-> (expect (should-open-picker? "hello world" {})) (.toBe false))))

    ;; The core regression: if the user just pressed Escape on the picker
    ;; while editor-value was "/cl", the outer useEffect re-runs on the
    ;; next render with the SAME editor-value. Without the dismissal
    ;; gate, the picker would reopen immediately. With it, should-open-picker?
    ;; must return false.
            (it "returns false when text matches the dismissed-value (escape guard)"
                (fn []
                  (-> (expect (should-open-picker? "/cl" {:dismissed-value "/cl"}))
                      (.toBe false))))

            (it "returns true once the user types more, breaking the dismissal match"
                (fn []
                  (-> (expect (should-open-picker? "/cle" {:dismissed-value "/cl"}))
                      (.toBe true))))

            (it "returns true after user backspaces past the dismissed value to a shorter trigger"
                (fn []
        ;; "/cl" was dismissed. User hits backspace → "/c". Picker
        ;; should open again because the text is now different.
                  (-> (expect (should-open-picker? "/c" {:dismissed-value "/cl"}))
                      (.toBe true))))

            (it "dismissed-value does NOT block a different trigger kind"
                (fn []
        ;; User dismissed "/agent", then types "hello @" — that's an
        ;; at trigger for a totally different editor-value, must open.
                  (-> (expect (should-open-picker? "hello @" {:dismissed-value "/agent"}))
                      (.toBe true))))))

;;; ─── replace-trigger-token ─────────────────────────────
;;; Pure function extracted from app.cljs on-resolve. Mirrors the branch
;;; logic of detect-trigger so that the picker that opened ends up
;;; replacing the right range of the editor text. We've hit two bugs
;;; here already — a loose startsWith check clobbering @-mentions and
;;; paths typed after a slash command, and the picker reopening across
;;; command arguments — so every hit is locked in by a regression test.

(describe "replace-trigger-token" (fn []
                                    (it "replaces a partial slash command with the selected command + space"
                                        (fn []
                                          (-> (expect (replace-trigger-token "/cl" "/clear")) (.toBe "/clear "))))

                                    (it "replaces a bare slash with the selected command"
                                        (fn []
                                          (-> (expect (replace-trigger-token "/" "/help")) (.toBe "/help "))))

                                    (it "preserves the full command when user has already typed it"
                                        (fn []
                                          (-> (expect (replace-trigger-token "/agent" "/agent")) (.toBe "/agent "))))

                                    (it "replaces a trailing @ with the selected mention value + space"
                                        (fn []
                                          (-> (expect (replace-trigger-token "hello @" "file.txt")) (.toBe "hello file.txt "))))

                                    (it "handles @ at the very start of the editor"
                                        (fn []
                                          (-> (expect (replace-trigger-token "@" "file.txt")) (.toBe "file.txt "))))

                                    (it "replaces the last path segment after the final slash"
                                        (fn []
                                          (-> (expect (replace-trigger-token "src/" "main.cljs"))
                                              (.toBe "src/main.cljs"))))

                                    (it "handles ./ relative paths"
                                        (fn []
                                          (-> (expect (replace-trigger-token "./foo/" "bar.cljs"))
                                              (.toBe "./foo/bar.cljs"))))

                                    (it "returns value when text has no slash"
                                        (fn []
                                          (-> (expect (replace-trigger-token "plain" "chosen")) (.toBe "chosen"))))

  ;; Regression: before the second app.cljs fix, the startsWith "/" branch
  ;; fired for any text beginning with slash — even "/agent @" where a
  ;; mention picker (:at) had opened. Selecting a file wiped the entire
  ;; "/agent " command. Must route to the @ branch instead.
                                    (it "regression: /agent @ + file selection keeps the command intact"
                                        (fn []
                                          (-> (expect (replace-trigger-token "/agent @" "file.txt"))
                                              (.toBe "/agent file.txt "))))

  ;; Same root cause, path flavour: /agent src/ + path selection must
  ;; replace only the last segment, not the whole command.
                                    (it "regression: /agent src/ + path selection replaces only last segment"
                                        (fn []
                                          (-> (expect (replace-trigger-token "/agent src/" "main.cljs"))
                                              (.toBe "/agent src/main.cljs"))))

                                    (it "regression: @-mention mid-sentence replaces trailing @ only"
                                        (fn []
                                          (-> (expect (replace-trigger-token "look at @" "README.md"))
                                              (.toBe "look at README.md "))))

                                    (it "nil text is treated as empty string"
                                        (fn []
                                          (-> (expect (replace-trigger-token nil "x")) (.toBe "x"))))

                                    (it "nil val is treated as empty string"
                                        (fn []
                                          (-> (expect (replace-trigger-token "/cl" nil)) (.toBe " "))))))

;;; ─── create-provider-registry ──────────────────────────

(describe "create-provider-registry" (fn []
                                       (it "registers and retrieves providers"
                                           (fn []
                                             (let [reg (create-provider-registry)
                                                   p   {:trigger :slash :complete (fn [_] (js/Promise.resolve []))}]
                                               ((:register reg) "test" p)
                                               (-> (expect (:trigger ((:get reg) "test"))) (.toBe "slash")))))

                                       (it "unregisters providers"
                                           (fn []
                                             (let [reg (create-provider-registry)]
                                               ((:register reg) "t" {:trigger :any
                                                                     :complete (fn [_] (js/Promise.resolve []))})
                                               ((:unregister reg) "t")
                                               (-> (expect ((:get reg) "t")) (.toBe js/undefined)))))

                                       (it "list returns all registered providers"
                                           (fn []
                                             (let [reg (create-provider-registry)]
                                               ((:register reg) "a" {:trigger :slash
                                                                     :complete (fn [_] (js/Promise.resolve []))})
                                               ((:register reg) "b" {:trigger :at
                                                                     :complete (fn [_] (js/Promise.resolve []))})
                                               (-> (expect (count ((:list reg)))) (.toBe 2)))))))

;;; ─── complete-all ──────────────────────────────────────

(defn ^:async test-complete-all-routes-to-matching-trigger []
  (let [reg (create-provider-registry)
        slash-items [{:label "clear" :value "/clear"}
                     {:label "help" :value "/help"}]
        at-items    [{:label "file.cljs" :value "file.cljs"}]]
    ((:register reg) "slash"
                     {:trigger :slash :complete (fn [_] (js/Promise.resolve (clj->js slash-items)))})
    ((:register reg) "at"
                     {:trigger :at :complete (fn [_] (js/Promise.resolve (clj->js at-items)))})
    ;; With '/cl' only slash provider should run
    (let [results (js-await (complete-all reg "/cl"))]
      (-> (expect (pos? (count results))) (.toBe true))
      (-> (expect (every? (fn [r] (or (.includes (or (get r :label)
                                                     (get r "label") "") "clear")
                                      (.includes (or (get r :label)
                                                     (get r "label") "") "help")))
                          results))
          (.toBe true)))))

(defn ^:async test-complete-all-merges-any-providers []
  (let [reg (create-provider-registry)
        any-items [{:label "alpha"} {:label "beta"}]]
    ((:register reg) "any"
                     {:trigger :any :complete (fn [_] (js/Promise.resolve (clj->js any-items)))})
    (let [results (js-await (complete-all reg "plain"))]
      (-> (expect (pos? (count results))) (.toBe true)))))

(defn ^:async test-complete-all-fuzzy-sorts []
  (let [reg (create-provider-registry)
        items [{:label "barfoo"} {:label "foobar"}]]
    ;; Use :slash so the query is extracted and fuzzy-sort runs.
    ((:register reg) "slash"
                     {:trigger :slash :complete (fn [_] (js/Promise.resolve (clj->js items)))})
    (let [results (js-await (complete-all reg "/foo"))]
      ;; Prefix match 'foobar' should rank ahead of substring match 'barfoo'
      (-> (expect (or (get (first results) :label)
                      (get (first results) "label")))
          (.toBe "foobar")))))

(defn ^:async test-complete-all-handles-provider-error []
  (let [reg (create-provider-registry)
        good [{:label "one"}]]
    ((:register reg) "bad"
                     {:trigger :any
                      :complete (fn [_] (js/Promise.reject (js/Error. "boom")))})
    ((:register reg) "good"
                     {:trigger :any
                      :complete (fn [_] (js/Promise.resolve (clj->js good)))})
    (let [results (js-await (complete-all reg "o"))]
      (-> (expect (pos? (count results))) (.toBe true)))))

(describe "complete-all" (fn []
                           (it "routes to matching triggers" test-complete-all-routes-to-matching-trigger)
                           (it "merges :any providers" test-complete-all-merges-any-providers)
                           (it "fuzzy-sorts merged results" test-complete-all-fuzzy-sorts)
                           (it "swallows errors from failing providers"
                               test-complete-all-handles-provider-error)))

;;; ─── readdir-cached ────────────────────────────────────

(defn ^:async test-readdir-cached-returns-entries []
  (clear-readdir-cache!)
  (let [entries (js-await (readdir-cached "test"))]
    (-> (expect (pos? (count entries))) (.toBe true))))

(defn ^:async test-readdir-cached-returns-empty-on-error []
  (clear-readdir-cache!)
  (let [entries (js-await (readdir-cached "/nonexistent/path/xyz123"))]
    (-> (expect (count entries)) (.toBe 0))))

(defn ^:async test-readdir-cached-hits-cache-on-second-call []
  (clear-readdir-cache!)
  (let [first  (js-await (readdir-cached "test"))
        second (js-await (readdir-cached "test"))]
    ;; Same reference? Not guaranteed, but entries should be deep-equal.
    (-> (expect (count first)) (.toBe (count second)))))

(describe "readdir-cached" (fn []
                             (it "returns entries from the filesystem" test-readdir-cached-returns-entries)
                             (it "returns empty on error" test-readdir-cached-returns-empty-on-error)
                             (it "hits cache on repeat calls within TTL"
                                 test-readdir-cached-hits-cache-on-second-call)))
