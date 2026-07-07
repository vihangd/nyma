(ns agent.extensions.openwiki.shared
  "Shared config + constants for the OpenWiki extension.

   Config is settings-driven (.nyma/settings.json#openwiki), everything
   overridable — no hardcoded aliases. Defaults match the upstream
   langchain-ai/openwiki layout."
  (:require [clojure.string :as str]))

(def default-config
  {:enabled  false                      ; opt-in (also via --ext-openwiki flag)
   :dir      "openwiki"                 ; output directory (repo-relative)
   :sections ["architecture" "workflows" "domain" "operations" "testing"]
   :model    nil})                      ; nil = use the active model

(defn config
  "Merge user settings#openwiki over defaults. `settings` is the JS object
   returned by api.getSettings (or nil)."
  [settings]
  (let [ow (when settings (aget settings "openwiki"))]
    (merge default-config
           (when ow
             (cond-> {}
               (some? (aget ow "enabled")) (assoc :enabled (aget ow "enabled"))
               (aget ow "dir")             (assoc :dir (aget ow "dir"))
               (aget ow "model")           (assoc :model (aget ow "model"))
               (aget ow "sections")        (assoc :sections (vec (aget ow "sections"))))))))

(defn metadata-file [dir] (str dir "/.last-update.json"))

(defn under-dir?
  "True when `path` is inside the wiki `dir` (for progress status)."
  [dir path]
  (and (string? path) (.includes path (str dir "/"))))
