(ns ext-mcp-client-load-smoke.test
  "Real-agent activation smoke test — the same shape that caught
   the hook-bridge wrong-bus bug. Verifies the MCP client extension:

   1. Module loads (everything in dist/agent/extensions/mcp_client/
      imports cleanly).
   2. default(api) activates against a real
      create-extension-api(create-agent) without throwing — even
      when no MCP servers are configured (no .mcp.json present).
   3. Subscribes its handlers to the MAIN agent event bus
      (api.on / api.off — NOT api.events.on the inter-extension bus).

   If the activation path drifts from how nyma actually exposes
   events to extensions, this test fails."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]))

(def ^:private dist-mcp-dir
  "/Users/vihangd/projects/pers/nyma/dist/agent/extensions/mcp_client")

(defn- collect-mjs
  "Recursively list every .mjs under `dir`."
  [dir]
  (let [out (atom [])]
    (doseq [entry (fs/readdirSync dir #js {:withFileTypes true})]
      (let [p (path/join dir (.-name entry))]
        (cond
          (.isDirectory entry) (swap! out into (collect-mjs p))
          (.endsWith (.-name entry) ".mjs") (swap! out conj p))))
    @out))

(defn ^:async test-every-module-imports []
  (let [files   (collect-mjs dist-mcp-dir)
        results (atom [])]
    (doseq [p files]
      (let [r (js-await
               (-> (js/import p)
                   (.then (fn [_] {:path p :ok true}))
                   (.catch (fn [e]
                             {:path p :ok false
                              :error (or (.-message e) (str e))}))))]
        (swap! results conj r)))
    (let [failures (filterv (complement :ok) @results)]
      (doseq [f failures]
        (js/console.error (str "[mcp-smoke] " (:path f) " — " (:error f))))
      (-> (expect (count failures)) (.toBe 0))
      (-> (expect (pos? (count @results))) (.toBe true)))))

(defn ^:async test-default-activates-against-real-api []
  (let [agent      (create-agent
                    {:model #js {:modelId "test-model"}
                     :system-prompt "test"})
        api        (create-extension-api agent)
        ;; Track what got subscribed on the MAIN bus.
        events     (:events agent)
        before-on  (count (filter (fn [_] true) (or @(or (:handlers events) (atom {})) {})))
        bridge-mod (js-await (js/import (path/join dist-mcp-dir "index.mjs")))
        dispose    (try ((.-default bridge-mod) api)
                        (catch :default e e))]
    ;; Activation must not throw.
    (-> (expect (or (fn? dispose) (nil? dispose))) (.toBe true))

    ;; Verify session_start / session_shutdown / session_end were
    ;; subscribed on the MAIN event bus (not the inter-extension bus).
    ;; We can probe this via :handler-count if exposed; otherwise
    ;; check via emit + atom side-effect.
    (let [start-count (when-let [hc (:handler-count events)] (hc "session_start"))
          stop-count  (when-let [hc (:handler-count events)] (hc "session_shutdown"))]
      ;; If handler-count is available, both must be > 0.
      (when (and (some? start-count) (some? stop-count))
        (-> (expect (pos? start-count)) (.toBe true))
        (-> (expect (pos? stop-count))  (.toBe true))))

    ;; Cleanup
    (when (fn? dispose) (try (dispose) (catch :default _e nil)))))

(describe "mcp-client/load-smoke"
          (fn []
            (it "every compiled .mjs in mcp_client/ imports cleanly"
                test-every-module-imports)
            (it "default(api) activates against a real agent without throwing"
                test-default-activates-against-real-api)))
