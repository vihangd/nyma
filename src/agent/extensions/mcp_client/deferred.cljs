(ns agent.extensions.mcp-client.deferred
  "Deferred MCP tools: two fixed tools instead of N schemas.

   Measured on this machine: the standing prompt prefix was 18,816 tokens per
   request and the five configured MCP servers accounted for 9,993 of it — 53%
   — whether or not the task had anything to do with them. Capping
   `max-description-length` from 1200 to 80 recovered only 1,274 of that, so
   roughly 8,700 is JSON Schema for parameters, which a description cap cannot
   touch.

   The shape here is the `skill` tool's: names in a tool description,
   descriptions in the system prompt, one small schema standing in for many.

     mcp_search(query)            — find tools, get their schemas back as text
     mcp_call(server, tool, args) — invoke any of them

   Why a dispatch tool rather than promoting what the model discovers into the
   real tool list: `loop.cljs` builds the tool map ONCE per loop iteration and
   `streamText` runs its whole multi-step sequence against it, so a tool made
   active mid-turn would not reach the model until the next turn. Dispatch lets
   search and call land in the same turn. It also never changes the tool list,
   so the prompt cache is never invalidated — which matters at the 86% cache
   read these runs measure — and it keeps `toolcall_rescue` working, since that
   validates prose-written calls against the ACTIVE set and would not recognise
   a deferred name.

   The bridged `mcp__server__tool` entries stay REGISTERED throughout. They are
   only withheld from the model, so the override wrappers that call them
   internally are unaffected."
  (:require ["ai" :refer [tool]]
            ["zod" :as z]
            [clojure.string :as str]
            [agent.extensions.mcp-client.manager :as mgr]
            [agent.extensions.mcp-client.client :as client]))

(def default-max-results
  "Matches returned by one search. Anthropic's tool search defaults to 5 for
   the same reason: a longer list is another catalog to read."
  5)

(def default-defer-threshold-tokens
  "Only defer once the MCP schemas are worth a round trip. A couple of small
   servers cost less than the search dance, so they stay eager.

   Absolute rather than a share of the context window because sizing against
   the window needs the `context` capability, and this extension has no reason
   to hold one."
  2000)

;;; ─── the catalog ───────────────────────────────────────────────

(defn catalog
  "Every tool every running server offers, flattened for search.
   `{:server :name :nyma-name :description :schema}`."
  [manager]
  (vec
   (mapcat (fn [{:keys [name client]}]
             (map (fn [t]
                    {:server      name
                     :name        (:name t)
                     :nyma-name   (str "mcp__" name "__" (:name t))
                     :description (or (:description t) "")
                     :schema      (:input-schema t)})
                  (or (client/list-tools client) [])))
           (mgr/all-clients manager))))

(defn- schema-words
  "Parameter names and their descriptions — two of the four fields a search
   should cover, and the ones a bare name/description index misses."
  [schema]
  (try
    (let [props (some-> schema (aget "properties"))]
      (if (object? props)
        (str/join " "
                  (mapcat (fn [k]
                            (let [p (aget props k)]
                              [k (or (and (object? p) (aget p "description")) "")]))
                          (js/Object.keys props)))
        ""))
    (catch :default _e "")))

(defn haystack
  "The searchable text for one entry: name, description, parameter names and
   parameter descriptions, lowercased."
  [entry]
  (str/lower-case
   (str (:server entry) " " (:name entry) " " (:description entry) " "
        (schema-words (:schema entry)))))

;;; ─── search ────────────────────────────────────────────────────

