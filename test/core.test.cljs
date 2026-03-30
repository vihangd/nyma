(ns core.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.core :refer [create-agent]]))

(describe "agent.core"
  (fn []
    (it "returns object with expected shape"
      (fn []
        (let [agent (create-agent {:model "test-model"
                                   :system-prompt "you are helpful"})]
          (-> (expect (:events agent)) (.toBeTruthy))
          (-> (expect (:config agent)) (.toBeTruthy))
          (-> (expect (:tool-registry agent)) (.toBeTruthy))
          (-> (expect (:state agent)) (.toBeTruthy))
          (-> (expect (:steer-queue agent)) (.toBeTruthy))
          (-> (expect (:follow-queue agent)) (.toBeTruthy))
          (-> (expect (:commands agent)) (.toBeTruthy))
          (-> (expect (:shortcuts agent)) (.toBeTruthy)))))

    (it "config contains model and system-prompt"
      (fn []
        (let [agent (create-agent {:model "claude"
                                   :system-prompt "test prompt"})]
          (-> (expect (:model (:config agent))) (.toBe "claude"))
          (-> (expect (:system-prompt (:config agent))) (.toBe "test prompt")))))

    (it "defaults max-steps to 20"
      (fn []
        (let [agent (create-agent {:model "m"})]
          (-> (expect (:max-steps (:config agent))) (.toBe 20)))))

    (it "respects custom max-steps"
      (fn []
        (let [agent (create-agent {:model "m" :max-steps 5})]
          (-> (expect (:max-steps (:config agent))) (.toBe 5)))))

    (it "tool-registry contains builtin tools"
      (fn []
        (let [agent (create-agent {:model "m"})
              all   ((:all (:tool-registry agent)))]
          (-> (expect (get all "read")) (.toBeTruthy))
          (-> (expect (get all "write")) (.toBeTruthy))
          (-> (expect (get all "edit")) (.toBeTruthy))
          (-> (expect (get all "bash")) (.toBeTruthy)))))

    (it "merges custom tools with builtins"
      (fn []
        (let [custom-tool {:description "custom"}
              agent (create-agent {:model "m"
                                   :tools {"my-tool" custom-tool}})
              all   ((:all (:tool-registry agent)))]
          (-> (expect (get all "my-tool")) (.toBeTruthy))
          (-> (expect (get all "read")) (.toBeTruthy)))))

    (it "state starts with empty messages"
      (fn []
        (let [agent (create-agent {:model "m"})
              state @(:state agent)]
          (-> (expect (count (:messages state))) (.toBe 0)))))

    (it "event bus on agent is functional"
      (fn []
        (let [agent   (create-agent {:model "m"})
              events  (:events agent)
              called  (atom false)]
          ((:on events) "test" (fn [_] (reset! called true)))
          ((:emit events) "test" {})
          (-> (expect @called) (.toBe true)))))))
