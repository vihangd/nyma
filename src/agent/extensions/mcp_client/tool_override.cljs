(ns agent.extensions.mcp-client.tool-override
  "Override pattern for native nyma tools that have a smarter MCP
   replacement. Unlike the shadow-set approach (which hides the
   native), this keeps the same tool name visible to the LLM but
   delegates the call to the MCP server when it's healthy, and
   falls back to the native execute via `__original` chaining when
   the server is down or unhealthy.

   Resilience: a flaky lean-ctx subprocess can disconnect mid-session
   and the LLM can still complete `read` / `edit` calls — they just
   degrade to native behavior until the server comes back.

   Tools overridden by default:
     read  → mcp__lean-ctx__ctx_read
     edit  → mcp__lean-ctx__ctx_edit

   Native arg shape is preserved (LLM sees the same schema), so the
   override is invisible from the model's perspective. Translation
   to the MCP server's argument shape happens inside the wrapper."
  (:require ["ai" :refer [jsonSchema]]
            ["zod" :as z]
            [agent.extensions.mcp-client.client :as client]
            [agent.extensions.mcp-client.manager :as mgr]))

;; ── Health probe ─────────────────────────────────────────────────

(defn- mcp-tool-healthy?
  "True iff the manager has a server `server-name` in :running state.
   Cheap synchronous read of the client's state atom — safe to call
   on every tool invocation."
  [manager-ref server-name]
  (let [m (and manager-ref @manager-ref)]
    (when m
      (when-let [c (mgr/get-client m server-name)]
        (= :running (client/state c))))))

;; ── Argument translation ─────────────────────────────────────────
;;
;; Translators map the native nyma tool's arg shape onto the lean-ctx
;; ctx_* tool's arg shape. Earlier versions referenced `file_path`,
;; which doesn't exist on the native schema (`tools.cljs:17` uses
;; `path` + `range`); the result was a `{path: undefined}` request to
;; ctx_read, an MCP error, and a silent fallback to native every time.
;; The wrapper looked installed but was never actually exercising
;; lean-ctx, which is why the model preferred calling
;; `mcp__lean-ctx__ctx_read` directly (it could pass `fresh: true`
;; that way).

(defn- read-args->ctx
  "Translate native `read` args to ctx_read args.
   Native:  {path, range?, fresh?, mode?}
   ctx_read: {path, mode?, fresh?}
   `range` (a 2-element array) collapses into ctx_read's
   `mode: \"lines:N-M\"`. An explicit `mode` from the caller wins."
  [args]
  (let [path     (.-path args)
        range    (.-range args)
        fresh    (.-fresh args)
        explicit (.-mode args)
        out      #js {:path path}]
    (cond
      explicit             (aset out "mode" explicit)
      (and range
           (>= (.-length range) 2))
      (aset out "mode"
            (str "lines:" (aget range 0) "-" (aget range 1))))
    (when (some? fresh) (aset out "fresh" fresh))
    out))

(defn- edit-args->ctx
  "Translate native `edit` args to ctx_edit args. Native and MCP shapes
   are identical except for the historic `file_path`/`path` mismatch
   (which we just fixed)."
  [args]
  (let [out #js {:path       (.-path args)
                 :old_string (.-old_string args)
                 :new_string (.-new_string args)}]
    (when (some? (.-replace_all args))
      (aset out "replace_all" (.-replace_all args)))
    out))

;; ── Wrapper schemas ──────────────────────────────────────────────
;;
;; When the override is installed we replace the native schema with
;; one that exposes lean-ctx's bypass parameters (`fresh`, `mode`) so
;; the model can ask for cache-bypass through the regular tool name.
;; Without this the model has no way to invalidate lean-ctx's cache
;; via `read` and reaches for the unwrapped mcp__lean-ctx__ctx_read
;; instead — the exact loop the override was supposed to prevent.

(defn- read-override-schema []
  (.object z
           #js {:path  (-> (.string z)
                           (.describe "File path to read"))
                :range (-> (.array z (.number z))
                           (.length 2)
                           (.optional)
                           (.describe "Line range [start, end]"))
                :fresh (-> (.boolean z)
                           (.optional)
                           (.describe (str "Bypass lean-ctx's content cache "
                                           "and force a fresh read. Use when "
                                           "the file changed or you need full "
                                           "fidelity.")))
                :mode  (-> (.string z)
                           (.optional)
                           (.describe (str "Optional lean-ctx read mode "
                                           "(e.g. \"signatures\", \"map\"). "
                                           "Leave unset for auto.")))}))

