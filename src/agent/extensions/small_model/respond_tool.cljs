(ns agent.extensions.small-model.respond-tool
  "Synthetic respond tool — forces small models into structured output mode.

   Small models (~8B) cannot reliably choose between producing bare text and
   calling a tool. Injecting a synthetic `respond` tool forces every response
   through a structured path: the model MUST either call a real tool or call
   `respond(message='...')` to reply to the user.

   Based on Forge (antoinezambelli/forge) — the technique moves an 8B model
   from single-digit to 84% on structured tool-calling benchmarks.

   Nyma-native implementation of Forge's proxy behaviour:
     1. activation — REGISTER `respond` like any other tool
     2. middleware :leave — when `respond` fires, save message, set flag
     3. before_provider_request (next turn) — if flag set, block with the
        saved message as the assistant response (loop.cljs stores it as an
        assistant message and calls agent_end — clean termination, no extra
        model round-trip)

   Step 1 used to `aset` the tool into `st-config.tools` from
   before_provider_request. Tools are wrapped ONCE at the top of each loop
   iteration, before that hook runs, so the injected object never passed
   through wrap-tools-with-middleware: step 2's :leave never fired, `pending`
   was never set, and step 3 was unreachable. It also skipped normalize-tool!,
   leaving `:parameters` unconverted, and stayed out of `getAllTools` — which
   the quality-monitor's hallucination check consults, so had it ever been
   wrapped it would have been flagged as a hallucinated tool and its result
   overwritten. Registering it is what makes all of that correct at once.

   The `respond` tool is stripped from the message before_store so the
   tool call doesn't appear as a visible tool invocation in the conversation.
   From the user's perspective the exchange looks like a normal text response.
  "
  (:require [clojure.string :as str]))

;; ── Tool definition ──────────────────────────────────────────────

;; Registered under the bare name; extension_scope prefixes it with the
;; namespace, the same convention the evidence tools follow (small-model__*).
;; The old hand-written constant used an underscore and matched nothing.
(def respond-tool-bare-name "respond")
(def respond-tool-name "small-model__respond")

(def ^:private respond-tool-def
  #js {:description
       (str "Respond to the user with a message. Use this when the user is chatting, "
            "asking a question, when you need to ask a clarifying question before "
            "proceeding, or when no other tool action is needed. "
            "Also use this after completing the user's request to report the result.")
       :parameters
       #js {:type       "object"
            :required   #js ["message"]
            :properties #js {:message #js {:type        "string"
                                           :description "The message to send to the user."}}}
       :execute
       (fn [args]
         ;; Returns the message — result visible only in the tool-call UI;
         ;; the block in before_provider_request on the NEXT turn converts
         ;; it to a clean assistant message.
         (str (.-message args)))})

;; ── Activation ───────────────────────────────────────────────────

(defn activate
  "Inject synthetic respond tool and wire the block-on-respond hooks.
   Returns a cleanup fn."
  [api _config]
  (let [pending    (atom nil)  ; saved respond message waiting to be promoted

        ;; ── before_provider_request: promote a captured respond ──
        on-before-request
        (fn [_data _ctx]
          ;; If a respond call was captured last turn, block the next LLM
          ;; call and emit the message as the final assistant response.
          (when-let [msg @pending]
            (reset! pending nil)
            #js {:block true :reason msg}))

        ;; ── middleware :leave: detect respond call ────────────────
        respond-interceptor
        #js {:name  "small-model/respond-tool"
             :leave (fn [ctx]
                      (when (= (str (aget ctx "tool-name")) respond-tool-name)
                        ;; Capture the message and flag for next turn's block.
                        (reset! pending (str (.-result ctx)))
                        ;; Set an empty result so the tool-call doesn't show
                        ;; as meaningful content in the tool-result channel.
                        (aset ctx "result" ""))
                      ctx)}

        ;; ── message_before_store: hide the respond tool call ─────
        ;; The AI SDK stores tool-call messages; we strip the respond entry
        ;; so it doesn't appear in the persistent message list.
        on-before-store
        (fn [data _ctx]
          (let [content (.-content data)]
            (when (and (array? content)
                       (some (fn [part]
                               (and (= "tool-call" (.-type part))
                                    (= respond-tool-name (.-toolName part))))
                             (js/Array.from content)))
              ;; Filter out respond tool-call parts
              (let [filtered (.filter content
                                      (fn [part]
                                        (not (and (= "tool-call" (.-type part))
                                                  (= respond-tool-name (.-toolName part))))))]
                (when (pos? (.-length filtered))
                  #js {:content filtered})))))]

    ;; Registered, so the loop wraps it with the middleware pipeline and
    ;; normalize-tool! migrates :parameters to a callable :inputSchema.
    (.registerTool api respond-tool-bare-name respond-tool-def)

    (.on api "before_provider_request" on-before-request)

    (.addMiddleware api respond-interceptor)

    (.on api "message_before_store" on-before-store)

    ;; Cleanup. Previously removed the middleware only, leaving both event
    ;; handlers subscribed and the tool registered after unload.
    (fn []
      (.removeMiddleware api "small-model/respond-tool")
      (when (.-unregisterTool api)
        (try (.unregisterTool api respond-tool-bare-name) (catch :default _e nil)))
      (when (.-off api)
        (.off api "before_provider_request" on-before-request)
        (.off api "message_before_store" on-before-store)))))
