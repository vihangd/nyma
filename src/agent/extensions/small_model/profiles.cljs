(ns agent.extensions.small-model.profiles
  "Per-model tuning profiles.

   model_roles maps name→model.  This maps model→tuning:
     settings[\"small-model\"][\"model-profiles\"][\"provider/model\"] = {
       contextLimit     <number>
       thinking         \"off\"|\"low\"|\"medium\"|\"high\"|\"xhigh\"
       temperature      <0..1>
       resultCap        <chars>   ; overrides token-suite truncation cap
       allowedTools     [\"read\",\"write\",...]  ; narrows the tool set
       editStrategy     \"patch\"|\"whole\"      ; Aider-style per-model format
     }

   Hooks used:
     model_resolve          — read active model, apply thinking level
     before_provider_request — inject temperature / providerOptions
     tool_access_check      — apply allowedTools allowlist
  "
  (:require [agent.extensions.small-model.shared :as shared]
            [clojure.string :as str]))

;; ── Profile lookup ───────────────────────────────────────────────

(defn- current-model-id
  "Return \"provider/model\" string for the currently active model."
  [api]
  (try
    (let [st (when-let [a (.-__state_atom api)] @a)]
      (when st
        (let [cfg (:config st)
              m   (:model cfg)]
          ;; m is a Vercel AI SDK LanguageModel object with a modelId field
          ;; plus a provider prefix in the parent provider object.
          ;; We reconstruct the logical key as used in registerProvider.
          (when m
            (let [mid (or (.-modelId m) (.-id m) "")
                  prov (or (some-> m .-provider .-providerId)
                           (some-> m .-provider .-id)
                           "")]
              (if (and (seq prov) (seq mid))
                (str prov "/" mid)
                (str mid)))))))
    (catch :default _ nil)))

(defn- profile-for
  "Look up a profile for the active model. Returns a CLJS map or nil."
  [config api]
  (let [profiles (get-in config [:profiles :model-profiles])
        model-id (current-model-id api)]
    (when (and model-id (seq profiles))
      (or (get profiles model-id)
          ;; Try without provider prefix
          (let [short (last (str/split model-id #"/"))]
            (get profiles short))))))

;; ── Edit-format routing (editStrategy) ───────────────────────────
;; Weak models loop on the brittle exact `edit` (string-not-found). Per-model
;; editStrategy steers which edit tools are surfaced (Aider/OpenCode pattern):
;;   "whole"        → only whole-file `write` (most stable multi-turn)
;;   "fuzzy"/"patch"→ `multi_edit` (fuzzy) + `write`; hide exact `edit`
;;   "exact"/unset  → no edit-tool filtering
(defn edit-tools-to-hide
  "Edit-tool names to HIDE for a given editStrategy. Exposed for tests."
  [edit-strategy]
  (case (str edit-strategy)
    "whole"          #{"edit" "multi_edit"}
    ("fuzzy" "patch") #{"edit"}
    #{}))

;; ── Activation ───────────────────────────────────────────────────

(defn activate
  "Wire profile hooks. Returns a cleanup fn."
  [api config]
  (let [handlers (atom [])

        ;; model_resolve — apply thinking level for this model
        on-resolve
        (fn [_data _ctx]
          (when-let [p (profile-for config api)]
            (let [thinking (or (:thinking p) (get p "thinking"))]
              (when (string? thinking)
                (.setThinkingLevel api thinking))))
          ;; Return nil — don't override the model; only side-effect thinking level.
          nil)

        ;; before_provider_request — mutate temperature / providerOptions in place
        on-before-request
        (fn [data _ctx]
          (when-let [p (profile-for config api)]
            (let [temp (or (:temperature p) (get p "temperature"))]
              (when (number? temp)
                (let [po (or (.-providerOptions data) #js {})]
                  (aset po "temperature" temp)
                  (aset data "providerOptions" po)))))
          nil)

        ;; tool_access_check — apply per-model allowedTools AND editStrategy
        ;; edit-tool routing. Both reduce the allowlist; the event merge
        ;; intersects :allowed across handlers (most-restrictive wins), so this
        ;; composes with model_roles' role/mode restriction.
        on-tool-access
        (fn [data _ctx]
          (when-let [p (profile-for config api)]
            (let [allowed (or (:allowed-tools p) (get p "allowedTools")
                              (:allowedTools p))
                  edit-strat (or (:editStrategy p) (get p "editStrategy")
                                 (:edit-strategy p))
                  hide   (edit-tools-to-hide edit-strat)
                  ;; candidate tool names from the event (the full active set)
                  cands  (when-let [t (.-tools data)] (vec t))
                  ;; base allowlist: explicit allowedTools if set, else all candidates
                  base   (cond (seq allowed) (vec allowed)
                               (seq hide)    cands
                               :else         nil)
                  final  (when base (vec (remove #(contains? hide (str %)) base)))]
              (when final
                #js {:allowed (clj->js final)}))))]

    (.on api "model_resolve" on-resolve)
    (swap! handlers conj ["model_resolve" on-resolve])

    (.on api "before_provider_request" on-before-request)
    (swap! handlers conj ["before_provider_request" on-before-request])

    (.on api "tool_access_check" on-tool-access)
    (swap! handlers conj ["tool_access_check" on-tool-access])

    ;; Cleanup
    (fn []
      (doseq [[event handler] @handlers]
        (.off api event handler)))))
