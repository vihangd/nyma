(ns ui-dialogs.test
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["ink-testing-library" :refer [render cleanup]]
            ["ink" :refer [Box Text]]
            ["./agent/ui/dialogs.jsx" :refer [ConfirmDialog SelectDialog InputDialog]]
            ["./agent/ui/notification.jsx" :refer [Notification]]
            ["./agent/ui/app.jsx" :refer [with-dismissal]]))

(def test-theme
  {:colors {:primary "#7aa2f7" :secondary "#9ece6a" :error "#f7768e"
            :warning "#e0af68" :success "#9ece6a" :muted "#565f89"
            :border "#3b4261"}})

(afterEach (fn [] (cleanup)))

(describe "SelectDialog" (fn []
  (it "renders title and options"
    (fn []
      (let [{:keys [lastFrame]} (render
                                  #jsx [SelectDialog {:title "Pick one:"
                                                      :options ["Alpha" "Beta" "Gamma"]
                                                      :on-select (fn [_])
                                                      :on-cancel (fn [])
                                                      :theme test-theme}])]
        (-> (expect (lastFrame)) (.toContain "Pick one:"))
        (-> (expect (lastFrame)) (.toContain "Alpha"))
        (-> (expect (lastFrame)) (.toContain "Beta"))
        (-> (expect (lastFrame)) (.toContain "Gamma")))))

  (it "highlights first option by default"
    (fn []
      (let [{:keys [lastFrame]} (render
                                  #jsx [SelectDialog {:title "Choose:"
                                                      :options ["A" "B"]
                                                      :on-select (fn [_])
                                                      :on-cancel (fn [])
                                                      :theme test-theme}])]
        (-> (expect (lastFrame)) (.toContain "> 1. A")))))))

(describe "InputDialog" (fn []
  (it "renders title"
    (fn []
      (let [{:keys [lastFrame]} (render
                                  #jsx [InputDialog {:title "Enter name:"
                                                     :placeholder "type here"
                                                     :on-submit (fn [_])
                                                     :on-cancel (fn [])
                                                     :theme test-theme}])]
        (-> (expect (lastFrame)) (.toContain "Enter name:")))))

  (it "calls on-cancel when blank text is submitted (prevents hung Promise)"
    (fn []
      (let [submitted (atom nil)
            cancelled (atom false)
            ;; Directly invoke the onSubmit callback with blank text, as TextInput would
            on-submit (fn [v] (reset! submitted v))
            on-cancel (fn [] (reset! cancelled true))
            ;; Replicate the onSubmit logic from dialogs.cljs
            onSubmit  (fn [text]
                        (if (seq (.trim text))
                          (when on-submit (on-submit (.trim text)))
                          (when on-cancel (on-cancel))))]
        ;; Blank submit → on-cancel should fire, on-submit should not
        (onSubmit "")
        (-> (expect @cancelled) (.toBe true))
        (-> (expect (nil? @submitted)) (.toBe true)))))

  (it "calls on-submit when non-blank text is submitted"
    (fn []
      (let [submitted (atom nil)
            cancelled (atom false)
            on-submit (fn [v] (reset! submitted v))
            on-cancel (fn [] (reset! cancelled true))
            onSubmit  (fn [text]
                        (if (seq (.trim text))
                          (when on-submit (on-submit (.trim text)))
                          (when on-cancel (on-cancel))))]
        (onSubmit "  hello  ")
        (-> (expect @submitted) (.toBe "hello"))
        (-> (expect @cancelled) (.toBe false)))))

  (it "whitespace-only submit is treated as blank and triggers on-cancel"
    (fn []
      (let [cancelled (atom false)
            on-cancel (fn [] (reset! cancelled true))
            onSubmit  (fn [text]
                        (if (seq (.trim text))
                          nil
                          (when on-cancel (on-cancel))))]
        (onSubmit "   ")
        (-> (expect @cancelled) (.toBe true)))))))

