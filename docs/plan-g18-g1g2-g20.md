# Implementation Plan: G18, G1/G2, G20

Three remaining hook changes to implement. G16 (checkpoints) and G17 (subagents) are deferred to the roadmap.

---

## Step 1: G18 — Model Context in Tool Execution (5 LOC)

### Goal
Pass `modelId` string into the extension context during tool execution so tools know which model is driving them.

### Changes

**File: `src/agent/middleware.cljs:29-43`** — In `execute-tool-fn`, after existing `aset` calls for `toolCallId`/`abortSignal`/`onUpdate`:

Add after line 42 (after the `onUpdate` aset):
```clojure
(aset ext-ctx "modelId"
  (let [agent (when-let [a (:agent ctx)] a)]
    (str (or (when agent (.-modelId (:model (:config agent)))) "unknown"))))
```

**File: `src/agent/middleware.cljs:264-271`** — In `create-pipeline`'s `:execute` function, add `:agent` to the context map:

Add `:agent agent` to the context map (after `:abort-controller`):
```clojure
ctx {:tool-name        tool-name
     :tool             tool
     :args             args
     :cancelled        false
     :result           nil
     :extension-context ext-ctx
     :events           events
     :agent            agent
     :abort-controller (when agent (:abort-controller agent))}
```

### Tests

Add to `test/hooks_events.test.cljs`:
```clojure
(describe "hooks:model-context-in-tools" (fn []
  (it "extension context includes modelId during tool execution"
    (fn []
      ;; Verify the agent object carries config.model
      (let [agent (create-agent {:model "test-model-id" :system-prompt "test"})]
        (-> (expect (.-model (:config agent))) (.toBe "test-model-id")))))))
```

---

## Step 2: G1 + G2 — Retry Mechanism + `stream_filter` Event (60 LOC)

### Goal
Wrap the streaming section in a retry loop. Add `stream_filter` emit-collect on each `message_update` delta. Extensions return `{abort: true, inject: [...]}` to abort the stream and retry with injected messages.

### Why this is the most complex remaining change
The streaming loop at `loop.cljs:198-205` is the innermost hot path — every LLM response token flows through it. The change must:
1. Accumulate text across deltas
2. Call emit-collect on each delta (only for `message_update`, not tool calls)
3. Handle abort by breaking out of the inner loop
4. Inject messages into state
5. Re-enter the outer streaming call (retry)
6. Limit retries to prevent infinite loops

### Changes

**File: `src/agent/core.cljs`** — Add `:retry-state` atom:

After `follow-queue (atom [])` (line 27):
```clojure
retry-state       (atom nil) ;; nil = no retry, {:reason :inject} = retry with injected messages
```

Include in agent map (after `:follow-queue`):
```clojure
:retry-state      retry-state
```

**File: `src/agent/loop.cljs:180-205`** — Replace the streaming section:

Replace from `;; Normal LLM call` through the stream event loop (lines 180-205):

```clojure
          ;; Normal LLM call with stream filter and retry support
          (do
            ((:emit events) "turn_start" {})

            (loop [attempt 0]
              (reset! (:retry-state agent) nil)

              ;; === AI SDK — with provider_error fallback ===
              (let [result (try
                             (js-await (streamText st-config))
                             (catch :default e
                               (let [err-result (js-await
                                                  ((:emit-collect events) "provider_error"
                                                    #js {:error   e
                                                         :message (.-message e)
                                                         :model   model-id
                                                         :config  st-config}))]
                                 (if (get err-result "retry")
                                   (js-await (streamText st-config))
                                   (throw e)))))
                    accumulated (atom "")
                    aborted     (atom false)]

                ;; Stream events with filter check on text deltas
                (let [iter (.call (aget (.-fullStream result) js/Symbol.asyncIterator)
                                  (.-fullStream result))]
                  (loop []
                    (let [chunk (js-await (.next iter))]
                      (when (and (not (.-done chunk)) (not @aborted))
                        (let [chunk-val (.-value chunk)
                              evt-type  (event-type chunk-val)]
                          ;; Stream filter — only on text deltas
                          (when (= evt-type "message_update")
                            (swap! accumulated str (or (.-textDelta chunk-val) ""))
                            (let [filter-result (js-await
                                                  ((:emit-collect events) "stream_filter"
                                                    #js {:delta @accumulated
                                                         :chunk (.-textDelta chunk-val)
                                                         :type  evt-type}))]
                              (when (get filter-result "abort")
                                (reset! aborted true)
                                (reset! (:retry-state agent)
                                  {:reason (get filter-result "reason")
                                   :inject (or (get filter-result "inject") [])}))))
                          (when-not @aborted
                            ((:emit events) evt-type chunk-val)))
                        (when-not @aborted (recur))))))

                ;; Check if stream was aborted for retry
                (if-let [retry @(:retry-state agent)]
                  (do
                    (reset! (:retry-state agent) nil)
                    ;; Inject messages (e.g., TTSR rule as system message)
                    (doseq [msg (:inject retry)]
                      (if-let [store (:store agent)]
                        ((:dispatch! store) :message-added {:message msg})
                        (swap! state update :messages conj msg)))
                    ;; Retry up to 2 times
                    (if (< attempt 2)
                      (recur (inc attempt))
                      ;; Max retries exceeded — emit warning and continue
                      ((:emit events) "agent_end" {:text "" :usage nil})))

                  ;; Normal completion — no abort
                  (do
```

