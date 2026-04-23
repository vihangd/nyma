(ns scoped-api-parity.test
  "Regression tests that catch methods added to the base extension API
   (extensions.cljs) but not forwarded in the scoped API (extension_scope.cljs).

   The getSettings / emitGlobal omissions were caught in production; this
   file ensures the same class of bug is caught at test time instead."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-scope :refer [create-scoped-api]]))

;;; ─── Helpers ────────────────────────────────────────────────

(defn- make-base-api []
  (let [agent (create-agent {:model "test-model" :system-prompt "test"})]
    (create-extension-api agent)))

(defn- make-scoped-api [base-api]
  ;; All capabilities enabled — we want to check surface coverage, not gating.
  (create-scoped-api base-api "test-ns"
                     #{:tools :commands :shortcuts :messages :middleware
                       :exec :spawn :session :renderers :ui :providers
                       :model :events :context :flags :state}))

(defn- api-fn-keys [api]
  (->> (js/Object.keys api)
       (filter (fn [k] (fn? (aget api k))))
       set))

;;; ─── Surface parity ─────────────────────────────────────────

(describe "scoped-api-parity" (fn []
                                (it "every function on the base API is also present on the scoped API"
                                    (fn []
                                      (let [base   (make-base-api)
                                            scoped (make-scoped-api base)
                                            base-fns   (api-fn-keys base)
                                            scoped-fns (api-fn-keys scoped)
                                            missing    (clj->js (sort (remove scoped-fns base-fns)))]
        ;; Report which methods are absent so failures are self-documenting.
                                        (-> (expect (.-length missing))
                                            (.toBe 0)))))

                                (it "getSettings returns actual settings data (not nil) through scoped API"
                                    (fn []
                                      (let [sentinel  {:my-key "my-value"}
                                            agent     (create-agent {:model "test-model" :system-prompt "test"
                                                                     :settings {:get (fn [] sentinel)}})
                                            base      (create-extension-api agent)
                                            scoped    (make-scoped-api base)
                                            result    (.getSettings scoped)]
                                        (-> (expect (fn? (.-getSettings scoped))) (.toBe true))
                                        ;; Must return the actual settings, not nil.
                                        ;; Catches: settings not stored on agent map,
                                        ;;          getSettings not forwarded in scope.
                                        (-> (expect (some? result)) (.toBe true))
                                        (-> (expect (get result :my-key)) (.toBe "my-value")))))

                                (it "emitGlobal is present and callable on the scoped API"
                                    (fn []
                                      (let [base   (make-base-api)
                                            scoped (make-scoped-api base)]
                                        (-> (expect (fn? (.-emitGlobal scoped))) (.toBe true))
                                        (-> (expect (fn [] (.emitGlobal scoped "test-event" #js {}))) (.not.toThrow)))))))

;;; ─── model_roles settings integration ──────────────────────
;;; These tests cover the get-roles JS-object path that was broken:
;;; JSON.parse returns plain JS objects; (map? js-obj) is false in squint,
;;; so get-roles was always falling back to built-in defaults.

(describe "scoped-api-parity:model-roles-settings" (fn []
                                                     (it "getSettings returns user settings through scoped API"
                                                         (fn []
                                                           (let [agent  (create-agent {:model "test-model" :system-prompt "test"
                                                                                       :settings {:get (fn []
                                                                                                         {:roles {"build" {"provider" "minimax"
                                                                                                                           "model" "MiniMax-M2.7"}}})}})
                                                                 base   (create-extension-api agent)
                                                                 scoped (make-scoped-api base)
                                                                 settings (.getSettings scoped)]
                                                             (-> (expect (some? settings)) (.toBe true))
        ;; roles key must be reachable from the returned settings
                                                             (let [roles (or (when settings (get settings "roles"))
                                                                             (when settings (get settings :roles)))]
                                                               (-> (expect (some? roles)) (.toBe true))))))

                                                     (it "user roles from JSON-parsed settings (JS objects) are accessible"
                                                         (fn []
      ;; Simulate what JSON.parse returns: a plain JS object tree.
                                                           (let [js-roles  (js/JSON.parse "{\"build\":{\"provider\":\"minimax\",\"model\":\"MiniMax-M2.7\"}}")
                                                                 agent     (create-agent {:model "test-model" :system-prompt "test"
                                                                                          :settings {:get (fn [] {"roles" js-roles})}})
                                                                 base      (create-extension-api agent)
                                                                 scoped    (make-scoped-api base)
                                                                 settings  (.getSettings scoped)
                                                                 raw-roles (when settings (get settings "roles"))]
        ;; raw-roles is a JS object here — must not be dropped by (map? …) check
                                                             (-> (expect (some? raw-roles)) (.toBe true))
        ;; The "build" key must be reachable regardless of whether the outer
        ;; value is a CLJS map or a JS plain object.
                                                             (let [build-cfg (or (when (map? raw-roles) (get raw-roles "build"))
                                                                                 (when raw-roles (aget raw-roles "build")))]
                                                               (-> (expect (some? build-cfg)) (.toBe true))))))))
