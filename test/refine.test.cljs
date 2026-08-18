(ns refine.test
  "Tests for /refine — session mining and the propose-only flow.

   The load-bearing test is `rediscovers a failure that is definitely there`:
   a miner that cannot find a known-bad session will not find new ones."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.extensions.refine.mining :as mining]
            ["./agent/extensions/refine/index.mjs" :as refine]))

(defn- turn [i tools]
  (concat [{:role "user" :content (str "q" i)}]
          (repeat tools {:role "tool_call" :content "{}"
                         :metadata {:tool-name "bash" :args {:command (str "echo " i)}}})
          [{:role "assistant" :content "ok"}]))

(describe "mining/no-op-turns"
          (fn []
            (it "flags a run of turns that did no work"
                (fn []
                  ;; The collapse signature: the model still talks, stops acting.
                  (let [entries (vec (concat (mapcat #(turn % 2) (range 3))
                                             (mapcat #(turn % 0) (range 3 9))))
                        s (mining/no-op-turns entries)]
                    (-> (expect (some? s)) (.toBe true))
                    (-> (expect (:longest-run s)) (.toBe 6)))))

            (it "stays quiet when work is being done"
                (fn []
                  ;; One quiet turn is an answer, not a collapse.
                  (let [entries (vec (mapcat #(turn % 2) (range 6)))]
                    (-> (expect (mining/no-op-turns entries)) (.toBeFalsy)))))))

(describe "mining/repeated-commands"
          (fn []
            (it "collapses nyma's own wrappers before comparing"
                (fn []
                  ;; env_filter and cwd_manager add these; comparing raw strings
                  ;; would make every command look unique.
                  (let [mk (fn [pre] {:role "tool_call" :content "{}"
                                      :metadata {:tool-name "bash"
                                                 :args {:command (str pre "bin/rails test")}}})
                        entries [(mk "unset LD_PRELOAD FOO 2>/dev/null ; ")
                                 (mk "cd '/repo' && ")
                                 (mk "cd '/repo' && cd '/repo' && ")]
                        s (mining/repeated-commands entries)]
                    (-> (expect (some? s)) (.toBe true))
                    (-> (expect (:count (first (:items s)))) (.toBe 3)))))))

(describe "mining/re-read-files"
          (fn []
            (it "counts repeat reads of the same path"
                (fn []
                  (let [entries (vec (repeat 5 {:role "tool_call" :content "x"
                                                :metadata {:tool-name "read"
                                                           :args {:path "/a.rb"}}}))
                        s (mining/re-read-files entries)]
                    (-> (expect (:redundant s)) (.toBe 4)))))))

(describe "mining/mine"
          (fn []
            (it "returns nothing for a healthy session"
                (fn []
                  ;; A tool that always has an opinion becomes noise.
                  (-> (expect (count (mining/mine (vec (mapcat #(turn % 2) (range 4))))))
                      (.toBe 0))))))

;;; ─── the acid test ───────────────────────────────────────────────────────

(describe "mining against a real failed session"
          (fn []
            (it "rediscovers a failure that is definitely there"
                (fn []
                  (let [bak (->> (try (fs/readdirSync (path/join (os/homedir) ".nyma" "sessions"))
                                      (catch :default _ #js []))
                                 vec
                                 (filter #(.includes % "pre-repair"))
                                 first)]
                    (if-not bak
                      ;; Archive pruned — skip rather than fail on another machine.
                      (-> (expect true) (.toBe true))
                      (let [p (path/join (os/homedir) ".nyma" "sessions" bak)
                            entries (->> (.split (str (fs/readFileSync p "utf8")) "\n")
                                         (filter seq)
                                         (keep (fn [l] (try (js/JSON.parse l) (catch :default _ nil))))
                                         vec)
                            signals (mining/mine entries)
                            kinds   (set (map :kind signals))]
                        ;; Found by hand first: a long run of dead turns and
                        ;; heavy re-reading. The miner must find both alone.
                        (-> (expect (contains? kinds :no-op-turns)) (.toBe true))
                        (-> (expect (contains? kinds :re-read-files)) (.toBe true))
                        (-> (expect (>= (:longest-run (first (filter #(= :no-op-turns (:kind %)) signals))) 10))
                            (.toBe true)))))))))

;;; ─── the extension contract ──────────────────────────────────────────────

(describe "refine extension"
          (fn []
            (it "registers a command and ZERO model-facing tools"
                (fn []
                  ;; The property that makes this safe to add: nyma exposes ~36
                  ;; tools against a 30-50 ceiling for small models, where each
                  ;; extra similar tool costs 1-8% selection accuracy.
                  (let [tools (atom 0) cmds (atom [])
                        api #js {:registerCommand (fn [n _] (swap! cmds conj n))
                                 :unregisterCommand (fn [_] nil)
                                 :registerTool (fn [& _] (swap! tools inc))}
                        off ((.-default refine) api)]
                    (-> (expect @cmds) (.toEqual #js ["refine"]))
                    (-> (expect @tools) (.toBe 0))
                    (off))))

            (it "writes nothing to MEMORY.md without an explicit choice"
                (^:async fn []
                 ;; Propose, never apply.
                 (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "refine-"))
                       prev (js/process.cwd)
                       sess (path/join dir "s.jsonl")
                       entries (vec (concat (mapcat #(turn % 2) (range 2))
                                            (mapcat #(turn % 0) (range 2 9))))]
                   (fs/writeFileSync sess (str/join "\n" (map #(js/JSON.stringify (clj->js %)) entries)))
                   (js/process.chdir dir)
                   (try
                     (js-await ((.-run_refine refine)
                                #js {:ui #js {:available true
                                              :notify (fn [& _] nil)
                                              :select (fn [& _] (js/Promise.resolve "Cancel"))}}
                                #js [sess]))
                     (-> (expect (fs/existsSync (path/join dir ".nyma" "memory" "MEMORY.md"))) (.toBe false))
                     ;; …but the report IS written, so the finding is not lost.
                     (-> (expect (pos? (count (fs/readdirSync (path/join dir ".nyma" "refine"))))) (.toBe true))
                     (finally
                       (js/process.chdir prev)
                       (try (fs/rmSync dir #js {:recursive true :force true}) (catch :default _ nil))))))))) 
