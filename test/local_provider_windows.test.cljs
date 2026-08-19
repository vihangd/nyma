(ns local-provider-windows.test
  "A local server knows its own limits; a settings file knows what someone typed
   months ago. The vllm entry here declared contextWindow 32768 while the
   endpoint served max_model_len 262144 — nyma sized compaction off the smaller
   number and began summarising at ~28k for no reason."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.custom-provider-local.index :as local]))

(describe "local-provider/model-window"
  (fn []
    (it "reads vLLM's max_model_len and llama.cpp's context_length"
        (fn []
          (-> (expect (local/model-window #js {:max_model_len 262144})) (.toBe 262144))
          (-> (expect (local/model-window #js {:context_length 32768})) (.toBe 32768))))

    (it "ignores absent or nonsense values rather than inventing one"
        (fn []
          (-> (expect (local/model-window #js {:id "x"})) (.toBeFalsy))
          (-> (expect (local/model-window #js {:max_model_len 0})) (.toBeFalsy))))))

(describe "local-provider/merge-server-windows"
  (fn []
    (it "an explicit ctx in settings always wins"
        ;; the user's number may be deliberate — a smaller window to save VRAM
        (fn []
          (let [out (local/merge-server-windows [{:id "a" :ctx 4096}]
                                                #js [#js {:id "a" :max_model_len 262144}])]
            (-> (expect (:ctx (first out))) (.toBe 4096)))))

    (it "fills in a window the settings entry left unset"
        (fn []
          (let [out (local/merge-server-windows [{:id "b"}]
                                                #js [#js {:id "b" :max_model_len 131072}])]
            (-> (expect (:ctx (first out))) (.toBe 131072)))))

    (it "adds models the server serves but settings never mentioned"
        ;; exactly this repo's case: the entry named poolside/Laguna while the
        ;; endpoint was serving unsloth/Qwen3.8-27B-NVFP4
        (fn []
          (let [out (local/merge-server-windows [{:id "declared" :ctx 8192}]
                                                #js [#js {:id "actually-served" :max_model_len 262144}])
                ids (set (map :id out))]
            (-> (expect (contains? ids "actually-served")) (.toBe true))
            (-> (expect (contains? ids "declared")) (.toBe true)))))

    (it "returns the settings models unchanged when the server says nothing"
        (fn []
          (let [ms [{:id "a" :ctx 4096}]]
            (-> (expect (local/merge-server-windows ms nil)) (.toEqual ms)))))))
