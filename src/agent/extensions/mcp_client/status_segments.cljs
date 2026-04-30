(ns agent.extensions.mcp-client.status-segments
  "Status-line segments for MCP connection state.

   Two segments are exposed; both self-hide when no MCP servers are
   configured so non-MCP users see zero change:

     mcp.summary  (left)   — \"MCP 2/3\" — connected / total.
                              Color reflects worst state (green if
                              all running, yellow if any starting/
                              restarting, red if any errored).

     mcp.detail   (right)  — \"lean-ctx ✓ everything ⚠ memory ✗\".
                              Per-server icons; opt-in via settings
                              `.nyma/settings.json#mcp.show-detail-segment`."
  (:require [clojure.string :as str]
            [agent.extensions.mcp-client.client :as client]
            [agent.extensions.mcp-client.manager :as mgr]))

(def ^:private color-running  "#9ece6a")  ;; green
(def ^:private color-starting "#e0af68")  ;; yellow
(def ^:private color-error    "#f7768e")  ;; red
(def ^:private color-muted    "#565f89")

(defn- visible [content color] {:content content :color color :visible? true})
(defn- hidden  []              {:visible? false})

(defn- worst-color
  "Pick the most alarming color across the manager's clients.
   error > starting/restarting > running."
  [{:keys [error starting running]}]
  (cond
    (pos? error)    color-error
    (pos? starting) color-starting
    (pos? running)  color-running
    :else           color-muted))

(defn- summary-segment-fn [manager-ref]
  (fn [_ctx]
    (let [m @manager-ref]
      (if (nil? m)
        (hidden)
        (let [s (mgr/summary m)]
          (if (zero? (:total s))
            (hidden)
            (visible (str "MCP " (:running s) "/" (:total s))
                     (worst-color s))))))))

(defn- icon-for-state [state]
  (case state
    :running        "✓"
    :starting       "⚠"
    :restarting     "⚠"
    :stopped-error  "✗"
    :stopped        "·"
    "?"))

(defn- detail-segment-fn [manager-ref show-detail?-ref]
  (fn [_ctx]
    (let [m @manager-ref]
      (if (or (nil? m)
              (not @show-detail?-ref)
              (zero? (count (mgr/server-names m))))
        (hidden)
        (let [parts (mapv (fn [{:keys [name client]}]
                            (str name " " (icon-for-state (client/state client))))
                          (mgr/all-clients m))]
          (visible (str/join "  " parts)
                   color-muted))))))

(defn register-all!
  "Register the two MCP segments. The render closures capture
   `manager-ref` and `show-detail?-ref` (atoms) so segment output
   updates automatically as the manager's state changes — no
   imperative refresh.

   Returns a no-arg cleanup thunk."
  [api manager-ref show-detail?-ref]
  (let [reg (.-registerStatusSegment api)]
    (when reg
      (reg "mcp.summary"
           #js {:category   "mcp"
                :autoAppend true
                :position   "left"
                :render     (summary-segment-fn manager-ref)})
      (reg "mcp.detail"
           #js {:category   "mcp"
                :autoAppend true
                :position   "right"
                :render     (detail-segment-fn manager-ref show-detail?-ref)}))
    (fn []
      (when-let [unreg (.-unregisterStatusSegment api)]
        (try (unreg "mcp.summary") (catch :default _ nil))
        (try (unreg "mcp.detail") (catch :default _ nil))))))
