(ns agent.extensions.openwiki.commands
  "The /openwiki command: dispatches init | update | chat.

   Generation is triggered by follow-up PRIMING — the doc-writing/answering run
   fires on the user's next turn (nyma commands can't start an agentic run at
   idle; the follow-queue drains inside the next run). This is the same pattern
   spec_driven's /spec import uses. The notice makes the deferral explicit."
  (:require ["node:fs" :as fs]
            [clojure.string :as str]
            [agent.loop :as agent-loop]
            [agent.extensions.openwiki.shared :as shared]
            [agent.extensions.openwiki.git :as git]
            [agent.extensions.openwiki.metadata :as md]
            [agent.extensions.openwiki.prompts :as prompts]))

(defn- notify [ctx msg level]
  (when-let [ui (.-ui ctx)]
    (when (.-notify ui) (.notify ui msg level))))

(defn- prime!
  "Enqueue the generation prompt as a follow-up (fires on the next turn).
   Returns true on success, false when the ctx has no agent (some command
   dispatch paths build a ctx without :agent)."
  [ctx prompt]
  (if-let [agent (.-agent ctx)]
    (do (agent-loop/follow-up agent {:content prompt}) true)
    false))

(defn make-handler
  "Command handler. `args` is a ClojureScript seq of tokens (from
   run-command!'s `(rest parts)`), NOT a JS array — use seq ops."
  [config]
  (fn [args ctx]
    (let [dir      (:dir config)
          sections (:sections config)
          verb     (or (first args) "help")
          rest-args (rest args)
          prime-notice
          (fn [ok? queued-msg]
            (if ok?
              (notify ctx queued-msg "info")
              (notify ctx "openwiki: no active agent in this context." "error")))]
      (case verb
        "init"
        (if-not (git/in-git-repo?)
          (notify ctx "openwiki: not a git repository." "error")
          (let [ctx-data (assoc (git/collect-context) :tree (git/repo-tree))]
            (prime-notice (prime! ctx (prompts/init-prompt dir sections ctx-data))
                          (str "OpenWiki queued — press Enter (or send any message) to start "
                               "writing " dir "/."))))

        "update"
        (cond
          (not (git/in-git-repo?))
          (notify ctx "openwiki: not a git repository." "error")
          (not (fs/existsSync (shared/metadata-file dir)))
          (notify ctx "No OpenWiki found. Run /openwiki init first." "error")
          :else
          (let [meta    (md/load-metadata dir)
                changes (git/changes-since (when meta (aget meta "gitHead")))]
            (if (str/blank? changes)
              (notify ctx "No changes since last update. Nothing to do." "warning")
              (let [{:keys [status diff]} (git/collect-context)]
                (prime-notice (prime! ctx (prompts/update-prompt dir meta changes status diff))
                              "OpenWiki update queued — send any message to begin.")))))

        "chat"
        (let [q (str/trim (str/join " " rest-args))]
          (if (str/blank? q)
            (notify ctx "Usage: /openwiki chat <question>" "error")
            (prime-notice (prime! ctx (prompts/chat-prompt dir q))
                          "OpenWiki question queued — send any message for the answer.")))

        (notify ctx "Usage: /openwiki [init | update | chat <question>]" "info")))))
