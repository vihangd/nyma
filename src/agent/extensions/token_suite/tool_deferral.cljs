(ns agent.extensions.token-suite.tool-deferral
  "Generic deferred tools: withhold a tool's schema until the model asks for it.

   `mcp_client/deferred.cljs` measured why this is worth having:

     \"the standing prompt prefix was 18,816 tokens per request and the five
      configured MCP servers accounted for 9,993 of it — 53% … roughly 8,700 is
      JSON Schema for parameters, which a description cap cannot touch.\"

   That machinery was MCP-only. Any tool's schema costs the same every request,
   so this is the same idea as a setting over any tool: name patterns in
   `token-suite.tool-deferral.defer`, and the matching tools stop being
   advertised until `tool_search` turns them up.

   ## Why there is no `tool_invoke`

   The obvious companion — a dispatcher that runs any tool by name, which is
   what `mcp_call` does — is deliberately absent. nyma's permission gate keys
   on the NAME of the tool being called, so a generic dispatcher would present
   itself to the gate instead of its target: one `ask` for `tool_invoke` and
   every deferred tool behind it runs under that single answer, whatever its
   own policy says. That is exactly the hole `96c0da5` closed for tools that
   declare no safety, and re-opening it to save a turn is the wrong trade.
   (`mcp_call` can live with it because an MCP tool's side effects are unknown
   to nyma either way, so it declares the worst case for itself.)

   So `tool_search` reveals the schema and ACTIVATES the tool, and the tool is
   advertised from the next request onward, gated by its own metadata like any
   other. The cost is one turn.

   ## Why activation is next-turn and not sooner

   `loop.cljs:231` builds the tool map once per loop iteration — per turn, not
   per step — so a tool activated mid-turn cannot reach the model until the
   next one. `mcp_client/deferred` records the same constraint and chose
   dispatch to work around it. The search result says so in words, because a
   model told about a tool it cannot yet call will otherwise try and get a
   NoSuchTool error.

   Also: not changing the advertised tool list mid-turn is what keeps the
   prompt cache intact, which is half the point of deferring in the first
   place."
  (:require [clojure.string :as str]
            ["zod" :as z]
            [agent.debug :as d]
            [agent.tool-metadata :as tm]
            [agent.extensions.token-suite.shared :as shared]))

;; ── matching ─────────────────────────────────────────────────────

(defn pattern->re
  "A defer pattern → regex. `*` is the only wildcard; everything else is
   literal, so a pattern cannot accidentally be a regex.

   Patterns match the LIVE tool name, which for an extension's tool is
   `<ns>__<name>` — so `mcp__*` and `token-suite__*` both work, and a bare
   `multi_edit` is handled by `matches-tool-name?` below rather than here."
  [pat]
  (let [escaped (-> (str pat)
                    (.replace (js/RegExp. "[.+?^${}()|\\[\\]\\\\]" "g") "\\$&")
                    (.replace (js/RegExp. "\\*" "g") ".*"))]
    (js/RegExp. (str "^" escaped "$"))))

(defn deferred?
  "Should `tool-name` be withheld? Pure.

   A pattern matches either the live name (so `mcp__*` works) or the bare name
   through `matches-tool-name?` (so `multi_edit` catches
   `token-suite__multi_edit`), because a user writing settings knows the tool
   as the model sees it in one case and as the docs name it in the other."
  [tool-name patterns]
  (let [n (str tool-name)]
    (boolean
     (some (fn [p]
             (let [p (str p)]
               (or (.test (pattern->re p) n)
                   (and (not (.includes p "*")) (tm/matches-tool-name? n p)))))
           (or patterns [])))))

(def search-tool-name
  "The bare name of the search tool. The live name is `token-suite__tool_search`
   — the scoped `registerTool` prefixes — so everything that has to recognise
   it goes through `matches-tool-name?` rather than comparing strings."
  "tool_search")

(defn withheld
  "Which of `names` to withhold: deferred by pattern, minus already activated,
   minus anything that must never be withheld. Pure.

   `tool_search` itself is excluded — withholding the only route to the
   withheld tools would make them permanently unreachable, which is the
   failure `gateway-tool-names` exists to prevent."
  [names patterns activated]
  (->> names
       (remove (fn [n] (contains? (set activated) (str n))))
       (remove (fn [n] (tm/matches-tool-name? (str n) search-tool-name)))
       (filter (fn [n] (deferred? n patterns)))
       (map str)
       set))

;; ── search ───────────────────────────────────────────────────────

