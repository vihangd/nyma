(ns agent.extensions.questionnaire.index
  "Questionnaire extension — lets the model ask the user one or more
   questions with optional option pickers or free-form text input.

   Registers a single `questionnaire` tool. Each question may have:
     - :options list for a picker (+ synthetic 'Type your own' when
       allowOther is true, which is the default)
     - No options → always shows a text-input dialog
     - isSecret → answer is stripped from event payloads

   Returns a structured result with `:answers` and a plain-text summary
   the model can read.

   Abort handling: if the AbortSignal fires during any question the tool
   returns `{:cancelled true, :answers <partial>}` immediately."
  (:require [clojure.string :as str]
            [agent.utils.ui :as ui]))

;;; ─── constants ───────────────────────────────────────────────

(def ^:private type-own-sentinel "__questionnaire_type_own__")

;;; ─── schema validation ───────────────────────────────────────

(defn- validate-questions
  "Return error string or nil if valid."
  [questions]
  (cond
    (not (array? questions))
    "questions must be an array"

    (zero? (.-length questions))
    "questions must contain at least one question"

    :else
    (let [ids (atom #{})]
      (reduce
       (fn [err q]
         (or err
             (when (not (.-id q))    "each question must have an 'id' field")
             (when (not (.-prompt q)) "each question must have a 'prompt' field")
             (when (contains? @ids (.-id q)) (str "duplicate question id: " (.-id q)))
             (do (swap! ids conj (.-id q)) nil)
             (when (and (.-options q) (not (array? (.-options q))))
               "question 'options' must be an array when present")
             (let [opt-values (atom #{})]
               (when (.-options q)
                 (reduce (fn [e opt]
                           (or e
                               (when (not (.-value opt))
                                 (str "option in question '" (.-id q) "' must have a 'value' field"))
                               (when (contains? @opt-values (.-value opt))
                                 (str "duplicate option value in question '" (.-id q) "': " (.-value opt)))
                               (do (swap! opt-values conj (.-value opt)) nil)))
                         nil
                         (js/Array.from (.-options q)))))))
       nil
       (js/Array.from questions)))))

;;; ─── single-question UI helpers ─────────────────────────────

(defn ^:async ask-one
  "Ask a single question. Returns {:value :label? :wasCustom} or nil if
   cancelled/aborted. Uses ui.select when options provided, ui.input otherwise."
  [api q signal]
  (let [opts         (when signal #js {:signal signal})
        allow-other  (if (some? (.-allowOther q)) (.-allowOther q) true)
        ui           (.-ui api)]
    (if (and (.-options q) (pos? (.-length (.-options q))))
      ;; ── picker mode ─────────────────────────────────────────
      (let [raw-opts  (js/Array.from (.-options q))
            ;; `recommended` badges the option the model suggests, borrowed
            ;; from oh-my-pi's ask tool ("(Recommended)" in its picker).
            picker-items (cond-> (mapv (fn [o]
                                         #js {:value (.-value o)
                                              :label (str (or (.-label o) (.-value o))
                                                          (when (.-recommended o)
                                                            "  (Recommended)"))
                                              :description (.-description o)})
                                       raw-opts)
                           allow-other
                           (conj #js {:value type-own-sentinel
                                      :label "Type your own answer…"}))
            chosen (js-await (.select ui (.-prompt q) (clj->js picker-items) opts))]
        (when (some? chosen)
          (if (= (.-value chosen) type-own-sentinel)
            ;; ── free-form fallback ───────────────────────────
            (let [typed (js-await (.input ui (.-prompt q) "" opts))]
              (when (some? typed)
                {:value typed :wasCustom true}))
            {:value (.-value chosen) :label (.-label chosen) :wasCustom false})))
      ;; ── text-input mode ─────────────────────────────────────
      (let [typed (js-await (.input ui (.-prompt q) "" opts))]
        (when (some? typed)
          {:value typed :wasCustom false})))))

;;; ─── result shape ────────────────────────────────────────────
;;
;; The tool contract is string-first: middleware/normalize-tool-result reads a
;; string, a `.summary`, or `.content[]` — and NOTHING else. This tool returned
;; a bare `{answers, text}`, so the model's copy of a 74-second questionnaire
;; was the string "[object Object]". `text` below is now the model's only view
;; of the answers, so it carries them in full.

(defn answered-headline
  "First line of the answered result. Stable and countable: formatResult sees
   this string AFTER the result policy and a 500-char truncation, so anything
   it counts has to live on line one."
  [n]
  (str "User answered " n " question" (when (not= n 1) "s") ":"))

(def cancelled-headline "User cancelled the questionnaire.")

(defn result-envelope
  "The pi-compatible shape normalize-tool-result understands. `answers` stays
   reachable on `.details` for structured consumers (and for the tests, which
   assert on the answers themselves)."
  [text answers]
  #js {:content #js [#js {:type "text" :text text}]
       :details #js {:answers (clj->js answers)}
       ;; Kept for callers that already read these directly.
       :text    text
       :answers (clj->js answers)})

;;; ─── main execute function ───────────────────────────────────

(defn ^:async questionnaire-execute [api args ctx]
  (let [ui      (.-ui api)
        signal  (when ctx (.-abortSignal ctx))]
    ;; UI availability check. `available` alone is not enough: it is true in
    ;; the interactive TUI even on hosts that never wired select/input, and
    ;; this tool then called an undefined `select`. Require the slots it
    ;; actually uses (same predicate the rest of the repo guards with).
    (when-not (ui/ui-prompt-ready? ui)
      (throw (js/Error. "UI not available: this host has no interactive prompt support")))

    (let [questions (.-questions args)
          err       (validate-questions questions)]
      (when err
        (throw (js/Error. (str "Invalid questionnaire: " err))))

      ;; Process questions in sequence
      (loop [remaining (js/Array.from questions)
             answers   []]
        (if (empty? remaining)
          ;; ── all answered ──────────────────────────────────
          (let [text (->> answers
                          (map (fn [a]
                                 (str "  " (:id a) ": "
                                      (if (:isSecret a) "[secret]" (:value a)))))
                          (str/join "\n")
                          (str (answered-headline (count answers)) "\n"))]
            (result-envelope text (mapv #(dissoc % :isSecret) answers)))

          ;; ── next question ─────────────────────────────────
          (let [q (first remaining)]
            (when (and signal (.-aborted signal))
              (throw (js/Error. "questionnaire_cancelled")))

            (let [answer (js-await (ask-one api q signal))]
              (if (nil? answer)
                ;; cancelled
                (let [env (result-envelope cancelled-headline
                                           (mapv #(dissoc % :isSecret) answers))]
                  (aset env "cancelled" true)
                  env)
                (recur (rest remaining)
                       (conj answers
                             (assoc answer
                                    :id (.-id q)
                                    :isSecret (boolean (.-isSecret q)))))))))))))

;;; ─── extension lifecycle ─────────────────────────────────────

(defn ^:export default [api]
  (.registerTool api "questionnaire"
                 #js {:safety #js {:read-only? true :category "meta"}
                      :description
                      "Ask the user one or more questions and collect their answers.
Use for clarifications, preference choices, or gathering structured user input.
Each question may offer a list of options (user may also type a custom answer)
or be an open-ended text question."
                      :parameters
                      #js {:type "object"
                           :properties
                           #js {:questions
                                #js {:type "array"
                                     :description "List of questions to ask in order."
                                     :items
                                     #js {:type "object"
                                          :properties
                                          #js {:id          #js {:type "string"
                                                                 :description "Unique identifier for this question."}
                                               :prompt      #js {:type "string"
                                                                 :description "The question text shown to the user."}
                                               :options     #js {:type "array"
                                                                 :description "Optional list of choices. User may also type a custom answer unless allowOther is false."
                                                                 :items #js {:type "object"
                                                                             :properties #js {:value #js {:type "string"}
                                                                                              :label #js {:type "string"}
                                                                                              :description #js {:type "string"}
                                                                                              :recommended #js {:type "boolean"
                                                                                                                :description "Mark this as the suggested choice — shown with a (Recommended) badge."}}
                                                                             :required #js ["value"]}}
                                               :allowOther  #js {:type "boolean"
                                                                 :description "Whether to allow free-form answer in addition to options (default: true)."}
                                               :isSecret    #js {:type "boolean"
                                                                 :description "If true, the answer is hidden from logs (default: false)."}}
                                          :required #js ["id" "prompt"]}}}
                           :required #js ["questions"]}
                      :display
                      #js {:icon "❓"
                           :formatArgs (fn [_name args]
                                         (let [qs (.-questions args)]
                                           (if (and qs (pos? (.-length qs)))
                                             (str (.-length qs) " question" (when (> (.-length qs) 1) "s"))
                                             "questions")))
                           ;; A STRING, not the object: middleware hands
                           ;; formatResult the policy-processed, 500-char
                           ;; truncated result. Reading `.answers` off it
                           ;; yielded undefined and threw, and safe-call
                           ;; swallowed that — which is why the line read
                           ;; "[object Object]" instead of "2 answers".
                           :formatResult (fn [result]
                                           (let [s (str result)]
                                             (if (.startsWith s cancelled-headline)
                                               "cancelled"
                                               (let [m (.match s (js/RegExp. "User answered (\\d+) question"))]
                                                 (if m
                                                   (str (aget m 1) " answer"
                                                        (when (not= "1" (aget m 1)) "s"))
                                                   "answered")))))}
                      :execute (fn [args ctx] (questionnaire-execute api args ctx))})

  ;; ── Withhold the schema on a host that cannot prompt ──────────
  ;; This tool's schema is ~900 tokens: a four-level nested questions → options
  ;; → {value,label,description,recommended} object, on every request. On a host
  ;; with no interactive prompt — `--print`, the benchmark, any headless run —
  ;; `questionnaire-execute` throws immediately, so all of it is on the wire to
  ;; produce a throw.
  ;;
  ;; Gated on `ui-prompt-ready?`, the same predicate the execute path uses and
  ;; the one the permission gate, plan mode, escalate and refine all guard with.
  ;; Deliberately not the cli's `mode`: that classes "rpc" as headless, but an
  ;; RPC frontend can proxy select/input, and then the tool does work.
  ;;
  ;; Re-checked per turn rather than once: `.ui` is populated by the host after
  ;; extensions load, so asking at activation time would answer for every host.
  (.on api "tool_access_check"
       (fn [data _ctx]
         (when-not (ui/ui-prompt-ready? (.-ui api))
           (let [cands (vec (or (.-tools data) []))
                 kept  (vec (remove (fn [c]
                                      (let [s (str c)]
                                        (or (= s "questionnaire")
                                            (.endsWith s "__questionnaire"))))
                                    cands))]
             ;; nil when nothing was removed — nil is "no opinion" to the
             ;; intersection merge, and an empty vector would hide everything.
             (when (not= (count kept) (count cands))
               #js {:allowed (clj->js kept)})))))

  ;; Return cleanup function
  (fn []
    (.unregisterTool api "questionnaire")))
