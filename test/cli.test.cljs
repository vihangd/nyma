(ns cli.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.cli :refer [resolve-ext-flags]]))

;; ── resolve-ext-flags ──────────────────────────────────────

(defn- setup-flagged-agent
  "Create agent + register flags for testing."
  []
  (let [agent (create-agent {:model "test" :system-prompt "test"})
        api   (create-extension-api agent)]
    ;; Register some flags
    (.registerFlag api "verbose" #js {:type "boolean" :default false})
    (.registerFlag api "format" #js {:type "string" :default "json"})
    (.registerFlag api "count" #js {:type "number" :default 10})
    agent))

(describe "resolve-ext-flags" (fn []
  (it "resolves boolean flag without value"
    (fn []
      (let [agent (setup-flagged-agent)
            orig  js/process.argv]
        (set! js/process.argv #js ["node" "nyma" "--ext-verbose"])
        (resolve-ext-flags agent)
        (set! js/process.argv orig)
        (-> (expect (:value (get @(:flags agent) "verbose"))) (.toBe true)))))

  (it "resolves string flag with value"
    (fn []
      (let [agent (setup-flagged-agent)
            orig  js/process.argv]
        (set! js/process.argv #js ["node" "nyma" "--ext-format=text"])
        (resolve-ext-flags agent)
        (set! js/process.argv orig)
        (-> (expect (:value (get @(:flags agent) "format"))) (.toBe "text")))))

  (it "resolves number flag with value"
    (fn []
      (let [agent (setup-flagged-agent)
            orig  js/process.argv]
        (set! js/process.argv #js ["node" "nyma" "--ext-count=5"])
        (resolve-ext-flags agent)
        (set! js/process.argv orig)
        (-> (expect (:value (get @(:flags agent) "count"))) (.toBe 5)))))

  (it "resolves boolean false"
    (fn []
      (let [agent (setup-flagged-agent)
            orig  js/process.argv]
        (set! js/process.argv #js ["node" "nyma" "--ext-verbose=false"])
        (resolve-ext-flags agent)
        (set! js/process.argv orig)
        (-> (expect (:value (get @(:flags agent) "verbose"))) (.toBe false)))))

  (it "ignores unknown ext flags"
    (fn []
      (let [agent (setup-flagged-agent)
            orig  js/process.argv]
        (set! js/process.argv #js ["node" "nyma" "--ext-unknown=x"])
        (resolve-ext-flags agent)
        (set! js/process.argv orig)
        ;; Known flags should still have nil value
        (-> (expect (:value (get @(:flags agent) "verbose"))) (.toBeNull)))))

  (it "handles no ext flags"
    (fn []
      (let [agent (setup-flagged-agent)
            orig  js/process.argv]
        (set! js/process.argv #js ["node" "nyma" "--model" "test"])
        (resolve-ext-flags agent)
        (set! js/process.argv orig)
        ;; All values should remain nil
        (-> (expect (:value (get @(:flags agent) "verbose"))) (.toBeNull)))))))
