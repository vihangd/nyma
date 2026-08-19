(ns small-model-edit-routing.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.small-model.profiles :as profiles :refer [edit-tools-to-hide]]))

;; editStrategy → which edit tools are hidden (Aider/OpenCode-style routing).
(describe "small-model:edit-tools-to-hide" (fn []
                                             (it "\"whole\" hides both exact edit and multi_edit (write only)"
                                                 (fn []
                                                   (let [h (edit-tools-to-hide "whole")]
                                                     (-> (expect (contains? h "edit")) (.toBe true))
                                                     (-> (expect (contains? h "multi_edit")) (.toBe true)))))

                                             (it "\"fuzzy\" hides only the brittle exact edit"
                                                 (fn []
                                                   (let [h (edit-tools-to-hide "fuzzy")]
                                                     (-> (expect (contains? h "edit")) (.toBe true))
                                                     (-> (expect (contains? h "multi_edit")) (.toBe false)))))

                                             (it "\"patch\" behaves like fuzzy (hides exact edit)"
                                                 (fn []
                                                   (-> (expect (contains? (edit-tools-to-hide "patch") "edit")) (.toBe true))))

                                             (it "\"exact\" and unset hide nothing"
                                                 (fn []
                                                   (-> (expect (count (edit-tools-to-hide "exact"))) (.toBe 0))
                                                   (-> (expect (count (edit-tools-to-hide nil))) (.toBe 0))))))

;; ── profile lookup: the whole module was unreachable ─────────────────────
;; `.provider` on an AI SDK model is the STRING "openai.chat" for every
;; OpenAI-compatible provider, so `.provider.providerId` is undefined and the
;; key collapsed to the bare modelId. Every documented profile key matched
;; nothing, silently, so temperature/allowedTools/editStrategy never applied.
(describe "small-model/profiles key matching"
          (fn []
            (it "offers provider/model, bare model, and short name"
                (fn []
                  (-> (expect (profiles/model-keys "openrouter" "qwen/qwen3.5-9b"))
                      (.toEqual #js ["openrouter/qwen/qwen3.5-9b" "qwen/qwen3.5-9b" "qwen3.5-9b"]))))

            (it "still yields usable keys when the provider name is missing"
                (fn []
                  (-> (expect (profiles/model-keys "" "Qwen3.6-27B-oQ4-mtp"))
                      (.toEqual #js ["Qwen3.6-27B-oQ4-mtp"]))))

            (it "does not emit duplicates when model id has no slash"
                (fn []
                  (-> (expect (count (profiles/model-keys "omlx" "big-pickle"))) (.toBe 2))))))
