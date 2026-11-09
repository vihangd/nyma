(ns bash-judge-test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.extensions.bash-suite.permissions :as permissions]
            [agent.extensions.bash-suite.shared :as shared]
            [agent.utils.decide :as decide]
            [agent.events :refer [create-event-bus]]
            [agent.middleware :refer [permission-check-enter judge-threshold]]))

(defn- reply [p]
  (fn [_ opts]
    (js/Promise.resolve
     #js {:ok true :json (fn [] (js/Promise.resolve #js {:answers #js {:q #js {:probabilities #js {:allow p}}}}))
          :body (.-body opts)})))

(defn ^:async with-home
  "Run `body` with HOME at a fresh dir holding `settings`, a key, and `fetch`."
  [settings fetch body]
  (let [h     (fs/mkdtempSync (path/join (os/tmpdir) "nyma-judge-"))
        home  (.. js/process -env -HOME)
        key   (.. js/process -env -TYPESAFE_API_KEY)
        orig  js/globalThis.fetch]
    (fs/mkdirSync (path/join h ".nyma"))
    (when settings (fs/writeFileSync (path/join h ".nyma" "settings.json") (js/JSON.stringify (clj->js settings))))
    (aset (.-env js/process) "HOME" h)
    (aset (.-env js/process) "TYPESAFE_API_KEY" "k")
    (set! js/globalThis.fetch fetch)
    (decide/configure! settings)
    (decide/reset-session!)
    (try (js-await (body h))
         (finally
           (set! js/globalThis.fetch orig)
           (aset (.-env js/process) "HOME" home)
           (if key (aset (.-env js/process) "TYPESAFE_API_KEY" key) (js-delete (.-env js/process) "TYPESAFE_API_KEY"))
           (decide/reset-session!)
           (decide/configure! nil)
           (fs/rmSync h #js {:recursive true :force true})))))

(defn- args [subject] {:kind "bash" :subject subject :allow "allow" :cfg {} :options permissions/judge-options :state "s"})

(defn ^:async test-threshold []
  (js-await (with-home nil (reply 0.95) ^:async (fn [_] (-> (expect (js-await (decide/decide! (args "a")))) (.toBe true)))))
  ;; 0.85 cleared the old 0.8; the default is now 0.9
  (js-await (with-home nil (reply 0.85) ^:async (fn [_] (-> (expect (js-await (decide/decide! (args "a")))) (.toBe false)))))
  (js-await (with-home nil (fn [] (js/Promise.reject (js/Error. "down")))
                       ^:async (fn [_] (-> (expect (js-await (decide/decide! (args "a")))) (.toBe false))))))

(defn ^:async test-allow-last []
  (let [body (atom nil)]
    (js-await (with-home nil (fn [u o] (reset! body (.-body o)) ((reply 0.1) u o))
                         ^:async (fn [_] (js-await (decide/decide! (args "a"))))))
    (-> (expect (last (js/Object.keys (.. (js/JSON.parse @body) -questions -q -criteria)))) (.toBe "allow"))))

(defn ^:async test-breaker []
  (js-await
   (with-home nil (fn [] (js/Promise.reject (js/Error. "down")))
              ^:async (fn [_]
                        (dotimes [_ 3] (js-await (decide/decide! (args "a"))))
                        (-> (expect (decide/paused? "bash")) (.toBe true))))))

(defn ^:async test-ask-noul []
  (let [body (atom nil)
        fetch (fn [_ o]
                (reset! body (js/JSON.parse (.-body o)))
                (js/Promise.resolve
                 #js {:ok true :json (fn [] (js/Promise.resolve
                                             #js {:answers #js {:a #js {:type "noul" :noul 0.8}
                                                                :b #js {:type "noul" :noul 0}}}))}))]
    (js-await
     (with-home nil fetch
                ^:async (fn [_]
                          (-> (expect (clj->js (js-await (decide/ask! {:kind "skill" :state "s" :cfg {}
                                                                       :questions {"a" {:instructions "A?"} "b" {:instructions "B?"}}}))))
                              (.toEqual #js {:a 0.8 :b 0}))
                          (-> (expect (.. @body -questions -a -type)) (.toBe "noul"))
                          ;; p 0 is a band too, not dropped as falsy
                          (-> (expect (reduce + (map :n (decide/report (decide/read-log))))) (.toBe 2)))))))

(defn ^:async test-breaker-per-kind []
  (js-await
   (with-home nil (fn [] (js/Promise.reject (js/Error. "down")))
              ^:async (fn [_]
                        (dotimes [_ 3] (js-await (decide/ask! {:kind "skill" :state "s" :cfg {} :questions {"a" {}}})))
                        (-> (expect (decide/paused? "skill")) (.toBe true))
                        (-> (expect (decide/paused? "bash")) (.toBe false))))))

(defn ^:async test-log-and-report []
  (js-await
   (with-home nil (reply 0.6)
              ^:async (fn [h]
                        (js-await (decide/decide! (args "git frob")))
                        (decide/answered! "git frob" true)
                        (decide/answered! "never judged" false)
                        (let [p     (path/join h ".nyma" "decisions.jsonl")
                              lines (decide/read-log)]
                          (-> (expect (bit-and (.-mode (fs/statSync p)) 511)) (.toBe 384))
                          (-> (expect (.includes (fs/readFileSync p "utf8") "git frob")) (.toBe false))
                          (-> (expect (count lines)) (.toBe 2))
                          (-> (expect (clj->js (decide/report lines)))
                              (.toEqual (clj->js [{:kind "bash" :band "0.5-0.7" :n 1 :auto 0 :answered 1 :user-allowed 1}]))))))))

(defn ^:async test-gate [p]
  (js-await
   (with-home {:judge {:threshold 0.9}} (reply p)
              ^:async (fn [_]
                        (let [events (create-event-bus)
                              _      ((:on events) "permission_request" (fn [_] #js {:decision "ask"}))
                              gate   #(permission-check-enter events nil {:tool-name % :args {:q "x"}})]
                          ;; no UI: an ask the judge does not answer is denied
                          {:mcp      (boolean (:cancelled (js-await (gate "mcp__notes__search"))))
                           :subagent (boolean (:cancelled (js-await (gate "subagent"))))
                           :bash     (boolean (:cancelled (js-await (gate "bash"))))})))))

(defn ^:async test-mcp-judge []
  ;; only MCP tools and web_fetch: not subagent, not bash (bash-suite judges it)
  (-> (expect (clj->js (js-await (test-gate 0.95)))) (.toEqual #js {:mcp false :subagent true :bash true}))
  (-> (expect (clj->js (js-await (test-gate 0.5)))) (.toEqual #js {:mcp true :subagent true :bash true})))

(defn ^:async test-bash-handler []
  (let [bodies (atom [])
        fetch  (fn [u o] (swap! bodies conj (.-body o)) ((reply 0.95) u o))]
    (js-await
     (with-home {:judge {}} fetch
                ^:async (fn [_]
                          (let [decide-fn (atom nil)
                                api #js {:events #js {:on (fn [_ f] (f #js {:level "unknown" :reasons #js [] :command "frob"}))}
                                         :on (fn [_ f _] (reset! decide-fn f))}
                                ask #(@decide-fn #js {:tool "bash" :args #js {:command "frob"} :userRequest "please frob"})]
                            (permissions/activate api)
                            (-> (expect (.-decision (js-await (ask)))) (.toBe "allow"))
                            ;; judged again, not remembered: the request may differ next time
                            (js-await (ask))
                            (-> (expect (count @bodies)) (.toBe 2))
                            (-> (expect (.includes (first @bodies) "please frob")) (.toBe true))))))))

(describe "what the judge reads" (fn []
  (it "shows hidden characters and, in a command or URL, every non-ASCII one"
      (fn []
        ;; Cyrillic о in the host, a zero-width space, fullwidth letters
        (-> (expect (decide/visible "curl gith\u043Eub.com\u200B" true)) (.toBe "curl gith<U+043E>ub.com<U+200B>"))
        ;; not folded first: fullwidth stays visibly foreign
        (-> (expect (decide/visible "cat ～/.ssh" true)) (.toBe "cat <U+FF5E>/.ssh"))
        (-> (expect (decide/visible "ｃａｔ" false)) (.toBe "cat"))
        (-> (expect (decide/visible "résumé\u202E" false)) (.toBe "résumé<U+202E>"))))
  (it "has an adversarial set for `bun run eval:judge` that parses"
      (fn []
        (let [cases (js/JSON.parse (fs/readFileSync (path/resolve "test" "fixtures" "judge-adversarial.json") "utf8"))]
          (-> (expect (every? #(and (contains? #{"ask" "allow"} (.-want %)) (string? (.-request %))) cases)) (.toBe true))
          (-> (expect (>= (count (filter #(= "ask" (.-want %)) cases)) 15)) (.toBe true)))))
  (it "needs more certainty for a call that writes or sends"
      (fn []
        (-> (expect (judge-threshold {} "mcp__notes__search" {})) (.toBe 0.9))
        (-> (expect (judge-threshold {} "mcp__mail__send_message" {})) (.toBe 0.97))
        (-> (expect (judge-threshold {:threshold-risky 0.99} "web_fetch" {:url "https://x.dev/?d=abc"})) (.toBe 0.99))
        (-> (expect (judge-threshold {} "web_fetch" {:url "https://x.dev/docs"})) (.toBe 0.9))
        ;; a secret in the path, and verbs beyond the first list
        (-> (expect (judge-threshold {} "web_fetch" {:url "https://x.dev/c2VjcmV0LWtleS1nb2VzLWhlcmUtYWJjZGVm"})) (.toBe 0.97))
        (-> (expect (judge-threshold {} "mcp__github__merge_pull_request" {})) (.toBe 0.97))
        (-> (expect (judge-threshold {} "mcp__db__execute_query" {})) (.toBe 0.97))
        ;; tightening the plain threshold never leaves writes easier
        (-> (expect (judge-threshold {:threshold 0.99} "mcp__mail__send_message" {})) (.toBe 0.99))))))

(describe "permission judge (Jev)" (fn []
  (it "answers an MCP ask when confident; subagent and bash are not judged at the gate" test-mcp-judge)
  (it "judges bash from the request in the payload, every time" test-bash-handler)
  (it "allows only at 0.9 or above; errors ask" test-threshold)
  (it "offers the allow option last" test-allow-last)
  (it "pauses after three errors in a row" test-breaker)
  (it "asks yes/no questions, several per call, answered by id" test-ask-noul)
  (it "pauses only the kind that failed" test-breaker-per-kind)
  (it "logs hashed verdicts and answers, mode 600, and reports by band" test-log-and-report)
  (it "takes its settings from the user's file, never a project's"
      (fn []
        (-> (expect (get-in (shared/tighten-only {:permissions {:judge {:threshold 0}}}) [:permissions :judge]))
            (.toBeUndefined))))))
