(ns agent.extensions.spec-driven.speckit-adapter
  "Spec-kit convention adapter — single source of truth for the
   text-level conventions nyma's `/spec` extension shares with
   github/spec-kit (clarify and analyze phases especially).

   Pinned to spec-kit v0.8.5 (May 4 2026). When spec-kit ships a new
   version, update the constants and prompt-template references here
   FIRST; the rest of the spec-driven code reads them via this layer.

   Conventions documented:
     - `## Clarifications` / `### Session YYYY-MM-DD` shape (clarify.md)
     - `[NEEDS CLARIFICATION: ...]` inline markers (spec-template.md)
     - `FR-###` / `SC-###` requirement / success-criterion ID prefixes
     - 4-level severity (CRITICAL / HIGH / MEDIUM / LOW)

   Everything in this namespace is pure (no fs/process side effects)
   except `read-constitution`, which reads `.specify/memory/constitution.md`
   when present. Pure helpers should be cheap to test and snapshot
   against real spec-kit fixtures."
  (:require ["node:fs"   :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

;; ── Versioning ────────────────────────────────────────────────

(def speckit-version
  "The spec-kit release whose conventions we mirror. Bump only after
   the snapshot test against fixtures/speckit-<ver>/ passes."
  "0.8.5")

;; ── Section conventions (clarify.md) ──────────────────────────

(def clarifications-heading "## Clarifications")

(defn session-heading
  "Markdown sub-heading for a single clarify session, dated."
  [iso-date]
  (str "### Session " iso-date))

(def session-heading-prefix "### Session ")

;; Bullet shape: `- Q: <question> → A: <answer>`
(def qa-bullet-prefix "- Q: ")
(def qa-separator " → A: ")

;; ── Inline markers (spec-template.md) ─────────────────────────

(def needs-clarification-re
  "Matches a [NEEDS CLARIFICATION: <text>] marker. Captures the
   inner text in group 1. Case-sensitive by spec-kit convention."
  ;; JS regex form (not CLJS literal) — squint compiles it more
  ;; predictably and we use .exec / .match throughout.
  "\\[NEEDS CLARIFICATION:\\s*([^\\]]+)\\]")

(defn make-needs-clarification-re []
  (js/RegExp. needs-clarification-re "g"))

;; ── ID conventions (spec-template.md) ─────────────────────────

;; Per spec-kit: 3+ digit zero-padded; FR-001, FR-006, SC-200.
(def fr-id-re   "\\bFR-(\\d{3,})\\b")
(def sc-id-re   "\\bSC-(\\d{3,})\\b")

(defn make-fr-id-re [] (js/RegExp. fr-id-re "g"))
(defn make-sc-id-re [] (js/RegExp. sc-id-re "g"))

;; ── Severity (analyze.md) ─────────────────────────────────────

(def critical-severity "CRITICAL")
(def severity-levels   ["CRITICAL" "HIGH" "MEDIUM" "LOW"])

;; ── Pure parsers ──────────────────────────────────────────────

(defn- find-section-bounds
  "Given lines (vec of strings) and a heading string, return
   [start-idx end-idx-exclusive] of the section body (excluding the
   heading line) — or nil if heading not found.

   Section ends at the next heading of the SAME OR HIGHER level
   (`##` or `#`), or end-of-file."
  [lines heading]
  (let [n        (count lines)
        ;; '##' counts as level 2; we end on level <= 2 but not on
        ;; deeper sub-sections (### Session, etc.).
        heading-level (->> heading
                           (take-while #(= % \#))
                           count)
        start    (loop [i 0]
                   (cond
                     (>= i n) nil
                     (= (get lines i) heading) i
                     :else (recur (inc i))))]
    (when start
      (let [body-start (inc start)
            end (loop [i body-start]
                  (cond
                    (>= i n) n
                    (let [ln (get lines i)
                          lev (count (take-while #(= % \#) ln))]
                      (and (pos? lev) (<= lev heading-level)
                           ;; Pure heading line (count then space).
                           (or (= (count ln) lev)
                               (= (.charAt ln lev) " "))))
                    i
                    :else (recur (inc i))))]
        [body-start end]))))

(defn parse-clarifications
  "Parse the `## Clarifications` section of a spec.md.
   Returns a vec of {:date <iso> :qa [{:q <str> :a <str>} ...]}
   sessions in document order. Returns [] if the section doesn't
   exist or contains no `### Session <date>` blocks."
  [spec-content]
  (when (string? spec-content)
    (let [lines  (vec (str/split-lines spec-content))
          bounds (find-section-bounds lines clarifications-heading)]
      (if-not bounds
        []
        (let [[s e]    bounds
              section  (subvec lines s e)
              session-prefix session-heading-prefix
              ;; Walk the section, collecting per-session Q&As.
              sessions (atom [])
              current  (atom nil)
              flush!   (fn []
                         (when @current
                           (swap! sessions conj @current)
                           (reset! current nil)))]
          (doseq [ln section]
            (cond
              (.startsWith ln session-prefix)
              (do (flush!)
                  (reset! current
                          {:date (.trim (subs ln (count session-prefix)))
                           :qa   []}))

              (and @current (.startsWith ln qa-bullet-prefix))
              (let [body (subs ln (count qa-bullet-prefix))
                    sep-idx (.indexOf body qa-separator)]
                (when (>= sep-idx 0)
                  (swap! current update :qa conj
                         {:q (.trim (subs body 0 sep-idx))
                          :a (.trim (subs body (+ sep-idx (count qa-separator))))})))

              :else nil))
          (flush!)
          @sessions)))))

(defn- find-overview-end
  "Per spec-kit's clarify.md, the `## Clarifications` section goes
   'just after the highest-level contextual/overview section.' We
   approximate that as: just before the SECOND level-2 heading
   (i.e. after the first one's body — which is typically `## Overview`
   or a similar context section). When there's only one level-2 heading,
   put Clarifications at the end. When there are zero, also at end.
   Returns the line index BEFORE which to insert."
  [lines]
  (let [n (count lines)
        ;; Collect all level-2 heading line indices, skipping our own
        ;; Clarifications heading (callers should only invoke this when
        ;; the section is absent, but be defensive).
        h2-idxs (->> (range n)
                     (filter (fn [i]
                               (let [ln (get lines i)]
                                 (and (.startsWith ln "## ")
                                      (not= ln clarifications-heading)))))
                     vec)]
    (cond
      (>= (count h2-idxs) 2) (nth h2-idxs 1)  ; before second h2 = after first h2's body
      :else                  n)))             ; <2 h2s → end

(defn append-clarification-session
  "Pure: produce new spec content with a new `### Session <date>` block
   appended to the `## Clarifications` section. If the section doesn't
   exist, create it just before the first non-Clarifications level-2
   heading (or at end). qa-pairs is a vec of {:q :a} maps.

   Re-runnability: existing sessions are preserved verbatim — this is
   append-only on the section, not overwrite."
  [spec-content iso-date qa-pairs]
  (let [lines    (vec (str/split-lines (or spec-content "")))
        existing (find-section-bounds lines clarifications-heading)
        block-lines
        (vec (concat
              [(session-heading iso-date)]
              (mapv (fn [{:keys [q a]}]
                      (str qa-bullet-prefix q qa-separator a))
                    qa-pairs)
              [""]))]
    (if existing
      ;; Insert at end of existing section.
      (let [[_ end] existing
            ;; Trim trailing blank lines inside the section so the new
            ;; block doesn't pile up newlines on re-runs.
            trim-end (loop [i (dec end)]
                       (if (and (> i 0) (str/blank? (get lines i)))
                         (recur (dec i))
                         (inc i)))
            before (subvec lines 0 trim-end)
            after  (subvec lines trim-end)
            ;; One blank line between the previous session and the new one.
            sep    [""]]
        (str/join "\n"
                  (concat before sep block-lines after)))
      ;; Create the section just before the first other level-2 heading.
      (let [insert-at (find-overview-end lines)
            before    (subvec lines 0 insert-at)
            after     (subvec lines insert-at)
            new-section (vec (concat [""
                                      clarifications-heading
                                      ""]
                                     block-lines))]
        (str/join "\n" (concat before new-section after))))))

(defn replace-needs-clarification
  "Pure: swap the FIRST occurrence of `[NEEDS CLARIFICATION: <marker-text>]`
   in `spec-content` with `replacement`. Marker matching is exact on the
   inner text (case-sensitive, whitespace-trimmed). Returns the new
   content unchanged if no match."
  [spec-content marker-text replacement]
  (when (string? spec-content)
    (let [needle (str "[NEEDS CLARIFICATION: " (str/trim marker-text) "]")
          idx    (.indexOf spec-content needle)]
      (if (< idx 0)
        spec-content
        (str (subs spec-content 0 idx)
             replacement
             (subs spec-content (+ idx (count needle))))))))

(defn extract-needs-clarifications
  "Pure: return a vec of {:line-idx <int> :text <str> :context <line>}
   for every [NEEDS CLARIFICATION: ...] marker in document order.
   `:text` is the inner marker text (trimmed); `:context` is the full
   line containing the marker (useful for the model when prioritizing
   which markers to clarify first)."
  [spec-content]
  (if-not (string? spec-content)
    []
    (let [lines (vec (str/split-lines spec-content))
          out   (atom [])]
      (doseq [i (range (count lines))]
        (let [ln (get lines i)
              re (make-needs-clarification-re)]
          (loop []
            (let [m (.exec re ln)]
              (when m
                (swap! out conj
                       {:line-idx i
                        :text     (.trim (aget m 1))
                        :context  ln})
                (recur))))))
      @out)))

(defn- parse-id-occurrences
  "Find every match of `re-builder` in `spec-content`, return
   [{:id :line-idx :text}] in document order."
  [spec-content re-builder prefix]
  (if-not (string? spec-content)
    []
    (let [lines (vec (str/split-lines spec-content))
          out   (atom [])]
      (doseq [i (range (count lines))]
        (let [ln (get lines i)
              re (re-builder)]
          (loop []
            (let [m (.exec re ln)]
              (when m
                (swap! out conj
                       {:id       (str prefix "-" (aget m 1))
                        :line-idx i
                        :text     ln})
                (recur))))))
      @out)))

(defn parse-fr-ids
  "Return every FR-### occurrence in `spec-content` in document order.
   Same FR may appear multiple times (e.g. once on definition, again
   in a downstream task) — caller can dedupe via :id if needed."
  [spec-content]
  (parse-id-occurrences spec-content make-fr-id-re "FR"))

(defn parse-sc-ids
  "Return every SC-### occurrence in `spec-content` in document order."
  [spec-content]
  (parse-id-occurrences spec-content make-sc-id-re "SC"))

;; ── Constitution access ───────────────────────────────────────

(defn read-constitution
  "Read `.specify/memory/constitution.md` if present. Returns the content
   string or nil. The MUST/SHOULD distinction is the analyze command's
   responsibility — this is a flat read."
  [cwd]
  (let [p (path/join cwd ".specify" "memory" "constitution.md")]
    (when (fs/existsSync p)
      (try (fs/readFileSync p "utf8")
           (catch :default _ nil)))))

;; ── Safe template interpolation ────────────────────────────────

(defn interpolate-template
  "Single-pass substitution of N `%s` placeholders in `template` with
   the N strings in `values`. Critically, user values are NOT re-scanned
   for further `%s` matches — this prevents user-authored `%s` literals
   in spec content from consuming downstream placeholders.

   `chained-replace` (the naïve `(.replace tpl \"%s\" v1).replace ...`
   pattern) DOES have that bug: if `v1` contains `%s`, the next
   `.replace` targets that injected `%s` instead of the next intended
   placeholder, shifting all subsequent values.

   Throws if the template's `%s` count doesn't match `(count values)`."
  [template values]
  (let [parts (vec (.split template "%s"))
        n     (count values)
        slots (dec (count parts))]
    (when (not= slots n)
      (throw (js/Error.
              (str "Template/value count mismatch: template has " slots
                   " `%s` placeholders, but " n " values were supplied."))))
    (loop [i 0 acc (first parts)]
      (if (>= i n)
        acc
        (recur (inc i)
               (str acc (nth values i) (nth parts (inc i))))))))
