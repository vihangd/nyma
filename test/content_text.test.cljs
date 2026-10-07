(ns content-text-test
  "Message content as text — strings, tool-call parts and images alike."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.utils.content :as content]
            [agent.token-estimation :as te]
            [agent.sessions.listing :as listing]))

(def ^:private with-image
  #js [#js {:type "text" :text "what is wrong here?"}
       #js {:type "image" :image (.repeat "A" 1000000) :mediaType "image/png" :name "shot.png"}])

(describe "->text"
          (fn []
            (it "keeps text, labels images and other parts, never [object Object]"
                (fn []
                  (-> (expect (content/->text "plain")) (.toBe "plain"))
                  (-> (expect (content/->text nil)) (.toBe ""))
                  (-> (expect (content/->text with-image)) (.toBe "what is wrong here?\n[image: shot.png]"))
                  (-> (expect (content/->text #js [#js {:type "tool-call" :toolName "bash"}])) (.toBe "[tool-call]"))
                  (-> (expect (content/->text #js [#js {:type "file" :mediaType "image/jpeg" :data "x"}])) (.toBe "[image]"))))))

(describe "token estimate of an image"
          (fn []
            (it "is a fixed per-image cost, not the base64 length"
                (fn []
                  (let [n (te/estimate-messages-tokens [{:role "user" :content with-image}])]
                    ;; 1 MB of base64 read as prose was ~276k tokens.
                    (-> (expect (< n 2000)) (.toBe true))
                    (-> (expect (> n content/image-token-estimate)) (.toBe true)))))))

(describe "session titles"
          (fn []
            (it "use the caption, not [object Object]"
                (fn []
                  (-> (expect (listing/derive-title [#js {:role "user" :content with-image}]))
                      (.toBe "what is wrong here? [image: shot.png]"))))))
