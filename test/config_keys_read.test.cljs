(ns config-keys-read-test
  "Every key an extension declares in `default-config` must be read somewhere
   in that extension.

   A setting that does nothing is worse than one that is rejected: the user
   writes it, the file parses, nothing happens, and there is no feedback
   anywhere. This branch already fixed three instances — `headroom` passed
   `algorithms` and `disableCcr` to a package that has never had them, and
   `bash_suite` read only the project settings file so a global shell policy
   was inert.

   **Scoped per extension on purpose.** `docs/roadmap.md` records a repo-wide
   reads-without-writers lint as DROPPED after two prototypes produced 11 then
   9 false positives. Scanning one extension's own directory for its own keys
   is the narrow version: 122 keys across nine extensions, six suspects, and
   all six verified genuinely unread. Zero false positives at the time of
   writing.

   The keys are read as DATA from the compiled `default-config`, not parsed out
   of the source — the maps are nested, and a regex that tried to find their
   boundaries silently matched nothing at all on the first attempt, which would
   have made this file a lint that checked zero keys."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.extensions.small-model.shared :as small-model]
            [agent.extensions.token-suite.shared :as token-suite]
            [agent.extensions.memory.shared :as memory]
            [agent.extensions.verify-gate.shared :as verify-gate]
            [agent.extensions.bash-suite.shared :as bash-suite]
            [agent.extensions.headroom.shared :as headroom]
            [agent.extensions.openwiki.shared :as openwiki]
            [agent.extensions.budget.shared :as budget]))

(def ^:private extensions
  [["small_model" small-model/default-config]
   ["token_suite" token-suite/default-config]
   ["memory"      memory/default-config]
   ["verify_gate" verify-gate/default-config]
   ["bash_suite"  bash-suite/default-config]
   ["headroom"    headroom/default-config]
   ["openwiki"    openwiki/default-config]
   ["budget"      budget/default-config]])

(def ^:private known-inert
  "Keys declared and read nowhere. Each was checked by hand.

   These are NOT excused — they are recorded so that no NEW inert key can
   appear while these wait for a decision. Wiring a setting up is a feature;
   this file's job is to stop the list growing."
  #{;; Both are in small_model's README as user-facing configuration, so the
    ;; README currently documents settings that do nothing.
    "small_model/no-progress-streak"
    "small_model/adaptive-temperature"
    ;; smart_compaction reads its thresholds from elsewhere; these two are
    ;; leftovers in the defaults map.
    "token_suite/background-threshold"
    "token_suite/full-threshold"
    ;; bash_suite output caps that never reached the truncation path.
    "bash_suite/absolute-max-bytes"
    "bash_suite/preserve-stderr"})

(defn- all-keys
  "Every key name in a (possibly nested) config map."
  [m]
  (letfn [(walk [x acc]
            (if (map? x)
              (reduce (fn [a k] (walk (get x k) (conj a (name k)))) acc (keys x))
              acc))]
    (vec (distinct (walk m [])))))

(defn- cljs-sources [dir]
  (->> (fs/readdirSync dir #js {:withFileTypes true})
       (mapcat (fn [e]
                 (let [p (path/join dir (.-name e))]
                   (cond (.isDirectory e)               (cljs-sources p)
                         (.endsWith (.-name e) ".cljs")  [p]
                         :else                           []))))
       vec))

(defn unread-keys
  "Keys of `cfg` that appear only once in `ext`'s sources — the declaration."
  [ext cfg]
  (let [dir  (path/join (js/process.cwd) "src" "agent" "extensions" ext)
        body (->> (cljs-sources dir)
                  (map (fn [f] (fs/readFileSync f "utf8")))
                  (str/join "\n"))]
    (->> (all-keys cfg)
         (filter (fn [k] (<= (dec (count (str/split body (str ":" k)))) 1)))
         (map (fn [k] (str ext "/" k)))
         vec)))

(describe "extension config keys are read"
  (fn []
    (it "finds a believable number of keys to check"
      (fn []
        ;; Guard the guard. The first attempt at this parsed the nested maps
        ;; with a regex, matched nothing, and would have reported a clean repo
        ;; forever while checking zero keys.
        (let [n (reduce + (map (fn [[_ c]] (count (all-keys c))) extensions))]
          (-> (expect n) (.toBeGreaterThan 80)))))

    (it "every extension's config map was actually loaded"
      (fn []
        (doseq [[ext cfg] extensions]
          (-> (expect #js [ext (map? cfg)]) (.toEqual #js [ext true]))
          (-> (expect #js [ext (pos? (count (all-keys cfg)))]) (.toEqual #js [ext true])))))

    (it "no key is declared and never read, beyond the recorded ones"
      (fn []
        (let [unread (vec (sort (mapcat (fn [[ext cfg]] (unread-keys ext cfg)) extensions)))
              new'   (vec (remove known-inert unread))]
          ;; Named, so a failure says which setting does nothing.
          (-> (expect (clj->js new')) (.toEqual (clj->js []))))))

    (it "every recorded entry is still inert"
      (fn []
        ;; The other direction: once a setting is wired up, its entry here must
        ;; go, or the list becomes folklore.
        (let [unread (set (mapcat (fn [[ext cfg]] (unread-keys ext cfg)) extensions))
              stale  (vec (sort (remove unread known-inert)))]
          (-> (expect (clj->js stale)) (.toEqual (clj->js []))))))))
