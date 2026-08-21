(ns agent.utils.js-interop
  "Squint ships `clj->js` but NOT `js->clj`.

   That absence is silent and expensive: `(js->clj x)` compiles to a bare
   reference to an undefined global, so the call throws a ReferenceError at
   runtime. Every site that had one was wrapped in a try/catch that swallowed
   it, so the symptom was never an error — it was a config block quietly
   discarded and the defaults used instead. `headroom` could not be enabled at
   all, by anyone, for exactly this reason.

   Two copies of the JSON round-trip workaround already existed
   (agent_shell/shared, bash_suite/shared). This is the shared one.")

(defn js->clj*
  "A JS value as something squint's `get`/`assoc`/`merge` can work with, via a
   JSON round-trip. Returns nil for nil.

   The result is plain JS objects with STRING keys, not keywordized maps —
   squint's `get` accepts either, and pretending otherwise is what
   `:keywordize-keys true` used to imply while doing nothing of the sort.
   Values JSON cannot carry (functions, undefined, cycles) do not survive;
   callers passing those want the object itself, not this."
  [x]
  (when (some? x)
    (try
      (js/JSON.parse (js/JSON.stringify x))
      (catch :default _ nil))))

(defn- kebab
  "camelCase -> kebab-case. Leaves an already-kebab key alone."
  [k]
  (.toLowerCase (.replace (str k) (js/RegExp. "([a-z0-9])([A-Z])" "g") "$1-$2")))

(defn kebab-keys
  "Shallow: every key of `o` also present in kebab-case.

   Config files are written in camelCase and read with kebab-case keywords,
   and nothing bridged the two — so `proxyUrl` landed beside an untouched
   `proxy-url` default and the user's value was never read. Both spellings are
   kept so a file using either works; the original wins where they collide,
   since that is what the author typed."
  [o]
  (when-let [m (js->clj* o)]
    (let [out #js {}]
      (doseq [k (js/Object.keys m)]
        (aset out (kebab k) (aget m k)))
      (doseq [k (js/Object.keys m)]
        (aset out k (aget m k)))
      out)))
