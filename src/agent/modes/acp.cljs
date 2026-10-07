(ns agent.modes.acp
  "Agent Client Protocol server: `nyma --mode acp` runs inside Zed, JetBrains
   and any other ACP editor.

   Newline-delimited JSON-RPC 2.0 over stdio. The editor sends `initialize`,
   `session/new`, `session/prompt`, `session/cancel` and `session/set_mode`;
   nyma streams the run back as `session/update` notifications (message and
   thought chunks, tool calls and their results) and asks the editor through
   `session/request_permission` whenever its permission gate would ask a
   person. One process serves one session at a time: a second `session/new`
   starts over, as /new does."
  (:require [agent.utils.jsonl-stdin :refer [read-lines!]]
            [agent.loop :refer [run]]
            [agent.utils.content :as content]
            [agent.version :as version]
            [agent.extensions.model-roles.policy :as policy]
            [agent.tool-metadata :as tm]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def protocol-version 1)

(defn- write! [obj]
  (.write js/process.stdout (str (js/JSON.stringify (clj->js obj)) "\n")))

(defn- reply! [id result] (write! {:jsonrpc "2.0" :id id :result result}))
(defn- reply-error! [id code msg] (write! {:jsonrpc "2.0" :id id :error {:code code :message msg}}))

(defn tool-kind
  "ACP ToolKind for a nyma tool name — registered names carry an extension's
   `ns__` prefix, hence the shared matcher. Pure."
  [tool]
  (let [is? (fn [& names] (tm/matches-any-tool-name? (str tool) (set names)))]
    (cond
      (is? "read" "view_image")                      "read"
      (is? "write" "edit" "multi_edit" "ast_edit")   "edit"
      (is? "bash")                                   "execute"
      (is? "grep" "glob" "ls" "ast_grep")            "search"
      (is? "web_fetch" "web_search" "deep_research") "fetch"
      (is? "think")                                  "think"
      :else                                          "other")))

(defn tool-title
  "A one-line title for a tool call: the tool and its most telling argument."
  [tool args]
  (let [a (or args {})
        v (or (get a :command) (get a :path) (get a :pattern) (get a :url) (get a :query))]
    (if v (str tool ": " (let [s (str v)] (if (> (count s) 80) (str (subs s 0 80) "…") s))) (str tool))))

(defn prompt->content
  "ACP ContentBlocks → nyma :content. Text and embedded text resources become
   text; images and blob resources become attachments; a resource link is
   named, since the editor did not send its contents."
  [blocks]
  (let [texts (atom []) atts (atom [])]
    (doseq [b (or blocks [])]
      (case (aget b "type")
        "text"          (swap! texts conj (aget b "text"))
        "image"         (swap! atts conj #js {:data (aget b "data") :mimeType (aget b "mimeType")})
        "resource_link" (swap! texts conj (str "@" (or (some-> (aget b "uri") (str/replace #"^file://" "")) (aget b "name"))))
        "resource"      (let [r (aget b "resource") uri (str (aget r "uri"))]
                          (if (string? (aget r "text"))
                            (swap! texts conj (str "<file path=\"" (str/replace uri #"^file://" "") "\">\n" (aget r "text") "\n</file>"))
                            (swap! atts conj #js {:data (aget r "blob") :mimeType (aget r "mimeType")
                                                  :name (last (.split uri "/"))})))
        nil))
    (content/prompt-content (str/join "\n\n" @texts) (clj->js @atts))))

(def ^:private option-kinds
  "nyma's permission choices → ACP PermissionOptionKind."
  [["Allow once" "allow_once"]
   ["Allow for this session" "allow_always"]
   ["Allow always" "allow_always"]
   ["Deny" "reject_once"]])

(defn- option-kind [label]
  (or (some (fn [[prefix k]] (when (str/starts-with? (str label) prefix) k)) option-kinds) "allow_once"))

(defn- modes [agent]
  {:currentModeId (or (:permission-mode @(:state agent)) "default")
   :availableModes (mapv (fn [m] {:id m :name m}) policy/mode-cycle)})

(defn ^:async start
  "Serve ACP on stdio until stdin closes. Returns a cleanup thunk."
  [agent & [{:keys [on-eof]}]]
  (let [session-id (atom nil)
        running    (atom nil)    ;; {:id rpc-id} while a prompt runs
        cancelled? (atom false)
        next-id    (atom 0)
        pending    (atom {})     ;; our request id → resolve fn
        update!    (fn [u] (when @session-id
                             (write! {:jsonrpc "2.0" :method "session/update"
                                      :params {:sessionId @session-id :update u}})))
        request!   (fn [method params]
                     (js/Promise.
                      (fn [resolve _]
                        (let [id (swap! next-id inc)]
                          (swap! pending assoc id resolve)
                          (write! {:jsonrpc "2.0" :id id :method method :params params})))))
        tool-ids   (atom 0)
        ;; nyma's gate asks through ui.select; in ACP the editor asks.
        ui         #js {:available true
                        :select  ^:async
                        (fn [prompt options]
                          (let [opts (vec options)
                                r    (js-await (request! "session/request_permission"
                                                         {:sessionId @session-id
                                                          :toolCall {:toolCallId (str "perm-" (swap! tool-ids inc))
                                                                     :title (first (str/split-lines (str prompt)))
                                                                     :status "pending"
                                                                     :content [{:type "content"
                                                                                :content {:type "text" :text (str prompt)}}]}
                                                          :options (vec (map-indexed (fn [i o] {:optionId (str i) :name o :kind (option-kind o)})
                                                                                     opts))}))
                                out  (some-> r (aget "outcome"))]
                            (when (= "selected" (some-> out (aget "outcome")))
                              (nth opts (js/Number (aget out "optionId")) nil))))
                        :confirm ^:async
                        (fn [msg]
                          (let [r (js-await (request! "session/request_permission"
                                                      {:sessionId @session-id
                                                       :toolCall {:toolCallId (str "confirm-" (swap! tool-ids inc))
                                                                  :title (first (str/split-lines (str msg)))}
                                                       :options [{:optionId "yes" :name "Yes" :kind "allow_once"}
                                                                 {:optionId "no" :name "No" :kind "reject_once"}]}))]
                            (= "yes" (some-> r (aget "outcome") (aget "optionId")))))
                        :notify  (fn [_ _] nil)
                        :input   (fn [& _] (js/Promise.resolve nil))}
        ;; Layered over the headless ui, so the no-op widget/status methods
        ;; extensions call still exist.
        _          (when-let [api (.-extension-api agent)]
                     (aset api "ui" (js/Object.assign #js {} (.-ui api) ui)))
        on         (:on (:events agent))
        off        (:off (:events agent))
        handlers
        [["message_update"
          (fn [chunk] (let [d (or (.-text chunk) (.-textDelta chunk) "")]
                        (when (seq d) (update! {:sessionUpdate "agent_message_chunk" :content {:type "text" :text d}}))))]
         ["reasoning_delta"
          (fn [chunk] (let [d (or (.-textDelta chunk) (.-text chunk) (.-delta chunk) "")]
                        (when (seq d) (update! {:sessionUpdate "agent_thought_chunk" :content {:type "text" :text d}}))))]
         ["tool_execution_start"
          (fn [data] (let [args (or (:args data) {})
                           p    (get args :path)]
                       (update! (cond-> {:sessionUpdate "tool_call" :toolCallId (str (:execId data))
                                         :title (tool-title (:toolName data) args)
                                         :kind (tool-kind (:toolName data)) :status "in_progress"
                                         :rawInput args}
                                  p (assoc :locations [{:path (path/resolve (str p))}])))))]
         ["tool_execution_end"
          (fn [data] (let [r (str (:result data))]
                       (update! {:sessionUpdate "tool_call_update" :toolCallId (str (:execId data))
                                 :status (if (:isError data) "failed" "completed")
                                 :content [{:type "content"
                                            :content {:type "text"
                                                      :text (if (> (count r) 8000) (str (subs r 0 8000) "\n…") r)}}]})))]]
        _          (doseq [[ev h] handlers] (on ev h))
        cancel!    (fn []
                     (reset! cancelled? true)
                     (try (.abort @(:abort-controller agent)) (catch :default _ nil))
                     ;; an open permission question resolves as cancelled
                     (doseq [[_ res] @pending] (res #js {:outcome #js {:outcome "cancelled"}}))
                     (reset! pending {}))
        handle
        (fn [line]
          (let [msg (try (js/JSON.parse line) (catch :default _ nil))
                id  (when msg (aget msg "id"))
                m   (when msg (aget msg "method"))
                p   (when msg (or (aget msg "params") #js {}))]
            (cond
              (nil? msg) (write! {:jsonrpc "2.0" :id nil :error {:code -32700 :message "Parse error"}})

              ;; a response to one of OUR requests
              (and (nil? m) (some? id))
              (when-let [res (get @pending id)]
                (swap! pending dissoc id)
                (res (or (aget msg "result") #js {:outcome #js {:outcome "cancelled"}})))

              (= m "initialize")
              (reply! id {:protocolVersion protocol-version
                          :agentCapabilities {:loadSession false
                                              :promptCapabilities {:image true :audio false :embeddedContext true}}
                          :authMethods []
                          :agentInfo {:name "nyma" :title "nyma" :version version/version}})

              (= m "authenticate") (reply! id {})

              (= m "session/new")
              (let [cwd (aget p "cwd")]
                (when (and (string? cwd) (fs/existsSync cwd)) (js/process.chdir cwd))
                (swap! (:state agent) assoc :messages [])
                (reset! session-id (str "nyma-" (js/Date.now)))
                (reply! id {:sessionId @session-id :modes (modes agent)}))

              (= m "session/set_mode")
              (let [mode (str (aget p "modeId"))]
                (if (policy/mode? mode)
                  (do (swap! (:state agent) assoc :permission-mode mode)
                      (update! {:sessionUpdate "current_mode_update" :currentModeId mode})
                      (reply! id {}))
                  (reply-error! id -32602 (str "unknown mode: " mode))))

              (= m "session/cancel") (cancel!)

              (= m "session/prompt")
              (cond
                (not= (aget p "sessionId") @session-id) (reply-error! id -32602 "unknown session")
                @running (reply-error! id -32603 "a prompt is already running")
                :else
                (do (reset! running {:id id})
                    (reset! cancelled? false)
                    ;; Not awaited: session/cancel must be read while it runs.
                    (-> (run agent (prompt->content (aget p "prompt")))
                        (.then (fn [_] (reply! id {:stopReason (if @cancelled? "cancelled" "end_turn")})))
                        (.catch (fn [e] (if @cancelled?
                                          (reply! id {:stopReason "cancelled"})
                                          (reply-error! id -32603 (str (.-message e))))))
                        (.finally (fn [] (reset! running nil))))))

              (some? id) (reply-error! id -32601 (str "Method not found: " m))
              :else nil)))
        stop (read-lines! js/process.stdin handle (fn [] (cancel!) (when on-eof (on-eof))))]
    (fn []
      (stop)
      (doseq [[ev h] handlers] (off ev h)))))
