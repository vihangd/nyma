(ns agent.ui.chat-renderer
  "Pure: message map → string[] for pi-tui rendering."
  (:require [agent.ui.themes :refer [icon]]
            ["@earendil-works/pi-tui" :refer [visibleWidth truncateToWidth]]
            [agent.utils.ansi :as ansi :refer [fg]]
            [agent.utils.markdown-blocks :as mb]
            [agent.ui.diff-lines :refer [diff-lines]]
            [agent.ui.think-tag-parser :refer [split-think-blocks]]))

(defn clamp-line
  "Truncate one rendered line to `width`, measured the way pi-tui measures it.

   pi-tui THROWS on any line wider than the terminal, from inside its own render
   timer — it calls stop() first, so the exception escapes with the terminal
   already torn down and takes the session with it. There is no error hook and
   no strict-width opt-out.

   Deliberately uses pi-tui's `visibleWidth` / `truncateToWidth` rather than our
   `string-width` and `truncate-line-to-width`: ours route through `wrap-ansi`,
   which cannot break inside a grapheme cluster and so emits an over-wide one
   whole. pi-tui's slices by column and holds for regional-indicator flags, ZWJ
   families, CJK and ANSI alike. Agreeing with the function whose verdict
   crashes us is the point."
  [line width]
  (if (and (number? width) (pos? width) (> (visibleWidth line) width))
    (truncateToWidth line width)
    line))

;;; ─── ANSI helpers ─────────────────────────────────────────────────────────
;;; squint silently drops \u001b from string literals; build ESC via charCode.

(def ^:private ESC   (js/String.fromCharCode 27))
(def ^:private RESET (str ESC "[0m"))
(def ^:private BOLD  (str ESC "[1m"))
(def ^:private DIM   (str ESC "[2m"))

(defn- wrap+split
  "Wrap plain-text ANSI string to width, return string[]."
  [s width]
  (if (or (nil? s) (= s ""))
    []
    (vec (.split (ansi/wrap-ansi s width {:hard false :trim false :word-wrap true})
                 "\n"))))

(defn- split-lines
  "Split a pre-wrapped ANSI string on newlines. Drops trailing empty element."
  [s]
  (if (or (nil? s) (= s ""))
    []
    (let [parts (.split s "\n")]
      (if (.endsWith s "\n") (vec (.slice parts 0 -1)) (vec parts)))))

;;; ─── Tool formatting helpers (inlined from tool_status.cljs) ─────────────

(defn- first-line
  "First line of a tool result, blank-safe. A failed tool's first line is the
   error text — that is what the transcript shows instead of a line count."
  [s]
  (let [t (str (or s ""))]
    (or (first (.split t "\n")) "")))

(defn- truncate-to [s max-len]
  (if (> (count s) max-len) (str (.slice s 0 max-len) "…") s))

(defn- truncate-line-to-width
  "Hard-truncate a line (which may contain ANSI escape codes) so its
   *visible* width fits in `cols` columns. Falls back to no-op when
   the line already fits.

   pi-tui's renderer crashes when any rendered line exceeds the
   terminal width — typically caused by long MCP tool calls whose
   path arguments overflow the per-arg budget computed inside
   format-one-line-args. This is the safety net that ensures we
   never hand pi-tui an over-wide row, regardless of upstream
   miscalculation."
  [s cols]
  (if (or (nil? s) (<= (ansi/string-width s) cols))
    s
    ;; Use Bun's wrapAnsi + take first line. wrap-ansi preserves ANSI
    ;; codes correctly across the truncation boundary.
    (let [wrapped (ansi/wrap-ansi s cols {:hard true :trim false :word-wrap false})
          first-line (first (.split wrapped "\n"))]
      ;; Append an ellipsis when there was content beyond cols. We
      ;; conservatively use plain "…" rather than reapplying color —
      ;; the trailing styling is already lost.
      (str first-line "…"))))