(defn- tokens [q]
  (->> (str/split (str/lower-case (str q)) #"[^a-z0-9_]+")
       (remove str/blank?)
       vec))

(defn score
  "How well `entry` answers `query`. A hit in the NAME counts for more than one
   buried in a parameter description, so `search files` prefers a tool called
   `search_files` over one that merely mentions searching."
  [entry query]
  (let [qs   (tokens query)
        name (str/lower-case (str (:server entry) " " (:name entry)))
        hay  (haystack entry)]
    (reduce (fn [acc t]
              (cond
                (.includes name t) (+ acc 3)
                (.includes hay t)  (+ acc 1)
                :else              acc))
            0
            qs)))

(defn search
  "Best matches for `query`, highest score first. Ties break on name so the
   result is stable and a test can assert on it. Pure."
  [entries query & [limit]]
  (let [n (or limit default-max-results)]
    (->> entries
         (map (fn [e] [e (score e query)]))
         (filter (fn [[_ s]] (pos? s)))
         (sort-by (fn [[e s]] [(- s) (:nyma-name e)]))
         (take n)
         (mapv first))))

;;; ─── rendering ─────────────────────────────────────────────────

(defn render-match
  "One match, with its parameter schema inline. The schema is the whole point:
   without it the model cannot construct a call, since there is no tool
   definition to validate against."
  [entry]
  (str "### " (:server entry) " / " (:name entry) "\n"
       (when (seq (:description entry)) (str (:description entry) "\n"))
       "parameters: "
       (try (js/JSON.stringify (:schema entry)) (catch :default _e "{}"))))

(defn render-results
  [entries query]
  (if (empty? entries)
    (str "No MCP tool matches \"" query "\". Try broader words, or a server name.")
    (str "Call any of these with mcp_call(server, tool, args).\n\n"
         (str/join "\n\n" (map render-match entries)))))

(defn server-summary
  "The one-line-per-server hint for the system prompt. Names the servers and
   their tool counts, not their tools — the tools are what we are trying not
   to send."
  [entries]
  (when (seq entries)
    (let [by-server (group-by :server entries)]
      (str "\n\n## MCP tools (search to use)\n"
           "These servers are connected. Their tools are NOT listed here — call "
           "`mcp_search` with what you are trying to do, then `mcp_call` to run one.\n"
           (str/join "\n"
                     (map (fn [[s ts]] (str "- " s ": " (count ts) " tools"))
                          (sort-by first by-server)))))))

;;; ─── the gate ──────────────────────────────────────────────────

(defn deferred-names
  "Bridged tool names to withhold this turn: every MCP tool in the catalog."
  [entries]
  (set (map :nyma-name entries)))

(defn allowed-after-deferral
  "The `tool_access_check` answer: everything currently on offer, minus the
   bridged MCP tools.

   Returns a VECTOR, always. The merge for this event is a set intersection and
   nil means 'no opinion', so returning nil here would silently re-admit every
   tool — the same trap `model_roles` documents."
  [current-tool-names entries]
  (let [drop? (deferred-names entries)]
    (vec (remove drop? current-tool-names))))

(defn defer?
  "Is the catalog big enough to be worth deferring? `estimate` is the host's
   token estimator, passed in so this stays pure and testable."
  [entries estimate threshold]
  (and (seq entries)
       (> (estimate (str/join " " (map (fn [e]
                                         (str (:description e)
                                              (try (js/JSON.stringify (:schema e))
                                                   (catch :default _e ""))))
                                       entries)))
          (or threshold default-defer-threshold-tokens))))

;;; ─── the two tools ─────────────────────────────────────────────

(defn search-tool
  [catalog-fn]
  (tool
   #js {:description
        (str "Search the connected MCP servers for a tool. Their tools are not "
             "listed in your context — search by what you want to do, then run "
             "the result with mcp_call.")
        :inputSchema
        (.object z #js {:query (-> (.string z) (.describe "What you are trying to do"))
                        :limit (-> (.number z) (.optional)
                                   (.describe "Max matches (default 5)"))})
        :execute
        (fn [input]
          (let [q (str (:query input))]
            (render-results (search (catalog-fn) q (:limit input)) q)))}))

(defn call-tool
  [manager]
  (tool
   #js {:description
        (str "Run a tool on an MCP server. Use mcp_search first to find the "
             "server, tool name and parameters.")
        :inputSchema
        (.object z #js {:server (-> (.string z) (.describe "Server name"))
                        :tool   (-> (.string z) (.describe "Tool name on that server"))
                        :args   (-> (.record z (.string z) (.any z)) (.optional)
                                    (.describe "Arguments object for the tool"))})
        :execute
        (^:async fn [input]
          (let [server (str (:server input))
                tname  (str (:tool input))
                cli    (mgr/get-client manager server)]
            (if-not cli
              (str "No MCP server named \"" server "\". Use mcp_search to find one.")
              (let [r (js-await (client/call-tool!
                                 cli
                                 {:tool-name tname
                                  :arguments (clj->js (or (:args input) {}))}))
                    text (->> (or (:content r) [])
                              (filter #(= "text" (:type %)))
                              (map :text)
                              (filter some?)
                              (str/join "\n"))]
                (if (:is-error? r) (str "[ERROR] " text) text)))))}))

;;; ─── reporting ─────────────────────────────────────────────────

(defn status-line
  "What the deferral is doing, for `/mcp`. Silent when it is off, so a small
   setup's status output is unchanged."
  [entries estimate]
  (when (seq entries)
    (let [saved (estimate (str/join " "
                                    (map (fn [e]
                                           (str (:description e)
                                                (try (js/JSON.stringify (:schema e))
                                                     (catch :default _e ""))))
                                         entries)))]
      (str "Deferred: " (count entries) " tool schemas withheld from the model, "
           "~" saved " tokens/request saved. "
           "The model reaches them with mcp_search + mcp_call."))))
