(ns agent.modes.sdk
  "Programmatic SDK mode."
  (:require [agent.core :refer [create-agent]]
            [agent.loop :refer [run steer follow-up]]
            [agent.resources.loader :refer [discover]]
            [agent.sessions.manager :refer [create-session-manager attach-session-persistence! new-session-path]]
            [agent.settings.manager :refer [create-settings-manager]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-loader :refer [discover-and-load deactivate-all]]
            [clojure.string :as str]))

(defn- temp-session-path []
  (new-session-path "/tmp" "nyma-sdk-session-"))

(defn- extract-last-assistant-text
  "Pull the final assistant message text out of agent state after a run."
  [agent]
  (let [msgs      (:messages @(:state agent))
        last-asst (last (filter #(= (:role %) "assistant") msgs))
        content   (when last-asst (:content last-asst))]
    (cond
      (string? content)  content
      (vector? content)  (str/join "\n"
                                   (keep #(when (= (:type %) "text") (:text %))
                                         content))
      :else              nil)))

(defn ^:async create-session
  "Programmatic API for embedding the agent in other applications.

   Opts may include all create-agent keys plus:
     :session-path          — path to JSONL session file (default: temp file)
     :require-capabilities  — #{kw} filter builtin tools by capability
     :exclude-capabilities  — #{kw} exclude tools with any of these capabilities
     :modes                 — #{kw} filter tools by allowed runtime mode

   Returned map: :send :send-and-wait :steer :follow-up :on :on-many
   :interrupt! :agent-state :state :agent :session, and :close — deactivates
   the extensions this session loaded; call it when the session is done."
  [opts]
  (let [settings  (create-settings-manager)
        resources (js-await (discover {:context-files (:context-files ((:get settings)))
                                     :max-skill-description (:max-description-length (:skills ((:get settings))))}))
        session   (create-session-manager (or (:session-path opts)
                                              (temp-session-path)))
        agent     (create-agent
                   (merge ((:get settings))
                          (select-keys opts [:model :tools :system-prompt
                                             :require-capabilities
                                             :exclude-capabilities
                                             :modes])
                          ;; `discover` was called for its context files and
                          ;; then only read for extension dirs, so an embedder
                          ;; or gateway with no explicit prompt ran with a nil
                          ;; system prompt and never saw AGENTS.md / CLAUDE.md.
                          ;; Same default the CLI builds.
                          (when-not (:system-prompt opts)
                            {:system-prompt ((:build-system-prompt resources))})))]

    ;; Attach the session so extensions/commands (@(:session agent)) see it,
    ;; and mirror turns to the JSONL — without this, embedder and gateway
    ;; conversations were never persisted despite the docstring.
    (reset! (:session agent) session)
    (attach-session-persistence! agent session)

    ;; Load extensions
    (let [loaded (js-await (discover-and-load
                            (:extension-dirs resources)
                            (create-extension-api agent)
                            (:builtin-extensions resources)))]

      {:agent     agent
       :session   session
       ;; Deactivate every extension this session loaded. A gateway that
       ;; replaces sessions (ephemeral policy) otherwise accumulates one full
       ;; set of extension handlers per message for the life of the process.
       :close     (fn [] (deactivate-all loaded))
       :send      (partial run agent)
       :steer     (partial steer agent)
       :follow-up (partial follow-up agent)

       ;; Wait for the run to finish and return the final assistant text.
       :send-and-wait
       (fn [text & args]
         (.. (apply run agent text args)
             (then (fn [_] (extract-last-assistant-text agent)))))

       ;; Subscribe to multiple events at once. Returns an unsubscribe-all fn.
       :on-many
       (fn [handlers-map]
         (let [unsub-fns (mapv (fn [[event handler]]
                                 (let [ev (name event)]
                                   ((:on (:events agent)) ev handler)
                                   (fn [] ((:off (:events agent)) ev handler))))
                               handlers-map)]
           (fn [] (doseq [f unsub-fns] (f)))))

       ;; Abort the current run. reason is informational only.
       :interrupt!
       (fn [& [_reason]]
         (.abort @(:abort-controller agent))
         nil)

       ;; Derive a coarse agent state keyword from live state.
       ;; :tool-running — one or more tool calls in flight
       ;; :idle         — nothing running
       ;; Not yet reported: :thinking :streaming :awaiting-approval :error.
       :agent-state
       (fn []
         (let [s @(:state agent)]
           (if (seq (:active-executions s)) :tool-running :idle)))

       ;; (name event) like :on-many — core emits string event names, so a
       ;; keyword subscriber would otherwise silently never fire.
       :on    (fn [event handler]
                (let [ev (name event)]
                  ((:on (:events agent)) ev handler)
                  (fn [] ((:off (:events agent)) ev handler))))
       :state (fn [] @(:state agent))})))
