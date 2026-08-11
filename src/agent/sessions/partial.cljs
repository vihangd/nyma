(ns agent.sessions.partial
  "Keep the in-flight assistant response on disk while it streams.

   Everything else in a session already survives a hard kill: appends are
   synchronous `appendFileSync`, one per message. The gap is the assistant's
   own text, which is only dispatched to the store when the turn COMPLETES
   (`loop.cljs`, the final-text path). Crash mid-stream — a render throw, a
   kill -9, a laptop lid — and the entire response is gone, however long it
   had been running.

   The partial text goes to a sidecar next to the session file rather than
   into the JSONL. The JSONL is an append-only tree whose entries link by
   `:parent-id`, and `build-context` walks leaf→root; writing superseded
   partial entries into it would corrupt the chain every reader depends on. A
   sidecar needs no tree surgery, and every existing reader ignores it.

   The sidecar is transient by design: it exists only between the first delta
   of a turn and that turn being committed to the JSONL. A sidecar found at
   load time therefore means exactly one thing — the last session died
   mid-response."
  (:require ["node:fs" :as fs]))

(def ^:private suffix ".partial")

(defn sidecar-path
  "Sidecar for `session-path`, or nil for an ephemeral session."
  [session-path]
  (when (seq (str (or session-path "")))
    (str session-path suffix)))

(def ^:private flush-interval-ms
  "A response streams hundreds of deltas; writing on each one would turn one
   turn into hundreds of synchronous disk writes for content that is about to
   be superseded anyway. Losing at most this much of a partial response is a
   fair trade against making every turn slower — the alternative today is
   losing all of it."
  400)

(defn create-checkpoint
  "Checkpointer for one session file. Returns
   {:note! :commit! :abandon! :flush!}.

     :note!     (text) — record the response so far; writes at most once per
                         flush interval, plus whatever the caller flushes.
     :flush!    ()     — write immediately if anything is pending.
     :commit!   ()     — the turn reached the JSONL; drop the sidecar.
     :abandon!  ()     — same, for a turn that ended without being stored.

   `now-fn` and the fs calls are injectable so the policy is testable without
   a clock or a filesystem."
  [session-path & [{:keys [now-fn write-fn remove-fn]}]]
  (let [path      (sidecar-path session-path)
        now-fn    (or now-fn (fn [] (js/Date.now)))
        write-fn  (or write-fn (fn [p s] (fs/writeFileSync p s "utf8")))
        remove-fn (or remove-fn (fn [p] (try (fs/unlinkSync p) (catch :default _ nil))))
        pending   (atom nil)
        last-at   (atom 0)

        write!
        (fn [text]
          ;; Never let a checkpoint failure take down the turn it is trying to
          ;; protect — a full disk must not cost the user their response.
          (try (write-fn path text) (catch :default _ nil))
          (reset! pending nil)
          (reset! last-at (now-fn)))]

    {:note!
     (fn [text]
       (when (and path (seq (str (or text ""))))
         (reset! pending text)
         (when (>= (- (now-fn) @last-at) flush-interval-ms)
           (write! text))))

     :flush!
     (fn [] (when (and path @pending) (write! @pending)))

     :commit!
     (fn []
       (when path
         (reset! pending nil)
         (remove-fn path)))

     :abandon!
     (fn []
       (when path
         (reset! pending nil)
         (remove-fn path)))}))

(defn read-partial
  "The partial response left by a session that died mid-stream, or nil.

   nil for: no sidecar, an unreadable one, or one holding only whitespace —
   an empty sidecar is noise, not a lost turn."
  [session-path & [{:keys [read-fn exists-fn]}]]
  (when-let [path (sidecar-path session-path)]
    (let [exists-fn (or exists-fn (fn [p] (fs/existsSync p)))
          read-fn   (or read-fn (fn [p] (str (fs/readFileSync p "utf8"))))]
      (when (exists-fn path)
        (try
          (let [text (read-fn path)]
            (when (seq (.trim (str (or text "")))) text))
          (catch :default _ nil))))))

(defn clear-partial!
  "Drop the sidecar once its contents have been folded into the conversation.

   Without this a resume that folds the partial in and then exits without a
   turn would leave the sidecar behind and fold the same text again next time.
   Committing a real assistant turn also clears it, so this only matters for a
   session that is resumed and then abandoned."
  [session-path & [{:keys [remove-fn]}]]
  (when-let [path (sidecar-path session-path)]
    (let [remove-fn (or remove-fn (fn [p] (try (fs/unlinkSync p) (catch :default _ nil))))]
      (remove-fn path)
      true)))

(defn append-partial
  "Fold a recovered partial response onto the end of `messages`.

   Marked in the content itself: the model is about to be shown a response it
   never finished emitting, and silently presenting a truncated answer as a
   complete one would have it continue from a sentence it thinks it finished.
   Merged into a trailing assistant message rather than appended after one,
   since two assistant turns in a row is not a shape every provider accepts."
  [messages partial-text]
  (if-not (seq (str (or partial-text "")))
    (vec messages)
    (let [v      (vec messages)
          marked (str partial-text "\n\n[response was cut off here]")
          tail   (last v)]
      (if (= "assistant" (:role tail))
        (update v (dec (count v)) assoc :content marked)
        (conj v {:role "assistant" :content marked})))))
