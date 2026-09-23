(ns skills-activation.test
  "`triggers` and `paths` in SKILL.md frontmatter were parsed and read by
   nothing. Now a trigger phrase in the prompt, or a touched path matching a
   skill's globs, injects that skill's body for the turn."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.resources.skills :as sk]))

(def ^:private skills
  {"deploy"  {:name "deploy" :triggers ["deploy" "ship it"] :body "DEPLOY BODY"}
   "cljs"    {:name "cljs" :paths ["src/**/*.cljs"] :body "CLJS BODY"}
   "hidden"  {:name "hidden" :triggers ["deploy"] :disable-model-invocation true :body "NO"}
   "plain"   {:name "plain" :body "never auto"}})

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
        (-> (expect (sk/activation-block skills [])) (.toBeNil))))))
