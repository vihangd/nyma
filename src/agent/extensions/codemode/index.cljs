(ns agent.extensions.codemode.index
  "Code mode: one `script` tool whose squint (Clojure) source calls other tools
   as functions — `(github/list-issues {…})`, `(nyma/grep {…})`,
   `(petstore/getPetById {…})` — so fetch → filter → fetch-more is one round
   trip and the intermediate data never enters the context.

   Measured on a real eM Client session (3 turns, 145 calls, ~1.3 MB of tool
   results): 736 KB was a 407 KB `get_email_data` dump and 16 `retrieve_result`
   pages to read it back. A script reads the same bodies, keeps sender, date and
   text, and returns that. The model in that session ended up writing its own
   Python MCP client to get there; this is the version nyma owns.

   Shape (opencode 2.0's `@opencode/codemode`, Cloudflare Code Mode, Anthropic
   programmatic tool calling), adapted:
   - The language is the squint nyma already ships. Squint is a compiler, not a
     sandbox, so the script is approved like bash: exec category — asks in
     default and accept-edits, absent from plan mode (not in its allowed
     tools), runs in full-auto.
   - Every nested call goes through `api.executeTool` — the middleware
     pipeline under the tool's own name, so before_tool_call hooks,
     .nymaignore, a role's per-tool deny and tracking all still apply. The
     tools the script names are listed in the approval prompt and preapproved
     for that run; a deny is never lifted.
   - Only tools the source names are bound (`analyze`), the script runs in a
     Worker so timeout and Esc can stop it, and the result is the last form.

   Settings `codemode`: enabled, catalog (tool-name patterns listed in the
   system prompt), apis (OpenAPI specs → tools), timeout-ms, max-calls,
   max-output-chars."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.debug :as d]
            [agent.middleware :as mw]
            [agent.utils.spill-file :as spill]
            [agent.extensions.token-suite.tool-deferral :as td]
            [agent.extensions.codemode.analyze :as an]
            [agent.extensions.codemode.catalog :as catalog]
            [agent.extensions.codemode.openapi :as openapi]))

(def default-config
  {:enabled false :catalog ["mcp__*"] :apis {}
   :timeout-ms 60000 :max-calls 50 :max-output-chars 20000})

