(ns status-line-render.test
  "Render test for the StatusLine component.

   Actually mounts StatusLine against ink-testing-library so import/runtime
   errors are caught at test time. The existing `status_line.test.cljs`
   only exercises the segments / presets / separators sub-modules — it
   never imports the top-level component, which is why the previous
   `js->clj` bug (Squint does not provide it) slipped through unit tests
   and only showed up when the UI actually mounted."
  {:squint/extension "jsx"}
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["ink-testing-library" :refer [render cleanup]]
            ["./agent/ui/status_line.jsx" :refer [StatusLine]]))

(defn- wait-ticks
  "Let queued microtasks + a setTimeout tick run so that useEffect
   promises (read-git-status etc.) resolve and re-render the component.
   Without this, the initial render is the only code path exercised
   and bugs inside async callbacks slip through."
  []
  (js/Promise. (fn [resolve] (js/setTimeout resolve 250))))

(defn- with-rejection-capture
  "Runs body-fn inside a wrapper that captures any unhandledRejection
   (e.g. a ReferenceError thrown from inside a .then callback) and
   resolves to [result rejection]. Used to surface async errors that
   would otherwise be silently swallowed by React + promise chains."
  [body-fn]
  (js/Promise.
    (fn [resolve _reject]
      (let [rejection (atom nil)
            handler   (fn [err]
                        (when (nil? @rejection) (reset! rejection err)))]
        (.on js/process "unhandledRejection" handler)
        (.on js/process "uncaughtException" handler)
        (let [result (body-fn)]
          (-> (wait-ticks)
              (.then (fn [_]
                       (.off js/process "unhandledRejection" handler)
                       (.off js/process "uncaughtException" handler)
                       (resolve #js [result @rejection])))))))))

(def test-theme
  {:colors {:primary         "#7aa2f7"
            :secondary       "#9ece6a"
            :error           "#f7768e"
            :warning         "#e0af68"
            :success         "#9ece6a"
            :muted           "#565f89"
            :border          "#3b4261"
            :context-ok      "#9ece6a"
            :context-warning "#e0af68"
            :context-purple  "#bb9af7"
            :context-error   "#f7768e"}})

(defn- noop-events []
  {:on  (fn [_evt _handler])
   :off (fn [_evt _handler])})

(defn- test-agent []
  {:state  (atom {:total-cost        0
                  :context-used      0
                  :context-window    200000
                  :active-executions []})
   :config {:model #js {:modelId "test-model" :contextLimit 200000}}
   :events (noop-events)})

(afterEach (fn [] (cleanup)))

(describe "StatusLine" (fn []
  (it "mounts without async errors with minimal props"
    (fn []
      (-> (with-rejection-capture
            (fn []
              (render #jsx [StatusLine {:agent     (test-agent)
                                        :theme     test-theme
                                        :settings  {}
                                        :max-width 80}])))
          (.then (fn [arr]
                   (let [rejection (aget arr 1)]
                     (-> (expect rejection) (.toBeNull))))))))

  (it "mounts without async errors with a preset override"
    (fn []
      (-> (with-rejection-capture
            (fn []
              (render #jsx [StatusLine {:agent     (test-agent)
                                        :theme     test-theme
                                        :settings  {:status-line {:preset "minimal"}}
                                        :max-width 80}])))
          (.then (fn [arr]
                   (let [rejection (aget arr 1)]
                     (-> (expect rejection) (.toBeNull))))))))

  (it "mounts without async errors with custom segment ids"
    (fn []
      (-> (with-rejection-capture
            (fn []
              (render #jsx [StatusLine {:agent     (test-agent)
                                        :theme     test-theme
                                        :settings  {:status-line {:left-segments  ["model"]
                                                                  :right-segments ["tokens"]}}
                                        :max-width 120}])))
          (.then (fn [arr]
                   (let [rejection (aget arr 1)]
                     (-> (expect rejection) (.toBeNull))))))))))
