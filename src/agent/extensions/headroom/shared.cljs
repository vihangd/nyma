(ns agent.extensions.headroom.shared
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.utils.js-interop :as ji]))

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

(defn malformed-settings-message
  "The error shown for a settings file whose `headroom` section will not
   load. Names the section AND the file: with neither, a typo'd settings
   file is indistinguishable from never having enabled the extension.
   Pure, so the test needs no filesystem and no UI."
  [file-path err-msg]
  (str "Malformed `headroom` settings section in " file-path " — " err-msg
       ". Falling back to defaults (headroom stays off)."))

(defn read-config
  "Load the merged headroom config, and the parse failures met on the way.
   Returns {:config {...} :errors [{:path p :message m}]}.

   This reads the two settings files itself rather than going through
   `(.settings api \"headroom\")` on purpose: the section is documented in
   camelCase (proxyUrl, compressionThreshold) and needs `kebab-keys`, and
   the merged-settings path would have already swallowed the parse error
   this function exists to report."
  []
  (let [errors (atom [])
        load-file
        (fn [p]
          (when (fs/existsSync p)
            (try
              (let [parsed (js/JSON.parse (fs/readFileSync p "utf8"))
                    h      (.-headroom parsed)]
                ;; `js->clj` does not exist in squint: this threw, the catch
                ;; below ate it, and load-config returned the defaults every
                ;; time — so `"enabled": true` never took effect and the
                ;; extension could not be turned on by anyone.
                ;;
                ;; kebab-keys because the settings file is documented in
                ;; camelCase (proxyUrl, compressionThreshold) while
                ;; default-config keys are kebab-case, so even once the throw
                ;; was fixed every key but `enabled` would still be ignored.
                (when h (ji/kebab-keys h)))
              ;; The catch used to eat this and hand back the defaults, so a
              ;; broken settings file and a deliberate `"enabled": false` were
              ;; the same silence. Record it; the caller says so out loud.
              (catch :default e
                (swap! errors conj {:path p :message (or (.-message e) (str e))})
                nil))))
        global-path  (path/join (.. js/process -env -HOME) ".nyma" "settings.json")
        project-path (path/join (js/process.cwd) ".nyma" "settings.json")
        ;; From the home directory these are the same file. The merged config
        ;; was unaffected — merging a map with itself — but the error list is
        ;; per read, so one broken settings file was reported twice.
        same?        (let [resolve* (fn [x] (try (fs/realpathSync x)
                                                 (catch :default _e (path/resolve x))))]
                       (= (resolve* global-path) (resolve* project-path)))]
    {:config (merge default-config
                    (or (load-file global-path) {})
                    (or (when-not same? (load-file project-path)) {}))
     :errors @errors}))

(defn load-config []
  (:config (read-config)))

(defn disabled-hint
  "What `/headroom-stats` answers when the extension is off. Names the exact
   setting and the proxy the user would need — a bare \"unknown command\" left
   them with nothing to act on. Pure."
  [proxy-url]
  (str "headroom is off — set headroom.enabled: true in .nyma/settings.json "
       "(proxy at " (or proxy-url (:proxy-url default-config))
       ") or start with --ext-headroom"))

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
