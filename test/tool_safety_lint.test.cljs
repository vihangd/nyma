(ns tool-safety-lint.test
  "Every tool a built-in extension registers must say what it is.

   `tool-metadata/default-safety` is all-false: a tool without a `:safety`
   field is neither read-only nor destructive, has no capability and no
   category, so `categorize-tool` files it under \"other\" — the one category
   no role policy maps, which the permission gate lets straight through. An
   extension tool that rewrote MEMORY.md and said nothing about itself was
   invisible to /planmode and ask-mode.

   Loads each built-in alone, the way loader_smoke does, so a failure names
   the extension and the tool."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-loader :refer [discover-and-load deactivate-all]]
            [agent.tool-metadata :as tm]
            [agent.middleware :refer [categorize-tool]]
            [agent.builtin-extensions :refer [registry]]))

(defn- classified?
  "Anything that distinguishes the tool from the all-false default."
  [s]
  (boolean (or (:read-only? s) (:destructive? s) (:network? s)
               (some? (:category s)) (seq (:capabilities s)))))

(defn ^:async test-extension-tools-classified [entry]
  (let [agent  (create-agent {:model "test" :system-prompt "lint"})
        api    (create-extension-api agent)
        before (set (keys ((:all (:tool-registry agent)))))
        loaded (js-await (discover-and-load [] api [entry]))]
    (try
      (let [added        (remove before (keys ((:all (:tool-registry agent)))))
            unclassified (vec (remove (fn [n] (classified? (tm/tool-safety n))) added))]
        (-> (expect (clj->js unclassified)) (.toEqual #js [])))
      (finally
        (js-await (deactivate-all loaded))))))

(describe "every extension tool carries safety metadata"
          (fn []
            (doseq [entry registry]
              (it (str (:namespace entry) " classifies every tool it registers")
                  (fn [] (test-extension-tools-classified entry))))

            (it "the detector rejects the all-false default"
                (fn []
                  ;; Guard the guard: a detector that passed the default would
                  ;; pass every unclassified tool too.
                  (-> (expect (classified? (tm/tool-safety "no-such-tool-xyz"))) (.toBe false))
                  (-> (expect (classified? (tm/tool-safety "read"))) (.toBe true))))))

(describe "the permission gate sees multi_edit as a file edit"
          (fn []
            (it "categorizes multi_edit like edit, with or without token_suite loaded"
                (fn []
                  (-> (expect (categorize-tool "multi_edit")) (.toBe "write"))
                  (-> (expect (categorize-tool "edit")) (.toBe "write"))
                  (-> (expect (tm/file-editing? "multi_edit")) (.toBe true))))))
