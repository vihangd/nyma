(ns agent.ui.tool-renderer-registry
  "Per-tool renderer registry.

   A renderer is a map:
     {:render-call   fn  (required) — takes a call props map and returns
                          an Ink element (the header/body when the tool
                          is still running or collapsed)
      :render-result fn  (optional) — takes a result props map and
                          returns an Ink element (the body when the tool
                          has finished)
      :merge-call-and-result? bool  — when true, :render-call is
                          responsible for rendering both states and the
                          registry skips :render-result entirely
      :inline?       bool — when true, ToolExecution skips the
                          CollapsibleBlock wrapper and the renderer owns
                          the entire visual treatment}

   Renderers register themselves at module load via `register-renderer`.
   Extensions register additional renderers via the `registerToolRenderer`
   API exposed in extensions.cljs.

   The registry is a module-level atom. Re-registering an id overwrites
   the previous entry (useful for hot-reload during development)."
  (:require ["react" :refer [createElement]]))

(def ^:private registry (atom {}))

(defn register-renderer
  "Register a renderer for the given tool-name. Returns the registry
   map after the update."
  [tool-name renderer]
  (swap! registry assoc tool-name renderer)
  @registry)

(defn unregister-renderer
  "Remove the renderer for tool-name from the registry. Safe to call
   when no renderer is registered."
  [tool-name]
  (swap! registry dissoc tool-name)
  @registry)

(defn get-renderer
  "Return the renderer map for tool-name, or nil."
  [tool-name]
  (get @registry tool-name))

(defn all-renderers
  "Return the current registry snapshot — useful for debugging and tests."
  []
  @registry)

(defn reset-registry!
  "Test helper: clear the registry. Not exported to extensions."
  []
  (reset! registry {}))
