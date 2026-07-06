(ns extension-loader-imports.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            [agent.extension-loader :refer [absolutize-imports]]))

(describe "absolutize-imports" (fn []

                                 (it "rewrites bare squint-cljs imports to a real absolute path"
                                     (fn []
                                       (let [out (absolutize-imports "import * as c from 'squint-cljs/core.js';\n")
                                             m   (.match out (js/RegExp. "from '([^']+)'"))
                                             p   (when m (aget m 1))]
                                         (-> (expect (.startsWith p "/")) (.toBe true))
        ;; The whole point: the rewritten path must actually resolve on disk.
                                         (-> (expect (fs/existsSync p)) (.toBe true)))))

                                 (it "leaves node:/bun: builtins untouched"
                                     (fn []
                                       (let [src "import * as fs from 'node:fs';\nimport {x} from 'bun:test';\n"]
                                         (-> (expect (absolutize-imports src)) (.toBe src)))))

                                 (it "leaves relative/absolute specifiers untouched"
                                     (fn []
                                       (let [src "import a from './a.mjs';\nimport b from '/abs/b.mjs';\n"]
                                         (-> (expect (absolutize-imports src)) (.toBe src)))))

                                 (it "leaves unresolvable specifiers as-is (no worse than before)"
                                     (fn []
                                       (let [src "import z from 'totally-not-a-real-pkg-xyz';\n"]
                                         (-> (expect (absolutize-imports src)) (.toBe src)))))))
