(ns width-guard.test
  "pi-tui compares each rendered line against the terminal width and throws
   from inside its own render timer, having already called stop() — so an
   over-wide line ends the session. The check exists only on the incremental
   path; `fullRender` writes lines unchecked, where an over-wide line instead
   soft-wraps and silently desynchronizes pi-tui's line accounting. The guard
   covers both halves, which a crash handler cannot."
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            ["@mariozechner/pi-tui" :refer [visibleWidth TUI ProcessTerminal Editor]]
            ["node:fs" :as fs]
            [agent.ui.width-guard :refer [guard-render! attach-guarded-children! clamps]]))

(def ^:private ESC (js/String.fromCharCode 27))

;; Every way a component has actually got this wrong: wide glyphs, a long
;; ASCII run, ANSI-styled content, and a tab (which every layer measures
;; differently).
(defn- over-wide-lines []
  #js [(apply str (repeat 80 "漢"))
       (apply str (repeat 300 "x"))
       (str ESC "[1m" (apply str (repeat 40 "🎉")) ESC "[0m")
       "short"
       ""])

(defn- make-plain-component []
  #js {:render (fn [_w] (over-wide-lines))
       :invalidate (fn [] nil)})

;; pi-tui's own Editor is a class instance whose `render` lives on the
;; prototype, not as an own property — the guard has to handle both.
(defn- make-class-component []
  (let [C (fn [] (js* "this"))]
    (set! (.. C -prototype -render) (fn [_w] (over-wide-lines)))
    (set! (.. C -prototype -invalidate) (fn [] nil))
    (set! (.. C -prototype -setState) (fn [_] "state-set"))
    (new C)))

(beforeEach (fn [] (reset! clamps {:count 0 :last nil})))

