(ns rpc-images-test
  "Images over the JSONL protocols: rpc and pi-rpc read `images` on a prompt.

   Both read `message` only, so a frontend that attached a screenshot sent
   the text alone."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [gateway.loop :as gloop]
            [agent.utils.content :as content]
            [test-util.fake-model-server :as fms]))

(describe "prompt-content"
          (fn []
            (it "text alone stays a string; images become parts, pi or AI-SDK shape"
                (fn []
                  (-> (expect (content/prompt-content "hi" nil)) (.toBe "hi"))
                  (-> (expect (content/prompt-content "hi" #js [])) (.toBe "hi"))
                  (let [c (content/prompt-content "look" #js [#js {:type "image" :data "AQID" :mimeType "image/png"}])]
                    (-> (expect (.-text (aget c 0))) (.toBe "look"))
                    (-> (expect (.-image (aget c 1))) (.toBe "AQID"))
                    (-> (expect (.-mediaType (aget c 1))) (.toBe "image/png")))
                  ;; junk entries are dropped, not sent as broken parts
                  (-> (expect (content/prompt-content "x" #js [#js {:mimeType "image/png"}])) (.toBe "x"))))))

(def ^:private cli (path/resolve (js/process.cwd) "dist" "agent" "cli.mjs"))

(defn ^:async wait-for [pred ms]
  (let [end (+ (js/Date.now) ms)]
    (loop []
      (cond (pred) true
            (> (js/Date.now) end) false
            :else (do (js-await (js/Bun.sleep 50)) (recur))))))

(defn ^:async run-mode [mode line]
  (let [srv  (fms/start!)
        home (fms/temp-home! (:base-url srv))
        proc (js/Bun.spawn #js ["bun" cli "--mode" mode "--model" "faketest/m1"]
                           #js {:cwd home :stdin "pipe" :stdout "pipe" :stderr "pipe"
                                :env (js/Object.assign #js {} js/process.env
                                                       #js {"HOME" home "NYMA_NO_MODEL_DISCOVERY" "1"})})]
    (.write (.-stdin proc) (str line "\n"))
    (.flush (.-stdin proc))
    (try
      (js-await (wait-for #(pos? (count @(:requests srv))) 15000))
      (js/JSON.stringify (first @(:requests srv)))
      (finally
        (.kill proc)
        (js-await (.-exited proc))
        ((:stop srv))
        (try (fs/rmSync home #js {:recursive true :force true}) (catch :default _ nil))))))

(def ^:private png-b64 "iVBORw0KGgo=")

(defn ^:async test-rpc []
  (let [body (js-await (run-mode "rpc" (js/JSON.stringify #js {:type "prompt" :message "what is this"
                                                               :images #js [#js {:data png-b64 :mimeType "image/png"}]})))]
    (-> (expect (.includes (str body) (str "data:image/png;base64," png-b64))) (.toBe true))))

(defn ^:async test-pi-rpc []
  (let [body (js-await (run-mode "pi-rpc" (js/JSON.stringify #js {:id "1" :type "prompt" :message "what is this"
                                                                  :images #js [#js {:type "image" :data png-b64 :mimeType "image/png"}]})))]
    (-> (expect (.includes (str body) (str "data:image/png;base64," png-b64))) (.toBe true))))

((.skipIf describe (not (fs/existsSync cli))) "images over the JSONL protocols"
                                              (fn []
                                                (it "rpc: a prompt's images reach the model" test-rpc 30000)
                                                (it "pi-rpc: a prompt's images reach the model" test-pi-rpc 30000)))

(describe "gateway attachments"
          (fn []
            ;; Telegram downloaded the photo; the loop sent (:text msg) alone.
            (it "image and PDF attachments reach the agent; temp files go"
                (fn []
                  (let [d   (fs/mkdtempSync (path/join (os/tmpdir) "nyma-gw-att-"))
                        img (path/join d "p.jpg")
                        doc (path/join d "d.pdf")]
                    (fs/writeFileSync img "jpegbytes")
                    (fs/writeFileSync doc "pdf")
                    (let [c (gloop/msg->content {:text "what is this?"
                                                 :attachments [{:local img :mime-type "image/jpeg"}
                                                               {:local-path doc :mime-type "application/pdf"}]})]
                      (-> (expect (.-length c)) (.toBe 3))
                      (-> (expect (.-mediaType (aget c 1))) (.toBe "image/jpeg"))
                      (-> (expect (.-mediaType (aget c 2))) (.toBe "application/pdf"))
                      (-> (expect (fs/existsSync img)) (.toBe false))
                      (-> (expect (fs/existsSync doc)) (.toBe false)))
                    (-> (expect (gloop/msg->content {:text "plain"})) (.toBe "plain"))
                    (fs/rmSync d #js {:recursive true :force true}))))))
