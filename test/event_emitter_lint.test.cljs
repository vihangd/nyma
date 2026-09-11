(ns event-emitter-lint.test
  "Every name in `all-event-types` must have an emitter somewhere in src.

   A declared event with no producer is a promise the codebase cannot keep:
   `editor_change` was subscribed by token_suite's live token-count widget —
   debounced, wired to setWidget, with passing tests — and never fired once,
   because nothing emitted it. Seven more (overlay_open/dismiss,
   autocomplete_open/close/select, keybinding_activated, acp_permission) were
   pure phantoms: no emitter, no listener, not even pi-compat names.

   The list is also what rpc mode forwards over the wire, so a phantom is
   advertised to every RPC client as a channel that can never carry traffic.

   Emitters come in two shapes. Most are literal — `(emit \"turn_end\" …)`.
   The stream events are emitted through a lookup in `loop.cljs`'s
   `stream-event-types` map, so their names never appear next to an emit call.
   That map is READ here rather than hand-listed: an allow-list maintained by
   hand is the same never-validated static data this lint exists to catch."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            ["./agent/events.mjs" :as events]
            ["./agent/loop.mjs" :as agent-loop]
            ["./agent/dev/event_map.mjs" :as event-map]))

(def ^:private src-root (path/resolve (js/process.cwd) "src"))

(defn- cljs-files [dir]
  (reduce
   (fn [acc e]
     (let [p (path/join dir (.-name e))]
       (cond
         (.isDirectory e)               (into acc (cljs-files p))
         (.endsWith (.-name e) ".cljs") (conj acc p)
         :else                          acc)))
   []
   (vec (fs/readdirSync dir #js {:withFileTypes true}))))

(def ^:private all-src
  (->> (cljs-files src-root)
       (map (fn [f] (fs/readFileSync f "utf8")))
       (str/join "\n")))

(defn literal-emits
  "Event names passed to any emit call in `source`. Covers the several shapes
   the codebase uses: ((:emit events) \"x\"), ((:emit-async events) \"x\"),
   (.emitGlobal api \"x\"), (emit \"x\"), (emit-fn \"x\")."
  [source]
  (set (map second (re-seq #"(?:emit|emit-async|emit-collect|emitGlobal|emit-fn|emit!)[^\n\"]{0,40}\"([a-zA-Z][a-zA-Z0-9_]*)\""
                           (str source)))))

(def ^:private dynamic-emits
  "Names emitted by mapping a provider stream chunk type through
   loop.cljs's `stream-event-types`. Read from the map itself."
  ;; squint has no js->clj; the map compiles to a plain JS object.
  (set (map str (vec (js/Object.values agent-loop/stream-event-types)))))

(defn unemitted
  "Declared event names with no emitter. Pure; `emitted` is the union of both
   emitter shapes, so the check can be exercised on a fixture."
  [declared emitted]
  (vec (sort (remove (fn [n] (contains? emitted (str n))) (map str declared)))))

(describe "every declared event has an emitter"
          (fn []
            (it "knows how to find emitters at all"
                (fn []
                  ;; Guard the guard: a lint that matches nothing passes forever.
                  (let [found (literal-emits all-src)]
                    (-> (expect (contains? found "turn_finalize")) (.toBe true))
                    (-> (expect (contains? found "input_submit")) (.toBe true)))
                  ;; And the dynamic set really came out of loop.cljs.
                  (-> (expect (contains? dynamic-emits "message_update")) (.toBe true))))

            (it "no event in core-event-types is unemitted"
                (fn []
                  (let [emitted (into (literal-emits all-src) dynamic-emits)]
                    (-> (expect (str/join ", " (unemitted events/core-event-types emitted)))
                        (.toBe "")))))

            (it "the pi-compat names stay out of the core list"
                (fn []
                  ;; They are inert BY DESIGN — declared so a pi extension can
                  ;; subscribe. If one gains a producer it belongs in core, and
                  ;; this check is what says so.
                  (let [core    (set (map str (vec events/core-event-types)))
                        emitted (into (literal-emits all-src) dynamic-emits)]
                    (doseq [n (vec events/pi-compat-event-types)]
                      (-> (expect (contains? core (str n))) (.toBe false))
                      (-> (expect (contains? emitted (str n))) (.toBe false))))))

            (it "detects the regression it was written for"
                (fn []
                  ;; editor_change, with its emit line deleted.
                  (-> (expect (unemitted ["editor_change" "turn_end"] #{"turn_end"}))
                      (.toEqual #js ["editor_change"]))
                  ;; A stream-mapped name is emitted even though no emit call
                  ;; names it, so it must not be flagged.
                  (-> (expect (count (unemitted ["message_update"] dynamic-emits)))
                      (.toBe 0))))))

;;; ─── The generated map ──────────────────────────────────────
;;; deepseek-harness ships docs/event-producer-consumer.md as a generated
;;; matrix: every event's dispatchers AND listeners, with a bare `-` where a
;;; column is empty. It tolerates an unheard event; it does not tolerate not
;;; knowing. This test only guards against drift — nothing here fails because a
;;; column is empty.

(describe "docs/event-map.md"
          (fn []
            (it "matches what the source scan produces"
                (fn []
                  (let [committed (try (fs/readFileSync "docs/event-map.md" "utf8")
                                       (catch :default _ ""))]
                    (-> (expect (str committed))
                        (.toBe (str (event-map/render-event-map)))))))

            (it "reports both directions, and names a registry nothing fills"
                (fn []
                  ;; Guard the guard: a generator that silently emitted an empty
                  ;; table would pass the drift check above forever.
                  (let [doc (str (event-map/render-event-map))]
                    (-> (expect doc) (.toContain "| Event | Emitted in | Listened in |"))
                    (-> (expect doc) (.toContain "turn_finalize"))
                    ;; A declared API with no call site still gets a row.
                    (-> (expect (contains? (event-map/declared-registries) "registerModelInfo"))
                        (.toBe true)))))))
