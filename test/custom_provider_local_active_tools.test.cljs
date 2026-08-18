(ns custom-provider-local-active-tools.test
  "The tool-call rescue parser validates every rescued call against the active
   tool names — `(contains? available tool-name)` in toolcall_adapter. It was
   fed `Object.keys` of `getAllTools`, which returns an ARRAY of names
   (extensions.cljs:45), so the set was #{\"0\" \"1\" \"2\" …} and every rescued
   call was thrown away. The rescue existed, was configured, and produced
   nothing — on exactly the local models it was written for.

   The identical mistake was live in small_model/quality_monitor at the same
   time, where it destroyed every tool result instead. Two independent
   consumers, one ambiguous return shape; these tests pin both shapes."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.custom-provider-local.index :as local]))

(defn- names-from [tools]
  ((local/active-tools-fn #js {:getAllTools (fn [] tools)})))

(describe "custom-provider-local/active-tools-fn"
          (fn []
            (it "reads the ARRAY of names getAllTools actually returns"
                (fn []
                  (let [s (names-from #js ["read" "write" "bash"])]
                    (-> (expect (contains? s "read")) (.toBe true))
                    (-> (expect (contains? s "bash")) (.toBe true))
            ;; the bug: indices instead of names
                    (-> (expect (contains? s "0")) (.toBe false)))))

            (it "also accepts an object-shaped tool map"
                (fn []
                  (let [s (names-from #js {:read #js {} :write #js {}})]
                    (-> (expect (contains? s "read")) (.toBe true)))))

            (it "returns an empty set rather than throwing when the host gives nothing"
                (fn []
                  (-> (expect (count (names-from nil))) (.toBe 0))
                  (-> (expect (count ((local/active-tools-fn
                                       #js {:getAllTools (fn [] (throw (js/Error. "boom")))}))))
                      (.toBe 0))))))
