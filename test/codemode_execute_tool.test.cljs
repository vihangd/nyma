(ns codemode-execute-tool-test
  "`api.executeTool` runs a tool through the real middleware pipeline under its
   own name. A script's nested calls go this way, so the gate sees each tool —
   not one opaque dispatcher — and the script's approval can answer an `ask`
   but never lift a deny."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]))

(defn- setup
  "Agent with one tool `mcp__mail__send`, a gate answering `decision`, and a
   UI that counts prompts and answers Allow once."
  [decision prompts ran]
  (let [a   (create-agent {:model #js {:modelId "test"} :system-prompt "s"})
        api (create-extension-api a)]
    ((:register (:tool-registry a)) "mcp__mail__send"
                                    #js {:description "send"
                                         :execute (fn [args] (swap! ran conj (.-to args)) "sent")})
    (set! (.-extension-api a)
          #js {:ui #js {:available true
                        :select (fn [_q _o] (swap! prompts inc) (js/Promise.resolve "Allow once"))}})
    ((:on (:events a)) "permission_request" (fn [_] #js {:decision decision}))
    api))

(defn ^:async test-preapproved-ask []
  (let [prompts (atom 0) ran (atom [])
        api     (setup "ask" prompts ran)
        r       (js-await (.executeTool api "mcp__mail__send" #js {:to "a"}
                                        #js {:preapproved #js ["mcp__mail__send"]}))]
    (-> (expect (.-ok r)) (.toBe true))
    (-> (expect (.-result r)) (.toBe "sent"))
    (-> (expect @prompts) (.toBe 0))
    ;; without the script's approval, the same call asks
    (js-await (.executeTool api "mcp__mail__send" #js {:to "b"}))
    (-> (expect @prompts) (.toBe 1))
    (-> (expect (clj->js @ran)) (.toEqual #js ["a" "b"]))))

(defn ^:async test-deny-wins []
  (let [prompts (atom 0) ran (atom [])
        api     (setup "deny" prompts ran)
        r       (js-await (.executeTool api "mcp__mail__send" #js {:to "a"}
                                        #js {:preapproved #js ["mcp__mail__send"]}))]
    (-> (expect (.-ok r)) (.toBe false))
    (-> (expect (.-denied r)) (.toBe true))
    (-> (expect (count @ran)) (.toBe 0))))

(defn ^:async test-unknown []
  (let [api (setup "allow" (atom 0) (atom []))
        r   (js-await (.executeTool api "nope" #js {}))]
    (-> (expect (.-error r)) (.toBe "Unknown tool: nope"))))

(defn ^:async test-throw []
  (let [a   (create-agent {:model #js {:modelId "test"} :system-prompt "s"})
        api (create-extension-api a)]
    ((:register (:tool-registry a)) "boom" #js {:execute (fn [_] (throw (js/Error. "kaput")))})
    (let [r (js-await (.executeTool api "boom" #js {}))]
      (-> (expect (.-ok r)) (.toBe false))
      (-> (expect (.-error r)) (.toContain "kaput")))))

(describe "api.executeTool" (fn []
  (it "a preapproved tool skips the ask; the same call without approval asks" test-preapproved-ask)
  (it "preapproval never lifts a deny" test-deny-wins)
  (it "an unknown tool resolves to an error, never rejects" test-unknown)
  (it "a throwing tool resolves to an error" test-throw)))
