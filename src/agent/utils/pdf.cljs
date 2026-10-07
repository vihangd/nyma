(ns agent.utils.pdf
  "PDFs: who can read one natively, and text out of one for whoever can't.

   Anthropic, OpenAI, Google and OpenRouter take a PDF as a `file` part and
   read its pages (text and images). Everyone else — an OpenAI-compatible
   relay, a local model — gets the extracted text instead, swapped in at send
   time so the session keeps the PDF and a later switch to a capable model
   still sends it.

   Extraction: poppler's `pdftotext` when it is on PATH (fast, keeps layout),
   else `unpdf` (pdf.js), imported on first use. It never throws; a failure is
   a note naming the file."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.pricing-data :as pricing-data]
            [agent.debug :as dbg]))

(def max-bytes
  "Anthropic's per-request limit, the strictest of the native providers."
  (* 32 1024 1024))

(defn pdf-bytes?
  "Does this Buffer/Uint8Array start with the `%PDF-` magic?"
  [b]
  (and (some? b) (>= (.-length b) 5)
       (= "%PDF-" (.toString (js/Buffer.from (.subarray b 0 5)) "latin1"))))

(defn page-count
  "Pages in a PDF by counting `/Type /Page` objects, or nil when none are
   visible (object streams can compress them away). An estimate for token
   budgets and caps, not a parser."
  [b]
  (let [m (.match (.toString (js/Buffer.from b) "latin1") (js/RegExp. "/Type\\s*/Page(?![s\\w])" "g"))]
    (when m (.-length m))))

(def ^:private native-providers #{"anthropic" "claude-native" "openai" "google" "openrouter"})

(defn native?
  "Does the model behind `model-key` (`provider/id`) take a PDF part?"
  [model-key]
  (let [k (str model-key)
        i (.indexOf k "/")
        p (when (pos? i) (.slice k 0 i))
        id (if (pos? i) (.slice k (inc i)) k)]
    (or (contains? native-providers p)
        (contains? pricing-data/pdf-input k)
        (and (nil? p) (contains? pricing-data/pdf-input id)))))

(defn- spawn-text [file first-page last-page]
  (let [proc (js/Bun.spawnSync #js ["pdftotext" "-layout" "-f" (str first-page) "-l" (str last-page) file "-"]
                               #js {:stdout "pipe" :stderr "pipe" :timeout 30000})]
    (when (zero? (.-exitCode proc))
      (.toString (.-stdout proc)))))

(defn ^:async extract-text
  "Text of pages `from`..`to` (1-based, inclusive) of a PDF given as a
   Buffer. Returns {:text :pages :via} or {:error msg}. `:native false`
   skips pdftotext (tests, or to compare)."
  [buf {:keys [from to native]}]
  (let [from (max 1 (or from 1))
        to   (or to 1000000)]
    (or (when (and (not= false native) (js/Bun.which "pdftotext"))
          (let [tmp (path/join (os/tmpdir) (str "nyma-pdf-" (js/Date.now) "-" (js/Math.random) ".pdf"))]
            (try
              (fs/writeFileSync tmp buf)
              (when-let [t (spawn-text tmp from (min to (or (page-count buf) to)))]
                {:text t :pages (page-count buf) :via "pdftotext"})
              (catch :default e (dbg/debug "pdf" (str "pdftotext: " (.-message e))) nil)
              (finally (try (fs/unlinkSync tmp) (catch :default _ nil))))))
        (try
          (let [m     (js-await (js/import "unpdf"))
                doc   (js-await ((.-getDocumentProxy m) (js/Uint8Array. buf)))
                r     (js-await ((.-extractText m) doc #js {:mergePages false}))
                pages (vec (.-text r))]
            {:text  (.join (clj->js (subvec pages (min (dec from) (count pages)) (min to (count pages))))
                           "\n\f")
             :pages (.-totalPages r)
             :via   "unpdf"})
          (catch :default e {:error (str "could not read PDF: " (.-message e))})))))

(defn- pdf-part? [p]
  (and (some? p) (not (string? p))
       (= "file" (aget p "type"))
       (= "application/pdf" (aget p "mediaType"))))

(defn- has-pdf? [messages]
  (some (fn [m] (let [c (or (:content m) (aget m "content"))]
                  (and (or (vector? c) (js/Array.isArray c)) (some pdf-part? c))))
        messages))

(defn ^:async part->text
  "A PDF part as the text part a model without PDF input gets instead."
  [p]
  (let [name (or (aget p "filename") "document.pdf")
        r    (js-await (extract-text (js/Buffer.from (str (aget p "data")) "base64") {}))]
    #js {:type "text"
         :text (if (:error r)
                 (str "[" name ": " (:error r) "]")
                 (str "<file path=\"" name "\" pages=\"" (or (:pages r) "?") "\">\n" (:text r) "\n</file>"))}))

(defn ^:async lower-for-model
  "`messages` with every PDF part replaced by its text, unless the model
   reads PDFs itself. Returns `messages` untouched when there is no PDF."
  [messages model-key]
  (if (or (native? model-key) (not (has-pdf? messages)))
    messages
    (let [out #js []]
      (dbg/debug "pdf" (str model-key " has no PDF input; sending extracted text"))
      (doseq [m messages]
        (let [c (or (:content m) (aget m "content"))]
          (if (and (or (vector? c) (js/Array.isArray c)) (some pdf-part? c))
            (let [parts #js []]
              (doseq [p c] (.push parts (if (pdf-part? p) (js-await (part->text p)) p)))
              (.push out (assoc m :content parts)))
            (.push out m))))
      (vec out))))
