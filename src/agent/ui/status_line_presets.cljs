(ns agent.ui.status-line-presets
  "Named status line layouts. A preset describes which segments appear
   on the left and right of the status line, the separator style, and
   optional per-segment overrides.")

(def presets
  {"default"
   {:left-segments  ["model" "path" "git" "pr" "context-pct"]
    :right-segments ["token-total" "cost" "time-spent"]
    :separator      "powerline-thin"}

   "minimal"
   {:left-segments  ["model" "git"]
    :right-segments ["context-pct"]
    :separator      "powerline-thin"}

   "compact"
   {:left-segments  ["model" "git" "context-pct"]
    :right-segments ["token-total"]
    :separator      "powerline-thin"}

   "full"
   {:left-segments  ["model" "path" "git" "pr" "subagents" "context-pct"]
    :right-segments ["token-in" "token-out" "token-rate" "cost" "time-spent"]
    :separator      "powerline"}

   "ascii"
   {:left-segments  ["model" "git" "context-pct"]
    :right-segments ["token-total" "cost"]
    :separator      "ascii"}

   "none"
   {:left-segments  []
    :right-segments []
    :separator      "none"}})

(defn get-preset
  "Return the preset map for `name`, falling back to 'default' for
   unknown names or nil."
  [name]
  (or (get presets (str name))
      (get presets "default")))
