(ns jsonl-stdin.test
  "The RPC channel is JSONL, and JSONL means LF — nothing else.

   Both RPC modes read stdin with `node:readline`, which also splits on U+2028
   and U+2029. Those are legal inside a JSON string, and ordinary in model
   output: JS source, minified bundles, pasted text. Measured under Bun 1.4.2,
   one valid record containing U+2028 came back as two, neither of which parsed.

   pi's own docs/rpc.md warns about exactly this:

     Node `readline` is not protocol-compliant for RPC mode because it also
     splits on U+2028 and U+2029, which are valid inside JSON strings.

   So the splitter is ours, and these are its rules."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:stream" :refer [Readable]]
            [agent.utils.jsonl-stdin :refer [split-records read-lines!]]))

;; Written as escapes: a literal U+2028 in source is invisible and gets
;; mangled by any tool that normalises line endings — including the one this
;; module exists to work around.
(def ^:private LS (js/String.fromCharCode 0x2028))
(def ^:private PS (js/String.fromCharCode 0x2029))

;;; ─── the pure splitter ──────────────────────────────────────

(describe "split-records"
          (fn []
            (it "splits on LF and keeps the remainder"
                (fn []
                  (let [[recs rest*] (split-records "a\nb\nc")]
                    (-> (expect (vec recs)) (.toEqual #js ["a" "b"]))
                    (-> (expect rest*) (.toBe "c")))))

            (it "does NOT split on U+2028 or U+2029"
                (fn []
                  ;; The whole reason this module exists.
                  (let [[recs rest*] (split-records (str "a" LS "b" PS "c" "\n"))]
                    (-> (expect (vec recs)) (.toEqual #js [(str "a" LS "b" PS "c")]))
                    (-> (expect rest*) (.toBe "")))))

            (it "strips a trailing CR but not an interior one"
                (fn []
                  (let [[recs _] (split-records "a\r\nb\r\n")]
                    (-> (expect (vec recs)) (.toEqual #js ["a" "b"])))
                  ;; A lone \r inside a record is data, not a delimiter.
                  (let [[recs _] (split-records "a\rb\n")]
                    (-> (expect (vec recs)) (.toEqual #js ["a\rb"])))))

            (it "yields nothing until a record is complete"
                (fn []
                  (let [[recs rest*] (split-records "{\"partial\":")]
                    (-> (expect (count recs)) (.toBe 0))
                    (-> (expect rest*) (.toBe "{\"partial\":")))))

            (it "handles empty input and bare newlines"
                (fn []
                  (-> (expect (count (first (split-records "")))) (.toBe 0))
                  (-> (expect (vec (first (split-records "\n\n")))) (.toEqual #js ["" ""]))))))

;;; ─── through a real stream ──────────────────────────────────

(defn- collect
  "Feed `chunks` through read-lines! and resolve with the records it produced."
  [chunks]
  (js/Promise.
   (fn [resolve _]
     (let [seen (atom [])]
       (read-lines! (.from Readable (clj->js chunks))
                    (fn [line] (swap! seen conj line))
                    (fn [] (resolve (clj->js @seen))))))))

(defn ^:async t-one-record-with-separators []
  ;; The exact shape that broke: a prompt whose message carries U+2028.
  (let [payload (js/JSON.stringify #js {:type "prompt" :message (str "line one" LS "line two")})
        out     (js-await (collect [(str payload "\n")]))]
    (-> (expect (.-length out)) (.toBe 1))
    ;; And it must still be parseable — the old behaviour produced two
    ;; fragments, neither of which was JSON.
    (-> (expect (.-message (js/JSON.parse (aget out 0)))) (.toContain LS))))

(defn ^:async t-records-split-across-chunks []
  ;; A transport may fragment anywhere, including mid-record and mid-escape.
  (let [out (js-await (collect ["{\"a\":1}\n{\"b\":" "2}\n" "{\"c\":3}\n"]))]
    (-> (expect (.-length out)) (.toBe 3))
    (-> (expect (.-b (js/JSON.parse (aget out 1)))) (.toBe 2))))

(defn ^:async t-crlf-and-trailing-record []
  (let [out (js-await (collect ["{\"a\":1}\r\n" "{\"b\":2}"]))]
    ;; CRLF is accepted, and a final record with no newline is still delivered
    ;; at end of stream rather than silently dropped.
    (-> (expect (.-length out)) (.toBe 2))
    (-> (expect (.-b (js/JSON.parse (aget out 1)))) (.toBe 2))))

(defn ^:async t-multibyte-across-chunk-boundary []
  ;; A UTF-8 character split across two chunks must not be corrupted.
  (let [payload (js/JSON.stringify #js {:m "héllo → 🌍"})
        mid     (js/Math.floor (/ (.-length payload) 2))
        out     (js-await (collect [(.slice payload 0 mid) (str (.slice payload mid) "\n")]))]
    (-> (expect (.-length out)) (.toBe 1))
    (-> (expect (.-m (js/JSON.parse (aget out 0)))) (.toBe "héllo → 🌍"))))

(describe "read-lines!"
          (fn []
            (it "delivers one record for a payload containing U+2028"
                t-one-record-with-separators)
            (it "reassembles records split across chunks" t-records-split-across-chunks)
            (it "accepts CRLF and flushes a trailing record" t-crlf-and-trailing-record)
            (it "does not corrupt a multibyte char split across chunks"
                t-multibyte-across-chunk-boundary)))
