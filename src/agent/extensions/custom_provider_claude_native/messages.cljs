(ns agent.extensions.custom-provider-claude-native.messages
  "Convert LanguageModelV3Prompt → Anthropic Messages API request body."
  (:require [agent.thinking :as thinking-util]
            [agent.utils.data :as data]
            [clojure.string :as str]))

(def ^:private default-max-tokens 8192)

;; ── Content-part conversion ──────────────────────────────────

(defn- text-part->anthropic [part]
  #js {:type "text" :text (.-text part)})

(defn- tool-call-part->anthropic [part]
  ;; V3 ToolCallPart.input is `unknown` — could be a JSON string or an already-parsed object.
  (let [raw   (.-input part)
        input (cond
                (string? raw) (data/parse-json raw #js {})
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
                    (let [t (.-type p)]
                      (cond
                        (= t "text") (.push arr #js {:type "text" :text (.-text p)})
                        ;; A tool's image (view_image, a screenshot) was
                        ;; dropped here; the model saw only the caption.
                        (contains? #{"image-data" "media" "file-data"} t)
                        (when-let [b (file-part->anthropic #js {:mediaType (.-mediaType p) :data (.-data p)
                                                                :filename (.-filename p)})]
                          (.push arr b))))))
        arr)
      :else "")))

(defn- base64-of [data]
  (if (string? data) data (.toString (js/Buffer.from data) "base64")))

(defn file-part->anthropic
  "An AI-SDK `file` part → an Anthropic block: an image, a PDF `document`, or
   a plain-text `document`. The SDK hands data over as bytes, a base64 string
   or a URL. This converter skipped every non-text part, so an attached image
   or PDF never reached the model on this provider — silently. Other types
   are nil (Anthropic rejects them; the caller inlines text instead)."
  [part]
  (let [mt   (str (or (.-mediaType part) ""))
        data (.-data part)]
    (when (some? data)
      (cond
        (instance? js/URL data)
        (cond
          (.startsWith mt "image/") #js {:type "image" :source #js {:type "url" :url (.-href data)}}
          (= mt "application/pdf")  #js {:type "document" :source #js {:type "url" :url (.-href data)}}
          :else nil)

        (.startsWith mt "image/")
        #js {:type "image" :source #js {:type "base64" :media_type mt :data (base64-of data)}}

        (= mt "application/pdf")
        (cond-> #js {:type "document" :source #js {:type "base64" :media_type mt :data (base64-of data)}}
          (.-filename part) (doto (aset "title" (.-filename part))))

        (= mt "text/plain")
        #js {:type "document"
             :source #js {:type "text" :media_type mt
                          :data (if (string? data)
                                  (.toString (js/Buffer.from data "base64") "utf-8")
                                  (.toString (js/Buffer.from data) "utf-8"))}}
        :else nil))))

(defn- content-part->anthropic [part]
  (case (.-type part)
    "text"      (text-part->anthropic part)
    "tool-call" (tool-call-part->anthropic part)
    "file"      (file-part->anthropic part)
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
        ;; Extended thinking, under the same providerOptions.anthropic.thinking
        ;; key @ai-sdk/anthropic uses — agent.thinking routes `claude-native` to
        ;; the anthropic dialect, and this provider is hand-rolled, so without
        ;; this the block was built and silently dropped.
        anth       (some-> (.-providerOptions opts) (aget "anthropic"))
        t          (when anth (aget anth "thinking"))
        ;; enabled+budget for older models; ADAPTIVE (+ effort below) for
        ;; current ones, which reject budget_tokens. Only "enabled" was read,
        ;; so agent.thinking's adaptive request was silently dropped here.
        thinking   (case (str (some-> t .-type))
                     "enabled"  {"type" "enabled"
                                 "budget_tokens" (or (.-budgetTokens t) (aget t "budget_tokens"))}
                     "adaptive" (cond-> {"type" "adaptive"}
                                  (.-display t) (assoc "display" (.-display t)))
                     "disabled" {"type" "disabled"}
                     nil)
        effort     (when anth (aget anth "effort"))
        ;; Current Claude models reject non-default sampling parameters with
        ;; a 400, and settings default temperature to 0.2 — every request
        ;; failed. @ai-sdk/anthropic strips them for these models; so must this.
        sampling?  (not (thinking-util/rejects-sampling? model-id))
        budget     (get thinking "budget_tokens")
        ;; Anthropic counts reasoning against max_tokens and rejects a request
        ;; whose budget is not strictly smaller.
        max-tokens (let [m (or (.-maxOutputTokens opts) default-max-tokens)]
                     (if (and budget (<= m budget)) (+ budget 1024) m))]
    (cond-> {"model"      model-id
             "max_tokens" max-tokens
             "stream"     true
             "messages"   messages}
      thinking                         (assoc "thinking" thinking)
      effort                           (assoc "output_config" {"effort" effort})
      system-str                       (assoc "system" system-str)
      (and sampling? (some? (.-temperature opts))) (assoc "temperature" (.-temperature opts))
      (and sampling? (some? (.-topP opts)))        (assoc "top_p" (.-topP opts))
      (.-stopSequences opts)           (assoc "stop_sequences" (.-stopSequences opts)))))
