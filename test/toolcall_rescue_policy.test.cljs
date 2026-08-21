(ns toolcall-rescue-policy.test
  "Which provider/model pairs speak prose.

   The parser has existed since the `local` provider needed it, but the policy
   for turning it on was written three times in three shapes — local-models
   entries, relay entries, and (as of today) opencode-zen — and the docstring
   advertised a fourth, {\"small-model\": {\"toolcall-adapter\": …}}, that
   appeared nowhere in the code and could not have worked: an extension has no
   hook reaching a provider's fetch.

   The switch is keyed by PROVIDER because that is what the measurements say.
   Across 759 graded tasks, `agent never modified the stub (no tool call?)` ran
   15% on opencode-zen/laguna and 11% on opencode-zen/nemotron, against 0% on
   openrouter over 245 tasks — on a nine-billion-parameter model. Gateway, not
   model size."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.utils.toolcall-rescue :as rescue]))

(def ^:private zen-on #js {"toolcall-rescue" #js {"providers" #js ["opencode-zen"]}})

(describe "toolcall-rescue/enabled-for?"
  (fn []
    (it "is off when nothing asks for it — today's behaviour is the floor"
        (fn []
          (-> (expect (rescue/enabled-for? #js {} "openrouter" "qwen/qwen3.5-9b" false))
              (.toBe false))))

    (it "turns on for a named provider"
        (fn []
          (-> (expect (rescue/enabled-for? zen-on "opencode-zen" "laguna-s-2.1-free" false))
              (.toBe true))))

    (it "leaves other providers alone — a 9B on openrouter emits json fine"
        (fn []
          (-> (expect (rescue/enabled-for? zen-on "openrouter" "qwen/qwen3.5-9b" false))
              (.toBe false))))

    (it "honours a per-entry override, so nothing working today breaks"
        ;; local-models[].rescueParsing and relay's :rescue-parsing
        (fn []
          (-> (expect (rescue/enabled-for? #js {} "vllm" "unsloth/Qwen3.8-27B-NVFP4" true))
              (.toBe true))))

    (it "matches one model on a gateway that fronts a mixed fleet"
        (fn []
          (let [cfg #js {"toolcall-rescue"
                         #js {"models" #js ["openrouter/qwen/qwen3.5-9b"]}}]
            (-> (expect (rescue/enabled-for? cfg "openrouter" "qwen/qwen3.5-9b" false))
                (.toBe true))
            (-> (expect (rescue/enabled-for? cfg "openrouter" "qwen/qwen3.6-35b-a3b" false))
                (.toBe false)))))

    (it "supports a trailing * so a whole family can be named"
        (fn []
          (let [cfg #js {"toolcall-rescue" #js {"models" #js ["opencode-zen/nemotron-*"]}}]
            (-> (expect (rescue/enabled-for? cfg "opencode-zen" "nemotron-3.5-lightning-free" false))
                (.toBe true))
            (-> (expect (rescue/enabled-for? cfg "opencode-zen" "laguna-s-2.1-free" false))
                (.toBe false)))))))
