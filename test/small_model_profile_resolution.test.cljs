(ns small-model-profile-resolution.test
  "Per-model profiles could never match anything.

   current-model-keys read `(:config @__state_atom)`, but the state atom has no
   :config — it carries :model, :active-tools, :active-role. So cfg was nil,
   `(aget nil \"active-provider-name\")` threw, the catch swallowed it, and the
   function returned []. profile-for then matched nothing, and EVERY per-model
   setting was inert: editStrategy, temperature, resultCap, allowedTools.

   The symptom was invisible by construction. A tool that is never hidden looks
   exactly like a model that chose not to call it — which is how a probe run
   'confirming' editStrategy passed: the task simply had no reason to use edit.
   The failure only surfaced on a task that wanted it, where editStrategy
   \"whole\" is supposed to hide `edit` and the agent called it 3 and 5 times.

   The model object IS in the state atom, which is what these pin."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.small-model.profiles :as p]))

(defn- api-with [model]
  #js {:__state_atom (atom {:model model :active-role :default})})

(describe "small-model/current-model-keys"
  (fn []
    (it "resolves the model from the state atom, where it actually lives"
        (fn []
          (let [ks (vec (p/current-model-keys
                         (api-with #js {:modelId "Qwen3.6-35B-A3B-OptiQ-4bit"})))]
            (-> (expect (count ks)) (.toBeGreaterThan 0))
            (-> (expect ks) (.toContain "Qwen3.6-35B-A3B-OptiQ-4bit")))))

    (it "falls back to :base-model-spec, the CLI's only record of the model"
        ;; the shape that actually occurs: the CLI assigns
        ;; (.-model (:config agent)) directly and never calls setModel, so
        ;; state :model stays nil for the entire run. base-model-spec is what
        ;; cli.cljs:461 puts in the state atom, and it carries the provider.
        (fn []
          (let [ks (vec (p/current-model-keys
                         #js {:__state_atom (atom {:base-model-spec
                                                   "omlx/Qwen3.6-35B-A3B-OptiQ-4bit"})}))]
            (-> (expect ks) (.toContain "omlx/Qwen3.6-35B-A3B-OptiQ-4bit"))
            (-> (expect ks) (.toContain "Qwen3.6-35B-A3B-OptiQ-4bit")))))

    (it "handles a model passed as a bare string"
        (fn []
          (let [ks (vec (p/current-model-keys (api-with "omlx/Qwen3.6-35B")))]
            (-> (expect ks) (.toContain "omlx/Qwen3.6-35B"))
            ;; the tail is what makes a profile reachable without a provider
            (-> (expect ks) (.toContain "Qwen3.6-35B")))))

    (it "returns no keys when there is no model, rather than throwing"
        (fn []
          (-> (expect (count (vec (p/current-model-keys (api-with nil))))) (.toBe 0))))

    (it "survives an api with no state atom at all"
        (fn []
          (-> (expect (count (vec (p/current-model-keys #js {})))) (.toBe 0))))))

(describe "small-model/edit-tools-to-hide"
  (fn []
    (it "whole hides both edit tools, leaving write"
        (fn []
          (let [h (p/edit-tools-to-hide "whole")]
            (-> (expect (contains? h "edit")) (.toBe true))
            (-> (expect (contains? h "multi_edit")) (.toBe true))
            (-> (expect (contains? h "write")) (.toBe false)))))))
