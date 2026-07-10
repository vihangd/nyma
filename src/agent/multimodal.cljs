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
   scale and look sparingly.")

(defn file-part
  "An AI-SDK tool-result file content part carrying base64 image/file data."
  [base64 media-type]
  #js {:type "file" :data base64 :mediaType media-type})

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
