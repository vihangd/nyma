(ns agent.extensions.small-model.knowledge-inject
  "Task-relevant knowledge injection — score a small library of markdown
   cards against the current user message and inject only the top matches.

   Borrowed from little-coder's `knowledge-inject` (word=1.0 / bigram=2.0 /
   threshold scoring). The SOTA lesson (SkillRet) that shapes the design:
   retrieval is the bottleneck and context is the cost — for small models we
   inject only the top-K cards (default 1) and hard-cap the byte budget, never
   the whole library.

   Cards live as flat `*.md` files under `.nyma/<dir>/` (project then home),
   dir defaults to `knowledge`. Each file is one card: optional YAML
   frontmatter (name/description) + markdown body, reusing the skills loader's
   `parse-frontmatter`. The card's name+description is matched against the
   user's prompt; the body is what gets injected.

   Injection uses the CONCATENATED `system-prompt-additions` collection key
   (events.cljs) so it composes with evidence.cljs's scalar systemPromptAddition
   rather than clobbering it.

   Off by default; opt-in via small-model config :knowledge-inject {:enabled true}."
  (:require ["node:path" :as path]
            ["node:fs" :as fs]
            [clojure.string :as str]
            [agent.resources.skills :refer [parse-frontmatter first-skill-line]]))

;; ── Scoring (pure) ───────────────────────────────────────────────

(defn tokenize [s]
  (->> (str/split (str/lower-case (or s "")) (js/RegExp. "[^a-z0-9]+" "g"))
       (filterv (complement str/blank?))))

(defn bigrams [toks]
  (mapv (fn [a b] (str a " " b)) toks (rest toks)))

(defn prep-card
  "Precompute the card's token/bigram match sets from its :match-text."
  [m]
  (let [toks (tokenize (:match-text m))]
    (assoc m :c-toks (set toks) :c-bi (set (bigrams toks)))))

(defn score
  "word=1 per query token present in the card, bigram=2 per query bigram
   present in the card. Card token/bigram sets are precomputed once at load.
   Higher = more relevant."
  [query c-toks c-bi]
  (let [q-toks (tokenize query)]
    (+ (count (filter c-toks q-toks))
       (* 2 (count (filter c-bi (bigrams q-toks)))))))

;; ── Card loading ─────────────────────────────────────────────────

(defn- fm-get [fm k]
  (cond (nil? fm) nil
        (object? fm) (aget fm k)
        (map? fm) (get fm k)
        :else nil))

(defn- card-dirs [dir-name]
  (let [home (.. js/process -env -HOME)]
    (filterv some?
             [(path/join (js/process.cwd) ".nyma" dir-name)
              (when home (path/join home ".nyma" dir-name))])))

(defn load-cards
  "Load `*.md` cards from `.nyma/<dir-name>/` (project dir shadows home on
   name collision). Token/bigram match sets are precomputed once here so the
   per-turn scorer does no card-side tokenization. Returns a vector of
   {:name :description :body :match-text :c-toks :c-bi}."
  [dir-name]
  (->> (card-dirs dir-name)
       (filter #(fs/existsSync %))
       (mapcat
        (fn [d]
          (->> (fs/readdirSync d)
               (filter #(.endsWith % ".md"))
               (map (fn [f]
                      (let [raw (fs/readFileSync (path/join d f) "utf8")
                            {:keys [frontmatter body]} (parse-frontmatter raw)
                            nm   (or (fm-get frontmatter "name")
                                     (.replace f (js/RegExp. "\\.md$") ""))
                            desc (or (fm-get frontmatter "description")
                                     (first-skill-line body))
                            mt   (str nm " " desc)]
                        (prep-card {:name nm :description desc :body body
                                    :match-text mt})))))))
       ;; First dir wins on duplicate name (project shadows home).
       (reduce (fn [acc c] (if (some #(= (:name %) (:name c)) acc) acc (conj acc c))) [])))

(defn select
  "Pure selection: score cards against query, keep those ≥ threshold, top-K."
  [cards query threshold top-k]
  (->> cards
       (map #(assoc % :score (score query (:c-toks %) (:c-bi %))))
       (filter #(>= (:score %) threshold))
       (sort-by :score >)
       (take top-k)
       vec))

(defn- user-text [um]
  (cond (string? um) um
        (map? um) (str (:content um))
        (object? um) (str (.-content um))
        :else ""))

(defn- trim-to [s max-chars]
  (if (> (count s) max-chars)
    (str (.slice s 0 max-chars) "\n…[knowledge truncated]")
    s))

;; ── Activation ───────────────────────────────────────────────────

(defn activate
  "Wire the before_agent_start knowledge injector. Returns a cleanup fn."
  [api config _state]
  (let [ki        (:knowledge-inject config)
        dir       (or (:dir ki) "knowledge")
        top-k     (or (:top-k ki) 1)
        threshold (or (:threshold ki) 2.0)
        budget    (* 4 (or (:token-budget ki) 800))   ; ~4 chars/token
        cards     (load-cards dir)
        ;; before_agent_start re-fires on steer/follow-up re-entry with the
        ;; nudge text as the user message; re-scoring there would drop the card
        ;; mid-task and bust the prompt cache. Keep the last non-empty block and
        ;; reuse it until a query selects something new.
        last-block (atom nil)
        handler
        (fn [data _ctx]
          (when (seq cards)
            (let [q      (user-text (.-userMessage data))
                  chosen (when (seq (str/trim (or q ""))) (select cards q threshold top-k))]
              (when (seq chosen)
                (reset! last-block
                        (->> chosen
                             (map (fn [c] (str "## " (:name c) "\n" (:body c))))
                             (str/join "\n\n")
                             (str "# Relevant knowledge (injected for this task)\n\n")
                             (#(trim-to % budget)))))
              (when-let [block @last-block]
                #js {:system-prompt-additions #js [block]}))))]
    (.on api "before_agent_start" handler)
    (fn [] (.off api "before_agent_start" handler))))
