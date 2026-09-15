(ns sdk-session.test
  "agent.modes.sdk/create-session — the embedding API the gateway and any
   host program use. Runs against a scripted model in-process with HOME and
   cwd pointed at temp dirs, so settings, context files and extensions are
   the ones the test wrote.

   The context-file case is the one that found the bug: `discover` was
   called with the setting and its result read only for extension dirs, so
   an embedder with no explicit :system-prompt ran on a nil prompt and never
   saw AGENTS.md / CLAUDE.md, whatever `context-files` said."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.modes.sdk :refer [create-session]]
            [test-util.mock-model :refer [scripted-model system-text]]))

(defn- write! [p content]
  (fs/mkdirSync (path/dirname p) #js {:recursive true})
  (fs/writeFileSync p content))

(defn- ^:async in-sandbox
  "Run `f` with cwd and HOME inside fresh temp dirs; restore whatever happens."
  [f]
  (let [root      (fs/mkdtempSync (path/join (os/tmpdir) "nyma-sdk-"))
        home      (fs/mkdtempSync (path/join (os/tmpdir) "nyma-sdk-home-"))
        orig-cwd  (js/process.cwd)
        orig-home (.-HOME js/process.env)]
    (js/process.chdir root)
    (aset js/process.env "HOME" home)
    (try
      (js-await (f root))
      (finally
        (js/process.chdir orig-cwd)
        (aset js/process.env "HOME" orig-home)
        (doseq [d [root home]]
          (try (fs/rmSync d #js {:recursive true :force true})
               (catch :default _e nil)))))))

(defn ^:async test-session-round-trip []
  (js-await
   (in-sandbox
    (^:async fn [root]
      ;; The setting replaces the default list, so CLAUDE.md must NOT be read.
      (write! (path/join root ".nyma" "settings.json")
              (js/JSON.stringify #js {:context-files #js ["TEAM_NOTES.md"]}))
      (write! (path/join root "TEAM_NOTES.md") "MARKER-TEAM-NOTES: reply tersely")
      (write! (path/join root "CLAUDE.md") "MARKER-CLAUDE-MD")
      (let [{:keys [model calls]} (scripted-model ["hel" "lo"])
            s        (js-await (create-session {:model model
                                                :session-path (path/join root "s.jsonl")}))
            agent    (:agent s)
            total    (:handler-total (:events agent))
            baseline (total)
            reply    (js-await ((:send-and-wait s) "hi"))
            sys      (str (system-text (first @calls)))]
        ;; One message in, the model's text out.
        (-> (expect reply) (.toBe "hello"))
        (-> (expect (count @calls)) (.toBe 1))
        ;; The context file named by the setting reached the provider as
        ;; part of the system prompt; the unlisted default did not.
        (-> (expect (.includes sys "MARKER-TEAM-NOTES")) (.toBe true))
        (-> (expect (.includes sys "MARKER-CLAUDE-MD")) (.toBe false))
        ;; Extensions loaded: the session is more than a bare agent.
        (-> (expect (pos? baseline)) (.toBe true))
        ;; Subscriptions come off cleanly...
        (let [off (:on s)
              unsub ((:on s) :message_update (fn [_] nil))
              unsub-many ((:on-many s) {:agent_start (fn [_] nil) :agent_end (fn [_] nil)})]
          (-> (expect (total)) (.toBe (+ baseline 3)))
          (unsub)
          (unsub-many)
          (-> (expect (total)) (.toBe baseline))
          (-> (expect (fn? off)) (.toBe true)))
        ;; ...and close tears the extensions down with their handlers.
        (js-await ((:close s)))
        (-> (expect (< (total) baseline)) (.toBe true))
        ;; The turn was persisted to the requested session file.
        (-> (expect (fs/existsSync (path/join root "s.jsonl"))) (.toBe true)))))))

(defn ^:async test-explicit-system-prompt-wins []
  (js-await
   (in-sandbox
    (^:async fn [root]
      (write! (path/join root "AGENTS.md") "MARKER-AGENTS-MD")
      (let [{:keys [model calls]} (scripted-model ["ok"])
            s (js-await (create-session {:model model
                                         :system-prompt "MARKER-EXPLICIT"
                                         :session-path (path/join root "s.jsonl")}))]
        (js-await ((:send-and-wait s) "hi"))
        (js-await ((:close s)))
        (let [sys (str (system-text (first @calls)))]
          (-> (expect (.includes sys "MARKER-EXPLICIT")) (.toBe true))
          (-> (expect (.includes sys "MARKER-AGENTS-MD")) (.toBe false))))))))

(describe "sdk create-session" (fn []
                                 (it "sends a message, gets the reply, reads the configured context file, and closes clean"
                                     test-session-round-trip)
                                 (it "an explicit :system-prompt is used as-is"
                                     test-explicit-system-prompt-wins)))