(defn score-match
  "How well `query` matches a tool. nil for no match. Pure.

   Name hits beat description hits, because a model that knows roughly what a
   tool is called should get it first."
  [query tool-name description]
  (let [q (-> (str query) .trim .toLowerCase)
        n (.toLowerCase (str tool-name))
        dsc (.toLowerCase (str (or description "")))]
    (cond
      (str/blank? q)     1                       ; empty query lists everything
      (= n q)            100
      (.includes n q)    50
      (.includes dsc q)  10
      ;; Last resort: any word of a multi-word query hitting the name, so
      ;; "read a file" still finds `read`.
      (some (fn [w] (and (> (count w) 2) (.includes n w)))
            (str/split q #"\s+"))
      5
      :else nil)))

(defn schema->json
  "A tool's declared schema as JSON Schema, or nil.

   An in-tree tool's `:inputSchema` is a ZOD schema, and `JSON.stringify` of
   one yields Zod internals — `{\"def\":{\"type\":\"object\",\"shape\":…}}` — with every
   `.describe()` string dropped. That is not a schema the model can use, and
   the schema is the only reason the tool was withheld, so handing back
   internals makes the whole feature pointless.

   `z.toJSONSchema` is the converter. Wrapped, because a recursive schema can
   make it throw, and that throw would land inside `tool_search`'s execute."
  [schema]
  (when schema
    (try
      (cond
        ;; Already JSON Schema (an MCP bridge hands us a plain object).
        (aget schema "type")       schema
        (aget schema "jsonSchema") (aget schema "jsonSchema")
        :else                      (.toJSONSchema z schema))
      (catch :default _e nil))))

(defn format-results
  "Search hits → what the model reads. Pure.

   Includes the input schema: the schema is the whole reason the tool was
   withheld, so a result without it would force a second round trip and save
   nothing."
  [hits]
  (if (empty? hits)
    "No deferred tools matched. Tools already available to you are not listed here."
    (str "Found " (count hits) " tool" (when (> (count hits) 1) "s")
         ". These are now ACTIVE and callable from your NEXT message — the "
         "tool list for this message was fixed before the search ran.\n\n"
         (str/join "\n\n"
                   (map (fn [{:keys [name description schema]}]
                          (str name "\n"
                               (when (seq (str (or description ""))) (str "  " description "\n"))
                               (if-let [js-schema (schema->json schema)]
                                 (str "  parameters: " (js/JSON.stringify js-schema))
                                 "  parameters: (schema unavailable — call it and read the error)")))
                        hits)))))

;; ── activation ───────────────────────────────────────────────────

(defn activate [api]
  (let [config    (shared/load-config)
        cfg       (:tool-deferral config)
        patterns  (vec (or (:defer cfg) []))
        enabled?  (and (boolean (:enabled cfg)) (seq patterns))
        ;; Session-scoped: a tool the model has already been shown stays shown,
        ;; so the prefix grows monotonically rather than flapping between turns
        ;; and invalidating the cache each time.
        activated (atom #{})
        stats     (atom {:searches 0 :revealed 0})]

    (if-not enabled?
      (fn [] nil)
      (let [all-names
            (fn [] (vec (or (try (js->clj (.getAllTools api)) (catch :default _e nil)) [])))

            search-tool
            #js {;; Read-only: it reveals schemas and flips a flag. Declared so
                 ;; the permission gate has an opinion about it rather than
                 ;; falling through to "other" — see 96c0da5.
                 :safety #js {:read-only? true :capabilities #js [] :category "meta"}
                 :description
                 (str "Find tools that are not currently listed. Some tools are "
                      "withheld to keep the prompt small; search here by name or "
                      "purpose, then call them on your next message.")
                 :inputSchema
                 (.object z #js {:query (-> (.string z)
                                            (.describe "What the tool should do, or its name"))})
                 :execute
                 (fn [input]
                   (let [q    (str (or (aget input "query") ""))
                         hide (withheld (all-names) patterns @activated)
                         hits (->> hide
                                   (keep (fn [n]
                                           (let [t (.getTool api n)
                                                 dsc (when t (aget t "description"))
                                                 s   (score-match q n dsc)]
                                             (when s
                                               {:score s :name n :description dsc
                                                :schema (when t (or (aget t "inputSchema")
                                                                    (aget t "parameters")))}))))
                                   (sort-by (fn [h] (- (:score h))))
                                   (take 10)
                                   vec)]
                     (swap! stats update :searches inc)
                     (when (seq hits)
                       (swap! activated into (map :name hits))
                       (swap! stats update :revealed + (count hits)))
                     ;; Published on EVERY search, not only a hit: a search that
                     ;; matched nothing is the most interesting thing
                     ;; `/token-stats` could tell you, and gating the publish on
                     ;; hits meant the counter it reads never moved.
                     (swap! shared/suite-stats assoc :tool-deferral @stats)
                     (format-results hits)))}

            access-handler
            (fn [data]
              (let [offered (vec (or (.-tools data) []))
                    hide    (withheld offered patterns @activated)]
                (when (seq hide)
                  #js {:allowed (clj->js (remove hide offered))})))]

        (.registerTool api "tool_search" search-tool)
        (.on api "tool_access_check" access-handler)
        (d/debug "tool-deferral"
                 (str "deferring tools matching " (pr-str patterns)))
        (fn []
          (.off api "tool_access_check" access-handler)
          (.unregisterTool api "tool_search")
          nil)))))