(defn- edit-override-schema []
  (.object z
           #js {:path        (.string z)
                :old_string  (.string z)
                :new_string  (.string z)
                :replace_all (-> (.boolean z) (.optional))}))

;; ── Result normalization ─────────────────────────────────────────

(defn- ctx-result->string
  "Flatten ctx_*'s {:content [{:type :text :text}] :is-error?} to a
   string the LLM can consume. Mirrors tool_bridge's flattener."
  [r]
  (let [content (or (:content r) [])
        text    (->> content
                     (map (fn [c]
                            (cond
                              (string? c) c
                              (and c (.-text c)) (.-text c)
                              :else "")))
                     (apply str))]
    (if (:is-error? r)
      (str "[ERROR via lean-ctx] " text)
      text)))

;; ── Override factory ─────────────────────────────────────────────

(defn- annotate-description
  "Append a route hint so the model knows the wrapped tool delegates
   to an MCP server. Without this annotation the model treats
   `read` and `mcp__lean-ctx__ctx_read` as independent tools and
   experiments by switching between them."
  [native-desc server-name mcp-tool]
  (let [base (str (or native-desc ""))
        hint (str " (Routes through MCP server `" server-name
                  "` tool `" mcp-tool "` when healthy; falls back to "
                  "native fs on outage. Pass `fresh: true` to bypass "
                  "the server's content cache.)")]
    (if (.includes base hint) base (str base hint))))

(defn- build-override
  "Construct a tool def whose execute delegates to the MCP server
   when healthy, else calls __original.execute(args).

   `native-tool` is the existing tool from the registry (used as
   display source). `manager-ref` is the atom holding the manager.
   `server-name`/`mcp-tool` identify the delegate target.
   `args-translate` is a fn from native args to MCP args.
   `wrapper-schema` overrides the native schema so we can expose
   lean-ctx-specific parameters like `fresh` to the LLM."
  [native-tool manager-ref server-name mcp-tool args-translate wrapper-schema]
  (let [td #js {:description (annotate-description
                              (.-description native-tool)
                              server-name mcp-tool)
                :inputSchema (or wrapper-schema
                                 (.-inputSchema native-tool)
                                 (.-parameters native-tool))
                :display     (.-display native-tool)}]
    (set! (.-execute td)
          (^:async fn [args]
            (try
              (if (mcp-tool-healthy? manager-ref server-name)
                ;; Healthy: route through MCP. Failures fall through
                ;; to native — never let a transient MCP error bubble
                ;; up if the native path can serve the call.
                (try
                  (let [m       @manager-ref
                        c       (mgr/get-client m server-name)
                        ctx-args (args-translate args)
                        result  (js-await
                                 (client/call-tool!
                                  c
                                  {:tool-name mcp-tool
                                   :arguments ctx-args}))]
                    (ctx-result->string result))
                  (catch :default _e
                    ((.-execute (.-__original td)) args)))
                ;; Unhealthy: native fallback.
                ((.-execute (.-__original td)) args))
              (catch :default e
                ;; Last-resort: surface error rather than swallow.
                (str "[ERROR] " (or (.-message e) (str e)))))))
    td))

;; ── Public surface ───────────────────────────────────────────────

(def default-overrides
  "Map of {native-name → {:server :mcp-tool :translate :schema}}. Users
   can disable individually via settings. `:schema` is a 0-arg fn
   building a zod object — kept lazy so we don't allocate it at
   namespace load when the override may never be used."
  {"read" {:server    "lean-ctx"
           :mcp-tool  "ctx_read"
           :translate read-args->ctx
           :schema    read-override-schema}
   "edit" {:server    "lean-ctx"
           :mcp-tool  "ctx_edit"
           :translate edit-args->ctx
           :schema    edit-override-schema}})