(describe "guard-render!"
          (fn []
            (it "clamps every returned line to the width it was given"
                (fn []
                  (let [c (make-plain-component)]
                    ;; Establish the component really is over-wide first —
                    ;; otherwise this test could pass against a no-op guard.
                    (-> (expect (some (fn [l] (> (visibleWidth l) 80))
                                      (vec (.render c 80))))
                        (.toBe true))
                    (guard-render! c "plain")
                    (doseq [w [167 120 80 40 20 10 1]]
                      (doseq [l (vec (.render c w))]
                        (-> (expect (visibleWidth l)) (.toBeLessThanOrEqual w)))))))

            (it "works on a class instance whose render is on the prototype"
                (fn []
                  (let [c (make-class-component)]
                    (guard-render! c "class")
                    (doseq [l (vec (.render c 40))]
                      (-> (expect (visibleWidth l)) (.toBeLessThanOrEqual 40))))))

            (it "preserves the component's other methods and identity"
                (fn []
                  ;; Wrapping in a NEW object would drop setState/setMessages/
                  ;; appendChunk, which is why the guard mutates in place.
                  (let [c (make-class-component)
                        r (guard-render! c "class")]
                    (-> (expect (identical? r c)) (.toBe true))
                    (-> (expect (.setState c "x")) (.toBe "state-set"))
                    (-> (expect (fn? (.-invalidate c))) (.toBe true)))))

            (it "leaves a conforming component byte-identical"
                (fn []
                  ;; The guard must remove crashes without altering normal
                  ;; rendering — at sane widths it has to be a no-op.
                  (let [lines #js ["hello" "there" ""]
                        c     #js {:render (fn [_w] lines)}]
                    (guard-render! c "fine")
                    (-> (expect (vec (.render c 80))) (.toEqual ["hello" "there" ""]))
                    (-> (expect (:count @clamps)) (.toBe 0)))))

            (it "records what it had to cut"
                (fn []
                  ;; A guard that silently fixes a real bug forever is how the
                  ;; fullRender path stayed invisible. A non-zero count means
                  ;; some component is still miscomputing its width.
                  (let [c (make-plain-component)]
                    (guard-render! c "plain")
                    (.render c 80)
                    (-> (expect (:count @clamps)) (.toBeGreaterThan 0))
                    (-> (expect (:component (:last @clamps))) (.toBe "plain")))))

            (it "tolerates a component that returns a non-array"
                (fn []
                  (let [c #js {:render (fn [_w] nil)}]
                    (guard-render! c "weird")
                    (-> (expect (.render c 80)) (.toBeNil)))))

            (it "tolerates nil and a component with no render"
                (fn []
                  (-> (expect (guard-render! nil)) (.toBeNil))
                  (let [c #js {}]
                    (-> (expect (guard-render! c "empty")) (.toBe c)))))))

(describe "attach-guarded-children!"
          (fn []
            (it "guards every child it attaches"
                (fn []
                  (let [attached (atom [])
                        tui #js {:addChild (fn [c] (swap! attached conj c) nil)}
                        a   (make-plain-component)
                        b   (make-class-component)]
                    (attach-guarded-children! tui [["a" a] ["b" b]])
                    (-> (expect (count @attached)) (.toBe 2))
                    ;; What pi-tui would receive is what matters.
                    (doseq [c @attached]
                      (doseq [w [80 40 10]]
                        (doseq [l (vec (.render c w))]
                          (-> (expect (visibleWidth l)) (.toBeLessThanOrEqual w))))))))))

;;; ─── the real Editor ─────────────────────────────────────────────────────
;;; The other tests use hand-rolled components, which prove the mechanism but
;;; not the thing it is actually applied to. pi-tui's Editor is a class whose
;;; render lives on the prototype, and the guard sets an OWN property that
;;; shadows it — if that broke `this` state, focus or input routing, nothing
;;; else in this suite would notice and every real session would.

(defn- make-editor []
  ;; Editor's theme is a map of STYLING FUNCTIONS, not strings.
  (let [tui   (new TUI (new ProcessTerminal))
        theme (new js/Proxy #js {} #js {:get (fn [& _] (fn [s] s))})]
    (new Editor tui theme #js {:paddingX 1})))

(describe "guard-render! on pi-tui's own Editor"
          (fn []
            (it "renders identically and stays inside every width"
                (fn []
                  (let [ed     (make-editor)
                        before (js/JSON.stringify (.render ed 80))]
                    (guard-render! ed "editor")
                    ;; No-op at a sane width: the guard must not alter normal
                    ;; rendering.
                    (-> (expect (js/JSON.stringify (.render ed 80))) (.toBe before))
                    (doseq [w [120 80 40 20 10]]
                      (doseq [l (vec (.render ed w))]
                        (-> (expect (visibleWidth l)) (.toBeLessThanOrEqual w)))))))

            (it "still accepts input and still invalidates"
                (fn []
                  ;; A guarded editor that renders but is inert would look
                  ;; fine here and be unusable in a session.
                  (let [ed (make-editor)]
                    (guard-render! ed "editor")
                    (-> (expect (fn? (.-invalidate ed))) (.toBe true))
                    (.invalidate ed)
                    (let [before (js/JSON.stringify (.render ed 80))]
                      (doseq [ch (vec (.split "hello \u6f22\u5b57" ""))]
                        (.handleInput ed ch))
                      (-> (expect (js/JSON.stringify (.render ed 80)))
                          (.not.toBe before))))))))

;;; ─── wiring ──────────────────────────────────────────────────────────────
;;; This one asserts on the compiled source rather than behaviour, deliberately.
;;; Driving `interactive/start` needs a live agent, session and resources, so a
;;; behavioural test of the real mount is out of reach — and a guard that is
;;; correct but unwired is exactly the failure this whole change exists to
;;; prevent. An earlier clamp-wiring test in this repo passed with and without
;;; the clamp; this one fails the moment someone attaches a base child
;;; directly.

(describe "the guard is wired into the real mount"
          (fn []
            (it "interactive mode attaches base children only through the guard"
                (fn []
                  (let [src (str (fs/readFileSync "dist/agent/modes/interactive.mjs" "utf8"))]
                    (-> (expect (.includes src "attach_guarded_children")) (.toBe true))
                    ;; No base child may be attached directly — that child
                    ;; would go straight into the diff loop that throws.
                    (-> (expect (.includes src ".addChild(")) (.toBe false)))))))
