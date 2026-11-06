(ns pilish-contract.test
  "What pilish (the Emacs frontend) needs from `--mode pi-rpc`, over the real
   process and stdio. Its elisp blocks on the matching id, so a command with no
   response hangs Emacs, and any non-JSON line on stdout breaks the channel. No
   model is called; `scripts/test-pilish.sh` runs pilish itself, with one."
  (:require ["bun:test" :refer [describe it expect beforeAll afterAll]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.ui.think-tag-parser :as think]
            [agent.modes.pi-rpc :refer [subscribe-events! new-st]]))

(def ^:private cli (path/resolve (js/process.cwd) "dist" "agent" "cli.mjs"))

(def ^:private rig (atom nil))

(defn- send! [cmd]
  (let [{:keys [proc]} @rig]
    (.write (.-stdin proc) (str (js/JSON.stringify (clj->js cmd)) "\n"))
    (.flush (.-stdin proc))))

(defn ^:async read-loop [proc lines]
  (let [reader (.getReader (.-stdout proc))
        dec    (js/TextDecoder.)
        buf    (atom "")]
    (loop []
      (let [{:keys [done value]} (js-await (.read reader))]
        (when-not done
          (swap! buf str (.decode dec value))
          (let [parts (.split @buf "\n")]
            (reset! buf (.pop parts))
            (swap! lines into parts))
          (recur))))))

(defn- parsed [line] (try (js/JSON.parse line) (catch :default _ nil)))

(defn ^:async rpc
  "Send `cmd` with a fresh id and wait for its response."
  [cmd]
  (let [id  (str "t" (swap! (:ids @rig) inc))
        end (+ (js/Date.now) 8000)]
    (send! (assoc cmd :id id))
    (loop []
      (if-let [r (some (fn [l] (let [o (parsed l)]
                                 (when (and o (= "response" (.-type o)) (= id (.-id o))) o)))
                       @(:lines @rig))]
        r
        (if (> (js/Date.now) end)
          (throw (js/Error. (str "no response to " (:type cmd))))
          (do (js-await (js/Bun.sleep 20)) (recur)))))))

