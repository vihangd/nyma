(ns namespaces-table.test
  "The Key Namespaces table in AGENTS.md is generated from ns docstrings
   (`bun run gen:namespaces`). Hand-kept, it listed 45 of 100+ core
   namespaces and drifted both ways."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["../scripts/gen-namespaces.mjs" :as gen]))

(describe "AGENTS.md namespaces table" (fn []
                                         (it "is the generated one"
                                             (fn []
                                               (let [doc (fs/readFileSync (path/join (js/process.cwd) "AGENTS.md") "utf8")]
                                                 (-> (expect (.includes doc gen/AGENTS_START)) (.toBe true))
                                                 (-> (expect (.includes doc (gen/render))) (.toBe true)))))

                                         (it "describe reads the ns and the first sentence of its docstring"
                                             (fn []
                                               (let [d (gen/describe "(ns agent.foo.bar\n  \"Does one thing. Then explains it at length.\"\n  (:require [x]))")]
                                                 (-> (expect (.-ns d)) (.toBe "agent.foo.bar"))
                                                 (-> (expect (.-purpose d)) (.toBe "Does one thing.")))
                                               (-> (expect (.-purpose (gen/describe "(ns agent.bare (:require [x]))"))) (.toBe "—"))
                                               (-> (expect (gen/describe "no ns here")) (.toBeNull))))

                                         (it "covers every core namespace and none of the extensions"
                                             (fn []
                                               (let [files (vec (gen/coreFiles))]
                                                 (-> (expect (count files)) (.toBeGreaterThan 90))
                                                 (-> (expect (some #(.includes % "src/agent/loop.cljs") files)) (.toBeTruthy))
                                                 (-> (expect (some #(.includes % "/extensions/") files)) (.toBeFalsy)))))))
