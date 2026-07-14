(ns agent.extensions.handoff.index
  "/handoff — write a purpose-built brief of this session to .nyma/handoff.md
   (one generateText call over the transcript, no tool loop), and inject any
   existing brief into new sessions until it is cleared or overwritten."
  (:require ["ai" :refer [generateText]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.extensions.handoff.shared :as shared]))

(defn- current-model [api]
  (when-let [a (aget api "__state_atom")]
    (try (:model (:config @a)) (catch :default _ nil))))

(defn ^:async generate-brief!
  "Generate + write the brief. gen-fn injectable for tests."
  [api gen-fn]
  (let [msgs  (:messages (.getState api))
        text  (shared/format-transcript (or msgs []))]
    (if (empty? text)
      "Nothing to hand off — the transcript is empty."
      (if-let [model (current-model api)]
        (let [result (js-await (gen-fn #js {:model model
                                            :system shared/brief-system-prompt
                                            :prompt text
                                            :maxOutputTokens 2048}))
              brief  (str (.-text result))
              f      (shared/handoff-path)]
          (fs/mkdirSync (path/dirname f) #js {:recursive true})
          (fs/writeFileSync f brief)
          (str "Handoff brief written to " f " — run /new; the brief seeds the next session. "
               "`/handoff clear` removes it."))
        "Handoff: no active model to generate the brief with."))))

(defn ^:export activate [api]
  (let [on-before-start
        (fn [_data _ctx]
          (let [f (shared/handoff-path)]
            (when (fs/existsSync f)
              #js {:system-prompt-additions
                   #js [(shared/injection-block (fs/readFileSync f "utf8"))]})))]

    (.on api "before_agent_start" on-before-start)

    (.registerCommand api "handoff"
                      #js {:description "Write a handoff brief for the next session (Amp-style /compact alternative). Usage: /handoff | /handoff clear"
                           :handler
                           (fn [args ctx]
                             (let [notify (fn [m l] (when-let [ui (.-ui ctx)]
                                                      (when (.-notify ui) (.notify ui m l))))]
                               (if (= (some-> (first args) str) "clear")
                                 (let [f (shared/handoff-path)]
                                   (if (fs/existsSync f)
                                     (do (fs/unlinkSync f) (notify "Handoff brief cleared." "info"))
                                     (notify "No handoff brief to clear." "warning")))
                                 (do (notify "Generating handoff brief…" "info")
                                     (-> (generate-brief! api generateText)
                                         (.then (fn [msg] (notify msg "info")))
                                         (.catch (fn [e] (notify (str "Handoff failed: " (.-message e)) "error"))))))))})

    (fn []
      (.off api "before_agent_start" on-before-start)
      (.unregisterCommand api "handoff"))))

(def ^:export default activate)
