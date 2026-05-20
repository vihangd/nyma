(ns gateway.config
  "Gateway configuration loading and validation.

   Config is a JSON file (default: gateway.json in CWD) with env-var interpolation.
   Any string value matching `${VAR_NAME}` is replaced with process.env.VAR_NAME.

   All config keys are kebab-case — they are read with plain Clojure keyword
   lookups (:some-key cfg), not rewritten from camelCase.

   ─── Schema ───────────────────────────────────────────────────────────────

   {
     \"agent\": {
       \"model\":                \"claude-sonnet-4-6\",   // required
       \"system-prompt\":        \"You are ...\",
       \"modes\":                [\"gateway\"],           // tool mode filter
       \"exclude-capabilities\": [\"execution\", \"shell\"]
     },
     \"gateway\": {
       \"streaming\": {
         \"policy\":      \"debounce\",  // immediate|debounce|throttle|batch-on-end
         \"delay-ms\":    400,           // for debounce
         \"interval-ms\": 500            // for throttle
       },
       \"session\": {
         \"policy\":        \"persistent\",   // ephemeral|idle-evict|persistent|capped
         \"idle-evict-ms\": 3600000
       },
       \"dedup\": {
         \"cache-ttl-ms\": 300000
       },
       \"auth\": {
         \"allowed-user-ids\": [\"U12345\"],   // optional allow-list
         \"allowed-channels\": [\"C12345\"]    // optional allow-list
       },
       \"projects\": {                       // optional — enables multi-project routing
         \"vyom\":  { \"root\": \"~/projects/pers/vyom\",
                    \"agents\": [\"claude\", \"gemini\"] },
         \"nyma\":  { \"root\": \"~/projects/pers/nyma\",
                    \"agents\": [\"claude\"] }
       },
       \"default-agent\": \"claude\"           // fallback when run_in_project omits :agent
     },
     \"channels\": [
       {
         \"type\":   \"telegram\",
         \"name\":   \"my-telegram\",
         \"config\": { \"token\": \"${TELEGRAM_BOT_TOKEN}\" }
       }
     ]
   }

   See docs/gateway.md for the full reference, including per-channel config keys
   and the behaviour of each streaming/session policy."
  (:require ["node:fs/promises" :as fsp]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]))

(defn- interpolate-str
  "Replace `${VAR}` tokens in a string with process.env values.
   Unresolved variables are left as-is (not blanked)."
  [s]
  (if (string? s)
    (.replace s
              (js/RegExp. "\\$\\{([^}]+)\\}" "g")
              (fn [match var-name]
                (or (aget (.-env js/process) var-name) match)))
    s))

(defn interpolate-env
  "Recursively walk a parsed JS object/array and interpolate env vars
   in all string leaf values. Mutates the structure in place; also returns it."
  [v]
  (cond
    (string? v) (interpolate-str v)
    (array? v)
    (do
      (doseq [i (range (.-length v))]
        (aset v i (interpolate-env (aget v i))))
      v)
    (and (some? v) (not (number? v)) (not (boolean? v)) (not (fn? v)))
    (do
      (doseq [k (js/Object.keys v)]
        (aset v k (interpolate-env (aget v k))))
      v)
    :else v))

(defn ^:async load-config
  "Load and parse a gateway config file. Returns a Clojure map with keyword keys.

   `file-path` defaults to gateway.json in the current working directory.
   Throws if the file cannot be read or parsed."
  [& [file-path]]
  (let [p      (or file-path (path/join (js/process.cwd) "gateway.json"))
        raw    (js-await (.readFile fsp p "utf8"))
        parsed (js/JSON.parse raw)
        _      (interpolate-env parsed)]
    (js->clj parsed :keywordize-keys true)))

