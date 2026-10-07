(ns no-js-to-clj-lint-test
  "`js->clj` compiles under squint but does not exist at runtime
   (ReferenceError: js__GT_clj). Wrapped in a try/catch it fails silently —
   that is how `tool_search` returned nothing for every query. Use the value
   directly (squint maps ARE JS objects) or `agent.utils.js-interop/js->clj*`."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(defn- cljs-files [dir]
  (->> (fs/readdirSync dir #js {:recursive true})
       (filter #(.endsWith % ".cljs"))
       (map #(path/join dir %))))

(describe "no bare js->clj in src"
          (fn []
            (it "every use is a call to the js-interop helper, never the bare symbol"
                (fn []
                  (let [hits (->> (cljs-files (path/join (js/process.cwd) "src"))
                                  (mapcat (fn [f]
                                            (->> (.split (fs/readFileSync f "utf8") "\n")
                                                 (map-indexed vector)
                                                 (keep (fn [[i line]]
                                                         ;; code only: skip comments and docstring mentions in backticks
                                                         (let [code (first (.split line ";"))]
                                                           (when (re-find #"(?<!`)\(js->clj[\s)]" code)
                                                             (str (path/relative (js/process.cwd) f) ":" (inc i)))))))))
                                  vec)]
                    (-> (expect (clj->js hits)) (.toEqual #js [])))))))
