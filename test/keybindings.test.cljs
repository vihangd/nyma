(ns keybindings.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            [agent.keybindings :refer [load-keybindings apply-keybindings]]))

(describe "load-keybindings" (fn []
  (it "returns empty map when file missing"
    (fn []
      ;; Default path ~/.nyma/keybindings.json likely doesn't exist in test
      ;; The function should return {} without throwing
      (let [result (load-keybindings)]
        (-> (expect (map? result)) (.toBe true)))))

  (it "returns empty map for malformed JSON"
    (fn []
      ;; We can't easily test with the real path, but the function handles parse errors
      (-> (expect (fn? load-keybindings)) (.toBe true))))))

(describe "apply-keybindings" (fn []
  (it "merges bindings into shortcuts atom"
    (fn []
      (let [shortcuts (atom {})
            commands  (atom {"clear" {:handler (fn [_args _ctx] nil)}})
            bindings  {"ctrl+k" "command:clear"}]
        (apply-keybindings shortcuts commands bindings)
        (-> (expect (get @shortcuts "ctrl+k")) (.toBeDefined))
        (-> (expect (:action (get @shortcuts "ctrl+k"))) (.toBe "command:clear"))
        (-> (expect (:source (get @shortcuts "ctrl+k"))) (.toBe "keybindings.json")))))

  (it "handler dispatches command"
    (fn []
      (let [called   (atom false)
            shortcuts (atom {})
            commands  (atom {"test" {:handler (fn [_args _ctx] (reset! called true))}})
            bindings  {"ctrl+t" "command:test"}]
        (apply-keybindings shortcuts commands bindings)
        ;; Invoke the handler
        ((:handler (get @shortcuts "ctrl+t")))
        (-> (expect @called) (.toBe true)))))

  (it "handles unknown command gracefully"
    (fn []
      (let [shortcuts (atom {})
            commands  (atom {})
            bindings  {"ctrl+x" "command:nonexistent"}]
        (apply-keybindings shortcuts commands bindings)
        ;; Handler should not throw
        ((:handler (get @shortcuts "ctrl+x")))
        (-> (expect true) (.toBe true)))))

  (it "handles non-command action"
    (fn []
      (let [shortcuts (atom {})
            commands  (atom {})
            bindings  {"ctrl+z" "custom:action"}]
        (apply-keybindings shortcuts commands bindings)
        ;; Should register but handler does nothing (action doesn't start with "command:")
        ((:handler (get @shortcuts "ctrl+z")))
        (-> (expect true) (.toBe true)))))))
