(ns pdf-attach-test
  "PDFs and other documents in the prompt.

   `@doc.pdf` under 200 KB was inlined as PDF syntax; a Telegram PDF was
   dropped and its temp file left behind; claude-native dropped any non-image
   file part, and tool-result images with it. Now a PDF travels as a file part
   to a model that reads PDFs, as extracted text to one that doesn't, and
   anything unsupported is named rather than dropped."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.utils.content :as content]
            [agent.utils.pdf :as pdf]
            [agent.ui.file-mentions :as fm]
            [agent.sessions.images :as images]
            [agent.tools :as tools]
            [agent.extensions.custom-provider-claude-native.messages :as native]
            [gateway.loop :as gloop]
            [test-util.pdf-fixture :refer [make-pdf]]
            [test-util.fake-model-server :as fms]))

(def ^:private doc (make-pdf ["Hello PDF" "Second page"]))
(def ^:private b64 (.toString doc "base64"))

(describe "attachment-part"
          (fn []
            (it "image, PDF, text, and a note for the rest"
                (fn []
                  (-> (expect (.-type (content/attachment-part {:data "AQID" :media-type "image/png"}))) (.toBe "image"))
                  (let [p (content/attachment-part {:data b64 :media-type "application/pdf" :name "d.pdf"})]
                    (-> (expect (.-type p)) (.toBe "file"))
                    (-> (expect (.-pages p)) (.toBe 2))
                    (-> (expect (content/->text [p])) (.toBe "[file: d.pdf]")))
                  (-> (expect (.-text (content/attachment-part {:data (.toString (js/Buffer.from "a,b") "base64")
                                                                :media-type "text/csv" :name "t.csv"})))
                      (.toBe "<file path=\"t.csv\">\na,b\n</file>"))
                  (-> (expect (.-text (content/attachment-part {:data "x" :name "r.docx"
                                                                :media-type "application/vnd.openxmlformats-officedocument.wordprocessingml.document"})))
                      (.toContain "[attachment skipped: r.docx"))))

            (it "a PDF costs tokens per page, not nothing"
                (fn []
                  (-> (expect (content/attachment-tokens #js [#js {:type "text" :text "x"}
                                                              (content/attachment-part {:data b64 :media-type "application/pdf"})]))
                      (.toBe 4000))))))

(describe "pdf extraction"
          (fn []
            (it "unpdf reads every page" ^:async
                (fn []
                  (let [r (js-await (pdf/extract-text doc {:native false}))]
                    (-> (expect (:text r)) (.toContain "Hello PDF"))
                    (-> (expect (:text r)) (.toContain "Second page"))
                    (-> (expect (:pages r)) (.toBe 2)))))
            (it "a page range; pdftotext when present" ^:async
                (fn []
                  (let [r (js-await (pdf/extract-text doc {:from 2 :to 2}))]
                    (-> (expect (:text r)) (.toContain "Second page"))
                    (-> (expect (:text r)) (.not.toContain "Hello PDF")))))
            (it "garbage is a note, not a throw" ^:async
                (fn []
                  (-> (expect (:error (js-await (pdf/extract-text (js/Buffer.from "%PDF-junk") {:native false}))))
                      (.toContain "could not read PDF"))))))

(describe "who gets the PDF itself"
          (fn []
            (it "native providers and models.dev pdf models; not relays or local ones"
                (fn []
                  (-> (expect (pdf/native? "anthropic/claude-opus-5-5")) (.toBe true))
                  (-> (expect (pdf/native? "claude-opus-5-5")) (.toBe true))
                  (-> (expect (pdf/native? "faketest/m1")) (.toBe false))
                  (-> (expect (pdf/native? "relay/claude-opus-5-5")) (.toBe false))))

            (it "a model without PDF input gets the text; the session keeps the PDF" ^:async
                (fn []
                  (let [msgs [{:role "user" :content #js [#js {:type "text" :text "summarise"}
                                                          (content/attachment-part {:data b64 :media-type "application/pdf" :name "d.pdf"})]}]
                        low  (js-await (pdf/lower-for-model msgs "faketest/m1"))
                        same (js-await (pdf/lower-for-model msgs "anthropic/claude-opus-5-5"))]
                    (-> (expect (.-text (aget (:content (first low)) 1))) (.toContain "Hello PDF"))
                    (-> (expect (.-type (aget (:content (first msgs)) 1))) (.toBe "file"))
                    (-> (expect (identical? same msgs)) (.toBe true)))))))

