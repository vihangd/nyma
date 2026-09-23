(ns agent.sessions.manager
  "JSONL tree session storage."
  (:require [agent.ui.think-tag-parser :refer [strip-think-tags]]
            ["node:fs" :as fs]
            [agent.debug :as d]
            [agent.sessions.partial :as partial]
            [agent.sessions.archive :as archive]))

(defn parse-lines
  "Parse JSONL text into entries, skipping lines that don't parse.

   A session file's last line is half-written whenever the previous run was
   killed mid-append — Ctrl-C during a stream, a crash, an OOM. `mapv
   JSON.parse` threw on it, so `-c` and `-r` died on exactly the sessions a
   user most wants back. `sessions/listing.cljs` has skipped bad lines since it
   was written, which is why the picker LISTED a session that then refused to
   open.

   Returns {:entries [...] :skipped n} so the caller can say what it dropped
   rather than losing turns quietly."
  [content]
  (reduce (fn [acc line]
            (try
              (update acc :entries conj (js/JSON.parse line))
              (catch :default _ (update acc :skipped inc))))
          {:entries [] :skipped 0}
          (filter seq (.split content "\n"))))

(defn- nanoid []
  (-> (js/Math.random) (.toString 36) (.slice 2 11)))

(defn new-session-path
  "A fresh session file under `dir`: `<ms>-<6 base36 chars>.jsonl`.

   The name used to be the bare millisecond clock, and five instances launched
   by one script in the same millisecond all appended to the same file — /tree
   in each showed the other processes' entries. Nothing parses the name as a
   number: listing sorts by mtime and hook payloads take it as an opaque id."
  [dir & [prefix]]
  (str dir "/" (or prefix "") (js/Date.now) "-" (.padEnd (.slice (nanoid) 0 6) 6 "0") ".jsonl"))

(defn- entry->core-message [entry]
  (select-keys entry [:role :content]))

(defn session->seed-messages
  "Map a session's build-context entries into LLM-context messages for resume
   (used by both startup --continue/--resume and the runtime /resume + /import
   commands). Rules:
     - user / assistant  → kept as-is
     - compaction / branch-summary → the summary REPLACES all prior context
       (it already summarizes everything before it), folded into a synthetic
       user message. This is what prevents re-expanding a compacted session
       back to its full pre-compaction length on resume.
     - tool_call / tool_result → dropped (they never live in `state :messages`
       and would break the provider message shape).
   Pure for testability."
  [entries]
  (reduce
   (fn [acc m]
     (let [role (:role m)]
       (cond
         (or (= role "user") (= role "assistant")) (conj acc m)
         (or (= role "compaction") (= role "branch-summary"))
         ;; Reset to the summary — discard everything it folded in.
         ;;
         ;; Deliberate, and covered by session_resume.test: the summarized span
         ;; must NOT reappear alongside its own summary. The cost is that the
         ;; KEPT span is dropped too, because the compaction entry is appended
         ;; at the leaf and nothing here can tell kept from summarized — see the
         ;; note in compaction.cljs.
         [{:role "user" :content (str "[Earlier conversation summary]\n"
                                      (:content m))}]
         :else acc)))
   []
   entries))

(defn- build-index
  "Build an in-memory index from entries: id → {:idx n, :parent-id pid, :role r}"
  [entries]
  (into {}
        (map-indexed
         (fn [idx entry]
           [(:id entry) {:idx idx :parent-id (:parent-id entry) :role (:role entry)}]))
        entries))

(defn- walk-branch
  "Walk from leaf-id to root using the index. Returns vector of array indices
   in root-to-leaf order."
  [index leaf-id]
  (loop [current leaf-id
         indices []]
    (if-let [entry (get index current)]
      (recur (:parent-id entry) (conj indices (:idx entry)))
      (vec (reverse indices)))))

