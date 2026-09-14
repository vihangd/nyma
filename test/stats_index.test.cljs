(ns stats-index.test
  "/stats is where people look for every other number command, so it points
   at the ones that are registered — and only those."
  (:require ["bun:test" :refer [describe it expect]]
            ["./agent/extensions/stats_dashboard/index.mjs" :as stats-ext]))

(def ^:private stats-index-footer (.-stats_index_footer stats-ext))

(defn- cmd [d] {:description d :handler (fn [_ _] nil)})

(describe
 "/stats indexes the other number commands"
 (fn []
   (it "lists the ones that are registered"
       (fn []
         (let [text (stats-index-footer {"stats-session" (cmd "session stats")
                                         "debug"         (cmd "debug")})]
           (-> (expect (.includes text "/stats-session")) (.toBe true))
           (-> (expect (.includes text "/debug")) (.toBe true)))))

   (it "finds a namespaced extension command by its short name"
       (fn []
         (let [text (stats-index-footer
                     {"headroom__headroom-stats" (cmd "context left")})]
           (-> (expect (.includes text "/headroom-stats")) (.toBe true)))))

   (it "never advertises a command that is not registered"
       (fn []
         (let [text (stats-index-footer {"debug" (cmd "debug")})]
           (-> (expect (.includes text "/token-stats")) (.toBe false))
           (-> (expect (.includes text "/bash-stats")) (.toBe false))
           (-> (expect (.includes text "/headroom-stats")) (.toBe false)))))

   (it "says what each one answers, not just its name"
       (fn []
         (-> (expect (.includes (stats-index-footer {"bash-stats" (cmd "x")})
                                "shell commands run"))
             (.toBe true))))

   (it "shows no footer at all when none of them exist"
       (fn []
         (-> (expect (stats-index-footer {})) (.toBeNil))
         (-> (expect (stats-index-footer nil)) (.toBeNil))
         (-> (expect (stats-index-footer {"unrelated" (cmd "x")})) (.toBeNil))))))
