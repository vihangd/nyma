(ns bash-judge-test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.bash-suite.permissions :as permissions]
            [agent.extensions.bash-suite.shared :as shared]
            [agent.utils.credentials :as creds]))

(defn- answer [p] #js {:answers #js {:risk #js {:type "choice" :probabilities #js {:safe p}}}})

(defn ^:async with-fetch [f env body]
  (let [orig js/globalThis.fetch
        key  (.. js/process -env -TYPESAFE_API_KEY)]
    (set! js/globalThis.fetch f)
    (if env (aset (.-env js/process) "TYPESAFE_API_KEY" env) (js-delete (.-env js/process) "TYPESAFE_API_KEY"))
    (try (js-await (body))
         (finally (set! js/globalThis.fetch orig)
                  (if key (aset (.-env js/process) "TYPESAFE_API_KEY" key)
                      (js-delete (.-env js/process) "TYPESAFE_API_KEY"))))))

(defn- reply [p] (fn [_ _] (js/Promise.resolve #js {:ok true :json (fn [] (js/Promise.resolve (answer p)))})))

(defn ^:async test-fail-closed []
  (let [allows? #(permissions/judge-allows? "frob" {:threshold 0.8})]
    (-> (expect (js-await (with-fetch (reply 0.9) "k" allows?))) (.toBe true))
    (-> (expect (js-await (with-fetch (reply 0.5) "k" allows?))) (.toBe false))
    (-> (expect (js-await (with-fetch (fn [] (js/Promise.reject (js/Error. "down"))) "k" allows?))) (.toBe false))
    (-> (expect (js-await (with-fetch (reply 0.99) nil allows?))) (.toBe false))
    ;; a /login key stands in for the env var
    (creds/set-run-key! "typesafe" "k")
    (-> (expect (js-await (with-fetch (reply 0.9) nil allows?))) (.toBe true))
    (creds/set-run-key! "typesafe" nil)))

(describe "bash judge (Jev)" (fn []
  (it "approves only a confident safe verdict; errors and no key still ask" test-fail-closed)
  (it "is never taken from a project's settings"
      (fn []
        (-> (expect (get-in (shared/tighten-only {:permissions {:judge {:threshold 0}}}) [:permissions :judge]))
            (.toBeUndefined))))))
