(ns agent.commands.parser
  "Pure command-line parsing + suggestion helpers. Borrowed from
   cc-kit's CommandRegistry (packages/ui/src/commands/registry.ts)
   with nyma's existing command-map shape kept intact.

   Before this module, `autocomplete_builtins.cljs:slash-provider`
   dumped every registered command and relied on the downstream fuzzy
   filter. Now:
     - `command-suggestions` replaces ad-hoc filtering with a prefix
       match that understands aliases + visibility.
     - `visible-commands` centralises hidden/disabled filtering.
     - `compute-display-names` shortens namespaced command names and
       handles collisions.

   Command map shape (extension — all fields except :description are
   optional, existing commands keep working):
     {\"name\" {:description \"...\"
               :handler      fn
               :aliases      [\"alt1\" \"alt2\"]   ; optional
               :hidden?      false                ; optional, default false
               :enabled?     (fn [] ...)}}        ; optional, default true"
  (:require [clojure.string :as str]))

;;; ─── Visibility predicates ─────────────────────────────

(defn hidden?
  "True when a command spec is explicitly marked hidden."
  [cmd]
  (boolean (:hidden? cmd)))

(defn enabled?
  "True when a command spec has no :enabled? predicate or the
   predicate returns truthy. Catches exceptions — a broken predicate
   from an extension must not crash the picker."
  [cmd]
  (if-let [pred (:enabled? cmd)]
    (try (boolean (pred)) (catch :default _ false))
    true))

(defn visible?
  "A command is visible iff it's not hidden AND it's enabled. Used by
   the suggestions list and by `visible-commands`."
  [cmd]
  (and (not (hidden? cmd)) (enabled? cmd)))

;;; ─── Alias resolution ──────────────────────────────────

(defn- aliases-of
  "Normalised sequence of aliases on a command spec. Always a seq,
   never nil."
  [cmd]
  (or (:aliases cmd) []))

;;; ─── Display names (namespace stripping + collision handling) ─

(defn- short-name
  "Strip the `namespace__` prefix from a command name.
   `agent-shell__agent` → `agent`. Names without `__` are returned
   as-is."
  [name]
  (let [idx (.indexOf name "__")]
    (if (neg? idx)
      name
      (.slice name (+ idx 2)))))

