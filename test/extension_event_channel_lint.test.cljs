(ns extension-event-channel-lint.test
  "`api.events` is the INTER-EXTENSION bus, and it prefixes every name.

   extension_scope.cljs wraps it so `on`/`emit` become `ns__name`. That is
   correct for extension-to-extension chatter and silently wrong for a core
   event: spec_driven emitted `spec-driven__turn_request` while interactive mode
   subscribes to the bare `turn_request`, so three features that arm a turn —
   /spec run, /spec next, an import's decomposition seed — dropped their prompt
   on the floor. Every unit test passed; the doubles were built on `.emit`.

   The correct channels for a CORE event are `(.on api …)` and
   `(.emitGlobal api …)`, neither of which prefixes.

   This is a lint, not a proof: it flags a core event name passed to a handle
   bound from `api.events`."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            ["./agent/events.mjs" :as events]))

(def ^:private ext-root
  (path/resolve (js/process.cwd) "src" "agent" "extensions"))

(def core-events
  "The core bus's event names, read from the source of truth rather than
   copied — a list that drifts is a lint that stops linting."
  (set (map str (vec events/all-event-types))))

(defn- cljs-files [dir]
  (reduce
   (fn [acc e]
     (let [p (path/join dir (.-name e))]
       (cond
         (.isDirectory e) (into acc (cljs-files p))
         (.endsWith (.-name e) ".cljs") (conj acc p)
         :else acc)))
   []
   (vec (fs/readdirSync dir #js {:withFileTypes true}))))

(defn prefixed-core-events
  "Core event names sent through an `api.events` handle in `source`.

   Two steps rather than a proximity window, which flagged a comment
   mentioning `turn_end` two lines below an unrelated `(.-events api)`:
   first find the symbols bound to the scoped bus, then look for a core name
   passed to one of them."
  [source]
  (let [src  (str source)
        ;; (let [events (.-events api)] …) and
        ;; (let [emit (some-> (.-events api) .-emit)] …)
        syms (->> (concat (re-seq #"\[\s*([a-zA-Z][\w*!?<>=-]*)\s+\(\.-events api\)" src)
                          (re-seq #"\[\s*([a-zA-Z][\w*!?<>=-]*)\s+\(some->\s+\(\.-events api\)" src))
                  (map second)
                  set)
        ;; …plus the inline ((.-emit (.-events api)) "name" …) shape.
        inline (->> (re-seq #"\(\.(?:emit|on)\s+\(\.-events api\)\s+\"([^\"]+)\""  src)
                    (map second))
        via    (mapcat
                (fn [sym]
                  (concat
                   (map second (re-seq (js/RegExp. (str "\\(\\.(?:emit|on)\\s+" sym "\\s+\"([^\"]+)\"") "g") src))
                   (map second (re-seq (js/RegExp. (str "\\(" sym "\\s+\"([^\"]+)\"") "g") src))))
                syms)]
    (->> (concat inline via)
         (filter (fn [n] (contains? core-events (str n))))
         set
         vec)))

(defn- offenders []
  (->> (vec (fs/readdirSync ext-root #js {:withFileTypes true}))
       (filter (fn [e] (.isDirectory e)))
       (keep (fn [e]
               (let [dir (path/join ext-root (.-name e))
                     src (->> (cljs-files dir)
                              (map (fn [f] (fs/readFileSync f "utf8")))
                              (str/join "\n"))
                     hits (prefixed-core-events src)]
                 (when (seq hits)
                   (str (.-name e) " reaches the core event(s) "
                        (str/join ", " hits)
                        " through api.events, which prefixes them — use "
                        "api.on / api.emitGlobal")))))
       vec))

(describe "extensions reach core events on an unprefixed channel" (fn []

                                                                    (it "knows the core event names"
                                                                        (fn []
        ;; Guard the guard.
                                                                          (-> (expect (contains? core-events "turn_request")) (.toBe true))
                                                                          (-> (expect (contains? core-events "before_tool_call")) (.toBe true))))

                                                                    (it "no extension emits a core event through api.events"
                                                                        (fn []
                                                                          (-> (expect (str/join "; " (offenders))) (.toBe ""))))

                                                                    (it "detects the regression it was written for"
                                                                        (fn []
        ;; The literal shape that shipped.
                                                                          (-> (expect (prefixed-core-events
                                                                                       "(if-let [emit (some-> (.-events api) .-emit)] (emit \"turn_request\" #js {}))"))
                                                                              (.toEqual #js ["turn_request"]))
        ;; An inter-extension name is exactly what api.events is FOR.
                                                                          (-> (expect (count (prefixed-core-events
                                                                                              "((.-emit (.-events api)) \"my-own-signal\" #js {})")))
                                                                              (.toBe 0))
        ;; emitGlobal with a core name is the correct call and must not flag.
                                                                          (-> (expect (count (prefixed-core-events
                                                                                              "((.-emitGlobal api) \"turn_request\" #js {})")))
                                                                              (.toBe 0))))))