(beforeAll
 ^:async (fn []
   (let [home  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-pilish-"))
         lines (atom [])
         proc  (js/Bun.spawn #js ["bun" cli "--mode" "pi-rpc"]
                             #js {:cwd home :stdin "pipe" :stdout "pipe" :stderr "ignore"
                                  :env (js/Object.assign #js {} js/process.env
                                                         #js {:HOME home :ANTHROPIC_API_KEY "test"})})]
     (reset! rig {:proc proc :home home :lines lines :ids (atom 0)})
     (read-loop proc lines)
     ;; the first answer also says the process came up
     (js-await (rpc {:type "get_state"})))) 30000)

(afterAll
 (fn []
   (.kill (:proc @rig))
   (fs/rmSync (:home @rig) #js {:recursive true :force true})))

(defn- ok [r]
  (-> (expect #js [(.-command r) (.-success r)]) (.toEqual #js [(.-command r) true]))
  (.-data r))

(describe "pilish: the wire"
  (fn []
    (it "get_state has what the header and session logic read"
        ^:async (fn []
          (let [d (ok (js-await (rpc {:type "get_state"})))]
            (-> (expect (.-model d)) (.toBeTruthy))
            (-> (expect (.-thinkingLevel d)) (.toBeTruthy))
            (-> (expect (.-messageCount d)) (.toBe 0))
            (-> (expect (string? (.-sessionFile d))) (.toBe true)))))

    (it "get_commands carries sourceInfo.scope, or the menu has no location"
        ^:async (fn []
          (let [cmds (.-commands (ok (js-await (rpc {:type "get_commands"}))))
                c    (aget cmds 0)]
            (-> (expect (pos? (count cmds))) (.toBe true))
            (-> (expect (.-name c)) (.toBeTruthy))
            (-> (expect (.-source c)) (.toBeTruthy))
            (-> (expect (.-scope (.-sourceInfo c))) (.toBeTruthy)))))

    (it "every read command pilish sends is answered"
        ^:async (fn []
          (doseq [t ["get_available_models" "get_available_thinking_levels" "get_session_stats"
                     "get_messages" "get_last_assistant_text" "get_fork_messages" "clear_queue"
                     "abort" "cycle_thinking_level"]]
            (ok (js-await (rpc {:type t}))))
          (-> (expect (js/Array.isArray (.-messages (ok (js-await (rpc {:type "get_fork_messages"}))))))
              (.toBe true))))

    (it "a rename shows in get_state and is announced"
        ^:async (fn []
          (ok (js-await (rpc {:type "set_session_name" :name "Named"})))
          (-> (expect (.-sessionName (ok (js-await (rpc {:type "get_state"}))))) (.toBe "Named"))
          (-> (expect (boolean (some (fn [l] (let [o (parsed l)]
                                               (and o (= "session_info_changed" (.-type o))
                                                    (= "Named" (.-name o)))))
                                     @(:lines @rig))))
              (.toBe true))))

    (it "new_session reports not cancelled, and starts empty"
        ^:async (fn []
          (-> (expect (.-cancelled (ok (js-await (rpc {:type "new_session"}))))) (.toBe false))
          (-> (expect (.-messageCount (ok (js-await (rpc {:type "get_state"}))))) (.toBe 0))))

    (it "a bad command is still answered, with the same id"
        ^:async (fn []
          (let [r (js-await (rpc {:type "switch_session" :sessionPath "/nonexistent.jsonl"}))]
            (-> (expect (.-success r)) (.toBe false))
            (-> (expect (.-error r)) (.toBeTruthy)))
          (-> (expect (.-success (js-await (rpc {:type "no_such_command"})))) (.toBe false))))

    (it "stdout carried nothing but JSONL"
        (fn []
          (-> (expect (vec (remove parsed (filter seq @(:lines @rig))))) (.toEqual #js []))))))

;;; ─── inline <think> ──────────────────────────────────────────

(defn- scan-all [chunks]
  (reduce (fn [[st out] c]
            (let [[st' ps] (think/scan st c)] [st' (into out ps)]))
          [nil []] chunks))

(defn- fake-agent []
  (let [handlers (atom {})]
    {:events {:on  (fn [e h] (swap! handlers update e (fnil conj []) h) nil)
              :off (fn [_ _] nil)}
     :follow-queue (atom [])
     :__handlers handlers}))

(defn- fire! [agent e data] (mapv (fn [h] (h data)) (get @(:__handlers agent) e)))

(defn- capture [f]
  (let [real (.-log js/console) out (atom [])]
    (set! (.-log js/console) (fn [line] (swap! out conj (js/JSON.parse (str line)))))
    (try (f) (finally (set! (.-log js/console) real)))
    @out))

(defn- deltas [events kind]
  (apply str (keep (fn [e] (let [a (.-assistantMessageEvent e)]
                             (when (and a (= kind (.-type a))) (.-delta a))))
                   events)))

(describe "pilish: inline think tags"
  (fn []
    (it "scan finds a tag split across chunks and holds nothing back at the end"
        (fn []
          (let [[st out] (scan-all ["<thi" "nk>hm</th" "ink>answer"])]
            (-> (expect (clj->js out)) (.toEqual (clj->js [[:open] [:think "hm"] [:close] [:text "answer"]])))
            (-> (expect (:held st)) (.toBe "")))))

    (it "scan leaves a lone < and other tags as text"
        (fn []
          (let [[st out] (scan-all ["a < b " "<div>"])]
            (-> (expect (clj->js out)) (.toEqual (clj->js [[:text "a < b "] [:text "<div>"]])))
            (-> (expect (:held st)) (.toBe "")))))

    (it "a client sees thinking deltas, and no tag in the text it renders"
        (fn []
          (let [agent (fake-agent)
                st    (new-st)
                ev    (capture
                       (fn []
                         (let [unsub (subscribe-events! agent st)]
                           (fire! agent "agent_start" #js {})
                           (fire! agent "message_start" #js {})
                           (doseq [c ["<think>plan" "ning</think>" "PONG"]]
                             (fire! agent "message_update" #js {:type "text-delta" :text c}))
                           (fire! agent "message_end" #js {})
                           (fire! agent "turn_end" #js {:text "" :finishReason "stop" :toolResults #js []})
                           (when (fn? unsub) (unsub)))))
                end   (some #(when (= "message_end" (.-type %)) %) ev)
                types (mapv #(.-type %) (.-content (.-message end)))]
            (-> (expect (deltas ev "text_delta")) (.toBe "PONG"))
            (-> (expect (deltas ev "thinking_delta")) (.toBe "planning"))
            (-> (expect (clj->js types)) (.toEqual #js ["thinking" "text"])))))))