(defn- format-one-line-args
  "Compact one-line summary of the tool's input args (path, pattern, etc).
   `width-budget` is the column allowance for this string (defaults to
   term-width - 40 to leave room for icon, name, result, and duration)."
  ([tool-name args] (format-one-line-args tool-name args nil))
  ([tool-name args width-budget]
   (let [max-w (or width-budget
                   (max 20 (- (or (.-columns js/process.stdout) 80) 40)))]
     (truncate-to
      (case tool-name
        "bash"       (or (first (.split (or (get args :command) "") "\n")) "")
        "read"       (let [p (or (get args :path) "")]
                       (if-let [r (get args :range)] (str p ":" (first r) "-" (second r)) p))
        "write"      (or (get args :path) "")
        "edit"       (or (get args :path) "")
        "ls"         (or (get args :path) ".")
        "glob"       (let [pat (or (get args :pattern) "") p (get args :path)]
                       (if (seq p) (str pat " in " p) pat))
        "grep"       (let [pat (or (get args :pattern) "") p (get args :path)]
                       (if (seq p) (str "\"" pat "\" in " p) (str "\"" pat "\"")))
        "web_fetch"  (or (get args :url) "")
        "web_search" (str "\"" (or (get args :query) "") "\"")
        "think"      (or (first (.split (or (get args :thought) "") "\n")) "")
        (let [pairs (map (fn [[k v]] (str k "=" v)) args)] (.join (clj->js pairs) " ")))
      max-w))))

(defn- format-one-line-result-for-tool [tool-name result _args]
  (let [lines-of (fn [s] (count (filterv seq (.split (or s "") "\n"))))]
    (case tool-name
      "read"  (let [n (lines-of result)] (if (= n 1) "1 line" (str n " lines")))
      "grep"  (let [n (lines-of result)] (if (= n 1) "1 match" (str n " matches")))
      "glob"  (let [n (lines-of result)] (if (= n 1) "1 file" (str n " files")))
      "ls"    (let [n (lines-of result)] (if (= n 1) "1 item" (str n " items")))
      "edit"  "applied"
      "write" "written"
      "bash"  (let [n (lines-of result)] (str n " lines"))
      (when (seq (str result))
        (let [lines (.split (str result) "\n") n (count lines) max-w (max 20 (- (or (.-columns js/process.stdout) 80) 30))]
          (if (= n 1) (truncate-to (first lines) max-w) (str n " lines")))))))

;;; ─── Expanded tool body ───────────────────────────────────────────────────

(def error-preview-lines
  "A failed call auto-expands this many lines of its result, collapsed or not:
   the one-line summary shows the first line of the error and the cause is
   usually a few lines further down."
  10)

(defn- plain-rows [prefix s]
  (mapv (fn [l] [prefix l]) (split-lines (str (or s "")))))

