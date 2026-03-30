(ns agent.ui.themes
  (:require ["node:fs" :as fs]
            ["node:fs/promises" :as fsp]))

(def default-dark
  {:colors {:primary   "#7aa2f7"
            :secondary "#9ece6a"
            :error     "#f7768e"
            :warning   "#e0af68"
            :success   "#9ece6a"
            :muted     "#565f89"
            :border    "#3b4261"
            :editor-border {:off    "#3b4261"
                            :low    "#9ece6a"
                            :medium "#e0af68"
                            :high   "#f7768e"}}
   :icons  {:user "❯" :assistant "●" :tool "⚙" :error "✗"}})

(def default-light
  {:colors {:primary   "#2e7de9"
            :secondary "#587539"
            :error     "#c64343"
            :warning   "#8c6c3e"
            :success   "#587539"
            :muted     "#848cb5"
            :border    "#d0d5e3"
            :editor-border {:off    "#d0d5e3"
                            :low    "#587539"
                            :medium "#8c6c3e"
                            :high   "#c64343"}}
   :icons  {:user "❯" :assistant "●" :tool "⚙" :error "✗"}})

(defn ^:async load-theme [path]
  (let [content (js-await (.readFile fsp path "utf8"))]
    (js->clj (js/JSON.parse content) :keywordize-keys true)))

(defn watch-theme
  "Hot-reload theme file. Returns cleanup function."
  [path on-change]
  (let [watcher (fs/watch path
                  (fn [_ _]
                    (-> (load-theme path)
                        (.then on-change)
                        (.catch js/console.error))))]
    (fn [] (.close watcher))))
