(ns ext-spec-driven-import.test
  "Tests for /spec import — extract-toc + compose-import-seed."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.spec-driven.import :as imp]
            [clojure.string :as str]))

(defn test-extract-toc-empty []
  (-> (expect (imp/extract-toc "")) (.toBe "(no headings detected in source)"))
  (-> (expect (imp/extract-toc "no headings here\njust prose"))
      (.toBe "(no headings detected in source)")))

(defn test-extract-toc-mixed-levels []
  (let [src "# Title\n\nSome prose.\n\n## Goals\n\n- a\n\n### Detail\n\n## Architecture\n\nmore"
        toc (imp/extract-toc src)]
    (-> (expect (.includes toc "H1  Title  (line 1)")) (.toBe true))
    (-> (expect (.includes toc "H2  Goals  (line 5)")) (.toBe true))
    (-> (expect (.includes toc "H3  Detail  (line 9)")) (.toBe true))
    (-> (expect (.includes toc "H2  Architecture  (line 11)")) (.toBe true))))

(defn test-extract-toc-cap []
  ;; 60 H2 headings, cap at 5 → ellipsis appended.
  (let [src (->> (range 60)
                 (map #(str "## Section " %))
                 (str/join "\n\n"))
        toc (imp/extract-toc src 5)]
    (-> (expect (.includes toc "Section 0")) (.toBe true))
    (-> (expect (.includes toc "Section 4")) (.toBe true))
    (-> (expect (.includes toc "Section 5")) (.toBe false))
    (-> (expect (.endsWith toc "…")) (.toBe true))))

(defn test-extract-toc-ignores-h4-and-deeper []
  (let [src "## Real\n\n#### Too deep\n\n##### Way too deep"
        toc (imp/extract-toc src)]
    (-> (expect (.includes toc "Real")) (.toBe true))
    (-> (expect (.includes toc "Too deep")) (.toBe false))))

(defn test-compose-import-seed-shape []
  (let [seed (imp/compose-import-seed
              {:spec-name      "test-feat"
               :source-path    "designs/foo.md"
               :target-dir     ".specify/specs/test-feat"
               :source-content "# Title\n\n## Goals\n\nthings"})]
    (-> (expect (.includes seed "test-feat")) (.toBe true))
    (-> (expect (.includes seed "designs/foo.md")) (.toBe true))
    (-> (expect (.includes seed ".specify/specs/test-feat")) (.toBe true))
    (-> (expect (.includes seed "H2  Goals")) (.toBe true))
    (-> (expect (.includes seed "FR-")) (.toBe true))
    (-> (expect (.includes seed "[NEEDS CLARIFICATION")) (.toBe true))
    (-> (expect (.includes seed "/spec clarify")) (.toBe true))))

(defn test-compose-import-seed-percent-s-leakage []
  ;; The interpolate-template invariant: literal `%s` in user content
  ;; must not be re-interpreted as a placeholder.
  (let [src "# Title\n\nUse %s in printf."
        seed (imp/compose-import-seed
              {:spec-name      "name-with-%s"
               :source-path    "p/%s.md"
               :target-dir     ".specify/specs/x"
               :source-content src})]
    ;; Substituted values appear verbatim, including the %s.
    (-> (expect (.includes seed "name-with-%s")) (.toBe true))
    (-> (expect (.includes seed "p/%s.md")) (.toBe true))))

(describe "/spec import — TOC + seed composition"
          (fn []
            (it "extract-toc handles empty / no-heading input"
                test-extract-toc-empty)
            (it "extract-toc captures H1/H2/H3 with line numbers"
                test-extract-toc-mixed-levels)
            (it "extract-toc respects cap and signals truncation"
                test-extract-toc-cap)
            (it "extract-toc ignores H4 and deeper"
                test-extract-toc-ignores-h4-and-deeper)
            (it "compose-import-seed embeds name/paths/TOC and key directives"
                test-compose-import-seed-shape)
            (it "compose-import-seed survives literal %s in user content"
                test-compose-import-seed-percent-s-leakage)))
