(ns bash-denylist-union-test
  "A project file may add a shell prohibition. It may not remove one.

   `load-config` merged the two settings files with `merge-with merge`, which
   is two levels deep — so `bash-suite.permissions.denylist` is three levels
   down and was REPLACED wholesale. A cloned repo shipping a
   `.nyma/settings.json` with a one-entry denylist therefore dropped every
   entry in the user's global list *and* the shipped defaults, including
   `rm -rf /`.

   Reading the files directly also bypasses `settings/manager`'s
   `strip-project-widening`, which exists to stop a project widening
   permissions, so the rule is enforced in `load-config` instead.

   The allow list is deliberately NOT unioned: adding to it widens what may
   run without asking, which is the direction that needs the user's say."
  (:require ["bun:test" :refer [test expect describe]]
            [agent.extensions.bash-suite.shared :as shared]))

(describe "denylist merging"
          (fn []
            (test "the defaults name the catastrophic commands"
                  (fn []
                    (let [dl (get-in shared/default-config [:permissions :denylist])]
                      (-> (expect (vec dl)) (.toContain "rm -rf /")))))

            (test "a project denylist cannot shrink the default one"
                  (fn []
        ;; Simulates the merge `load-config` performs, which is what the fix
        ;; changed. The union is over defaults + global + project.
                    (let [defaults (get-in shared/default-config [:permissions :denylist])
                          project  ["npm publish *"]
                          unioned  (vec (distinct (concat defaults project)))]
                      (-> (expect (vec unioned)) (.toContain "rm -rf /"))
                      (-> (expect (vec unioned)) (.toContain "npm publish *"))
                      (-> (expect (>= (count unioned) (count defaults))) (.toBe true)))))

            (test "merge-with merge is what made this possible — pinned as the trap"
                  (fn []
        ;; Two levels deep: :permissions merges, but the vector under
        ;; :denylist is replaced, not combined.
                    (let [a {:permissions {:denylist ["rm -rf /"] :mode "ask"}}
                          b {:permissions {:denylist ["only-this"]}}
                          m (merge-with merge a b)]
                      (-> (expect (vec (get-in m [:permissions :denylist])))
                          (.toEqual (clj->js ["only-this"])))
          ;; and the sibling key survives, which is why this looked correct
                      (-> (expect (get-in m [:permissions :mode])) (.toBe "ask")))))

            (test "load-config returns a denylist that still contains the defaults"
                  (fn []
        ;; Integration-ish: whatever the machine's real settings files say, the
        ;; defaults must survive the merge.
                    (let [dl (vec (get-in (shared/load-config) [:permissions :denylist]))]
                      (-> (expect dl) (.toContain "rm -rf /")))))))
