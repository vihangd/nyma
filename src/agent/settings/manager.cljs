(ns agent.settings.manager
  (:require ["node:path" :as path]
            ["node:fs" :as fs]
            ["node:os" :as os]))

(def defaults
  {:model          "claude-sonnet-4-20250514"
   :provider       "anthropic"
   :thinking       "off"
   :compaction     {:enabled true :threshold 0.85}
   :retry          {:enabled true :max-retries 3}
   :tools          ["read" "write" "edit" "bash"]
   :steering-mode  "one-at-a-time"
   :follow-up-mode "one-at-a-time"
   :transport      "auto"})

(defn- load-json [file-path]
  (when (fs/existsSync file-path)
    (-> (fs/readFileSync file-path "utf8")
        (js/JSON.parse))))

(defn- save-json [file-path data]
  (let [dir (path/dirname file-path)]
    (when-not (fs/existsSync dir)
      (fs/mkdirSync dir #js {:recursive true}))
    (fs/writeFileSync file-path (js/JSON.stringify (clj->js data) nil 2))))

(defn create-settings-manager
  "Two-scope settings: global + project. Project overrides global."
  []
  (let [global-settings  (load-json (path/join (os/homedir) ".agent" "settings.json"))
        project-settings (load-json ".agent/settings.json")
        overrides        (atom {})]

    {:get (fn []
            (merge defaults
                   (or global-settings {})
                   (or project-settings {})
                   @overrides))

     :set-override (fn [k v]
                     (swap! overrides assoc k v))

     :apply-overrides (fn [m]
                        (swap! overrides merge m))

     :save-global (fn [settings]
                    (save-json (path/join (os/homedir) ".agent" "settings.json")
                      (merge global-settings settings)))

     :save-project (fn [settings]
                     (save-json ".agent/settings.json"
                       (merge project-settings settings)))}))
