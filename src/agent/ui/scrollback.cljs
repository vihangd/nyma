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

   `render-message-to-string` uses ink's headless `renderToString`, which
   reuses the SAME MessageBubble component that ChatView renders live,
   so committed vs. in-flight output is visually identical."
  {:squint/extension "jsx"}
  (:require ["ink" :refer [renderToString]]
            ["./chat_view.jsx" :refer [MessageBubble]]))

(defn render-message-to-string
  "Render a single message to an ANSI-styled string via ink's headless
   renderer. `columns` is passed through to match the target stdout's
   width so ANSI wrapping is correct.

   Returns the rendered string (possibly empty if the message produced
   no visible output — e.g. a :local-only bash message in some paths)."
  [message theme block-renderers columns]
  (renderToString
   #jsx [MessageBubble {:message         message
                        :theme           theme
                        :block-renderers block-renderers
                        :is-live         false}]
   #js {:columns (or columns 80)}))

(defn commit-to-scrollback!
  "Render a single message and write it to terminal scrollback via Ink's
   writeToStdout. Fire-and-forget.

   `write` is Ink's writeToStdout function, obtained from the App context
   (see `useStdout()` — its `write` field). It clears the dynamic region,
   writes the data to scrollback, then restores the dynamic region below.

   This function does NOT modify any React state. The caller is
   responsible for removing the message from any in-flight state
   container after committing."
  [{:keys [write message theme block-renderers columns]}]
  (when write
    (let [rendered (render-message-to-string message theme block-renderers columns)]
      (when (and rendered (pos? (count rendered)))
        (write (str rendered "\n"))))))
