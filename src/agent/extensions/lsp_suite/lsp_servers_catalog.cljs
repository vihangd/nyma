(ns agent.extensions.lsp-suite.lsp-servers-catalog
  "Built-in language server recipes. Each entry is a default that users
   can override via .nyma/settings.json 'lsp' key.")

(def catalog
  {"typescript"
   {:name       "TypeScript Language Server"
    :command    ["typescript-language-server" "--stdio"]
    :extensions [".ts" ".tsx" ".js" ".jsx" ".mjs" ".cjs"]
    :install    "npm install -g typescript-language-server typescript"}

   "pyright"
   {:name       "Pyright"
    :command    ["pyright-langserver" "--stdio"]
    :extensions [".py" ".pyi"]
    :install    "npm install -g pyright"}

   "rust-analyzer"
   {:name       "rust-analyzer"
    :command    ["rust-analyzer"]
    :extensions [".rs"]}

   "gopls"
   {:name       "gopls"
    :command    ["gopls"]
    :extensions [".go"]}

   "clojure-lsp"
   {:name       "clojure-lsp"
    :command    ["clojure-lsp"]
    :extensions [".clj" ".cljs" ".cljc" ".edn"]}

   "ruby-lsp"
   {:name       "ruby-lsp"
    :command    ["ruby-lsp"]
    :extensions [".rb"]}})

(defn server-for-extension
  "Returns [id recipe] for the first catalog server that handles ext,
   or nil if none matches."
  [ext]
  (some (fn [[id recipe]]
          (when (some #(= % ext) (:extensions recipe))
            [id recipe]))
        catalog))
