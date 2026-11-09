(ns agent.extensions.model-roles.features.clarify
  "Ask before guessing. Interaction lifts results on underspecified tasks
   by up to 74% (Ambig-SWE), yet models rarely ask unprompted and cannot
   tell a vague request from a clear one. Jev decides whether this one is
   vague; the model, told to ask, writes the questions.

   Only for a message the user typed (`input_submit`, so the TUI) that then
   starts a run: never a steer, a queued follow-up or nudge, a command, a
   `!`/`$` line or a reply of a few words. Jev is asked at submit, so its
   answer is ready by the time the run starts and costs no extra wait. Opt-in with
   `judge.clarify` true; `judge.clarify-threshold` (0.8) sets how sure."
  (:require [clojure.string :as str]
            [agent.utils.content :as content]
            [agent.utils.decide :as decide]))

(def nudge
  (str "Before changing anything, ask the user the one or two questions this request"
       " leaves open, then stop until they answer. Do not guess."))

(defn ^:async underspecified?
  "Does Jev say `text` lacks what only the user can supply?"
  [text]
  (let [cfg (decide/config)
        r   (js-await
             (decide/ask! {:kind "clarify" :cfg cfg
                           :state (str "The user asked a coding agent working in their repository:\n"
                                       (decide/visible (subs text 0 (min 2000 (count text))) false))
                           :questions {"vague" {:instructions "Is this request missing information that the agent cannot find in the repository and must ask the user for before starting?"}}}))
        p   (get r "vague")]
    (and (number? p) (>= p (or (:clarify-threshold cfg) 0.8)))))

(defn- eligible? [t]
  (and (:clarify (decide/config))
       (not (contains? #{"/" "!" "$"} (subs t 0 1)))
       (>= (count (str/split t #"\s+")) 4)))

(defn activate [api]
  (let [;; {:text :verdict}: the last typed message and Jev's answer, pending
        fresh     (atom nil)
        on-submit (fn [d]
                    (let [t (str/trim (str (.-text d)))]
                      (reset! fresh (when (and (seq t) (eligible? t))
                                      {:text t :verdict (underspecified? t)})))
                    nil)
        on-start  ^:async (fn [d]
                            (let [f @fresh]
                              (reset! fresh nil)
                              ;; this run must be that message: a steer starts
                              ;; none, and a queued run carries other text
                              (when (and f (not (.-queued d))
                                         (str/includes? (str (some-> d .-userMessage :content content/->text))
                                                        (subs (:text f) 0 (min 40 (count (:text f))))))
                                (when (js-await (:verdict f))
                                  #js {"volatile-additions" #js [nudge]}))))]
    (.on api "input_submit" on-submit)
    (.on api "before_agent_start" on-start)
    (fn []
      (.off api "input_submit" on-submit)
      (.off api "before_agent_start" on-start))))
