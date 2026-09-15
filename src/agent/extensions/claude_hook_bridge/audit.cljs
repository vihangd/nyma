(ns agent.extensions.claude-hook-bridge.audit
  "Trust audit log for hook executions.

   First time a (script, sha256) pair fires, we append a single line
   to ~/.nyma/hooks-audit.log so the user can review what's running.
   Subsequent firings of the same sha are silent (no log spam).

   Audit log format (one JSON object per line):
     {\"ts\":\"2026-04-30T12:34:56Z\",\"event\":\"...\",
      \"command\":\"...\",\"sha256\":\"abc...\"}

   The sha is computed over the raw command string, not the resolved
   script body — that's a deliberate scope cut. Hashing the script
   would require resolving paths, reading files, and dealing with
   shell expansion. The command-string hash gives the user a stable
   token they can grep for and recognize.

   This module is intentionally small and side-effecting (file IO).
   The bridge calls `note!` from the dispatcher right before
   spawning each command-type handler."
  (:require [agent.utils.home :as home]
             ["node:fs" :as fs]
            ["node:path" :as path]
))

(defn- audit-path
  ;; Per call, not at load: HOME can change after import (tests), and a
  ;; baked path silently logs to the wrong home.
  []
  (path/join (home/dir) ".nyma" "hooks-audit.log"))

(def ^:private seen
  "Set of sha256 strings already logged this session."
  (atom #{}))

(defn- sha-of [s]
  ;; Bun.CryptoHasher, not node:crypto's createHash: same digest, measured 2x
  ;; faster on a 200KB input, and one fewer import.
  (-> (js/Bun.CryptoHasher. "sha256")
      (.update (str s))
      (.digest "hex")))

(defn- ensure-dir! [p]
  (try
    (let [dir (path/dirname p)]
      (when-not (fs/existsSync dir)
        (fs/mkdirSync dir #js {:recursive true})))
    (catch :default _e nil)))

(defn note!
  "Log this hook firing once per session per (command, sha) pair.
   Returns the sha string so callers can include it in trace data."
  [event-name command]
  (let [sha (sha-of command)]
    (when-not (contains? @seen sha)
      (swap! seen conj sha)
      (try
        (ensure-dir! (audit-path))
        (let [line (str (js/JSON.stringify
                         #js {:ts      (.toISOString (js/Date.))
                              :event   event-name
                              :command command
                              :sha256  sha})
                        "\n")]
          (fs/appendFileSync (audit-path) line))
        (catch :default _e nil)))
    sha))

(defn reset-seen!
  "Clear the in-memory seen-set. Test helper; also called on
   bridge re-activation (hot reload) so newly-edited scripts get
   re-audited on first fire."
  []
  (reset! seen #{}))

(defn audit-log-path
  "Where the log lives, resolved per call (see audit-path)."
  []
  (audit-path))