(describe "@doc.pdf"
          (fn []
            (it "attaches the PDF instead of inlining its bytes"
                (fn []
                  (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-pdf-"))]
                    (fs/writeFileSync (path/join d "doc.pdf") doc)
                    (let [r (fm/expand-mentions "read @doc.pdf" d)
                          c (fm/->content r)]
                      (-> (expect (.includes (:text r) "%PDF")) (.toBe false))
                      (-> (expect (.-mediaType (aget c 1))) (.toBe "application/pdf")))
                    (fs/rmSync d #js {:recursive true}))))))

(describe "gateway documents"
          (fn []
            (it "a PDF reaches the agent, a docx is named, every temp file goes"
                (fn []
                  (let [d    (fs/mkdtempSync (path/join (os/tmpdir) "nyma-gw-doc-"))
                        f    (path/join d "nyma-tg-1-x.pdf")
                        docx (path/join d "r.docx")]
                    (fs/writeFileSync f doc)
                    (fs/writeFileSync docx "PK")
                    (let [c (gloop/msg->content {:text "see attached"
                                                 :attachments [{:local f :mime-type "application/pdf" :name "report.pdf"}
                                                               {:local docx :mime-type "application/msword"}]})]
                      (-> (expect (.-filename (aget c 1))) (.toBe "report.pdf"))
                      (-> (expect (.-text (aget c 2))) (.toContain "[attachment skipped: r.docx"))
                      (-> (expect (fs/existsSync f)) (.toBe false))
                      (-> (expect (fs/existsSync docx)) (.toBe false)))
                    (fs/rmSync d #js {:recursive true :force true}))))))

(describe "claude-native tool results"
          (fn []
            (it "keep a tool's image"
                (fn []
                  (let [b (native/build-request-body
                           "claude-opus-5-5"
                           #js {:prompt #js [#js {:role "tool"
                                                  :content #js [#js {:type "tool-result" :toolCallId "t1"
                                                                     :output #js {:type "content"
                                                                                  :value #js [#js {:type "text" :text "shot"}
                                                                                              #js {:type "image-data" :data "AQID" :mediaType "image/png"}]}}]}]})
                        blocks (get-in b ["messages" 0 "content" 0 "content"])]
                    (-> (expect (.-type (aget blocks 1))) (.toBe "image")))))))

(describe "read tool on a PDF"
          (fn []
            (it "returns the text by page, not PDF syntax" ^:async
                (fn []
                  (let [d (fs/mkdtempSync (path/join (os/tmpdir) "nyma-pdf-read-"))
                        f (path/join d "doc.pdf")]
                    (fs/writeFileSync f doc)
                    (let [all (js-await (tools/read-execute {:path f}))
                          p2  (js-await (tools/read-execute {:path f :range [1 1]}))]
                      (-> (expect all) (.toContain "Second page"))
                      (-> (expect all) (.not.toContain "%PDF"))
                      (-> (expect p2) (.toContain "Hello PDF"))
                      (-> (expect p2) (.toContain "1 more pages — read with range [2, 2]")))
                    (fs/rmSync d #js {:recursive true}))))))

(describe "session sidecar"
          (fn []
            (it "a PDF round-trips through the session's attachment dir"
                (fn []
                  (let [d  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-pdf-sess-"))
                        sf (path/join d "s.jsonl")
                        c  #js [#js {:type "text" :text "x"}
                                (content/attachment-part {:data b64 :media-type "application/pdf" :name "d.pdf"})]
                        ex (images/externalize c sf)
                        re (images/rehydrate ex sf)]
                    (-> (expect (str/ends-with? (.-ref (aget ex 1)) ".pdf")) (.toBe true))
                    (-> (expect (.-data (aget ex 1))) (.toBeUndefined))
                    (-> (expect (.-data (aget re 1))) (.toBe b64))
                    (-> (expect (.-filename (aget re 1))) (.toBe "d.pdf"))
                    (fs/rmSync d #js {:recursive true}))))))

(def ^:private cli (path/resolve (js/process.cwd) "dist" "agent" "cli.mjs"))

(defn ^:async wait-for [pred ms]
  (let [end (+ (js/Date.now) ms)]
    (loop []
      (cond (pred) true
            (> (js/Date.now) end) false
            :else (do (js-await (js/Bun.sleep 50)) (recur))))))

(defn ^:async test-pdf-over-rpc []
  (let [srv  (fms/start!)
        home (fms/temp-home! (:base-url srv))
        proc (js/Bun.spawn #js ["bun" cli "--mode" "rpc" "--model" "faketest/m1"]
                           #js {:cwd home :stdin "pipe" :stdout "pipe" :stderr "pipe"
                                :env (js/Object.assign #js {} js/process.env
                                                       #js {"HOME" home "NYMA_NO_MODEL_DISCOVERY" "1"})})]
    (.write (.-stdin proc) (str (js/JSON.stringify #js {:type "prompt" :message "summarise"
                                                        :files #js [#js {:data b64 :mimeType "application/pdf" :name "d.pdf"}]})
                                "\n"))
    (.flush (.-stdin proc))
    (try
      (-> (expect (js-await (wait-for #(pos? (count @(:requests srv))) 15000))) (.toBe true))
      ;; faketest has no PDF input: the request carries the text, not the file
      (let [body (js/JSON.stringify (first @(:requests srv)))]
        (-> (expect (.includes body "Hello PDF")) (.toBe true))
        (-> (expect (.includes body b64)) (.toBe false)))
      (finally
        (.kill proc)
        (js-await (.-exited proc))
        ((:stop srv))
        (try (fs/rmSync home #js {:recursive true :force true}) (catch :default _ nil))))))

((.skipIf describe (not (fs/existsSync cli))) "PDF end to end"
                                              (fn [] (it "rpc files[]: a PDF reaches a text-only model as its text" test-pdf-over-rpc 30000)))