(defn- tool-body-rows
  "`[style-prefix text]` rows for a finished tool's expanded view.
   `edit` shows a line diff of old_string → new_string (the tool's own result
   is only \"Edit applied\"), `write` the content it wrote, everything else its
   result. A failure shows the result whatever the tool: the error is the
   information, not the diff that never applied."
  [tname msg is-error {:keys [ec gc mc]}]
  (let [dim-mc (str mc DIM)]
    (cond
      is-error         (plain-rows dim-mc (:result msg))
      (= tname "edit") (mapv (fn [[op s]]
                               (case op
                                 :- [ec (str "-" s)]
                                 :+ [gc (str "+" s)]
                                 [dim-mc (str " " s)]))
                             (diff-lines (get-in msg [:args :old_string])
                                         (get-in msg [:args :new_string])))
      (= tname "write") (plain-rows dim-mc (get-in msg [:args :content]))
      :else            (plain-rows dim-mc (:result msg)))))

(defn- expanded-lines
  "Indented, styled, width-clamped body lines plus a `… N more lines` tail
   when `limit` cut it."
  [rows limit w mc]
  (let [shown  (vec (take limit rows))
        hidden (- (count rows) (count shown))
        maxw   (max 10 (dec w))]
    (cond-> (mapv (fn [[prefix s]]
                    (truncate-line-to-width
                     (str "  " prefix (ansi/expand-tabs s) RESET) maxw))
                  shown)
      (pos? hidden)
      (conj (truncate-line-to-width
             (str "  " mc DIM "… " hidden " more lines" RESET) maxw)))))

;;; ─── Message renderer ─────────────────────────────────────────────────────

(defn- render-message*
  "Per-role rendering. Callers want `render-message`, which additionally
   enforces the width guarantee.

   Options:
     :msg      — the message map
     :width    — available terminal width in columns
     :theme    — theme map (same format as agent.ui.themes/default-dark)
     :md-cache — atom holding incremental-render cache for assistant messages
                 (mutated on each call; pass nil to skip caching)"
  [{:keys [msg width theme md-cache]}]
  (let [role    (:role msg)
        ;; Tabs are expanded HERE, before any wrapping or markdown rendering.
        ;; Every layer below measures a tab differently (0 / 1 / 3 / 4-8
        ;; columns), so a line containing one has no true width — that is what
        ;; crashed the TUI on tab-indented Go under a permission overlay.
        ;; Expanding later, once a line is wrapped or composited, only widens
        ;; it further.
        content (ansi/expand-tabs (or (:content msg) ""))
        w       (or width 80)
        pc      (fg (get-in theme [:colors :primary]   "#7aa2f7"))
        sc      (fg (get-in theme [:colors :secondary] "#9ece6a"))
        ec      (fg (get-in theme [:colors :error]     "#f7768e"))
        mc      (fg (get-in theme [:colors :muted]     "#565f89"))
        wc      (fg (get-in theme [:colors :warning]   "#e0af68"))
        gc      (fg (get-in theme [:colors :success]   "#9ece6a"))
        ;; Theme tokens, not literals: /theme applies live and a hardcoded
        ;; cyan would be the one colour that never followed it.
        cy      (fg (get-in theme [:colors :info]      "#7dcfff"))
        plc     (fg (get-in theme [:colors :plan]      "#7dcfff"))]
    (case role
      "user"
      (wrap+split (str pc BOLD (icon theme :user) " " RESET content) w)

      "assistant"
      (let [{:keys [text reasoning]} (split-think-blocks content)
            prev     (when md-cache @md-cache)
            result   (mb/incremental-render (or text "") prev {})
            _        (when md-cache (reset! md-cache result))
            rendered (:rendered result)
            ;; Wrap at (w-2) to leave room for the 2-col left gutter:
            ;;   first line:   "● <content>"
            ;;   continuation: "  <content>"
            ;; The continuation indent matches the bullet+space width so
            ;; multi-line responses align flush with the body of line 1
            ;; (Claude Code's convention).
            safe     (when (seq rendered)
                       (ansi/wrap-ansi rendered (- w 2) {:hard true :trim false :word-wrap true}))
            ;; Reasoning block: dim, italic-ish via DIM, prefixed with "│ "
            ;; (matches the standalone "thinking" role render).
            reasoning-lines
            (when (seq reasoning)
              ;; Wrap raw text first (no styling), then apply muted color
              ;; per line. Wrapping a pre-styled string strips the DIM/RESET
              ;; from continuation lines, which is why the original
              ;; rendering only dimmed the first line.
              (let [budget  (max 10 (- w 4))
                    wrapped (ansi/wrap-ansi (str reasoning) budget
                                            {:hard true :trim false :word-wrap true})]
                (mapv #(truncate-line-to-width
                        (str "  " mc "│ " % RESET)
                        (max 10 (dec w)))
                      (.split wrapped "\n"))))]
        (let [lines (if (seq safe)
                      (vec (.split safe "\n"))
                      [(str mc DIM "…" RESET)])
              body  (into [(str sc (icon theme :assistant) " " RESET (first lines))]
                          (map #(str "  " %) (rest lines)))]
          (if (seq reasoning-lines)
            (into reasoning-lines body)
            body)))

      ("tool-start" "tool-end")
      (let [tname    (:tool-name msg)
            args     (:args msg)
            dur      (:duration msg)
            is-end   (= role "tool-end")
            ;; A tool's own :display metadata wins over the generic formatter.
            ;; middleware computes these (formatArgs/formatResult/icon) and
            ;; app_reducers copies them onto the message — and nothing read
            ;; them here, so every extension tool rendered through the generic
            ;; `k=v` branch below. That is what printed
            ;; `questions=[object Object]` for a questionnaire call.
            ;;
            ;; A FAILED tool outranks both. middleware emits :isError on
            ;; tool_execution_end and app_reducers copies it here; before that
            ;; every failure rendered as a green-path "✓", which is the one
            ;; thing a transcript must never say about a call that threw.
            is-error (and is-end (boolean (:is-error msg)))
            icon     (cond
                       is-error           (icon theme :error)
                       (:custom-icon msg) (:custom-icon msg)
                       is-end             (icon theme :tool-done)
                       :else              (icon theme :tool))
            arg-str  (or (:custom-one-line-args msg)
                         (format-one-line-args tname args))
            ;; In-flight status line, written by tool_execution_update. Only
            ;; meaningful while the tool is still running — the end event
            ;; replaces the message wholesale, so it cannot go stale.
            status   (when-not is-end (:custom-status-text msg))
            res-str  (when is-end
                       (if is-error
                         ;; The per-tool formatter would say "2 lines" — for a
                         ;; failure the first line IS the information.
                         (truncate-to (first-line (:result msg))
                                      (max 20 (- w 30)))
                         (or (:custom-one-line-result msg)
                             (format-one-line-result-for-tool tname (:result msg) args))))
            base-c   (if is-error ec mc)
            line     (str base-c icon " " (or tname "?")
                          (when (seq arg-str) (str " " arg-str))
                          (when (seq status)
                            (str " " DIM "— " status RESET base-c))
                          ;; Result + duration appear in dim with a · separator so the
                          ;; eye lands on the args first, summary second.
                          (when (and is-end (seq res-str))
                            (str " " DIM "· " res-str RESET))
                          (when (and is-end dur)
                            (str " " DIM (.toFixed (/ dur 1000) 1) "s" RESET))
                          RESET)
            ;; Body under the one-liner. `:expanded` is stamped by app_reducers
            ;; from the tool-display setting and flipped by ctrl+o; a failure
            ;; shows a short preview even when collapsed.
            limit    (cond
                       (not is-end)    0
                       (:expanded msg) (let [n (:max-lines msg)] (if (number? n) n 40))
                       is-error        error-preview-lines
                       :else           0)
            body     (when (pos? limit)
                       (expanded-lines
                        (tool-body-rows tname msg is-error {:ec ec :gc gc :mc mc})
                        limit w mc))]
        ;; Final width-safety guard: pi-tui crashes if any line exceeds
        ;; the terminal width. Long MCP arg lists (e.g. multi_read
        ;; with several full paths) can blow past format-one-line-args'
        ;; per-arg budget — truncate to (w - 1) here as a last resort.
        (into [(truncate-line-to-width line (max 10 (dec w)))] body))

      "shell"
      (if (seq content)
        (wrap+split (str mc DIM content RESET) w)
        [])

      "error"
      (wrap+split (str ec (icon theme :error) " " RESET content) w)

      "thinking"
      (wrap+split (str mc DIM "│ " RESET content) w)

      "plan"
      (wrap+split (str plc (icon theme :widget) " " RESET content) w)

      ;; One line per ACP tool call, rewritten in place as its status changes.
      ;; Dim like "shell": this is activity, not output, and a turn can carry
      ;; dozens of them.
      "tool"
      (wrap+split (str mc DIM content RESET) w)

      ;; The four notify levels. `ui.notify(msg, type)` used to throw its type
      ;; away and file everything as "info", so a warning and a failure read
      ;; exactly like a status line.
      "info"
      (wrap+split (str cy (icon theme :info) " " RESET mc content RESET) w)

      "warn"
      (wrap+split (str wc (icon theme :warn) " " RESET mc content RESET) w)

      "success"
      (wrap+split (str gc (icon theme :success) " " RESET mc content RESET) w)

      "widget"
      (split-lines (or content ""))

      ;; fallback
      (wrap+split (str mc role ": " RESET content) w))))

(defn render-message
  "Render a single message map to string[], every line guaranteed to fit
   `:width`. See `render-message*` for the per-role rendering.

   The guarantee is enforced here rather than in each branch because every
   branch got it wrong in a different way, and pi-tui turns an over-wide line
   into a dead session. Measured before this existed: `assistant` overflowed
   from width 3, `user` from 5, `thinking` from 6, and the tool branches from
   **10** — the last two because they floor their wrap budget at `(max 10 …)`,
   a floor above the width they were handed. A run of wide graphemes at width 2
   came out 240 columns over, since `wrap-ansi` cannot break inside a cluster
   and emits it whole.

   At any sane width this is a no-op — the branches already fit — so it costs
   nothing and removes a whole class of crash. Below ~6 columns it truncates,
   which is the right trade against losing the session."
  [opts]
  (let [w (or (:width opts) 80)]
    (mapv (fn [l] (clamp-line l w)) (render-message* opts))))
