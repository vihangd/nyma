(ns agent.utils.content
  "Message :content as text, for every reader that wants text.

   :content is a string for most messages, but an assistant turn that called
   tools is a vector of parts, and a user turn with an attached image is too.
   `(str content)` on a vector yields `[object Object],[object Object]`: the
   caption vanished from titles, summaries, search and the transcript, and an
   image counted as ~9 tokens. One helper, used everywhere text is wanted."
  (:require [clojure.string :as str]))

(defn image-part?
  "An AI-SDK image part: `{type: image}`, or a `file` part with an image/
   media type."
  [p]
  (and (some? p) (not (string? p))
       (let [t  (or (:type p) (aget p "type"))
             mt (str (or (:mediaType p) (aget p "mediaType") ""))]
         (or (= t "image") (and (= t "file") (str/starts-with? mt "image/"))))))

(defn- parts [content]
  (when (or (vector? content) (js/Array.isArray content)) content))

(defn- part->text [b]
  (cond
    (string? b)     b
    (image-part? b) (if-let [n (or (:name b) (aget b "name"))] (str "[image: " n "]") "[image]")
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

(def image-token-estimate
  "Rough tokens per attached image. Providers charge by pixels (Anthropic
   ~1.6k for a 1092x1092 image); a fixed figure keeps budgets honest without
   decoding the image."
  1600)

(defn prompt-content
  "A prompt's :content from text plus attached images, as protocols send them
   — `{data, mimeType}` (pi) or `{data|image, mediaType}`, base64. A plain
   string when there are none, so text-only prompts are exactly as before;
   else the AI-SDK parts [text, image…]."
  [text images]
  (let [imgs (->> (or images [])
                  (keep (fn [i]
                          (let [data (or (aget i "data") (aget i "image"))
                                mt   (or (aget i "mimeType") (aget i "mediaType") "image/png")]
                            (when (string? data)
                              #js {:type "image" :image data :mediaType mt :name (aget i "name")}))))
                  vec)]
    (if (empty? imgs)
      (str (or text ""))
      (clj->js (into [#js {:type "text" :text (str (or text ""))}] imgs)))))
