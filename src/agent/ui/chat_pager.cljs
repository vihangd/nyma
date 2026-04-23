(ns agent.ui.chat-pager
  "In-app chat history pager. Keeps ALL turns in React state and
   renders a windowed slice bounded by the terminal viewport height,
   with scroll-offset controlling which slice is visible.

   Unlike scrollback-mode=true (writeToStdout commits to terminal
   scrollback) and scrollback-mode=false (<Static> emissions to real
   scrollback), pager mode never leaves the Ink frame: past turns are
   reachable with in-app key bindings, no terminal-scroll required.

   Rendering model:
     - scroll-offset = number of messages to hide from the END of the
       list (0 = show latest, max = show oldest).
     - The slice is `messages[0, count - scroll-offset)`.
     - Box with justifyContent flex-end + overflow hidden: the slice's
       LAST messages fill the viewport from the bottom up; earlier
       entries in the slice clip off the top when they exceed viewport
       height. This is the expected chat-app UX: scroll up → older
       messages appear at top, newer disappear off bottom.

   The scroll-indicator bar renders above the message slice when
   scroll-offset > 0, so the user always knows when they're not at
   the bottom."
  {:squint/extension "jsx"}
  (:require ["ink" :refer [Box Text]]
            ["./chat_view.jsx" :refer [MessageBubble]]))

(defn clamp-scroll-offset
  "Pure: clamp scroll-offset to [0, max-offset] where max-offset =
   max(0, message-count - 1). The lower bound keeps scroll-offset
   non-negative (0 = at bottom). The upper bound prevents scrolling
   past the oldest message (one visible msg minimum).

   Exported and tested directly."
  [offset message-count]
  (let [max-offset (max 0 (dec message-count))]
    (-> offset
        (max 0)
        (min max-offset))))

(defn visible-slice
  "Pure: return the visible message slice given the full list and the
   current scroll-offset. Drops the last `scroll-offset` messages from
   the end. Box flex-end + overflow hidden handles viewport fitting."
  [messages scroll-offset]
  (let [v (vec messages)
        total (count v)
        end (- total (clamp-scroll-offset scroll-offset total))]
    (if (pos? end)
      (subvec v 0 end)
      [])))

(defn auto-follow-offset
  "Pure: when new messages arrive, maintain the user's visible window.
   If they were at the bottom (offset=0), keep them at the bottom
   (follow). If they were scrolled up (offset>0), advance offset by
   the count of new messages so the view doesn't shift under them.

   `prev-offset` is the scroll-offset before the message list changed.
   `prev-count` and `next-count` are the list lengths before and after.
   Returns the new scroll-offset to apply.

   Exported and tested directly."
  [prev-offset prev-count next-count]
  (let [delta (- next-count prev-count)]
    (cond
      ;; No growth (message count shrunk or unchanged) — don't mess with
      ;; offset. The clamp below catches any out-of-range state.
      (not (pos? delta))
      (clamp-scroll-offset prev-offset next-count)

      ;; User is at the bottom — keep following.
      (zero? prev-offset)
      0

      ;; User scrolled up — advance offset by delta so their window
      ;; stays anchored on the same messages.
      :else
      (clamp-scroll-offset (+ prev-offset delta) next-count))))

(defn page-step
  "Pure: default page-scroll step in messages. Small enough to keep
   context, large enough to feel like a page. Fixed constant so the
   math in tests is deterministic regardless of terminal height."
  []
  10)

(defn ChatPager
  "Pager component. Renders a bounded slice of `messages` at the bottom
   of a flex Box, with a scroll-indicator above when scrolled up.

   Props:
     :messages        - full list of messages (includes the streaming tail)
     :theme           - theme map
     :block-renderers - custom block renderers (from agent)
     :streaming       - boolean; the tail of the slice shows as live when true
     :scroll-offset   - number of messages hidden from the end"
  [{:keys [messages theme block-renderers streaming scroll-offset]}]
  (let [all      (vec messages)
        total    (count all)
        offset   (clamp-scroll-offset scroll-offset total)
        slice    (visible-slice all offset)
        slice-n  (count slice)
        muted    (get-in theme [:colors :muted] "#565f89")]
    #jsx [Box {:flexDirection "column"
               :flexGrow       1
               :overflow       "hidden"}
          (when (pos? offset)
            #jsx [Box {:flexShrink 0 :paddingX 1}
                  [Text {:color muted}
                   (str "↑ " offset
                        (if (= offset 1) " newer message" " newer messages")
                        " · Ctrl+End to jump to latest")]])
          [Box {:flexDirection "column"
                :flexGrow       1
                :justifyContent "flex-end"
                :overflow       "hidden"}
           (map-indexed
            (fn [i msg]
              (let [tail? (= i (dec slice-n))]
                #jsx [MessageBubble {:key             (or (:id msg) (str "pager-" i))
                                     :message         msg
                                     :theme           theme
                                     :block-renderers block-renderers
                                     :is-live         (and tail? (boolean streaming))}]))
            slice)]]))