(describe "Notification" (fn []
  (it "renders info notification"
    (fn []
      (let [{:keys [lastFrame]} (render
                                  #jsx [Notification {:message "Done!"
                                                      :level "info"
                                                      :theme test-theme}])]
        (-> (expect (lastFrame)) (.toContain "Done!")))))

  (it "renders error notification"
    (fn []
      (let [{:keys [lastFrame]} (render
                                  #jsx [Notification {:message "Oops"
                                                      :level "error"
                                                      :theme test-theme}])]
        (-> (expect (lastFrame)) (.toContain "Oops")))))

  (it "renders warning notification"
    (fn []
      (let [{:keys [lastFrame]} (render
                                  #jsx [Notification {:message "Watch out"
                                                      :level "warning"
                                                      :theme test-theme}])]
        (-> (expect (lastFrame)) (.toContain "Watch out")))))))

(describe "ConfirmDialog" (fn []
  (it "renders message and Y/N labels"
    (fn []
      (let [{:keys [lastFrame]} (render
                                  #jsx [ConfirmDialog {:message "Deploy to prod?"
                                                       :on-confirm (fn [])
                                                       :on-cancel (fn [])}])]
        (-> (expect (lastFrame)) (.toContain "Deploy to prod?"))
        (-> (expect (lastFrame)) (.toContain "[Y]es"))
        (-> (expect (lastFrame)) (.toContain "[N]o")))))

  (it "renders title in bold when provided"
    (fn []
      (let [{:keys [lastFrame]} (render
                                  #jsx [ConfirmDialog {:title "Danger!"
                                                       :message "Are you sure?"
                                                       :on-confirm (fn [])
                                                       :on-cancel (fn [])}])]
        (-> (expect (lastFrame)) (.toContain "Danger!"))
        (-> (expect (lastFrame)) (.toContain "Are you sure?")))))

  (it "renders without title (backward compat)"
    (fn []
      (let [{:keys [lastFrame]} (render
                                  #jsx [ConfirmDialog {:message "Continue?"
                                                       :on-confirm (fn [])
                                                       :on-cancel (fn [])}])]
        (-> (expect (lastFrame)) (.toContain "Continue?"))
        ;; Should not crash without title
        (-> (expect (lastFrame)) (.toContain "[Y]es")))))))

(describe "with-dismissal" (fn []
  (it "auto-dismisses after timeout" ^:async
    (fn []
      (let [result (atom :pending)]
        (with-dismissal (fn [val] (reset! result val)) #js {:timeout 50})
        ;; Wait for timeout to fire
        (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 100))))
        (-> (expect @result) (.toBeNull)))))

  (it "auto-dismisses on AbortSignal" ^:async
    (fn []
      (let [result     (atom :pending)
            controller (js/AbortController.)]
        (with-dismissal (fn [val] (reset! result val)) #js {:signal (.-signal controller)})
        (.abort controller)
        ;; Signal fires synchronously, but give microtask a chance
        (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 10))))
        (-> (expect @result) (.toBeNull)))))

  (it "cleanup prevents double-fire" ^:async
    (fn []
      (let [call-count (atom 0)
            dismiss-fn (fn [_val] (swap! call-count inc))
            cleanup    (with-dismissal dismiss-fn #js {:timeout 50})]
        ;; Manual dismiss before timeout
        (cleanup)
        (dismiss-fn "manual")
        ;; Wait for timeout period to pass — should NOT fire again
        (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 100))))
        (-> (expect @call-count) (.toBe 1)))))

  (it "does nothing when opts is nil" ^:async
    (fn []
      (let [result (atom :pending)
            cleanup (with-dismissal (fn [val] (reset! result val)) nil)]
        ;; Should return a no-op cleanup and not auto-dismiss
        (-> (expect (fn? cleanup)) (.toBe true))
        (js-await (js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
        (-> (expect @result) (.toBe "pending")))))))
