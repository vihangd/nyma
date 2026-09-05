(ns agent.extensions.agent-shell.features.input-router
  "Intercepts user input and forwards to the active ACP agent.
   Streams response chunks to the UI in real-time."
  (:require [agent.extensions.agent-shell.shared :as shared]
            [agent.extensions.agent-shell.acp.client :as client]
            [clojure.string :as str]))

(def ^:private prompt-id
  "Unique ID for the current prompt. Each new prompt increments this
   so append-chunk knows when to start a new assistant message."
  (atom 0))

(defn append-chunk
  "Append a text chunk to the current prompt's assistant message.
   Uses prompt-id to distinguish messages from different prompts."
  [prev text-delta current-prompt-id]
  (let [all      (vec prev)
        last-msg (last all)]
    (if (and (= (:role last-msg) "assistant")
             (= (:prompt-id last-msg) current-prompt-id))
      ;; Same prompt — append to existing message
      (conj (vec (butlast all))
            (update last-msg :content str text-delta))
      ;; New prompt or no assistant msg — create new message
      (conj all {:role "assistant"
                 :content (or text-delta "")
                 :prompt-id current-prompt-id}))))

(defn append-thought
  "Append a thinking chunk to the current prompt's thinking message.
   Uses prompt-id to distinguish messages from different prompts."
  [prev thought-text current-prompt-id]
  (let [all      (vec prev)
        last-msg (last all)]
    (if (and (= (:role last-msg) "thinking")
             (= (:prompt-id last-msg) current-prompt-id))
      (conj (vec (butlast all))
            (update last-msg :content str thought-text))
      (conj all {:role "thinking"
                 :content (or thought-text "")
                 :prompt-id current-prompt-id}))))

(defn append-plan
  "Replace or create a plan message for the current prompt."
  [prev plan-data current-prompt-id]
  (let [all       (vec prev)
        formatted (str/join "\n"
                            (mapv (fn [e]
                                    (str (case (:status e)
                                           "done"   "✓"
                                           "active" "→"
                                           " ")
                                         " " (:content e)))
                                  plan-data))
        plan-msg  {:role "plan" :content formatted :prompt-id current-prompt-id}]
    ;; Replace existing plan message or append new one
    (let [idx (some (fn [[i m]] (when (and (= (:role m) "plan")
                                           (= (:prompt-id m) current-prompt-id))
                                  i))
                    (map-indexed vector all))]
      (if idx
        (assoc all idx plan-msg)
        (conj all plan-msg)))))

(def tool-glyphs
  "ACP ToolCallStatus -> glyph. `pending` covers both \"input still streaming\"
   and \"awaiting approval\", which look the same from here."
  {"pending" "\u22ef" "in_progress" "\u22ef" "completed" "\u2713" "failed" "\u2717"})

(def tool-labels
  "ACP ToolKind -> a short verb. The spec's nine kinds; anything else falls
   back to the raw kind so a new one is visible rather than swallowed."
  {"read" "Read" "edit" "Edit" "delete" "Delete" "move" "Move" "search" "Search"
   "execute" "Bash" "think" "Think" "fetch" "Fetch" "other" "Tool"})

(defn tool-line
  "One activity line for a tool call: `\u2692 Read  src/auth/store.ts  \u22ef`.

   Prefers the reported location over the title — `locations` is what the spec
   provides for follow-along, and a path says more in one line than prose."
  [{:keys [title kind status path]}]
  (let [label  (get tool-labels (str kind) (or (not-empty (str kind)) "Tool"))
        detail (or (not-empty (str (or path ""))) (not-empty (str (or title ""))) "")
        glyph  (get tool-glyphs (str status) "\u22ef")]
    (str "\u2692 " label (when (seq detail) (str "  " detail)) "  " glyph)))

(defn upsert-tool
  "Add or update the message for one tool call, keyed by its id.

   ACP sends `tool_call` once and then any number of `tool_call_update`s in
   which every field but `toolCallId` is optional. So this MERGES: a
   status-only update must not blank the title it already showed."
  [prev {:keys [id] :as call} current-prompt-id]
  (let [all (vec prev)
        idx (some (fn [[i m]] (when (and (= (:tool-id m) (str id))
                                         (= (:prompt-id m) current-prompt-id))
                                i))
                  (map-indexed vector all))
        prior (when idx (get all idx))
        merged (merge (select-keys (or prior {}) [:title :kind :status :path])
                      (into {} (remove (fn [[_ v]] (nil? v))
                                       (select-keys call [:title :kind :status :path]))))
        msg   (assoc merged
                     :role "tool" :tool-id (str id) :prompt-id current-prompt-id
                     :content (tool-line merged))]
    (if idx (assoc all idx msg) (conj all msg))))

