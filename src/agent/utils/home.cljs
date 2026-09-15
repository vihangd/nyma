(ns agent.utils.home
  "The one place nyma asks where HOME is.

   `os.homedir()` is the wrong call under Bun: it is resolved once at process
   start and never re-reads `process.env.HOME`. Node re-reads it. So every
   path built from `os/homedir` ignored the scratch HOME the test preload
   installs, and the suite read and wrote the developer's real ~/.nyma —
   settings, keybindings, extensions, a 13 MB debug.log of fixture warnings.
   `home_dir_lint` keeps `os/homedir` out of src."
  (:require ["node:os" :as os]))

(defn dir
  "HOME as the process currently has it; `os.homedir()` only when unset."
  []
  (let [h (.-HOME (.-env js/process))]
    (if (and (string? h) (seq h)) h (os/homedir))))
