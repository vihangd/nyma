(ns file-mentions.test
  "`@path` mentions: expansion on submit and the autocomplete listing."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:child_process" :as cp]
            ["@earendil-works/pi-tui" :refer [CombinedAutocompleteProvider]]
            [agent.ui.file-mentions :as fm]
            [agent.extensions.add-dir.index :as add-dir]
            [clojure.string :as str]))

(defn- fixture []
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "nyma-mentions-"))]
    (fs/mkdirSync (path/join dir "src"))
    (fs/writeFileSync (path/join dir "src" "main.cljs") "(ns main)")
    (fs/writeFileSync (path/join dir "notes.txt") "hello notes")
    (fs/writeFileSync (path/join dir "big.bin") (.repeat "x" (* 300 1024)))
    dir))

(describe "@file mentions / expansion" (fn []
                                         (it "expands an existing file into a <file> block with its relative path"
                                             (fn []
                                               (let [dir (fixture)
                                                     r   (fm/expand-mentions "look at @notes.txt please" dir)]
                                                 (-> (expect (:text r)) (.toBe "look at @notes.txt please\n\n<file path=\"notes.txt\">\nhello notes\n</file>"))
                                                 (-> (expect (:files r)) (.toEqual ["notes.txt"])))))

                                         (it "leaves emails and npm scopes untouched"
                                             (fn []
                                               (let [dir (fixture)
                                                     t   "mail user@example.com about @scope/pkg"
                                                     r   (fm/expand-mentions t dir)]
                                                 (-> (expect (:text r)) (.toBe t))
                                                 (-> (expect (:files r)) (.toEqual [])))))

                                         (it "skips a 300 KB file with a skipped marker"
                                             (fn []
                                               (let [dir (fixture)
                                                     r   (fm/expand-mentions "@big.bin" dir)]
                                                 (-> (expect (:text r)) (.toContain "<file path=\"big.bin\" skipped=\"300 KB\"/>"))
                                                 (-> (expect (:text r)) (.not.toContain "xxxx"))
                                                 (-> (expect (:skipped r)) (.toEqual ["big.bin"])))))

                                         (it "a directory yields a one-level listing"
                                             (fn []
                                               (let [dir (fixture)
                                                     r   (fm/expand-mentions "what is in @src" dir)]
                                                 (-> (expect (:text r)) (.toContain "<dir path=\"src\">\nmain.cljs\n</dir>")))))

                                         (it "a duplicate mention is appended once"
                                             (fn []
                                               (let [dir (fixture)
                                                     r   (fm/expand-mentions "@notes.txt and again @notes.txt" dir)]
                                                 (-> (expect (count (.split (:text r) "<file path=\"notes.txt\">"))) (.toBe 2))
                                                 (-> (expect (:files r)) (.toEqual ["notes.txt"])))))

                                         (it "trailing punctuation does not break resolution"
                                             (fn []
                                               (let [dir (fixture)]
                                                 (-> (expect (fm/find-mentions "see @notes.txt, then @src/main.cljs.")) (.toEqual ["notes.txt" "src/main.cljs"]))
                                                 (-> (expect (:files (fm/expand-mentions "see @notes.txt, then @src/main.cljs." dir)))
                                                     (.toEqual ["notes.txt" "src/main.cljs"])))))))

(defn- git-fixture []
  (let [dir (fixture)]
    (fs/writeFileSync (path/join dir ".gitignore") "big.bin\n")
    (cp/execSync "git init -q" #js {:cwd dir :stdio "ignore"})
    dir))

(defn- provider [dir fd-path]
  (fm/configure-provider! (new CombinedAutocompleteProvider #js [] dir nil) dir fd-path))

(defn- ^:async suggest [p text]
  (let [r (js-await (.getSuggestions p #js [text] 0 (count text) #js {:signal (.-signal (js/AbortController.))}))]
    (when r (mapv #(.-value %) (.-items r)))))

(describe "@file mentions / autocomplete provider" (fn []
                                                     (it "with an fd path the upstream provider receives it"
                                                         (fn []
                                                           (-> (expect (.-fdPath (provider (fixture) "/fake/bin/fd"))) (.toBe "/fake/bin/fd"))))

                                                     (it "without fd the fallback lists a git repo, honouring .gitignore"
                                                         (fn [] (js/Promise.
                                                                 (fn [resolve reject]
                                                                   (-> (suggest (provider (git-fixture) nil) "@")
                                                                       (.then (fn [items]
                                                                                (-> (expect items) (.toContain "@notes.txt"))
                                                                                (-> (expect items) (.toContain "@src/main.cljs"))
                                                                                (-> (expect items) (.not.toContain "@big.bin"))
                                                                                (resolve)))
                                                                       (.catch reject))))))

                                                     (it "fuzzy @srmn finds src/main.cljs"
                                                         (fn [] (js/Promise.
                                                                 (fn [resolve reject]
                                                                   (-> (suggest (provider (git-fixture) nil) "@srmn")
                                                                       (.then (fn [items]
                                                                                (-> (expect (first items)) (.toBe "@src/main.cljs"))
                                                                                (resolve)))
                                                                       (.catch reject))))))))

;; The listing is cached per cwd for a few seconds; a file that appears inside
;; the TTL is invisible until `reset-index!` — which is what `/add-dir` calls.
(defn- mock-add-dir-api []
  (let [cmds (atom {})]
    #js {:on                (fn [_ev _h] nil)
         :registerCommand   (fn [name opts] (swap! cmds assoc name opts))
         :unregisterCommand (fn [name] (swap! cmds dissoc name))
         :_commands         cmds}))

(describe "@file listing cache" (fn []
                                  (it "reset-index! makes the next suggestion re-list, so a file added mid-TTL shows up"
                                      (fn [] (js/Promise.
                                              (fn [resolve reject]
                                                (let [dir (fixture)
                                                      p   (provider dir nil)]
                                                  (-> (suggest p "@")
                                                      (.then (fn [items]
                                                               (-> (expect items) (.toContain "@notes.txt"))
                                                               (fs/writeFileSync (path/join dir "later.txt") "x")
                                                               (suggest p "@later")))
                                                      (.then (fn [items]
                                                               ;; Still cached: nothing knows about later.txt.
                                                               (-> (expect (vec (or items []))) (.not.toContain "@later.txt"))
                                                               (fm/reset-index!)
                                                               (suggest p "@later")))
                                                      (.then (fn [items]
                                                               (-> (expect items) (.toContain "@later.txt"))
                                                               (resolve)))
                                                      (.catch reject)))))))

                                  (it "/add-dir drops the cached listing"
                                      (fn []
                                        (let [dir     (fixture)
                                              extra   (fs/mkdtempSync (path/join (os/tmpdir) "nyma-extra-root-"))
                                              api     (mock-add-dir-api)
                                              _       ((.-default add-dir) api)
                                              handler (.-handler (get @(.-_commands api) "add-dir"))]
                                          (fm/file-index dir)
                                          (fs/writeFileSync (path/join dir "later.txt") "x")
                                          (-> (expect (fm/file-index dir)) (.not.toContain "later.txt"))
                                          (handler #js [extra] #js {:ui #js {:notify (fn [_m _l] nil)}})
                                          (-> (expect (fm/file-index dir)) (.toContain "later.txt")))))))