Then the existing `final-text`/`usage`/`store` section continues as-is, and the closing parens need adjustment. The full structure becomes:

```
(loop [attempt 0]
  ...streaming with filter...
  (if retry
    (recur)
    (do
      ;; final-text, usage tracking, message storage
      ...existing code...
      ;; follow-up queue
      (when-let [next ...]
        ...))))
```

### Event shape

**`stream_filter`** (emit-collect):
```javascript
// Handler receives:
{
  delta: "accumulated text so far (full buffer)",
  chunk: "just this chunk's text",
  type: "message_update"
}

// Handler can return:
{
  abort: true,                    // stop streaming
  reason: "matched rule X",       // logged
  inject: [                       // messages injected before retry
    {role: "system", content: "Rule: never use eval()"}
  ]
}
```

### TTSR example usage

```clojure
;; Extension: Time Traveling Streamed Rules
(let [rules [{:pattern #"eval\(" :message "Never use eval(). Use safer alternatives."}
             {:pattern #"rm -rf" :message "Never suggest rm -rf."}]]
  (.on api "stream_filter"
    (fn [data]
      (let [text (.-delta data)]
        (when-let [matched (first (filter #(re-find (:pattern %) text) rules))]
          #js {:abort  true
               :reason (str "TTSR: " (:message matched))
               :inject #js [#js {:role "system" :content (:message matched)}]})))))
```

### Tests: `test/stream_filter.test.cljs` (~8 tests)

```clojure
(describe "stream-filter:event-shape" (fn []
  (it "stream_filter receives delta and chunk"
    ;; Verify event data shape via emit-collect
    )
  (it "handler returning {abort: true} sets retry state"
    ;; Create agent, set retry-state, verify it's set
    )
  (it "handler returning nil has no effect"
    )
  (it "abort includes inject messages in retry state"
    )))

(describe "stream-filter:retry" (fn []
  (it "retry-state atom starts as nil"
    )
  (it "retry-state can be set and cleared"
    )
  (it "max 2 retries enforced"
    ;; Test retry counter logic
    )
  (it "injected messages appear in agent state"
    ;; Simulate inject, verify messages added to state
    )))
```

---

## Step 3: G20 — `before_message_send` Event (15 LOC)

### Goal
Final emit-collect before the LLM call, after `context_assembly`. Extensions can transform messages or system prompt one last time.

### Changes

**File: `src/agent/loop.cljs`** — Between `context_assembly` result (line 151) and `st-config` construction (line 153):

Insert after the messages/effective-prompt from context_assembly:
```clojure
              ;; before_message_send — final chance to transform before LLM call
              send-result (js-await
                            ((:emit-collect events) "before_message_send"
                              #js {:messages (clj->js messages)
                                   :system   effective-prompt
                                   :model    model-id}))
              messages (if-let [msgs (get send-result "messages")]
                         (vec (map (fn [m]
                                     (if (map? m) m
                                       {:role (.-role m) :content (.-content m)}))
                                   msgs))
                         messages)
              effective-prompt (or (get send-result "system") effective-prompt)
```

### Event shape

**`before_message_send`** (emit-collect):
```javascript
// Handler receives:
{
  messages: [{role: "user", content: "..."}, ...],  // final message list
  system: "system prompt text",                     // final system prompt
  model: "claude-sonnet-4-20250514"                 // resolved model ID
}

// Handler can return:
{
  messages: [...],   // replace message list
  system: "..."      // replace system prompt
}
```

### Tests: `test/before_message_send.test.cljs` (~4 tests)

```clojure
(describe "before-message-send" (fn []
  (it "handler returning {messages: [...]} replaces message list"
    )
  (it "handler returning {system: '...'} replaces system prompt"
    )
  (it "handler returning nil has no effect"
    )
  (it "fires after context_assembly"
    ;; Verify ordering by checking that context_assembly changes are visible
    )))
```

---

## Execution Order

| Step | Gap | LOC | Depends On |
|------|-----|-----|-----------|
| 1 | G18 | 5 | None |
| 2 | G1/G2 | 60 | None (but benefits from G18 for model-aware filters) |
| 3 | G20 | 15 | None |

All three are independent. Steps 1 and 3 are surgical. Step 2 is the only one requiring careful attention to the streaming loop structure.

---

## Post-Implementation: Complete Event Surface

After these 3 steps, nyma's event surface is **complete** — all 20 gaps closed:

| Event | Type | Status |
|-------|------|--------|
| `session_ready` | emit-async | Done |
| `session_end_summary` | emit | Done |
| `input_submit` | emit-collect | Done (upgraded) |
| `model_resolve` | emit-collect | Done |
| `before_agent_start` | emit-collect | Existing |
| `context_assembly` | emit-collect | Existing |
| `before_message_send` | emit-collect | **G20 (this plan)** |
| `before_provider_request` | emit-collect | Existing |
| `provider_error` | emit-collect | Done |
| `stream_filter` | emit-collect | **G1/G2 (this plan)** |
| `message_before_store` | emit-collect | Done |
| `tool_access_check` | emit-collect | Done |
| `before_tool_call` | emit-collect | Done (upgraded) |
| `permission_request` | emit-collect | Done |
| `tool_complete` | emit-collect | Done |
| `tool_result` | emit-collect | Existing |
| `after_provider_request` | emit | Existing |
| `agent_start/end` | emit | Existing |
| `turn_start/end` | emit | Existing |
| `message_start/update/end` | emit | Existing |
| `editor_change` | emit | Existing |
