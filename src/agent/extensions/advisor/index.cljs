(ns agent.extensions.advisor.index
  "Advisor pattern — model-as-critic.

   Mirrors Anthropic's advisor tool (Claude API beta, April 2026): the
   executor model can call `advisor` to consult a stronger reviewer
   model that sees the full conversation transcript and returns a plan,
   correction, or stop signal. Empirical impact: +2.7pp on SWE-bench
   Multilingual at -11.9% cost (Sonnet+Opus advisor vs Sonnet alone).

   This implementation is provider-agnostic. The advisor model is
   resolved via the existing settings.roles map:
     settings.roles.advisor  →  fallback: settings.roles.deep
                                fallback: current active model

   FUTURE: when @ai-sdk/anthropic ships native support for Anthropic's
   platform-side advisor tool (`advisor_20260301`, beta header
   `advisor-tool-2026-03-01`), tracked at vercel/ai#14285, switch to
   the platform tool when both executor and advisor are on Anthropic.
   Doing it manually today is half-broken — we can inject the request
   spec via before_provider_request, but the AI SDK's response parser
   doesn't know `advisor_tool_result` / `advisor_redacted_result`
   blocks, so the advice never reaches the executor. Until the
   adapter lands the response side, the cross-provider generateText
   path here is the only safe option.

   Two entrypoints share one core fn (`consult-advisor`):
     - `advisor` tool — model-invokable, executor uses it autonomously
     - `/advisor [focus]` slash command — user-invokable, forces a
       consultation mid-conversation

   The advisor sees the full transcript but is invoked without tools,
   so it cannot recursively call advisor — loop-safe by construction."
  (:require ["ai" :refer [generateText]]
            [agent.token-estimation :as te]
            [clojure.string :as str]))

