(ns agent.ui.autocomplete-provider
  "Combined autocomplete provider registry.

   A provider is a map:
     {:id       string
      :trigger  :slash | :at | :any
      :priority number (higher runs first — used only for display order)
      :complete fn [context] → Promise<vec items>}

   Each item is a map {:label :value :description? :replace-range?}.

   The trigger decides when a provider is invoked:
     :slash  — text starts with '/' (and has no space, so we're still
               typing the command name)
     :at     — text ends with '@' at a word boundary (start of text or
               preceded by whitespace)
     :any    — always invoked

   `complete-all` runs every provider whose trigger matches the text,
   merges the results, scores them with the fuzzy scorer, and returns a
   flat list of items sorted best-first.

   History note: an earlier design also had a `:path` trigger that
   fired on `endsWith '/'` or `startsWith './'`. It created ambiguity
   (every bare `/` lit up both the command picker and a file browser)
   and was dropped in phase 21. File references go through @-mentions
   exclusively — the same pattern cc-kit and claude code use."
  (:require [agent.ui.fuzzy-scorer :refer [fuzzy-filter]]
            ["node:fs/promises" :as fsp]))

;;; ─── Trigger detection ──────────────────────────────────

(defn detect-trigger
  "Inspect the editor text and return a set of trigger keywords
   that apply right now. Pure function — used by complete-all and
   by the app-side effect.

   Triggers:

     :slash   — text starts with a single `/` and has no space yet.
                Fires only while the command name is still being
                typed, so arguments after a space don't reopen the
                picker on top of the user's typing.

     :at      — the last character is `@` AND the `@` is at a word
                boundary (start of text or preceded by whitespace),
                so `email@foo.com` style text does not fire the
                mention picker.

     :any     — always present. Used by providers that want to run
                on every keystroke regardless of prefix.

   The old `:path` trigger (fired on `endsWith '/'` or `startsWith './'`)
   was dropped in phase 21 — file references go through the @-mention
   path exclusively, matching cc-kit and claude code. `/` is now
   unambiguously a command trigger."
  [text]
  (let [s (or text "")
        n (count s)
        has-space?     (not (neg? (.indexOf s " ")))
        starts-slash?  (.startsWith s "/")
        ;; `//` is the literal agent-forward trigger, handled by the
        ;; agent-shell input router (strips the leading slash and
        ;; streams the command to the connected ACP agent). When `//`
        ;; is present we MUST NOT also fire `:slash`, or the nyma
        ;; command picker would render over the agent command picker.
        starts-double? (.startsWith s "//")
        at-last?       (and (pos? n) (= "@" (subs s (dec n) n)))
        ;; Word-boundary check for @: either the @ is at position 0,
        ;; or the char immediately before it is whitespace. Prevents
        ;; `email@foo.com` style mid-word @ from firing the mention
        ;; picker, which would be surprising and wrong.
        at-wb?   (or (= n 1)
                     (and (> n 1)
                          (let [prev (subs s (- n 2) (dec n))]
                            (or (= prev " ") (= prev "\t") (= prev "\n")))))]
    (cond-> #{}
      (and starts-double? (not has-space?))                    (conj :slash-forward)
      (and starts-slash? (not starts-double?) (not has-space?)) (conj :slash)
      (and at-last? at-wb?)                                     (conj :at)
      true                                                       (conj :any))))

(defn active-trigger?
  "Return true if any of the 'picker-opening' triggers are active in
   the text — excludes :any, which is always present but should not
   pop a picker on every keystroke."
  [text]
  (let [ts (detect-trigger text)]
    (boolean (or (contains? ts :slash)
                 (contains? ts :slash-forward)
                 (contains? ts :at)))))

(defn should-open-picker?
  "Pure decision function for the app-side autocomplete effect.

   Returns true iff ALL of the following hold:
     - no overlay is currently showing (`:overlay?` is false)
     - the agent is not streaming (`:streaming?` is false)
     - the editor isn't hidden (`:editor-hidden?` is false)
     - `text` is a non-empty string
     - `text` is NOT the exact value the user just dismissed a picker
       on (`:dismissed-value`) — this is the gate that prevents the
       slash picker from snapping right back after an Escape
     - at least one opening trigger (:slash / :at / :path) is detected

   Extracted from app.cljs so the dismissal logic can be unit-tested
   without mounting the whole ink tree."
  [text {:keys [overlay? streaming? editor-hidden? dismissed-value]}]
  (and (not overlay?)
       (not streaming?)
       (not editor-hidden?)
       (string? text)
       (pos? (count text))
       (not= text dismissed-value)
       (active-trigger? text)))