(defn inline-thinking?
  "Should thinking be streamed into the transcript?

   `agent-shell.inline-thinking`: \"auto\" (default) inlines only when nothing
   else renders it; \"always\" and \"never\" override. A normal settings dial
   rather than a hidden coupling to whichever extension happens to be
   installed."
  [api]
  (case (str (or (get (shared/load-config) "inline-thinking") "auto"))
    "always" true
    "never"  false
    ;; `auto`: inline unless something else is already painting it. Guarded
    ;; because getGlobalFlag is absent on programmatic/gateway APIs, and an
    ;; unconditional call there threw inside `subscribe` — taking the whole
    ;; turn down rather than losing one nicety.
    (not (when (.-getGlobalFlag api)
           (.getGlobalFlag api "thinking-renderer__active")))))

(defn- clear-callbacks! []
  (reset! shared/stream-callback nil)
  (reset! shared/thought-callback nil)
  (reset! shared/plan-callback nil)
  (reset! shared/tool-callback nil))

(defn- make-stream-handler
  "Create a streaming handler object.
   The user's prompt is prefixed inline on the first chunk."
  [conn agent-key text api user-text]
  (let [first?    (atom true)
        pid       (swap! prompt-id inc)]
    #js
     {:handle    true
      :streaming true
      :subscribe
      (fn [set-messages]
        ;; Wire text streaming callback
        (reset! shared/stream-callback
                (fn [text-delta]
                  ;; No "❯ <text>" prefix any more: the caller echoes the
                  ;; prompt at submit time instead. Prefixing the first CHUNK
                  ;; meant the prompt appeared only once the agent answered —
                  ;; and Claude Code answers last, after all its tool work.
                  (compare-and-set! first? true false)
                  (set-messages (fn [prev] (append-chunk prev text-delta pid)))))
        ;; Wire thinking inline unless something else already renders it.
        ;; `auto` reproduces the original behaviour — thinking_renderer paints a
        ;; widget, so inlining too would double-render — but the check was
        ;; unconditional and undocumented, and with that extension installed
        ;; (its `active` flag defaults TRUE) thinking never appeared inline and
        ;; there was no way to ask for it.
        (when (inline-thinking? api)
          (reset! shared/thought-callback
                  (fn [thought-text]
                    (set-messages (fn [prev] (append-thought prev thought-text pid))))))
        ;; Wire tool activity — the reads and edits that fill the silence
        ;; before any text arrives.
        (reset! shared/tool-callback
                (fn [call]
                  (set-messages (fn [prev] (upsert-tool prev call pid)))))
        ;; Wire plan callback
        (reset! shared/plan-callback
                (fn [plan-data]
                  (set-messages (fn [prev] (append-plan prev plan-data pid)))))
        ;; RETURN this chain — the caller (interactive/route-to-agent!) clears
        ;; its submit lock in a .finally on it. Returning nil left the editor
        ;; wedged for the rest of the session.
        (-> (client/send-prompt conn text)
            (.then
             (fn [result]
               (clear-callbacks!)
               (when @first?
                 (set-messages
                  (fn [prev]
                    (append-chunk prev "(no response)" pid))))
               (when-let [usage (:usage result)]
                 (shared/update-agent-state! agent-key :turn-usage usage))))
            (.catch
             (fn [e]
               (clear-callbacks!)
               (when (and (.-ui api) (.-available (.-ui api)))
                 (.notify (.-ui api)
                          (str "ACP error: " (.-message e)) "error"))))))}))

(defn activate
  "Hook the `input` event at high priority. When an agent is active:
   - Plain text → forwarded to ACP agent as a prompt (streamed)
   - //command  → forwarded to ACP agent as '/command' (streamed)
   - /command   → passed through to nyma's command handler

   `input` is an INTERCEPTION hook, not a notification. The payload key is
   `:input` (not `:text` — that is the separate fire-and-forget
   `input_submit`), and returning a truthy `:handle` takes the turn over:

     {:handle true :streaming true :subscribe (fn [set-messages] -> Promise)}

   Returning nil declines, and the input falls through to nyma's own loop —
   which is how `/command` still reaches the local command handler.

   `subscribe` receives a `set-messages` of the same shape as the interactive
   pane's updater — `(fn [(fn [prev-msgs]) -> msgs])` — and MUST return the
   promise for the turn so the caller can unlock the editor when it settles."
  [api]
  (let [handler
        (fn [data _ctx]
          (let [text      (.-input data)
                agent-key @shared/active-agent]
            (when (and agent-key (seq text))
              (let [conn (shared/find-conn-by-agent agent-key)]
                (cond
                  (.startsWith text "//")
                  (when conn
                    (make-stream-handler conn agent-key (.slice text 1) api text))

                  (.startsWith text "/")
                  nil

                  :else
                  (when conn
                    (make-stream-handler conn agent-key text api text)))))))]
    (.on api "input" handler 100)
    (fn [] (.off api "input" handler))))