(def advisor-system-prompt
  "You are an advisor. The executor agent is consulting you for guidance.

You have access to the FULL transcript of the executor's session — every
user message, every assistant turn, every tool call, every tool result.
Read it carefully before responding.

Return concise, specific advice:
  - file paths and line numbers when pointing at code
  - what to change, not just what's wrong
  - if the executor is stuck on a wrong premise, say so directly
  - if the executor should stop and ask the user, say so
  - if the work looks correct and ready, say \"ready\" with one-sentence justification

Do not delegate understanding back to the executor (\"based on your
findings, fix it\") — the executor is asking precisely because it cannot
synthesize. Be the synthesis.

Do not call tools; you are a reviewer, not a co-executor. Output is
plain text; the executor will read it on its next turn.")

(def ^:private warn-token-threshold 100000)
(def ^:private hard-cap-token-threshold 200000)

(defn resolve-advisor-model
  "Pick the advisor model.
     1. settings.roles.advisor (explicit user setting)
     2. settings.roles.deep    (fallback to existing 'deep' role)
     3. nil → caller falls back to current active model
   Returns {:provider <str> :model <str>} or nil."
  [settings]
  (let [roles (:roles settings)
        adv   (:advisor roles)
        deep  (:deep roles)]
    (cond
      (and adv (:provider adv) (:model adv))    {:provider (:provider adv) :model (:model adv)}
      (and deep (:provider deep) (:model deep)) {:provider (:provider deep) :model (:model deep)}
      :else                                     nil)))

(defn format-transcript-for-advisor
  "Strip nyma-internal fields so the advisor sees a clean OpenAI-style
   messages array. Keeps role + content; drops :local-only,
   :tool-call-id binding etc. Tool calls/results are summarized into
   text so the advisor can read them without needing the original
   multi-part shape (different providers have different schemas)."
  [messages]
  (->> messages
       (remove :local-only)
       (keep (fn [m]
               (let [role    (:role m)
                     content (:content m)]
                 (case role
                   "user"        {:role "user"      :content (str content)}
                   "assistant"   {:role "assistant" :content (str content)}
                   "tool_call"   {:role "assistant"
                                  :content (str "[tool call: " (:tool-name m)
                                                " "
                                                (when (:args m)
                                                  (try (js/JSON.stringify (clj->js (:args m)))
                                                       (catch :default _ "")))
                                                "]")}
                   "tool_result" {:role "user"
                                  :content (str "[tool result: "
                                                (.slice (str content) 0 4000) "]")}
                   nil))))
       vec))

(defn ^:async consult-advisor
  "Core consultation fn. Reads the agent's transcript, resolves the
   advisor model, sends to generateText, returns the advice text.

   Returns a string in all cases (advice on success, structured
   error string on refusal/failure) — Anthropic's advisor pattern
   relies on tool output being a string the executor can react to.

   Optional opts: {:focus <str> :gen-fn <fn>} (gen-fn used in tests)."
  [api settings opts]
  (let [focus    (:focus opts)
        gen-fn   (or (:gen-fn opts) generateText)
        msgs     (or (:messages opts)            ; allow tests to inject
                     (:messages (.getState api)))
        forwarded (format-transcript-for-advisor msgs)
        ;; Append optional focus as a final user message.
        forwarded2 (if (and focus (seq focus))
                     (conj forwarded
                           {:role "user"
                            :content (str "Focus question: " focus)})
                     forwarded)
        tokens   (te/estimate-messages-tokens forwarded2)]
    (cond
      (zero? (count forwarded2))
      "Advisor: nothing to review — the transcript is empty."

      (> tokens hard-cap-token-threshold)
      (str "Advisor: refused — transcript is " tokens " tokens, over the "
           hard-cap-token-threshold "-token cap. Run /compact first or "
           "narrow the scope before consulting.")

      :else
      (let [chosen (resolve-advisor-model settings)
            current-model (when-let [a (aget api "__state_atom")]
                            (try (:model (:config @a))
                                 (catch :default _ nil)))
            ;; Try the role-resolved model first, but if its provider
            ;; lacks credentials (common: default `:advisor {:provider
            ;; "anthropic"}` for a `claude-native` OAuth-only user),
            ;; silently fall back to the current active model rather
            ;; than blowing up the tool call.
            model  (or (when chosen
                         (try ((aget api "resolveModel")
                               (:provider chosen) (:model chosen))
                              (catch :default _ nil)))
                       current-model)
            warn   (when (> tokens warn-token-threshold)
                     (str "[advisor: large transcript, " tokens
                          " tokens — proceeding] "))]
        (cond
          (nil? model)
          "Advisor: no model resolved. Set settings.roles.advisor to a {provider, model} pair."

          :else
          (try
            (let [result (js-await
                          (gen-fn #js {:model           model
                                       :system          advisor-system-prompt
                                       :messages        (clj->js forwarded2)
                                       :maxOutputTokens 2048}))
                  text   (str (.-text result))]
              (if warn (str warn text) text))
            (catch :default e
              (str "Advisor: call failed — "
                   (or (.-message e) (str e))))))))))

(defn ^:export default [api]
  ;; Tool: model-invokable autonomous consultation.
  (.registerTool api "advisor"
                 #js {:description
                      (str "Consult a stronger reviewer LLM (the 'advisor') "
                           "with the full conversation transcript. The advisor "
                           "sees what you've done — every tool call, every "
                           "result, every prior thought. Use it BEFORE "
                           "substantive work (before writing/committing/finalizing), "
                           "when stuck (errors recurring, approach not converging), "
                           "when considering a change of approach, or before "
                           "declaring done. Pass NO arguments by default — the "
                           "entire transcript is forwarded automatically. The "
                           "advisor returns a plan, correction, or stop signal.")
                      :parameters #js {:type       "object"
                                       :properties #js {:focus #js {:type        "string"
                                                                    :description "Optional one-line focus question to pin the advisor's attention."}}}
                      :display    #js {:icon "🧭"
                                       :formatArgs (fn [_n args]
                                                     (or (.-focus args) "(full transcript)"))}
                      :execute    (fn [args]
                                    (consult-advisor
                                     api
                                     (or (and (.-getSettings api)
                                              (.getSettings api))
                                         {})
                                     {:focus (.-focus args)}))})

  ;; Slash command: user-driven forced consultation.
  (.registerCommand api "advisor"
                    #js {:description "Consult the advisor model with the full transcript. Usage: /advisor [optional focus question]"
                         :handler
                         (fn [args ctx]
                           (let [focus (when (seq args) (.join (clj->js args) " "))
                                 settings (or (and (.-getSettings api)
                                                   (.getSettings api))
                                              {})]
                             (-> (consult-advisor api settings {:focus focus})
                                 (.then (fn [text]
                                          (.notify (.-ui ctx)
                                                   (str "🧭 Advisor:\n\n" text)
                                                   "info"))))))})

  ;; Cleanup
  (fn []
    (.unregisterTool api "advisor")
    (.unregisterCommand api "advisor")))
