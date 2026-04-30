(ns agent.extensions.claude-hook-bridge.dispatch
  "Wire-up between (event, payload) → matched hooks → invocations →
   merged response. Owns:

   - Selecting the entries in the event's hook list whose `matcher`
     matches the inbound discriminator (tool name, source, type, etc).
   - Invoking each entry's handlers in order, sequentially. Sequential
     because CC's precedence rules (deny > defer > ask > allow) and
     additionalContext concatenation depend on a deterministic order.
   - Routing each handler entry to the right handler module (command
     today; http / mcp_tool / prompt / agent in later phases).
   - Threading abort-signal and timeout into each invocation.

   This is the only module that knows about the full set of hook
   handler `:type`s — adding a new handler type means one new branch
   here and one new module under `handlers/`."
  (:require [agent.extensions.claude-hook-bridge.matcher :as matcher]
            [agent.extensions.claude-hook-bridge.response :as response]
            [agent.extensions.claude-hook-bridge.audit :as audit]
            [agent.extensions.claude-hook-bridge.handlers.command :as cmd]
            [agent.extensions.claude-hook-bridge.handlers.http :as http]
            [agent.extensions.claude-hook-bridge.handlers.prompt :as prompt]
            [agent.extensions.claude-hook-bridge.handlers.mcp-tool :as mcp-tool]))

(defn- matchers-fire?
  "Decide whether a hook block fires for this discriminator. The block
   may have :matcher (CC) or be unmatched (always fires)."
  [block discriminator]
  (let [m (or (.-matcher block) "")]
    (matcher/matches? m discriminator)))

(defn- hook-spec->result
  "Dispatch a single hook spec to the right handler module. Logs the
   first execution of each unique (event, command) pair via audit/note!"
  [spec stdin-json event-name {:keys [abort-signal cwd api]}]
  (let [t (str (or (.-type spec) "command"))]
    (case t
      "command"
      (do
        (audit/note! event-name (str "command:" (.-command spec)))
        (cmd/run-command
         {:command      (.-command spec)
          :timeout-ms   (when-let [tt (.-timeout spec)] (* tt 1000))
          :shell        (.-shell spec)
          :cwd          cwd
          :abort-signal abort-signal
          :stdin-json   stdin-json}))

      "http"
      (do
        (audit/note! event-name (str "http:" (.-url spec)))
        (http/run-http
         {:url          (.-url spec)
          :headers      (.-headers spec)
          :allowed-env  (.-allowedEnvVars spec)
          :timeout-ms   (when-let [tt (.-timeout spec)] (* tt 1000))
          :stdin-json   stdin-json
          :abort-signal abort-signal}))

      "prompt"
      (prompt/run-prompt
       {:api          api
        :prompt       (.-prompt spec)
        :model        (.-model spec)
        :timeout-ms   (when-let [tt (.-timeout spec)] (* tt 1000))
        :stdin-json   stdin-json
        :abort-signal abort-signal})

      "mcp_tool"
      (mcp-tool/run-mcp-tool
       {:server       (.-server spec)
        :tool         (.-tool spec)
        :input        (.-input spec)
        :stdin-json   stdin-json
        :abort-signal abort-signal})

      ;; Unknown / not-implemented types still degrade non-blocking.
      (js/Promise.resolve
       {:exit-code 1
        :stdout    ""
        :stderr    (str "[hook-bridge] handler type '" t "' not implemented")
        :timed-out? false
        :aborted?   false}))))

(defn ^:async dispatch
  "Run all matching hooks for an event and return the merged response.

   Args:
     :hooks-map     — normalized config map, {EventName -> [block ...]}
     :event-name    — CC event name, e.g. \"PreToolUse\"
     :discriminator — string used by matchers (tool name, source, etc)
     :stdin-payload — JS object/CLJS map sent to each hook's stdin
     :abort-signal  — AbortSignal for the in-flight agent turn
     :cwd           — working directory passed to subprocesses

   Returns the merged response map (see response/merge-many) or nil if
   no hooks matched."
  [{:keys [hooks-map event-name discriminator stdin-payload abort-signal cwd api]}]
  (let [blocks   (get hooks-map event-name [])
        matched  (filterv #(matchers-fire? % discriminator) blocks)]
    (when (seq matched)
      (let [parsed (atom [])]
        ;; Run each matched block, then each hook spec inside, in order.
        (doseq [block matched]
          (let [hook-arr (or (.-hooks block) #js [])]
            (doseq [i (range (.-length hook-arr))]
              (let [spec   (aget hook-arr i)
                    raw    (js-await
                            (hook-spec->result spec stdin-payload event-name
                                               {:abort-signal abort-signal
                                                :cwd          cwd
                                                :api          api}))
                    parsed-r (response/parse-one raw)]
                (when parsed-r
                  (swap! parsed conj parsed-r))))))
        (response/merge-many @parsed)))))
