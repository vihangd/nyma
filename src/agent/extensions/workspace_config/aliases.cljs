(ns agent.extensions.workspace-config.aliases
  "Custom command aliases — /alias <name> <target> stored in workspace config."
  (:require [clojure.string :as str]))

;;; ─── Helpers ────────────────────────────────────────────────

(defn- notify [api msg & [level]]
  (if (and (.-ui api) (.-available (.-ui api)))
    (.notify (.-ui api) msg (or level "info"))
    nil))

(defn- find-handler
  "Look up a command handler in the current command map by name or __suffix."
  [commands name]
  (or (get commands name)
      (let [suffix  (str "__" name)
            matches (filterv (fn [[k _]] (.endsWith k suffix)) (seq commands))]
        (when (= (count matches) 1)
          (second (first matches))))))

;;; ─── Registration helpers ───────────────────────────────────

(defn- register-alias!
  "Register a single alias command. Late-binding: target is resolved at call time."
  [api aliases-atom name target]
  (let [commands (.getCommands api)]
    ;; Refuse to shadow an existing command
    (if (find-handler commands name)
      (notify api (str "Cannot alias \"" name "\" — command already exists") "error")
      (do
        (swap! aliases-atom assoc name target)
        (.registerCommand api name
          #js {:description (str "Alias → " target)
               :handler (fn [extra-args _ctx]
                          (let [cmds        (.getCommands api)
                                ;; target may be "/foo bar" — strip leading slash, split
                                target-str  (if (str/starts-with? target "/")
                                              (subs target 1)
                                              target)
                                parts       (str/split target-str #"\s+" 2)
                                target-cmd  (first parts)
                                target-args (when (second parts) (str/split (second parts) #"\s+"))
                                all-args    (vec (concat target-args extra-args))
                                entry       (find-handler cmds target-cmd)]
                            (if entry
                              ((:handler entry) (clj->js all-args) nil)
                              (notify api (str "Alias target \"" target "\" not found") "error"))))})
        (notify api (str "Alias /" name " → " target " registered"))))))

(defn- unregister-alias!
  "Remove an alias command."
  [api aliases-atom name]
  (if (get @aliases-atom name)
    (do
      (swap! aliases-atom dissoc name)
      (.unregisterCommand api name)
      (notify api (str "Alias /" name " removed")))
    (notify api (str "No alias named \"" name "\"") "error")))

(defn ctx-positional
  "The flag-free tokens interactive mode's shared splitter put on `ctx`,
   or nil when the caller did not come through it."
  [ctx]
  (when ctx
    (when-let [p (try (aget ctx "positional") (catch :default _ nil))]
      (vec p))))

(defn ctx-flags
  "`--flag` / `--flag=value` / `--no-flag` from the shared splitter, as a
   map of raw key → true | false | string. Empty when absent."
  [ctx]
  (or (when ctx
        (when-let [f (try (aget ctx "flags") (catch :default _ nil))]
          (into {} (map (fn [k] [k (aget f k)]) (js/Object.keys f)))))
      {}))

(defn- list-aliases [api aliases-atom]
  (let [m @aliases-atom]
    (if (empty? m)
      (notify api "No aliases defined. Use /alias <name> <target> to create one.")
      (let [lines (map (fn [[k v]] (str "  /" k " → " v)) (sort m))
            text  (str "Aliases:\n" (str/join "\n" lines))]
        (notify api text)))))

;;; ─── Activation ─────────────────────────────────────────────

(defn activate
  "Register /alias command and load pre-existing aliases from atom."
  [api aliases-atom]

  ;; Register all aliases already in the atom (from workspace config)
  (doseq [[name target] @aliases-atom]
    (let [cmds (.getCommands api)]
      (when-not (find-handler cmds name)
        (.registerCommand api name
          #js {:description (str "Alias → " target)
               :handler (fn [extra-args _ctx]
                          (let [cmds        (.getCommands api)
                                target-str  (if (str/starts-with? target "/")
                                              (subs target 1)
                                              target)
                                parts       (str/split target-str #"\s+" 2)
                                target-cmd  (first parts)
                                target-args (when (second parts) (str/split (second parts) #"\s+"))
                                all-args    (vec (concat target-args extra-args))
                                entry       (find-handler cmds target-cmd)]
                            (if entry
                              ((:handler entry) (clj->js all-args) nil)
                              (notify api (str "Alias target \"" target "\" not found") "error"))))}))))

  ;; Register the /alias management command
  (.registerCommand api "alias"
    #js {:description "Manage command aliases. Usage: /alias [<name> <target>] [--remove <name>]"
         :handler (fn [args ctx]
                    ;; Migrated to interactive mode's shared argument splitter:
                    ;; `--remove` is read from ctx.flags rather than matched as
                    ;; a positional token, and a quoted target
                    ;; (`/alias fix "spec analyze --run"`) survives as ONE
                    ;; argument instead of being re-split on spaces.
                    ;;
                    ;; `args` is still every token, so a caller that is not
                    ;; interactive mode (a keybinding, a test) keeps the old
                    ;; behaviour via the fallbacks below.
                    (let [flags  (ctx-flags ctx)
                          argv   (or (ctx-positional ctx) (vec args))
                          remove-name (let [f (get flags "remove")]
                                        (cond
                                          (string? f) f
                                          (true? f)   (first argv)
                                          ;; Pre-splitter form: ["--remove" "x"]
                                          (= (first (vec args)) "--remove") (second (vec args))
                                          :else nil))]
                      (cond
                        (some? remove-name)
                        (unregister-alias! api aliases-atom remove-name)

                        (empty? argv)
                        (list-aliases api aliases-atom)

                        (and (first argv) (second argv))
                        (register-alias! api aliases-atom (first argv)
                          (str/join " " (rest argv)))

                        :else
                        (notify api "Usage: /alias <name> <target>  |  /alias --remove <name>  |  /alias" "error"))))})

  ;; Return deactivator
  (fn []
    (.unregisterCommand api "alias")
    (doseq [name (keys @aliases-atom)]
      (.unregisterCommand api name))))