(defn parse-overrides
  "Settings parser:
     nil → defaults
     false → disabled
     object → merged on top of defaults; null value drops a key"
  [user-val]
  (cond
    (false? user-val) {}
    (nil? user-val)   default-overrides
    (object? user-val)
    (let [merged (atom default-overrides)]
      (doseq [k (js/Object.keys user-val)]
        (let [v (aget user-val k)]
          (if (nil? v)
            (swap! merged dissoc k)
            ;; Allow user to point a native at a different server/tool
            (swap! merged assoc k
                   {:server    (or (.-server v) "lean-ctx")
                    :mcp-tool  (.-mcp-tool v)
                    ;; No custom translator/schema support yet —
                    ;; overrides that need them should be added to
                    ;; default-overrides
                    :translate (or (get-in @merged [k :translate])
                                   (fn [a] a))
                    :schema    (get-in @merged [k :schema])}))))
      @merged)
    :else default-overrides))

(defn- registered-servers
  "Return a set of MCP server names actually known to the manager.
   Used to gate override installation: there's no point modifying the
   native `read` schema/description if lean-ctx isn't even configured."
  [manager-ref]
  (let [m (and manager-ref @manager-ref)]
    (if m (set (mgr/server-names m)) #{})))

(defn register!
  "Register override tools on `api`. For each native name present in
   the registry AND whose target server is registered with the
   manager, register a delegating tool with the same name (no
   namespace prefix) via `api.overrideTool`, and hide the underlying
   `mcp__<server>__<tool>` from the active set so the model doesn't
   see two paths to the same effect.

   Gating on server presence keeps the override invisible when
   lean-ctx isn't configured — the user keeps the native `read`
   description and schema unchanged.

   Requires the extension manifest to declare the `tools-override`
   capability. Returns
     {:overrides [<native-name>...]
      :hidden    [<mcp-prefixed-name>...]}
   so the caller can reverse both on disposal."
  [api manager-ref overrides]
  (let [all-tools  (.getAllTools api)
        present?   (set (js/Array.from all-tools))
        servers    (registered-servers manager-ref)
        applied    (atom [])
        hidden     (atom [])]
    (when-not (.-overrideTool api)
      (throw (js/Error. "[mcp-client/tool-override] api.overrideTool is missing — declare 'tools-override' capability in extension.json")))
    (doseq [[native {:keys [server mcp-tool translate schema]}] overrides]
      (cond
        (not (contains? present? native))
        nil  ; no native tool to wrap

        (not (contains? servers server))
        nil  ; target server not configured — leave native untouched

        :else
        (let [stub #js {:description ""
                        :inputSchema #js {:type "object" :properties #js {}}
                        :execute (fn [_] nil)}]
          (.overrideTool api native stub)
          (let [native-tool (.-__original stub)]
            (if (some? native-tool)
              (let [wrapper-schema (when schema (schema))
                    override (build-override native-tool manager-ref
                                             server mcp-tool translate
                                             wrapper-schema)]
                (.overrideTool api native override)
                (swap! applied conj native)
                ;; Hide the underlying MCP tool from the active set so
                ;; the model can't bypass the wrapper. The wrapper still
                ;; calls it internally via the manager.
                (let [mcp-name (str "mcp__" server "__" mcp-tool)
                      active   (set (js/Array.from (.getActiveTools api)))]
                  (when (contains? active mcp-name)
                    (.setActiveTools api
                                     (clj->js (vec (disj active mcp-name))))
                    (swap! hidden conj mcp-name))))
              ;; No native to wrap — restore by removing our stub.
              (try (.unoverrideTool api native)
                   (catch :default _e nil)))))))
    {:overrides @applied
     :hidden    @hidden}))

(defn unregister!
  "Restore native tools by unregistering the overrides AND restore the
   underlying MCP tools to the active set. Accepts either the legacy
   shape (vec of native names) or the new shape returned by
   `register!`: `{:overrides [...], :hidden [...]}`."
  [api applied]
  (let [{:keys [overrides hidden]}
        (cond
          (map? applied)        applied
          (sequential? applied) {:overrides (vec applied) :hidden []}
          :else                 {:overrides [] :hidden []})]
    (doseq [n overrides]
      (try (.unoverrideTool api n)
           (catch :default _e nil)))
    (when (seq hidden)
      (let [active (set (js/Array.from (.getActiveTools api)))
            restored (apply conj active hidden)]
        (try (.setActiveTools api (clj->js (vec restored)))
             (catch :default _e nil))))))
