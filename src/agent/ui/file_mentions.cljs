(ns agent.ui.file-mentions
  "`@path` in the editor: the autocomplete listing behind it and the expansion
   that runs on submit.

   Expansion is deliberately textual — the mention stays in the user's text
   and the file is appended after it in a `<file path=…>` block — so sessions
   keep `:content` an opaque string and the transcript shows exactly what the
   model saw."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.tools :refer [try-binary]]
            [agent.utils.git-files :as gf]
            [agent.ui.fuzzy-scorer :refer [fuzzy-filter]]
            [clojure.string :as str]))

;;; ─── Mentions in submitted text ──────────────────────────────────────────

;; `@` at start or after whitespace, then a path-ish run. Emails fail the
;; "after whitespace" test; `@scope/pkg` matches but resolves to nothing and
;; is left alone by expand-mentions.
(def ^:private mention-re (js/RegExp. "(?:^|\\s)@([A-Za-z0-9_./~-]+)" "g"))

(defn find-mentions
  "Distinct `@` tokens in `text`, in order, trailing punctuation stripped."
  [text]
  (let [re     (js/RegExp. (.-source mention-re) "g")
        tokens (loop [acc []]
                 (if-let [m (.exec re (str text))]
                   (recur (conj acc (str/replace (aget m 1) #"[.,;:!?]+$" "")))
                   acc))]
    (vec (distinct (remove str/blank? tokens)))))

(def ^:private max-file-bytes (* 200 1024))

(defn- resolve-mention [cwd token]
  (let [expanded (cond (= token "~") (os/homedir)
                       (.startsWith token "~/") (path/join (os/homedir) (subs token 2))
                       :else token)]
    (path/resolve cwd expanded)))

(defn- display-path [cwd abs]
  (let [rel (path/relative cwd abs)]
    (if (or (str/blank? rel) (.startsWith rel "..")) abs rel)))

(defn- dir-listing [abs]
  (->> (fs/readdirSync abs #js {:withFileTypes true})
       (map (fn [e] (if (.isDirectory e) (str (.-name e) "/") (.-name e))))
       sort
       (str/join "\n")))

(defn- render-block [cwd abs]
  (let [st   (fs/statSync abs)
        disp (display-path cwd abs)]
    (cond
      (.isDirectory st)
      {:kind :dir :block (str "<dir path=\"" disp "\">\n" (dir-listing abs) "\n</dir>")}

      (not (.isFile st))
      nil

      (> (.-size st) max-file-bytes)
      {:kind :skipped
       :block (str "<file path=\"" disp "\" skipped=\"" (js/Math.round (/ (.-size st) 1024)) " KB\"/>")}

      :else
      {:kind :file
       :block (str "<file path=\"" disp "\">\n" (fs/readFileSync abs "utf-8") "\n</file>")})))

(defn expand-mentions
  "Append a block per resolvable mention. Returns {:text :files :skipped};
   :files and :skipped are display paths. Unresolvable tokens are left as-is."
  [text cwd]
  (let [cwd (or cwd (js/process.cwd))]
    (reduce (fn [acc token]
              (let [abs (resolve-mention cwd token)
                    r   (try (when (fs/existsSync abs) (render-block cwd abs))
                             (catch :default _ nil))]
                (if-not r
                  acc
                  (-> acc
                      (update :text str "\n\n" (:block r))
                      (update (if (= :skipped (:kind r)) :skipped :files)
                              conj (display-path cwd abs))))))
            {:text (str text) :files [] :skipped []}
            (find-mentions text))))

;;; ─── Autocomplete listing ────────────────────────────────────────────────

(defn detect-fd
  "Path of `fd` (or Debian's `fdfind`) on PATH, else nil."
  []
  (cond (try-binary "fd") (js/Bun.which "fd")
        (try-binary "fdfind") (js/Bun.which "fdfind")
        :else nil))

(def ^:private skip-dirs #{"node_modules" ".git" "dist" ".nyma" ".claude"})
(def ^:private walk-cap 2000)

(defn- walk-into! [root dir depth out]
  (when (and (< (count @out) walk-cap) (< depth 10))
    (doseq [e (try (fs/readdirSync dir #js {:withFileTypes true})
                   (catch :default _ []))]
      (let [n (.-name e)]
        (when (and (< (count @out) walk-cap)
                   (not (.startsWith n "."))
                   (not (contains? skip-dirs n)))
          (if (.isDirectory e)
            (walk-into! root (path/join dir n) (inc depth) out)
            (swap! out conj (path/relative root (path/join dir n)))))))))

(defn- walk-files
  "Relative file paths under `root`, hidden and build dirs skipped, capped."
  [root]
  (let [out (atom [])]
    (walk-into! root root 0 out)
    @out))

;; One listing per cwd, good for 5 s: a keystroke burst re-scores the same
;; vector instead of spawning git for every character typed.
(def ^:private index-cache (atom {}))
(def ^:private cache-ttl-ms 5000)

(defn file-index [cwd]
  (let [now (js/Date.now)
        {:keys [at files]} (get @index-cache cwd)]
    (if (and files (< (- now at) cache-ttl-ms))
      files
      (let [files (if (gf/in-git-repo? cwd) (gf/list-files cwd) (walk-files cwd))]
        (swap! index-cache assoc cwd {:at now :files files})
        files))))

(def ^:private max-suggestions 50)

(defn fallback-suggestions
  "Items in the shape the upstream fd path returns:
   {value \"@rel/path\" label basename description \"rel/path\"}."
  [cwd query]
  (->> (fuzzy-filter (file-index cwd) (str query) identity)
       (take max-suggestions)
       (mapv (fn [p] #js {:value (str "@" p)
                          :label (path/basename p)
                          :description p}))))

(defn install-fallback!
  "Without fd the upstream `getFuzzyFileSuggestions` returns [] unconditionally.
   `getSuggestions` calls it through `this`, so an own property on the instance
   shadows the prototype method — no subclassing needed."
  [provider cwd]
  (aset provider "getFuzzyFileSuggestions"
        (fn [query _opts] (js/Promise.resolve (fallback-suggestions cwd query))))
  provider)

(defn configure-provider!
  "Wire `provider` for `@file` completion: fd when present, else the nyma
   enumerator. Returns the provider."
  [provider cwd fd-path]
  (if fd-path
    (do (aset provider "fdPath" fd-path) provider)
    (install-fallback! provider cwd)))
