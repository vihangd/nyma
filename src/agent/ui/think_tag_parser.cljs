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

(defn split-think-blocks
  "Parse inline think tags from accumulated text.
   Returns {:reasoning combined-inner-text :text clean-text}.

   Handles three shapes:
   - Closed blocks: <think>…</think> — extracted and removed from :text.
   - Unterminated trailing block: <think>… (no close tag yet) — everything after
     the opener goes to :reasoning; everything before goes to :text.
     This is critical during live streaming when the close tag has not yet arrived.
   - Orphan closer: …</think>… (a </think> with no opener) — everything before the
     closer is :reasoning, everything after is :text. This is what poolside/Laguna
     and other template-prefilled-<think> models emit.

   Multiple closed blocks have their inner text joined with \\n\\n."
  [text]
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
        ;; No opener; check for an orphan closer (template-prefilled <think>).
        (let [close-match (.exec close-re clean)]
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

(defn strip-think-tags
  "Remove inline think tags, returning clean text only.
   Convenience wrapper around split-think-blocks for use at summarization sites."
  [text]
  (:text (split-think-blocks text)))
