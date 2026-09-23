(ns agent.utils.data
  "Two things every namespace kept re-deriving: reading a config key whose
   spelling depends on who wrote it, and parsing JSON that may not be JSON.

   Squint keywords ARE strings and `get` works on plain JS objects, so the
   same lookup serves a CLJS map, a `JSON.parse` result and a `#js` literal.
   What differed per site was the *ladder* — `(or (.-baseUrl x) (aget x
   \"base-url\"))` — and `or` collapses a legitimate `false` or `0` into the
   next rung. Three incompatible helpers grew out of that (`entry-get` +
   `entry-get-bool` in the relay, `cfg-get` in subagent) plus 60-odd inline
   copies. These are the one version."
  (:require [clojure.string :as str]))

(defn key-get
  "First PRESENT value of `ks` in `m`, or nil. Present means non-nil, so a
   `false` or a `0` a user wrote survives (an `or`-chain would skip it).
   `m` may be a CLJS map, a JS object or nil; keys may be keywords or strings."
  [m & ks]
  (when (some? m)
    (loop [ks ks]
      (when (seq ks)
        (let [v (get m (first ks))]
          (if (some? v) v (recur (rest ks))))))))

(defn spellings
  "The kebab, camelCase and snake_case spellings of a kebab-case key, kebab
   first so a normalised settings map wins over a raw JS one."
  [k]
  (let [kebab (name k)
        ;; squint's str/replace passes the callback the match only.
        camel (str/replace kebab #"-\w" (fn [m] (str/upper-case (subs (str m) 1))))
        snake (str/replace kebab "-" "_")]
    (vec (distinct [kebab camel snake]))))

(defn conf-get
  "`key-get` across the spellings of one kebab-case key: `(conf-get x
   :base-url)` reads `base-url`, `baseUrl` or `base_url`, whichever is
   present. Optional `default` when none is."
  [m k & [default]]
  (let [v (apply key-get m (spellings k))]
    (if (nil? v) default v)))

(defn conf-bool
  "`conf-get` for a flag: an explicit `false` is a value, absence is
   `default`."
  [m k default]
  (let [v (conf-get m k)]
    (if (nil? v) default (boolean v))))

(defn parse-json
  "`JSON.parse` that answers `fallback` (default nil) instead of throwing —
   for anything else, blank, or not a string. One place to grow a
   duplicate-key scan or a position-aware error later."
  [s & [fallback]]
  (if (and (string? s) (seq s))
    (try (js/JSON.parse s) (catch :default _ fallback))
    fallback))
