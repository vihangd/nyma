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
  (:require [clojure.string :as str]))

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
            picker-items (cond-> (mapv (fn [o]
                                         #js {:value (.-value o)
                                              :label (or (.-label o) (.-value o))
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

;;; ─── main execute function ───────────────────────────────────

(defn ^:async questionnaire-execute [api args ctx]
  (let [ui      (.-ui api)
        signal  (when ctx (.-abortSignal ctx))]
    ;; UI availability check
    (when (not (.-available ui))
      (throw (js/Error. "UI not available")))

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
                                 (str (:id a) "="
                                      (if (:isSecret a) "[secret]" (:value a)))))
                          (str/join ", ")
                          (str "User answered: "))]
            #js {:answers  (clj->js (mapv #(dissoc % :isSecret) answers))
                 :text     text})

          ;; ── next question ─────────────────────────────────
          (let [q (first remaining)]
            (when (and signal (.-aborted signal))
              (throw (js/Error. "questionnaire_cancelled")))

            (let [answer (js-await (ask-one api q signal))]
              (if (nil? answer)
                ;; cancelled
                #js {:cancelled true
                     :answers   (clj->js answers)
                     :text      "User cancelled questionnaire"}
                (recur (rest remaining)
                       (conj answers
                             (assoc answer
                                    :id (.-id q)
                                    :isSecret (boolean (.-isSecret q)))))))))))))

;;; ─── extension lifecycle ─────────────────────────────────────

(defn ^:export default [api]
  (.registerTool api "questionnaire"
                 #js {:description
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
                                                                                              :description #js {:type "string"}}
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
                           :formatResult (fn [result]
                                           (if (.-cancelled result)
                                             "cancelled"
                                             (str (.-length (.-answers result)) " answer"
                                                  (when (> (.-length (.-answers result)) 1) "s"))))}
                      :execute (fn [args ctx] (questionnaire-execute api args ctx))})

  ;; Return cleanup function
  (fn []
    (.unregisterTool api "questionnaire")))
