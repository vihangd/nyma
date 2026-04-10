(ns agent.ui.renderers.index
  "Requires every built-in tool renderer for its self-registration side
   effects. Import this from app setup so the registry is populated
   before the chat view mounts.

   Uses explicit .jsx extensions because each renderer file is compiled
   to .jsx by squint (the files use `{:squint/extension \"jsx\"}`)."
  (:require ["./read_renderer.jsx" :as _read]
            ["./write_renderer.jsx" :as _write]
            ["./edit_renderer.jsx" :as _edit]
            ["./grep_renderer.jsx" :as _grep]
            ["./todo_write_renderer.jsx" :as _todo]
            ["./bash_renderer.jsx" :as _bash]))
