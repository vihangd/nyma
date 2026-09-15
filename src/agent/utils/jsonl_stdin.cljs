(ns agent.utils.jsonl-stdin
  "Reading a JSONL protocol channel off a stream.

   `node:readline` cannot do this job. It splits on U+2028 and U+2029 as well as
   on LF, and both are legal inside a JSON string — so one valid record
   containing either arrives as two fragments, neither of which parses.
   Measured under Bun 1.4.2:

     records readline produced: 2 (protocol says 1)
       [0] parses as JSON: false  \"{\\\"type\\\":\\\"prompt\\\",\\\"message\\\":\\\"line one\"
       [1] parses as JSON: false  \"line two\\\"}\"

   They are not exotic characters: they turn up in JS source, minified bundles
   and pasted text, all of which a coding agent handles constantly. pi's own
   docs/rpc.md carries the same warning about readline, which is where this was
   found.

   So: split on LF only, strip one trailing CR, and never hand a protocol
   channel to a general-purpose line reader."
  (:require [clojure.string :as str]))

(defn split-records
  "[records remainder] — `buf` cut on LF only.

   A trailing CR is stripped from each record (so CRLF input works) but an
   interior CR is data and is left alone. The remainder is whatever followed the
   last LF, which the caller carries into the next chunk."
  [buf]
  (let [s     (str buf)
        parts (str/split s "\n" -1)
        n     (count parts)]
    [(mapv (fn [r] (if (str/ends-with? r "\r") (subs r 0 (dec (count r))) r))
           (subvec (vec parts) 0 (dec n)))
     (nth parts (dec n))]))

(defn read-lines!
  "Call `on-line` for each LF-delimited record from `stream`; `on-close` at end.

   Returns a stop fn. `setEncoding` is what makes a multi-byte character split
   across two chunks safe — node decodes with a StringDecoder that holds the
   partial sequence, so we only ever see whole characters."
  [stream on-line & [on-close]]
  (let [buf    (atom "")
        ;; A stream emits "end" AND "close"; both mean the same thing here,
        ;; and a caller's on-close (exit, teardown) must run once.
        closed (atom false)
        flush!
        (fn []
          ;; A final record with no trailing newline is still a record.
          (let [remainder @buf]
            (reset! buf "")
            (when (seq remainder) (on-line remainder))))
        on-data
        (fn [chunk]
          (let [[records remainder] (split-records (str @buf chunk))]
            (reset! buf remainder)
            (doseq [r records] (on-line r))))
        on-end
        (fn []
          (when-not @closed
            (reset! closed true)
            (flush!)
            (when on-close (on-close))))]
    (.setEncoding stream "utf8")
    (.on stream "data" on-data)
    (.on stream "end" on-end)
    (.on stream "close" on-end)
    (fn []
      (try (.off stream "data" on-data) (catch :default _ nil))
      (try (.off stream "end" on-end) (catch :default _ nil))
      (try (.off stream "close" on-end) (catch :default _ nil)))))
