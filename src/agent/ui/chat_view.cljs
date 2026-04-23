(ns agent.ui.chat-view
  {:squint/extension "jsx"}
  (:require ["react" :refer [useMemo]]
            ["ink" :refer [Box Text Static]]
            ["./tool_status.jsx" :refer [ToolGroupStatus group_messages]]
            ["./tool_execution.jsx" :refer [ToolExecution]]
            ["./streaming_markdown.mjs" :refer [useStreamingMarkdown]]
            [agent.ui.think-tag-parser :refer [split-think-blocks]]
            [agent.token-estimation :refer [estimate-tokens]]
            [clojure.string :as str]))

(defn role-prefix [role]
  (case role "user" "❯ " "assistant" "● " "thinking" "💭 " "plan" "📋 " "tool-call" "⚙ " "error" "✗ " "  "))

(defn role-color [theme role]
  (case role
    "user"      (get-in theme [:colors :primary])
    "assistant" (get-in theme [:colors :secondary])
    "thinking"  "gray"
    "plan"      "cyan"
    "tool-call" (get-in theme [:colors :muted])
    "error"     (get-in theme [:colors :error])
    nil))

(def ^:private reasoning-visible-lines
  "Max number of reasoning lines shown when the block is expanded."
  10)

(defn- format-token-count
  "Format a token count as '~N.Nk tokens' for thousands, '~N tokens' otherwise."
  [n]
  (cond
    (>= n 1000) (str "~" (.toFixed (/ n 1000) 1) "k tokens")
    (> n 0)     (str "~" n " tokens")
    :else       nil))

(defn- ReasoningBlock
  "Dedicated renderer for inline <think>…</think> reasoning text.
   - `expanded` true while the model is still thinking (no answer text yet):
     shows a bold header with token count + last N lines with a │ left gutter.
   - `expanded` false once the final answer starts streaming: collapses to a
     compact `✻ Thought (~N tokens) ›` pill so the answer stays visible.

   Matches the visual conventions of the existing thinking-renderer extension:
   `│ ` gutter per line, 10-line cap, overflow indicator, token count in header."
  [{:keys [reasoning expanded muted]}]
  (let [tokens     (estimate-tokens reasoning)
        tok-label  (format-token-count tokens)
        lines      (str/split reasoning #"\n")
        line-count (count lines)
        overflow?  (> line-count reasoning-visible-lines)
        visible    (if overflow?
                     (vec (drop (- line-count reasoning-visible-lines) lines))
                     (vec lines))]
    (if expanded
      ;; No ticking Spinner here: in scrollback-mode the ReasoningBlock
      ;; renders inside a dynamic region that can be ≥ terminal height
      ;; once reasoning + editor + status + footer are stacked. Ink
      ;; appends a trailing `\n` to every render, and when the bottom
      ;; row is at the viewport bottom, that newline scrolls the top
      ;; of the dynamic region into permanent scrollback. At 80 ms per
      ;; spinner tick that compounds into one leaked line every frame
      ;; (the visible bug was 12 stacked `✻ Thinking` lines per second).
      ;; Visual liveness is delegated to the status-line activity
      ;; segment, which lives in a fixed 1-row region that can't
      ;; overflow. The token-count update in the header below already
      ;; tells the user the model is still working.
      #jsx [Box {:flexDirection "column" :marginBottom 1}
            [Box {:flexDirection "row"}
             [Text {:color muted :bold true}
              (str "✻ Thinking" (when tok-label (str " (" tok-label ")")) "…")]]
            (map-indexed
             (fn [i line]
               #jsx [Text {:key i :color "gray" :italic true :wrap "word"}
                     (str "│ " line)])
             visible)
            (when overflow?
              #jsx [Text {:color muted}
                    (str "│ … " (- line-count reasoning-visible-lines) " earlier lines")])]
      #jsx [Box {:flexDirection "row" :marginBottom 1}
            [Text {:color muted}
             (str "✻ Thought"
                  (when tok-label (str " (" tok-label ")"))
                  " ›")]])))

