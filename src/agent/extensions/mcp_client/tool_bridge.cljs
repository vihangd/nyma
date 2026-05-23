(ns agent.extensions.mcp-client.tool-bridge
  "Translate each connected MCP server's `tools/list` into nyma
   tool definitions and register them on the extension api.

   Naming follows the canonical CC pattern (`mcp__<server>__<tool>`)
   so matchers in hook configs and the tool-name renderer pick them
   up the same way they do MCP tools coming from a Claude Code
   subprocess.

   The tool's `:execute` is a thin shim that delegates to
   `client/call-tool!`. Result shape: MCP returns `{content: [...]}`
   where each item has `{type, text}`; we flatten to a single
   string for the LLM. If `isError` is true, we surface the text
   prefixed with `[ERROR] ` so the LLM can react.

   `:inputSchema` is wrapped via ai-sdk's `jsonSchema` helper so the
   LLM sees the server's JSON Schema verbatim (no zod conversion
   needed in either direction)."
  (:require ["ai" :refer [tool jsonSchema]]
            [clojure.string :as str]
            [agent.extensions.mcp-client.client :as client]
            [agent.extensions.mcp-client.manager :as mgr]))

(defn nyma-tool-name
  "Translate a (server, tool) pair to the canonical CC-shaped name."
  [server-name tool-name]
  (str "mcp__" server-name "__" tool-name))

(defn- flatten-content
  "Reduce MCP result.content (array of {:type :text :raw}) to a
   single string. Most servers emit one text block; some emit
   several. We join with newlines and ignore non-text blocks for
   now (resources / images would need additional plumbing)."
  [content-vec]
  (->> (or content-vec [])
       (filter (fn [item] (= "text" (:type item))))
       (map :text)
       (filter some?)
       (str/join "\n")))

(defn- build-execute-fn
  "Build an :execute function for the AI SDK tool wrapper.
   Captures the client + server tool name and converts the
   result to a string."
  [cli tool-name]
  (^:async fn [args]
    (let [r (js-await (client/call-tool!
                       cli
                       {:tool-name tool-name
                        :arguments (clj->js args)}))
          text (flatten-content (:content r))]
      (if (:is-error? r)
        (str "[ERROR] " text)
        text))))

(defn- build-tool-def
  "Convert a single MCP `{:name :description :input-schema}` tool
   to an AI-SDK `tool(...)` def, with a server-aware execute fn.
   Param `cli` (not `client`) avoids shadowing the namespace alias."
  [server-name cli mcp-tool]
  (let [base-desc (or (:description mcp-tool) "")
        desc      (str base-desc
                       (when (seq base-desc) " ")
                       "[via MCP server: " server-name "]")
        input-schema (or (:input-schema mcp-tool)
                         #js {:type "object" :properties #js {}})]
    (tool
     #js {:description desc
          :inputSchema (jsonSchema input-schema)
          :execute (build-execute-fn cli (:name mcp-tool))})))

(defn register-all!
  "Walk the manager's running clients, register each of their tools
   on the api. Returns the vector of registered tool names so the
   caller can pass it to unregister-all! later.

   Servers that aren't :running are skipped — their tools simply
   appear later when restart succeeds (which triggers
   `tool_bridge.refresh!` from index.cljs)."
  [api manager]
  (let [registered (atom [])]
    ;; Destructure key renamed to :cli to avoid shadowing the
    ;; `client` namespace alias (squint compiles `client/state` in
    ;; the body to a free `client` lookup that the local would
    ;; otherwise mask).
    (doseq [pair (mgr/all-clients manager)]
      (let [name (:name pair)
            cli  (:client pair)]
        (when (= :running (client/state cli))
          (doseq [t (client/list-tools cli)]
            (let [full-name (nyma-tool-name name (:name t))
                  tool-def  (build-tool-def name cli t)]
              ;; Use overrideTool (unprefixed) so the registered name
              ;; stays the canonical CC-shape `mcp__server__tool`
              ;; rather than `mcp-client__mcp__server__tool`. The
              ;; double-prefix burns tool-block tokens with no benefit
              ;; — `mcp__` already marks these as MCP-sourced.
              (.overrideTool api full-name tool-def)
              (swap! registered conj full-name))))))
    @registered))

(defn unregister-all!
  "Best-effort unregister of every name in `names`. Safe to call
   with names that aren't currently registered. Mirrors
   register-all!'s use of overrideTool — names are unprefixed."
  [api names]
  (doseq [n (or names [])]
    (try (.unoverrideTool api n)
         (catch :default _e nil))))
