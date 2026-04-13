(ns agent.utils.validation
  "Structured validation warnings with suggestions.

   Borrowed from cc-kit's
     packages/ui/src/keybindings/validate.ts:20-28, 427-465
   which defines a `{type, severity, message, suggestion}` shape for
   every validation issue and a `formatWarnings` helper that groups
   errors vs warnings and pluralises messages for the user.

   Before this module, nyma's error paths (settings loader, extension
   loader, tool schema check) returned bare strings. That's fine for a
   programmer reading logs but terrible for a user debugging a typo:
   no category, no actionable fix, no way to surface them consistently
   in the CLI.

   The shape:
     {:type     keyword/string identifying the rule, e.g.
                :settings/duplicate-key, :extension/missing-handler
      :severity :error | :warning | :info
      :path     optional — breadcrumb into the config (e.g.
                [:status-line :left-segments])
      :message  one-line human description
      :suggestion optional — actionable hint ending in a period
                  (e.g. \"Use ctrl+s instead\")}

   Usage:
     (v/issue :settings/duplicate-key
              \"Key 'theme' appears twice in settings.json\"
              {:path [:theme]
               :severity :warning
               :suggestion \"Remove the duplicate entry; JSON.parse keeps the last value.\"})"
  (:require [clojure.string :as str]))

(defn issue
  "Construct a validation issue. Only :type and :message are required;
   everything else falls back to sensible defaults (severity :warning,
   empty path)."
  [issue-type message & [{:keys [severity path suggestion] :as _opts}]]
  (cond-> {:type     issue-type
           :severity (or severity :warning)
           :message  message}
    path       (assoc :path path)
    suggestion (assoc :suggestion suggestion)))

(defn error
  "Shortcut for an :error-severity issue."
  [issue-type message & [opts]]
  (issue issue-type message (assoc opts :severity :error)))

(defn warning
  "Shortcut for a :warning-severity issue."
  [issue-type message & [opts]]
  (issue issue-type message (assoc opts :severity :warning)))

(defn errors-only
  "Filter a seq of issues to the errors."
  [issues]
  (filterv #(= (:severity %) :error) issues))

(defn warnings-only
  "Filter a seq of issues to the warnings."
  [issues]
  (filterv #(= (:severity %) :warning) issues))

(defn has-errors?
  "True when any issue in `issues` has :severity :error. Always
   returns a proper boolean (not nil/undefined) so callers can use
   the result in JS contexts where `undefined` behaves differently
   from `false`."
  [issues]
  (boolean (some #(= (:severity %) :error) issues)))

;;; ─── Formatting ────────────────────────────────────────

(defn- ->string
  "Normalise a keyword / symbol / string into a plain display string
   WITHOUT relying on `clojure.core/name`. Squint does not
   auto-import `name` into every compiled module, and bare `(name x)`
   compiles to an un-scoped function call that ReferenceErrors at
   runtime. We mimic it manually: strip the leading ':' from a
   keyword's string form, leave strings alone."
  [x]
  (cond
    (nil? x)     ""
    (string? x)  x
    :else        (let [s (str x)]
                   (if (and (pos? (count s)) (= (.charAt s 0) ":"))
                     (.slice s 1)
                     s))))

(defn- format-path [path]
  (when (seq path)
    (str "at " (str/join "." (map ->string path)) " — ")))

(defn format-issue
  "Format a single issue as a one-line string.
   Example:
     '[warning] settings/duplicate-key at theme — Key 'theme' appears twice.
                Remove the duplicate entry.'"
  [{:keys [type severity message suggestion path] :as _issue}]
  (let [sev-tag  (str "[" (->string (or severity :warning)) "]")
        type-str (->string type)
        prefix   (str sev-tag " " type-str " ")
        body     (str (format-path path) message)
        suffix   (when suggestion (str " " suggestion))]
    (str prefix body suffix)))

(defn format-issues
  "Format a seq of issues as a multi-line string, grouped with a
   leading summary line. Returns the empty string when there are no
   issues so callers can unconditionally print the result without a
   blank line."
  [issues]
  (if (empty? issues)
    ""
    (let [err-count (count (errors-only issues))
          warn-count (count (warnings-only issues))
          header (str (when (pos? err-count)
                        (str err-count " error" (when (not= 1 err-count) "s")))
                      (when (and (pos? err-count) (pos? warn-count)) ", ")
                      (when (pos? warn-count)
                        (str warn-count " warning" (when (not= 1 warn-count) "s"))))
          ;; Force to a concrete vector: str/join's lazy-seq path in
          ;; squint-cljs tickles an internal 'name' reference under
          ;; certain iterator shapes. A vector short-circuits into
          ;; the Array.join fast path.
          body   (str/join "\n" (mapv format-issue issues))]
      (str header ":\n" body))))
