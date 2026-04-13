(ns agent.commands.parser
  "Pure command-line parsing + suggestion helpers. Borrowed from
   cc-kit's CommandRegistry (packages/ui/src/commands/registry.ts)
   with nyma's existing command-map shape kept intact.

   Before this module:
     - `autocomplete_builtins.cljs:slash-provider` dumped every
       registered command and relied on the downstream fuzzy filter.
     - `app.cljs` + `autocomplete_provider.cljs` used loose
       `.startsWith(\"/\")` checks that we've already debugged twice
       (the `/agent qwen` arg-swallowing bug and the `/agent @file`
       command-clobbering bug).

   After this module:
     - `parse-command-line` is the single authoritative way to ask
       'is this editor text a completed /cmd invocation, and if so
       what's the command + args?'. It returns nil for bare slashes,
       unknown commands, hidden commands, or disabled commands.
     - `command-suggestions` replaces ad-hoc filtering with a prefix
       match that understands aliases + visibility.
     - `visible-commands` centralises hidden/disabled filtering.

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
   both the suggestions list and by parse-command-line to reject
   invocations of disabled commands."
  [cmd]
  (and (not (hidden? cmd)) (enabled? cmd)))

;;; ─── Alias resolution ──────────────────────────────────

(defn- aliases-of
  "Normalised sequence of aliases on a command spec. Always a seq,
   never nil."
  [cmd]
  (or (:aliases cmd) []))

(defn- find-by-name-or-alias
  "Look up a command in the commands map by canonical name first,
   falling back to an alias scan. Returns `[canonical-name cmd]` or
   nil. Aliases are case-sensitive — same as cc-kit's contract at
   registry.ts:37-40."
  [commands name]
  (when (and commands name)
    (if-let [direct (get commands name)]
      [name direct]
      ;; Fallback — scan entries for an alias match.
      (some (fn [[cname spec]]
              (when (some #{name} (aliases-of spec))
                [cname spec]))
            commands))))

;;; ─── Parsing ───────────────────────────────────────────

(defn parse-command-line
  "Parse the raw editor text. Returns
     {:name canonical-name :command cmd-spec :args args-string}
   when `text` is a well-formed invocation of a visible, enabled
   command, or nil otherwise.

   Returns nil for:
     - nil / non-string input
     - text that doesn't start with '/'
     - bare '/' (no command name yet)
     - unknown command names
     - hidden or disabled commands

   `:args` is the whitespace-trimmed string after the first space.
   Empty when no arguments were provided.

   Mirrors cc-kit's registry.ts:30-43."
  [text commands]
  (when (string? text)
    (let [trimmed (str/trim text)]
      (when (and (> (count trimmed) 1)
                 (str/starts-with? trimmed "/"))
        (let [space-idx (.indexOf trimmed " ")
              name      (if (neg? space-idx)
                          (subs trimmed 1)
                          (subs trimmed 1 space-idx))
              args      (if (neg? space-idx)
                          ""
                          (str/trim (subs trimmed (inc space-idx))))]
          (when-let [[canonical cmd] (find-by-name-or-alias commands name)]
            (when (visible? cmd)
              {:name    canonical
               :command cmd
               :args    args})))))))

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
