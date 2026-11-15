(ns agent.extensions.custom-provider-claude-native.messages
  "Convert LanguageModelV3Prompt → Anthropic Messages API request body."
  (:require [agent.thinking :as thinking-util]
            [agent.utils.data :as data]
            [clojure.string :as str]))

(def ^:private default-max-tokens 8192)

;; ── Content-part conversion ──────────────────────────────────

(defn- text-part->anthropic
  "A text part, or — when it carries Anthropic's compaction marker (set by the
   stream on a server compaction block) — the compaction block itself, sent
   back so the server resumes from its summary rather than compacting again.
   An empty one is dropped, as @ai-sdk/anthropic does."
  [part]
  (let [meta (some-> (.-providerOptions part) (aget "anthropic"))]
    (if (= "compaction" (some-> meta (aget "type")))
      (when (seq (str (.-text part)))
        (cond-> #js {:type "compaction" :content (.-text part)}
          (string? (aget meta "signature")) (doto (aset "signature" (aget meta "signature")))))
      #js {:type "text" :text (.-text part)})))

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
                        (contains? #{"file" "image-data" "media" "file-data"} t)
                        ;; v7 tags the data ({type data, data}); older parts
                        ;; carry it bare.
                        (when-let [b (file-part->anthropic #js {:mediaType (.-mediaType p)
                                                                :data (let [d (.-data p)]
                                                                        (if (and d (= "data" (.-type d))) (.-data d) d))
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

(defn- reasoning-part->anthropic
  "A reasoning part streamed by this provider back to a `thinking` block. Only
   a signed one: Anthropic rejects a thinking block without its signature."
  [part]
  (when-let [sig (some-> (.-providerOptions part) (aget "anthropic") (aget "signature"))]
    #js {:type "thinking" :thinking (or (.-text part) "") :signature sig}))

(defn- content-part->anthropic [part]
  (case (.-type part)
    "text"      (text-part->anthropic part)
    "reasoning" (reasoning-part->anthropic part)
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

(defn- cache-control-of
  "The breakpoint kv_cache put on a prompt message
   (providerOptions.anthropic.cacheControl), or nil. This provider is
   hand-rolled, so without reading it every breakpoint was computed, counted
   against the four allowed, and never reached the wire."
  [msg]
  (some-> (.-providerOptions msg) (aget "anthropic") (aget "cacheControl")))

(defn- extract-system
  "The system prompt: a string, as always, unless a system message carries a
   breakpoint — then text blocks, the only form that can hold one."
  [prompt]
  (let [sys (.filter prompt #(and (= (.-role %) "system") (string? (.-content %))))]
    (when (pos? (.-length sys))
      (if (.some sys cache-control-of)
        (.map sys (fn [m]
                    (let [b #js {:type "text" :text (.-content m)}]
                      (when-let [cc (cache-control-of m)] (aset b "cache_control" cc))
                      b)))
        (.join (.map sys #(.-content %)) "\n\n")))))

(defn- prompt->anthropic-messages [prompt]
  (let [result #js []]
    (.forEach prompt (fn [msg]
                       (let [am (case (.-role msg)
                                  "system"    nil
                                  "user"      (user-msg->anthropic msg)
                                  "assistant" (assistant-msg->anthropic msg)
                                  "tool"      (tool-msg->anthropic msg)
                                  nil)]
                         (when am
                           ;; A message-level breakpoint goes on its last block,
                           ;; as @ai-sdk/anthropic places it — but never on a
                           ;; thinking block, which Anthropic refuses to mark.
                           (when-let [cc (cache-control-of msg)]
                             (when-let [b (last (remove #(= "thinking" (.-type %)) (.-content am)))]
                               (aset b "cache_control" cc)))
                           (.push result am)))))
    result))

;; ── Breakpoint budget ────────────────────────────────────────

(def ^:private max-breakpoints
  "Anthropic rejects a request with more cache_control blocks than this."
  4)

(defn cap-breakpoints!
  "Drop the OLDEST message breakpoints past the limit, in place. kv_cache's
   own count stays within it at the default settings, but max-message-
   breakpoints is a setting, and step-breakpoint adds one per step; the
   @ai-sdk/anthropic path drops extras itself, this hand-rolled one sent them
   and every request failed. The system mark and the newest marks stay: they
   cover the longest prefixes."
  [system messages]
  (let [sys-n  (if (js/Array.isArray system) (count (filter #(some? (.-cache_control %)) system)) 0)
        marked (vec (mapcat (fn [m] (filter #(some? (.-cache_control %)) (.-content m))) messages))
        extra  (- (+ sys-n (count marked)) max-breakpoints)]
    (doseq [b (take (max 0 extra) marked)]
      (js-delete b "cache_control"))
    messages))

;; ── Public: build full request body ─────────────────────────

(defn build-request-body
  "Build the Anthropic Messages API JSON body from a model ID and V3 call
   options. Returns a ClojureScript map; caller should clj->js + JSON.stringify."
  [model-id opts]
  (let [prompt     (.-prompt opts)
        system-str (extract-system prompt)
        messages   (cap-breakpoints! system-str (prompt->anthropic-messages prompt))
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
        ;; Server-side compaction (token_suite/anthropic_compaction), in the
        ;; camelCase shape @ai-sdk/anthropic takes; the wire is snake_case.
        ;; It was dropped here, so claude-native never compacted server-side.
        ctx-mgmt   (when-let [cm (when anth (aget anth "contextManagement"))]
                     {"edits" (vec (for [e (or (aget cm "edits") [])
                                         :when (= "compact_20260112" (aget e "type"))]
                                     (cond-> {"type" "compact_20260112"}
                                       (aget e "trigger") (assoc "trigger" (aget e "trigger"))
                                       (some? (aget e "pauseAfterCompaction"))
                                       (assoc "pause_after_compaction" (aget e "pauseAfterCompaction"))
                                       (aget e "instructions") (assoc "instructions" (aget e "instructions")))))})
        ;; Current Claude models reject non-default sampling parameters with
        ;; a 400, and settings default temperature to 0.2 — every request
        ;; failed. @ai-sdk/anthropic strips them for these models; so must this.
        ;; Thinking on: "`temperature` may only be set to 1 when thinking is
        ;; enabled" — a 400 on every request at any level above off.
        sampling?  (and (not (thinking-util/rejects-sampling? model-id))
                        (not (contains? #{"enabled" "adaptive"} (get thinking "type"))))
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
      (seq (get ctx-mgmt "edits"))     (assoc "context_management" ctx-mgmt)
      system-str                       (assoc "system" system-str)
      (and sampling? (some? (.-temperature opts))) (assoc "temperature" (.-temperature opts))
      (and sampling? (some? (.-topP opts)))        (assoc "top_p" (.-topP opts))
      (.-stopSequences opts)           (assoc "stop_sequences" (.-stopSequences opts)))))
