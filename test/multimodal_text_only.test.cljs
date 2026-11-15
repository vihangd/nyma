(ns multimodal-text-only.test
  (:require ["bun:test" :refer [describe it expect]]
            ["ai/test" :refer [MockLanguageModelV4]]
            [agent.loop :refer [run]]
            [agent.multimodal :as mm]
            [test-util.agent-harness :as h]))

(describe "images and text-only models" (fn []
  (it "knows which models declare no image input, gateway ids included"
    (fn []
      (-> (expect (mm/text-only? "deepseek/deepseek-v4-pro")) (.toBe true))
      (-> (expect (mm/text-only? "openlux/deepseek-v4-pro")) (.toBe true))
      (-> (expect (mm/text-only? "zai/glm-5.3")) (.toBe true))
      (-> (expect (mm/text-only? "anthropic/claude-haiku-4-5")) (.toBe false))
      (-> (expect (mm/text-only? "acme/never-heard-of-it")) (.toBe false))))

  (it "replaces attached and tool-result images with a note, for a text-only model"
    (fn []
      (let [img  #js {:type "file" :mediaType "image/png" :data "AAAA"}
            msgs #js [#js {:role "user" :content #js [#js {:type "text" :text "look"} img]}
                      #js {:role "tool" :content #js [#js {:type "tool-result" :toolCallId "t"
                                                           :output #js {:type "content" :value #js [img]}}]}]
            out  (mm/lower-images msgs "deepseek/deepseek-v4-pro")]
        (-> (expect (.-type (aget (.-content (aget out 0)) 1))) (.toBe "text"))
        (-> (expect (.-type (aget (.. (aget (.-content (aget out 1)) 0) -output -value) 0))) (.toBe "text"))
        ;; a vision model gets them as they are
        (-> (expect (identical? msgs (mm/lower-images msgs "anthropic/claude-haiku-4-5"))) (.toBe true)))))))

(defn ^:async test-text-only-step-messages []
  ;; the SDK carries what prepareStep returns into the next step's `messages`:
  ;; built from them, kv_cache's per-step marks compounded past Anthropic's 4
  (let [agent (h/make-test-agent {:model (new MockLanguageModelV4
                                               #js {:modelId "deepseek-v4-pro" :provider "deepseek"})})
        cfg   (atom nil)]
    ((:on (:events agent)) "before_provider_request"
                           (fn [c _] (reset! cfg c) #js {:block true}))
    (js-await (run agent "go"))
    (let [init #js [#js {:role "user" :content "go"}]
          resp #js [#js {:role "assistant" :content "ok"}]
          out  (.prepareStep @cfg #js {:stepNumber 1 :initialMessages init :responseMessages resp
                                        :messages (.concat init resp #js [#js {:role "user" :content "stale"}])})]
      (-> (expect (.. out -messages -length)) (.toBe 2)))))

(describe "text-only prepareStep" (fn []
  (it "builds each step from initial + response messages, not the carried list"
      test-text-only-step-messages)))
