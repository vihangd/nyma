(ns agent.extensions.small-model.respond-tool
  "Synthetic respond tool — forces small models into structured output mode.

   Small models (~8B) cannot reliably choose between producing bare text and
   calling a tool. Injecting a synthetic `respond` tool forces every response
   through a structured path: the model MUST either call a real tool or call
   `respond(message='...')` to reply to the user.

   Based on Forge (antoinezambelli/forge) — the technique moves an 8B model
   from single-digit to 84% on structured tool-calling benchmarks.

   Nyma-native implementation of Forge's proxy behaviour:
     1. before_provider_request — inject `respond` into the tools map
     2. middleware :leave — when `respond` fires, save message, set flag
     3. before_provider_request (next turn) — if flag set, block with the
        saved message as the assistant response (loop.cljs stores it as an
        assistant message and calls agent_end — clean termination, no extra
        model round-trip)

   The `respond` tool is stripped from the message before_store so the
   tool call doesn't appear as a visible tool invocation in the conversation.
   From the user's perspective the exchange looks like a normal text response.
  "
  (:require [clojure.string :as str]))

;; ── Tool definition ──────────────────────────────────────────────

(def ^:private respond-tool-name "small_model__respond")

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
  (let [handlers   (atom [])
        pending    (atom nil)  ; saved respond message waiting to be promoted

        ;; ── before_provider_request: inject + check pending ──────
        on-before-request
        (fn [data _ctx]
          ;; If a respond call was captured last turn, block the next LLM
          ;; call and emit the message as the final assistant response.
          (if-let [msg @pending]
            (do (reset! pending nil)
                #js {:block true :reason msg})
            ;; Otherwise inject the respond tool into the tools map.
            ;; st-config.tools is a plain JS object {name → tool-def}.
            (do (let [tools (.-tools data)]
                  (when tools
                    (aset tools respond-tool-name respond-tool-def)))
                nil)))

        ;; ── middleware :leave: detect respond call ────────────────
        respond-interceptor
        #js {:name  "small-model/respond-tool"
             :leave (fn [ctx]
                      (when (= (str (.-tool-name ctx)) respond-tool-name)
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

    (.on api "before_provider_request" on-before-request)
    (swap! handlers conj ["before_provider_request" on-before-request])

    (.addMiddleware api respond-interceptor)

    (.on api "message_before_store" on-before-store)
    (swap! handlers conj ["message_before_store" on-before-store])

    ;; Cleanup
    (fn []
      (doseq [[event handler] @handlers]
        (.off api event handler))
      (.removeMiddleware api "small-model/respond-tool"))))
