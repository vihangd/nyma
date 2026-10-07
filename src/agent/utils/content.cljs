(ns agent.utils.content
  "Message :content as text, for every reader that wants text.

   :content is a string for most messages, but an assistant turn that called
   tools is a vector of parts, and a user turn with an attached image is too.
   `(str content)` on a vector yields `[object Object],[object Object]`: the
   caption vanished from titles, summaries, search and the transcript, and an
   image counted as ~9 tokens. One helper, used everywhere text is wanted."
  (:require [clojure.string :as str]
            [agent.utils.pdf :as pdf]))

(defn image-part?
  "An AI-SDK image part: `{type: image}`, or a `file` part with an image/
   media type."
  [p]
  (and (some? p) (not (string? p))
       (let [t  (or (:type p) (aget p "type"))
             mt (str (or (:mediaType p) (aget p "mediaType") ""))]
         (or (= t "image") (and (= t "file") (str/starts-with? mt "image/"))))))

(defn file-part?
  "A non-image AI-SDK `file` part (a PDF)."
  [p]
  (and (some? p) (not (string? p))
       (= "file" (or (:type p) (aget p "type")))
       (not (image-part? p))))

(defn- parts [content]
  (when (or (vector? content) (js/Array.isArray content)) content))

(defn- part->text [b]
  (cond
    (string? b)     b
    (image-part? b) (if-let [n (or (:name b) (aget b "name"))] (str "[image: " n "]") "[image]")
    (file-part? b)  (str "[file: " (or (aget b "filename") (aget b "mediaType") "attachment") "]")
    (some? (or (:text b) (aget b "text"))) (str (or (:text b) (aget b "text")))
    :else           (str "[" (or (:type b) (aget b "type") "content") "]")))

(defn ->text
  "Flatten :content to a string: text parts joined by newlines, an image as
   `[image]` (or `[image: name]`), any other part as `[type]`. Strings pass
   through; nil is \"\"."
  [content]
  (cond
    (string? content) content
    (nil? content)    ""
    (parts content)   (str/join "\n" (map part->text (parts content)))
    :else             (str content)))

(defn image-count [content]
  (count (filter image-part? (or (parts content) []))))

(defn file-count [content]
  (count (filter file-part? (or (parts content) []))))

(def image-token-estimate
  "Rough tokens per attached image. Providers charge by pixels (Anthropic
   ~1.6k for a 1092x1092 image); a fixed figure keeps budgets honest without
   decoding the image."
  1600)

(def pdf-page-token-estimate
  "Rough tokens per PDF page: providers send each page's text AND an image of
   it (Anthropic quotes 1.5-3k per page)."
  2000)

(defn attachment-tokens
  "Estimated tokens for the images and PDFs in `content`. A PDF whose page
   count is unknown counts as one page."
  [content]
  (+ (* image-token-estimate (image-count content))
     (reduce + 0 (map #(* pdf-page-token-estimate (or (aget % "pages") 1))
                      (filter file-part? (or (parts content) []))))))

(def ^:private text-types #{"application/json" "application/xml" "application/x-yaml" "application/javascript"})

(defn attachment-part
  "One attachment — base64 `data`, `media-type`, `name` — as the part a prompt
   carries: an image part, a PDF `file` part (agent.utils.pdf swaps in text
   for a model that can't read it), a text file inlined as `<file>`, or a
   note for anything else. A docx or zip used to vanish without a word."
  [{:keys [data media-type name]}]
  (let [mt (str (or media-type ""))
        nm (or name "attachment")]
    (cond
      (str/starts-with? mt "image/")
      #js {:type "image" :image data :mediaType mt :name name}

      (= mt "application/pdf")
      (let [b (js/Buffer.from (str data) "base64")]
        (if (> (.-length b) pdf/max-bytes)
          #js {:type "text" :text (str "[attachment skipped: " nm " — PDF over 32 MB]")}
          #js {:type "file" :data data :mediaType mt :filename nm :pages (pdf/page-count b)}))

      (or (str/starts-with? mt "text/") (contains? text-types mt))
      #js {:type "text" :text (str "<file path=\"" nm "\">\n" (.toString (js/Buffer.from (str data) "base64") "utf-8") "\n</file>")}

      :else
      #js {:type "text" :text (str "[attachment skipped: " nm " — " (if (seq mt) mt "unknown type") " is not supported]")})))

(defn prompt-content
  "A prompt's :content from text plus attachments, as protocols send them —
   `{data, mimeType}` (pi) or `{data|image, mediaType}`, base64, with an
   optional `name`. A plain string when there are none, so text-only prompts
   are exactly as before; else the AI-SDK parts [text, attachment…]."
  [text attachments]
  (let [ps (->> (or attachments [])
                (keep (fn [i]
                        (let [data (or (aget i "data") (aget i "image"))
                              mt   (or (aget i "mimeType") (aget i "mediaType") "image/png")]
                          (when (string? data)
                            (attachment-part {:data data :media-type mt :name (aget i "name")})))))
                vec)]
    (if (empty? ps)
      (str (or text ""))
      (clj->js (into [#js {:type "text" :text (str (or text ""))}] ps)))))

(defn cmd-attachments
  "A protocol prompt's `images` and `files`, as one list."
  [cmd]
  (.concat (or (aget cmd "images") #js []) (or (aget cmd "files") #js [])))
