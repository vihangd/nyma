(ns agent.cli
  (:require ["node:util" :refer [parseArgs]]
            [agent.core :refer [create-agent]]
            [agent.loop :refer [run]]
            [agent.resources.loader :refer [discover]]
            [agent.sessions.manager :refer [create-session-manager]]
            [agent.settings.manager :refer [create-settings-manager]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-loader :refer [discover-and-load]]
            ["./modes/interactive.jsx" :as interactive]
            [agent.modes.print :as print-mode]
            [agent.modes.rpc :as rpc]))

(defn- resolve-model [values merged]
  (or (:model values) (:model merged) "claude-sonnet-4-20250514"))

(defn- resolve-tools [values merged]
  (or (when-let [t (:tools values)]
        (-> t (.split ",") vec))
      (:tools merged)
      ["read" "write" "edit" "bash"]))

(defn- temp-session-path []
  (str "/tmp/nyma-session-" (js/Date.now) ".jsonl"))

(defn- resolve-session [values]
  (let [session-path (or (:session values)
                         (when-not (:no-session values)
                           (str (.. js/process -env -HOME) "/.agent/sessions/default.jsonl")))]
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
        resources (js-await (discover))
        session   (resolve-session values)

        agent (create-agent
                {:model         (resolve-model values merged)
                 :system-prompt ((:build-system-prompt resources))
                 :tools         (resolve-tools values merged)
                 :max-steps     20})

        api (create-extension-api agent)]

    ;; Register built-in commands
    (swap! (:commands agent) assoc
      "help" {:description "Show available commands"
              :handler (fn [_args _ctx]
                         (let [cmds @(:commands agent)]
                           (js/console.log "\nAvailable commands:")
                           (doseq [[name cmd] cmds]
                             (js/console.log (str "  /" name " — " (:description cmd))))
                           (js/console.log "")))}
      "model" {:description "Show current model"
               :handler (fn [_args _ctx]
                          (js/console.log
                            (str "\nModel: " (:model (:config agent)) "\n")))}
      "clear" {:description "Clear messages"
               :handler (fn [_args _ctx]
                          (swap! (:state agent) assoc :messages [])
                          (js/console.log "\nMessages cleared.\n"))}
      "exit" {:description "Exit the agent"
              :handler (fn [_args _ctx]
                         (js/process.exit 0))})

    ;; Load all extensions (both .cljs and .ts/.js)
    (js-await (discover-and-load (:extension-dirs resources) api))

    ;; Dispatch to mode
    (let [mode (or (:mode values)
                   (when (:print values) "print")
                   "interactive")]
      (case mode
        "interactive" (js-await (interactive/start agent session resources))
        "print"       (js-await (print-mode/start agent (first positionals)))
        "json"        (js-await (print-mode/start-json agent (first positionals)))
        "rpc"         (js-await (rpc/start agent))))))

(main)
