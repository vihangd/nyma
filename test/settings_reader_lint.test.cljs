(ns settings-reader-lint.test
  "Every documented settings key must be read by something.

   Six were not: steering-mode, follow-up-mode, tool-display,
   tool-display-max-lines, scrollback-mode and status-line. All six are in the
   defaults, all six are in the README's defaults block, and none has a reader
   in src/. `:status-line`'s only apparent reader is a docstring EXAMPLE in
   utils/validation.cljs.

   Two of them were never built; four were live before `acee030` and were
   deleted with the Ink UI, leaving the key, the docs, and — for scrollback — a
   doc comment pointing at two files that no longer exist. A user who sets one
   gets silence, which is the same thing they get if they set it correctly.

   This lint does not care WHY a key is dead. It requires that every key either
   has a reader or is listed below with a reason, so the next deletion has to
   make a choice rather than leave a lie behind."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]))

(def ^:private manager-path
  (path/resolve (js/process.cwd) "src" "agent" "settings" "manager.cljs"))

(def known-unread
  "Keys with no reader, and why. Each entry is a promise to the user that the
   setting does nothing — which is only acceptable while it is written down."
  {"steering-mode"          "never built; inject-steer-messages! drains the whole queue"
   "follow-up-mode"         "never built; the drain is already one-at-a-time"
   "tool-display"           "removed with the Ink UI (acee030); chat_renderer is one-line only"
   "tool-display-max-lines" "removed with the Ink UI (acee030); only applied in the expanded branch"
   "scrollback-mode"        "removed with the Ink UI (acee030); scrollback.cljs and chat_pager.cljs are gone"
   
   "status-line"            "removed with the Ink UI (acee030); no preset layer remains"})

(defn default-keys
  "Top-level keys of the settings defaults map."
  [source]
  (->> (str/split-lines source)
       (keep (fn [l] (second (re-find #"^\s{2,4}:([a-z][a-z0-9-]*)\s" l))))
       ;; `:else` is a cond arm, not a settings key.
       (remove (fn [k] (= k "else")))
       set))

(defn- readers
  "Files outside manager.cljs that mention `:key` or \"key\"."
  [k]
  (let [out (try
              (.toString
               (.execSync (js/require "node:child_process")
                          (str "grep -rl -e ':" k "\\b' -e '\"" k "\"' src/ || true")))
              (catch :default _ ""))]
    (->> (str/split (str/trim out) #"\n")
         (remove str/blank?)
         (remove (fn [f] (.includes f "settings/manager.cljs")))
         ;; A docstring example is not a reader — utils/validation.cljs cites
         ;; [:status-line :left-segments] purely to illustrate a :path field.
         (remove (fn [f] (.includes f "utils/validation.cljs")))
         vec)))

(describe "every settings key has a reader, or a written reason" (fn []

  (it "parses the defaults map"
      (fn []
        ;; Guard the guard.
        (-> (expect (> (count (default-keys (fs/readFileSync manager-path "utf8"))) 10))
            (.toBe true))
        ;; …and must include keys we know are live, or the matcher is wrong.
        (let [ks (default-keys (fs/readFileSync manager-path "utf8"))]
          (-> (expect (contains? ks "thinking")) (.toBe true))
          (-> (expect (contains? ks "compaction")) (.toBe true)))))

  (it "finds readers for a key that certainly has one"
      (fn []
        ;; Detector self-test: a broken grep would report every key as dead and
        ;; the allowlist would silently absorb it.
        (-> (expect (> (count (readers "compaction")) 0)) (.toBe true))
        (-> (expect (count (readers "definitely-not-a-real-settings-key"))) (.toBe 0))))

  (it "has no unread key that is not accounted for"
      (fn []
        (let [ks   (default-keys (fs/readFileSync manager-path "utf8"))
              dead (->> ks
                        (filter (fn [k] (zero? (count (readers k)))))
                        (remove known-unread)
                        sort vec)]
          (-> (expect (str/join ", " dead)) (.toBe "")))))

  (it "has no allowlist entry that has quietly come back to life"
      (fn []
        ;; A stale allowlist is its own failure: it would keep claiming a
        ;; working setting does nothing.
        (let [revived (->> (keys known-unread)
                           (filter (fn [k] (pos? (count (readers k)))))
                           sort vec)]
          (-> (expect (str/join ", " revived)) (.toBe "")))))

  (it "has no allowlist entry that has been removed from the defaults"
      (fn []
        (let [ks    (default-keys (fs/readFileSync manager-path "utf8"))
              stale (->> (keys known-unread) (remove ks) sort vec)]
          (-> (expect (str/join ", " stale)) (.toBe "")))))))
