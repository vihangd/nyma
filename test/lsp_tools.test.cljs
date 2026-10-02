(ns lsp-tools-test
  "`lsp_tools.cljs` is 446 lines and had no test of any kind — the largest
   zero-coverage file in the repo.

   Its own docstring names the thing most worth testing: \"All line/col inputs
   are 1-based … converted to 0-based before sending to the language server.\"
   A coordinate transform with an off-by-one is the canonical unit-test case,
   and the transform was a bare `(dec (js/parseInt n 10))` reachable directly
   from model-supplied arguments with no validation anywhere in between.

   Two inputs a model actually produces broke it:
     `0`      — thinking in 0-based terms, became `-1`
     `\"abc\"` — or any non-integer, became `NaN`
   Both were handed to the language server as a position.

   The nominal coverage for this subsystem was
   `ext_lsp_suite_integration.test.cljs`, which reported five passes while
   asserting nothing (fixed earlier on this branch)."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.lsp-suite.lsp-tools :as lt]))

(describe "->0 (1-based → 0-based)"
          (fn []
            (it "converts the normal case"
                (fn []
                  (-> (expect (lt/->0 1)) (.toBe 0))
                  (-> (expect (lt/->0 2)) (.toBe 1))
                  (-> (expect (lt/->0 42)) (.toBe 41))))

            (it "accepts a numeric string, which is how JSON args often arrive"
                (fn []
                  (-> (expect (lt/->0 "7")) (.toBe 6))))

            (it "clamps 0 to 0 instead of producing -1"
                (fn []
        ;; A model thinking in 0-based terms sends 0. `-1` is not a valid LSP
        ;; position.
                  (-> (expect (lt/->0 0)) (.toBe 0))))

            (it "clamps negatives to 0"
                (fn []
                  (-> (expect (lt/->0 -5)) (.toBe 0))))

            (it "does not emit NaN for a non-integer"
                (fn []
                  (doseq [bad ["abc" nil "" "x1"]]
                    (-> (expect #js [bad (lt/->0 bad)]) (.toEqual #js [bad 0])))))

            (it "never returns a negative or NaN, for any input"
                (fn []
        ;; The property, rather than another example.
                  (doseq [v [1 0 -1 -100 "3" "abc" nil "" 1.9 999999]]
                    (let [r (lt/->0 v)]
                      (-> (expect #js [v (and (number? r)
                                              (not (js/Number.isNaN r))
                                              (>= r 0))])
                          (.toEqual #js [v true]))))))))

(describe "position-params"
          (fn []
            (it "builds the LSP shape with both coordinates converted"
                (fn []
                  (let [p (lt/position-params "/tmp/x.cljs" 3 7 "/tmp")]
                    (-> (expect (.. p -position -line)) (.toBe 2))
                    (-> (expect (.. p -position -character)) (.toBe 6))
                    (-> (expect (.. p -textDocument -uri)) (.toContain "x.cljs")))))

            (it "resolves a relative file against cwd"
                (fn []
                  (let [p (lt/position-params "src/x.cljs" 1 1 "/tmp/proj")]
                    (-> (expect (.. p -textDocument -uri)) (.toContain "/tmp/proj/src/x.cljs")))))

            (it "leaves an absolute path alone"
                (fn []
                  (let [p (lt/position-params "/abs/y.cljs" 1 1 "/tmp/proj")]
                    (-> (expect (.. p -textDocument -uri)) (.toContain "/abs/y.cljs"))
                    (-> (expect (.. p -textDocument -uri)) (.not.toContain "proj")))))

            (it "emits a file:// uri, which is what the protocol requires"
                (fn []
                  (-> (expect (.. (lt/position-params "/a/b.cljs" 1 1 "/") -textDocument -uri))
                      (.toStartWith "file://"))))))

(describe "format-locations"
          (fn []
            (it "says so plainly when there is nothing"
                (fn []
                  (-> (expect (lt/format-locations #js [])) (.toBe "No definition found."))))

            (it "handles a single location, not just an array"
                (fn []
        ;; LSP lets a server answer with either.
                  (let [out (lt/format-locations
                             #js {:uri "file:///a/b.cljs" :range #js {:start #js {:line 4 :character 2}}})]
                    (-> (expect out) (.toContain "b.cljs")))))

            (it "reads the LocationLink spelling too"
                (fn []
        ;; `targetUri`/`targetSelectionRange` is what a server sends when the
        ;; client advertises LocationLink support.
                  (let [out (lt/format-locations
                             #js [#js {:targetUri "file:///a/c.cljs"
                                       :targetSelectionRange #js {:start #js {:line 0 :character 0}}}])]
                    (-> (expect out) (.toContain "c.cljs")))))

            (it "skips an entry with no usable uri or range rather than crashing"
                (fn []
                  (let [out (lt/format-locations #js [#js {:uri "file:///a/d.cljs"} #js {}])]
                    (-> (expect (string? out)) (.toBe true)))))

            (it "nil in, nil out"
                (fn []
                  (-> (expect (lt/format-locations nil)) (.toBeNil))))))

(describe "no-server-msg"
          (fn []
            (it "names the file and the setting the user has to add"
                (fn []
        ;; A bare "no server" left people with nothing to act on.
                  (let [m (lt/no-server-msg "src/x.rs")]
                    (-> (expect m) (.toContain "src/x.rs"))
                    (-> (expect m) (.toContain "settings.json")))))))
