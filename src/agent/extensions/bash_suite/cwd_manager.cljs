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

(defn already-cd-to?
  "Does `cmd` already start with a `cd` into `dir`?

   Belt and braces against re-prefixing: the loop that stacked these is fixed by
   not mutating the model's args, but the model legitimately writing
   `cd /x && foo` while /x is already tracked should not become
   `cd '/x' && cd /x && foo` either. Accepts both the quoted form this file
   emits and a bare one."
  [cmd dir]
  (let [c (str/triml (str cmd))]
    (boolean (or (.startsWith c (str "cd " (quote-path dir) " &&"))
                 (.startsWith c (str "cd " dir " &&"))))))

(defn activate [api]
  (let [config  (shared/load-config)
        cwd-cfg (:cwd-manager config)]

    (.addMiddleware api
                    #js {:name  "bash-suite/cwd-manager"
                         :enter (fn [ctx]
                                  (let [tool-name (aget ctx "tool-name")]
                                    (if (and (:enabled cwd-cfg)
                                             (shared/is-bash-tool? tool-name))
                                      (let [args    (.-args ctx)
                                            cmd     (.-command args)
                                            cwd     @tracked-cwd
                              ;; Carry the pre-prepend command on the CTX, which
                              ;; is threaded enter → execute → leave per
                              ;; execution. This was a single module-level atom,
                              ;; so two bash calls in flight — tool calls in one
                              ;; step run concurrently — had the second :enter
                              ;; clobber the first's command before its :leave
                              ;; read it, and the tracked cwd followed the wrong
                              ;; one.
                                            ctx     (assoc ctx :cwd-manager-original-cmd cmd)]
                          ;; Prepend cd if we have a tracked cwd
                                        (if (and cwd (:validate-cwd cwd-cfg))
                                          (if (fs/existsSync cwd)
                                            (if (already-cd-to? cmd cwd)
                                              ctx
                                              (do
                                                (swap! shared/suite-stats update-in
                                                       [:cwd-manager :cwd-prepended] inc)
                                  ;; REPLACE, never `aset`. The args object is
                                  ;; aliased onto the model's own tool-call part
                                  ;; and replayed to it next step, so mutating it
                                  ;; rewrote the model's record of its own
                                  ;; request — it then imitated the prefix and we
                                  ;; prefixed the imitation, up to 25 deep in
                                  ;; real sessions. See shared/with-arg.
                                                (assoc ctx :args
                                                       (shared/with-arg
                                                         args "command"
                                                         (str "cd " (quote-path cwd) " && " cmd)))))
                              ;; Invalid cwd — reset
                                            (do
                                              (reset! tracked-cwd nil)
                                              (swap! shared/suite-stats update-in
                                                     [:cwd-manager :invalid-cwd-caught] inc)
                                              ctx))
                                          ctx))
                                      ctx)))
                         :leave (fn [ctx]
                                  (let [tool-name (aget ctx "tool-name")]
                                    (when (and (:enabled cwd-cfg)
                                               (:track-cd cwd-cfg)
                                               (shared/is-bash-tool? tool-name)
                                               (get ctx :cwd-manager-original-cmd))
                                      (let [cmd     (get ctx :cwd-manager-original-cmd)
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
      (reset! tracked-cwd nil))))
