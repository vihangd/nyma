(ns agent.extension-loader
  (:require ["squint-cljs" :refer [compileString]]
            ["node:path" :as path]
            ["node:fs/promises" :as fsp]))

(defn- cljs-extension?  [p] (or (.endsWith p ".cljs") (.endsWith p ".cljc")))
(defn- ts-js-extension? [p] (or (.endsWith p ".ts") (.endsWith p ".js")
                                (.endsWith p ".mjs")))

(defn ^:async load-squint-extension
  "Compile a .cljs extension file with squint and evaluate it."
  [file-path]
  (let [source   (js-await (.readFile fsp file-path "utf8"))
        compiled (compileString source
                   #js {:context       "expr"
                        :elide-imports false})
        tmp-path (str "/tmp/nyma-ext-" (js/Date.now) ".mjs")]
    (js-await (js/Bun.write tmp-path compiled))
    (let [mod (js-await (js/import tmp-path))]
      (.-default mod))))

(defn ^:async load-ts-extension
  "Load a .ts/.js extension file directly via Bun's native TS loader."
  [file-path]
  (let [mod (js-await (js/import (path/resolve file-path)))]
    (.-default mod)))

(defn ^:async load-extension
  "Load a single extension file. Dispatches on file extension."
  [file-path]
  (cond
    (cljs-extension? file-path)  (js-await (load-squint-extension file-path))
    (ts-js-extension? file-path) (js-await (load-ts-extension file-path))
    :else (js/console.warn (str "Unknown extension type: " file-path))))

(defn ^:async discover-and-load
  "Scan directories for extension files, load them, and wire them up."
  [dirs api]
  (let [extensions (atom [])]
    (doseq [dir dirs]
      (when (js-await (-> (.stat fsp dir) (.then (constantly true)) (.catch (constantly false))))
        (let [entries (js-await (.readdir fsp dir #js {:recursive true}))]
          (doseq [entry entries]
            (let [full-path (path/join dir entry)]
              (when (or (cljs-extension? entry) (ts-js-extension? entry))
                (let [ext-fn (js-await (load-extension full-path))]
                  (when ext-fn
                    (ext-fn api)
                    (swap! extensions conj
                      {:path full-path
                       :type (if (cljs-extension? entry) :squint :ts)})))))))))
    @extensions))
