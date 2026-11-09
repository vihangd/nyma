(ns skills-activation.test
  "`triggers` and `paths` in SKILL.md frontmatter were parsed and read by
   nothing. Now a trigger phrase in the prompt, or a touched path matching a
   skill's globs, injects that skill's body for the turn."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.resources.skills :as sk]
            [agent.utils.decide :as decide]))

(def ^:private skills
  {"deploy"  {:name "deploy" :triggers ["deploy" "ship it"] :body "DEPLOY BODY"}
   "cljs"    {:name "cljs" :paths ["src/**/*.cljs"] :body "CLJS BODY"}
   "hidden"  {:name "hidden" :triggers ["deploy"] :disable-model-invocation true :body "NO"}
   "plain"   {:name "plain" :body "never auto"}})

(defn ^:async with-judge [answer body]
  (let [h    (js/Promise.withResolvers)
        orig js/globalThis.fetch
        key  (.. js/process -env -TYPESAFE_API_KEY)
        sent (atom nil)]
    (aset (.-env js/process) "TYPESAFE_API_KEY" "k")
    (set! js/globalThis.fetch
          (fn [_ o]
            (reset! sent (js/JSON.parse (.-body o)))
            (if answer
              (js/Promise.resolve #js {:ok true :json (fn [] (js/Promise.resolve #js {:answers answer}))})
              (js/Promise.reject (js/Error. "down")))))
    (decide/configure! {:judge {}})
    (decide/reset-session!)
    (try (js-await (body sent))
         (finally
           (set! js/globalThis.fetch orig)
           (if key (aset (.-env js/process) "TYPESAFE_API_KEY" key) (js-delete (.-env js/process) "TYPESAFE_API_KEY"))
           (decide/configure! nil)
           (decide/reset-session!)))))

(def ^:private described
  {"tdd"    {:description "Test-driven development workflow" :body "TDD"}
   "pdf"    {:description "Read and fill PDF forms" :body "PDF"}
   "repo"   {:description "This repo's conventions" :scope "project" :body "R"}
   "secret" {:description "Hidden" :disable-model-invocation true}
   "bare"   {:body "no description"}})

(defn ^:async test-judged []
  (js-await
   (with-judge #js {:tdd #js {:type "noul" :noul 0.9} :pdf #js {:type "noul" :noul 0.2}}
               ^:async (fn [sent]
                         (-> (expect (clj->js (js-await (sk/judged-skills described "write a test")))) (.toEqual #js ["tdd"]))
                         (-> (expect (.sort (js/Object.keys (.-questions @sent)))) (.toEqual #js ["pdf" "tdd"])))))
  (js-await
   (with-judge nil ^:async (fn [_] (-> (expect (js-await (sk/judged-skills described "write a test"))) (.toBeNil))))))

(describe "skills:auto-activation" (fn []
  (it "a trigger phrase in the prompt activates the skill, case-insensitively"
      (fn []
        (-> (expect (sk/auto-activated skills "please SHIP IT today" #{})) (.toEqual (clj->js ["deploy"])))))

  (it "a touched path matching a skill's glob activates it"
      (fn []
        (-> (expect (sk/auto-activated skills "hello" #{"src/agent/loop.cljs"})) (.toEqual (clj->js ["cljs"])))
        (-> (expect (sk/auto-activated skills "hello" #{"README.md"})) (.toEqual (clj->js [])))))

  (it "hidden skills and skills with neither field never auto-activate"
      (fn []
        (-> (expect (sk/auto-activated skills "deploy now" #{"src/x.cljs"})) (.toEqual (clj->js ["cljs" "deploy"])))))

  (it "activation-block carries the bodies, nil when nothing matched"
      (fn []
        (-> (expect (sk/activation-block skills ["deploy"])) (.toContain "DEPLOY BODY"))
        (-> (expect (sk/activation-block skills [])) (.toBeNil))))

  (it "trigger matches lead the judge's picks; path matches lead both"
      (fn []
        (-> (expect (sk/auto-activated skills "please ship it" #{"src/x.cljs"} nil ["plain" "deploy"]))
            (.toEqual (clj->js ["cljs" "deploy" "plain"])))
        ;; three picks cannot crowd out a phrase the user typed
        (-> (expect (sk/auto-activated (assoc skills "a" {} "b" {} "c" {}) "ship it" #{} nil ["a" "b" "c"]))
            (.toEqual (clj->js ["deploy" "a" "b"])))
        ;; picked AND triggered: once
        (-> (expect (sk/auto-activated skills "ship it" #{} nil ["deploy"])) (.toEqual (clj->js ["deploy"])))
        ;; picked nothing: a trigger phrase the user typed still loads its skill
        (-> (expect (sk/auto-activated skills "ship it" #{} nil [])) (.toEqual (clj->js ["deploy"])))
        ;; never a hidden skill, whatever was picked
        (-> (expect (sk/auto-activated skills "x" #{} nil ["hidden"])) (.toEqual (clj->js [])))))

  (it "asks Jev about described, visible, non-project skills only; nil when it fails" test-judged)))
