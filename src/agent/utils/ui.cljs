(ns agent.utils.ui
  "Shared UI-capability predicates. Kept in one place so the permission gate
   (middleware) and the plan gate (plan_mode) agree on when a prompt is possible.")

(defn ui-prompt-ready?
  "True when `ui` can show an interactive selection prompt — it exists, is
   available, and exposes .select. (Distinct from extension-context's hasUI,
   which checks only .available.)"
  [ui]
  (boolean (and ui (.-available ui) (.-select ui))))
