(ns agent.extensions.lsp-suite.lsp-diagnostics
  "Per-file diagnostic registry with content-hash deduplication
   and volume caps (10/file, 30/total) matching Claude Code's limits.")

(def ^:private max-per-file 10)
(def ^:private max-total 30)
(def ^:private lru-cap 500)

;; ── State ─────────────────────────────────────────────────────────

(defonce ^:private pending      (atom []))   ; [{:uri :diag :path}] for next-turn injection
(defonce ^:private current-diags (atom {}))  ; uri -> [{:diag :path}] live view for get_diagnostics
(defonce ^:private seen-lru     (atom []))   ; ordered content-hash strings for dedup

;; ── Deduplication ─────────────────────────────────────────────────

(defn- diag-hash [uri diag]
  (let [line (when (.. diag -range -start) (.. diag -range -start -line))
        msg  (.-message diag)]
    (str uri "|" line "|" msg)))

(defn- seen? [h]
  (boolean (some #(= % h) @seen-lru)))

(defn- mark-seen! [h]
  (swap! seen-lru
         (fn [lru]
           (let [without (filterv #(not= % h) lru)
                 updated (conj without h)]
             (if (> (count updated) lru-cap)
               (vec (drop (- (count updated) lru-cap) updated))
               updated)))))

;; ── Public API ────────────────────────────────────────────────────

(defn register!
  "Store incoming diagnostics. Updates the live view (current-diags) and
   the pending queue (for next before_agent_start injection).
   Call with uri, JS diagnostics array, and resolved file path string."
  [uri diags-array path]
  ;; Always update live view — replace with latest batch
  (if (or (nil? diags-array) (zero? (.-length diags-array)))
    (swap! current-diags dissoc uri)
    (let [entries (atom [])]
      (.forEach diags-array
                (fn [d]
                  (when (< (count @entries) max-per-file)
                    (swap! entries conj {:diag d :path path}))))
      (swap! current-diags assoc uri @entries)))
  ;; Append new (non-duplicate) items to pending queue
  (when (and diags-array (pos? (.-length diags-array)))
    (let [by-file (atom [])]
      (.forEach diags-array
                (fn [d]
                  (when (< (count @by-file) max-per-file)
                    (let [h (diag-hash uri d)]
                      (when-not (seen? h)
                        (mark-seen! h)
                        (swap! by-file conj d))))))
      (when (pos? (count @by-file))
        (swap! pending into
               (mapv (fn [d] {:uri uri :diag d :path path}) @by-file))))))

(defn register-pending! [uri diags-array path]
  (register! uri diags-array path))

(defn drain-pending!
  "Drain the pending queue, capping at max-total.
   Returns a vector of {:uri :diag :path} maps."
  []
  (let [result (vec (take max-total @pending))]
    (reset! pending [])
    result))

(defn get-all-current
  "Return all current diagnostics as a flat JS array for the get_diagnostics tool.
   Does NOT drain the pending queue."
  []
  (let [arr (atom #js [])]
    (doseq [[uri entries] @current-diags]
      (doseq [{:keys [diag]} entries]
        (.push @arr (doto (js/Object.assign #js {} diag) (aset "uri" uri)))))
    @arr))

(defn pending-count [] (count @pending))

(defn severity-counts
  "`{:errors n :warnings n}` over the LIVE view, for the status line.

   Reads `current-diags`, NOT `pending`: `drain-pending!` empties that queue
   inside `before_agent_start`, so a segment reading it would race the prompt
   injector and flicker to zero every turn.

   Partial by construction — servers spawn lazily on extension match, so this
   covers files an already-running server has opened, and the store is capped at
   10 per file / 30 per drain. A count, not an audit."
  []
  (reduce (fn [acc [_uri entries]]
            (reduce (fn [a {:keys [diag]}]
                      (case (and diag (.-severity diag))
                        1 (update a :errors inc)
                        2 (update a :warnings inc)
                        a))
                    acc entries))
          {:errors 0 :warnings 0}
          @current-diags))

(defn clear! []
  (reset! pending [])
  (reset! current-diags {})
  (reset! seen-lru []))
