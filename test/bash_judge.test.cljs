(ns bash-judge-test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.bash-suite.permissions :as permissions]
            [agent.extensions.bash-suite.shared :as shared]
            [agent.utils.credentials :as creds]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

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

(defn ^:async test-remembers []
  (let [h     (fs/mkdtempSync (path/join (os/tmpdir) "nyma-judge-"))
        home  (.. js/process -env -HOME)
        calls (atom 0)
        decide (atom nil)]
    (fs/mkdirSync (path/join h ".nyma"))
    (fs/writeFileSync (path/join h ".nyma" "settings.json")
                      (js/JSON.stringify #js {"bash-suite" #js {:permissions #js {:judge #js {:threshold 0.8}}}}))
    (aset (.-env js/process) "HOME" h)
    (try
      (permissions/activate #js {:events #js {:on (fn [_ f] (f #js {:level "unknown" :reasons #js [] :command "frob"}))}
                                 :on (fn [_ f _] (reset! decide f))})
      (let [fetch (fn [_ _] (swap! calls inc) ((reply 0.9) nil nil))]
        (js-await (with-fetch fetch "k" (fn [] (@decide #js {:tool "bash" :args #js {:command "frob"}}))))
        (let [second (js-await (with-fetch fetch "k" (fn [] (js/Promise.resolve (@decide #js {:tool "bash" :args #js {:command "frob"}})))))]
          (-> (expect #js [(.-decision second) @calls]) (.toEqual #js ["allow" 1]))))
      (finally
        (aset (.-env js/process) "HOME" home)
        (fs/rmSync h #js {:recursive true :force true})))))

(describe "bash judge (Jev)" (fn []
  (it "asks TypeSafe once per command" test-remembers)
  (it "approves only a confident safe verdict; errors and no key still ask" test-fail-closed)
  (it "is never taken from a project's settings"
      (fn []
        (-> (expect (get-in (shared/tighten-only {:permissions {:judge {:threshold 0}}}) [:permissions :judge]))
            (.toBeUndefined))))))
