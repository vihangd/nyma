(ns agent.sessions.listing
  (:require ["node:fs" :as fs]
            ["node:path" :as path]))

(defn list-sessions
  "Scan a directory for .jsonl session files.
   Returns [{:path :name :file :modified :entry-count}] sorted by modified desc.
   Attempts to extract session name from entries with role 'session-name'."
  [dir]
  (if-not (and dir (fs/existsSync dir))
    []
    (let [files (->> (fs/readdirSync dir)
                     (filter #(.endsWith % ".jsonl")))]
      (->> files
           (map (fn [file]
                  (try
                    (let [full    (path/join dir file)
                          stat    (fs/statSync full)
                          content (.readFileSync fs full "utf8")
                          lines   (->> (.split (.trim content) "\n")
                                       (filter seq))
                          ;; Try to find session name from entries
                          name-entry (->> lines
                                          (map (fn [line]
                                                 (try (js/JSON.parse line)
                                                      (catch :default _ nil))))
                                          (filter some?)
                                          (filter #(= (.-role %) "session-name"))
                                          last)
                          name    (or (when name-entry (.-content name-entry))
                                      (.replace file ".jsonl" ""))]
                      {:path         full
                       :name         name
                       :file         file
                       :modified     (.-mtimeMs stat)
                       :entry-count  (count lines)})
                    (catch :default _ nil))))
           (filter some?)
           (sort-by :modified >)
           vec))))
