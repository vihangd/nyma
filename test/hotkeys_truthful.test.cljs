(ns hotkeys-truthful.test
  "/hotkeys must list the keys that are bound and nothing else, and a
   resumed session must say what it resumed."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.keybinding-registry :as kbr]
            [agent.modes.interactive :refer [resumed-banner]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-scope :refer [create-scoped-api]]
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
         ;; app.scroll.* are in the action table but no code dispatches
         ;; them; printing them is the bug this fixes.
         (let [text (kbr/hotkeys-text registry {})]
           (-> (expect (.includes text "Scroll chat up one page")) (.toBe false)))))

   (it "lists ctrl+o once it has a handler, and not a second time from the shortcuts atom"
       (fn []
         ;; interactive mode registers the handler in `(:shortcuts agent)`
         ;; under the action id; the registry row is the one that prints.
         (let [text (kbr/hotkeys-text
                     registry
                     {"ctrl+o" {:action "app.tools.expand" :handler (fn [])
                                :description "Expand or collapse the last tool's output"}})]
           (-> (expect (.includes text "^O")) (.toBe true))
           (-> (expect (.includes text "Expand or collapse the last tool's output")) (.toBe true))
           (-> (expect (count (.split text "Expand or collapse"))) (.toBe 2)))))

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

;;; ─── The description must survive the real, gated api ────────────────────
;;
;; Every test above hands `hotkeys-text` a hand-built shortcuts map. In
;; production the description travels registerShortcut → extension_scope's
;; capability gate → extensions.cljs, and an arity mismatch anywhere on that
;; path drops it silently: the key would still be listed, with no description,
;; which is the exact failure /hotkeys was rewritten to end.

(describe
 "an extension's shortcut description reaches /hotkeys through the gated api"
 (fn []
   (it "keeps the description a scoped extension passes"
       (fn []
         (let [agent  (create-agent #js {:model #js {:modelId "test-model"}
                                         :system-prompt "test"})
               scoped (create-scoped-api (create-extension-api agent)
                                         "demo" #{"shortcuts"})]
           (.registerShortcut scoped "ctrl+t" (fn []) #js {:description "Do the thing"})
           (-> (expect (kbr/shortcut-description (get @(:shortcuts agent) "ctrl+t")))
               (.toBe "Do the thing"))
           (-> (expect (.includes (kbr/hotkeys-text @(:keybinding-registry agent)
                                                    @(:shortcuts agent))
                                  "Do the thing"))
               (.toBe true)))))

   (it "still stores a bare fn for the two-argument form"
       (fn []
         (let [agent  (create-agent #js {:model #js {:modelId "test-model"}
                                         :system-prompt "test"})
               scoped (create-scoped-api (create-extension-api agent)
                                         "demo" #{"shortcuts"})]
           (.registerShortcut scoped "ctrl+t" (fn []))
           (-> (expect (fn? (get @(:shortcuts agent) "ctrl+t"))) (.toBe true)))))))

(describe
 "/hotkeys reports keybinding conflicts"
 (fn []
   (it "prints a Conflicts section when keybindings.json puts two actions on one key"
       (fn []
         ;; ctrl+r is app.history.search by default; binding it to app.help
         ;; too must be visible here, not only under NYMA_DEBUG.
         (let [text (kbr/hotkeys-text (kbr/create-registry {"ctrl+r" "app.help"}) {})]
           (-> (expect (.includes text "Conflicts")) (.toBe true))
           (-> (expect (.includes text "app.help, app.history.search")) (.toBe true)))))

   (it "prints no Conflicts section without one"
       (fn []
         ;; Enter is shared by submit and steer on purpose, and neither is
         ;; rebindable — that is not a conflict the user can do anything about.
         (-> (expect (.includes (kbr/hotkeys-text registry {}) "Conflicts")) (.toBe false))))))
