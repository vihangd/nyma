(ns model-info.test
  (:require [agent.model-info :refer [create-model-registry]]))

(describe "create-model-registry" (fn []

  (it "returns correct context window for known model"
    (fn []
      (let [r (create-model-registry)]
        (-> (expect (:context-window ((:get r) "claude-sonnet-4-20250514")))
            (.toBe 200000)))))

  (it "returns 100k fallback for unknown model"
    (fn []
      (let [r (create-model-registry)]
        (-> (expect (:context-window ((:get r) "totally-unknown-model")))
            (.toBe 100000)))))

  (it "fuzzy matches model ID prefix"
    (fn []
      (let [r (create-model-registry)]
        (-> (expect (:context-window ((:get r) "claude-sonnet-4-custom-variant")))
            (.toBe 200000)))))

  (it "register adds new model entries"
    (fn []
      (let [r (create-model-registry)]
        ((:register r) {"my-custom-model" {:context-window 500000}})
        (-> (expect (:context-window ((:get r) "my-custom-model")))
            (.toBe 500000)))))

  (it "register overrides existing model entry"
    (fn []
      (let [r (create-model-registry)]
        ((:register r) {"gpt-4o" {:context-window 256000}})
        (-> (expect (:context-window ((:get r) "gpt-4o")))
            (.toBe 256000)))))

  (it "context-window shorthand returns integer"
    (fn []
      (let [r (create-model-registry)]
        (-> (expect ((:context-window r) "gpt-4o"))
            (.toBe 128000)))))

  (it "get returns full info map"
    (fn []
      (let [r (create-model-registry)
            info ((:get r) "gemini-2.0-flash")]
        (-> (expect (:context-window info)) (.toBe 1000000)))))

  (it "multiple registrations merge without replacing all"
    (fn []
      (let [r (create-model-registry)]
        ((:register r) {"model-a" {:context-window 50000}})
        ((:register r) {"model-b" {:context-window 75000}})
        (-> (expect (:context-window ((:get r) "model-a"))) (.toBe 50000))
        (-> (expect (:context-window ((:get r) "model-b"))) (.toBe 75000)))))))
