(ns claude-hook-bridge-compact-trigger.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.claude-hook-bridge.events.compact :as cc]))

(describe "claude-hook-bridge compact trigger" (fn []
  (it "idle and server compactions match a hook's `auto` matcher"
    (fn []
      (-> (expect (cc/cc-trigger "manual")) (.toBe "manual"))
      (-> (expect (cc/cc-trigger "idle")) (.toBe "auto"))
      (-> (expect (cc/cc-trigger "server")) (.toBe "auto"))
      (-> (expect (cc/cc-trigger nil)) (.toBe "auto"))))))
