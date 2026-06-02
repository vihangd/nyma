(ns agent.extensions.small-model.evidence
  "Evidence store — per-session memory snippets that survive token_suite
   compaction.

   Small models lose task context across turns.  This module provides
   three tools (EvidenceAdd / EvidenceGet / EvidenceList) backed by
   extension-scoped state, each snippet capped at ~1 KB.

   A context_assembly hook injects the evidence block into every prompt
   so it persists through compaction.  The schema follows OpenHands'
   condenser principle: preserve goals / progress / what's-left / todo.

   Tools are namespaced: small-model__EvidenceAdd etc. (Nyma double-
   underscore convention for tool names).
  "
  (:require [agent.extensions.small-model.shared :as shared]
            [clojure.string :as str]))

;; ── Tool definitions ─────────────────────────────────────────────

(defn- make-tools [api config state]
  (let [ev-cfg   (:evidence config)
        max-snip (or (:max-snippets ev-cfg) 20)
        max-chars (or (:max-snippet-chars ev-cfg) 1024)

        add-tool
        #js {:description
             (str "Store a key fact, finding, or progress note in the evidence "
                  "store.  Evidence persists across context compaction.  Use it "
                  "to record: task goals, files changed, errors seen, "
                  "what's left to do.  Keep snippets under "
                  max-chars " characters.")
             :parameters
             #js {:type       "object"
                  :required   #js ["key" "value"]
                  :properties #js {:key   #js {:type        "string"
                                               :description "Short unique label (e.g. \"goal\", \"progress\", \"todo\", \"error-foo\")"}
                                   :value #js {:type        "string"
                                               :description "The fact or note to store"}}}
             :execute
             (fn [args]
               (let [k   (str (.-key args))
                     v   (.slice (str (.-value args)) 0 max-chars)
                     ev  (:evidence @state)
                     ;; Replace if key exists, otherwise append (up to max)
                     idx (first (keep-indexed (fn [i e] (when (= (:key e) k) i)) ev))
                     ev' (if idx
                           (assoc ev idx {:key k :value v})
                           (if (< (count ev) max-snip)
                             (conj ev {:key k :value v})
                             (conj (subvec ev 1) {:key k :value v})))]
                 (swap! state assoc :evidence ev')
                 (str "Evidence stored: \"" k "\"")))}

        get-tool
        #js {:description "Retrieve a stored evidence snippet by key."
             :parameters
             #js {:type       "object"
                  :required   #js ["key"]
                  :properties #js {:key #js {:type "string" :description "The evidence key to retrieve"}}}
             :execute
             (fn [args]
               (let [k  (str (.-key args))
                     ev (:evidence @state)
                     e  (first (filter #(= (:key %) k) ev))]
                 (if e (:value e)
                     (str "No evidence found for key: \"" k "\""))))}

        list-tool
        #js {:description "List all evidence keys and a short preview of each value."
             :parameters
             #js {:type "object" :properties #js {}}
             :execute
             (fn [_args]
               (let [ev (:evidence @state)]
                 (if (empty? ev)
                   "No evidence stored yet."
                   (str/join "\n"
                             (map (fn [{:keys [key value]}]
                                    (str "• " key ": " (.slice (str value) 0 80)
                                         (when (> (count value) 80) "…")))
                                  ev)))))}]

    [["EvidenceAdd"  add-tool]
     ["EvidenceGet"  get-tool]
     ["EvidenceList" list-tool]]))

;; ── context_assembly: inject evidence block ──────────────────────

(defn- evidence-block [ev]
  (when (seq ev)
    (str "\n\n## Evidence Store\nKey facts from this session (persists through compaction):\n"
         (str/join "\n"
                   (map (fn [{:keys [key value]}]
                          (str "- **" key "**: " value))
                        ev)))))

;; ── Activation ───────────────────────────────────────────────────

(defn activate
  "Register evidence tools and the context_assembly injection hook.
   Returns a cleanup fn."
  [api config state]
  (let [tools    (make-tools api config state)
        handlers (atom [])

        ;; Inject evidence into system prompt via before_agent_start
        on-before-start
        (fn [_data _ctx]
          (when-let [block (evidence-block (:evidence @state))]
            #js {:systemPromptAddition block}))]

    ;; Register tools
    (doseq [[name td] tools]
      (.registerTool api name td))

    ;; Hook
    (.on api "before_agent_start" on-before-start)
    (swap! handlers conj ["before_agent_start" on-before-start])

    ;; Cleanup
    (fn []
      (doseq [[name _] tools]
        (.unregisterTool api name))
      (doseq [[event handler] @handlers]
        (.off api event handler)))))
