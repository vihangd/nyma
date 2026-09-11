(ns builtin-extensions.test
  "The generated builtin registry must match the source tree.

   An extension missing from it does not exist in a compiled binary — and says
   nothing about it, because an empty directory scan is not an error. That is how
   `bun run bundle` shipped a nyma with 2 of 40 extensions and 3 of 20 providers
   for as long as it did."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.builtin-extensions :refer [registry]]))

(def ^:private src-root
  (path/join (js/process.cwd) "src" "agent" "extensions"))

(defn source-dirs
  "Directories under src/agent/extensions with an index.cljs — the definition of
   a builtin, read from the tree rather than from a list maintained by hand."
  []
  (->> (vec (fs/readdirSync src-root #js {:withFileTypes true}))
       (filter (fn [e] (.isDirectory e)))
       (map (fn [e] (.-name e)))
       (filter (fn [n] (fs/existsSync (path/join src-root n "index.cljs"))))
       sort
       vec))

(defn- manifest-namespace
  "What the directory's extension.json calls itself, else its kebab name."
  [dir]
  (let [p (path/join src-root dir "extension.json")]
    (or (when (fs/existsSync p)
          (.-namespace (js/JSON.parse (fs/readFileSync p "utf8"))))
        (str/replace dir "_" "-"))))

(describe "builtin registry"
          (fn []
            (it "covers every extension in the source tree"
                (fn []
                  (let [in-registry (set (map :namespace registry))
                        expected    (set (map manifest-namespace (source-dirs)))]
                    ;; Named rather than counted, so the failure says which one.
                    (-> (expect (vec (sort (remove in-registry expected)))) (.toEqual #js []))
                    (-> (expect (vec (sort (remove expected in-registry)))) (.toEqual #js [])))))

            (it "is not empty and has no duplicates"
                (fn []
                  ;; Guard the guard: two empty sets agree with each other.
                  (-> (expect (count registry)) (.toBeGreaterThan 30))
                  (-> (expect (count (set (map :namespace registry))))
                      (.toBe (count registry)))))

            (it "every entry carries a module with a default export"
                (fn []
                  ;; This is what the loader calls instead of importing a file.
                  (doseq [{:keys [namespace module]} registry]
                    (-> (expect (str namespace ": " (some? module))) (.toBe (str namespace ": true")))
                    (-> (expect (str namespace ": " (fn? (.-default module))))
                        (.toBe (str namespace ": true"))))))

            (it "manifests are objects the loader can read fields off"
                (fn []
                  (doseq [{:keys [namespace manifest]} registry]
                    (when manifest
                      (-> (expect (str namespace ": " (object? manifest)))
                          (.toBe (str namespace ": true")))))))))