(defn AssistantMessage
  "Separate component for assistant messages — uses streaming markdown hook.
   Inline <think>…</think> tags (MiniMax, DeepSeek-R1, Qwen-QwQ, GLM-4.6, Kimi)
   are split out via `split-think-blocks` and rendered as a `ReasoningBlock`
   above the clean answer text. Raw tags still survive in storage so
   interleaved-thinking round-trips work for subsequent turns."
  [{:keys [content theme block-renderers is-live]}]
  (let [{:keys [reasoning text]} (split-think-blocks (or content ""))
        has-reasoning (seq reasoning)
        has-text      (seq text)
        ;; Expand the reasoning panel while the model is still thinking
        ;; (no answer text yet) and collapse it once the answer begins
        ;; to stream. Also requires is-live so completed/historic messages
        ;; never show the spinner — stream end always collapses to the pill.
        expanded?     (and is-live has-reasoning (not has-text))
        rendered      (useStreamingMarkdown text block-renderers)
        color         (role-color theme "assistant")
        muted         (get-in theme [:colors :muted] "#565f89")]
    #jsx [Box {:flexDirection "column" :marginBottom 1}
          (when has-reasoning
            #jsx [ReasoningBlock {:reasoning reasoning
                                  :expanded  expanded?
                                  :muted     muted}])
          ;; Suppress the `●` row entirely when there's no text AND
          ;; reasoning IS present — the reasoning pill/block is the
          ;; only meaningful signal, and an empty `●` bubble looks
          ;; broken. Pre-first-chunk (no reasoning, no text) still
          ;; shows `● …` so the user sees SOMETHING while waiting.
          (when (or has-text (not has-reasoning))
            #jsx [Box {:flexDirection "row"}
                  [Text {:color color} (role-prefix "assistant")]
                  [Box {:flexDirection "column" :flexShrink 1}
                   (if has-text
                     #jsx [Text {:wrap "word"} rendered]
                     #jsx [Text {:color muted} "…"])]])]))

(defn EvalMessage
  "Rendered result of an editor eval-mode expression (`$expr` /
   `$$expr`). Sibling to BashMessage; uses a `λ` header glyph and
   the theme's primary (blue) accent to distinguish at a glance.
   Falls back to an install hint when bb is not on PATH."
  [{:keys [message theme]}]
  (let [eval-color (get-in theme [:colors :primary] "#7aa2f7")
        err-color  (get-in theme [:colors :error] "#f7768e")
        muted      (get-in theme [:colors :muted] "#565f89")
        expr       (:expr message)
        stdout     (:stdout message)
        stderr     (:stderr message)
        exit-code  (:exit-code message)
        unavail?   (:unavailable? message)
        install    (:install-hint message)
        hidden?    (:local-only message)]
    #jsx [Box {:flexDirection "column" :marginBottom 1}
          [Box {:flexDirection "row"}
           [Text {:color eval-color :bold true} "λ "]
           [Text {:color eval-color :bold true} (str expr)]
           (when hidden?
             #jsx [Text {:color muted :dimColor true} "  (hidden from LLM)"])]
          (cond
            unavail?
            #jsx [Box {:flexDirection "column" :marginLeft 2}
                  [Text {:color err-color :bold true}
                   "Babashka (`bb`) is not installed or not on PATH."]
                  [Text {:color muted} "Install options:"]
                  [Text {:color muted}
                   "  brew install borkdude/brew/babashka"]
                  [Text {:color muted}
                   "  bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)"]]

            :else
            #jsx [Box {:flexDirection "column" :marginLeft 2}
                  (when (and stdout (> (count stdout) 0))
                    #jsx [Text {:wrap "word"} stdout])
                  (when (and stderr (> (count stderr) 0))
                    #jsx [Box {:flexDirection "column"}
                          [Text {:color muted :dimColor true} "stderr:"]
                          [Text {:color err-color :wrap "word"} stderr]])
                  (when (and exit-code (not (zero? exit-code)))
                    #jsx [Text {:color err-color :bold true}
                          (str "exit " exit-code)])])]))

(defn BashMessage
  "Rendered result of an editor bash-mode command (`!cmd` / `!!cmd`).
   Shows:
     - a bold command header in the editor-bash warning color
     - stdout in the default text color
     - stderr under a dim 'stderr:' label in the error color
     - an exit-code footer when non-zero
     - a 'hidden from LLM' hint when the message is :local-only
     - a 'BLOCKED' block when bash_suite vetoed the command"
  [{:keys [message theme]}]
  (let [bash-color  (get-in theme [:colors :warning] "#e0af68")
        err-color   (get-in theme [:colors :error] "#f7768e")
        muted       (get-in theme [:colors :muted] "#565f89")
        command     (:command message)
        stdout      (:stdout message)
        stderr      (:stderr message)
        exit-code   (:exit-code message)
        blocked?    (:blocked? message)
        reason      (:reason message)
        hidden?     (:local-only message)]
    #jsx [Box {:flexDirection "column" :marginBottom 1}
          [Box {:flexDirection "row"}
           [Text {:color bash-color :bold true} "❯ "]
           [Text {:color bash-color :bold true} (str command)]
           (when hidden?
             #jsx [Text {:color muted :dimColor true} "  (hidden from LLM)"])]
          (cond
            blocked?
            #jsx [Box {:flexDirection "column" :marginLeft 2}
                  [Text {:color err-color}
                   (str "BLOCKED: " (or reason "command blocked"))]]

            :else
            #jsx [Box {:flexDirection "column" :marginLeft 2}
                  (when (and stdout (> (count stdout) 0))
                    #jsx [Text {:wrap "word"} stdout])
                  (when (and stderr (> (count stderr) 0))
                    #jsx [Box {:flexDirection "column"}
                          [Text {:color muted :dimColor true} "stderr:"]
                          [Text {:color err-color :wrap "word"} stderr]])
                  (when (and exit-code (not (zero? exit-code)))
                    #jsx [Text {:color err-color :bold true}
                          (str "exit " exit-code)])])]))

