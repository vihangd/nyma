(ns agent.extensions.claude-hook-bridge.matcher
  "Claude Code matcher syntax — used to filter when a hook fires.

   Three forms (per the CC spec):
     - empty / nil / \"*\"           → match everything
     - letters/digits/underscores/pipes only → exact string or pipe-list
       e.g. \"Bash\"          → exactly Bash
            \"Edit|Write\"     → either Edit or Write
     - any other characters         → JavaScript regex
       e.g. \"^Notebook\"     → starts with Notebook
            \"mcp__memory__.*\" → all tools from the memory MCP server

   The simple-shape detection is deliberately the same regex CC uses
   so legacy scripts behave identically."
  (:require [clojure.string :as str]))

(defn- simple-shape?
  "True when the matcher string contains only letters, digits,
   underscores, and pipes — meaning treat it as exact / pipe-list."
  [s]
  (boolean (re-matches #"^[A-Za-z0-9_|]+$" s)))

(defn matches?
  "Return true when `matcher` matches the inbound `value` (typically
   a tool name or matcher-key like \"startup\" / \"resume\").

   Case-tolerance for the simple shape:
   nyma's bridge accepts `matcher: \"bash\"` as well as `\"Bash\"` —
   strict CC is case-sensitive, but the lowercase forms come up so
   often when CLJS users hand-write configs that the silent
   no-match foot-gun is worth eating a small spec divergence for.
   The regex branch stays strict (use `(?i)` if you want it
   case-insensitive) so users with intentional regexes aren't
   surprised."
  [matcher value]
  (let [m (when matcher (str matcher))
        v (when value (str value))]
    (cond
      (or (nil? m) (= m "") (= m "*"))
      true

      (nil? v)
      false

      (simple-shape? m)
      (let [lower-v (.toLowerCase v)
            exacts  (->> (str/split m #"\|")
                         (map #(.toLowerCase %))
                         set)]
        (contains? exacts lower-v))

      :else
      (try
        (boolean (.test (js/RegExp. m) v))
        (catch :default _e
          ;; If the regex is invalid, fail-safe: don't match (rather
          ;; than always-match) — matches CC's behavior.
          false)))))
