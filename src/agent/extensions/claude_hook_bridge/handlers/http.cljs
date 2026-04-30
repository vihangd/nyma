(ns agent.extensions.claude-hook-bridge.handlers.http
  "HTTP handler — POSTs the JSON event to a URL and treats the
   response body as the hook's stdout.

   Per CC spec:
     - Default timeout: 30 seconds (vs 600 for command).
     - 2xx empty body            → success, no decision.
     - 2xx + plain text body     → success, text becomes additionalContext.
     - 2xx + JSON body           → parsed as a structured decision response.
     - Non-2xx                   → non-blocking error.
     - Connection / timeout fail → non-blocking error.

   Auth header substitution:
     - Headers may reference `$VAR_NAME`. Only env-var names listed in
       the spec's `allowedEnvVars` are substituted; others are left
       as literal text. This is a deliberate restriction so a malicious
       project hooks.json can't exfiltrate arbitrary environment vars.

   We translate the HTTP-shaped result into the same
   `{:exit-code :stdout :stderr ...}` envelope the command handler
   uses, so response/parse-one handles both transparently."
  (:require [clojure.string :as str]))

(def default-timeout-ms 30000)

(defn- substitute-env
  "Replace `$VAR` occurrences in `s` with the value of `process.env.VAR`,
   but ONLY for var names in `allowed-set`. Leaves others unchanged.
   Supports both `$VAR` and `${VAR}` forms."
  [s allowed-set]
  (let [s' (str s)
        ;; ${VAR}
        s' (.replace s' (js/RegExp. "\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}" "g")
                     (fn [_match name]
                       (if (contains? allowed-set name)
                         (or (aget js/process.env name) "")
                         (str "${" name "}"))))
        ;; $VAR (greedy)
        s' (.replace s' (js/RegExp. "\\$([A-Za-z_][A-Za-z0-9_]*)" "g")
                     (fn [_match name]
                       (if (contains? allowed-set name)
                         (or (aget js/process.env name) "")
                         (str "$" name))))]
    s'))

(defn- build-headers
  "Produce a JS headers object from spec.headers + spec.allowedEnvVars."
  [headers-spec allowed-vec]
  (let [headers   (or headers-spec #js {})
        allowed   (set (or (when allowed-vec (vec (js/Array.from allowed-vec))) []))
        out       #js {}]
    (doseq [k (js-keys headers)]
      (aset out k (substitute-env (aget headers k) allowed)))
    (aset out "Content-Type" "application/json")
    out))

(defn ^:async run-http
  "Args:
     :url            string (required)
     :headers        JS object (optional)
     :allowed-env    JS array of env var names (optional)
     :timeout-ms     int — clamp; default 30s
     :stdin-json     CLJS map / JS object — POST body
     :abort-signal   AbortSignal — fired on agent abort

   Returns the same envelope as the command handler:
     {:exit-code int :stdout string :stderr string ...}"
  [{:keys [url headers allowed-env timeout-ms stdin-json abort-signal]}]
  (let [hdrs   (build-headers headers allowed-env)
        body   (cond
                 (string? stdin-json) stdin-json
                 (some? stdin-json)   (js/JSON.stringify (clj->js stdin-json))
                 :else                "{}")
        timer-controller (js/AbortController.)
        timer  (js/setTimeout
                (fn [] (.abort timer-controller))
                (or timeout-ms default-timeout-ms))
        ;; If the agent's signal fires, cancel the request too.
        _      (when abort-signal
                 (.addEventListener abort-signal "abort"
                                    (fn [] (.abort timer-controller))))]
    (try
      (let [resp (js-await
                  (js/fetch url
                            #js {:method  "POST"
                                 :headers hdrs
                                 :body    body
                                 :signal  (.-signal timer-controller)}))
            text (js-await (.text resp))
            ok?  (and (>= (.-status resp) 200) (< (.-status resp) 300))]
        (js/clearTimeout timer)
        {:exit-code   (if ok? 0 1)
         :stdout      (or text "")
         :stderr      (when-not ok? (str "HTTP " (.-status resp)))
         :timed-out?  false
         :aborted?    false
         :error       nil})
      (catch :default e
        (js/clearTimeout timer)
        (let [aborted? (= (.-name e) "AbortError")]
          {:exit-code   1
           :stdout      ""
           :stderr      (str "fetch error: " (or (.-message e) (str e)))
           :timed-out?  aborted?
           :aborted?    aborted?
           :error       e})))))
