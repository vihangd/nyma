(ns agent.extensions.custom-provider-claude-native.messages
  "Convert LanguageModelV3Prompt → Anthropic Messages API request body."
  (:require [clojure.string :as str]))

(def ^:private default-max-tokens 8192)

;; ── Content-part conversion ──────────────────────────────────

(defn- text-part->anthropic [part]
  #js {:type "text" :text (.-text part)})

(defn- tool-call-part->anthropic [part]
  ;; V3 ToolCallPart.input is `unknown` — could be a JSON string or an already-parsed object.
  (let [raw   (.-input part)
        input (cond
                (string? raw) (try (js/JSON.parse raw) (catch :default _ #js {}))
                (nil? raw)    #js {}
                :else         raw)]  ; already an object — pass through
    #js {:type  "tool_use"
         :id    (.-toolCallId part)
         :name  (.-toolName part)
         :input input}))

(defn- tool-result-output->anthropic [output]
  ;; Returns either a string or JS array suitable for Anthropic content field
  (let [t (.-type output)]
    (cond
      (= t "text")         (.-value output)
      (= t "json")         (js/JSON.stringify (.-value output))
      (= t "error-text")   (.-value output)
      (= t "error-json")   (js/JSON.stringify (.-value output))
      (= t "execution-denied") (or (.-reason output) "Tool execution denied.")
      (= t "content")
      (let [arr #js []]
        (.forEach (.-value output)
                  (fn [p]
                    (when (= (.-type p) "text")
                      (.push arr #js {:type "text" :text (.-text p)}))))
        arr)
      :else "")))

(defn- content-part->anthropic [part]
  (case (.-type part)
    "text"      (text-part->anthropic part)
    "tool-call" (tool-call-part->anthropic part)
    nil))

(defn- content-parts->js-array
  "Convert a JS array of V3 content parts to Anthropic JS array,
   skipping unrecognised parts."
  [parts]
  (let [result #js []]
    (.forEach parts (fn [p]
                      (when-let [ap (content-part->anthropic p)]
                        (.push result ap))))
    result))

;; ── Per-role message conversion ───────────────────────────────

(defn- user-msg->anthropic [msg]
  (let [parts (content-parts->js-array (.-content msg))]
    (when (pos? (.-length parts))
      #js {:role "user" :content parts})))

(defn- assistant-msg->anthropic [msg]
  (let [parts (content-parts->js-array (.-content msg))]
    (when (pos? (.-length parts))
      #js {:role "assistant" :content parts})))

(defn- tool-msg->anthropic [msg]
  ;; V3 "tool" role → Anthropic "user" with tool_result blocks
  (let [result #js []]
    (.forEach (.-content msg)
              (fn [part]
                (when (= (.-type part) "tool-result")
                  (let [output  (.-output part)
                        content (tool-result-output->anthropic output)
                        obj     #js {:type        "tool_result"
                                     :tool_use_id (.-toolCallId part)
                                     :content     content}]
                    (when (or (= (.-type output) "error-text")
                              (= (.-type output) "error-json"))
                      (aset obj "is_error" true))
                    (.push result obj)))))
    (when (pos? (.-length result))
      #js {:role "user" :content result})))

;; ── Prompt extraction ────────────────────────────────────────

(defn- extract-system [prompt]
  (let [sys-parts #js []]
    (.forEach prompt (fn [msg]
                       (when (= (.-role msg) "system")
                         (let [c (.-content msg)]
                           (when (string? c)
                             (.push sys-parts c))))))
    (when (pos? (.-length sys-parts))
      (.join sys-parts "\n\n"))))

(defn- prompt->anthropic-messages [prompt]
  (let [result #js []]
    (.forEach prompt (fn [msg]
                       (let [am (case (.-role msg)
                                  "system"    nil
                                  "user"      (user-msg->anthropic msg)
                                  "assistant" (assistant-msg->anthropic msg)
                                  "tool"      (tool-msg->anthropic msg)
                                  nil)]
                         (when am (.push result am)))))
    result))

;; ── Public: build full request body ─────────────────────────

(defn build-request-body
  "Build the Anthropic Messages API JSON body from a model ID and V3 call
   options. Returns a ClojureScript map; caller should clj->js + JSON.stringify."
  [model-id opts]
  (let [prompt     (.-prompt opts)
        system-str (extract-system prompt)
        messages   (prompt->anthropic-messages prompt)
        max-tokens (or (.-maxOutputTokens opts) default-max-tokens)]
    (cond-> {"model"      model-id
             "max_tokens" max-tokens
             "stream"     true
             "messages"   messages}
      system-str                       (assoc "system" system-str)
      (some? (.-temperature opts))     (assoc "temperature" (.-temperature opts))
      (some? (.-topP opts))            (assoc "top_p" (.-topP opts))
      (.-stopSequences opts)           (assoc "stop_sequences" (.-stopSequences opts)))))
