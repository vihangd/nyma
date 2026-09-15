(ns mcp-status-candidates.test
  "/mcp-status lists each config candidate on ONE line, truncated in the
   middle so the parent and basename survive.

   Observed on the binary: each entry wrapped to three lines and the path was
   tail-truncated identically for every candidate, so `.nyma/mcp.json`,
   `.cursor/mcp.json` and `.mcp.json` were indistinguishable."
  (:require ["bun:test" :refer [describe it expect]]
            [clojure.string :as str]
            [agent.extensions.agent-shell.features.mcp-discovery
             :refer [truncate-path-middle format-candidates]]))

(def ^:private long-root "/Users/someone/projects/personal/very-long-project-name/nested/deeper")

(describe "truncate-path-middle"
          (fn []
            (it "returns a short path unchanged"
                (fn []
                  (-> (expect (truncate-path-middle "/a/b/mcp.json" 40)) (.toBe "/a/b/mcp.json"))))

            (it "keeps the basename and its parent, cuts from the middle, fits the budget"
                (fn []
                  (let [p (str long-root "/.cursor/mcp.json")
                        t (truncate-path-middle p 40)]
                    (-> (expect (count t)) (.toBeLessThanOrEqual 40))
                    (-> (expect t) (.toContain "…"))
                    (-> (expect (.endsWith t ".cursor/mcp.json")) (.toBe true))
                    (-> (expect (.startsWith t "/Users/")) (.toBe true)))))

            (it "two candidates that differ only in their parent stay distinguishable"
                (fn []
                  (let [a (truncate-path-middle (str long-root "/.nyma/mcp.json") 40)
                        b (truncate-path-middle (str long-root "/.cursor/mcp.json") 40)]
                    (-> (expect (= a b)) (.toBe false)))))))

(describe "format-candidates"
          (fn []
            (it "renders one line per candidate with the status at the end"
                (fn []
                  (let [out   (format-candidates [{:file (str long-root "/.nyma/mcp.json") :exists? true}
                                                  {:file (str long-root "/.cursor/mcp.json") :exists? false}
                                                  {:file (str long-root "/.mcp.json") :exists? false}]
                                                 60)
                        lines (rest (str/split-lines out))]
                    (-> (expect (count lines)) (.toBe 3))
                    (doseq [l lines]
                      (-> (expect (count l)) (.toBeLessThanOrEqual 60)))
                    (-> (expect (.endsWith (first lines) "/.nyma/mcp.json")) (.toBe true))
                    (-> (expect (.endsWith (second lines) "/.cursor/mcp.json (not found)")) (.toBe true))
                    (-> (expect (count (set lines))) (.toBe 3)))))

            (it "does not truncate when no width is known (output is piped)"
                (fn []
                  (let [p   (str long-root "/.nyma/mcp.json")
                        out (format-candidates [{:file p :exists? true}])]
                    (-> (expect out) (.toContain (str "✓ " p))))))))