(defn- MessageBubbleByRole
  "Role-based rendering (original MessageBubble logic). Extracted so
   that MessageBubble can branch on :kind first before dispatching
   on :role."
  [{:keys [message theme block-renderers is-live]}]
  (let [role (:role message)]
    (case role
      "tool-group"
      #jsx [ToolGroupStatus {:tool-name (:tool-name message)
                             :items     (:items message)
                             :theme     theme}]

      "tool-start"
      #jsx [ToolExecution {:tool-name            (:tool-name message)
                           :args                 (:args message)
                           :verbosity            (:verbosity message)
                           :theme                theme
                           :is-partial           true
                           :custom-one-line-args (:custom-one-line-args message)
                           :custom-status-text   (:custom-status-text message)
                           :custom-icon          (:custom-icon message)}]

      "tool-end"
      #jsx [ToolExecution {:tool-name              (:tool-name message)
                           :args                   (:args message)
                           :duration               (:duration message)
                           :result                 (:result message)
                           :verbosity              (:verbosity message)
                           :max-lines              (:max-lines message)
                           :theme                  theme
                           :is-partial             false
                           :custom-one-line-result (:custom-one-line-result message)
                           :custom-icon            (:custom-icon message)}]

      ;; Default: text-based messages (user, assistant, error, etc.)
      (let [content (:content message)
            color   (role-color theme role)]
        (case role
          "assistant"
          #jsx [AssistantMessage {:content content :theme theme
                                  :block-renderers block-renderers
                                  :is-live is-live}]

          "thinking"
          ;; Thinking messages: dimmed italic style
          #jsx [Box {:flexDirection "column" :marginBottom 0}
                [Text {:color "gray" :dimColor true}
                 (str (role-prefix role) content)]]

          "plan"
          ;; Plan messages: structured checklist style
          #jsx [Box {:flexDirection "column" :marginBottom 1}
                [Text {:color "cyan" :bold true} (role-prefix role)]
                [Box {:flexDirection "column" :marginLeft 2}
                 [Text {:color "cyan" :wrap "word"} content]]]

          ;; Other roles: plain text rendering
          #jsx [Box {:flexDirection "column" :marginBottom 1}
                [Text {:color color :bold (= role "user")}
                 (str (role-prefix role) content)]])))))

(defn MessageBubble [{:keys [message theme block-renderers is-live]}]
  (let [kind (:kind message)]
    (cond
      ;; Editor bash mode (!cmd / !!cmd) — dedicated renderer. Checked
      ;; before :role because the message is still tagged :role
      ;; "assistant" so build-context picks it up for LLM context on
      ;; the next turn (unless :local-only, which is the !! case).
      (= kind :bash)
      #jsx [BashMessage {:message message :theme theme}]

      ;; Editor eval mode ($expr / $$expr) — sibling renderer with a
      ;; λ header and primary-color accent. Same :local-only rules
      ;; apply.
      (= kind :eval)
      #jsx [EvalMessage {:message message :theme theme}]

      :else
      #jsx [MessageBubbleByRole {:message message :theme theme
                                 :block-renderers block-renderers
                                 :is-live is-live}])))

(defn last-user-index
  "Return the index of the last :role \"user\" message in msgs, or -1.
   The turn boundary — everything from this index onwards is the current
   turn and must stay in the live region so the user's question never
   scrolls off while the assistant streams."
  [msgs]
  (loop [i (dec (count msgs))]
    (cond
      (< i 0) -1
      (= "user" (:role (nth msgs i))) i
      :else (recur (dec i)))))

