(ns agent.extensions.spec-driven.status-segment
  "Status-line segment for the active spec.

   Added because a real run went silent: after `/spec import … --run` the
   decomposition is queued as a follow-up, so it does not begin until the next
   turn ends — and while it runs there was nothing on screen saying a spec was
   active, that a decomposition was pending, or that the phase loop was armed.
   The user typed \"go\" several times assuming nothing had happened.

   `ui.setStatus` would be the obvious home for this, but the interactive TUI
   never assigns it (it wires notify/select/input/custom/setWidget only), so it
   is a nil placeholder. `registerStatusSegment` is the mechanism that actually
   renders — it is what agent_shell's ACP segments use.

   Pure render functions taking the already-gathered state, so the whole thing
   is testable without a TUI or a spec on disk."
  (:require [clojure.string :as str]))

(defn- visible [content color] {:content content :color color :visible? true})
(defn- hidden [] {:visible? false})

(defn render-spec
  "The segment body from a plain state map, or {:visible? false}.

   `state` is {:spec :phase :role :progress :pending? :armed?} where
   :progress is phases/progress output. Nothing on screen unless a spec is
   active — a user who never touches /spec sees no change at all."
  [{:keys [spec phase role progress pending? armed?]} theme]
  (if-not (seq (str (or spec "")))
    (hidden)
    (let [total   (or (:total progress) 0)
          checked (or (:checked progress) 0)
          bits    (cond-> [(str "⏵ " spec)]
                    ;; "decomposing" is the state that was invisible: the spec
                    ;; exists but its tasks are still the scaffold template.
                    pending?          (conj "decomposing…")
                    (seq (str phase)) (conj (str phase))
                    (pos? total)      (conj (str checked "/" total))
                    (and armed? (seq (str role))) (conj (str role)))]
      (visible (str/join " · " bits)
               (get-in theme [:colors :secondary] "#9ece6a")))))

(def segments
  {"spec.active" {:render-with render-spec :position "left"}})

(defn register!
  "Register the segment. `state-fn` returns the map `render-spec` wants, so
   this ns stays free of fs and api access. Returns a deactivator."
  [api state-fn]
  (if-let [reg (.-registerStatusSegment api)]
    (do
      (reg "spec.active"
           #js {:category   "spec-driven"
                :autoAppend true
                :position   "left"
                :render     (fn [ctx]
                              (render-spec (state-fn)
                                           (js->clj (or (.-theme ctx) #js {})
                                                    :keywordize-keys true)))})
      (fn [] (when-let [unreg (.-unregisterStatusSegment api)]
               (unreg "spec.active"))))
    (fn [] nil)))
