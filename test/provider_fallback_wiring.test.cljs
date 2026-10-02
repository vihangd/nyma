(ns provider-fallback-wiring-test
  "The fallback chain's WIRING, which is where it was broken.

   `test/provider_fallback.test.cljs` covers the pure decisions and passed
   throughout. The feature was still dead for the outage it was written for,
   because of how the failure actually arrives:

   **`streamText` does not throw for a refused connection.** Probed against
   ai@7.0.127 — a model pointed at a closed port yields fullStream parts
   `[\"start\",\"error\"]` and the promise does not reject. So the `catch` around
   `streamText`, which was the only place carrying `:config`, never saw
   ECONNREFUSED / 503 / model-failed-to-load. The stream-error path emitted
   `provider_error` with no config and through a plain `emit` that discards
   handler returns, so `on-provider-error` bailed on `(when (and cfg …))` every
   time.

   Two more wiring faults in the same handler, both verified rather than
   reasoned about:

   - `attempted` was a squint map keyed on the config OBJECT. Map literals
     compile to plain JS objects, so every key stringified to
     `\"[object Object]\"`: one global bucket, and once a chain was exhausted
     fallback was dead for the process lifetime.
   - `tried` was seeded from `data.model`, which is the bare modelId, while
     `spec-of` produces `provider/model`. So the primary was never actually in
     the attempted set, and a chain that re-listed it would retry the model
     that had just failed."
  (:require ["bun:test" :refer [test expect describe]]
            [agent.providers.fallback :as fb]))

(describe "the shape the failure arrives in"
          (fn []
            (test "an availability message is still a fallback case"
                  (fn []
        ;; These are the strings that come out of a stream `error` part, not
        ;; out of a thrown APICallError.
                    (doseq [m ["connect ECONNREFUSED 127.0.0.1:8000"
                               "fetch failed"
                               "503 Service Unavailable"
                               "context deadline exceeded"]]
                      (-> (expect #js [m (fb/should-fall-back? m)]) (.toEqual #js [m true])))))

            (test "the message the AI SDK ACTUALLY produces for a refused connection"
                  (fn []
        ;; Captured by pointing @ai-sdk/openai at a closed port and reading it.
        ;; This is the correction that mattered most: the original tests used
        ;; raw errno strings, which the SDK never surfaces — it wraps, retries
        ;; six times, and reports its own wording. So `should-fall-back?` said
        ;; no to every real outage while passing a suite full of
        ;; plausible-looking messages.
                    (let [m (str "Failed after 6 attempts. Last error: AI_APICallError: "
                                 "Cannot connect to API: Unable to connect. Is the computer "
                                 "able to access the url?")]
                      (-> (expect (fb/should-fall-back? m)) (.toBe true)))))

            (test "an auth failure through the same wrapper still does not fall back"
                  (fn []
        ;; The wrapper must not make every failure look like an outage.
                    (-> (expect (fb/should-fall-back?
                                 "Failed after 2 attempts. Last error: AI_APICallError: 401 Unauthorized"))
                        (.toBe false))))))

(describe "attempted must distinguish runs"
          (fn []
            (test "a squint map cannot key on a config object — a WeakMap can"
                  (fn []
        ;; This is the bug, demonstrated, so nobody reintroduces the map.
                    (let [a #js {:model "m"} b #js {:model "n"}
                          as-map (-> {} (assoc a "A") (assoc b "B"))]
                      (-> (expect (count as-map)) (.toBe 1)))
                    (let [a #js {:model "m"} b #js {:model "n"}
                          wm (js/WeakMap.)]
                      (.set wm a #{"A"})
                      (.set wm b #{"B"})
                      (-> (expect (.get wm a)) (.toEqual #{"A"}))
                      (-> (expect (.get wm b)) (.toEqual #{"B"})))))))

(describe "the primary must be in the attempted set"
          (fn []
            (test "spec-of's shape is provider/model, not a bare modelId"
                  (fn []
        ;; The mismatch that made the seed useless: data.model is "glm-5.3",
        ;; spec-of yields "zai/glm-5.3".
                    (-> (expect (fb/spec-of {:provider "zai" :model "glm-5.3"}))
                        (.toBe "zai/glm-5.3"))))

            (test "a chain that re-lists the primary does not retry it"
                  (fn []
                    (let [role  {:provider "omlx" :model "qwen3.6-35b"
                                 :fallback [{:provider "omlx" :model "qwen3.6-35b"}
                                            {:provider "zai"  :model "glm-5.3-flash"}]}
                          chain (fb/chain role)
              ;; seeded the way the handler now does it — from the ROLE
                          primary (fb/spec-of {:provider (:provider role) :model (:model role)})
                          nxt   (fb/next-entry chain #{primary})]
                      (-> (expect (fb/spec-of nxt)) (.toBe "zai/glm-5.3-flash")))))

            (test "seeding from a bare modelId would have retried the dead model"
                  (fn []
        ;; The old behaviour, pinned as the thing being prevented.
                    (let [role  {:provider "omlx" :model "qwen3.6-35b"
                                 :fallback [{:provider "omlx" :model "qwen3.6-35b"}]}
                          chain (fb/chain role)
                          nxt   (fb/next-entry chain #{"qwen3.6-35b"})]
          ;; a bare id matches nothing in the chain, so the primary comes back
                      (-> (expect (fb/spec-of nxt)) (.toBe "omlx/qwen3.6-35b")))))))
