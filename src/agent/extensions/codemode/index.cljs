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
            [agent.extensions.codemode.openapi :as openapi]
            [agent.extensions.codemode.files :as files]
            [agent.extensions.codemode.saved :as saved]
            [agent.trust :as trust]
            [agent.schema :as schema]))

(def default-config
  {:enabled false :catalog ["mcp__*"] :script-only [] :apis {}
   :timeout-ms 60000 :max-calls 50 :max-output-chars 20000})

(defn config [section]
  (let [s (or section #js {})
        g (fn [k] (aget s k))]
    (cond-> default-config
      (some? (g "enabled"))          (assoc :enabled (true? (g "enabled")))
      (array? (g "catalog"))         (assoc :catalog (vec (g "catalog")))
      (array? (g "script-only"))     (assoc :script-only (vec (g "script-only")))
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
       "awaited deeply, so (map #(gh/get-issue {:number %}) ids) runs in parallel. To filter or "
       "reduce fetched data, await the batch first: (js-await (js/Promise.all (mapv #(gh/get-issue {:number %}) ids))).\n"
       "- Tool results arrive parsed when they are JSON (or as the server's structuredContent), otherwise as strings. "
       "An MCP attachment arrives as {:text … :files [{:path :mime :bytes}]}; copy it with "
       "(nyma/save-file {:path \"out/x.pdf\" :from (:path (first (:files r)))}), read a PDF with (nyma/pdf-text {:path …}).\n"
       "- Email/HTML bodies: (nyma/strip-quotes (nyma/html->text html)) is the new text without quoted history or "
       "signature — use it rather than writing an HTML regex.\n"
       "- The last form is the result; (println …) goes to logs. A failed call throws — wrap in "
       "(try … (catch :default e …)) to continue.\n"
       "- A catalog line's → is what the call returns, and each script's result lists the shape of every call "
       "it made (shapes:) — build on those in the next script instead of probing with keys or pr-str.\n"
       "- Return the filtered data you need, not whole raw results — big output is truncated.\n"
       "- squint ≠ Clojure: maps are not functions (use get / :key), no js->clj, no array-seq; "
       "str/ and clojure.string/ work without a require."))

;;; ─── catalog ───────────────────────────────────────────────────

;;; ─── observed result shapes ────────────────────────────────────
;; Most MCP servers declare no outputSchema, so the model guessed fields. The
;; shape of the first successful result is kept per tool, keyed by a hash of
;; the tool's input schema so a server that changes drops the stale shape.

(defn- schema-hash [api tool]
  (str (js/Bun.hash (str (js/JSON.stringify (td/schema->json (some-> (.getTool api tool) .-inputSchema)))))))

(defn- store [api] (try (.-state api) (catch :default _ nil)))

(defn observed-shape [api tool]
  (when-let [st (store api)]
    (let [e (try (.get st (str "shape:" tool)) (catch :default _ nil))]
      (when (and e (= (.-hash e) (schema-hash api tool))) (.-schema e)))))

(defn- informative?
  "An inferred shape that says something: not `[]` or `{}`. A nullable
   field (`x?: any`) is fine — rejecting any `any` kept almost nothing."
  [sig]
  (not (contains? #{"any" "[any]" "{}" "[{}]"} sig)))

(defn- richness [sig] (count (re-seq #":" (str sig))))

(defn- observe!
  "Keep the shape of `value` for `tool`: the first informative one, or a
   richer one than what is kept (a first result whose fields were mostly
   null would otherwise stick)."
  [api tool value]
  (when-let [st (store api)]
    (let [inferred (schema/infer value)
          sig      (schema/signature inferred)
          kept     (observed-shape api tool)]
      (when (and (some? value) (not (some-> (.getTool api tool) .-nymaOutputSchema))
                 (informative? sig)
                 (or (nil? kept) (> (richness sig) (richness (schema/signature kept)))))
        (try (.set st (str "shape:" tool) #js {:hash (schema-hash api tool) :schema inferred})
             (catch :default _ nil))))))

;;; ─── catalog ───────────────────────────────────────────────────

(defn- entries [api patterns]
  (->> (vec (or (.getAllTools api) []))
       (remove #(= self-name %))
       (filter #(td/deferred? % patterns))
       (map (fn [n]
              (let [t    (.getTool api n)
                    decl (some-> t .-nymaOutputSchema)
                    seen (when-not decl (observed-shape api n))]
                {:name n
                 :description (some-> t .-description)
                 :schema (td/schema->json (some-> t .-inputSchema))
                 :output (or decl seen)
                 :observed? (some? seen)})))))

(defn- saved-block [api]
  (let [names   (vec (or (.getAllTools api) []))
        scripts (vals (saved/load-all))]
    (when (seq scripts)
      (str "\nsaved (" (count scripts) " scripts, call as (saved/<name> {args})):\n"
           (str/join "\n"
                     (map (fn [e]
                            (let [b (saved/broken e names)]
                              (str "  saved/" (:name e)
                                   (when (seq (:returns e)) (str " → " (:returns e)))
                                   (when (seq (:description e)) (str " — " (:description e)))
                                   (when (seq b) (str " [broken: missing " (str/join ", " b) "]")))))
                          (sort-by :name scripts)))))))

(defn prompt-block [api cfg]
  (let [es (entries api (conj (:catalog cfg) "codemode__*__*"))
        sv (saved-block api)]
    (when (or (seq es) sv)
      (str "\n\n## Script functions (codemode__script)\n"
           "Call these from a `codemode__script` script as (ns/fn {args}); `?` marks optional, "
           "`→` is what a call returns (`~` = seen in an earlier result). "
           "MCP tools run only from scripts here (mcp_call is off) — one script can search, "
           "fetch, filter and save in a single step.\n"
           (catalog/render es catalog/default-budget-chars)
           (or sv "")
           "\nPure helpers: (nyma/html->text s) (nyma/html->md s) (nyma/strip-quotes body) "
           "— an email body without quoted history or signature — (nyma/truncate s n)."
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
    ;; A top-level `def` compiles to `var x = …` plus `export { x }`; the
    ;; export is a syntax error inside the function body the worker builds,
    ;; and the first benchmark script that used `def` died on it.
    (-> ((.-compileString sq) code #js {:context "return" :elide-imports true})
        (.replace (js/RegExp. "^export \\{[^}]*\\};?\\s*$" "gm") ""))))

(defn- short-error
  "A worker error's message, bounded. A SyntaxError from the AsyncFunction
   constructor came back carrying the whole bundle's source."
  [msg]
  (let [m (str msg)
        m (if (> (count m) 400) (str (subs m 0 400) "…") m)]
    m))

(defn run-worker
  "Run compiled `body` with `bindings` {ns [fn]} → Promise of the worker's
   done message, plus :calls (the ledger) and :logs. `call!` runs one nested
   call: (call! ns fn args signal), where `signal` aborts when the script is
   stopped."
  [body bindings {:keys [timeout-ms max-calls signal label]} call!]
  (js/Promise.
   (fn [resolve _]
     (let [w      (js/Worker. (worker-url))
           calls  #js []
           ;; kept here, streamed line by line: a timed-out or killed worker
           ;; never sends `done`, and its printlns are what explain why
           logs   #js []
           done?  (atom false)
           inflight (js/Set.)
           stop   (js/AbortController.)
           timer  (atom nil)
           on-abort (atom nil)
           ;; `settle?`: let calls already in flight finish (≤5 s) so the
           ;; ledger reports what they did. A script that throws while 18
           ;; parallel fetches run would otherwise report them "running"
           ;; though every one completed — and a retry would repeat them.
           ;; Timeout and Esc do not wait: stopping is the point.
           finish (fn [m settle?]
                    (when-not @done?
                      (reset! done? true)
                      (.terminate w)
                      (js/clearTimeout @timer)
                      (when signal (.removeEventListener signal "abort" @on-abort))
                      ;; Timeout/Esc: calls still in flight are told to stop
                      (when-not settle? (.abort stop))
                      (aset m "calls" calls)
                      (aset m "logs" logs)
                      (if (and settle? (pos? (.-size inflight)))
                        (-> (js/Promise.race [(js/Promise.allSettled (js/Array.from inflight))
                                              (js/Promise. (fn [r] (js/setTimeout r 5000)))])
                            (.then #(resolve m)))
                        (resolve m))))
           fail   (fn [kind msg]
                    (finish #js {:ok false :error #js {:kind kind :message (short-error msg)}} false))]
       (reset! timer (js/setTimeout #(fail "Timeout" (str "script exceeded " timeout-ms " ms")) timeout-ms))
       (reset! on-abort #(fail "Aborted" "interrupted"))
       (when signal
         (if (.-aborted signal)
           (@on-abort)
           (.addEventListener signal "abort" @on-abort #js {:once true})))
       (set! (.-onerror w) (fn [e] (fail "RuntimeError" (str (or (.-message e) e)))))
       (set! (.-onmessage w)
             ^:async (fn [ev]
                       (let [m (.-data ev)]
                         (case (.-type m)
                           "log"  (.push logs (.-line m))
                           "done" (do (when-not (.-ok m)
                                        (aset (.-error m) "message" (short-error (.. m -error -message))))
                                      (finish m true))
                           "call"
                           (let [entry #js {:tool ((or label str) (.-ns m) (.-fn m)) :status "running"}]
                             (.push calls entry)
                             (if (> (.-length calls) max-calls)
                               (do (aset entry "status" "error")
                                   (.postMessage w #js {:type "reply" :id (.-id m) :ok false :kind "CallLimit"
                                                        :ns (.-ns m) :fn (.-fn m) :label (.-tool entry)
                                                        :error (str "more than " max-calls " tool calls")}))
                               (let [p (call! (.-ns m) (.-fn m) (.-args m) (.-signal stop))
                                     _ (.add inflight p)
                                     r (js-await p)
                                     _ (.delete inflight p)]
                                 (aset entry "status" (if (.-ok r) "ok" (if (.-denied r) "denied" "error")))
                                 (when (.-shape r) (aset entry "shape" (.-shape r)) (aset entry "hint" (.-hint r)))
                                 (when-not @done?
                                   (.postMessage w #js {:type "reply" :id (.-id m) :ok (.-ok r)
                                                        :ns (.-ns m) :fn (.-fn m) :label (.-tool entry)
                                                        :kind (if (.-denied r) "Denied" "ToolFailure")
                                                        :result (maybe-json (.-result r))
                                                        :error (.-error r)})))))
                           nil))))
       (when-not @done?
         (.postMessage w #js {:type "run" :body body :bindings (clj->js bindings)}))))))

(defn mcp-error
  "The MCP bridge reports a server error as text, not as a failed call; only
   an MCP tool's result means that — a log file read by `nyma/read` may well
   start with `[ERROR] `."
  [tool r]
  (if (and (str/starts-with? (str tool) "mcp__") (.-ok r) (string? (.-result r))
           (.startsWith (.-result r) "[ERROR] "))
    #js {:ok false :error (subs (.-result r) 8)}
    r))

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
        ;; what each tool returned, once per tool, read now: the catalog only
        ;; learns observed shapes at the next prompt
        shapes (let [seen (atom #{})
                     ls   (keep (fn [c]
                                  (when (and (.-shape c) (not (contains? @seen (.-tool c))))
                                    (swap! seen conj (.-tool c))
                                    (str "  " (.-tool c) " → " (.-shape c)
                                         (when (.-hint c) (str "  [" (.-hint c) "]")))))
                                calls)]
                 (when (seq ls) (str "shapes (observed):\n" (str/join "\n" ls))))
        body   (if (.-ok m)
                 (let [v (.-value m)]
                   (cond (string? v) (if (seq v) v "\"\" (empty string)")
                         (nil? v)    "nil"
                         :else       (js/JSON.stringify v nil 1)))
                 (let [e (.-error m)]
                   (str "[" (.-kind e) "] " (.-message e)
                        (when (.-tool e) (str " (in " (.-tool e) ")"))
                        ;; two benchmark scripts bound a call without js-await
                        ;; and then iterated the promise
                        (when (.includes (str (.-message e)) "[object Promise]")
                          " — a tool call returns a promise: (js-await (ns/fn …)) before using its value"))))
        body   (if (> (count body) max-chars)
                 (let [f (spill! body)
                       v (when (.-ok m) (.-value m))
                       tail (str "…[truncated: " (count body) " chars"
                                 (when f (str "; full result in " f)) "]")]
                   (if (array? v)
                     ;; structure and one example beat a cut prefix: the model
                     ;; sees what it has, and filters in the next script
                     (str "count: " (.-length v) "\nshape: " (schema/signature (schema/infer v))
                          "\nfirst: " (let [s (js/JSON.stringify (aget v 0) nil 1)]
                                         (if (> (count (str s)) 2000) (str (subs s 0 2000) "…") s))
                          "\n" tail)
                     (str (subs body 0 max-chars) "\n" tail)))
                 body)]
    (str/join "\n" (cond-> [body]
                     (seq logs) (conj (str "logs:\n" (str/join "\n" (take 50 logs))))
                     ledger     (conj ledger)
                     shapes     (conj shapes)))))

(def last-ok
  "The last script that finished ok — what `/codemode save` keeps."
  (atom nil))

(defn prepare
  "The script with the saved scripts it calls expanded in, analysed.
   → {:code :ex (saved/expand) :result (analyze of the expanded source)}"
  [api code]
  (let [names (remove #(= self-name %) (vec (or (.getAllTools api) [])))
        ex    (saved/expand code (saved/load-all))]
    {:code code :ex ex :result (an/analyze (:source ex) names)}))

(defn approval [{:keys [ex result]}]
  (str (when (seq (:used ex))
         (str (str/join " · " (map #(str "saved/" %) (:used ex))) " → "))
       (an/approval-line result mw/categorize-tool)))

(defn ^:async execute-script [api cfg args ext-ctx]
  (let [code   (str (or (aget args "code") ""))
        {:keys [ex result] :as p} (prepare api code)
        approved (aget args "calls")]
    (cond
      (str/blank? code) "[ParseError] empty script"
      ;; prepareArguments wrote `calls` for the prompt; a saved script or the
      ;; tool registry that changed since must not bind what was not shown
      (and (string? approved) (not= approved (approval p)))
      (str "[Denied] what this script calls changed after it was approved (now: " (approval p)
           ") — run it again to approve that")
      (seq (:missing ex)) (str "[UnknownTool] no saved script "
                               (str/join ", " (map #(str "saved/" %) (:missing ex)))
                               " — saved: " (let [ns (sort (keys (saved/load-all)))] (if (seq ns) (str/join ", " ns) "none")))
      (seq (:nested ex)) (str "[UnknownTool] saved scripts cannot call saved scripts: "
                              (str/join ", " (:nested ex)))
      (seq (:traps result)) (str "[TrapLint] " (str/join "; " (:traps result)))
      (seq (:unknown result)) (str "[UnknownTool] " (did-you-mean api (:unknown result)))
      :else
      (let [compiled (try (js-await (compile-script (an/asyncify (:source ex))))
                          (catch :default e {:error (str (or (.-message e) e))}))]
        (if (:error compiled)
          (str "[ParseError] " (:error compiled)
               (when-let [r (an/paren-report code)] (str " — " r)))
          (let [by-key   (into {} (map (fn [t] [(str/join "/" (an/binding-key t)) t]) (:tools result)))
                bindings (reduce (fn [acc t]
                                   (let [[n f] (an/binding-key t)]
                                     (update acc n (fnil conj []) f)))
                                 {} (:tools result))
                call!    (fn [n f a signal]
                           (let [t (get by-key (str n "/" f))]
                             (-> (.executeTool api t a #js {:preapproved (clj->js (:tools result)) :signal signal})
                                 (.then #(mcp-error t %))
                                 (.then (fn [r]
                                          (when (.-ok r)
                                            (let [v (maybe-json (.-result r))]
                                              (observe! api t v)
                                              ;; the shape this call returned, for the result's
                                              ;; shapes: line — unless the tool declares one
                                              (when (and (some? v) (not (some-> (.getTool api t) .-nymaOutputSchema)))
                                                (let [s   (schema/infer v)
                                                      sig (schema/signature s)]
                                                  ;; a string result has no fields to learn
                                                  (when (and (informative? sig) (not= "str" sig))
                                                    (aset r "shape" sig)
                                                    (aset r "hint" (catalog/html-hint s)))))))
                                          r)))))
                m        (js-await (run-worker compiled bindings
                                               (assoc cfg
                                                      :signal (some-> ext-ctx .-abortSignal)
                                                      :label  (fn [n f] (an/display-name (get by-key (str n "/" f)))))
                                               call!))]
            (when (.-ok m)
              (reset! last-ok {:code code :tools (:tools result) :saved (:used ex)
                               :returns (schema/signature (schema/infer (.-value m)))}))
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
         (js/Object.assign #js {} args
                           #js {:calls (approval (prepare api (str (or (aget args "code") ""))))}))
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
                cfg  {:base-url (or (aget c "base-url") (openapi/server-url spec (aget c "spec")))
                      :headers  (aget c "headers")}]
            (doseq [op (openapi/operations spec)]
              (let [n (str k "__" (:name op))]
                (.registerTool api n (openapi/tool-def cfg op))
                (swap! registered conj n))))
          (catch :default e
            (d/warn (str "[codemode] API " k ": " (.-message e)))))))
    @registered))

(defn direct-allowed
  "The tools still offered directly, as `tool_access_check` wants them.
   API operations are for scripts — listing each is the schema bloat code mode
   removes. `mcp_call` goes too: with it on offer a benchmark model called MCP
   one tool per step and wrote no script; `mcp_search` stays for discovery.
   `script-only` patterns withhold more, for the same reason: a small
   server's tools are offered directly and got called one per step. Pure."
  [tools cfg]
  (vec (remove #(or (re-find #"^codemode__.+__" %)
                    (= "mcp-client__mcp_call" %)
                    (td/deferred? % (:script-only cfg)))
               tools)))

(defn command
  "`/codemode save <name> [--global] [description…]` | `scripts` | `rm <name>`.
   Returns the receipt text; `notify` shows it."
  [api args]
  (let [[sub nm & more] (map str (or args []))
        global? (some #(= "--global" %) more)
        desc    (str/join " " (remove #(= "--global" %) more))
        scripts (saved/load-all)]
    (cond
      (= sub "save")
      (let [last @last-ok]
        (cond
          (not (and nm (re-find saved/name-re nm))) "Usage: /codemode save <name> [--global] [description] — name: lowercase, digits, -"
          (nil? last) "No script has run ok in this session yet."
          (seq (:saved last)) "That script calls saved scripts; saved scripts cannot call each other."
          :else (str "Saved saved/" nm " to "
                     (saved/save! (if global? :global :project) nm
                                  {:description desc :tools (:tools last) :returns (:returns last) :body (:code last)})
                     (when-not global? (when-not (trust/trusted?) " (this folder is untrusted: project scripts load once you trust it)")))))
      (= sub "rm")
      (if-let [e (get scripts nm)]
        (do (fs/unlinkSync (:path e)) (str "Removed saved/" nm " (" (:path e) ")"))
        (str "No saved script " nm))
      (or (nil? sub) (= sub "scripts"))
      (if (seq scripts)
        (str/join "\n" (map (fn [e] (str "saved/" (:name e) "  [" (name (:scope e)) "]  " (:description e)
                                          (let [b (saved/broken e (vec (or (.getAllTools api) [])))]
                                            (when (seq b) (str "  [broken: missing " (str/join ", " b) "]")))))
                             (sort-by :name (vals scripts))))
        "No saved scripts. After a script runs ok: /codemode save <name> [description]")
      :else
      (str "Unknown subcommand " sub ". Usage: /codemode save <name> | scripts | rm <name>"))))

(defn ^:export activate [api]
  (let [cfg (config (.settings api "codemode"))]
    (if-not (:enabled cfg)
      (fn [] nil)
      (let [apis (atom [])
            on-access (fn [ev] #js {:allowed (clj->js (direct-allowed (vec (.-tools ev)) cfg))})
            on-start  (fn [_ev]
                        (when-let [b (prompt-block api cfg)]
                          #js {"system-prompt-additions" #js [b]}))]
        (.registerTool api "script" (script-tool api cfg))
        (doseq [[n t] files/tools] (.registerTool api n t))
        (.registerCommand api "codemode"
                          #js {:description "Saved code-mode scripts: /codemode save <name> [--global] [description] | scripts | rm <name>"
                               :handler (fn [args ctx]
                                          (let [msg (try (command api args) (catch :default e (str "codemode: " (.-message e))))]
                                            (when-let [ui (some-> ctx .-ui)] (when (.-notify ui) (.notify ui msg "info")))
                                            msg))})
        (-> (register-apis! api (:apis cfg)) (.then #(reset! apis %)))
        (.on api "tool_access_check" on-access 50)
        (.on api "before_agent_start" on-start)
        (fn []
          (.off api "tool_access_check" on-access)
          (.off api "before_agent_start" on-start)
          (doseq [n @apis] (.unregisterTool api n))
          (doseq [[n _] files/tools] (.unregisterTool api n))
          (.unregisterCommand api "codemode")
          (.unregisterTool api "script"))))))

(def ^:export default activate)
