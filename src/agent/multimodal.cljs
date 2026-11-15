(ns agent.multimodal
  "Multimodal (image) tool results — let a tool return an image a vision-capable
   model can SEE.

   A tool opts in by returning the convention shape:
     #js {:content #js [(file-part base64 media-type) …] :summary \"short text\"}
   and setting `:toModelOutput` to `tool-model-output`.

   AI SDK v7 calls the tool's `toModelOutput` to build the provider's image
   `tool_result`; nyma's middleware returns the STRUCTURED result (not the
   policy-truncated string) precisely when `toModelOutput` is present
   (middleware/wrap-tools-with-middleware), so real bytes reach the model. The
   `:summary` is what the transcript/UI shows (base64 never persists there).

   No resizing here: providers auto-downscale images past their long-edge cap
   (Anthropic >1568px / >2576px on 4.7+). Render/screenshot at a sane source
   scale and look sparingly."
  (:require [agent.pricing-data :as pricing-data]))

(defn file-part
  "An AI-SDK tool-result file content part carrying base64 image/file data.
   AI SDK 7 takes the data TAGGED in a tool result ({type data, data}); a bare
   string fails its schema (\"The messages do not match the ModelMessage[]
   schema\"), which broke the step after every image tool call, on every
   provider."
  [base64 media-type]
  #js {:type "file" :data #js {:type "data" :data base64} :mediaType media-type})

(defn text-part [s]
  #js {:type "text" :text (str s)})

(defn image-result
  "Build the convention result for a tool that returns a single image."
  [base64 media-type summary]
  #js {:content #js [(file-part base64 media-type)]
       :summary (str summary)})

(defn media-type-for
  "Guess an image mediaType from a file path/extension. nil when unknown."
  [path]
  (let [p (.toLowerCase (str path))]
    (cond
      (.endsWith p ".png")                     "image/png"
      (or (.endsWith p ".jpg") (.endsWith p ".jpeg")) "image/jpeg"
      (.endsWith p ".webp")                    "image/webp"
      (.endsWith p ".gif")                     "image/gif"
      :else                                    nil)))

(defn sniff-media-type
  "Detect an image mediaType from the leading magic bytes (a Uint8Array), so
   extension-less files (e.g. tmp screenshots / rendered output) still work.
   nil when the bytes aren't a recognized image."
  [bytes]
  (let [b (fn [i] (when (< i (.-length bytes)) (aget bytes i)))]
    (cond
      (and (= (b 0) 0x89) (= (b 1) 0x50) (= (b 2) 0x4E) (= (b 3) 0x47)) "image/png"
      (and (= (b 0) 0xFF) (= (b 1) 0xD8) (= (b 2) 0xFF))                "image/jpeg"
      (and (= (b 0) 0x47) (= (b 1) 0x49) (= (b 2) 0x46))                "image/gif"
      (and (= (b 0) 0x52) (= (b 1) 0x49) (= (b 2) 0x46) (= (b 3) 0x46)
           (= (b 8) 0x57) (= (b 9) 0x45) (= (b 10) 0x42) (= (b 11) 0x50)) "image/webp"
      :else nil)))

(defn tool-model-output
  "Shared AI-SDK `toModelOutput`. When the tool's output carries `:content`
   parts (images), the model receives those; otherwise it's plain text. Safe to
   attach to any tool — text-returning tools just take the text branch."
  [opts]
  (let [output (and opts (.-output opts))]
    (if (some-> output .-content)
      #js {:type "content" :value (.-content output)}
      #js {:type "text" :value (str output)})))


;; ── Models that take no image ─────────────────────────────────────
;; An image sent to a text-only model is a 400 (DeepSeek, GLM, MiniMax,
;; gpt-oss), and nothing checked: a pasted screenshot or a view_image result
;; failed the turn. models.dev declares each model's inputs; only a model that
;; declares them and leaves images out counts, so an unknown one is sent as is.

(def ^:private text-only-segments-memo (atom nil))

(defn- last-seg [k] (subs k (inc (.lastIndexOf k "/"))))

(defn- text-only-segments
  "Last id segments every known row of which is text-only (`deepseek-v4-pro`),
   for gateway ids keyed differently from the vendor's row."
  []
  (or @text-only-segments-memo
      (reset! text-only-segments-memo
              (let [known (group-by last-seg (keys pricing-data/context))]
                (set (for [[seg ks] known
                           :when (every? #(contains? pricing-data/text-only %) ks)]
                       seg))))))

(defn text-only?
  "Does the model behind `model-key` (`provider/id`) declare no image input?"
  [model-key]
  (let [k (str model-key)
        i (.indexOf k "/")]
    (boolean
     (or (contains? pricing-data/text-only k)
         (and (pos? i) (contains? pricing-data/text-only (subs k (inc i))))
         (contains? (text-only-segments) (last-seg k))))))

(defn- image-part? [p]
  (and (some? p) (not (string? p))
       (let [t  (aget p "type")
             mt (str (or (aget p "mediaType") (aget p "mimeType") ""))]
         (or (= t "image")
             (and (contains? #{"file" "image-data" "media"} t) (.startsWith mt "image/"))))))

(defn- omitted [model-key]
  #js {:type "text" :text (str "[image omitted: " model-key " takes no image input]")})

(defn- jsmap
  "Map `f` over a JS array or a vector, keeping the kind — no clj->js, which
   converts deeply and turned the SDK's message objects into ones its schema
   refused."
  [f xs]
  (if (js/Array.isArray xs) (.map xs (fn [x] (f x))) (mapv f xs)))

(defn- lower-parts [parts model-key]
  (if (and (or (vector? parts) (js/Array.isArray parts)) (some image-part? parts))
    (jsmap #(if (image-part? %) (omitted model-key) %) parts)
    parts))

(defn- lower-tool-result [p model-key]
  (let [out (and (some? p) (not (string? p)) (= "tool-result" (aget p "type")) (aget p "output"))]
    (if (and out (= "content" (aget out "type")))
      (let [v  (aget out "value")
            v2 (lower-parts v model-key)]
        (if (identical? v v2)
          p
          (js/Object.assign #js {} p #js {:output (js/Object.assign #js {} out #js {:value v2})})))
      p)))

(defn- tool-image? [p]
  (let [out (and (some? p) (not (string? p)) (= "tool-result" (aget p "type")) (aget p "output"))
        v   (and out (= "content" (aget out "type")) (aget out "value"))]
    (boolean (and v (some image-part? v)))))

(defn- lower-message [m model-key]
  (let [c (aget m "content")]
    (if (and (or (vector? c) (js/Array.isArray c))
             (some #(or (image-part? %) (tool-image? %)) c))
      (js/Object.assign #js {} m
                        #js {:content (jsmap #(lower-tool-result % model-key)
                                             (lower-parts c model-key))})
      m)))

(defn lower-images
  "`messages` with every image part (user attachments and tool-result images)
   replaced by a note, when the model takes no image input. Untouched otherwise."
  [messages model-key]
  (if (text-only? model-key)
    (jsmap #(lower-message % model-key) messages)
    messages))
