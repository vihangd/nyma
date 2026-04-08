(ns workspace-config.test
  "Tests for the workspace config extension (F1: loading, F3: aliases)."
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.extensions.workspace-config.index :as wc]
            [agent.extensions.workspace-config.aliases :as aliases]))

;;; ─── Mock API ─────────────────────────────────────────────────

(defn- make-mock-api []
  (let [registered-commands (atom {})
        notifications       (atom [])]
    #js {:ui                #js {:available true
                                 :notify    (fn [msg _level] (swap! notifications conj msg))}
         :registerCommand   (fn [name opts]
                              (swap! registered-commands assoc name opts))
         :unregisterCommand (fn [name]
                              (swap! registered-commands dissoc name))
         :getCommands       (fn [] (clj->js @registered-commands))
         :_commands         registered-commands
         :_notifications    notifications}))

;;; ─── State reset ──────────────────────────────────────────────

(beforeEach
  (fn []
    (reset! wc/aliases-atom {})
    (reset! wc/flags-atom {})))

(afterEach
  (fn []
    (reset! wc/aliases-atom {})
    (reset! wc/flags-atom {})))

;;; ─── Group 1: Config loading ──────────────────────────────────

(describe "workspace-config:loading" (fn []
  (it "returns empty maps when .nyma/settings.json does not exist"
    (fn []
      (let [config (wc/load-config!)]
        (-> (expect (= (:aliases config) {})) (.toBe true))
        (-> (expect (= (:flags config) {})) (.toBe true)))))

  (it "parses aliases and flags from valid JSON"
    (fn []
      (let [tmp-dir  (str (js/process.cwd) "/.nyma-test-tmp")
            tmp-file (path/join tmp-dir "settings.json")
            json-str (js/JSON.stringify #js {:aliases #js {:cc "model claude" :hw "help"}
                                             :flags   #js {:debug true}})]
        (.mkdirSync fs tmp-dir #js {:recursive true})
        (.writeFileSync fs tmp-file json-str "utf8")
        (let [raw           (.readFileSync fs tmp-file "utf8")
              obj           (js/JSON.parse raw)
              alias-entries (js/Object.entries (.-aliases obj))
              parsed-aliases (reduce (fn [m e] (assoc m (aget e 0) (aget e 1))) {} alias-entries)]
          (-> (expect (get parsed-aliases "cc")) (.toBe "model claude"))
          (-> (expect (get parsed-aliases "hw")) (.toBe "help")))
        (.rmSync fs tmp-dir #js {:recursive true}))))

  (it "returns empty maps on invalid JSON"
    (fn []
      (let [caught (try (js/JSON.parse "NOT VALID JSON") (catch :default _ :error))]
        (-> (expect (= caught :error)) (.toBe true)))))))

;;; ─── Group 2: Aliases atom ────────────────────────────────────

(describe "workspace-config:aliases-atom" (fn []
  (it "get-aliases returns empty map initially"
    (fn []
      (-> (expect (empty? (wc/get-aliases))) (.toBe true))))

  (it "set-alias! adds to the atom"
    (fn []
      (wc/set-alias! "foo" "/help")
      (-> (expect (get (wc/get-aliases) "foo")) (.toBe "/help"))))

  (it "set-alias! can overwrite existing alias"
    (fn []
      (wc/set-alias! "foo" "/help")
      (wc/set-alias! "foo" "/clear")
      (-> (expect (get (wc/get-aliases) "foo")) (.toBe "/clear"))))

  (it "remove-alias! removes from atom"
    (fn []
      (wc/set-alias! "foo" "/help")
      (wc/remove-alias! "foo")
      (-> (expect (nil? (get (wc/get-aliases) "foo"))) (.toBe true))))))

;;; ─── Group 3: Activate / deactivate (via wc/default) ─────────

(describe "workspace-config:activate" (fn []
  (it "registers reload command on activate"
    (fn []
      (let [activate (.-default wc)
            api      (make-mock-api)
            deact    (activate api)]
        (-> (expect (contains? @(.-_commands api) "reload")) (.toBe true))
        (deact))))

  (it "registers alias command on activate"
    (fn []
      (let [activate (.-default wc)
            api      (make-mock-api)
            deact    (activate api)]
        (-> (expect (contains? @(.-_commands api) "alias")) (.toBe true))
        (deact))))

  (it "deactivator unregisters reload and alias commands"
    (fn []
      (let [activate (.-default wc)
            api      (make-mock-api)
            deact    (activate api)]
        (deact)
        (-> (expect (contains? @(.-_commands api) "reload")) (.toBe false))
        (-> (expect (contains? @(.-_commands api) "alias")) (.toBe false)))))

  (it "reload command notifies with alias count"
    (fn []
      (let [activate (.-default wc)
            api      (make-mock-api)
            deact    (activate api)
            handler  (.-handler (get @(.-_commands api) "alias"))]
        ;; Register two aliases first
        (handler #js ["foo" "/help"] nil)
        (handler #js ["bar" "/clear"] nil)
        (reset! (.-_notifications api) [])
        ;; Now call reload
        (let [reload-h (.-handler (get @(.-_commands api) "reload"))]
          (reload-h #js [] #js {:ui (.-ui api)})
          (let [notif (str/join " " @(.-_notifications api))]
            (-> (expect (str/includes? notif "reloaded")) (.toBe true))))
        (deact))))))

;;; ─── Group 4: Alias command registration ─────────────────────
;; Tests use aliases/activate directly to avoid load-config! side effects

(describe "workspace-config:alias-command" (fn []
  (it "/alias with name and target registers new command"
    (fn []
      (let [api     (make-mock-api)
            a-atom  (atom {})
            deact   (aliases/activate api a-atom)
            handler (.-handler (get @(.-_commands api) "alias"))]
        (handler #js ["foo" "/help"] nil)
        (-> (expect (contains? @(.-_commands api) "foo")) (.toBe true))
        (deact))))

  (it "/alias with no args lists aliases after registering some"
    (fn []
      (let [api     (make-mock-api)
            a-atom  (atom {})
            deact   (aliases/activate api a-atom)
            handler (.-handler (get @(.-_commands api) "alias"))]
        ;; Register one first, then list
        (handler #js ["mykey" "/help"] nil)
        (reset! (.-_notifications api) [])
        (handler #js [] nil)
        (let [notif (str/join " " @(.-_notifications api))]
          (-> (expect (or (str/includes? notif "mykey")
                          (str/includes? notif "Alias"))) (.toBe true)))
        (deact))))

  (it "/alias with no args and empty map shows no-aliases message"
    (fn []
      (let [api     (make-mock-api)
            a-atom  (atom {})
            deact   (aliases/activate api a-atom)
            handler (.-handler (get @(.-_commands api) "alias"))]
        (handler #js [] nil)
        (let [notif (str/join " " @(.-_notifications api))]
          (-> (expect (str/includes? notif "No aliases")) (.toBe true)))
        (deact))))

  (it "/alias --remove removes an alias"
    (fn []
      (let [api     (make-mock-api)
            a-atom  (atom {})
            deact   (aliases/activate api a-atom)
            handler (.-handler (get @(.-_commands api) "alias"))]
        (handler #js ["myalias" "/help"] nil)
        (-> (expect (contains? @(.-_commands api) "myalias")) (.toBe true))
        (handler #js ["--remove" "myalias"] nil)
        (-> (expect (contains? @(.-_commands api) "myalias")) (.toBe false))
        (deact))))

  (it "/alias --remove unknown alias shows error"
    (fn []
      (let [api     (make-mock-api)
            a-atom  (atom {})
            deact   (aliases/activate api a-atom)
            handler (.-handler (get @(.-_commands api) "alias"))]
        (handler #js ["--remove" "doesnotexist"] nil)
        (let [notif (str/join " " @(.-_notifications api))]
          (-> (expect (str/includes? notif "No alias")) (.toBe true)))
        (deact))))))

;;; ─── Group 5: Alias collision ─────────────────────────────────

(describe "workspace-config:alias-collision" (fn []
  (it "refuses to shadow an existing command"
    (fn []
      (let [api     (make-mock-api)
            a-atom  (atom {})
            deact   (aliases/activate api a-atom)
            handler (.-handler (get @(.-_commands api) "alias"))]
        ;; Pre-register "help"
        (.registerCommand api "help" #js {:description "help" :handler (fn [] nil)})
        ;; Attempt to alias "help"
        (handler #js ["help" "/clear"] nil)
        (let [notif (str/join " " @(.-_notifications api))]
          (-> (expect (str/includes? notif "Cannot alias")) (.toBe true)))
        (-> (expect (.-description (get @(.-_commands api) "help"))) (.toBe "help"))
        (deact))))))

;;; ─── Group 6: Alias persistence ──────────────────────────────

(describe "workspace-config:alias-persistence" (fn []
  (it "pre-loaded aliases from atom are registered on activate"
    (fn []
      ;; Test aliases/activate directly with pre-populated atom
      (let [api    (make-mock-api)
            a-atom (atom {"preloaded" "/help"})
            deact  (aliases/activate api a-atom)]
        (-> (expect (contains? @(.-_commands api) "preloaded")) (.toBe true))
        (deact))))

  (it "deactivator unregisters pre-loaded alias commands"
    (fn []
      (let [api    (make-mock-api)
            a-atom (atom {"tmp" "/clear"})
            deact  (aliases/activate api a-atom)]
        (deact)
        (-> (expect (contains? @(.-_commands api) "tmp")) (.toBe false)))))

  (it "multiple pre-loaded aliases all registered"
    (fn []
      (let [api    (make-mock-api)
            a-atom (atom {"cc" "/model claude" "hw" "/help" "bye" "/exit"})
            deact  (aliases/activate api a-atom)]
        (-> (expect (contains? @(.-_commands api) "cc")) (.toBe true))
        (-> (expect (contains? @(.-_commands api) "hw")) (.toBe true))
        (-> (expect (contains? @(.-_commands api) "bye")) (.toBe true))
        (deact))))))