(defn validate-config
  "Check that a loaded config map has the required fields.
   Returns {:valid? true} or {:valid? false :errors [str]}."
  [cfg]
  (let [errors (atom [])]
    (when-not (get-in cfg [:agent :model])
      (swap! errors conj "agent.model is required"))
    (when-not (seq (:channels cfg))
      (swap! errors conj "channels must be a non-empty array"))
    (doseq [[i ch] (map-indexed vector (or (:channels cfg) []))]
      (when-not (:type ch)
        (swap! errors conj (str "channels[" i "].type is required")))
      (when-not (:name ch)
        (swap! errors conj (str "channels[" i "].name is required"))))
    ;; Channel names must be unique
    (let [names (mapv :name (or (:channels cfg) []))
          dupes (filter (fn [n] (> (count (filter #(= % n) names)) 1)) names)]
      (when (seq dupes)
        (swap! errors conj (str "Duplicate channel names: " (str/join ", " dupes)))))
    ;; Projects, if present, must declare a root + non-empty agent allow-list.
    ;; Without these, chat-driven project selection becomes a remote-shell exploit.
    (when-let [projects (get-in cfg [:gateway :projects])]
      (doseq [[pname pcfg] projects]
        (when-not (:root pcfg)
          (swap! errors conj (str "gateway.projects." (strip-kw-colon pname) ".root is required")))
        (when-not (seq (:agents pcfg))
          (swap! errors conj
                 (str "gateway.projects." (strip-kw-colon pname)
                      ".agents must be a non-empty array")))))
    (if (empty? @errors)
      {:valid? true}
      {:valid? false :errors @errors})))

(defn- strip-kw-colon
  "Squint compiles keywords to strings like `\":vyom\"`. When we need the bare
   name (\"vyom\") for user-facing strings or as a string key, strip the colon."
  [k]
  (let [s (str k)]
    (if (str/starts-with? s ":") (subs s 1) s)))

(defn expand-home
  "Expand a leading `~` in a path to the user's home directory."
  [p]
  (cond
    (not (string? p)) p
    (= p "~")         (os/homedir)
    (str/starts-with? p "~/") (path/join (os/homedir) (subs p 2))
    :else p))

(defn projects-from-config
  "Return the resolved project allow-list as
   `{project-name {:root <abs-path>, :agents #{...}}}`.

   Roots are expanded (`~/...`) and `path.resolve`-d at load time so the
   gateway never has to perform path operations on chat-supplied strings —
   the project name is a dictionary lookup, the resolved root is read from
   the value. Returns `nil` if no projects section is configured."
  [cfg]
  (when-let [projects (get-in cfg [:gateway :projects])]
    (reduce-kv
     (fn [acc pname pcfg]
       (assoc acc (strip-kw-colon pname)
              {:root   (path/resolve (expand-home (:root pcfg)))
               :agents (set (mapv str (:agents pcfg)))}))
     {} projects)))

(defn default-agent-from-config
  "The fallback agent key for run_in_project when :agent is omitted by the
   router LLM. Defaults to `\"claude\"` if not configured."
  [cfg]
  (or (get-in cfg [:gateway :default-agent]) "claude"))

(defn streaming-policy-from-config
  "Extract streaming policy keyword or opts map from the gateway config."
  [cfg]
  (let [sc (get-in cfg [:gateway :streaming])]
    (if (nil? sc)
      :debounce
      ;; Squint: keywords ARE strings, so :debounce == "debounce". No (keyword) wrapper.
      (let [policy (or (:policy sc) "debounce")]
        (case policy
          "debounce" (if (:delay-ms sc)
                       {:type :debounce :delay-ms (:delay-ms sc)}
                       :debounce)
          "throttle" (if (:interval-ms sc)
                       {:type :throttle :interval-ms (:interval-ms sc)}
                       :throttle)
          policy)))))

(defn session-pool-opts-from-config
  "Extract session pool creation options from the gateway config."
  [cfg]
  ;; Squint: keywords ARE strings, so (or str-val :default) works without (keyword).
  {:default-policy (or (get-in cfg [:gateway :session :policy]) :persistent)
   :idle-evict-ms  (or (get-in cfg [:gateway :session :idle-evict-ms]) 3600000)
   :dedup-ttl-ms   (or (get-in cfg [:gateway :dedup :cache-ttl-ms]) 300000)})

(defn agent-opts-from-config
  "Extract agent creation options from the gateway config.
   Returns a map suitable for passing to agent.modes.sdk/create-session."
  [cfg]
  ;; Squint: keywords ARE strings, so config "execution" == :execution.
  ;; Just build sets directly from the string arrays.
  (let [ac (:agent cfg)]
    (cond-> {:model (:model ac)}
      (:system-prompt ac)
      (assoc :system-prompt (:system-prompt ac))
      (:modes ac)
      (assoc :modes (set (:modes ac)))
      (:exclude-capabilities ac)
      (assoc :exclude-capabilities (set (:exclude-capabilities ac)))
      (:require-capabilities ac)
      (assoc :require-capabilities (set (:require-capabilities ac))))))