(defn compute-turn-split
  "Pure function — split messages into finalized (scrollback, completed
   turns) and live (the in-progress turn from the last user message onwards).

   - finalized: everything before the last user message. nil when there is
     no completed turn (fresh chat or single turn in progress).
   - live:      everything from the last user message to the end. Includes
     the user's question, streaming assistant content, tool calls, etc.
   - turn-idx:  the index of the last user message, or -1 if none.

   This split is the single source of truth for ChatView's Static / live
   region decision. Test it directly with pure-function tests — do NOT
   rely on `lastFrame()` assertions, since ink-testing-library runs Ink
   in debug mode which writes full static + dynamic output every frame,
   masking any bug where a message is in the wrong region."
  [messages]
  (let [msgs     (vec messages)
        turn-idx (last-user-index msgs)]
    {:turn-idx  turn-idx
     :finalized (when (pos? turn-idx) (subvec msgs 0 turn-idx))
     :live      (if (>= turn-idx 0) (subvec msgs turn-idx) msgs)}))

(defn ChatView
  "Renders the chat history region.

   Two code paths behind the `scrollback-mode` prop (plumbed down from
   app.cljs, which reads `:scrollback-mode` from settings):

   OFF (default): current behavior — finalized slice goes into `<Static>`
     and the live slice (from last user message onwards) renders in the
     dynamic region. See compute-turn-split + fin-grouped memo below.

   ON: scrollback mode — the `<Static>` component is NOT rendered. All
     remaining `messages` are assumed to be in-flight (app.cljs commits
     stable items to scrollback via `commit-to-scrollback!` and removes
     them from state). The dynamic region shows only the in-flight tail.

   PR-2 will remove the OFF path entirely once the ON path is validated."
  [{:keys [messages theme block-renderers streaming scrollback-mode]}]
  (let [all-msgs       (vec messages)
        {:keys [turn-idx finalized live]} (compute-turn-split all-msgs)
        ;; Group only the stable finalized slice (previous completed turns).
        ;; Memo key: turn-idx (new turn boundary), plus first-message id
        ;; (session switch detection — a different message list with the
        ;; same turn-idx would otherwise return a stale cache). The live
        ;; slice never participates in the memo — it renders fresh every
        ;; frame so streaming content updates appear immediately.
        ;; group_messages returns a CLJS vector; to-array converts for Static.
        first-id       (:id (first all-msgs))
        fin-grouped    (useMemo
                        (fn []
                          (if (and (not scrollback-mode) finalized)
                            (to-array (group_messages finalized))
                            #js []))
                        #js [turn-idx first-id scrollback-mode])
        live-is-live   (boolean streaming)
        ;; In scrollback mode, in-flight = every non-:committed message.
        ;; The commit sweep (app.cljs) promotes each sub-message to real
        ;; terminal scrollback as soon as it is no longer the actively-
        ;; streaming tail (committable-now rule), so this filter normally
        ;; resolves to at most one message: the currently-streaming tail.
        ;; Keeping filter-uncommitted (instead of `live`) means earlier
        ;; sub-messages of the current turn — user prompt, finished
        ;; assistant bubble, completed tool-end — stay visible until the
        ;; commit sweep fires, rather than vanishing and reappearing in
        ;; scrollback (which looked like content loss).
        in-flight      (if scrollback-mode
                         (filterv (fn [m] (not (:committed m))) all-msgs)
                         live)
        in-flight-count (count in-flight)]
    #jsx [Box {:flexDirection "column"}
          (when (and (not scrollback-mode) (pos? (.-length fin-grouped)))
            ;; :key pinned to first-message-id forces Ink to unmount and
            ;; remount <Static> when the session changes. Ink's Static
            ;; tracks emitted items via an internal watermark (useState)
            ;; and assumes items is append-only; without a key, switching
            ;; to a different session's finalized list — even one that
            ;; differs by content — silently drops the new items because
            ;; items.length <= prior watermark.
            #jsx [Static {:key (str "static-" (or first-id "empty"))
                          :items fin-grouped}
                  (fn [msg i]
                    #jsx [MessageBubble {:key             (or (:id msg) i)
                                         :message         msg
                                         :theme           theme
                                         :block-renderers block-renderers
                                         :is-live         false}])])
          (map-indexed
           (fn [i msg]
             (let [tail? (= i (dec in-flight-count))]
               #jsx [MessageBubble {:key             (or (:id msg) (str "live-" i))
                                    :message         msg
                                    :theme           theme
                                    :block-renderers block-renderers
                                    :is-live         (and tail? live-is-live)}]))
           in-flight)]))