(defn- extract-query
  "Extract the token the user is currently typing. For '/' triggers the
   token is everything after the slash up to the first space. For '@'
   it's everything after the last '@'. For :any the provider's own
   results are authoritative, so the fuzzy filter should be a no-op
   (empty query)."
  [trigger text]
  (let [s (or text "")]
    (case trigger
      :slash (let [rest-text (.slice s 1)
                   space-idx (.indexOf rest-text " ")]
               (if (neg? space-idx) rest-text (.slice rest-text 0 space-idx)))
      :slash-forward (let [rest-text (.slice s 2)
                           space-idx (.indexOf rest-text " ")]
                       (if (neg? space-idx) rest-text (.slice rest-text 0 space-idx)))
      :at    (let [idx (.lastIndexOf s "@")]
               (if (>= idx 0) (.slice s (inc idx)) s))
      :any   ""
      s)))

(defn replace-trigger-token
  "Pure function: given current editor `txt` and the `val` the user
   selected from a picker, return the new editor text. Branches must
   match detect-trigger exactly — the slash branch only fires while the
   command name is still being typed (no space yet), otherwise a picker
   that opened via :at or :path while the user was typing arguments
   would clobber the whole command line.

   Contract:
     /cl     + \"/clear\"    → \"/clear \"
     /agent  + \"/agent\"    → \"/agent \"
     hello @ + \"file.txt\"  → \"hello file.txt \"
     src/    + \"main.cljs\" → \"src/main.cljs\"
     /agent @ + \"file.txt\" → \"/agent file.txt \"   (regression)
     /agent qwen / no match  → slash branch skipped   (regression)"
  [txt val]
  (let [s (or txt "")
        v (or val "")]
    (cond
      ;; Slash-command completion — only while the command name is still
      ;; being typed. Once a space is in the text we're typing args and
      ;; any picker that opened came from :at or :path.
      ;; The `//` (slash-forward) case is caught by the same branch —
      ;; the provider returns values like `//cmd` so the whole text is
      ;; replaced wholesale.
      (and (.startsWith s "/") (neg? (.indexOf s " ")))
      (str v " ")

      ;; @-mention completion. The @ is at the end of the text; replace
      ;; just the trailing @ with the selected value plus a trailing space.
      (.endsWith s "@")
      (str (.slice s 0 (dec (count s))) v " ")

      ;; Path completion — replace the last segment after the final '/'.
      :else
      (let [idx (.lastIndexOf s "/")]
        (if (>= idx 0)
          (str (.slice s 0 (inc idx)) v)
          v)))))

;;; ─── Registry ──────────────────────────────────────────

(defn create-provider-registry
  "Build a fresh provider registry. Returns
     {:providers (atom {id → provider})
      :register fn [id provider]
      :unregister fn [id]
      :get fn [id]
      :list fn []}"
  []
  (let [providers (atom {})]
    {:providers providers
     :register  (fn [id provider]
                  (swap! providers assoc (str id)
                         (assoc provider :id (str id))))
     :unregister (fn [id] (swap! providers dissoc (str id)))
     :get       (fn [id] (get @providers (str id)))
     :list      (fn [] (vec (vals @providers)))}))

;;; ─── Aggregation ───────────────────────────────────────

(defn ^:async complete-all
  "Run every provider whose :trigger matches the current text. Each
   provider is invoked with a context map {:text :trigger :query}.
   Results from all providers are concatenated and fuzzy-filtered by
   the extracted query."
  [registry text]
  (let [triggers (detect-trigger text)
        providers (filter (fn [p] (contains? triggers (:trigger p)))
                          ((:list registry)))
        ;; Use the first non-:any trigger when extracting the query.
        primary  (or (first (disj triggers :any)) :any)
        query    (extract-query primary text)
        promises (mapv (fn [p]
                         (let [ctx {:text text :trigger primary :query query}
                               ;; Catch both synchronous and async errors so a
                               ;; single flaky provider can't tank the whole
                               ;; completion step.
                               p-promise (try
                                           ((:complete p) ctx)
                                           (catch :default _ (js/Promise.resolve [])))]
                           (.catch p-promise (fn [_] []))))
                       providers)
        all-raw  (js-await (js/Promise.all (clj->js promises)))
        merged   (reduce (fn [acc items]
                           (into acc (or items [])))
                         []
                         all-raw)]
    (fuzzy-filter merged query
                  (fn [item]
                    (or (get item :label) (get item "label") "")))))

;;; ─── readdir cache ─────────────────────────────────────

(def ^:private readdir-cache
  "{path → {:entries :expires-at}} — process-wide TTL cache used by the
   built-in path-completion provider."
  (atom {}))

(def ^:private TTL-MS 2000)

(defn clear-readdir-cache!
  "Test helper."
  []
  (reset! readdir-cache {}))

(defn ^:async readdir-cached
  "Return a vector of entries for `path`, cached for TTL-MS milliseconds.
   Silently returns [] on any filesystem error."
  [path]
  (let [now   (js/Date.now)
        entry (get @readdir-cache path)]
    (if (and entry (< now (:expires-at entry)))
      (:entries entry)
      (let [entries (try
                      (js-await (fsp/readdir path))
                      (catch :default _ #js []))
            v       (vec entries)]
        (swap! readdir-cache assoc path
               {:entries v :expires-at (+ now TTL-MS)})
        v))))
