(ns command-surface-lint.test
  "A command's advertised subcommands must match the ones it implements.

   `/spec` implements 14 subcommands and advertised 13 — `install-skill` existed
   and was undiscoverable. The reverse is worse: advertising a subcommand that
   was renamed or removed sends users at something that answers `Usage:`.

   This is the cheap half of a bug class whose expensive half is `/spec next`,
   which was advertised, existed, and did nothing. A lint can catch absence; it
   cannot catch inertness — that needs an integration test per command that
   claims to cause work."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def ^:private ext-root
  (path/resolve (js/process.cwd) "src" "agent" "extensions"))

(defn advertised
  "Subcommand names from a `Usage: /cmd [a|b|c]` string, or nil."
  [source]
  (when-let [m (re-find #"Usage: /[a-z-]+ \[([a-z|<>\-\s]+)\]" source)]
    (->> (str/split (nth m 1) #"\|")
         (map str/trim)
         (remove str/blank?)
         (remove (fn [t] (.startsWith t "<")))
         set)))

(defn implemented
  "Subcommand names from the dispatcher's `case` arms.

   Anchored on `(case sub` and restricted to arms at exactly the indent of the
   FIRST arm. A looser `quoted string on its own line` match also swept up
   `advance`/`continue`/`hold` — the phase loop's own `(case (:action d))`
   further down the same file — and reported them as unadvertised subcommands."
  [source]
  (let [lines (vec (str/split-lines source))
        start (first (keep-indexed (fn [i l] (when (re-find #"\(case\s+sub\b" l) i)) lines))]
    (if-not start
      #{}
      (let [case-ind (count (second (re-find #"^(\s*)" (nth lines start))))
            ;; Stop at the first non-blank line indented no deeper than the
            ;; `(case sub` line itself — that is where the form closes. Without
            ;; this the scan ran on into sibling `case` forms further down the
            ;; file and reported their arms as subcommands.
            after (->> (subvec lines (inc start))
                       (take-while (fn [l]
                                     (or (str/blank? l)
                                         (> (count (second (re-find #"^(\s*)" l))) case-ind))))
                       vec)
            arm   (fn [l] (re-find #"^(\s+)\"([a-z][a-z-]*)\"\s*$" l))
            ind   (some (fn [l] (when-let [m (arm l)] (count (nth m 1)))) after)]
        (if-not ind
          #{}
          (->> after
               (keep (fn [l] (when-let [m (arm l)]
                               (when (= ind (count (nth m 1))) (nth m 2)))))
               set))))))

(defn- sources []
  (->> (vec (fs/readdirSync ext-root #js {:withFileTypes true}))
       (filter (fn [e] (.isDirectory e)))
       (keep (fn [e]
               (let [f (path/join ext-root (.-name e) "index.cljs")]
                 (when (fs/existsSync f)
                   {:name (.-name e) :source (fs/readFileSync f "utf8")}))))
       vec))

;;; ─── README ↔ registered commands ────────────────────────────

(defn registered-commands
  "Names passed to `(.registerCommand api \"…\"` anywhere under the extension."
  [source]
  (set (map second (re-seq #"\(\.registerCommand api \"([a-z0-9-]+)\"" source))))

(defn undocumented-commands
  "Registered commands the README never mentions as `/name` (bare or under the
   `ns__` prefix). Pure, for the self-test."
  [registered readme]
  (vec (sort (remove (fn [c]
                       (or (.includes readme (str "/" c))
                           (re-find (js/RegExp. (str "/[a-z0-9-]+__" c "\\b")) readme)))
                     registered))))

(defn- ext-dirs []
  (->> (vec (fs/readdirSync ext-root #js {:withFileTypes true}))
       (filter (fn [e] (.isDirectory e)))
       (map (fn [e] {:name (.-name e) :dir (path/join ext-root (.-name e))}))
       vec))

(defn- all-source [dir]
  (->> (vec (fs/readdirSync dir #js {:recursive true}))
       (filter (fn [f] (.endsWith (str f) ".cljs")))
       (map (fn [f] (fs/readFileSync (path/join dir (str f)) "utf8")))
       (str/join "\n")))

(describe "every registered command is in the extension's README"
          (fn []
            (it "detects an undocumented command"
                (fn []
                  (-> (expect (undocumented-commands #{"stats" "roles"} "| `/x__stats` | …"))
                      (.toEqual #js ["roles"]))
                  (-> (expect (undocumented-commands #{"stats"} "run /stats to see"))
                      (.toEqual #js []))))

            (it "READMEs mention every command the code registers"
                (fn []
                  ;; model_roles registered /plan, /planmode and /mode and
                  ;; documented none of them, while documenting three commands
                  ;; other extensions own. A README is the only place a user
                  ;; learns a command exists without reading source.
                  (let [bad (->> (ext-dirs)
                                 (keep (fn [{:keys [name dir]}]
                                         (let [readme (path/join dir "README.md")]
                                           (when (fs/existsSync readme)
                                             (let [u (undocumented-commands
                                                      (registered-commands (all-source dir))
                                                      (fs/readFileSync readme "utf8"))]
                                               (when (seq u)
                                                 (str name " registers but README omits: " (str/join ", " u))))))))
                                 vec)]
                    (-> (expect (str/join "; " bad)) (.toBe "")))))))

(describe "advertised subcommands match implemented ones" (fn []

                                                            (it "finds extension sources"
                                                                (fn []
        ;; Guard the guard — a bad path would pass every assertion vacuously.
                                                                  (-> (expect (> (count (sources)) 10)) (.toBe true))))

                                                            (it "parses a usage string"
                                                                (fn []
        ;; Detector self-test: a broken regex would report a clean repo forever.
                                                                  (-> (expect (contains? (advertised "Usage: /spec [list|new|end]") "new")) (.toBe true))
                                                                  (-> (expect (advertised "no usage string here")) (.toBeNil))
        ;; …and `implemented` must find arms only under `(case sub`.
                                                                  (let [src (str "  (case sub\n"
                                                                                 "              \"list\"\n"
                                                                                 "              (do 1)\n"
                                                                                 "              \"new\"\n"
                                                                                 "              (do 2))\n"
                                                                                 "  (case other\n"
                                                                                 "              \"nope\"\n"
                                                                                 "              3)")]
                                                                    (-> (expect (contains? (implemented src) "list")) (.toBe true))
                                                                    (-> (expect (contains? (implemented src) "nope")) (.toBe false)))))

                                                            (it "advertises everything it implements, and implements everything it advertises"
                                                                (fn []
                                                                  (let [report (fn [{:keys [name source]}]
                                                                                 (let [adv  (advertised source)
                                                                                       impl (implemented source)]
                         ;; Only report when BOTH were found; an extension with
                         ;; no `case` arms means the matcher missed, not that
                         ;; the extension is broken.
                                                                                   (when (and adv (seq impl))
                                                                                     (let [missing      (vec (remove impl adv))
                                                                                           unadvertised (vec (remove adv impl))]
                                                                                       (when (or (seq missing) (seq unadvertised))
                                                                                         (str name
                                                                                              (when (seq missing)
                                                                                                (str " advertises but does not implement: "
                                                                                                     (str/join "," (sort missing))))
                                                                                              (when (seq unadvertised)
                                                                                                (str " implements but does not advertise: "
                                                                                                     (str/join "," (sort unadvertised))))))))))
                                                                        bad (vec (keep report (sources)))]
                                                                    (-> (expect (str/join "; " bad)) (.toBe "")))))))
