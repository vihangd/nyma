(ns agent.ui.bracketed-paste
  "Bracketed-paste marker support.

   Terminals that speak bracketed-paste wrap any paste in the escape
   sequences ESC [200~ ... ESC [201~ so a program can distinguish
   user-typed input from pasted input. This namespace:

   * Enables/disables the bracketed-paste mode on stdout
   * Provides a small state machine that scans raw stdin chunks for the
     paste framing and returns the captured content
   * Provides a helper to expand '[paste #N +X lines]' markers back into
     full paste content at submit time

   The state machine is pure and exposed for testing. The enable/disable
   functions write directly to process.stdout.")

(def ^:private PASTE-START "\u001b[200~")
(def ^:private PASTE-END   "\u001b[201~")

;;; ─── Terminal mode toggles ──────────────────────────────

(defn enable-bracketed-paste
  "Tell the terminal to wrap pasted text in ESC[200~ ... ESC[201~.
   No-op when stdout is not a TTY."
  []
  (let [out (.-stdout js/process)]
    (when (and out (.-isTTY out))
      (.write out "\u001b[?2004h"))))

(defn disable-bracketed-paste
  "Disable bracketed-paste mode. Safe to call multiple times."
  []
  (let [out (.-stdout js/process)]
    (when (and out (.-isTTY out))
      (.write out "\u001b[?2004l"))))

;;; ─── Marker formatting ──────────────────────────────────

(defn- count-lines [s]
  (if (or (nil? s) (= s ""))
    0
    (inc (- (.-length (.split s "\n")) 1))))

(defn format-marker
  "Format a paste marker for the editor. id is a positive int, content is
   the raw pasted text."
  [id content]
  (str "[paste #" id " +" (count-lines content) " lines]"))

;;; ─── Paste handler state machine ────────────────────────

(defn create-paste-handler
  "Build a fresh paste handler.

   Returns {:pastes atom, :process fn, :reset fn}.

   :pastes is an atom holding {id → content}.

   :process takes a raw data chunk (string) and returns:
     {:handled bool       ;; whether any part of the chunk was paste framing
      :marker  string?    ;; the marker to insert, when a paste completed
      :id      int?       ;; the paste id (same in :pastes map)
      :content string?    ;; the captured paste content
      :passthrough string?} ;; non-paste characters the caller should still
                            ;; treat as normal input

   Can receive chunks where the paste framing is split across multiple
   calls. A buffer persists until the terminating ESC[201~ arrives."
  []
  (let [state  (atom {:active?   false
                      :buffer    ""
                      :next-id   1})
        pastes (atom {})]
    {:pastes pastes
     :reset (fn [] (reset! state {:active? false :buffer "" :next-id 1})
                   (reset! pastes {}))
     :process
     (fn [data]
       (let [data (str data)
             s    @state]
         (cond
           ;; Not currently in a paste — look for a start marker.
           (not (:active? s))
           (let [idx (.indexOf data PASTE-START)]
             (if (neg? idx)
               ;; No start marker at all — passthrough unchanged.
               {:handled false :passthrough data}
               ;; Found start marker: split into pre (normal) + post (paste-body).
               (let [pre  (.slice data 0 idx)
                     post (.slice data (+ idx (.-length PASTE-START)))
                     end  (.indexOf post PASTE-END)]
                 (if (neg? end)
                   ;; End marker not in this chunk — buffer the rest.
                   (do (swap! state assoc :active? true :buffer post)
                       {:handled true :passthrough pre})
                   ;; Full paste contained in this chunk.
                   (let [content (.slice post 0 end)
                         tail    (.slice post (+ end (.-length PASTE-END)))
                         id      (:next-id s)
                         marker  (format-marker id content)]
                     (swap! pastes assoc id content)
                     (swap! state assoc :next-id (inc id))
                     {:handled     true
                      :id          id
                      :content     content
                      :marker      marker
                      :passthrough (str pre tail)})))))

           ;; Inside a paste — append until we see the end marker.
           :else
           (let [buf  (:buffer s)
                 end  (.indexOf data PASTE-END)]
             (if (neg? end)
               (do (swap! state update :buffer str data)
                   {:handled true :passthrough ""})
               (let [content (str buf (.slice data 0 end))
                     tail    (.slice data (+ end (.-length PASTE-END)))
                     id      (:next-id s)
                     marker  (format-marker id content)]
                 (swap! pastes assoc id content)
                 (swap! state assoc
                        :active? false :buffer ""
                        :next-id (inc id))
                 {:handled     true
                  :id          id
                  :content     content
                  :marker      marker
                  :passthrough tail})))))) }))

;;; ─── Marker expansion (on submit) ───────────────────────

(defn- marker-re []
  ;; Global regex — must be constructed fresh because .lastIndex is stateful.
  (js/RegExp. "\\[paste #(\\d+) \\+\\d+ lines\\]" "g"))

(defn expand-paste-markers
  "Replace every '[paste #N +X lines]' marker in text with the content
   stored under id N in pastes-map. Unknown ids are left intact."
  [text pastes-map]
  (if (or (nil? text) (= text ""))
    (or text "")
    (.replace text (marker-re)
              (fn [match id-str]
                (let [id (js/parseInt id-str 10)]
                  (if-let [content (get pastes-map id)]
                    content
                    match))))))
