(ns agent.ui.chat-renderer
  "Pure: message map → string[] for pi-tui rendering."
  (:require [agent.utils.ansi :as ansi]
            [agent.utils.markdown-blocks :as mb]
            [agent.ui.think-tag-parser :refer [split-think-blocks]]))

;;; ─── ANSI helpers ─────────────────────────────────────────────────────────
;;; squint silently drops \u001b from string literals; build ESC via charCode.

(def ^:private ESC   (js/String.fromCharCode 27))
(def ^:private RESET (str ESC "[0m"))
(def ^:private BOLD  (str ESC "[1m"))
(def ^:private DIM   (str ESC "[2m"))

(defn- fg [hex]
  (let [r (js/parseInt (.slice hex 1 3) 16)
        g (js/parseInt (.slice hex 3 5) 16)
        b (js/parseInt (.slice hex 5 7) 16)]
    (str ESC "[38;2;" r ";" g ";" b "m")))

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

(defn- truncate-to [s max-len]
  (if (> (count s) max-len) (str (.slice s 0 max-len) "…") s))

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

;;; ─── Message renderer ─────────────────────────────────────────────────────

(defn render-message
  "Render a single message map to string[] (one element per terminal line).

   Options:
     :msg      — the message map
     :width    — available terminal width in columns
     :theme    — theme map (same format as agent.ui.themes/default-dark)
     :md-cache — atom holding incremental-render cache for assistant messages
                 (mutated on each call; pass nil to skip caching)"
  [{:keys [msg width theme md-cache]}]
  (let [role    (:role msg)
        content (or (:content msg) "")
        w       (or width 80)
        pc      (fg (get-in theme [:colors :primary]   "#7aa2f7"))
        sc      (fg (get-in theme [:colors :secondary] "#9ece6a"))
        ec      (fg (get-in theme [:colors :error]     "#f7768e"))
        mc      (fg (get-in theme [:colors :muted]     "#565f89"))]
    (case role
      "user"
      (wrap+split (str pc BOLD "❯ " RESET content) w)

      "assistant"
      (let [{:keys [text reasoning]} (split-think-blocks content)
            prev     (when md-cache @md-cache)
            result   (mb/incremental-render (or text "") prev {})
            _        (when md-cache (reset! md-cache result))
            rendered (:rendered result)
            ;; Wrap at (w-2) to accommodate the "● " prefix on line 1.
            ;; Uses hard:true so lines with no word-break (e.g. long filenames
            ;; in bash output) are still clipped rather than crashing pi-tui.
            safe     (when (seq rendered)
                       (ansi/wrap-ansi rendered (- w 2) {:hard true :trim false :word-wrap true}))]
        (let [lines (if (seq safe)
                      (vec (.split safe "\n"))
                      [(str mc DIM "…" RESET)])]
          (into [(str sc "● " RESET (first lines))]
                (rest lines))))

      ("tool-start" "tool-end")
      (let [tname    (:tool-name msg)
            args     (:args msg)
            dur      (:duration msg)
            is-end   (= role "tool-end")
            icon     (if is-end "✓" "⚙")
            arg-str  (format-one-line-args tname args)
            res-str  (when is-end
                       (format-one-line-result-for-tool tname (:result msg) args))]
        [(str mc icon " " (or tname "?")
              (when (seq arg-str) (str " " arg-str))
              ;; Result + duration appear in dim with a · separator so the
              ;; eye lands on the args first, summary second.
              (when (and is-end (seq res-str))
                (str " " DIM "· " res-str RESET))
              (when (and is-end dur)
                (str " " DIM (.toFixed (/ dur 1000) 1) "s" RESET))
              RESET)])

      "shell"
      (if (seq content)
        (wrap+split (str mc DIM content RESET) w)
        [])

      "error"
      (wrap+split (str ec "✗ " RESET content) w)

      "thinking"
      (wrap+split (str mc DIM "│ " RESET content) w)

      "plan"
      (wrap+split (str (fg "#7dcfff") "📋 " RESET content) w)

      "info"
      (wrap+split (str (fg "#7dcfff") "ℹ " RESET mc content RESET) w)

      "widget"
      (split-lines (or content ""))

      ;; fallback
      (wrap+split (str mc role ": " RESET content) w))))
