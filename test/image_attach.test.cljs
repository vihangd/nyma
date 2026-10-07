(ns image-attach-test
  "Images in the prompt: `@shot.png` attaches the image, and a clipboard image
   pasted with ctrl+v becomes such a mention.

   `@shot.png` used to be skipped as binary — the model got
   `<file skipped=\"binary\"/>` instead of the screenshot."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.ui.file-mentions :as fm]
            [test-util.fake-model-server :as fms]))

(def ^:private png-bytes
  (js/Uint8Array. #js [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A 0 0 0 13 1 2 3 4]))

(describe "@image mentions"
          (fn []
            (it "attach the image as a part and keep the text"
                (fn []
                  (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-img-"))]
                    (fs/writeFileSync (path/join d "shot.png") png-bytes)
                    (let [r (fm/expand-mentions "what is wrong in @shot.png ?" d)
                          c (fm/->content r)]
                      (-> (expect (count (:images r))) (.toBe 1))
                      (-> (expect (.includes (:text r) "skipped")) (.toBe false))
                      (-> (expect (.-type (aget c 1))) (.toBe "image"))
                      (-> (expect (.-mediaType (aget c 1))) (.toBe "image/png"))
                      (-> (expect (.-text (aget c 0))) (.toContain "what is wrong")))
                    ;; no image → still a plain string, exactly as before
                    (-> (expect (fm/->content (fm/expand-mentions "hello" d))) (.toBe "hello"))
                    (fs/rmSync d #js {:recursive true}))))))

(describe "pasted images"
          (fn []
            (it "are saved and become an @ mention; non-images are refused"
                (fn []
                  (let [dir   (path/join (.. js/process -env -HOME) ".nyma" "pasted")
                        token (fm/save-pasted-image! png-bytes dir)]
                    (-> (expect (str/starts-with? token "@~/.nyma/pasted/")) (.toBe true))
                    (-> (expect (str/ends-with? token ".png")) (.toBe true))
                    (-> (expect (fs/existsSync (path/join (.. js/process -env -HOME) (subs token 2)))) (.toBe true))
                    (-> (expect (fm/save-pasted-image! (js/Uint8Array. #js [1 2 3]) dir)) (.toBeFalsy)))))))

(def ^:private cli (path/resolve (js/process.cwd) "dist" "agent" "cli.mjs"))

(defn- strip [s] (.replace s (js/RegExp. "\u001b\\[[0-9;?]*[a-zA-Z]" "g") ""))

(defn ^:async wait-for [pred ms]
  (let [end (+ (js/Date.now) ms)]
    (loop []
      (cond (pred) true
            (> (js/Date.now) end) false
            :else (do (js-await (js/Bun.sleep 50)) (recur))))))

(defn ^:async test-image-reaches-model []
  (let [srv  (fms/start!)
        home (fms/temp-home! (:base-url srv))
        _    (fs/writeFileSync (path/join home "shot.png") png-bytes)
        buf  (atom "")
        dec  (js/TextDecoder.)
        proc (js/Bun.spawn #js ["bun" cli "--model" "faketest/m1"]
                           #js {:cwd home
                                :env (js/Object.assign #js {} js/process.env
                                                       #js {"HOME" home "NYMA_NO_MODEL_DISCOVERY" "1" "TERM" "xterm-256color"})
                                :terminal #js {:cols 120 :rows 40
                                               :data (fn [_ d] (swap! buf str (.decode dec d #js {:stream true})))}})]
    (try
      (-> (expect (js-await (wait-for #(str/includes? (strip @buf) "ready") 15000))) (.toBe true))
      (.write (.-terminal proc) "what is in @shot.png")
      (js-await (js/Bun.sleep 300))
      ;; Escape closes the @ autocomplete popup; Enter would pick from it.
      (.write (.-terminal proc) "\u001b")
      (js-await (js/Bun.sleep 200))
      (.write (.-terminal proc) "\u001b[13u")
      (-> (expect (js-await (wait-for #(pos? (count @(:requests srv))) 15000))) (.toBe true))
      (let [body (js/JSON.stringify (first @(:requests srv)))]
        ;; OpenAI-compatible wire: the image travels as a data URL part.
        (-> (expect (.includes body "data:image/png;base64,")) (.toBe true))
        (-> (expect (.includes body "what is in")) (.toBe true)))
      ;; and the transcript shows a marker, not [object Object]
      (-> (expect (js-await (wait-for #(str/includes? (strip @buf) "[image: shot.png]") 5000))) (.toBe true))
      (finally
        (.kill proc)
        (js-await (.-exited proc))
        ((:stop srv))
        (try (fs/rmSync home #js {:recursive true :force true}) (catch :default _ nil))))))

((.skipIf describe (not (fs/existsSync cli))) "image attach end to end"
                                              (fn [] (it "@shot.png reaches the model as an image" test-image-reaches-model 45000)))

(describe "clipboard fallback"
          (fn []
            ;; pi-tui's native helper cannot load in the compiled binary
            ;; (computed .node path); macOS falls back to osascript.
            (it "parses osascript's PNG data"
                (fn []
                  (-> (expect (vec (fm/parse-osascript-png "«data PNGf89504E47»"))) (.toEqual [0x89 0x50 0x4E 0x47]))
                  (-> (expect (fm/parse-osascript-png "")) (.toBeFalsy))))))