(defn compute-display-names
  "Given a seq of canonical command names, return a map
     {canonical → display-string}
   with:
     - Non-colliding commands shortened to their suffix
       (`agent-shell__agent` → `agent`).
     - Commands whose short form collides with another command keep
       their full canonical name, and are right-padded with spaces so
       every member of the collision group has the same width. Padding
       is LOCAL to the collision group only — it does not affect other,
       non-colliding suggestions. This keeps the picker compact when
       there are no collisions, and aligned when there are.

   Example:
     inputs:  [\"help\" \"agent-shell__agent\" \"foo__plan\" \"bar__plan\"]
     output:  {\"help\"               \"help\"
               \"agent-shell__agent\" \"agent\"
               \"foo__plan\"          \"foo__plan\"
               \"bar__plan\"          \"bar__plan\"}
   (`foo__plan` and `bar__plan` are already the same length so no
   padding is needed; a shorter canonical name in the same group would
   have been space-padded to 9 chars.)"
  [names]
  (let [pairs  (mapv (fn [n] [n (short-name n)]) names)
        freq   (frequencies (map second pairs))
        in-collision? (fn [short] (> (get freq short 0) 1))
        group-max (reduce (fn [acc [canonical short]]
                            (if (in-collision? short)
                              (update acc short (fnil max 0) (count canonical))
                              acc))
                          {}
                          pairs)]
    (into {}
          (map (fn [[canonical short]]
                 (if (in-collision? short)
                   (let [w   (get group-max short)
                         pad (- w (count canonical))
                         padded (if (pos? pad)
                                  (str canonical (apply str (repeat pad " ")))
                                  canonical)]
                     [canonical padded])
                   [canonical short]))
               pairs))))

;;; ─── Visibility + suggestions ──────────────────────────

(defn visible-commands
  "Return the commands map filtered to visible, enabled entries.
   Preserves the input shape ({name → cmd}) so callers can still
   destructure via seq."
  [commands]
  (into {} (filter (fn [[_ cmd]] (visible? cmd)) (or commands {}))))

(defn command-suggestions
  "Return a seq of `{:name :display-name :command}` maps for every
   visible command whose canonical name, short (namespace-stripped)
   name, or any alias starts with the prefix typed after the leading
   '/'. Stable alphabetical order by display name.

   `:name` is the canonical key (still used by the resolver and the
   input dispatcher). `:display-name` is what the picker should show:
   the short form for unique commands, the full canonical for
   collision-group members (padded locally — see
   `compute-display-names`).

   Returns an empty seq when `partial` doesn't start with '/'.
   Mirrors cc-kit's registry.ts:45-53."
  [partial commands]
  (if (and (string? partial) (str/starts-with? partial "/"))
    (let [visible    (visible-commands commands)
          displays   (compute-display-names (vec (keys visible)))
          search     (str/lower-case (subs partial 1))
          matches    (for [[name cmd] visible
                           :let [disp      (get displays name name)
                                 lname     (str/lower-case name)
                                 lshort    (str/lower-case (short-name name))
                                 ldisplay  (str/lower-case (str/trim disp))
                                 lalises   (map str/lower-case (aliases-of cmd))]
                           :when (or (str/starts-with? lname search)
                                     (str/starts-with? lshort search)
                                     (str/starts-with? ldisplay search)
                                     (some #(str/starts-with? % search) lalises))]
                       {:name         name
                        :display-name disp
                        :command      cmd})]
      (sort-by (fn [m] (str/trim (:display-name m))) matches))
    []))

;;; ─── Fuzzy fallback ────────────────────────────────────

(defn edit-distance
  "Levenshtein distance between two strings. Small inputs only — this
   runs over the command table, which is dozens of entries, not
   thousands."
  [a b]
  (let [a (str a) b (str b)
        m (count a) n (count b)]
    (cond
      (zero? m) n
      (zero? n) m
      :else
      (let [prev (js/Array. (inc n))
            cur  (js/Array. (inc n))]
        (loop [j 0] (when (<= j n) (aset prev j j) (recur (inc j))))
        (loop [i 1]
          (if (> i m)
            (aget prev n)
            (do
              (aset cur 0 i)
              (loop [j 1]
                (when (<= j n)
                  (let [cost (if (= (.charAt a (dec i)) (.charAt b (dec j))) 0 1)]
                    (aset cur j (min (inc (aget cur (dec j)))
                                     (inc (aget prev j))
                                     (+ cost (aget prev (dec j))))))
                  (recur (inc j))))
              (loop [j 0] (when (<= j n) (aset prev j (aget cur j)) (recur (inc j))))
              (recur (inc i)))))))))

(defn near-commands
  "Visible commands whose canonical name, short name or alias is within
   `max-dist` edits of `word` (no leading '/'). Sorted by distance then
   display name, so `/sepc` offers `/spec` first.

   Separate from `command-suggestions` on purpose: the picker wants
   prefix matches only — a fuzzy pass on every keystroke shows junk —
   while 'did you mean' runs once, after an exact lookup already failed."
  ([word commands] (near-commands word commands 2))
  ([word commands max-dist]
   (let [visible  (visible-commands commands)
         displays (compute-display-names (vec (keys visible)))
         w        (str/lower-case (str word))
         scored   (for [[name cmd] visible
                        :let [cands (concat [(str/lower-case name)
                                             (str/lower-case (short-name name))]
                                            (map str/lower-case (aliases-of cmd)))
                              d     (apply min (map (fn [c] (edit-distance w c)) cands))]
                        :when (<= d max-dist)]
                    {:name         name
                     :display-name (get displays name name)
                     :command      cmd
                     :distance     d})]
     (sort-by (fn [m] [(:distance m) (str/trim (:display-name m))]) scored))))

(defn command-suggestions+fuzzy
  "`command-suggestions`, falling back to an edit-distance ≤ 2 match when
   the prefix pass finds nothing. This is what 'Unknown command — did you
   mean?' should call: `/sepc` is a transposition, not a prefix, and the
   prefix-only path answered it with silence."
  [partial commands]
  (let [exact (command-suggestions partial commands)]
    (if (seq exact)
      exact
      (if (and (string? partial) (str/starts-with? partial "/"))
        (near-commands (subs partial 1) commands)
        []))))

;;; ─── Argument splitting ────────────────────────────────

(defn- tokenize
  "Split a command's argument string on runs of whitespace, honouring
   double quotes. `a \"b c\" d` → [\"a\" \"b c\" \"d\"]. An unterminated
   quote runs to the end of the line rather than dropping the text."
  [s]
  (let [s   (str (or s ""))
        out #js []
        buf #js []]
    (loop [i 0 in-q false]
      (if (>= i (count s))
        (do (when (pos? (.-length buf)) (.push out (.join buf "")))
            (vec out))
        (let [c (.charAt s i)]
          (cond
            (= c "\"")
            (recur (inc i) (not in-q))

            (and (not in-q) (re-find #"\s" c))
            (do (when (pos? (.-length buf))
                  (.push out (.join buf ""))
                  (.splice buf 0 (.-length buf)))
                (recur (inc i) in-q))

            :else
            (do (.push buf c) (recur (inc i) in-q))))))))

(defn- flag-token?
  [t]
  (and (string? t) (.startsWith t "--") (> (count t) 2)))

(defn parse-command-args
  "Split the text AFTER `/cmd` into {:args :positional :flags}.

     /name \"my session\"        → {:args [\"my session\"]
                                    :positional [\"my session\"] :flags {}}
     /alias --remove foo        → {:args [\"--remove\" \"foo\"]
                                    :positional [\"foo\"]
                                    :flags {\"remove\" true}}
     /x --profile=fast --no-cache
                                → :flags {\"profile\" \"fast\" \"cache\" false}

   `:args` keeps EVERY token, flags included, exactly as today's
   whitespace split produced them — every command that hand-parses its
   own `--flags` keeps working. Quoted spans collapsing into one token is
   the only change. `:positional` is `:args` minus the flag tokens, for
   commands that have been migrated; `:flags` is additive.

   Flag keys keep their raw hyphenated spelling (`--no-cache` →
   `flags[\"cache\"] = false`, `--dry-run` → `flags[\"dry-run\"]`): a
   hyphen does not survive dot access in squint, so callers read flags
   with `get`, never `.-`."
  [s]
  (let [tokens (tokenize s)
        flags  (reduce
                (fn [acc t]
                  (if (flag-token? t)
                    (let [body (subs t 2)
                          eq   (.indexOf body "=")]
                      (cond
                        (>= eq 0) (assoc acc (subs body 0 eq) (subs body (inc eq)))
                        (.startsWith body "no-") (assoc acc (subs body 3) false)
                        :else (assoc acc body true)))
                    acc))
                {}
                tokens)]
    {:args       tokens
     :positional (vec (remove flag-token? tokens))
     :flags      flags}))
