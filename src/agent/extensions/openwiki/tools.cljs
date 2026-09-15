(ns agent.extensions.openwiki.tools
  "The two helper tools the model calls at the end of an init/update run.
   Registered as `save_metadata` / `ensure_agents_md`; the scoped API prefixes
   them to `openwiki__…` (the names the model actually sees — the prompts
   reference those). Plain #js JSON schemas (nyma convention — no typebox)."
  (:require ["node:fs" :as fs]
            [clojure.string :as str]
            [agent.extensions.openwiki.shared :as shared]
            [agent.extensions.openwiki.metadata :as md]))

(def ^:private heading "## OpenWiki")

(defn agents-section
  "The AGENTS.md/CLAUDE.md reference block. Section list is derived from the
   configured `:sections` so a custom taxonomy is described accurately."
  [dir sections]
  (str heading "\n\n"
       "This repository has AI-maintained documentation in `" dir "/`.\n\n"
       "Start here: [" dir "/index.md](" dir "/index.md)\n\n"
       "It covers " (str/join ", " sections) ". When working here, read the "
       "index first, then follow its links to the relevant notes before "
       "exploring source.\n"))

(defn ensure-section
  "Pure: given existing file content (nil if the file is missing) and the
   section text, return the content to write, or nil when the file already
   carries an identical block (idempotent).

   A stale block is REPLACED, not left alone: matching on the heading only
   meant that changing `openwiki.dir` left AGENTS.md pointing at the old path
   forever, which is worse than no pointer at all."
  [content section]
  (cond
    (nil? content) section

    (not (.includes content heading))
    (str content "\n\n" section)

    :else
    (let [start (.indexOf content heading)
          ;; The block runs to the next heading of ANY level, or EOF. Not just
          ;; h1/h2: the generated block has no sub-headings, so a `### …` after
          ;; it is the user's, and terminating only on h1/h2 spliced it out
          ;; along with everything under it.
          next-h (.search (.slice content (+ start (count heading)))
                          #"\n#{1,6} ")
          end    (if (neg? next-h) (count content) (+ start (count heading) next-h 1))
          old    (.slice content start end)
          out    (str (.slice content 0 start) section (.slice content end))]
      (when-not (= (str/trim old) (str/trim section)) out))))

(defn- update-agents-file!
  "Append/create/refresh one agent-instruction file. When `create?` is false and
   the file is missing, do nothing. Returns a human-readable status string."
  [file section create?]
  (let [exists? (fs/existsSync file)]
    (if (and (not exists?) (not create?))
      (str "· " file " absent, skipped")
      (let [content (when exists? (fs/readFileSync file "utf8"))
            out     (ensure-section content section)]
        (if (nil? out)
          (str "· " file " already references OpenWiki")
          (do (fs/writeFileSync file out)
              (str (if exists? "✓ updated " "✓ created ") file)))))))

(defn make-tools
  "Returns [[name td] ...] for registration. `config` supplies the wiki dir
   and section taxonomy."
  [config]
  (let [dir      (:dir config)
        sections (:sections config)]
    [["save_metadata"
      #js {:safety #js {:destructive? true :capabilities #js ["filesystem" "write"]}
           :description "Save OpenWiki metadata after generating/updating docs. Call this once at the end of an init or update run. It also reports any OKF conformance violations in the bundle — fix them and call it again."
           :parameters
           #js {:type "object"
                :required #js ["command" "model"]
                :properties
                #js {:command #js {:type "string" :enum #js ["init" "update"]
                                   :description "The operation performed"}
                     :model   #js {:type "string" :description "Model used for generation"}}}
           :execute
           (fn [args]
             (let [r      (md/save-metadata dir (str (.-command args)) (str (.-model args)) sections)
                   report (md/okf-report dir)]
               (str "✅ Saved " (shared/metadata-file dir)
                    (when (:unchanged r) "\n(No content change since the last run — gitHead still advanced.)")
                    "\n" (js/JSON.stringify (:metadata r) nil 2)
                    (when report (str "\n\n" report)))))}]

     ["ensure_agents_md"
      #js {:safety #js {:destructive? true :capabilities #js ["filesystem" "write"]}
           :description "Add an OpenWiki reference section to AGENTS.md / CLAUDE.md so coding agents discover the wiki. Idempotent; refreshes the block if the wiki directory changed."
           :parameters #js {:type "object" :properties #js {}}
           :execute
           (fn [_args]
             (let [section (agents-section dir sections)]
               (str (update-agents-file! "AGENTS.md" section true) "\n"
                    (update-agents-file! "CLAUDE.md" section false))))}]]))
