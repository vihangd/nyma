(ns ext-headroom-off.test
  "An extension that is off must still answer for itself.

   headroom registered `/headroom-stats` only inside `(when (:enabled config))`,
   so with the extension off the command did not exist — and an unknown command
   reads as a broken install, not as a switch someone has to flip. Worse, the
   `when-not` branch above it built a deactivator and threw it away mid-`let`.

   Second silence, same file: `load-config` caught every JSON error from
   .nyma/settings.json with `(catch :default _ nil)` and returned the defaults,
   so a settings file with a trailing comma and one that never mentioned
   headroom produced exactly the same result — off, with no reason given."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.extensions.headroom.shared :as shared]
            [agent.extensions.headroom.index :as index]))

(def tmp-root (atom nil))
(def orig-cwd (atom nil))

(beforeEach (fn []
              (reset! orig-cwd (js/process.cwd))
              (reset! tmp-root (fs/mkdtempSync (path/join (os/tmpdir) "nyma-headroom-")))))

(afterEach (fn []
             (js/process.chdir @orig-cwd)
             (try (fs/rmSync @tmp-root #js {:recursive true :force true})
                  (catch :default _e nil))))

(defn- stub-api [registered flags flag-val listeners]
  #js {:registerFlag      (fn [n _] (swap! flags assoc n nil) nil)
       :getFlag           (fn [_] flag-val)
       :registerCommand   (fn [n spec] (swap! registered assoc n spec) nil)
       :unregisterCommand (fn [n] (swap! registered dissoc n) nil)
       :on                (fn [evt h & _] (swap! listeners update evt (fnil conj []) h) nil)
       :off               (fn [evt h]
                            (swap! listeners update evt
                                   (fn [hs] (filterv #(not= % h) (or hs []))))
                            nil)
       :ui                #js {:available false}})

;;; ─── /headroom-stats answers when headroom is off ─────────────────────────

(describe "headroom answers /headroom-stats when it is off"
          (fn []
            (it "names the setting, the proxy and the flag"
                (fn []
                  (let [hint (shared/disabled-hint "http://localhost:9999")]
                    (-> (expect (.includes hint "headroom is off")) (.toBe true))
                    (-> (expect (.includes hint "headroom.enabled: true")) (.toBe true))
                    (-> (expect (.includes hint ".nyma/settings.json")) (.toBe true))
                    (-> (expect (.includes hint "http://localhost:9999")) (.toBe true))
                    (-> (expect (.includes hint "--ext-headroom")) (.toBe true)))))

            (it "falls back to the default proxy url when none is configured"
                (fn []
                  (-> (expect (.includes (shared/disabled-hint nil) "http://localhost:8787"))
                      (.toBe true))))

            (it "registers the command anyway, and returns a working deactivator"
                (fn []
                  (js/process.chdir @tmp-root)
                  (let [registered (atom {})
                        flags      (atom {})
                        api (stub-api registered flags nil (atom {}))
                        dispose ((.-default index) api)]
                    (-> (expect (contains? @registered "headroom-stats")) (.toBe true))
                    ;; …and it says how to switch it on, rather than stats of nothing.
                    (let [out ((.-handler (get @registered "headroom-stats")) "" nil)]
                      (-> (expect (.includes out "headroom is off")) (.toBe true)))
                    ;; The old disabled branch built this fn mid-`let` and dropped it.
                    (-> (expect (fn? dispose)) (.toBe true))
                    (dispose)
                    (-> (expect (contains? @registered "headroom-stats")) (.toBe false)))))

            (it "registers the real stats command when --ext-headroom is given"
                (fn []
                  (js/process.chdir @tmp-root)
                  (let [registered (atom {})
                        api (stub-api registered (atom {}) true (atom {}))]
                    ((.-default index) api)
                    ;; Description, not the handler: the enabled path probes the
                    ;; proxy, and this only needs to know which one was bound.
                    (-> (expect (.includes (.-description (get @registered "headroom-stats"))
                                           "is off"))
                        (.toBe false)))))))

;;; ─── a malformed headroom section is reported with its file ───────────────

(describe "a malformed headroom settings section is reported with its file"
          (fn []
            (it "collects the path and the parse error instead of defaulting silently"
                (fn []
                  (js/process.chdir @tmp-root)
                  (fs/mkdirSync (path/join @tmp-root ".nyma") #js {:recursive true})
                  (fs/writeFileSync (path/join @tmp-root ".nyma" "settings.json")
                                    "{ \"headroom\": { \"enabled\": true, } ")
                  (let [{:keys [config errors]} (shared/read-config)]
                    (-> (expect (count errors)) (.toBe 1))
                    ;; endsWith, not =: process.cwd() comes back through
                    ;; /private on macOS while mkdtemp hands back /var.
                    (-> (expect (.endsWith (:path (first errors))
                                           (path/join ".nyma" "settings.json")))
                        (.toBe true))
                    ;; …and it still comes up with the defaults rather than dying.
                    (-> (expect (:enabled config)) (.toBe false)))))

            (it "says nothing when the settings file parses"
                (fn []
                  (js/process.chdir @tmp-root)
                  (fs/mkdirSync (path/join @tmp-root ".nyma") #js {:recursive true})
                  (fs/writeFileSync (path/join @tmp-root ".nyma" "settings.json")
                                    (js/JSON.stringify (clj->js {:headroom {:enabled true}})))
                  (let [{:keys [config errors]} (shared/read-config)]
                    (-> (expect (count errors)) (.toBe 0))
                    (-> (expect (:enabled config)) (.toBe true)))))

            (it "notifies an error naming the section and the file"
                (fn []
                  (let [seen (atom [])]
                    (index/notify-settings-errors!
                     [{:path "/tmp/p/.nyma/settings.json" :message "Unexpected token"}]
                     (fn [m lvl] (swap! seen conj [m lvl])))
                    (-> (expect (count @seen)) (.toBe 1))
                    (-> (expect (nth (first @seen) 1)) (.toBe "error"))
                    (-> (expect (.includes (nth (first @seen) 0) "`headroom`")) (.toBe true))
                    (-> (expect (.includes (nth (first @seen) 0) "/tmp/p/.nyma/settings.json"))
                        (.toBe true)))))

            (it "stays quiet when there is nothing wrong"
                (fn []
                  (let [seen (atom [])]
                    (index/notify-settings-errors! [] (fn [m lvl] (swap! seen conj [m lvl])))
                    (-> (expect (count @seen)) (.toBe 0)))))

            (it "waits for session_ready to say it, because the UI is not up yet"
                (fn []
                  ;; api.ui.notify is wired while interactive mode builds the
                  ;; TUI — long after extensions load. Notifying at activation
                  ;; hits `available: false` and falls back to debug.log, which
                  ;; is the silence this whole change exists to end.
                  (js/process.chdir @tmp-root)
                  (fs/mkdirSync (path/join @tmp-root ".nyma") #js {:recursive true})
                  (fs/writeFileSync (path/join @tmp-root ".nyma" "settings.json") "{ broken")
                  (let [listeners (atom {})]
                    ((.-default index) (stub-api (atom {}) (atom {}) nil listeners))
                    (-> (expect (count (get @listeners "session_ready" []))) (.toBe 1)))

                  ;; …and subscribes to nothing when the file is fine.
                  (fs/writeFileSync (path/join @tmp-root ".nyma" "settings.json") "{}")
                  (let [listeners (atom {})]
                    ((.-default index) (stub-api (atom {}) (atom {}) nil listeners))
                    (-> (expect (count (get @listeners "session_ready" []))) (.toBe 0)))))))
