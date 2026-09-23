(ns agent.extensions.memory
  "Persistent memory — a small, agent-maintained MEMORY.md injected each run.

   Read layer: `<cwd>/.nyma/memory/MEMORY.md` (project) + `~/.nyma/memory/MEMORY.md`
   (global), size-capped and injected via before_agent_start. Write layer: the
   `memory_write` / `memory_forget` tools let the agent curate it (selective, not
   store-everything — the SOTA guard against memory rot).

   Always on; low-risk (no-ops when no MEMORY.md exists)."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.extensions.memory.shared :as shared]))

(defn- project-file [dir] (path/join (js/process.cwd) ".nyma" dir "MEMORY.md"))
(defn- global-file [dir]
  (path/join (or (.. js/process -env -HOME) ".") ".nyma" dir "MEMORY.md"))

(defn- read-file [f]
  (when (fs/existsSync f)
    (try (fs/readFileSync f "utf8") (catch :default _ nil))))

(defn- read-memory
  "Combined, size-capped memory block for injection, or nil when empty."
  [config]
  (let [dir (:dir config)
        gf  (global-file dir)
        pf  (project-file dir)
        ;; The project path is built from the cwd, so running nyma from the
        ;; home directory makes these the same file and the block was joined to
        ;; itself: the same notes injected twice into the cacheable prefix on
        ;; every request, with `cap-lines` then truncating at half the content
        ;; it was meant to carry.
        same? (let [resolve* (fn [x] (try (fs/realpathSync x)
                                          (catch :default _e (path/resolve x))))]
                (= (resolve* gf) (resolve* pf)))
        g   (read-file gf)
        p   (when-not same? (read-file pf))
        combined (->> [(when (seq (str/trim (or g ""))) g)
                       (when (seq (str/trim (or p ""))) p)]
                      (filter some?)
                      (str/join "\n\n"))]
    (when (seq (str/trim combined))
      (shared/cap-lines combined (:max-lines config)))))

(defn- write-memory! [dir mutate]
  (let [f     (project-file dir)
        pairs (shared/parse-memory (read-file f))
        pairs' (mutate pairs)]
    (fs/mkdirSync (path/dirname f) #js {:recursive true})
    (fs/writeFileSync f (shared/render-memory pairs'))
    f))

(defn ^:export default [api]
  (let [config   (shared/config (.settings api))
        dir      (:dir config)

        on-before-start
        (fn [_data _ctx]
          (when-let [block (read-memory config)]
            #js {:system-prompt-additions #js [(str "# Memory (your persistent notes)\n\n" block)]}))]

    (.on api "before_agent_start" on-before-start)

    (.registerTool api "memory_write"
                   #js {;; Rewrites MEMORY.md: category "write" for the permission gate.
                        :safety #js {:destructive? true :capabilities #js ["filesystem" "write"]}
                        :description "Save or update a durable fact in your persistent MEMORY.md (survives across sessions). Use for stable project conventions, decisions, and hard-won insights — NOT transient state. Keep it terse; a bloated memory hurts you."
                        :parameters
                        #js {:type "object"
                             :required #js ["key" "value"]
                             :properties
                             #js {:key   #js {:type "string" :description "Short unique label (e.g. \"build\", \"db-schema\", \"gotcha-auth\")"}
                                  :value #js {:type "string" :description "The fact/note (markdown ok). Replaces any existing note under this key."}}}
                        :execute
                        (fn [args]
                          (let [k (str (.-key args)) v (str (.-value args))]
                            (write-memory! dir #(shared/upsert % k v))
                            (str "Remembered \"" k "\".")))})

    (.registerTool api "memory_forget"
                   #js {:safety #js {:destructive? true :capabilities #js ["filesystem" "write"]}
                        :description "Remove a fact from your persistent MEMORY.md by key. Use to prune stale/wrong notes (memory hygiene)."
                        :parameters
                        #js {:type "object"
                             :required #js ["key"]
                             :properties #js {:key #js {:type "string" :description "The key to remove"}}}
                        :execute
                        (fn [args]
                          (let [k (str (.-key args))]
                            (write-memory! dir #(shared/remove-key % k))
                            (str "Forgot \"" k "\".")))})

    (fn []
      (.unregisterTool api "memory_write")
      (.unregisterTool api "memory_forget"))))
