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
       }
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
    (if (empty? @errors)
      {:valid? true}
      {:valid? false :errors @errors})))

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
