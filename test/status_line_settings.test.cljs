(ns status-line-settings.test
  "Fifteen built-in segments were implemented, registered, and unreachable: the
   bar draws only `:auto-append?` segments, four were, and the setting that would
   select the rest — `settings.status-line` — was declared INERT in the settings
   manager. So the status line was not configurable at all: no way to reorder,
   enable or disable anything.

   `resolve-auto-append` is the way out. It is pure so the selection rules are
   testable without a terminal."
  (:require ["bun:test" :refer [describe it expect afterEach]]
            [agent.ui.status-line-segments :as segs]))

(afterEach (fn [] (segs/install-builtins!) nil))

(def ^:private known (segs/builtin-ids))
(defn- R [cfg] (segs/resolve-auto-append cfg known))

;;; ─── selection ─────────────────────────────────────────────────

(describe "resolve-auto-append" (fn []

  (it "unset reproduces exactly today's four segments"
      (fn []
        ;; the compatibility guarantee: nobody's status line changes by upgrading
        (let [r (R nil)]
          (-> (expect (:spec r)) (.toEqual (clj->js segs/default-auto-append)))
          (-> (expect (count (:unknown r))) (.toBe 0)))))

  (it "an empty map is also unset, not an empty line"
      (fn []
        (-> (expect (:spec (R {}))) (.toEqual (clj->js segs/default-auto-append)))))

  (it "naming segments unlocks ones that could never render before"
      (fn []
        ;; git, path and cache-read are three of the fifteen
        (let [r (R {:left-segments ["path" "git"] :right-segments ["cache-read"]})]
          (-> (expect (get (:spec r) "git")) (.toBe :left))
          (-> (expect (get (:spec r) "path")) (.toBe :left))
          (-> (expect (get (:spec r) "cache-read")) (.toBe :right)))))

  (it "naming either side replaces the defaults wholesale"
      (fn []
        ;; a half-specified line would be impossible to reason about
        (let [r (R {:left-segments ["git"]})]
          (-> (expect (contains? (:spec r) "activity")) (.toBe false))
          (-> (expect (contains? (:spec r) "cost")) (.toBe false)))))

  (it "the vector order is the render order"
      (fn []
        ;; the whole point: ordering used to be sort-by :id and nothing else, so
        ;; context-pct could never sit right of cost
        (let [r (R {:right-segments ["context-pct" "cost" "time-spent"]})]
          (-> (expect (get (:order r) "context-pct")) (.toBe 0))
          (-> (expect (get (:order r) "cost")) (.toBe 1))
          (-> (expect (get (:order r) "time-spent")) (.toBe 2)))))

  (it "a typo is reported, not thrown, and the rest still renders"
      (fn []
        (let [r (R {:left-segments ["git" "gti" "path"]})]
          (-> (expect (:unknown r)) (.toEqual (clj->js ["gti"])))
          (-> (expect (contains? (:spec r) "git")) (.toBe true))
          (-> (expect (contains? (:spec r) "path")) (.toBe true)))))

  (it "an extension segment id is not a built-in and is reported as unknown"
      (fn []
        ;; extension segments manage their own autoAppend; naming one here is a
        ;; mistake worth a warning rather than a silent no-op
        (-> (expect (:unknown (R {:left-segments ["todos.progress"]})))
            (.toEqual (clj->js ["todos.progress"])))))))

;;; ─── applying it to the registry ───────────────────────────────

(defn- auto-append-now []
  (->> (vals (segs/segment-registry))
       (filter :auto-append?)
       (map :id)
       set))

(describe "apply-settings!" (fn []

  (it "adds auto-append to a selected segment"
      (fn []
        (segs/apply-settings! {:left-segments ["git"]})
        (-> (expect (contains? (auto-append-now) "git")) (.toBe true))))

  (it "REMOVES auto-append from a dropped one"
      (fn []
        ;; re-installing has to clear the flag, not just set it — otherwise a
        ;; second apply accumulates every segment ever selected
        (segs/apply-settings! {:left-segments ["git"]})
        (-> (expect (contains? (auto-append-now) "cost")) (.toBe false))
        (segs/apply-settings! nil)
        (-> (expect (contains? (auto-append-now) "cost")) (.toBe true))
        (-> (expect (contains? (auto-append-now) "git")) (.toBe false))))

  (it "carries the position a user asked for"
      (fn []
        (segs/apply-settings! {:right-segments ["git"]})
        (-> (expect (:position (segs/get-segment "git"))) (.toBe :right))))

  (it "carries the order"
      (fn []
        (segs/apply-settings! {:right-segments ["context-pct" "cost"]})
        (-> (expect (:order (segs/get-segment "context-pct"))) (.toBe 0))
        (-> (expect (:order (segs/get-segment "cost"))) (.toBe 1))))

  (it "returns the unknown ids for a single startup warning"
      (fn []
        ;; warned once at startup, never per frame — this runs at render cadence
        (-> (expect (segs/apply-settings! {:left-segments ["nope"]}))
            (.toEqual (clj->js ["nope"])))))

  (it "leaves extension segments alone"
      (fn []
        (segs/register-segment "ext.thing"
                               {:category :extension :auto-append? true :position :left
                                :render (fn [_] {:visible? true :content "x"})})
        (segs/apply-settings! {:left-segments ["git"]})
        (-> (expect (:auto-append? (segs/get-segment "ext.thing"))) (.toBe true))
        (segs/unregister-segment "ext.thing")))))
