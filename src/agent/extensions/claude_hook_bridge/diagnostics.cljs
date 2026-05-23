(ns agent.extensions.claude-hook-bridge.diagnostics
  "Track which configured (event, matcher) pairs actually fired
   during a session, and surface a warning at session end for any
   that never matched anything.

   Catches the most common hook-config mistake — a typo in a
   matcher (often a casing typo, mostly fixed in matcher.cljs's
   case-tolerance, but other typos like `Edot` vs `Edit` slip
   through) — without changing runtime behavior. Pure observability.

   Hot reload calls reset! so unseen matchers from the OLD config
   don't bleed into the report after the user fixes a typo and
   saves."
  (:require [clojure.string :as str]
            [agent.extensions.token-suite.shared :as ts-shared]))

;; Internally we key by a `${event}::${matcher}` string, not a
;; [event matcher] vector. Squint's set is a JS Set, which compares
;; arrays by reference — vector keys would never match. Strings hash
;; by value so we get the semantics we want.

(def ^:private seen
  "Set of \"event::matcher\" strings that fired this session."
  (atom #{}))

(defn- key-of [event-name matcher]
  (str (str event-name) "::" (or (when matcher (str matcher)) "")))

(defn record-match!
  "Called by dispatch when a matcher actually fires."
  [event-name matcher]
  (swap! seen conj (key-of event-name matcher)))

(defn reset!
  "Clear the seen-set. Called on bridge re-activation (hot reload)."
  []
  (cljs.core/reset! seen #{}))

(defn- configured-pairs
  "Walk the hooks-map and return every [event matcher] pair the
   user has configured. Empty/nil/'*' matchers count as ''
   (always-fires) — those don't get unseen warnings since they
   match everything by definition."
  [hooks-map]
  (let [pairs (atom [])]
    (doseq [[event entries] hooks-map]
      (doseq [block entries]
        (let [m (or (.-matcher block) "")]
          (when-not (or (= m "") (= m "*"))
            (swap! pairs conj [event m])))))
    @pairs))

(defn- did-you-mean
  "Suggest a likely tool-name correction for a typo'd matcher.
   Cheap Levenshtein-style: if any known CC tool name is within
   edit-distance 2 of the matcher, return it. Otherwise nil."
  [matcher]
  (let [target (.toLowerCase (str matcher))
        candidates ["Bash" "Read" "Edit" "Write" "Glob" "Grep" "LS"
                    "WebFetch" "WebSearch" "Agent" "ExitPlanMode" "Think"
                    "AskUserQuestion" "NotebookEdit"]
        ;; Reuse the suite's Levenshtein implementation.
        score-fn (fn [c]
                   {:cand c
                    :dist (ts-shared/levenshtein-distance target (.toLowerCase c))})
        best (->> candidates
                  (map score-fn)
                  (sort-by :dist)
                  first)]
    (when (and best (<= (:dist best) 2) (not= 0 (:dist best)))
      (:cand best))))

(defn report-unseen
  "Compute and log unseen matchers given the current hooks-map.
   Returns the list of unseen pairs (for tests). Logs nothing
   when everything fired."
  [hooks-map]
  (let [seen-set @seen
        all-pairs (configured-pairs hooks-map)
        unseen (->> all-pairs
                    (remove (fn [[ev m]] (contains? seen-set (key-of ev m))))
                    vec)]
    (when (and (seq unseen) (.-NYMA_DEBUG js/process.env))
      (js/console.warn
       (str "[hook-bridge] " (count unseen)
            " configured matcher(s) never fired this session:"))
      (doseq [[event matcher] unseen]
        (let [hint (did-you-mean matcher)]
          (js/console.warn
           (str "  " event " matcher " (pr-str matcher)
                (when hint (str " (did you mean " (pr-str hint) "?)")))))))
    unseen))

;; Test helper exposed under a different name to avoid shadowing
;; clojure.core/reset!.
(def reset-seen! reset!)
(def configured-pairs* configured-pairs)
(def did-you-mean* did-you-mean)
