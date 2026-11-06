(ns ui-surface-parity-test
  "nyma has three `api.ui` surfaces, and they must agree on what exists.

     `extensions.cljs`      declares the contract — ten method names, all nil,
                            with `available: false`. Calling any is a TypeError.
     `extension_scope.cljs` `inert-ui`, the stub an extension gets without the
                            `:ui` capability: the same names as no-ops.
     `overlay_host.cljs`    the real implementation, installed by interactive
                            mode.

   Two defects came out of these disagreeing. `inert-ui` originally had no
   methods at all, so every call site needed a guard; completing it, I gave
   `custom` and `showOverlay` the WRONG RETURN TYPE (a Promise and nil, where
   the real ones return a synchronous dismiss function) — so
   `(let [d (.custom ui p)] (d))` threw precisely when the UI was unavailable,
   which is the case the stub exists for. It was also still missing
   `setEditorValue`/`getEditorValue`.

   Compared as OBJECTS rather than by scraping the source: the first version of
   this test scrape-matched method names with a regex and reported six false
   missing entries because the two files format their literals differently."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-scope :refer [create-scoped-api]]
            [agent.modes.acp :refer [editor-ui]]))

(defn- ui-of [api] (.-ui api))

(defn- method-names [ui]
  (->> (js/Object.keys ui)
       (remove #{"available"})
       set))

(def ^:private contract-ui
  ;; The declared contract: a bare agent's extension api, before interactive
  ;; mode installs anything.
  (ui-of (create-extension-api
          (create-agent {:model "m" :system-prompt "s"}))))

(def ^:private inert-ui
  ;; What an extension gets with NO :ui capability.
  (ui-of (create-scoped-api
          (create-extension-api (create-agent {:model "m" :system-prompt "s"}))
          "some-ext"
          #{})))

(describe "ui surface parity"
  (fn []
    (it "ACP says the ui is available, so what extensions then call must exist"
      (fn []
        (let [ui (editor-ui contract-ui #js {:available true})]
          ((.-setWidget ui) "id" #js [])
          ((.-clearWidget ui) "id")
          ;; Tested for presence: a stub would swallow their text fallback.
          (-> (expect (.-showOverlay ui)) (.toBeFalsy)))))

    (it "both surfaces were actually built"
      (fn []
        ;; Guard the guard: two empty objects would satisfy every comparison
        ;; below.
        (-> (expect (count (method-names contract-ui))) (.toBeGreaterThan 8))
        (-> (expect (count (method-names inert-ui))) (.toBeGreaterThan 8))))

    (it "reports available: false on both"
      (fn []
        (-> (expect (.-available contract-ui)) (.toBe false))
        (-> (expect (.-available inert-ui)) (.toBe false))))

    (it "the inert stub covers every method the contract declares"
      (fn []
        ;; A method the contract names but the stub omits is a TypeError for
        ;; any extension without the :ui capability.
        (let [missing (vec (sort (remove (method-names inert-ui)
                                         (method-names contract-ui))))]
          (-> (expect (clj->js missing)) (.toEqual (clj->js []))))))

    (it "the stub does not invent methods the contract does not declare"
      (fn []
        (let [extra (vec (sort (remove (method-names contract-ui)
                                       (method-names inert-ui))))]
          (-> (expect (clj->js extra)) (.toEqual (clj->js []))))))

    (it "every stub method is callable and none throws"
      (fn []
        ;; The whole point of the stubs. The contract surface has nils here by
        ;; design; the inert one must not.
        (doseq [m (sort (method-names inert-ui))]
          (let [f (aget inert-ui m)]
            (-> (expect #js [m (fn? f)]) (.toEqual #js [m true]))
            (-> (expect #js [m (try (do (f "x" "y") :ok)
                                    (catch :default e (or (.-message e) "threw")))])
                (.toEqual #js [m :ok]))))))

    (it "custom and showOverlay return a dismiss FUNCTION, like the real ones"
      (fn []
        ;; The return-type bug, pinned. `overlay_host` returns `(show! …)`, a
        ;; synchronous dismisser; a Promise or nil here breaks
        ;; `(let [d (.custom ui p)] (d))` exactly when the UI is absent.
        (doseq [m ["custom" "showOverlay"]]
          (let [r ((aget inert-ui m) #js {})]
            (-> (expect #js [m (fn? r)]) (.toEqual #js [m true]))
            ;; and calling the dismisser is safe
            (-> (expect #js [m (try (do (r) :ok) (catch :default _e :threw))])
                (.toEqual #js [m :ok]))))))))
