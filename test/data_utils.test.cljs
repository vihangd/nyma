(ns data-utils.test
  "agent.utils.data: the one key-reader and the one tolerant JSON parser.
   Three incompatible helpers and sixty inline `(or (.-camel x) (aget x
   \"kebab\"))` ladders preceded it, and `or` had already eaten a `false`
   once (`\"discover\": false` read as unset)."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.utils.data :as data]))

(describe "data/key-get" (fn []
                           (it "returns the first PRESENT key, so false and 0 survive"
                               (fn []
                                 (-> (expect (data/key-get {:a false :b 1} :a :b)) (.toBe false))
                                 (-> (expect (data/key-get {:a 0 :b 1} :a :b)) (.toBe 0))
                                 (-> (expect (data/key-get {:b 1} :a :b)) (.toBe 1))
                                 (-> (expect (data/key-get {} :a)) (.toBeNil))
                                 (-> (expect (data/key-get nil :a)) (.toBeNil))))

                           (it "reads CLJS maps and JS objects alike, keyword or string keys"
                               (fn []
                                 (-> (expect (data/key-get #js {"base-url" "u"} :base-url)) (.toBe "u"))
                                 (-> (expect (data/key-get #js {:baseUrl "u"} "baseUrl")) (.toBe "u"))))))

(describe "data/conf-get" (fn []
                            (it "tries kebab, camelCase and snake_case, kebab first"
                                (fn []
                                  (-> (expect (data/spellings :cache-read)) (.toEqual (clj->js ["cache-read" "cacheRead" "cache_read"])))
                                  (-> (expect (data/conf-get #js {:baseUrl "camel"} :base-url)) (.toBe "camel"))
                                  (-> (expect (data/conf-get #js {:base_url "snake"} :base-url)) (.toBe "snake"))
                                  (-> (expect (data/conf-get #js {"base-url" "kebab" :baseUrl "camel"} :base-url)) (.toBe "kebab"))
                                  (-> (expect (data/conf-get {:api "x"} :api)) (.toBe "x"))))

                            (it "keeps a declared 0 and honours the default only on absence"
                                (fn []
                                  (-> (expect (data/conf-get #js {:cacheRead 0} :cache-read 9)) (.toBe 0))
                                  (-> (expect (data/conf-get #js {} :cache-read 9)) (.toBe 9))))

                            (it "conf-bool: explicit false is a value, absence is the default"
                                (fn []
                                  (-> (expect (data/conf-bool #js {:discover false} :discover true)) (.toBe false))
                                  (-> (expect (data/conf-bool #js {} :discover true)) (.toBe true))
                                  (-> (expect (data/conf-bool {:discover 1} :discover false)) (.toBe true))))))

(describe "data/parse-json" (fn []
                              (it "parses, and answers the fallback for junk, blank or non-strings"
                                  (fn []
                                    (-> (expect (.-a (data/parse-json "{\"a\":1}"))) (.toBe 1))
                                    (-> (expect (data/parse-json "{nope")) (.toBeNil))
                                    (-> (expect (data/parse-json "{nope" "raw")) (.toBe "raw"))
                                    (-> (expect (data/parse-json "")) (.toBeNil))
                                    (-> (expect (data/parse-json nil {})) (.toEqual (clj->js {})))
                                    (-> (expect (data/parse-json 42 "x")) (.toBe "x"))))))
