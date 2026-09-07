(ns stream-delta-field.test
  "Every consumer of `message_update` must read `.text`.

   `agent/loop.cljs` emits the RAW AI SDK v7 fullStream part, whose text field
   is `text`. `textDelta` is the OBJECT stream's field and is always undefined
   here — loop.cljs says so in a comment, and the interactive pane and session
   manager were both fixed for it. Two consumers were not:

     src/gateway/loop.cljs:88   → on-chunk never fired; the gateway's entire
                                  streaming path was dead and every response
                                  arrived in one flush at on-end.
     src/agent/modes/pi_rpc.cljs → every text_delta sent to the Emacs frontend
                                  was the empty string.

   Same shape as `:runtime-model`: a field with no producer, read behind an `or`
   or a `when-let`, so it degrades to silence instead of failing. A grep-level
   lint is the only thing that would have caught it, because neither consumer
   has a test and the field name is valid JS either way."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def ^:private roots ["src/agent" "src/gateway"])

(defn- cljs-files [dir]
  (if-not (fs/existsSync dir)
    []
    (reduce (fn [acc e]
              (let [p (path/join dir (.-name e))]
                (cond (.isDirectory e) (into acc (cljs-files p))
                      (.endsWith (.-name e) ".cljs") (conj acc p)
                      :else acc)))
            []
            (vec (fs/readdirSync dir #js {:withFileTypes true})))))

(defn- all-files []
  (vec (mapcat (fn [r] (cljs-files (path/resolve (js/process.cwd) r))) roots)))

(def ^:private window
  "How far either side of a `.-textDelta` to look for its `.-text` partner.
   Line-based matching was not enough: sessions/manager.cljs pairs them across
   two lines of an `or`, and flagging that would have taught everyone to ignore
   this lint."
  200)

(defn bare-text-delta-reads
  "Occurrences of `.-textDelta` with no `.-text` alternative nearby. A paired
   read is a deliberate fallback for older producers and is fine."
  [source]
  (loop [from 0 hits []]
    (let [i (.indexOf source ".-textDelta" from)]
      (if (neg? i)
        hits
        (let [lo   (max 0 (- i window))
              hi   (min (count source) (+ i window))
              near (.slice source lo hi)
              ok?  (.includes near ".-text ")]
          (recur (inc i)
                 (if ok?
                   hits
                   (conj hits (str/trim (.slice source
                                                (max 0 (- i 60))
                                                (min (count source) (+ i 40))))))))))))

(describe "message_update consumers read .text" (fn []

  (it "finds sources under both roots"
      (fn []
        ;; Guard the guard: a bad path would pass the assertion below vacuously.
        (-> (expect (> (count (all-files)) 50)) (.toBe true))
        ;; …and both roots must actually contribute, since the bug lived in the
        ;; one that is easy to forget.
        (-> (expect (boolean (some (fn [f] (.includes f "/gateway/")) (all-files))))
            (.toBe true))))

  (it "flags a bare read and accepts a paired one"
      (fn []
        ;; Detector self-test.
        (-> (expect (count (bare-text-delta-reads "(when-let [d (.-textDelta c)] d)")))
            (.toBe 1))
        (-> (expect (count (bare-text-delta-reads "(or (.-text c) (.-textDelta c))")))
            (.toBe 0))
        ;; …including when the pair straddles a line break, as it does in
        ;; sessions/manager.cljs.
        (-> (expect (count (bare-text-delta-reads
                            "(or (and data (.-text data))\n     (and data (.-textDelta data))\n     \"\")")))
            (.toBe 0))))

  (it "no consumer reads textDelta alone"
      (fn []
        (let [bad (->> (all-files)
                       (mapcat (fn [f]
                                 (map (fn [l] (str (path/basename f) ": " (str/trim l)))
                                      (bare-text-delta-reads (fs/readFileSync f "utf8")))))
                       vec)]
          (-> (expect (str/join " | " bad)) (.toBe "")))))))
