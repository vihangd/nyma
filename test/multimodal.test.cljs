(ns multimodal.test
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.multimodal :as mm]
            [agent.middleware :as mw]
            [agent.tools :as tools]
            [agent.extensions.mcp-client.tool-bridge :as bridge]))

;; ── multimodal helpers ───────────────────────────────────────────

(describe "multimodal helpers" (fn []

                                 (it "media-type-for maps extensions, nil for unknown"
                                     (fn []
                                       (-> (expect (mm/media-type-for "a.png")) (.toBe "image/png"))
                                       (-> (expect (mm/media-type-for "A.JPG")) (.toBe "image/jpeg"))
                                       (-> (expect (mm/media-type-for "x.webp")) (.toBe "image/webp"))
                                       (-> (expect (mm/media-type-for "x.gif")) (.toBe "image/gif"))
                                       (-> (expect (mm/media-type-for "x.txt")) (.toBeNil))))

                                 (it "sniff-media-type reads magic bytes (extension-less files)"
                                     (fn []
                                       (-> (expect (mm/sniff-media-type (js/Uint8Array. #js [0x89 0x50 0x4E 0x47 0 0]))) (.toBe "image/png"))
                                       (-> (expect (mm/sniff-media-type (js/Uint8Array. #js [0xFF 0xD8 0xFF 0]))) (.toBe "image/jpeg"))
                                       (-> (expect (mm/sniff-media-type (js/Uint8Array. #js [0x47 0x49 0x46]))) (.toBe "image/gif"))
                                       (-> (expect (mm/sniff-media-type (js/Uint8Array. #js [1 2 3 4]))) (.toBeNil))))

                                 (it "image-result carries a file content part + summary"
                                     (fn []
                                       (let [r (mm/image-result "QkFTRTY0" "image/png" "an image")]
                                         (-> (expect (.-summary r)) (.toBe "an image"))
                                         (-> (expect (.. r -content (at 0) -type)) (.toBe "file"))
                                         (-> (expect (.. r -content (at 0) -data)) (.toBe "QkFTRTY0"))
                                         (-> (expect (.. r -content (at 0) -mediaType)) (.toBe "image/png")))))

                                 (it "tool-model-output: content output → content; string → text"
                                     (fn []
                                       (let [img (mm/image-result "B64" "image/png" "s")
                                             oc  (mm/tool-model-output #js {:output img})
                                             ot  (mm/tool-model-output #js {:output "hello"})]
                                         (-> (expect (.-type oc)) (.toBe "content"))
                                         (-> (expect (.. oc -value (at 0) -type)) (.toBe "file"))
                                         (-> (expect (.-type ot)) (.toBe "text"))
                                         (-> (expect (.-value ot)) (.toBe "hello")))))))

;; ── normalize-tool-result: summary/placeholder, never [object Object] ──

(describe "normalize-tool-result" (fn []

                                    (it "uses :summary for a multimodal result"
                                        (fn []
                                          (-> (expect (mw/normalize-tool-result (mm/image-result "B64" "image/png" "rendered slide 1")))
                                              (.toBe "rendered slide 1"))))

                                    (it "never stringifies a file part to [object Object]"
                                        (fn []
                                          (let [s (mw/normalize-tool-result #js {:content #js [(mm/file-part "B64" "image/png")]})]
                                            (-> (expect (.includes s "[object Object]")) (.toBe false))
                                            (-> (expect (.includes s "file content")) (.toBe true)))))

                                    (it "passes strings and text-content through"
                                        (fn []
                                          (-> (expect (mw/normalize-tool-result "plain")) (.toBe "plain"))
                                          (-> (expect (mw/normalize-tool-result #js {:content #js [#js {:type "text" :text "hi"}]})) (.toBe "hi"))))))

;; ── wrap-tools-with-middleware routing (regression guard) ─────────

(describe "wrap-tools-with-middleware routing" (fn []

                                                 (it "raw result ONLY when toModelOutput AND content parts; else truncated :result string"
                                                     (fn []
                                                       (let [raw      (mm/image-result "B64" "image/png" "sum")
                                                             ;; pipeline gives content-parts only to a tool flagged :hasContent
                                                             pipeline {:execute (fn [_name t _args]
                                                                                  (js/Promise.resolve
                                                                                   (if (.-hasContent t)
                                                                                     {:result "sum" :raw-result raw :result-content-parts (.-content raw)}
                                                                                     {:result "TRUNC"})))}
                                                             img-tool  #js {:toModelOutput mm/tool-model-output :hasContent true :execute (fn [_])}
                                                             mcp-text  #js {:toModelOutput mm/tool-model-output :execute (fn [_])} ; toModelOutput but NO content
                                                             plain-txt #js {:execute (fn [_])}
                                                             wrapped   (mw/wrap-tools-with-middleware
                                                                        {"img" img-tool "mcp" mcp-text "txt" plain-txt} pipeline nil)]
                                                         (-> (.then ((.-execute (get wrapped "img")) #js {})
                                                                    (fn [out] (-> (expect (.-summary out)) (.toBe "sum")))) ; image → raw obj
                                                             (.then (fn [_] ((.-execute (get wrapped "mcp")) #js {})))
                                                             (.then (fn [out] (-> (expect out) (.toBe "TRUNC")))) ; toModelOutput+no content → truncated string
                                                             (.then (fn [_] ((.-execute (get wrapped "txt")) #js {})))
                                                             (.then (fn [out] (-> (expect out) (.toBe "TRUNC")))))))))) ; no toModelOutput → string

;; ── MCP split-content: images become file parts ──────────────────

(describe "mcp split-content" (fn []

                                (it "separates text and maps image items (data/mimeType off :raw) to file parts"
                                    (fn []
                                      ;; client/call-tool! shape: {:type :text :raw <js item>} — base64 + type on :raw
                                      (let [r (bridge/split-content [{:type "text" :text "here" :raw #js {:type "text" :text "here"}}
                                                                     {:type "image" :text nil :raw #js {:type "image" :data "B64" :mimeType "image/jpeg"}}])]
                                        (-> (expect (:text r)) (.toBe "here"))
                                        (-> (expect (count (:images r))) (.toBe 1))
                                        (-> (expect (.-type (first (:images r)))) (.toBe "file"))
                                        (-> (expect (.-data (first (:images r)))) (.toBe "B64"))
                                        (-> (expect (.-mediaType (first (:images r)))) (.toBe "image/jpeg")))))

                                (it "drops an image item with no base64 data"
                                    (fn []
                                      (-> (expect (count (:images (bridge/split-content
                                                                   [{:type "image" :raw #js {:type "image"}}]))))
                                          (.toBe 0))))

                                (it "text-only content yields no images"
                                    (fn []
                                      (-> (expect (count (:images (bridge/split-content [{:type "text" :text "x"}])))) (.toBe 0))))))

;; ── view_image tool ──────────────────────────────────────────────

(def ^:private tmp (atom nil))
;; 1x1 transparent PNG
(def ^:private png-b64
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==")

(beforeEach (fn []
              (let [d (path/join (os/tmpdir) (str "nyma-mm-" (js/Date.now)))]
                (fs/mkdirSync d #js {:recursive true})
                (reset! tmp d))))
(afterEach (fn []
             (when @tmp (try (fs/rmSync @tmp #js {:recursive true :force true}) (catch :default _)))))

(describe "view_image tool" (fn []

                              (it "returns a file content part for a real PNG"
                                  (fn []
                                    (let [p (path/join @tmp "x.png")]
                                      (fs/writeFileSync p (js/Buffer.from png-b64 "base64"))
                                      (-> (tools/view-image-execute {:path p})
                                          (.then (fn [r]
                                                   (-> (expect (.. r -content (at 0) -type)) (.toBe "file"))
                                                   (-> (expect (.. r -content (at 0) -mediaType)) (.toBe "image/png"))
                                                   (-> (expect (> (count (.. r -content (at 0) -data)) 0)) (.toBe true))
                                                   (-> (expect (.includes (.-summary r) "x.png")) (.toBe true))))))))

                              (it "sniffs magic bytes for an extension-less image"
                                  (fn []
                                    (let [p (path/join @tmp "screenshot")] ; no extension
                                      (fs/writeFileSync p (js/Buffer.from png-b64 "base64"))
                                      (-> (tools/view-image-execute {:path p})
                                          (.then (fn [r]
                                                   (-> (expect (.. r -content (at 0) -mediaType)) (.toBe "image/png"))))))))

                              (it "errors (string) on a missing file"
                                  (fn []
                                    (-> (tools/view-image-execute {:path (path/join @tmp "nope.png")})
                                        (.then (fn [r] (-> (expect (.includes (str r) "not found")) (.toBe true)))))))

                              (it "the tool carries toModelOutput (so middleware surfaces the image)"
                                  (fn []
                                    (-> (expect (some? (.-toModelOutput tools/view-image-tool))) (.toBe true))))))
