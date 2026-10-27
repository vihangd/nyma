(ns agent.utils.pi-shape
  "nyma messages in the shapes pi's `AgentMessage` uses (pi-agent-core 1.2):
   user, assistant with `[text | thinking | toolCall]` blocks, and toolResult.
   Pure, so the field names a pi consumer reads can be pinned one by one —
   a misnamed field still arrives, it is just never read.

   `agent.modes.pi-rpc` writes these; a stateful assistant message is built
   here block by block and snapshotted as `partial` on every update, which is
   what ThinkRail renders from."
  (:require [agent.utils.content :as content]
            [agent.utils.event-json :refer [step-usage]]
            [agent.ui.think-tag-parser :as think]
            [clojure.string :as str]))

(defn part->pi
  "nyma content part → pi content item."
  [p]
  (cond
    (= (:type p) "text")
    {:type "text" :text (or (:text p) "")}

    ;; pi's own image shape. It became an empty text item, so a resumed or
    ;; mirrored session lost its screenshots in the frontend.
    (content/image-part? p)
    {:type "image" :data (or (:image p) (:data p) "") :mimeType (or (:mediaType p) "image/png")}

    (content/file-part? p)
    {:type "text" :text (content/->text [p])}

    (or (= (:type p) "tool-call") (= (:type p) "toolCall"))
    {:type "toolCall"
     :id (or (:toolCallId p) (:id p) "")
     :name (or (:toolName p) (:name p) "")
     :arguments (or (:args p) (:input p) (:arguments p) {})}

    :else {:type "text" :text (str (or (:text p) ""))}))

(defn ->pi-message
  "nyma state message {:role :content} → pi message object."
  [m]
  (let [c (:content m)]
    {:role (or (:role m) "assistant")
     :content (cond
                (string? c) [{:type "text" :text c}]
                (sequential? c) (mapv part->pi c)
                :else [])
     :timestamp (or (:timestamp m) (js/Date.now))}))

(defn stop-reason
  "AI SDK finishReason → pi's `stop | length | toolUse | error | aborted`.
   ThinkRail treats a message's tool calls as executable only on `toolUse`."
  [finish]
  (case (str finish)
    "tool-calls" "toolUse"
    "length"     "length"
    "error"      "error"
    "stop"))

(defn usage
  "StepResult usage → pi's Usage, keeping `inputTokens`/`outputTokens`, which
   `@agentproto/adapter-pi` reads off `turn_end.message.usage`."
  [step]
  (let [u  (step-usage step)
        in (or (:inputTokens u) 0)
        out (or (:outputTokens u) 0)
        cr (or (some-> step .-usage .-inputTokenDetails .-cacheReadTokens) 0)
        cw (or (some-> step .-usage .-inputTokenDetails .-cacheWriteTokens) 0)]
    (cond-> {:input in :output out :cacheRead cr :cacheWrite cw :totalTokens (+ in out)
             :cost {:input 0 :output 0 :cacheRead 0 :cacheWrite 0 :total 0}}
      u (merge u))))

(defn assistant-message
  "An empty assistant message carrying the model it came from."
  [{:keys [api provider model]}]
  {:role "assistant" :content [] :api (or api "") :provider (or provider "")
   :model (or model "") :usage (usage nil) :stopReason "stop" :timestamp (js/Date.now)})

(defn split-think
  "Text blocks carrying a model's inline `<think>…</think>` become a thinking
   block and a text block, as pi's message has them. The conservative split
   (no orphan-closer guess): a consumer stores this message, and a false
   positive there would cut real answer text."
  [content]
  (vec (mapcat (fn [b]
                 (if (and (= "text" (:type b)) (re-find #"(?i)<think" (str (:text b))))
                   (let [{:keys [reasoning text]} (think/split-blocks* (str (:text b)) false)]
                     (cond-> []
                       (seq reasoning)       (conj {:type "thinking" :thinking reasoning})
                       (seq (str/trim text)) (conj {:type "text" :text text})))
                   [b]))
               content)))

(defn add-block
  "`msg` with `block` appended → [msg index]."
  [msg block]
  [(update msg :content conj block) (count (:content msg))])

(defn append-at
  "`msg` with `d` appended to field `k` of content block `i`."
  [msg i k d]
  (update-in msg [:content i k] str d))

(defn tool-call-index
  "Index of the toolCall block with `id`, or nil."
  [msg id]
  (first (keep-indexed (fn [i b] (when (and (= "toolCall" (:type b)) (= id (:id b))) i))
                       (:content msg))))

(defn result-text [r]
  (cond (string? r)    r
        (nil? r)       ""
        (undefined? r) ""
        :else          (try (js/JSON.stringify r) (catch :default _ (str r)))))

(defn tool-result-message
  "A finished tool execution → pi ToolResultMessage. `:result` is kept beside
   pi's fields for consumers of nyma's earlier turn_end shape."
  [{:keys [id name text details error?]}]
  (let [content [{:type "text" :text (str text)}]]
    {:role "toolResult" :toolCallId (str id) :toolName (str name)
     :content content :details (or details {}) :isError (boolean error?)
     :result {:content content :details (or details {})}
     :timestamp (js/Date.now)}))

(defn entries->messages
  "Session branch entries → pi messages, for `get_messages` and history.
   nyma persists a run as `user, tool_call…, assistant`, with the step text
   between tool calls not kept; each run of tool_call entries becomes one
   assistant message of toolCall blocks followed by its toolResult messages."
  [entries]
  (let [flush (fn [acc calls]
                (if (empty? calls)
                  acc
                  (into (conj acc (assoc (assistant-message {})
                                         :stopReason "toolUse"
                                         :content (mapv (fn [c] {:type "toolCall" :id (:id c)
                                                                 :name (:name c) :arguments (:args c)})
                                                        calls)))
                        (map (fn [c] (assoc (tool-result-message c) :timestamp (:ts c))))
                        calls)))]
    (loop [[e & more] entries acc [] calls []]
      (if (nil? e)
        (flush acc calls)
        (let [role (:role e)]
          (cond
            (= role "tool_call")
            (let [md (or (:metadata e) {})]
              (recur more acc (conj calls {:id   (or (:tool-call-id md) (:id e) "")
                                           :name (or (:tool-name md) "")
                                           :args (or (:args md) {})
                                           :text (:content e)
                                           :ts   (:timestamp e)})))

            (contains? #{"user" "assistant"} role)
            (recur more (conj (flush acc calls) (->pi-message e)) [])

            :else (recur more acc calls)))))))

(defn session-id
  "A session's id as pi reports it: the file's name without `.jsonl`.
   Filename-safe, since a consumer keys files by it (ThinkRail's todo
   store), and the same in get_state and an extension's ctx."
  [file]
  (some-> file str (str/split #"/") last (str/replace #"\.jsonl$" "")))

(defn queue-texts
  "A message queue as the plain strings pi's `queue_update` carries."
  [q]
  (mapv (fn [m] (let [c (:content m)] (if (string? c) c (str/trim (content/->text c))))) q))
