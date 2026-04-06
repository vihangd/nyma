(ns agent.extensions.bash-suite.cwd-manager
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.extensions.bash-suite.shared :as shared]
            [clojure.string :as str]))

;; ── State ────────────────────────────────────────────────────

(def tracked-cwd (atom nil))

;; ── Helpers ──────────────────────────────────────────────────

(defn quote-path
  "Quote a path for safe use in shell commands.
   Wraps in single quotes and escapes internal single quotes."
  [p]
  (str "'" (str/replace (str p) "'" "'\\''") "'"))

(defn extract-cd-targets
  "Extract cd targets from a command string. Returns vector of target paths.
   Takes the LAST cd target since that's what persists in the shell."
  [cmd]
  (let [matches (re-seq #"cd\s+([^\s;&|]+)" (str cmd))]
    (vec (map second matches))))

(defn resolve-cd-target
  "Resolve a cd target path to an absolute path."
  [target base-cwd]
  (let [target (str target)
        ;; Expand ~ to home directory
        expanded (if (.startsWith target "~")
                   (str/replace target #"^~" (os/homedir))
                   target)]
    (if (path/isAbsolute expanded)
      expanded
      (path/resolve (or base-cwd (js/process.cwd)) expanded))))

;; ── Extension activation ─────────────────────────────────────

(defn activate [api]
  (let [config  (shared/load-config)
        cwd-cfg (:cwd-manager config)
        ;; Store original command before cwd prepend for cd tracking
        original-cmd (atom nil)]

    (.addMiddleware api
      #js {:name  "bash-suite/cwd-manager"
           :enter (fn [ctx]
                    (let [tool-name (.-tool-name ctx)]
                      (if (and (:enabled cwd-cfg)
                               (shared/is-bash-tool? tool-name))
                        (let [args    (.-args ctx)
                              cmd     (.-command args)
                              cwd     @tracked-cwd]
                          ;; Store original for cd tracking in leave phase
                          (reset! original-cmd cmd)
                          ;; Prepend cd if we have a tracked cwd
                          (if (and cwd (:validate-cwd cwd-cfg))
                            (if (fs/existsSync cwd)
                              (do
                                (aset args "command" (str "cd " (quote-path cwd) " && " cmd))
                                (swap! shared/suite-stats update-in
                                  [:cwd-manager :cwd-prepended] inc)
                                ctx)
                              ;; Invalid cwd — reset
                              (do
                                (reset! tracked-cwd nil)
                                (swap! shared/suite-stats update-in
                                  [:cwd-manager :invalid-cwd-caught] inc)
                                ctx))
                            ctx))
                        ctx)))
           :leave (fn [ctx]
                    (let [tool-name (.-tool-name ctx)]
                      (when (and (:enabled cwd-cfg)
                                 (:track-cd cwd-cfg)
                                 (shared/is-bash-tool? tool-name)
                                 @original-cmd)
                        (let [cmd     @original-cmd
                              targets (extract-cd-targets cmd)]
                          (when (seq targets)
                            (let [last-target (last targets)
                                  base        (or @tracked-cwd (js/process.cwd))
                                  resolved    (resolve-cd-target last-target base)]
                              (when (fs/existsSync resolved)
                                (reset! tracked-cwd resolved)
                                (swap! shared/suite-stats update-in
                                  [:cwd-manager :cd-tracked] inc))))))
                    ctx))})

    ;; Return deactivator
    (fn []
      (.removeMiddleware api "bash-suite/cwd-manager")
      (reset! tracked-cwd nil)
      (reset! original-cmd nil))))
