(ns agent.extension-state
  "Persistent per-extension state stored in .nyma/ext-state/{namespace}.json."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.debug :as d]))

(defn quarantine-path
  "Where a malformed store is moved aside to. Timestamped so repeated failures
   do not overwrite the first (and most useful) copy. Pure; exposed for tests."
  [fpath now]
  (str fpath ".corrupt-" (.replace (.toISOString now) (js/RegExp. "[:.]" "g") "-")))

(defn create-state-api
  "Create a state API for an extension namespace.
   State is persisted as JSON in .nyma/ext-state/{ns-str}.json.
   Returns a JS object with get/set/delete/keys/clear methods.

   Two properties this did not have:

   MISSING vs MALFORMED. `load` was a bare `(catch :default _ #js {})` with no
   existsSync gate, so a first run and a corrupt file were indistinguishable —
   and since `set` is load → assoc → write, the very next write TRUNCATED the
   file it had failed to read. A store holding four keys became one key, with no
   message. `.nyma/` is gitignored, so there was no recovery. A malformed file is
   now moved aside once, named in a warning, and the store continues from empty.

   ATOMIC WRITES. `writeFileSync` straight onto the live path means a crash or a
   full disk mid-write leaves exactly the truncated JSON this is defending
   against. Write to a temp file in the same directory, then rename — rename is
   atomic within a filesystem, so a reader sees either the old file or the new
   one. No such helper existed in the repo (35 writeFileSync sites, none atomic).

   Loud, never destructive: nothing here throws, so a broken store cannot stop
   an extension activating."
  [ns-str]
  (let [dir        (path/join (js/process.cwd) ".nyma" "ext-state")
        fpath      (path/join dir (str ns-str ".json"))
        quarantine!
        (fn [err]
          ;; Move the bytes aside BEFORE anything can write over them.
          (let [dest (quarantine-path fpath (js/Date.))]
            (try
              (fs/renameSync fpath dest)
              (d/warn "ext-state"
                      (str ns-str ": state file was unreadable ("
                           (or (.-message err) (str err))
                           ") — moved to " dest " and starting empty."))
              (catch :default e
                ;; Could not even move it aside. Nothing further to do but say
                ;; so — the next write will overwrite, and the user needs to
                ;; know which file to rescue by hand.
                (d/warn "ext-state"
                        (str ns-str ": state file is unreadable and could not be"
                             " moved aside (" (or (.-message e) (str e))
                             "); " fpath " may be overwritten.")))))
          #js {})
        load
        (fn []
          (if-not (fs/existsSync fpath)
            #js {}                       ; genuinely first run — silent, correct
            (let [raw (try (fs/readFileSync fpath "utf8")
                           (catch :default e
                             (d/warn "ext-state"
                                     (str ns-str ": cannot read " fpath ": "
                                          (or (.-message e) (str e))))
                             nil))]
              (if (nil? raw)
                #js {}
                (try
                  (let [parsed (js/JSON.parse raw)]
                    ;; A JSON scalar or array parses fine but is not a store;
                    ;; treating it as one would aset onto the wrong shape.
                    (if (and parsed (object? parsed) (not (array? parsed)))
                      parsed
                      (quarantine! (js/Error. "not a JSON object"))))
                  (catch :default e (quarantine! e)))))))
        save
        (fn [data]
          (try
            (fs/mkdirSync dir #js {:recursive true})
            (let [tmp (str fpath ".tmp-" (.now js/Date))]
              (fs/writeFileSync tmp (js/JSON.stringify data nil 2))
              (fs/renameSync tmp fpath))
            (catch :default e
              (d/warn "ext-state"
                      (str ns-str ": could not persist state: "
                           (or (.-message e) (str e)))))))]
    #js {:get    (fn [k] (aget (load) k))
         :set    (fn [k v]
                   (let [d (load)]
                     (aset d k v)
                     (save d)))
         :delete (fn [k]
                   (let [d (load)]
                     (js-delete d k)
                     (save d)))
         :keys   (fn [] (js/Object.keys (load)))
         :clear  (fn [] (save #js {}))}))
