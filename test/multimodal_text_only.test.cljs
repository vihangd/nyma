(ns multimodal-text-only.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.multimodal :as mm]))

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
