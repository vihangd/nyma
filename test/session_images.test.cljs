(ns session-images-test
  "Image bytes are written beside the session file, not into it, and read
   back when the session is resumed."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.sessions.manager :as sm]
            [agent.extensions.custom-provider-claude-native.messages :as native]))

(def ^:private png-b64 (.toString (js/Buffer.from "fake-png-bytes-1234567890") "base64"))

(defn- image-msg []
  #js [#js {:type "text" :text "look at this"}
       #js {:type "image" :image png-b64 :mediaType "image/png" :name "shot.png"}])

(describe "session images"
          (fn []
            (it "the JSONL holds a reference; the bytes come back on resume"
                (fn []
                  (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-simg-"))
                        f (path/join d "s.jsonl")
                        s (sm/create-session-manager f)]
                    ((:append s) {:role "user" :content (image-msg)})
                    (let [raw (fs/readFileSync f "utf8")]
                      (-> (expect (.includes raw png-b64)) (.toBe false))
                      (-> (expect (.includes raw "\"ref\"")) (.toBe true)))
                    (-> (expect (count (fs/readdirSync (str f ".images")))) (.toBe 1))
                    ;; A fresh manager over the same file: the model gets bytes again.
                    (let [s2  (sm/create-session-manager f)
                          _   ((:load s2))
                          msg (first ((:build-context s2)))
                          img (second (vec (:content msg)))]
                      (-> (expect (.-image img)) (.toBe png-b64))
                      (-> (expect (.-mediaType img)) (.toBe "image/png")))
                    (fs/rmSync d #js {:recursive true}))))

            (it "an image whose file is gone becomes a note, not a failure"
                (fn []
                  (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-simg-"))
                        f (path/join d "s.jsonl")
                        s (sm/create-session-manager f)]
                    ((:append s) {:role "user" :content (image-msg)})
                    (fs/rmSync (str f ".images") #js {:recursive true})
                    (let [s2 (sm/create-session-manager f)
                          _  ((:load s2))
                          c  (vec (:content (first ((:build-context s2)))))]
                      (-> (expect (.-text (second c))) (.toBe "[image missing: shot.png]")))
                    (fs/rmSync d #js {:recursive true}))))))

(describe "claude-native provider"
          (fn []
            ;; It converted text and tool-call parts only: an image was dropped
            ;; without a word.
            (it "sends an attached image as an Anthropic image block"
                (fn []
                  (-> (expect (js/JSON.stringify
                               (native/image-part->anthropic #js {:type "file" :mediaType "image/png"
                                                                  :data (js/Uint8Array. #js [1 2 3])})))
                      (.toBe "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"AQID\"}}"))
                  (-> (expect (native/image-part->anthropic #js {:type "file" :mediaType "application/pdf" :data "x"}))
                      (.toBeFalsy))))))
