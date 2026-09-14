(ns agent.ui.themes)

(def default-dark
  {:colors {:primary   "#7aa2f7"
            :secondary "#9ece6a"
            :error     "#f7768e"
            :warning   "#e0af68"
            :success   "#9ece6a"
            :muted     "#565f89"
            :border    "#3b4261"
            ;; Info notices and plan blocks; both were a hardcoded cyan.
            :info      "#7dcfff"
            :plan      "#7dcfff"
            :editor-border {:off    "#3b4261"
                            :low    "#9ece6a"
                            :medium "#e0af68"
                            :high   "#f7768e"}
            :context-ok      "#9ece6a"
            :context-warning "#e0af68"
            :context-purple  "#bb9af7"
            :context-error   "#f7768e"}
   :icons  {:user "❯" :assistant "●" :tool "⚙" :error "✗"}})

(def unicode-icons
  {:user "❯" :assistant "●" :tool "⚙" :tool-done "✓" :error "✗"
   :info "ℹ" :warn "⚠" :success "✓" :widget "📋"})

(def ascii-icons
  {:user ">" :assistant "*" :tool "~" :tool-done "+" :error "x"
   :info "i" :warn "!" :success "+" :widget "#"})

(defn unicode-ok?
  "False under NYMA_ASCII=1 or a locale that is not UTF-8 — the renderers
   hardcoded emoji and a terminal without them showed tofu."
  ([] (unicode-ok? js/process.env))
  ([env]
   (let [g (fn [k] (str (or (aget env k) "")))]
     (and (empty? (g "NYMA_ASCII"))
          (let [loc (str (g "LC_ALL") (g "LC_CTYPE") (g "LANG"))]
            (or (empty? loc) (.includes (.toLowerCase loc) "utf")))))))

(defn icon
  "The glyph for `k`: the theme's :icons entry, else the unicode default,
   else its ASCII stand-in when the locale cannot show it."
  [theme k]
  (let [base (if (unicode-ok?) unicode-icons ascii-icons)]
    (or (when (unicode-ok?) (get-in theme [:icons k]))
        (get base k)
        (get ascii-icons k)
        "?")))

(def default-light
  {:colors {:primary   "#2e7de9"
            :secondary "#587539"
            :error     "#c64343"
            :warning   "#8c6c3e"
            :success   "#587539"
            :muted     "#848cb5"
            :border    "#d0d5e3"
            :info      "#007197"
            :plan      "#007197"
            :editor-border {:off    "#d0d5e3"
                            :low    "#587539"
                            :medium "#8c6c3e"
                            :high   "#c64343"}
            :context-ok      "#587539"
            :context-warning "#8c6c3e"
            :context-purple  "#7847bd"
            :context-error   "#c64343"}
   :icons  {:user "❯" :assistant "●" :tool "⚙" :error "✗"}})
