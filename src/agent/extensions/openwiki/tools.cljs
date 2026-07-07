(ns agent.extensions.openwiki.tools
  "The two helper tools the model calls at the end of an init/update run.
   Registered as `save_metadata` / `ensure_agents_md`; the scoped API prefixes
   them to `openwiki__…` (the names the model actually sees — the prompts
   reference those). Plain #js JSON schemas (nyma convention — no typebox)."
  (:require ["node:fs" :as fs]
            [clojure.string :as str]
            [agent.extensions.openwiki.metadata :as md]))

(defn agents-section
  "The AGENTS.md/CLAUDE.md reference block. Section list is derived from the
   configured `:sections` so a custom taxonomy is described accurately."
  [dir sections]
  (str "## OpenWiki\n\n"
       "This repository has AI-maintained documentation in `" dir "/`.\n\n"
       "Start here: [" dir "/quickstart.md](" dir "/quickstart.md)\n\n"
       "It covers " (str/join ", " sections) ". When working here, read the "
       "quickstart first, then follow its links to the relevant notes before "
       "exploring source.\n"))

(defn ensure-section
  "Pure: given existing file content (nil if the file is missing) and the
   section text, return the content to write, or nil if the section is already
   present (idempotent)."
  [content section]
  (cond
    (nil? content)                    section
    (.includes content "## OpenWiki") nil
    :else                             (str content "\n\n" section)))

(defn- update-agents-file!
  "Append/create one agent-instruction file. When `create?` is false and the
   file is missing, do nothing. Returns a human-readable status string."
  [file section create?]
  (let [exists? (fs/existsSync file)]
    (cond
      (and (not exists?) (not create?)) (str "· " file " absent, skipped")
      :else
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
      #js {:description "Save OpenWiki metadata after generating/updating docs. Call this once at the end of an init or update run."
           :parameters
           #js {:type "object"
                :required #js ["command" "model"]
                :properties
                #js {:command #js {:type "string" :enum #js ["init" "update"]
                                   :description "The operation performed"}
                     :model   #js {:type "string" :description "Model used for generation"}}}
           :execute
           (fn [args]
             (let [r (md/save-metadata dir (str (.-command args)) (str (.-model args)))]
               (if (:skipped r)
                 (str "No content change since last run — metadata left untouched (no-op guard).")
                 (str "✅ Saved " dir "/.last-update.json\n"
                      (js/JSON.stringify (:metadata r) nil 2)))))}]

     ["ensure_agents_md"
      #js {:description "Add an OpenWiki reference section to AGENTS.md / CLAUDE.md so coding agents discover the wiki. Idempotent."
           :parameters #js {:type "object" :properties #js {}}
           :execute
           (fn [_args]
             (let [section (agents-section dir sections)]
               (str (update-agents-file! "AGENTS.md" section true) "\n"
                    (update-agents-file! "CLAUDE.md" section false))))}]]))