(defn create-session-manager
  "Manages conversation tree stored as JSONL. Each entry has
   :id and :parent-id enabling in-place branching.

   opts (optional map):
     :sqlite-store — SQLite mirror store (from create-sqlite-store)
     :events       — event bus for branch switch events
     :session-file — session file identifier for SQLite"
  [initial-file-path & [opts]]
  (let [file-path     (atom initial-file-path)
        entries       (atom [])
        leaf-id       (atom nil)
        index         (atom {})  ;; id → {:idx, :parent-id, :role}
        session-name  (atom nil)
        sqlite-store  (:sqlite-store opts)
        events        (:events opts)
        session-file  (or (:session-file opts) initial-file-path)

        load-fn
        (fn []
          (let [fp @file-path]
            ;; read-text takes either shape: an old session may be sitting on
            ;; disk as <path>.zstd. Appending still requires a plain file, which
            ;; is why cli restores before opening one for write.
            (when-let [content (and fp (archive/read-text fp))]
              (let [{lines :entries skipped :skipped} (parse-lines content)]
                (when (pos? skipped)
                  ;; Once per load, naming the file: a resumed session that is
                  ;; quietly short a turn is worse than one that says so.
                  (d/warn "sessions"
                          (str "skipped " skipped " unparseable line"
                               (when (> skipped 1) "s") " in " fp)))
                (reset! entries lines)
                (reset! leaf-id (:id (last lines)))
                (reset! index (build-index lines))
                ;; Entries are CLJS maps here (listing.cljs reads the same
                ;; role off raw JS objects), so this cannot reuse
                ;; listing/explicit-name.
                (reset! session-name
                        (some->> lines
                                 (filter #(= "session-name" (:role %)))
                                 last
                                 :content))
              ;; Sync to SQLite if available
                (when sqlite-store
                  (doseq [entry lines]
                    ((:upsert-entry sqlite-store)
                     (assoc entry :session-file session-file))))))))

        build-context-fn
        (fn []
          ;; Use index for O(branch-depth) traversal instead of O(n) map construction
          (let [branch-indices (walk-branch @index @leaf-id)
                all-entries    @entries]
            (->> branch-indices
                 (map #(nth all-entries %))
                 (filter #(contains? #{"user" "assistant" "tool_call" "tool_result"
                                       "compaction" "branch-summary"}
                                     (:role %)))
                 (mapv entry->core-message))))

        append-fn
        (fn [entry-data]
          (let [id    (nanoid)
                entry (assoc entry-data :id id :parent-id @leaf-id
                             :timestamp (js/Date.now))]
            (let [new-idx (count @entries)]
              (swap! entries conj entry)
              (reset! leaf-id id)
              (swap! index assoc id {:idx new-idx :parent-id (:parent-id entry) :role (:role entry)}))
            (when-let [fp @file-path]
              (fs/appendFileSync fp (str (js/JSON.stringify (clj->js entry)) "\n")))
            ;; Mirror to SQLite
            (when sqlite-store
              ((:upsert-entry sqlite-store)
               (assoc entry :session-file session-file)))
            id))

        branch-fn
        (fn [entry-id]
          (let [old-leaf @leaf-id]
            ;; Emit branch switch event if events available
            (when events
              ((:emit events) "before_branch_switch"
                              {:old-leaf old-leaf :new-leaf entry-id}))
            (reset! leaf-id entry-id)
            old-leaf))

        get-tree-fn (fn [] @entries)
        leaf-id-fn  (fn [] @leaf-id)

        search-fn
        (fn [query]
          (if sqlite-store
            ((:search-content sqlite-store) query)
            ;; Fallback: linear scan in memory
            (->> @entries
                 (filter #(and (:content %) (.includes (str (:content %)) query)))
                 (take 50)
                 vec)))

        mgr {:load             load-fn
             :build-context    build-context-fn
             :append           append-fn
             :branch           branch-fn
             :get-tree         get-tree-fn
             :leaf-id          leaf-id-fn
             :search           search-fn
             ;; Session naming and entry labeling (pi-compat)
             ;; Also persisted as an entry: the atom alone died with the
             ;; process, so `/name` never survived a restart and
             ;; listing/explicit-name — the only reader of the name — had no
             ;; producer at all. build-context-fn whitelists conversation
             ;; roles, so this entry is inert for the model, compaction and
             ;; token counts.
             :set-session-name (fn [n]
                                 (reset! session-name n)
                                 (append-fn {:role "session-name" :content (str n)})
                                 n)
             :get-session-name (fn [] @session-name)
             :get-entries      (fn [] @entries)
             :get-branch       (fn [] (build-context-fn))
             ;; The file this session persists to — the key usage rows and
             ;; prompt history are grouped by.
             :session-file     (fn [] session-file)
             :get-leaf-id      (fn [] @leaf-id)
             ;; Runtime session switching (for /resume, /import)
             :get-file-path    (fn [] @file-path)
             :switch-file      (fn [new-path]
                                 (let [old-path @file-path]
                                   (when events
                                     ((:emit events) "session_before_switch"
                                                     {:old-path old-path :new-path new-path}))
                                   (reset! file-path new-path)
                                   (reset! entries [])
                                   (reset! index {})
                                   (reset! leaf-id nil)
                                   (reset! session-name nil)
                                   (load-fn)
                                   (when events
                                     ((:emit events) "session_switch"
                                                     {:old-path old-path :new-path new-path
                                                      :entry-count (count @entries)}))
                                   new-path))}]

    mgr))

(defn attach-session-persistence!
  "Mirror new user/assistant turns into the session JSONL as they're added to
   the store. No-op for ephemeral (nil file path) sessions. Returns nothing.
   Shared by cli (interactive/print) and modes.sdk (embedders + gateway).

   Also checkpoints the assistant's response WHILE it streams. Everything else
   here is already crash-safe — appends are synchronous, one per message — but
   the assistant message is only dispatched once the turn completes, so a
   crash mid-stream lost the whole response. The checkpoint goes to a sidecar
   and is dropped the moment the real entry reaches the JSONL."
  [agent session]
  (when (and session ((:get-file-path session)))
    (let [ckpt  (partial/create-checkpoint ((:get-file-path session)))
          acc   (atom "")
          events (:events agent)]

      ;; Stream deltas: accumulate and checkpoint. `message_update` is the
      ;; same event the UI renders from, so this cannot drift from what the
      ;; user saw on screen.
      (when events
        ((:on events) "message_update"
                      (fn [data]
                        ;; message_update carries the RAW AI SDK part, whose
                        ;; text-delta field is `text` (ai/index.d.ts:2818-2822).
                        ;; `textDelta` belongs to the OBJECT stream, so reading
                        ;; it accumulated "" and the sidecar saved nothing. The
                        ;; UI reads `.text` here too (interactive.cljs).
                        (swap! acc str (or (and data (.-text data))
                                           (and data (.-textDelta data))
                                           ""))
                        ((:note! ckpt) @acc)
                        nil))

        ;; Deliberately NOT hooked to "turn_end": that is emitted from the
        ;; SDK's :onStepFinish, so it fires once per STEP, not once per turn.
        ;; Clearing there would delete the sidecar in the middle of any
        ;; tool-using response — exactly when it is protecting something. The
        ;; turn boundary that matters is the next user message, below.

        ;; A turn that ended WITHOUT storing a response: write what was
        ;; streamed, rather than discarding it.
        ;;
        ;; The assistant message is dispatched at exactly one place in the loop
        ;; (the final-text path). A provider error or a user interrupt throws
        ;; out of the stream and is swallowed by the loop's catch, and
        ;; retry-exhaustion emits agent_end carrying the accumulated text and
        ;; drops it — so in both cases a response the user WATCHED ARRIVE was
        ;; never recorded. Measured on real sessions before this: seven user
        ;; messages, two assistant messages.
        ;;
        ;; Safe because of an ordering invariant: on a normal turn the
        ;; assistant :message-added fires BEFORE turn_finalize and the
        ;; subscriber below resets `acc`. So a non-empty `acc` here means
        ;; nothing was stored — precisely the interrupted case. That ordering
        ;; is load-bearing and has its own test.
        ((:on events) "turn_finalize"
                      (fn [_]
                        (let [text @acc]
                          (when (seq (.trim (str text)))
                            ((:append session) {:role "assistant"
                                                :content (partial/mark-cutoff text)})
                            (reset! acc "")
                            ((:commit! ckpt))))
                        nil)))

      ((:subscribe (:store agent))
       (fn [event-type state]
         ;; Skip replays (/resume, /import seed via :message-added too) — those
         ;; messages are already on disk; re-appending would double the file.
         (when (and (= event-type :message-added)
                    (not (:replaying-session? state)))
           (let [msg  (last (:messages state))
                 role (:role msg)]
             (when (contains? #{"user" "assistant"} role)
               ;; Reasoning does not belong in the persisted transcript. In a
               ;; real session 73% of assistant text (253 KB of 346 KB) was
               ;; <think> blocks, and 52 of 61 messages opened with one — all of
               ;; it stored and replayed into context. Stripping is
               ;; deterministic, loses nothing of the answer, and shrinks what
               ;; compaction later has to summarize.
               ((:append session)
                (cond-> (select-keys msg [:role :content])
                  (= role "assistant")
                  (update :content #(strip-think-tags (str %)))))
               (cond
                 ;; The real entry is on disk now, so the checkpoint has
                 ;; nothing left to protect.
                 (= role "assistant")
                 (do (reset! acc "") ((:commit! ckpt)))

                 ;; A new user message starts a new turn. Whatever the last
                 ;; one left behind is either already committed or belongs to
                 ;; a turn that never finished — either way it must not be
                 ;; prefixed onto the response about to stream, and it must
                 ;; not be resurrected by a later crash.
                 (= role "user")
                 (do (reset! acc "") ((:abandon! ckpt))))))))))))
