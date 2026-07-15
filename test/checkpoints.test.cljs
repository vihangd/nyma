(ns checkpoints.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.extensions.checkpoints.shared :as shared]
            [agent.extensions.checkpoints.index :as cp]))

(defn- tmp-file [content]
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "nyma-cp-"))
        f   (path/join dir "f.txt")]
    (when content (fs/writeFileSync f content))
    f))

(describe "checkpoints shared" (fn []
                                 (it "snapshot captures pre-turn content once per turn"
                                     (fn []
                                       (let [cps (atom {})
                                             f   (tmp-file "v1")]
                                         (shared/snapshot! cps 0 f)
                                         (fs/writeFileSync f "v2")
                                         (shared/snapshot! cps 0 f)          ;; second edit same turn — keep v1
                                         (-> (expect (get-in @cps [0 f])) (.toBe "v1")))))

                                 (it "restore! writes the newest turn back and pops it"
                                     (fn []
                                       (let [cps (atom {})
                                             f   (tmp-file "v1")]
                                         (shared/snapshot! cps 0 f)
                                         (fs/writeFileSync f "v2")
                                         (shared/snapshot! cps 1 f)          ;; turn 1 pre-state = v2
                                         (fs/writeFileSync f "v3")
                                         (let [[turn paths] (shared/restore! cps)]
                                           (-> (expect (js/Number turn)) (.toBe 1))
                                           (-> (expect (count paths)) (.toBe 1))
                                           (-> (expect (fs/readFileSync f "utf8")) (.toBe "v2")))
                                         (shared/restore! cps)
                                         (-> (expect (fs/readFileSync f "utf8")) (.toBe "v1"))
                                         (-> (expect (shared/restore! cps)) (.toBeFalsy)))))

                                 (it "restore! deletes files that did not exist pre-turn"
                                     (fn []
                                       (let [cps (atom {})
                                             f   (tmp-file nil)]
                                         (shared/snapshot! cps 0 f)          ;; :absent
                                         (fs/writeFileSync f "created")
                                         (shared/restore! cps)
                                         (-> (expect (fs/existsSync f)) (.toBe false)))))))

(describe "checkpoints extension" (fn []
                                    (it "snapshots on before_tool_call and rewinds via /rewind"
                                        (fn []
                                          (let [handlers (atom {})
                                                commands (atom {})
                                                api      #js {:on  (fn [evt h] (swap! handlers assoc evt h))
                                                              :off (fn [evt _] (swap! handlers dissoc evt))
                                                              :registerCommand   (fn [name spec] (swap! commands assoc name spec))
                                                              :unregisterCommand (fn [name] (swap! commands dissoc name))}
                                                _        (cp/activate api)
                                                f        (tmp-file "original")]
          ;; Agent edits the file this turn: capture → execute → confirm
                                            ((get @handlers "before_tool_call")
                                             #js {:toolName "edit" :args #js {:path f}} nil)
                                            (fs/writeFileSync f "mutated")
                                            ((get @handlers "tool_complete")
                                             #js {:toolName "edit" :args #js {:path f}
                                                  :cancelled false :isError false} nil)
                                            ((get @handlers "turn_finalize") #js {} nil)
          ;; /rewind restores
                                            (let [rewind (.-handler (get @commands "rewind"))
                                                  notes  (atom [])]
                                              (rewind [] #js {:ui #js {:notify (fn [m _l] (swap! notes conj m))}})
                                              (-> (expect (fs/readFileSync f "utf8")) (.toBe "original"))
                                              (-> (expect (first @notes)) (.toContain "Rewound"))))))

                                    (it "a denied edit creates no phantom rewind point"
                                        (fn []
                                          (let [handlers (atom {})
                                                commands (atom {})
                                                api      #js {:on  (fn [evt h] (swap! handlers assoc evt h))
                                                              :off (fn [evt _] (swap! handlers dissoc evt))
                                                              :registerCommand   (fn [name spec] (swap! commands assoc name spec))
                                                              :unregisterCommand (fn [name] (swap! commands dissoc name))}
                                                _        (cp/activate api)
                                                f        (tmp-file "untouched")]
          ;; before_tool_call fires, but the call was DENIED: tool_complete
          ;; arrives cancelled — no promotion.
                                            ((get @handlers "before_tool_call")
                                             #js {:toolName "edit" :args #js {:path f}} nil)
                                            ((get @handlers "tool_complete")
                                             #js {:toolName "edit" :args #js {:path f}
                                                  :cancelled true :isError false} nil)
                                            ((get @handlers "turn_finalize") #js {} nil)
                                            (let [rewind (.-handler (get @commands "rewind"))
                                                  notes  (atom [])]
                                              (rewind [] #js {:ui #js {:notify (fn [m _l] (swap! notes conj m))}})
                                              (-> (expect (first @notes)) (.toContain "Nothing to rewind"))))))))
