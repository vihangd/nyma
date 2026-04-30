(ns agent.extensions.claude-hook-bridge.index
  "Claude-Code-shaped hooks for nyma.

   Reads the `hooks` key from .nyma/settings.json (and ~/.nyma's), plus
   optional .claude/ + .agents/ compat sources, and registers nyma
   event handlers that translate inbound nyma events into CC's hook
   stdin schema, dispatch to the configured handlers (command / http /
   mcp_tool / prompt), and translate the response back into mutations
   on nyma's middleware ctx or event flow.

   Intended to be activated as a built-in extension via `default api`.
   Per-event wiring lives in `events/*.cljs`; this index only handles
   loading config and orchestrating subscription."
  (:require [agent.extensions.claude-hook-bridge.config :as config]
            [agent.extensions.claude-hook-bridge.events.pre-tool-use :as pre-tool-use]
            [agent.extensions.claude-hook-bridge.events.post-tool-use :as post-tool-use]
            [agent.extensions.claude-hook-bridge.events.session :as session]
            [agent.extensions.claude-hook-bridge.events.user-prompt-submit :as ups]
            [agent.extensions.claude-hook-bridge.events.stop :as stop]
            [agent.extensions.claude-hook-bridge.events.compact :as compact]
            [agent.extensions.claude-hook-bridge.events.permission-request :as perm]))

(defn ^:export default [api]
  (let [cwd        (js/process.cwd)
        compat     (config/load-compat-flags cwd)
        loaded     (config/load-merged-hooks cwd compat)
        hooks-map  (:hooks loaded)
        cleanups   (atom [])]

    (when (seq hooks-map)
      (let [shared {:api          api
                    :hooks-map    hooks-map
                    :cwd          cwd
                    :sources      (:sources-loaded loaded)
                    :disable-all-source (:disable-all-source loaded)}]
        ;; Per-event registrations. Each `register!` returns a
        ;; cleanup thunk we accumulate.
        (swap! cleanups conj (pre-tool-use/register! shared))
        (swap! cleanups conj (post-tool-use/register! shared))
        (swap! cleanups conj (session/register! shared))
        (swap! cleanups conj (ups/register! shared))
        (swap! cleanups conj (stop/register! shared))
        (swap! cleanups conj (compact/register! shared))
        (swap! cleanups conj (perm/register! shared))))

    ;; Deactivate
    (fn []
      (doseq [c @cleanups]
        (when (fn? c) (c))))))
