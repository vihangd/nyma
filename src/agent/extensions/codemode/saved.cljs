(ns agent.extensions.codemode.saved
  "Saved scripts: a script that ran `ok`, kept by the user with
   `/codemode save <name>`, callable from later scripts as `(saved/<name> {…})`.

   Curated by the user, never by the model: SkillsBench (2026) found
   self-generated skills gave no average gain and made 16 of 84 tasks worse,
   while CRAFT and ToolMaker gained by verifying a tool before keeping it. A
   saved script is one that already succeeded, and saving is a command.

   On disk: `.nyma/scripts/<name>.cljs` (project, loaded only in a trusted
   folder) or `~/.nyma/scripts/` (global; project wins on a name clash). A
   header of `;; key: value` lines, then the body. Inside the body `args` is
   the map the caller passed.

   A call is expanded into the script before analysis, so the approval lists
   the tools a saved script will call, and only those are bound."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.utils.home :as home]
            [agent.trust :as trust]
            [agent.extensions.codemode.analyze :as an]))

(defn dirs
  "[[scope dir] …], global first so a project script of the same name wins."
  []
  (cond-> [[:global (path/join (home/dir) ".nyma" "scripts")]]
    (trust/trusted?) (conj [:project (path/join (js/process.cwd) ".nyma" "scripts")])))

(def name-re #"^[a-z][a-z0-9-]{0,40}$")

(defn parse
  "{:name :description :tools :returns :body} from a file's text. Pure."
  [nm text]
  (let [lines  (str/split-lines (str text))
        header (take-while #(re-find #"^;;\s*[a-z-]+:" %) lines)
        kv     (into {} (map (fn [l] (let [[_ k v] (re-find #"^;;\s*([a-z-]+):\s*(.*)$" l)] [k v])) header))]
    {:name        nm
     :description (get kv "description" "")
     :tools       (vec (remove str/blank? (str/split (get kv "tools" "") #",\s*")))
     :returns     (get kv "returns")
     :body        (str/join "\n" (drop (count header) lines))}))

(defn render
  "File text for a saved script. Pure."
  [{:keys [description tools returns body]}]
  (str ";; description: " (str/replace (str description) #"\n" " ") "\n"
       ";; tools: " (str/join ", " tools) "\n"
       (when (seq returns) (str ";; returns: " returns "\n"))
       (str/trim (str body)) "\n"))

(defn load-all
  "Every saved script, by name; a project script shadows a global one."
  []
  (reduce (fn [acc [scope dir]]
            (if-not (fs/existsSync dir)
              acc
              (reduce (fn [acc f]
                        (let [nm (str/replace f #"\.cljs$" "")]
                          (if (and (.endsWith f ".cljs") (re-find name-re nm))
                            (assoc acc nm (assoc (parse nm (fs/readFileSync (path/join dir f) "utf8"))
                                                 :scope scope :path (path/join dir f)))
                            acc)))
                      acc (vec (fs/readdirSync dir)))))
          {} (dirs)))

(defn save!
  "Write `entry` as `<name>.cljs` in the scope's dir; returns the path."
  [scope nm entry]
  (let [dir (path/join (if (= scope :global) (home/dir) (js/process.cwd)) ".nyma" "scripts")
        f   (path/join dir (str nm ".cljs"))]
    (fs/mkdirSync dir #js {:recursive true})
    (fs/writeFileSync f (render entry))
    f))

(defn broken
  "Tools a saved script names that are no longer registered."
  [entry tool-names]
  (let [have (set tool-names)]
    (vec (remove #(contains? have %) (:tools entry)))))

(defn expand
  "The script with the saved scripts it calls bound in front:
   `(def saved {\"name\" (fn [args] body)})`, so `(saved/name {…})` compiles
   to `saved.name(…)`. Pure.
   → {:source :used [names] :missing [names] :nested [names]}
   `:nested` are used scripts that themselves call `saved/`, which is refused:
   one level keeps what a call can reach visible in the approval."
  [code scripts]
  (let [by-key  (into {} (map (fn [[nm e]] [(an/munge-part nm) e]) scripts))
        refs    (->> (an/references code) (filter #(= "saved" (first %))) (map second) distinct)
        used    (keep #(get by-key %) refs)
        missing (vec (remove #(contains? by-key %) refs))
        nested  (vec (keep (fn [e] (when (some #(= "saved" (first %)) (an/references (:body e))) (:name e))) used))]
    ;; only when used: the prefix shifts squint's error line numbers
    {:source  (if (seq used)
                (str "(def saved {"
                     (str/join " " (map (fn [e] (str (js/JSON.stringify (an/munge-part (:name e)))
                                                     " (fn [args]\n" (:body e) "\n)"))
                                        used))
                     "})\n" code)
                code)
     :used    (mapv :name used)
     :missing (mapv #(.replaceAll % "_" "-") missing)
     :nested  nested}))
