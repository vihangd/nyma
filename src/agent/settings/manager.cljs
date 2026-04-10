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
   :steering-mode  "one-at-a-time"
   :follow-up-mode "one-at-a-time"
   :transport              "auto"
   :tool-display           "collapsed"
   :tool-display-max-lines 500
   :status-line            {:preset "default"
                            :left-segments nil
                            :right-segments nil
                            :separator nil}
   :roles {:default {:provider "anthropic" :model "claude-sonnet-4-20250514"}
           :fast    {:provider "anthropic" :model "claude-haiku-4-20250901"}
           :deep    {:provider "anthropic" :model "claude-opus-4-20250514"}
           :plan    {:provider "anthropic" :model "claude-opus-4-20250514"
                     :allowed-tools ["read" "glob" "grep" "ls" "think" "web_search" "web_fetch"]
                     :permissions {"write" "deny" "edit" "deny" "bash" "deny"}}
           :commit  {:provider "anthropic" :model "claude-sonnet-4-20250514"
                     :allowed-tools ["read" "bash" "glob" "grep" "edit" "write"]}}})

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
  "Two-scope settings: global + project. Project overrides global.
   Supports :reload to re-read files from disk without restarting."
  []
  (let [global-path      (path/join (os/homedir) ".nyma" "settings.json")
        project-path     ".nyma/settings.json"
        global-settings  (atom (load-json global-path))
        project-settings (atom (load-json project-path))
        overrides        (atom {})]

    {:get (fn []
            (merge defaults
                   (or @global-settings {})
                   (or @project-settings {})
                   @overrides))

     :set-override (fn [k v]
                     (swap! overrides assoc k v))

     :apply-overrides (fn [m]
                        (swap! overrides merge m))

     :reload (fn []
               (reset! global-settings (load-json global-path))
               (reset! project-settings (load-json project-path)))

     :save-global (fn [settings]
                    (save-json global-path
                      (merge @global-settings settings)))

     :save-project (fn [settings]
                     (save-json project-path
                       (merge @project-settings settings)))}))
