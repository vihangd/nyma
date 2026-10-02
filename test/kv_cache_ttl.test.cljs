(ns kv-cache-ttl-test
  "`cacheControl.ttl` — opt-in, Anthropic-only, and absent by default.

   The default must stay byte-identical to what nyma has always sent. A `ttl`
   that appeared unasked-for would change what every Anthropic request costs:
   a 1h cache WRITE is dearer than a 5m one, so turning it on is a tradeoff the
   user makes, not one a default makes for them."
  (:require ["bun:test" :refer [test expect describe]]
            [agent.extensions.token-suite.kv-cache :as kv]
            [agent.extensions.token-suite.shared :as shared]))

(describe "cache-control"
          (fn []
            (test "no ttl by default — the request is what it has always been"
                  (fn []
                    (let [o (kv/cache-control :anthropic nil)]
                      (-> (expect (.-type o)) (.toBe "ephemeral"))
                      (-> (expect (.hasOwnProperty o "ttl")) (.toBe false)))))

            (test "sets the two lifetimes Anthropic accepts"
                  (fn []
                    (doseq [t ["5m" "1h"]]
                      (-> (expect (.-ttl (kv/cache-control :anthropic t))) (.toBe t)))))

            (test "drops a value Anthropic would refuse, rather than sending it"
                  (fn []
        ;; Sending an unknown ttl would fail the request every single turn.
                    (doseq [t ["2h" "30s" "" "1hour"]]
                      (-> (expect (.hasOwnProperty (kv/cache-control :anthropic t) "ttl"))
                          (.toBe false)))))

            (test "never sets ttl for google, which has no such field"
                  (fn []
                    (let [o (kv/cache-control :google "1h")]
                      (-> (expect (.-type o)) (.toBe "ephemeral"))
                      (-> (expect (.hasOwnProperty o "ttl")) (.toBe false)))))))

(describe "the setting"
          (fn []
            (test "defaults to nil, so it is opt-in"
                  (fn []
                    (-> (expect (get-in shared/default-config [:kv-cache :cache-ttl]))
                        (.toBeNil))))

            (test "valid-ttls matches @ai-sdk/anthropic's cacheControl.ttl union"
                  (fn []
        ;; dist/index.d.ts: ttl?: "5m" | "1h". If the SDK widens this, the
        ;; set here is what has to grow — asserted so the drift is visible.
                    (-> (expect (clj->js (sort kv/valid-ttls)))
                        (.toEqual (clj->js ["1h" "5m"])))))))
