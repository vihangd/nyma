(ns agent.ui.think-tag-parser
  "Parse inline <think>…</think> tags emitted by reasoning models that stream
   chain-of-thought through the normal content channel (MiniMax M2, DeepSeek-R1,
   Qwen-QwQ, GLM-4.6, Kimi). Designed for render-time use — raw tags survive
   in storage for interleaved-thinking round-trips."
  (:require [clojure.string :as str]))

;; Matches a complete <think>…</think> or <thinking>…</thinking> block.
;; Flags: g=replace-all, i=case-insensitive.
;; [\s\S]*? is non-greedy and matches across newlines without the s flag.
(def ^:private closed-re
  (js/RegExp. "<(think(?:ing)?)>([\\s\\S]*?)<\\/\\1>\\s*" "gi"))

;; Matches an *opening* <think> or <thinking> tag with no corresponding closer.
;; No g flag — we only look for the first occurrence.
(def ^:private open-re
  (js/RegExp. "<think(?:ing)?>" "i"))

;; Matches a *closing* </think>/</thinking> tag with no corresponding opener.
;; Happens when the model's chat template pre-fills the opening <think> in the
;; prompt (poolside/Laguna interleaved-thinking recipe): the model's first
;; special token is the closer, so a bare </think> arrives in the content
;; channel. `close-re` (no g) finds the first orphan; `close-re-g` strips the rest.
(def ^:private close-re
  (js/RegExp. "<\\/think(?:ing)?>" "i"))

(def ^:private close-re-g
  (js/RegExp. "<\\/think(?:ing)?>" "gi"))

(def ^:private scan-open (js/RegExp. "<think(?:ing)?>" "i"))
(def ^:private scan-close (js/RegExp. "<\\/think(?:ing)?>" "i"))

(defn- partial-tag
  "Where a tag may be starting at the end of `s`, cut off by a chunk boundary."
  [s tags]
  (let [i (.lastIndexOf s "<")]
    (when (and (>= i 0)
               (some #(.startsWith % (.toLowerCase (subs s i))) tags))
      i)))

(defn scan
  "One streamed chunk through the tags. `state` is {:in? :held}, nil to start;
   the tags can land anywhere across chunks, so a possible start is held back.
   Returns [state pieces], pieces being [:text s] [:think s] [:open] [:close].
   Pure."
  [state chunk]
  (loop [s   (str (:held state) chunk)
         in? (boolean (:in? state))
         out []]
    (if-let [m (.exec (if in? scan-close scan-open) s)]
      (let [before (subs s 0 (.-index m))]
        (recur (subs s (+ (.-index m) (count (aget m 0))))
               (not in?)
               (cond-> out
                 (seq before) (conj [(if in? :think :text) before])
                 true         (conj [(if in? :close :open)]))))
      (let [cut (or (partial-tag s (if in? ["</think>" "</thinking>"] ["<think>" "<thinking>"]))
                    (count s))]
        [{:in? in? :held (subs s cut)}
         (cond-> out (pos? cut) (conj [(if in? :think :text) (subs s 0 cut)]))]))))

(defn split-blocks*
  "Core parser. `orphan?` enables the orphan-closer branch (see
   `split-think-blocks`). Callers that persist their output pass false — a
   false positive there permanently truncates stored text.

   Returns {:reasoning combined-inner-text :text clean-text}."
  [text orphan?]
  (if (empty? text)
    {:reasoning "" :text ""}
    (let [parts      (atom [])
          ;; Replace all closed blocks, collecting inner text as reasoning.
          ;; String.prototype.replace with a global regex calls the fn per match.
          clean      (.replace text closed-re
                               (fn [_match _tag inner]
                                 (swap! parts conj inner)
                                 ""))
          ;; Check for an unterminated opening tag in the remaining text.
          open-match (.exec open-re clean)]
      (if open-match
        (let [idx     (.-index open-match)
              tag-len (count (aget open-match 0))
              before  (subs clean 0 idx)
              after   (subs clean (+ idx tag-len))]
          {:reasoning (str/join "\n\n" (conj @parts after))
           :text      before})
        ;; No opener; maybe an orphan closer (template-prefilled <think>).
        ;; Requires `orphan?` AND that no closed block was found: a model that
        ;; pairs its tags properly never emits an orphan, so a stray </think>
        ;; alongside a closed pair is literal text (e.g. prose about think tags),
        ;; not a prefill artifact — reclassifying it would eat real answer text.
        (let [close-match (when (and orphan? (empty? @parts))
                            (.exec close-re clean))]
          (if close-match
            (let [idx     (.-index close-match)
                  tag-len (count (aget close-match 0))
                  before  (subs clean 0 idx)
                  ;; Strip any further stray closers from the answer text.
                  after   (.trimStart (.replace (subs clean (+ idx tag-len)) close-re-g ""))]
              {:reasoning (str/join "\n\n" (conj @parts before))
               :text      after})
            {:reasoning (str/join "\n\n" @parts)
             :text      clean}))))))

(defn split-think-blocks
  "Parse inline think tags from accumulated text.
   Returns {:reasoning combined-inner-text :text clean-text}.

   Handles three shapes:
   - Closed blocks: <think>…</think> — extracted and removed from :text.
   - Unterminated trailing block: <think>… (no close tag yet) — everything after
     the opener goes to :reasoning; everything before goes to :text.
     This is critical during live streaming when the close tag has not yet arrived.
   - Orphan closer: …</think>… (a </think> with no opener AND no closed block in
     the same text) — everything before the closer is :reasoning, everything after
     is :text. This is what poolside/Laguna and other template-prefilled-<think>
     models emit.

   Render-time only, so an orphan-closer false positive is a transient
   mis-render. Persisting callers must use `strip-think-tags`, which is
   deliberately conservative.

   Multiple closed blocks have their inner text joined with \\n\\n."
  [text]
  (split-blocks* text true))

(defn strip-think-tags
  "Remove inline think tags, returning clean text only — for summarization and
   compaction sites, whose output is PERSISTED.

   Handles closed blocks and an unterminated opener, but deliberately NOT the
   orphan closer: a message merely mentioning `</think>` in prose or code would
   otherwise have everything before it silently dropped from the stored text."
  [text]
  (:text (split-blocks* text false)))