(defn config [section]
  (let [s (or section #js {})
        g (fn [k] (aget s k))]
    (cond-> default-config
      (some? (g "enabled"))          (assoc :enabled (true? (g "enabled")))
      (array? (g "catalog"))         (assoc :catalog (vec (g "catalog")))
      (object? (g "apis"))           (assoc :apis (g "apis"))
      (number? (g "timeout-ms"))     (assoc :timeout-ms (g "timeout-ms"))
      (number? (g "max-calls"))      (assoc :max-calls (g "max-calls"))
      (number? (g "max-output-chars")) (assoc :max-output-chars (g "max-output-chars")))))

(def self-name "codemode__script")

(def tool-description
  "Kept fixed — the catalog of functions rides in the system prompt — so the
   tool list, and the prompt cache with it, do not change when a server does."
  (str "Run a squint (ClojureScript) script that calls tools as functions, in ONE step. "
       "Use it to chain calls, loop over results, and filter/aggregate data before it reaches you: "
       "fetch 20 records, keep 3 fields, return those.\n"
       "- Call tools as (ns/fn {args}) — the catalog in the system prompt lists them with their "
       "argument names; use those names exactly (per_page stays per_page).\n"
       "- Calls return promises: (js-await …) inside let, or just map over them — the result is "
       "awaited deeply, so (map #(gh/get-issue {:number %}) ids) runs in parallel.\n"
       "- Tool results arrive parsed when they are JSON, otherwise as strings.\n"
       "- The last form is the result; (println …) goes to logs. A failed call throws — wrap in "
       "(try … (catch :default e …)) to continue.\n"
       "- squint ≠ Clojure: maps are not functions (use get / :key), no js->clj, no array-seq."))

;;; ─── catalog ───────────────────────────────────────────────────

(defn- entries [api patterns]
  (->> (vec (or (.getAllTools api) []))
       (remove #(= self-name %))
       (filter #(td/deferred? % patterns))
       (map (fn [n]
              (let [t (.getTool api n)]
                {:name n
                 :description (some-> t .-description)
                 :schema (td/schema->json (some-> t .-inputSchema))})))))

(defn prompt-block [api cfg]
  (let [es (entries api (conj (:catalog cfg) "codemode__*__*"))]
    (when (seq es)
      (str "\n\n## Script functions (codemode__script)\n"
           "Call these from a `codemode__script` script as (ns/fn {args}); `?` marks optional.\n"
           (catalog/render es catalog/default-budget-chars)
           "\nOther registered tools are callable the same way: (nyma/read {:path …}), "
           "(<extension>/<tool> …)."))))

;;; ─── running ───────────────────────────────────────────────────

(defn- maybe-json [x]
  (if (and (string? x) (re-find #"^\s*[\[{]" x))
    (try (js/JSON.parse x) (catch :default _ x))
    x))

(defn- did-you-mean [api missing]
  (let [names (vec (or (.getAllTools api) []))]
    (str/join "; "
              (map (fn [m]
                     (let [[n f] (str/split m #"/")
                           same  (->> names
                                      (filter #(= (an/munge-part n) (first (an/binding-key %))))
                                      (map an/display-name)
                                      (sort-by (fn [c] (if (.includes c (str f)) 0 1)))
                                      (take 5))]
                       (str m (if (seq same) (str " — did you mean " (str/join ", " same) "?")
                                  " — no such namespace"))))
                   missing))))

(defn- worker-url
  "Beside this file in dist/; in a compiled binary, where it was passed as an
   extra entry point (package.json bundle scripts) and lives under the bundle
   root relative to `dist/agent`, the entry points' common root — measured: a
   URL relative to import.meta.url names `/$bunfs/root/worker.mjs`, which does
   not exist."
  []
  (let [u (js/URL. "./worker.mjs" (.-url js/import.meta))]
    (if (fs/existsSync (js/Bun.fileURLToPath u))
      u
      (path/join (.-dir js/import.meta) "extensions" "codemode" "worker.mjs"))))

(defn ^:async compile-script [code]
  (let [sq (js-await (js/import "squint-cljs"))]
    ((.-compileString sq) code #js {:context "return" :elide-imports true})))

(defn run-worker
  "Run compiled `body` with `bindings` {ns [fn]} → Promise of the worker's
   done message, plus :calls (the ledger). `call!` runs one nested call."
  [body bindings {:keys [timeout-ms max-calls signal label]} call!]
  (js/Promise.
   (fn [resolve _]
     (let [w      (js/Worker. (worker-url))
           calls  #js []
           done?  (atom false)
           finish (fn [m]
                    (when-not @done?
                      (reset! done? true)
                      (.terminate w)
                      (aset m "calls" calls)
                      (resolve m)))
           fail   (fn [kind msg]
                    (finish #js {:ok false :logs #js [] :error #js {:kind kind :message msg}}))
           timer  (js/setTimeout #(fail "Timeout" (str "script exceeded " timeout-ms " ms")) timeout-ms)]
       (when signal
         (.addEventListener signal "abort" #(fail "Aborted" "interrupted") #js {:once true}))
       (set! (.-onerror w) (fn [e] (fail "RuntimeError" (str (or (.-message e) e)))))
       (set! (.-onmessage w)
             ^:async (fn [ev]
                       (let [m (.-data ev)]
                         (case (.-type m)
                           "done" (do (js/clearTimeout timer)
                                      (when-not (.-ok m) (aset m "logs" (or (.-logs m) #js [])))
                                      (finish m))
                           "call"
                           (let [entry #js {:tool ((or label str) (.-ns m) (.-fn m)) :status "running"}]
                             (.push calls entry)
                             (if (> (.-length calls) max-calls)
                               (do (aset entry "status" "error")
                                   (.postMessage w #js {:type "reply" :id (.-id m) :ok false :kind "CallLimit"
                                                        :ns (.-ns m) :fn (.-fn m) :label (.-tool entry)
                                                        :error (str "more than " max-calls " tool calls")}))
                               (let [r (js-await (call! (.-ns m) (.-fn m) (.-args m)))]
                                 (aset entry "status" (if (.-ok r) "ok" (if (.-denied r) "denied" "error")))
                                 (when-not @done?
                                   (.postMessage w #js {:type "reply" :id (.-id m) :ok (.-ok r)
                                                        :ns (.-ns m) :fn (.-fn m) :label (.-tool entry)
                                                        :kind (if (.-denied r) "Denied" "ToolFailure")
                                                        :result (maybe-json (.-result r))
                                                        :error (.-error r)})))))
                           nil))))
       (.postMessage w #js {:type "run" :body body :bindings (clj->js bindings)})))))

(defn- spill! [text]
  (when-let [dir (spill/private-temp-dir! "nyma-codemode")]
    (spill/write! (path/join dir (spill/random-name ".json")) text)))

(defn render-result
  "The text the model reads. Always carries logs and the call ledger, so a
   failed script says which calls already ran (a retry should not repeat a
   side effect blindly)."
  [m max-chars]
  (let [logs   (vec (or (.-logs m) []))
        calls  (vec (or (.-calls m) []))
        ledger (when (seq calls)
                 (str "calls: " (str/join ", " (map #(str (.-tool %) " " (.-status %)) calls))))
        body   (if (.-ok m)
                 (let [v (.-value m)]
                   (if (string? v) v (js/JSON.stringify v nil 1)))
                 (let [e (.-error m)]
                   (str "[" (.-kind e) "] " (.-message e)
                        (when (.-tool e) (str " (in " (.-tool e) ")")))))
        body   (if (> (count body) max-chars)
                 (let [f (spill! body)]
                   (str (subs body 0 max-chars) "\n…[truncated: " (count body) " chars"
                        (when f (str "; full result in " f)) "]"))
                 body)]
    (str/join "\n" (cond-> [body]
                     (seq logs) (conj (str "logs:\n" (str/join "\n" (take 50 logs))))
                     ledger     (conj ledger)))))

(defn ^:async execute-script [api cfg args ext-ctx]
  (let [code   (str (or (aget args "code") ""))
        names  (remove #(= self-name %) (vec (or (.getAllTools api) [])))
        result (an/analyze code names)]
    (cond
      (str/blank? code) "[ParseError] empty script"
      (seq (:traps result)) (str "[TrapLint] " (str/join "; " (:traps result)))
      (seq (:unknown result)) (str "[UnknownTool] " (did-you-mean api (:unknown result)))
      :else
      (let [compiled (try (js-await (compile-script code))
                          (catch :default e {:error (str (or (.-message e) e))}))]
        (if (:error compiled)
          (str "[ParseError] " (:error compiled))
          (let [by-key   (into {} (map (fn [t] [(str/join "/" (an/binding-key t)) t]) (:tools result)))
                bindings (reduce (fn [acc t]
                                   (let [[n f] (an/binding-key t)]
                                     (update acc n (fnil conj []) f)))
                                 {} (:tools result))
                approved #js {:preapproved (clj->js (:tools result))}
                call!    (fn [n f a] (.executeTool api (get by-key (str n "/" f)) a approved))
                m        (js-await (run-worker compiled bindings
                                               (assoc cfg
                                                      :signal (some-> ext-ctx .-abortSignal)
                                                      :label  (fn [n f] (an/display-name (get by-key (str n "/" f)))))
                                               call!))]
            (render-result m (:max-output-chars cfg))))))))

;;; ─── the tool ──────────────────────────────────────────────────

(defn script-tool [api cfg]
  #js {:description tool-description
       :inputSchema #js {:type "object"
                         :properties #js {:code #js {:type "string" :description "squint source"}}
                         :required #js ["code"]}
       ;; exec, like bash: the compiled script has the process's authority.
       :safety #js {:destructive? true :requires-confirmation? true
                    :capabilities #js ["shell"] :category "exec"}
       ;; Runs before the permission gate, so the prompt can name what the
       ;; script will call instead of showing an opaque blob.
       :prepareArguments
       (fn [args]
         (let [code  (str (or (aget args "code") ""))
               names (remove #(= self-name %) (vec (or (.getAllTools api) [])))]
           (js/Object.assign #js {} args
                             #js {:calls (an/approval-line (an/analyze code names) mw/categorize-tool)})))
       :execute (fn [args ext-ctx] (execute-script api cfg args ext-ctx))})

;;; ─── activation ────────────────────────────────────────────────

(defn ^:async register-apis!
  "Load each configured spec and register its operations. A spec that fails
   to load is reported and skipped; the others still register."
  [api apis]
  (let [registered (atom [])]
    (doseq [k (js/Object.keys apis)]
      (let [c (aget apis k)]
        (try
          (let [spec (js-await (openapi/load-spec (aget c "spec")))
                cfg  {:base-url (or (aget c "base-url")
                                    (some-> spec (aget "servers") (aget 0) (aget "url")))
                      :headers  (aget c "headers")}]
            (doseq [op (openapi/operations spec)]
              (let [n (str k "__" (:name op))]
                (.registerTool api n (openapi/tool-def cfg op))
                (swap! registered conj n))))
          (catch :default e
            (d/warn (str "[codemode] API " k ": " (.-message e)))))))
    @registered))

(defn ^:export activate [api]
  (let [cfg (config (.settings api "codemode"))]
    (if-not (:enabled cfg)
      (fn [] nil)
      (let [apis (atom [])
            on-access (fn [ev]
                        ;; API operations are for scripts; listing each as a
                        ;; direct tool is the schema bloat code mode removes.
                        #js {:allowed (clj->js (remove #(re-find #"^codemode__.+__" %) (vec (.-tools ev))))})
            on-start  (fn [_ev]
                        (when-let [b (prompt-block api cfg)]
                          #js {"system-prompt-additions" #js [b]}))]
        (.registerTool api "script" (script-tool api cfg))
        (-> (register-apis! api (:apis cfg)) (.then #(reset! apis %)))
        (.on api "tool_access_check" on-access 50)
        (.on api "before_agent_start" on-start)
        (fn []
          (.off api "tool_access_check" on-access)
          (.off api "before_agent_start" on-start)
          (doseq [n @apis] (.unregisterTool api n))
          (.unregisterTool api "script"))))))

(def ^:export default activate)
