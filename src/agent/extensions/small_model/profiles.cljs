(ns agent.extensions.small-model.profiles
  "Per-model tuning profiles.

   model_roles maps name→model.  This maps model→tuning:
     settings[\"small-model\"][\"model-profiles\"][\"provider/model\"] = {
       contextLimit     <number>   ; NOT consumed — no clean context-budget hook
                                 ; exists without coupling into token_suite;
                                 ; deferred (use token_suite / compaction instead)
       thinking         \"off\"|\"low\"|\"medium\"|\"high\"|\"xhigh\"
       temperature      <0..1>
       resultCap        <chars>   ; cap each tool result to N chars (self-contained)
       allowedTools     [\"read\",\"write\",...]  ; narrows the tool set
       editStrategy     \"patch\"|\"whole\"      ; Aider-style per-model format
     }

   Hooks used:
     model_resolve          — read active model, apply thinking level
     before_provider_request — inject temperature / providerOptions
     tool_access_check      — apply allowedTools allowlist
     addMiddleware :leave   — cap tool results to resultCap chars
  "
  (:require [agent.tool-metadata :as tool-metadata]
            [agent.debug :as d]
            [agent.extensions.small-model.shared :as shared]
            [clojure.string :as str]))

;; ── Profile lookup ───────────────────────────────────────────────

(defn model-keys
  "Pure: every key a profile may be written under, most specific first.

   The AI SDK sets `.provider` to a STRING (`\"openai.chat\"`) for every
   OpenAI-compatible provider, so `.provider.providerId` is undefined and the
   old single key collapsed to the bare modelId — meaning every profile key in
   the docs (`\"omlx/Qwen3.6-27B-oQ4-mtp\"`) silently matched nothing and the
   whole module was inert. nyma's own provider name lives on the config as
   `active-provider-name`, set by setModel. Exposed for tests."
  [provider-name model-id]
  (let [mid  (str (or model-id ""))
        prov (str (or provider-name ""))
        tail (last (.split mid "/"))]
    (vec (distinct (remove #(or (nil? %) (= "" %))
                           [(when (and (seq prov) (seq mid)) (str prov "/" mid))
                            mid
                            tail])))))

(defn current-model-keys
  "Keys to look a profile up under, resolved from the live agent.

   This read `(:config @__state_atom)` and the state atom has no :config — it
   carries :model, :active-tools, :active-role and friends, while :config lives
   on the agent map. So cfg was nil, `(aget nil ...)` threw, the catch returned
   [], and profile-for could never match ANYTHING. Every per-model profile —
   editStrategy, temperature, resultCap, allowedTools — was inert, and the
   symptom was invisible: a hidden tool that is never hidden looks exactly like
   a model that chose not to call it.

   Measured: with editStrategy \"whole\" configured, which hides `edit`, the
   agent called `edit` 3 and 5 times on two tasks.

   The model object IS in the state atom. The provider name is not — it lives
   on the agent config as active-provider-name, which extensions cannot reach —
   so the provider/model key only resolves when the model itself carries a
   provider id. model-keys already falls back to the bare model id, which is
   what makes a profile reachable either way."
  [api]
  (try
    (let [spec (try (when-let [f (.-getActiveModelSpec api)] (f)) (catch :default _ nil))
          st   (when-let [a (.-__state_atom api)] @a)
          ;; :model is only populated when something calls setModel, which the
          ;; CLI never does — it assigns (.-model (:config agent)) directly,
          ;; and extensions cannot see the agent config. What the CLI DOES put
          ;; in the state atom is :base-model-spec, the "<provider>/<model-id>"
          ;; it resolved at startup (cli.cljs). That is the only place an
          ;; extension can learn the active model, so it is the fallback.
          m    (:model st)
          mid  (cond
                 (nil? m)    (str (:base-model-spec st) "")
                 (string? m) m
                 :else       (or (some-> m .-modelId) (some-> m .-id)
                                 (str (:base-model-spec st) "")))
          prov (or (some-> m .-provider .-providerId) "")
          ;; getActiveModelSpec reads the agent config, which BOTH cli paths
          ;; write; everything below it is a fallback for hosts that predate it.
          ks   (if (seq (str spec))
                 (model-keys "" (str spec))
                 (model-keys prov mid))]
      ;; 3-arity: (tag msg extras). Passing a map as msg logs "[object Object]"
      ;; and tells you nothing, which is what the first version did.
      (d/debug "small-model/profiles" "resolved model keys"
               #js {:spec (str spec) :keys (clj->js (vec ks))})
      ks)
    (catch :default _ [])))

(defn- profile-for
  "Look up a profile for the active model. Returns a CLJS map or nil."
  [config api]
  (let [profiles (get-in config [:profiles :model-profiles])]
    (when (seq profiles)
      (some (fn [k] (get profiles k)) (current-model-keys api)))))

;; ── Edit-format routing (editStrategy) ───────────────────────────
;; Weak models loop on the brittle exact `edit` (string-not-found). Per-model
;; editStrategy steers which edit tools are surfaced (Aider/OpenCode pattern):
;;   "whole"        → only whole-file `write` (most stable multi-turn)
;;   "fuzzy"/"patch"→ `multi_edit` (fuzzy) + `write`; hide exact `edit`
;;   "exact"/unset  → no edit-tool filtering
(defn resolve-allowed
  "The allowlist this profile contributes to `tool_access_check`, or nil for
   \"no opinion\".

   `cands` are the tools on offer this turn, `allowed` the profile's explicit
   list, `hide` the edit tools the edit-strategy removes.

   The gateway union is the subtle part. A profile allowlist says which tools
   this model is GOOD at — a capability preference, not a permission boundary.
   It was written before MCP deferral existed, so it names neither gateway
   tool, and because this event merges by INTERSECTION, naming neither removes
   both: the deferred MCP tools end up withheld AND unreachable, which is worse
   than either deferring or not. So the gateways ride along when they are
   genuinely on offer. A narrower expressing permission — plan mode, role
   policy — deliberately does not do this, because there withholding the route
   is the point."
  [cands allowed hide & [keep]]
  (let [hide  (or hide #{})
        keep  (or keep #{})
        cands (vec (or cands []))
        base  (cond (seq allowed) (vec allowed)
                    (seq hide)    cands
                    :else         nil)]
    (when base
      (let [pruned  (remove #(contains? hide (str %)) base)
            offered (set (map str cands))
            gateway (filter offered tool-metadata/gateway-tool-names)
            ;; Same reasoning as the gateway union, one step further. The
            ;; strategy's whole job is to route the model ONTO a tool; hiding
            ;; `edit` only helps if what it routes to is actually on the wire.
            ;; An allowlist written before this existed names `edit` and not
            ;; `multi_edit`, so "patch" pruned the one edit tool that was
            ;; allowed and could not add the one it wanted: the profile read
            ;; "patch" and behaved as "whole", leaving `write` as the only way
            ;; to change a file. Measured as 0 `edit` calls and 2.5x the writes
            ;; across 25 tasks. Still subject to `hide`, so a later strategy
            ;; cannot be overridden by an earlier one's target.
            kept    (->> tool-metadata/gateway-tool-names
                         (concat keep)
                         (filter offered)
                         (remove #(contains? hide (str %))))]
        (vec (distinct (concat pruned gateway kept)))))))

(defn edit-tools-to-hide
  "Edit-tool names to HIDE for a given editStrategy. Exposed for tests."
  [edit-strategy]
  (case (str edit-strategy)
    "whole"          #{"edit" "multi_edit"}
    ("fuzzy" "patch") #{"edit"}
    #{}))

(defn edit-tools-to-keep
  "The edit tools a strategy routes the model ONTO, which must therefore survive
   a profile allowlist that predates the strategy. Hiding `edit` accomplishes
   nothing if the fuzzy tool it steers toward was never allowlisted."
  [edit-strategy]
  (case (str edit-strategy)
    "whole"           #{"write"}
    ("fuzzy" "patch") #{"multi_edit" "write"}
    #{}))

(defn cap-result
  "Cap a tool result to ~`cap` chars, keeping a head AND tail slice so the end
   of the output (where errors/results often land) survives — the same intent
   as token_suite's line-based `truncate-head-tail`, but in chars to match the
   per-model `resultCap` unit. Returns the result unchanged when cap is
   unset/non-positive or it already fits."
  [result cap]
  (if (and (number? cap) (pos? cap) (string? result) (> (count result) cap))
    (let [head (js/Math.floor (* cap 0.7))
          tail (- cap head)]
      (str (.slice result 0 head)
           "\n…[" (- (count result) cap) " chars truncated by small-model profile]…\n"
           (.slice result (- (count result) tail))))
    result))

;; ── Activation ───────────────────────────────────────────────────

(defn activate
  "Wire profile hooks. Returns a cleanup fn."
  [api config]
  (let [;; model_resolve — apply thinking level for this model
        on-resolve
        (fn [_data _ctx]
          (when-let [p (profile-for config api)]
            (let [thinking (:thinking p)]
              (when (string? thinking)
                (.setThinkingLevel api thinking))))
          ;; Return nil — don't override the model; only side-effect thinking level.
          nil)

        ;; before_provider_request — mutate temperature / providerOptions in place
        on-before-request
        (fn [data _ctx]
          (when-let [p (profile-for config api)]
            (let [temp (:temperature p)]
              ;; streamText reads a TOP-LEVEL temperature. providerOptions is
              ;; namespaced per provider, so the value written there below never
              ;; reached the wire — this module's temperature has always been a
              ;; no-op. Write both: the top level is what actually applies.
              (when (number? temp)
                (aset data "temperature" temp)
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
          (d/debug "small-model/tool-access" "gate"
                   #js {:profile (boolean (profile-for config api))
                        :tools   (clj->js (vec (or (.-tools data) [])))})
          (when-let [p (profile-for config api)]
            (let [allowed (or (:allowed-tools p) (get p "allowedTools")
                              (:allowedTools p))
                  edit-strat (or (:editStrategy p)
                                 (:edit-strategy p))
                  hide   (edit-tools-to-hide edit-strat)
                  keep   (edit-tools-to-keep edit-strat)
                  ;; candidate tool names from the event (the full active set)
                  cands  (when-let [t (.-tools data)] (vec t))
                  ;; base allowlist: explicit allowedTools if set, else all candidates
                  final  (resolve-allowed cands allowed hide keep)]
              (when final
                #js {:allowed (clj->js final)}))))

        ;; addMiddleware :leave — cap each tool result to resultCap chars so a
        ;; weak model's context isn't blown by one huge tool output. Profile
        ;; value is read per-call so it tracks the active model.
        result-cap-mw
        #js {:name  "small-model/profiles-result-cap"
             :leave (fn [ctx]
                      (when-let [p (profile-for config api)]
                        (let [cap (or (:result-cap p) (get p "resultCap"))
                              r   (.-result ctx)
                              capped (cap-result r cap)]
                          (when (not= capped r)
                            (aset ctx "result" capped))))
                      ctx)}]

    (.on api "model_resolve" on-resolve)

    (.on api "before_provider_request" on-before-request)

    (.on api "tool_access_check" on-tool-access)

    (.addMiddleware api result-cap-mw)

    ;; Cleanup
    (fn []
      (.removeMiddleware api "small-model/profiles-result-cap"))))
