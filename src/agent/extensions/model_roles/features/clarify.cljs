(ns agent.extensions.model-roles.features.clarify
  "Ask before guessing. Interaction lifts results on underspecified tasks
   by up to 74% (Ambig-SWE), yet models rarely ask unprompted and cannot
   tell a vague request from a clear one. Jev decides whether this one is
   vague; the model, told to ask, writes the questions.

   Only for a message the user typed (`input_submit`, so the TUI): never a
   follow-up, a nudge, a command or a reply of a few words. Opt-in with
   `judge.clarify` true; `judge.clarify-threshold` (0.8) sets how sure."
  (:require [clojure.string :as str]
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

(defn activate [api]
  (let [fresh     (atom nil)
        on-submit (fn [d]
                    (let [t (str/trim (str (.-text d)))]
                      (reset! fresh (when (and (:clarify (decide/config))
                                               (not (str/starts-with? t "/"))
                                               (>= (count (str/split t #"\s+")) 4))
                                      t)))
                    nil)
        on-start  ^:async (fn [_]
                            (when-let [t @fresh]
                              (reset! fresh nil)
                              (when (js-await (underspecified? t))
                                #js {"volatile-additions" #js [nudge]})))]
    (.on api "input_submit" on-submit)
    (.on api "before_agent_start" on-start)
    (fn []
      (.off api "input_submit" on-submit)
      (.off api "before_agent_start" on-start))))
