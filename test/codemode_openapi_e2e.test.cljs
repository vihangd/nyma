(ns codemode-openapi-e2e-test
  "OpenAPI → registered tools → a script calls one, end to end: a Bun.serve
   stub serves the spec and the endpoint, the tools register through a real
   scoped api, the call runs through the real pipeline, and the auth header
   reaches the server without ever being shown to the model."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-scope :refer [create-scoped-api]]
            [agent.extensions.codemode.index :as cm]))

(def ^:private spec
  #js {:openapi "3.0.0"
       :paths #js {"/pets/{petId}"
                   #js {:get #js {:operationId "getPetById" :summary "Find pet by ID"
                                  :parameters #js [#js {:name "petId" :in "path" :required true
                                                        :schema #js {:type "integer"}}]}}}})

(defn ^:async test-e2e []
  (let [seen   (atom nil)
        server (js/Bun.serve
                #js {:port 0
                     :fetch (fn [req]
                              (let [u (js/URL. (.-url req))]
                                (if (= "/openapi.json" (.-pathname u))
                                  (js/Response.json spec)
                                  (do (reset! seen {:auth (.get (.-headers req) "authorization")
                                                    :path (.-pathname u)})
                                      (js/Response.json #js {:id 7 :name "Rex"})))))})
        base   (str "http://127.0.0.1:" (.-port server))
        _      (aset js/process.env "PETSTORE_TOKEN" "s3cret-token")
        a      (create-agent {:model #js {:modelId "test"} :system-prompt "s"})
        api    (create-scoped-api (create-extension-api a) "codemode" #{:tools :tools-preapprove :events})
        names  (js-await (cm/register-apis! api #js {:petstore #js {:spec (str base "/openapi.json")
                                                                    :base-url base
                                                                    :headers #js {:Authorization "Bearer ${PETSTORE_TOKEN}"}}}))
        cfg    (assoc cm/default-config :enabled true :timeout-ms 5000)
        block  (cm/prompt-block api cfg)
        out    (js-await (cm/execute-script api cfg #js {:code "(:name (js-await (petstore/getPetById {:petId 7})))"} nil))]
    (.stop server true)
    (js-delete js/process.env "PETSTORE_TOKEN")
    ;; registered under the scoped name, read by scripts as petstore/getPetById
    (-> (expect (clj->js names)) (.toEqual #js ["petstore__getPetById"]))
    (-> (expect (.includes (clj->js (.getAllTools api)) "codemode__petstore__getPetById")) (.toBe true))
    (-> (expect block) (.toContain "(petstore/getPetById {petId: int})"))
    ;; the call went through, with the expanded secret, to the right path
    (-> (expect out) (.toContain "Rex"))
    (-> (expect (:auth @seen)) (.toBe "Bearer s3cret-token"))
    (-> (expect (:path @seen)) (.toBe "/pets/7"))
    ;; …and the secret is in neither what the model reads nor what it gets back
    (-> (expect (str block out)) (.not.toContain "s3cret"))
    (-> (expect (str block out)) (.not.toContain "PETSTORE_TOKEN"))))

(describe "codemode: OpenAPI end to end" (fn []
  (it "spec → tool → script call, auth applied and never shown" test-e2e)))
