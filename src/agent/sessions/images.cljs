(ns agent.sessions.images
  "Attached images live beside the session file, not inside it.

   A user turn with an image carries its base64 bytes (megabytes). Written into
   the JSONL as-is, every /resume listing, title and search would read them,
   and `--mode json` would print them. So on append each image part's bytes go
   to `<session>.jsonl.images/<sha256>.<ext>` and the entry keeps a `:ref`; on
   rebuilding context the bytes are read back. An image whose file is gone
   becomes a text note rather than failing the turn."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:crypto" :as crypto]
            [agent.utils.content :as content]))

(defn- images-dir [session-file] (str session-file ".images"))

(defn- ext-for [media-type]
  (case (str media-type)
    "image/png" "png" "image/jpeg" "jpg" "image/gif" "gif" "image/webp" "webp" "bin"))

(defn- part-bytes [p] (or (aget p "image") (aget p "data")))

(defn externalize
  "`content` with every inline image's base64 written to the session's images
   dir and replaced by `{:type image :ref … :mediaType … :name …}`. Strings and
   non-image parts pass through. No session file → unchanged."
  [content session-file]
  (if (or (nil? session-file) (zero? (content/image-count content)))
    content
    (let [dir (images-dir session-file)]
      (clj->js
       (map (fn [p]
              (let [b64 (when (content/image-part? p) (part-bytes p))]
                (if-not (string? b64)
                  p
                  (let [mt  (or (aget p "mediaType") "image/png")
                        sha (-> (crypto/createHash "sha256") (.update b64) (.digest "hex"))
                        f   (str sha "." (ext-for mt))]
                    (fs/mkdirSync dir #js {:recursive true})
                    (let [full (path/join dir f)]
                      (when-not (fs/existsSync full)
                        (fs/writeFileSync full (js/Buffer.from b64 "base64"))))
                    #js {:type "image" :ref f :mediaType mt :name (aget p "name")}))))
            content)))))

(defn rehydrate
  "Inverse of `externalize`: `:ref` parts get their bytes back as base64.
   A missing file becomes a text part saying so."
  [content session-file]
  (if (or (nil? session-file) (string? content) (not (js/Array.isArray content)))
    content
    (let [dir (images-dir session-file)]
      (clj->js
       (map (fn [p]
              (if-let [ref (and (not (string? p)) (aget p "ref"))]
                (let [full (path/join dir (path/basename (str ref)))]
                  (if (fs/existsSync full)
                    #js {:type "image" :image (.toString (fs/readFileSync full) "base64")
                         :mediaType (aget p "mediaType") :name (aget p "name")}
                    #js {:type "text" :text (str "[image missing: " (or (aget p "name") ref) "]")}))
                p))
            content)))))
