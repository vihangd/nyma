(ns events-doc.test
  "The README's event table is generated from `agent.events/event-registry`
   (`bun run gen:events-doc`). One table in code; the doc renders it."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["../scripts/gen-events-doc.mjs" :as gen]
            [agent.events :refer [event-registry core-event-types collect-hook-event-types]]))

(describe "events registry" (fn []
          (it "README's event table is the generated one"
              (fn []
                (-> (gen/render)
                    (.then (fn [table]
                             (let [doc (fs/readFileSync (path/join (js/process.cwd) "README.md") "utf8")]
                               (-> (expect (.includes doc gen/README_START)) (.toBe true))
                               (-> (expect (.includes doc table)) (.toBe true))))))))

          (it "every event has a kind and a doc"
              (fn []
                (doseq [[n m] event-registry]
                  (-> (expect (contains? #{:emit :async :collect} (:kind m))) (.toBe true))
                  (-> (expect (seq (str (:doc m)))) (.toBeTruthy)))))

          (it "the derived lists are what they were"
              (fn []
                (-> (expect (count core-event-types)) (.toBe (count event-registry)))
                (-> (expect (vec collect-hook-event-types))
                    (.toEqual (clj->js ["model_resolve" "before_message_send" "provider_error" "stream_filter" "message_before_store"])))
                (-> (expect (contains? (set core-event-types) "turn_finalize")) (.toBe true))))))
