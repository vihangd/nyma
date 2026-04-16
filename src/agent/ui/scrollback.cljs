(ns agent.ui.scrollback
  "Commit finalized chat messages directly to terminal scrollback via
   Ink's writeToStdout API.

   Design notes — see /Users/vihangd/.claude/plans/enchanted-greeting-honey.md

   The dominant coding-agent pattern (Claude Code, Codex, Aider, pi-mono)
   is: past turns live in real terminal scrollback; only a small in-flight
   region is repainted per frame by the UI framework. Nyma adopts this via
   ink's supported `writeToStdout` (exposed as `{write}` from useStdout):

     1. log.clear() — erase the dynamic region
     2. stdout.write(rendered) — commit the message at that position
     3. restoreLastOutput() — redraw the dynamic region below

   Rendering strategy: build ANSI strings directly — do NOT use ink's
   `renderToString`. Headless renderToString creates a React root, runs
   the tree, tears down, frees yoga — but React's scheduler may fire
   pending passive effects AFTER the teardown, triggering
   `resetAfterCommit → onComputeLayout → yoga.calculateLayout` on a
   freed yoga node (yoga-wasm `call_indirect` out-of-bounds crash).
   Components in the tree that use useRef/useMemo/useEffect (e.g.,
   `useStreamingMarkdown`, ink's internal layout effects) trigger this.

   Matching MessageBubble's output byte-for-byte is not a goal: once
   content is in terminal scrollback, it's the terminal that renders
   it, not ink. We just need a readable, colored representation of
   each message type."
  (:require ["node:util" :as nodeutil]
            [agent.utils.markdown-blocks :as mb]
            [agent.utils.ansi :as ansi]
            [agent.token-estimation :refer [estimate-tokens]]
            [agent.ui.think-tag-parser :refer [split-think-blocks]]
            [clojure.string :as str]))

;;; ─── ANSI primitives ────────────────────────────────────────────

(def ^:private ESC "\u001b[")
(def ^:private RESET (str ESC "0m"))
(def ^:private BOLD (str ESC "1m"))
(def ^:private DIM (str ESC "2m"))
(def ^:private ITALIC (str ESC "3m"))

(defn- hex->rgb
  "Parse a '#rrggbb' hex color to [r g b] ints. Returns nil for invalid input."
  [hex]
  (when (and (string? hex) (= 7 (count hex)) (.startsWith hex "#"))
    (let [r (js/parseInt (.slice hex 1 3) 16)
          g (js/parseInt (.slice hex 3 5) 16)
          b (js/parseInt (.slice hex 5 7) 16)]
      (when (and (not (js/isNaN r)) (not (js/isNaN g)) (not (js/isNaN b)))
        [r g b]))))

(defn- fg
  "Apply a hex foreground color to `s`. Returns `s` unchanged if color is nil."
  [s color]
  (if-let [[r g b] (hex->rgb color)]
    (str ESC "38;2;" r ";" g ";" b "m" s RESET)
    s))

(defn- bold [s] (str BOLD s RESET))
(defn- dim [s]  (str DIM s RESET))

;;; ─── Per-role renderers ─────────────────────────────────────────

(defn- render-user
  [message theme]
  (let [color   (get-in theme [:colors :primary] "#7aa2f7")
        content (str (:content message))]
    (str (fg (bold (str "❯ " content)) color))))

(defn- render-assistant-text
  [content block-renderers columns]
  ;; Use the same incremental-render the live path uses — no hook wrapper.
  (let [result   (mb/incremental-render content nil (or block-renderers {}))
        rendered (:rendered result)]
    (or rendered "")))

(defn- format-token-count [n]
  (cond
    (>= n 1000) (str "~" (.toFixed (/ n 1000) 1) "k tokens")
    (> n 0)     (str "~" n " tokens")
    :else       nil))

(defn- render-reasoning-pill
  "Render a compact one-line `✻ Thought (~N tokens) ›` pill for the
   committed-scrollback view. Matches the collapsed live-view pill
   (see chat_view.cljs ReasoningBlock) so reasoning is visible in
   history without the full chain-of-thought cluttering scrollback."
  [reasoning theme]
  (let [muted     (get-in theme [:colors :muted] "#565f89")
        tok-label (format-token-count (estimate-tokens reasoning))]
    (fg (str "✻ Thought"
             (when tok-label (str " (" tok-label ")"))
             " ›")
        muted)))

(defn- render-assistant
  "Render an assistant message for scrollback commit.

   Inline <think>…</think> blocks (MiniMax, DeepSeek-R1, Qwen-QwQ,
   GLM-4.6, Kimi) are split out via split-think-blocks — exactly like
   the live AssistantMessage view. The reasoning becomes a compact
   collapsed pill; the clean text becomes the message body. This
   matches what the user saw while the turn was streaming, so
   committed scrollback and live-region appearance are consistent.

   Returns nil for messages with NO visible text (reasoning-only
   pre-tool-call assistants). These already appeared as a live pill;
   committing an empty `●` bubble plus an orphan pill to scrollback
   is noise. Committing nothing also keeps scrollback cleaner for
   reasoning-heavy models that emit many intermediate think-only
   messages."
  [message theme block-renderers columns]
  (let [color         (get-in theme [:colors :secondary] "#9ece6a")
        raw           (or (:content message) "")
        {:keys [reasoning text]} (split-think-blocks raw)
        has-reasoning (seq reasoning)
        has-text      (seq text)]
    (cond
      ;; No text and no reasoning → nothing to commit.
      (and (not has-text) (not has-reasoning))
      nil

      ;; Reasoning only (no final text) → skip. The live view already
      ;; showed the pill; committing it again just clutters history.
      (and (not has-text) has-reasoning)
      nil

      :else
      (let [rendered       (render-assistant-text text block-renderers columns)
            lines          (str/split rendered #"\n")
            first-ln       (or (first lines) "")
            rest-lns       (rest lines)
            prefixed-first (fg (str "● " first-ln) color)
            prefixed-rest  (str/join "\n" (map #(str "  " %) rest-lns))
            body           (if (seq rest-lns)
                             (str prefixed-first "\n" prefixed-rest)
                             prefixed-first)]
        (if has-reasoning
          ;; Prepend a one-line reasoning pill above the body.
          (str "  " (render-reasoning-pill reasoning theme) "\n" body)
          body)))))

(defn- render-bash
  "Editor bash mode (`!cmd` / `!!cmd`) result."
  [message theme]
  (let [bash-color (get-in theme [:colors :warning] "#e0af68")
        err-color  (get-in theme [:colors :error] "#f7768e")
        muted      (get-in theme [:colors :muted] "#565f89")
        command    (:command message)
        stdout     (:stdout message)
        stderr     (:stderr message)
        exit-code  (:exit-code message)
        blocked?   (:blocked? message)
        reason     (:reason message)
        hidden?    (:local-only message)
        header     (str (fg (bold (str "❯ " command)) bash-color)
                        (when hidden? (str "  " (fg (dim "(hidden from LLM)") muted))))
        body       (cond
                     blocked?
                     (str "  " (fg (str "BLOCKED: " (or reason "command blocked")) err-color))

                     :else
                     (let [out-parts
                           [(when (and stdout (pos? (count stdout)))
                              (->> (str/split stdout #"\n")
                                   (map #(str "  " %))
                                   (str/join "\n")))
                            (when (and stderr (pos? (count stderr)))
                              (str "  " (fg (dim "stderr:") muted) "\n"
                                   (->> (str/split stderr #"\n")
                                        (map #(str "  " (fg % err-color)))
                                        (str/join "\n"))))
                            (when (and exit-code (not (zero? exit-code)))
                              (str "  " (fg (bold (str "exit " exit-code)) err-color)))]]
                       (str/join "\n" (remove nil? out-parts))))]
    (if (seq body)
      (str header "\n" body)
      header)))

(defn- render-eval
  "Editor eval mode (`$expr` / `$$expr`) result."
  [message theme]
  (let [eval-color (get-in theme [:colors :primary] "#7aa2f7")
        err-color  (get-in theme [:colors :error] "#f7768e")
        muted      (get-in theme [:colors :muted] "#565f89")
        expr       (:expr message)
        stdout     (:stdout message)
        stderr     (:stderr message)
        exit-code  (:exit-code message)
        unavail?   (:unavailable? message)
        hidden?    (:local-only message)
        header     (str (fg (bold (str "λ " expr)) eval-color)
                        (when hidden? (str "  " (fg (dim "(hidden from LLM)") muted))))
        body       (cond
                     unavail?
                     (str "  " (fg (bold "Babashka (`bb`) is not installed or not on PATH.") err-color))

                     :else
                     (let [out-parts
                           [(when (and stdout (pos? (count stdout)))
                              (->> (str/split stdout #"\n")
                                   (map #(str "  " %))
                                   (str/join "\n")))
                            (when (and stderr (pos? (count stderr)))
                              (str "  " (fg (dim "stderr:") muted) "\n"
                                   (->> (str/split stderr #"\n")
                                        (map #(str "  " (fg % err-color)))
                                        (str/join "\n"))))
                            (when (and exit-code (not (zero? exit-code)))
                              (str "  " (fg (bold (str "exit " exit-code)) err-color)))]]
                       (str/join "\n" (remove nil? out-parts))))]
    (if (seq body)
      (str header "\n" body)
      header)))

(defn- render-tool-end
  "Finalized tool call — render name + compact args + one-line result."
  [message theme]
  (let [muted     (get-in theme [:colors :muted] "#565f89")
        tool-name (or (:tool-name message) "?")
        args      (:args message)
        result    (:result message)
        duration  (:duration message)
        arg-str   (cond
                    (nil? args)    ""
                    (string? args) args
                    :else          (try (nodeutil/inspect (clj->js args)
                                                          #js {:depth 2 :colors false})
                                        (catch :default _ "")))
        first-line (-> (str (or result ""))
                       (.split "\n") first (or "")
                       (.slice 0 120))
        header    (fg (str "⚙ " (bold tool-name)
                           (when (seq arg-str) (str "(" (.slice arg-str 0 80) ")"))
                           (when duration (str " · " duration "ms")))
                      muted)
        body      (when (seq first-line)
                    (str "  " (fg first-line muted)))]
    (if body (str header "\n" body) header)))

(defn- render-thinking
  [message _theme]
  (fg (dim (str "💭 " (:content message))) "#777"))

(defn- render-plan
  [message _theme]
  (fg (str "📋 " (:content message)) "#6cc"))

(defn- render-error
  [message theme]
  (fg (str "✗ " (:content message))
      (get-in theme [:colors :error] "#f7768e")))

(defn- render-default
  "Fallback for any role we don't have a dedicated renderer for."
  [message _theme]
  (str (:role message) ": " (:content message)))

;;; ─── Commit-sweep helpers (pure) ────────────────────────────────

(defn last-user-index
  "Return the index of the last \"user\" message in `messages`, or -1.
   The turn boundary: messages BEFORE this index are past turns
   (eligible to commit); messages FROM this index onwards are the
   current turn (stay in the chat region until a new user submit)."
  [messages]
  (loop [i (dec (count messages))]
    (cond
      (< i 0) -1
      (= "user" (:role (nth messages i))) i
      :else (recur (dec i)))))

(defn committable-past-turn
  "Given the current messages vector, return the subvector of messages
   that form PAST TURNS (before the last user message) AND haven't been
   committed yet AND aren't in-flight tool-starts.

   Pure. Drives the commit-sweep effect in app.cljs and is tested
   directly so the turn-boundary rules don't regress in a
   flushPassiveEffects / renderToString path that integration tests
   can't observe."
  [messages]
  (let [last-user-idx (last-user-index messages)]
    (if (pos? last-user-idx)
      (let [past-turn (vec (take last-user-idx messages))]
        (filterv (fn [m]
                   (and (not= (:role m) "tool-start")
                        (not (:committed m))))
                 past-turn))
      [])))

;;; ─── Public API ─────────────────────────────────────────────────

(defn render-message-to-string
  "Render a single message to an ANSI-styled string. Pure — no React,
   no hooks, no yoga. Safe to call from any context (including the
   scrollback sweep effect in app.cljs).

   `columns` is accepted for future wrapping support; markdown's
   internal renderer handles its own line breaks.

   Returns the rendered string (possibly empty for degenerate inputs)."
  [message theme block-renderers columns]
  (let [kind (:kind message)
        role (:role message)]
    (cond
      (= kind :bash)      (render-bash message theme)
      (= kind :eval)      (render-eval message theme)
      (= role "user")     (render-user message theme)
      (= role "assistant") (render-assistant message theme block-renderers columns)
      (= role "tool-end") (render-tool-end message theme)
      (= role "thinking") (render-thinking message theme)
      (= role "plan")     (render-plan message theme)
      (= role "error")    (render-error message theme)
      :else               (render-default message theme))))

(defn commit-to-scrollback!
  "Render a single message and write it to terminal scrollback via Ink's
   writeToStdout. Fire-and-forget.

   `write` is Ink's writeToStdout function, obtained from the App context
   (see `useStdout()` — its `write` field). It clears the dynamic region,
   writes the data to scrollback, then restores the dynamic region below.

   This function does NOT modify any React state. The caller is
   responsible for marking the message :committed (or removing it) after
   committing."
  [{:keys [write message theme block-renderers columns]}]
  (when write
    (let [rendered (render-message-to-string message theme block-renderers columns)]
      (when (and rendered (pos? (count rendered)))
        (write (str rendered "\n"))))))

;;; ─── Startup banner (direct stdout, pre-Ink) ────────────────────

(defn print-header-banner!
  "Write a minimal startup banner to stdout BEFORE Ink's render mounts.
   Matches the Claude Code pattern: the banner is normal terminal output —
   it scrolls up naturally as chat content accumulates, no pinning, no
   Static component. Ink's live region (chat, status, editor) mounts
   below the banner and takes over from there.

   `model-id` is the configured model string (or nil — falls back to
   \"unknown\"). `theme` is the current theme map."
  [{:keys [model-id theme]}]
  (let [primary (get-in theme [:colors :primary] "#7aa2f7")
        muted   (get-in theme [:colors :muted] "#565f89")
        model   (or model-id "unknown")
        ;; Simple one-line banner — no borders, no box drawing.
        ;; Terminal scrollback shows it at the top of the session
        ;; until enough content pushes it out of the visible area.
        line    (str (fg (bold "● nyma") primary)
                     " " (fg (str "· " model) muted))]
    (.write js/process.stdout (str line "\n\n"))))
