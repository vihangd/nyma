(ns compiled-scope-lint.test
  "One squint trap, checked where it actually bit.

   Squint suffixes let bindings (`tools_this_turn68`). A closure written ABOVE
   the binding it uses compiles to a bare `tools_this_turn` — a different symbol,
   declared nowhere, a ReferenceError the moment the closure runs. The compiler
   is happy and `node --check` is happy; only the code path that invokes the
   closure fails.

   loop.cljs shipped exactly this: `:onStepFinish` incremented a tool counter
   that was bound 17 lines further down, inside the next `let`. The counter
   stayed 0, so `:no-op-turns` counted every turn as idle, and the escalation and
   stall warning built on it could never work.

   A general version of this check over all of dist/ drowns in false positives
   (arrow params, catch bindings, shorthand keys), so this asserts the invariant
   on the counters the loop's turn accounting depends on."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]))

(def ^:private loop-mjs
  (path/join (js/process.cwd) "dist" "agent" "loop.mjs"))

(defn escaped?
  "Pure: does `src` declare NAME<digits> while also using a bare NAME? That bare
   use cannot resolve — it is the escaped-binding bug."
  [src name]
  (let [declared? (some? (.match src (js/RegExp. (str "(?:const|let|var)\\s+" name "\\d+\\s*="))))
        bare?     (some? (.match src (js/RegExp. (str "(?<![\\w$.])" name "(?![\\w$\\d])"))))]
    (and declared? bare?)))

(describe "compiled-scope-lint"
          (fn []
            (it "detects the shape loop.cljs shipped"
                (fn []
                  (let [bad "const cfg1 = {onStep: function (s) { swap(tools_this_turn, 1) }};\nconst tools_this_turn9 = atom(0);"]
                    (-> (expect (escaped? bad "tools_this_turn")) (.toBe true)))))

            (it "does not fire when the binding is declared before its closure"
                (fn []
                  (let [ok "const tools_this_turn9 = atom(0);\nconst cfg1 = {f: function () { return deref(tools_this_turn9) }};"]
                    (-> (expect (escaped? ok "tools_this_turn")) (.toBe false)))))

            (it "loop.mjs turn-accounting bindings all resolve"
                (fn []
                  (let [src (fs/readFileSync loop-mjs "utf8")]
                    (doseq [n ["tools_this_turn" "step_usage" "turn_error" "overflow_recovered_QMARK_"]]
                      (-> (expect #js [n (escaped? src n)]) (.toEqual #js [n false]))))))

            (it "the tool counter is actually incremented from onStepFinish"
        ;; the increment and the declaration must name the SAME symbol
                (fn []
                  (let [src  (fs/readFileSync loop-mjs "utf8")
                        decl (.match src (js/RegExp. "const (tools_this_turn\\d+) = squint_core.atom"))
                        used (.match src (js/RegExp. "swap_BANG_\\((tools_this_turn\\d*)"))]
                    (-> (expect (some? decl)) (.toBe true))
                    (-> (expect (some? used)) (.toBe true))
                    (-> (expect (aget used 1)) (.toBe (aget decl 1))))))))
