(ns tool-renderer-registry.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.ui.tool-renderer-registry :refer [register-renderer
                                                      unregister-renderer
                                                      get-renderer
                                                      all-renderers
                                                      reset-registry!]]))

(describe "tool-renderer-registry" (fn []
  (beforeEach (fn [] (reset-registry!)))

  (it "returns nil for an unknown tool"
    (fn []
      (-> (expect (get-renderer "unknown")) (.toBe js/undefined))))

  (it "registers and retrieves a renderer"
    (fn []
      (let [r {:render-call (fn [_] "call") :render-result (fn [_] "result")}]
        (register-renderer "test-tool" r)
        (-> (expect (:render-call (get-renderer "test-tool"))) (.toBeDefined)))))

  (it "overwrites an existing registration"
    (fn []
      (register-renderer "t" {:render-call (fn [_] "v1")})
      (register-renderer "t" {:render-call (fn [_] "v2")})
      (-> (expect ((:render-call (get-renderer "t")) nil)) (.toBe "v2"))))

  (it "unregisters a renderer"
    (fn []
      (register-renderer "t" {:render-call (fn [_] nil)})
      (unregister-renderer "t")
      (-> (expect (get-renderer "t")) (.toBe js/undefined))))

  (it "unregistering an unknown tool is a no-op"
    (fn []
      (unregister-renderer "never-registered")
      (-> (expect (get-renderer "never-registered")) (.toBe js/undefined))))

  (it "all-renderers returns a snapshot of the current registry"
    (fn []
      (register-renderer "a" {:render-call (fn [_] nil)})
      (register-renderer "b" {:render-call (fn [_] nil)})
      (let [snap (all-renderers)]
        (-> (expect (count snap)) (.toBe 2)))))

  (it "respects optional :merge-call-and-result? flag"
    (fn []
      (register-renderer "t" {:render-call (fn [_] nil)
                              :merge-call-and-result? true})
      (-> (expect (:merge-call-and-result? (get-renderer "t")))
          (.toBe true))))

  (it "respects optional :inline? flag"
    (fn []
      (register-renderer "t" {:render-call (fn [_] nil) :inline? true})
      (-> (expect (:inline? (get-renderer "t"))) (.toBe true))))))
