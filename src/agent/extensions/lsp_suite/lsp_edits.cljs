(ns agent.extensions.lsp-suite.lsp-edits
  "Apply LSP WorkspaceEdit / TextEdit results to disk, then sync the changed
   files back to the language server. Used by the rename / code-action tools."
  (:require ["node:fs" :as fs]
            [clojure.string :as str]
            [agent.extensions.lsp-suite.lsp_formatters :as fmt]
            [agent.extensions.lsp-suite.lsp_manager :as mgr]))

;; ── Pure TextEdit application ─────────────────────────────────────

(defn- pos->offset
  "Character offset in `content` (split into `lines` on \\n, keeping empties) of
   an LSP position {line, character}, both 0-based. Clamps past-EOL characters."
  [lines line character]
  (let [before (take line lines)
        ;; +1 per preceding line for the \n that split removed
        base   (reduce (fn [acc l] (+ acc (count l) 1)) 0 before)
        cur    (nth lines line "")]
    (+ base (min character (count cur)))))

(defn apply-text-edits
  "Apply an LSP TextEdit[] to `content`, returning the new string. Edits are
   applied end→start (by start offset, descending) so earlier offsets stay
   valid; offsets are computed once from the original content. LSP forbids
   overlapping edits, so ordering is well-defined."
  [content edits]
  (let [lines (str/split content #"\n" -1)
        off   (fn [p] (pos->offset lines (.-line p) (.-character p)))
        items (->> (into [] edits)
                   (sort-by #(off (.-start (.-range %))) >))]
    (reduce
     (fn [text e]
       (let [rng (.-range e)]
         (str (.slice text 0 (off (.-start rng)))
              (.-newText e)
              (.slice text (off (.-end rng))))))
     content
     items)))

;; ── WorkspaceEdit → disk ──────────────────────────────────────────

(defn- ^:async apply-edits-to-file! [manager path edits changed]
  (when (and path (fs/existsSync path) edits (pos? (.-length edits)))
    (let [content (.readFileSync fs path "utf8")
          updated (apply-text-edits content edits)]
      (when (not= updated content)
        (.writeFileSync fs path updated "utf8")
        (js-await (-> (mgr/did-change-if-running! manager path updated)
                      (.catch (fn [_] nil))))
        (swap! changed conj path)))))

(defn ^:async apply-workspace-edit
  "Apply a WorkspaceEdit to disk. Prefers `documentChanges` (ordered, with
   resource ops) over the legacy `changes` map. Resource operations
   (create/rename/delete file) are reported but NOT applied. Returns
   {:files [changed-paths] :skipped [notes]}."
  [manager wsedit]
  (let [changed (atom [])
        skipped (atom [])
        doc-changes (and wsedit (.-documentChanges wsedit))
        changes     (and wsedit (.-changes wsedit))]
    (cond
      (and doc-changes (pos? (.-length doc-changes)))
      (doseq [dc (into [] doc-changes)]
        (if (.-edits dc)                       ; TextDocumentEdit
          (js-await (apply-edits-to-file!
                     manager (fmt/uri->path (.-uri (.-textDocument dc))) (.-edits dc) changed))
          (swap! skipped conj (str (.-kind dc) " file op"))))

      (and changes (pos? (count (js/Object.keys changes))))
      (doseq [uri (js/Object.keys changes)]
        (js-await (apply-edits-to-file! manager (fmt/uri->path uri) (aget changes uri) changed)))

      :else nil)
    {:files @changed :skipped @skipped}))
