(ns agent.cli
  (:require ["node:util" :refer [parseArgs]]
            [agent.core :refer [create-agent]]
            [agent.loop :refer [run]]
            [agent.resources.loader :refer [discover]]
            [agent.sessions.manager :refer [create-session-manager]]
            [agent.settings.manager :refer [create-settings-manager]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-loader :refer [discover-and-load deactivate-all]]
            [agent.commands.builtins :refer [register-builtins]]
            [agent.keybindings :refer [load-keybindings apply-keybindings]]
            [agent.providers.oauth :as oauth]
            ["./modes/interactive.jsx" :as interactive]
            [agent.modes.print :as print-mode]
            [agent.modes.rpc :as rpc]))

(defn- resolve-model-via-registry
  "Resolve a model through the agent's provider registry.
   Falls back to the provider name as a direct model ID.
   Handles OAuth credential refresh for providers that need it."
  [provider-registry values merged]
  (let [model-id  (or (:model values) (:model merged) "claude-sonnet-4-20250514")
        provider  (or (:provider values) (:provider merged) "anthropic")
        p-config  ((:get provider-registry) provider)]
    ;; Auto-refresh OAuth credentials if needed
    (when-let [oauth-cfg (:oauth p-config)]
      (let [creds (oauth/load-credentials provider)]
        (when (and creds (oauth/needs-refresh? creds))
          (try
            ((:refresh-token oauth-cfg)
              #js {"access"     (:access creds)
                   "refresh"    (:refresh creds)
                   "expires-at" (:expires-at creds)})
            (catch :default e
              (js/console.warn (str "OAuth refresh failed for " provider ": " (.-message e))))))))
    ((:resolve provider-registry) provider model-id)))

(defn- resolve-tools
  "Returns an explicit tool restriction list, or nil if all builtins should be active.
   Only restricts when --tools CLI flag or settings 'tools' key is set."
  [values merged]
  (or (when-let [t (:tools values)]
        (-> t (.split ",") vec))
      (:tools merged)))

(defn resolve-ext-flags
  "Resolve --ext-* CLI flags against registered extension flags.
   Scans process.argv for --ext-flagname=value or --ext-flagname (boolean),
   matches against registered flags, coerces by type, and sets :value."
  [agent]
  (let [argv  (.slice js/process.argv 2)
        flags @(:flags agent)]
    (doseq [arg argv]
      (when (.startsWith arg "--ext-")
        (let [rest-arg  (.slice arg 6)
              eq-idx    (.indexOf rest-arg "=")
              flag-name (if (>= eq-idx 0) (.slice rest-arg 0 eq-idx) rest-arg)
              raw-value (when (>= eq-idx 0) (.slice rest-arg (inc eq-idx)))]
          (doseq [[full-name flag-config] flags]
            (let [short-name (last (.split full-name "/"))]
              (when (= short-name flag-name)
                (let [coerced (case (:type flag-config)
                                "boolean" (if (nil? raw-value) true (not= raw-value "false"))
                                "number"  (js/Number raw-value)
                                "string"  (or raw-value "")
                                raw-value)]
                  (swap! (:flags agent) assoc-in [full-name :value] coerced))))))))))

(defn- temp-session-path []
  (str "/tmp/nyma-session-" (js/Date.now) ".jsonl"))

(defn- resolve-session [values]
  (let [session-path (or (:session values)
                         (when-not (:no-session values)
                           (str (.. js/process -env -HOME) "/.nyma/sessions/default.jsonl")))]
    (create-session-manager session-path)))

(defn ^:async main []
  (let [{:keys [values positionals]}
        (parseArgs
          #js {:args    (.slice js/process.argv 2)
               :options #js {:provider     #js {:type "string"}
                             :model        #js {:type "string" :short "m"}
                             :mode         #js {:type "string"}
                             :print        #js {:type "boolean" :short "p"}
                             :continue     #js {:type "boolean" :short "c"}
                             :resume       #js {:type "boolean" :short "r"}
                             :tools        #js {:type "string"}
                             :thinking     #js {:type "string"}
                             :session      #js {:type "string"}
                             :fork         #js {:type "string"}
                             :no-session   #js {:type "boolean"}}
               :allowPositionals true})

        settings  (create-settings-manager)
        merged    ((:get settings))
        resources (assoc (js-await (discover)) :settings settings)
        session   (resolve-session values)

        active-tools (resolve-tools values merged)
        ;; Create agent first, then resolve model via its provider registry
        agent (create-agent
                {:model         nil
                 :system-prompt ((:build-system-prompt resources))
                 :max-steps     20})
        model (try
                (resolve-model-via-registry (:provider-registry agent) values merged)
                (catch :default e
                  (js/console.warn (str "[nyma] " (.-message e)))
                  nil))
        _     (when model (set! (.-model (:config agent)) model))
        api (create-extension-api agent)]

    ;; Filter active tools if --tools flag was used
    (when active-tools
      ((:set-active (:tool-registry agent)) (set active-tools)))

    ;; Attach session to agent so extensions can access it
    (reset! (:session agent) session)

    ;; Attach extension API to agent so UI can access it
    (set! (.-extension-api agent) api)

    ;; Load all extensions (both .cljs and .ts/.js)
    (let [loaded-extensions (js-await (discover-and-load (:extension-dirs resources) api))
          extensions-atom   (atom loaded-extensions)]

      ;; Resolve --ext-* CLI flags against registered extension flags
      (resolve-ext-flags agent)

      ;; Register built-in commands with reload support
      (register-builtins agent session resources extensions-atom resolve-ext-flags)

      ;; Load user keybindings
      (let [bindings (load-keybindings)]
        (when (seq bindings)
          (apply-keybindings (:shortcuts agent) (:commands agent) bindings)))

      ;; Deactivate extensions on process exit
      (.on js/process "exit" (fn [] (deactivate-all @extensions-atom)))

      ;; Dispatch to mode
      (let [mode (or (:mode values)
                     (when (:print values) "print")
                     "interactive")]
        (case mode
          "interactive" (js-await (interactive/start agent session resources))
          "print"       (js-await (print-mode/start agent (first positionals)))
          "json"        (js-await (print-mode/start-json agent (first positionals)))
          "rpc"         (js-await (rpc/start agent)))))))

(when (.-main js/import.meta) (main))
