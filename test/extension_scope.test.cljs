(ns extension-scope.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extension-scope :refer [create-scoped-api derive-namespace]]
            [agent.events :refer [create-event-bus]]
            [agent.extensions :refer [create-extension-api]]
            [agent.core :refer [create-agent]]))

(defn make-test-agent []
  (create-agent {:model "mock" :system-prompt "test"}))

(describe "create-scoped-api" (fn []
  (it "prefixes tool registration with namespace"
    (fn []
      (let [agent    (make-test-agent)
            base-api (create-extension-api agent)
            scoped   (create-scoped-api base-api "git" #{:all})]
        (.registerTool scoped "status" #js {:execute (fn [_] "ok")})
        ;; Tool should be registered as "git/status"
        (-> (expect (get ((:all (:tool-registry agent))) "git/status")) (.toBeDefined)))))

  (it "prefixes command registration with namespace"
    (fn []
      (let [agent    (make-test-agent)
            base-api (create-extension-api agent)
            scoped   (create-scoped-api base-api "docker" #{:all})]
        (.registerCommand scoped "ps" {:description "List containers"})
        (-> (expect (get @(:commands agent) "docker/ps")) (.toBeDefined)))))

  (it "exposes namespace property"
    (fn []
      (let [agent    (make-test-agent)
            base-api (create-extension-api agent)
            scoped   (create-scoped-api base-api "my-ext" #{:all})]
        (-> (expect (.-namespace scoped)) (.toBe "my-ext")))))

  (it "gates tools capability"
    (fn []
      (let [agent    (make-test-agent)
            base-api (create-extension-api agent)
            scoped   (create-scoped-api base-api "no-tools" #{:events})]
        (-> (expect (fn [] (.registerTool scoped "x" #js {})))
            (.toThrow "missing capability")))))

  (it "gates events capability"
    (fn []
      (let [agent    (make-test-agent)
            base-api (create-extension-api agent)
            scoped   (create-scoped-api base-api "no-events" #{:tools})]
        (-> (expect (fn [] (.on scoped "agent_start" (fn [_]))))
            (.toThrow "missing capability")))))

  (it "gates middleware capability"
    (fn []
      (let [agent    (make-test-agent)
            base-api (create-extension-api agent)
            scoped   (create-scoped-api base-api "no-mw" #{:tools})]
        (-> (expect (fn [] (.addMiddleware scoped {:name :test})))
            (.toThrow "missing capability")))))

  (it ":all grants all capabilities"
    (fn []
      (let [agent    (make-test-agent)
            base-api (create-extension-api agent)
            scoped   (create-scoped-api base-api "admin" #{:all})]
        ;; Should not throw
        (.registerTool scoped "x" #js {:execute (fn [_] "ok")})
        (.on scoped "agent_start" (fn [_]))
        (.addMiddleware scoped {:name :test :enter (fn [ctx] ctx)}))))

  (it "prefixes flag registration with namespace"
    (fn []
      (let [agent    (make-test-agent)
            base-api (create-extension-api agent)
            scoped   (create-scoped-api base-api "git" #{:all})]
        (.registerFlag scoped "verbose" #js {:type "boolean" :default false})
        (-> (expect (get @(:flags agent) "git/verbose")) (.toBeDefined)))))

  (it "getFlag reads namespace-prefixed flag"
    (fn []
      (let [agent    (make-test-agent)
            base-api (create-extension-api agent)
            scoped   (create-scoped-api base-api "git" #{:all})]
        (.registerFlag scoped "verbose" #js {:type "boolean" :default true})
        (-> (expect (.getFlag scoped "verbose")) (.toBe true)))))

  (it "gates flags capability"
    (fn []
      (let [agent    (make-test-agent)
            base-api (create-extension-api agent)
            scoped   (create-scoped-api base-api "no-flags" #{:tools})]
        (-> (expect (fn [] (.registerFlag scoped "x" #js {})))
            (.toThrow "missing capability")))))))

(describe "derive-namespace" (fn []
  (it "derives from simple filename"
    (fn []
      (-> (expect (derive-namespace "/path/to/git-tools.cljs")) (.toBe "git-tools"))))

  (it "derives from nested path"
    (fn []
      (-> (expect (derive-namespace "/home/user/.nyma/extensions/my-ext.ts")) (.toBe "my-ext"))))

  (it "handles .mjs extension"
    (fn []
      (-> (expect (derive-namespace "/ext/logger.mjs")) (.toBe "logger"))))

  (it "throws on empty namespace (dotfile like .cljs)"
    (fn []
      (-> (expect (fn [] (derive-namespace "/path/to/.cljs")))
          (.toThrow "Invalid extension namespace"))))

  (it "throws on namespace with invalid characters"
    (fn []
      (-> (expect (fn [] (derive-namespace "/path/to/bad name.cljs")))
          (.toThrow "Invalid extension namespace"))))))
