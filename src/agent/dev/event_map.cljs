(ns agent.dev.event-map
  "Generates docs/event-map.md: every core event with its emitters and its
   listeners, and every extension-API registry with its producers and consumers.

   Borrowed from deepseek-harness's generated `docs/event-producer-consumer.md`.
   Its discipline is not 'never ship an event with no listener' — it prints a
   bare `-` and moves on — it is that neither column is ever unknown. nyma had
   the emitter half as a failing lint and no view of the listener half, and the
   registry half lived in a roadmap table maintained by hand, which is how five
   registries with no consumer lived long enough to need their own commit.

   Report, don't fail: a zero-listener row is a fact on the page. The only
   failure is drift between the committed file and the source."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.events :as events]
            [agent.loop :as agent-loop]))

(def ^:private src-root (path/resolve (js/process.cwd) "src"))

(defn- cljs-files [dir]
  (reduce
   (fn [acc e]
     (let [p (path/join dir (.-name e))]
       (cond
         (.isDirectory e)               (into acc (cljs-files p))
         (.endsWith (.-name e) ".cljs") (conj acc p)
         :else                          acc)))
   []
   (vec (fs/readdirSync dir #js {:withFileTypes true}))))

(defn- rel [p] (str "src/" (path/relative src-root p)))

(defn- names-matching
  "Event names in `source` matched by `re`, whose first group is the name."
  [re source]
  (set (map second (re-seq re (str source)))))

(def ^:private emit-re
  #"(?:emit|emit-async|emit-collect|emitGlobal|emit-fn|emit!)[^\n\"]{0,40}\"([a-zA-Z][a-zA-Z0-9_]*)\"")

(def ^:private listen-re
  #"(?:\.on|\(:on|:on\b)[^\n\"]{0,40}\"([a-zA-Z][a-zA-Z0-9_]*)\"")

(def ^:private table-listen-re
  ;; Table-driven subscription: a vector of ["event" handler] pairs fed through
  ;; `(doseq [[ev h] handlers] (on ev h))`. pi_rpc.cljs registers all thirteen of
  ;; its handlers this way, and a scan that only looked for a name near `.on`
  ;; printed `-` for five events it consumes — in a column whose `-` is the
  ;; evidence used to delete things.
  #"\[\s*\"([a-zA-Z][a-zA-Z0-9_]*)\"\s+(?:\(fn|[a-z])")

(def ^:private register-re
  ;; Both call shapes: `(.registerX api …)` and the guarded
  ;; `(when-let [reg (.-registerX api)] (reg …))` that status segments use.
  #"\.-?(register[A-Z][a-zA-Z]*)\b")

(defn scan
  "Walk src once. Returns {:emitters {event #{file}} :listeners {event #{file}}
   :registry-calls {api-name #{file}}}."
  []
  (reduce
   (fn [acc f]
     (let [src (fs/readFileSync f "utf8")
           r   (rel f)
           add (fn [m k] (update m k (fnil conj #{}) r))]
       (-> acc
           (update :emitters  (fn [m] (reduce add m (names-matching emit-re src))))
           (update :listeners (fn [m] (reduce add m (into (names-matching listen-re src)
                                                          (names-matching table-listen-re src)))))
           (update :registry-calls
                   (fn [m] (reduce add m (set (map second (re-seq register-re src)))))))))
   {:emitters {} :listeners {} :registry-calls {}}
   (cljs-files src-root)))

(def ^:private stream-mapped
  "Names emitted by mapping a stream chunk type through loop.cljs's
   `stream-event-types` — read from the map so this can never be a stale
   hand-written allow-list."
  (set (map str (vec (js/Object.values agent-loop/stream-event-types)))))

(defn- cell [files]
  (if (seq files) (str/join ", " (sort files)) "-"))

(defn- event-rows [scanned]
  (let [{:keys [emitters listeners]} scanned]
    (for [n (sort (map str (vec events/core-event-types)))]
      (let [emits (get emitters n)
            emits (if (contains? stream-mapped n)
                    (conj (or emits #{}) "src/agent/loop.cljs (stream-event-types)")
                    emits)]
        (str "| `" n "` | " (cell emits) " | " (cell (get listeners n)) " |")))))

(defn- pi-rows [scanned]
  (for [n (sort (map str (vec events/pi-compat-event-types)))]
    (str "| `" n "` | " (cell (get (:listeners scanned) n)) " |")))

(def ^:private non-producers
  "extensions.cljs DEFINES these APIs and extension_scope.cljs forwards them;
   this file only names them in prose. None is a call site."
  #{"src/agent/extensions.cljs" "src/agent/extension_scope.cljs"
    "src/agent/dev/event_map.cljs"})

(defn registry-storage
  "For each `registerX` defined in extensions.cljs, the token its body writes to:
   a `(:some-key agent)` atom, or an `alias/fn` on a registry module.

   Derived from the definition rather than declared in a table here — a
   hand-written map of API → storage is the same never-validated static data this
   file exists to replace."
  []
  (let [lines (vec (.split (fs/readFileSync (path/join src-root "agent" "extensions.cljs") "utf8") "\n"))]
    (reduce
     (fn [acc i]
       (if-let [api (second (re-find #"^\s+:(register[A-Z][a-zA-Z]*)" (nth lines i)))]
         ;; Take the lines up to the NEXT API entry rather than balancing parens.
         ;; The stop pattern is an entry (`:someName (fn` or a key on its own
         ;; line), not any `:keyword` — nested map literals are full of those,
         ;; and stopping at the first one cut registerProvider's body 26 lines
         ;; short of the registry it writes to.
         (let [body (loop [j (inc i) acc []]
                      (if (or (>= j (count lines))
                              (>= (- j i) 80)
                              (re-find #"^\s+:[a-zA-Z]+\s*(?:\(fn\s*\[|$)" (nth lines j)))
                        (str/join "\n" acc)
                        (recur (inc j) (conj acc (nth lines j)))))
               atom-key (second (re-find #"\(:([a-z][a-z-]*) agent\)" body))
               mod-fn   (second (re-find #"\(([a-z][a-z-]*)/[a-z-]+ " body))]
           (assoc acc api (or (when atom-key (str ":" atom-key))
                              (when mod-fn (str mod-fn "/")))))
         acc))
     {}
     (range (count lines)))))

(defn declared-registries
  "The `registerX` keys `extensions.cljs` actually defines. Rows come from HERE,
   not from the call sites, so an API that nothing calls still gets a row with a
   `-` — which is the whole point: a registry nobody fills is as interesting as
   one nobody reads."
  []
  (let [src (fs/readFileSync (path/join src-root "agent" "extensions.cljs") "utf8")]
    (set (map second (re-seq #":(register[A-Z][a-zA-Z]*)\s" src)))))

(defn- alias->path
  "`status-segments/` → `agent/ui/status_line_segments`, by reading
   extensions.cljs's own require list. A module-backed registry's readers are the
   files that use that namespace, not the alias, which exists only here."
  [token]
  (when (str/ends-with? (str token) "/")
    (let [a   (str/replace (str token) "/" "")
          src (fs/readFileSync (path/join src-root "agent" "extensions.cljs") "utf8")
          ns' (second (re-find (js/RegExp. (str "\\[([a-z0-9.-]+) :as " a "\\]")) src))]
      (when ns'
        (-> (str ns')
            (str/replace "." "/")
            (str/replace "-" "_"))))))

(defn- readers-of
  "Files that mention `token` — the registry's storage — other than the API
   definition itself. This is the column the deletions turned on: a registry with
   producers and no reader is `registerToolRenderer`, which had twelve callers
   and nothing that ever looked at what they wrote."
  [token]
  (when token
    (let [needle (or (alias->path token) token)]
      (->> (cljs-files src-root)
           (filter (fn [f]
                     (and (not (contains? non-producers (rel f)))
                          (or (str/includes? (fs/readFileSync f "utf8") needle)
                              ;; Namespaces are written dotted in a require and
                              ;; slashed on disk; match either.
                              (str/includes? (fs/readFileSync f "utf8")
                                             (str/replace (str/replace needle "/" ".") "_" "-"))))))
           (map rel)
           (remove (fn [r] (str/starts-with? r "src/agent/extensions/")))
           (into #{})))))

(defn- registry-rows [scanned]
  (let [calls   (:registry-calls scanned)
        storage (registry-storage)]
    (for [n (sort (declared-registries))]
      (let [token (get storage n)]
        (str "| `" n "` | " (cell (remove non-producers (get calls n)))
             " | " (if token (str "`" token "` in " (cell (readers-of token))) "-")
             " |")))))

(defn render-event-map
  "The full document. Pure apart from reading src."
  []
  (let [scanned (scan)]
    (str/join
     "\n"
     (concat
      ["<!-- Generated by scripts/gen-event-map.mjs — do not edit by hand."
       "     Run `bun run gen:event-map` to regenerate. -->"
       ""
       "# Event and registry map"
       ""
       "Who produces each extension point, and who consumes it. A `-` is a fact, not"
       "a failure: an event with no listener is a hook nobody has taken up yet. What"
       "this file exists to prevent is the column being *unknown* — five registries"
       "with no consumer survived several releases because nothing showed the pair."
       ""
       "## Core events"
       ""
       "`test/event_emitter_lint.test.cljs` fails when the emitters column is empty."
       "The listeners column is reported only."
       ""
       "| Event | Emitted in | Listened in |"
       "| --- | --- | --- |"]
      (event-rows scanned)
      [""
       "## pi-compat events"
       ""
       "Declared for pi API parity; nyma itself never emits these. A listener here is"
       "waiting for something only a pi-style host produces."
       ""
       "| Event | Listened in |"
       "| --- | --- |"]
      (pi-rows scanned)
      [""
       "## Extension-API registries"
       ""
       "Producers of each `registerX`, and who reads the registry back. Producers"
       "with no reader is the shape that got `registerBlockRenderer`,"
       "`registerToolRenderer`, `registerCompletionProvider`,"
       "`registerMentionProvider` and `registerContextProvider` deleted on"
       "2026-09-11. The reader column lists files outside `src/agent/extensions/`"
       "that mention the registry's storage, since an extension touching it is"
       "another producer, not a consumer."
       ""
       "| API | Called from | Read back in |"
       "| --- | --- | --- |"]
      (registry-rows scanned)
      [""]))))
