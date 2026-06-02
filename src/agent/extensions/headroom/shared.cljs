(ns agent.extensions.headroom.shared
  (:require ["node:fs" :as fs]
            ["node:path" :as path]))

;; ── Stats ────────────────────────────────────────────────────────

(def suite-stats
  (atom {:calls            0
         :tokens-saved     0
         :compression-ratio 1.0
         :skipped          0
         :errors           0}))

;; ── Default config ───────────────────────────────────────────────

(def default-config
  {:enabled               false
   :proxy-url             "http://localhost:8787"
   :compression-threshold 0.5
   :min-tokens-to-compress 8000
   :algorithms            ["SmartCrusher" "CodeCompressor" "Kompress"]
   :disable-ccr           true})

;; ── Settings loader ──────────────────────────────────────────────

(defn load-config []
  (let [load-file
        (fn [p]
          (when (fs/existsSync p)
            (try
              (let [parsed (js/JSON.parse (fs/readFileSync p "utf8"))
                    h      (.-headroom parsed)]
                (when h (js->clj h :keywordize-keys true)))
              (catch :default _ nil))))
        global-path  (path/join (.. js/process -env -HOME) ".nyma" "settings.json")
        project-path (path/join (js/process.cwd) ".nyma" "settings.json")]
    (merge default-config
           (or (load-file global-path) {})
           (or (load-file project-path) {}))))

;; ── Proxy health check ────────────────────────────────────────────

(defn ^:async probe-proxy
  "Ping the proxy health endpoint. Returns true if reachable."
  [url]
  (try
    (let [ctrl (js/AbortController.)
          _    (js/setTimeout #(.abort ctrl) 3000)
          resp (js-await (js/fetch (str url "/health")
                                   #js {:method "GET"
                                        :signal (.-signal ctrl)}))]
      (.-ok resp))
    (catch :default _ false)))
