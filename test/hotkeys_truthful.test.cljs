(ns hotkeys-truthful.test
  "/hotkeys must list the keys that are bound and nothing else, and a
   resumed session must say what it resumed."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.keybinding-registry :as kbr]
            [agent.modes.interactive :refer [resumed-banner]]
            ["node:fs" :as fs]))

(def ^:private registry (kbr/create-registry))

(describe
 "/hotkeys lists the keys that are actually bound"
 (fn []
   (it "names Escape as the abort and says when it applies"
       (fn []
         (let [text (kbr/hotkeys-text registry {})]
           (-> (expect (.includes text "esc")) (.toBe true))
           (-> (expect (.includes text "Abort the turn in flight")) (.toBe true)))))

   (it "names Ctrl-C as the interrupt, which no action table knew about"
       (fn []
         (-> (expect (.includes (kbr/hotkeys-text registry {}) "^C")) (.toBe true))))

   (it "names Enter and Shift-Enter"
       (fn []
         (let [text (kbr/hotkeys-text registry {})]
           (-> (expect (.includes text "enter")) (.toBe true))
           (-> (expect (.includes text "Insert a newline")) (.toBe true)))))

   (it "no longer claims Ctrl+L shows the model or that Ctrl+P is reserved"
       (fn []
         (let [text (kbr/hotkeys-text registry {})]
           (-> (expect (.includes text "Reserved")) (.toBe false))
           (-> (expect (.includes text "^L")) (.toBe false))
           (-> (expect (.includes text "^P")) (.toBe false)))))

   (it "omits an action nothing dispatches"
       (fn []
         ;; app.tools.expand / app.scroll.* are in the action table but no
         ;; code dispatches them; printing them is the bug this fixes.
         (let [text (kbr/hotkeys-text registry {})]
           (-> (expect (.includes text "Expand tool execution view")) (.toBe false))
           (-> (expect (.includes text "Scroll chat up one page")) (.toBe false)))))

   (it "prints an extension shortcut with its description"
       (fn []
         (let [text (kbr/hotkeys-text
                     registry
                     {"ctrl+r" {:handler (fn [])
                                :description "Search prompt history"}})]
           (-> (expect (.includes text "^R")) (.toBe true))
           (-> (expect (.includes text "Search prompt history")) (.toBe true)))))

   (it "still lists a bare-fn shortcut, registered the two-argument way"
       (fn []
         (let [text (kbr/hotkeys-text registry {"ctrl+k" (fn [])})]
           (-> (expect (.includes text "^K")) (.toBe true)))))

   (it "names what a keybindings.json binding runs"
       (fn []
         (let [text (kbr/hotkeys-text
                     registry
                     {"ctrl+g" {:action "command:compact" :source "keybindings.json"}})]
           (-> (expect (.includes text "Run /compact")) (.toBe true)))))

   (it "is generated, not hardcoded"
       (fn []
         (let [src (fs/readFileSync "src/agent/commands/builtins.cljs" "utf8")]
           ;; The hardcoded table, not the comment that records why it went.
           (-> (expect (.includes src "[\"Ctrl+L\"  \"Show current model\"]")) (.toBe false))
           (-> (expect (.includes src "[\"Ctrl+P\"  \"Reserved\"]")) (.toBe false))
           (-> (expect (.includes src "kbr/hotkeys-text")) (.toBe true)))))))

(describe
 "a session says how to abort and where help is, on first launch"
 (fn []
   (it "offers Esc, Ctrl-C and /help in one line"
       (fn []
         (-> (expect (.includes kbr/first-launch-hint "Esc")) (.toBe true))
         (-> (expect (.includes kbr/first-launch-hint "Ctrl-C")) (.toBe true))
         (-> (expect (.includes kbr/first-launch-hint "/help")) (.toBe true))
         ;; One line, not a paragraph.
         (-> (expect (.includes kbr/first-launch-hint "\n")) (.toBe false))))))

(describe
 "a resumed session says what it resumed"
 (fn []
   (it "names the session, its size and its cost so far"
       (fn []
         (let [text (resumed-banner {:message-count 12
                                     :name          "auth refactor"
                                     :total-cost    1.234})]
           (-> (expect (.includes text "auth refactor")) (.toBe true))
           (-> (expect (.includes text "12 messages")) (.toBe true))
           (-> (expect (.includes text "$1.23")) (.toBe true)))))

   (it "falls back to the session file's name when it has no display name"
       (fn []
         (-> (expect (.includes (resumed-banner {:message-count 3
                                                 :file-path "/home/u/.nyma/sessions/abc.jsonl"})
                                "abc.jsonl"))
             (.toBe true))))

   (it "leaves the cost out when the state carries none"
       (fn []
         (-> (expect (.includes (resumed-banner {:message-count 2 :name "x"}) "$"))
             (.toBe false))))

   (it "says nothing at all for a fresh session"
       (fn []
         (-> (expect (resumed-banner {:message-count 0 :name "x"})) (.toBeNil))
         (-> (expect (resumed-banner {})) (.toBeNil))))

   (it "counts one message in the singular"
       (fn []
         (-> (expect (.includes (resumed-banner {:message-count 1 :name "x"}) "1 message"))
             (.toBe true))))))
