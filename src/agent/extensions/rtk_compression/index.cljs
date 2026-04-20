(ns agent.extensions.rtk-compression.index
  "Rewrite bash commands through rtk (Rust Token Killer) for 60-90% output compression.
   RTK is exec-only and subcommand-based: 'git status' becomes 'rtk git status'.
   The canonical integration pattern (used by Claude Code, OpenCode, Cursor, etc.)
   is REWRITE-BEFORE-EXECUTION, not compress-after. This extension does the same."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["shell-quote" :as shell-quote]
            [clojure.string :as str]))

;; ── Default config ────────────────────────────────────────────

(def default-config
  {:enabled            true
   :rtk-binary         "rtk"
   :disabled-rewriters []
   :log-rewrites       false})

;; ── Built-in rewriter table ───────────────────────────────────
;; Closed whitelist: only commands explicitly listed here are rewritten.
;; Each :id doubles as the command prefix to match and the rtk subcommand.
;; Other extensions can append entries via the rtk-compression:register-rewriter event.

(def built-in-rewriters
  [{:id "git"}
   {:id "cargo"}
   {:id "npm"}
   {:id "pnpm"}
   {:id "pytest"}
   {:id "ruff"}
   {:id "tsc"}
   {:id "eslint"}
   {:id "rg"}
   {:id "grep"}
   {:id "find"}
   {:id "ls"}
   {:id "cat"}
   {:id "head"}
   {:id "tail"}
   {:id "diff"}])

;; ── Config loading ────────────────────────────────────────────

(defn- load-config []
  (let [settings-path (path/join (js/process.cwd) ".nyma" "settings.json")]
    (if (fs/existsSync settings-path)
      (try
        (let [raw    (fs/readFileSync settings-path "utf8")
              parsed (js/JSON.parse raw)
              rtk    (aget parsed "rtk-compression")]
          (if rtk
            ;; js->clj doesn't exist in Squint; use JSON round-trip
            (let [rtk-clj (js/JSON.parse (js/JSON.stringify rtk))]
              (merge default-config rtk-clj))
            default-config))
        (catch :default _e default-config))
      default-config)))

(defn- build-disabled-set
  "Convert :disabled-rewriters value (may be a CLJ seq or JS array) to a set of strings."
  [v]
  (if (array? v)
    (set (js/Array.from v))
    (set (or v []))))

;; ── Command analysis ──────────────────────────────────────────

(defn has-operators?
  "Returns true if cmd contains shell operator tokens (pipes, &&, ||, ;, redirects)
   or subshell syntax. Only single-invocation commands are safe to rewrite."
  [cmd]
  (let [s (str cmd)]
    (or (.includes s "$(")
        (.includes s "`")
        (try
          (.some (.parse shell-quote s)
                 (fn [token] (and (not (string? token)) (.-op token))))
          (catch :default _e true)))))  ; fail-safe: treat unparseable as unsafe

(defn- first-token
  "Returns the first whitespace-delimited token of cmd, or nil if unparseable."
  [cmd]
  (try
    (let [parsed (.parse shell-quote (str cmd))]
      (when (pos? (.-length parsed))
        (let [first-el (aget parsed 0)]
          (when (string? first-el) first-el))))
    (catch :default _e nil)))

(defn find-rewriter
  "Find a matching rewriter spec for cmd from rewriters, excluding disabled ids.
   Returns the rewriter map {:id ...} or nil if no rewrite should happen.
   Exported for unit testing."
  [cmd rewriters disabled-set]
  (when-not (has-operators? cmd)
    (let [tok (first-token cmd)]
      (when tok
        (some (fn [r]
                (when (and (= (:id r) tok)
                           (not (contains? disabled-set (:id r))))
                  r))
              rewriters)))))

;; ── Binary availability check ─────────────────────────────────

(defn- check-available [api rtk-binary]
  (-> (.exec api "which" #js [rtk-binary])
      (.then (fn [result]
               (pos? (count (str/trim (str (.-stdout result)))))))
      (.catch (fn [_] false))))

;; ── Extension activation ──────────────────────────────────────

(defn ^:export default [api]
  (let [config       (load-config)
        enabled?     (:enabled config)
        rtk-binary   (str (:rtk-binary config))
        disabled-set (build-disabled-set (:disabled-rewriters config))
        log?         (:log-rewrites config)]

    (if-not enabled?
      ;; Disabled in config — return no-op deactivator immediately
      (fn [] nil)

      (let [rewriters        (atom built-in-rewriters)
            ;; Gate: starts false, set true after successful binary check.
            ;; Middleware is registered synchronously but is a no-op until gate opens.
            rtk-ok?          (atom false)
            mw-name          "rtk-compression/rewrite"
            ;; Keep a stable reference for .off cleanup
            register-handler (fn [spec]
                               (let [id (str (.-id spec))]
                                 (when (and id (pos? (count id)))
                                   (swap! rewriters conj {:id id}))))]

        ;; Async binary check — fires in background, opens the gate on success
        (-> (check-available api rtk-binary)
            (.then (fn [ok?]
                     (if ok?
                       (reset! rtk-ok? true)
                       (js/console.warn
                        (str "[rtk-compression] '"  rtk-binary
                             "' not found in PATH. Compression inactive."
                             " Install: https://github.com/rtk-ai/rtk")))))
            (.catch (fn [_] nil)))

        ;; Register-rewriter event: other extensions append entries to the table
        (.on api "rtk-compression:register-rewriter" register-handler)

        ;; Middleware: enter phase, after permission check, before execute-tool
        (.addMiddleware api
                        #js {:name  mw-name
                             :enter (fn [ctx]
                                      (when (and @rtk-ok?
                                                 (= "bash" (str (.-tool-name ctx))))
                                        (let [args (.-args ctx)
                                              cmd  (str (aget args "command"))]
                                          (when-let [_rw (find-rewriter cmd @rewriters disabled-set)]
                                            (let [new-cmd (str rtk-binary " " cmd)]
                                              (aset args "command" new-cmd)
                                              (when log?
                                                (js/console.log
                                                 (str "[rtk-compression] " cmd " → " new-cmd)))))))
                                      ctx)})

        ;; Return deactivator
        (fn []
          (.removeMiddleware api mw-name)
          (.off api "rtk-compression:register-rewriter" register-handler)
          (reset! rewriters built-in-rewriters)
          (reset! rtk-ok? false))))))
