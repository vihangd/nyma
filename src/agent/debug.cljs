(ns agent.debug
  "Lightweight debug logger. Writes to ~/.nyma/debug.log when
   NYMA_DEBUG_USERMSG=1 is set. No-op otherwise."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(defn- log-path []
  (path/join (os/homedir) ".nyma" "debug.log"))

(defn- ensure-dir []
  (let [dir (path/join (os/homedir) ".nyma")]
    (when-not (fs/existsSync dir)
      (fs/mkdirSync dir #js {:recursive true}))))

(defn dbg
  "Append a debug line to ~/.nyma/debug.log.
   Only active when NYMA_DEBUG_USERMSG env var is set."
  [& args]
  (when (.-NYMA_DEBUG_USERMSG (.-env js/process))
    (ensure-dir)
    (let [ts   (.toISOString (js/Date.))
          line (str ts " " (apply str (interpose " " (map str args))) "\n")]
      (fs/appendFileSync (log-path) line "utf8"))))
