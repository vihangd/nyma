(ns command-effects-test
  "Every built-in command must DO something when invoked.

   `commands_builtins.test.cljs` asserts that commands are *registered* and
   that a few declare aliases. `/fork` passed that test throughout the period
   it was a complete no-op — it was registered, it had a description, it
   emitted events, it printed \"Session forked\", and it changed nothing. `/new`
   passed it while failing to switch the session file, and `/agent handoff`
   passed its own picker tests while resolving to
   `Unknown agent: [object Object]`.

   Three commands, one defect class, zero test coverage: nothing invoked a
   handler and asked whether anything happened.

   So this invokes all of them. \"Something happened\" is deliberately broad —
   a notification, an overlay, a returned string, or an observable change to
   the session — because the point is to catch SILENCE, not to re-specify each
   command. A command that is legitimately silent in a stub environment is
   allowlisted with its reason, which is the only part of this file that should
   ever need editing."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.core :refer [create-agent]]
            [agent.sessions.manager :as sm]
            [agent.commands.builtins :refer [register-builtins]]))

(def ^:private silent-ok
  "Commands that produce no observable effect in a stub environment, each with
   the reason. Anything NOT here must do something when invoked.

   Keep this list short and justified. Every entry is a thing the test cannot
   see, not a thing the command need not do."
  {"exit"     "calls process.exit; the test preload turns that into a throw"
   "compact"  "needs a live model to summarise"
   "reload"   "re-imports extensions; no-op with none loaded"
   "copy"     "writes to the system clipboard via pbcopy"
   "cls"      "writes ANSI escapes straight to stdout, bypassing ctx.ui"
   "resume"   "opens a picker; with no other sessions on disk it has nothing to offer"
   "import"   "requires a file argument that does not exist here"})

(defn- temp-dir []
  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-cmdfx-")))

(defn- make-agent []
  (let [dir   (temp-dir)
        sess  (sm/create-session-manager (path/join dir "session.jsonl"))
        agent (create-agent {:model "test-model" :system-prompt "test"})]
    ;; A REAL session manager, not a stub of one. A stub answers every call
    ;; with nil, which is indistinguishable from a command that did nothing —
    ;; exactly the blind spot that let /fork pass.
    ((:append sess) {:role "user" :content "first question"})
    ((:append sess) {:role "assistant" :content "an answer"})
    ((:append sess) {:role "user" :content "second question"})
    (reset! (:session agent) sess)
    (register-builtins agent sess {})
    {:agent agent :session sess :dir dir}))

(defn- make-ctx []
  (let [notifications (atom [])
        overlays      (atom [])
        selects       (atom [])]
    {:ctx #js {:ui #js {:available true
                        :notify      (fn [msg level] (swap! notifications conj [msg level]))
                        :showOverlay (fn [text] (swap! overlays conj text) (fn [] nil))
                        :custom      (fn [_p & _] (swap! selects conj :custom) (fn [] nil))
                        ;; Resolves with a real ITEM, not nil. Every mock in
                        ;; the suite defaulted to nil, so the cancel path was
                        ;; tested and the PICK path never was — which is how
                        ;; the handoff bug survived its own picker tests.
                        ;; Recorded, not just stubbed: `/login` prompts via
                        ;; `input`, and an unrecorded prompt reads as silence.
                        :input       (fn [& _]
                                       (swap! selects conj :input)
                                       (js/Promise.resolve nil))
                        :select      (fn [_title opts]
                                       (swap! selects conj :select)
                                       (js/Promise.resolve
                                        (let [a (js/Array.from (or opts #js []))]
                                          (first a))))}}
     :notifications notifications
     :overlays overlays
     :selects selects}))

(defn- effectful?
  "Did invoking the handler produce anything observable?"
  [{:keys [notifications overlays selects]} ret]
  (or (seq @notifications)
      (seq @overlays)
      (seq @selects)
      (and (string? ret) (pos? (count (str/trim ret))))))

(describe "built-in commands"
          (fn []
            (it "registers a believable number of them"
                (fn []
        ;; Guard the guard: if registration failed, every assertion below would
        ;; iterate an empty map and report green.
                  (let [{:keys [agent]} (make-agent)]
                    (-> (expect (count (keys @(:commands agent)))) (.toBeGreaterThan 25)))))

            (it "every command either does something or is allowlisted as silent"
                (^:async fn []
                  (let [names (sort (keys @(:commands (:agent (make-agent)))))
                        silent (atom [])
                        threw  (atom [])]
                    (doseq [name names]
            ;; A fresh agent per command: /clear, /new and /fork mutate state,
            ;; and a shared one would let an earlier command's effects answer
            ;; for a later one's silence.
                      (let [{:keys [agent]} (make-agent)
                            handler (get-in @(:commands agent) [name :handler])
                            rec     (make-ctx)
                            ret     (try
                                      (let [r (handler [] (:ctx rec))]
                              ;; handlers may be ^:async
                                        (if (and r (.-then r)) (js-await r) r))
                                      (catch :default e
                                        (swap! threw conj [name (or (.-message e) (str e))])
                                        ::threw))]
                        (when (and (not= ret ::threw) (not (effectful? rec ret)))
                          (swap! silent conj name))))
          ;; Reported by NAME so a failure says which command went quiet.
                    (-> (expect (clj->js (vec (remove silent-ok @silent))))
                        (.toEqual (clj->js [])))
          ;; Throws are reported separately: a handler that blows up on a valid
          ;; ctx is broken in a different way from one that is merely silent.
                    (-> (expect (clj->js (vec (remove (fn [[n _]] (contains? silent-ok n)) @threw))))
                        (.toEqual (clj->js []))))))

            (it "the allowlist names only commands that exist"
                (fn []
        ;; A stale entry would silently excuse a command that had been renamed.
                  (let [{:keys [agent]} (make-agent)
                        registered (set (keys @(:commands agent)))
                        stale (vec (remove registered (keys silent-ok)))]
                    (-> (expect (clj->js stale)) (.toEqual (clj->js []))))))))

(describe "the commands that regressed"
          (fn []
            (it "/fork changes the branch it is invoked from"
                (^:async fn []
                  (let [{:keys [agent session]} (make-agent)
                        handler (get-in @(:commands agent) ["fork" :handler])
                        rec     (make-ctx)
                        before  ((:leaf-id session))]
          ;; `/fork 1` takes the first candidate without needing a picker.
                    (let [r (handler ["1"] (:ctx rec))]
                      (when (and r (.-then r)) (js-await r)))
                    (-> (expect ((:leaf-id session))) (.not.toBe before)))))

            (it "/new switches the session file"
                (^:async fn []
                  (let [{:keys [agent session]} (make-agent)
                        handler (get-in @(:commands agent) ["new" :handler])
                        before  ((:get-file-path session))
                        rec     (make-ctx)]
                    (let [r (handler [] (:ctx rec))]
                      (when (and r (.-then r)) (js-await r)))
                    (-> (expect ((:get-file-path session))) (.not.toBe before)))))))
