(ns agent.extensions.lsp-suite.lsp-formatters
  "Format LSP responses for LLM consumption.
   All line/col output is 1-based (matching grep/editor conventions).")

;; ── Symbol kind names ─────────────────────────────────────────────

(def ^:private symbol-kind-names
  {1  "File"      2  "Module"    3  "Namespace" 4  "Package"   5  "Class"
   6  "Method"    7  "Property"  8  "Field"     9  "Constructor"
   10 "Enum"      11 "Interface" 12 "Function"  13 "Variable"  14 "Constant"
   15 "String"    16 "Number"    17 "Boolean"   18 "Array"     19 "Object"
   20 "Key"       21 "Null"      22 "EnumMember" 23 "Struct"   24 "Event"
   25 "Operator"  26 "TypeParameter"})

(defn symbol-kind-name [k]
  (get symbol-kind-names k (str "Kind" k)))

;; ── URI / path conversion ─────────────────────────────────────────

(defn uri->path
  "Convert file:// URI to local filesystem path."
  [uri]
  (when (string? uri)
    (-> uri
        (.replace #"^file://" "")
        js/decodeURIComponent)))

(defn path->uri
  "Convert absolute filesystem path to file:// URI."
  [p]
  (str "file://" (js/encodeURI p)))

;; ── Location formatting ───────────────────────────────────────────

(defn format-location
  "Format a {uri line col} triple as path:line:col (1-based)."
  [uri line-0based col-0based]
  (str (uri->path uri) ":" (inc line-0based) ":" (inc col-0based)))

(defn format-range-location
  "Extract start position from an LSP Range and format as path:line:col."
  [uri range]
  (when (and uri range (.-start range))
    (format-location uri (.. range -start -line) (.. range -start -character))))

;; ── Hover formatting ──────────────────────────────────────────────

(defn- markup-content-str [mc]
  (cond
    (string? mc) mc
    (nil? mc)    ""
    (and (.-kind mc) (.-value mc)) (.-value mc)
    :else (str mc)))

(defn format-hover
  "Normalize all three LSP hover shapes into a markdown string.
   Returns nil if no hover content."
  [hover]
  (when hover
    (let [contents (.-contents hover)]
      (cond
        (nil? contents) nil
        ;; Plain string
        (string? contents) (when (> (.-length contents) 0) contents)
        ;; MarkupContent: {kind, value}
        (and (.-kind contents) (.-value contents))
        (when (> (.-length (.-value contents)) 0) (.-value contents))
        ;; MarkedString array — use .forEach to avoid Squint lazy-seq issues
        (array? contents)
        (let [strs (atom [])]
          (.forEach contents
                    (fn [ms]
                      (let [s (cond
                                (string? ms)  (when (> (.-length ms) 0) ms)
                                (.-value ms)  (when (> (.-length (.-value ms)) 0) (.-value ms))
                                :else nil)]
                        (when s (swap! strs conj s)))))
          (when (pos? (count @strs))
            (.join (clj->js @strs) "\n\n---\n\n")))
        :else nil))))

;; ── Document symbols formatting ───────────────────────────────────

(declare format-symbol-tree)

(defn- format-symbol-item
  "Format a single DocumentSymbol or SymbolInformation to a string line."
  [sym depth]
  (let [indent (apply str (repeat (* depth 2) " "))
        kind   (symbol-kind-name (.-kind sym))
        name   (.-name sym)
        detail (.-detail sym)
        line   (when (.-range sym) (inc (.. sym -range -start -line)))]
    (str indent kind " " name
         (when (seq detail) (str " — " detail))
         (when line (str " (line " line ")")))))

(defn format-symbol-tree
  "Recursively format hierarchical DocumentSymbol tree."
  [syms depth]
  (when (and syms (pos? (.-length syms)))
    (->> (into [] syms)
         (mapcat (fn [sym]
                   (let [line   (format-symbol-item sym depth)
                         children (.-children sym)]
                     (cons line (when (and children (pos? (.-length children)))
                                  (format-symbol-tree children (inc depth)))))))
         (clojure.string/join "\n"))))

;; ── Diagnostics formatting ────────────────────────────────────────

(def ^:private severity-names {1 "Error" 2 "Warning" 3 "Information" 4 "Hint"})

(defn format-diagnostic [diag path]
  (let [sev   (get severity-names (.-severity diag) "Info")
        line  (when (.. diag -range -start) (inc (.. diag -range -start -line)))
        col   (when (.. diag -range -start) (inc (.. diag -range -start -character)))
        msg   (.-message diag)
        src   (.-source diag)]
    (str sev " " path ":" line ":" col " — " msg
         (when src (str " [" src "]")))))
