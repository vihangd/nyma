(ns ext-mcp-client-load-smoke.test
  "Real-agent activation smoke test — production-shaped.

   This test catches the WHOLE activation pipeline, not just module
   imports. Past bugs proved isolated probes lie:
     - L1 'every .mjs imports' missed the 'await outside async' bug.
     - L2 'default(api) doesn't throw' against a hand-built mock api
       missed the 'subscribed to wrong bus' bug.
     - L2 against a real create-extension-api(agent) missed the
       'manifest declared wrong capabilities' bug — because the
       capability gating lives in extension_scope/create-scoped-api,
       which the production loader runs but a raw default(api)
       call doesn't.

   This test now runs the full chain:
     1. Recursively import every .mjs (catches syntax / top-level
        eval throws).
     2. Read extension.json to get the declared capabilities list.
     3. Wrap the real api with create-scoped-api using that list.
     4. Call default(scoped-api) and assert no throw.

   If any registerX call inside default uses an undeclared
   capability, the gate throws synchronously at activation —
   exactly the way it does in production."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.core :refer [create-agent]]
            [agent.extensions :refer [create-extension-api]]
            [agent.extension-scope :refer [create-scoped-api]]))

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

(defn- read-manifest-capabilities
  "Read declared capabilities from extension.json next to index.mjs.
   In squint, keywords compile to strings — so the set this returns
   matches what the production loader builds via parse-capabilities
   (a set of strings)."
  []
  (let [p      (path/join dist-mcp-dir "extension.json")
        raw    (fs/readFileSync p "utf8")
        parsed (js/JSON.parse raw)
        caps   (.-capabilities parsed)
        n      (or (and caps (.-length caps)) 0)]
    (set (for [i (range n)] (aget caps i)))))

(defn ^:async test-default-activates-with-scoped-api []
  ;; The production path wraps the base api with extension_scope/
  ;; create-scoped-api, which gates registerX calls behind the
  ;; declared capabilities. We replicate that exactly so a missing
  ;; capability declaration fails this test instead of silently
  ;; activating in a permissive test environment and crashing in
  ;; the real loader.
  (let [agent      (create-agent
                    {:model #js {:modelId "test-model"}
                     :system-prompt "test"})
        base-api   (create-extension-api agent)
        caps       (read-manifest-capabilities)
        scoped-api (create-scoped-api base-api "mcp-client" caps)
        events     (:events agent)
        bridge-mod (js-await (js/import (path/join dist-mcp-dir "index.mjs")))
        dispose    (try ((.-default bridge-mod) scoped-api)
                        (catch :default e e))]
    ;; Activation must not throw — captured value must be a fn or nil.
    (when (instance? js/Error dispose)
      (js/console.error "[mcp-smoke] activation threw:" (.-message dispose)))
    (-> (expect (or (fn? dispose) (nil? dispose))) (.toBe true))

    ;; Verify session_start / session_shutdown were subscribed on the
    ;; MAIN bus.
    (let [start-count (when-let [hc (:handler-count events)] (hc "session_start"))
          stop-count  (when-let [hc (:handler-count events)] (hc "session_shutdown"))]
      (when (and (some? start-count) (some? stop-count))
        (-> (expect (pos? start-count)) (.toBe true))
        (-> (expect (pos? stop-count))  (.toBe true))))

    (when (fn? dispose) (try (dispose) (catch :default _e nil)))))

(describe "mcp-client/load-smoke"
          (fn []
            (it "every compiled .mjs in mcp_client/ imports cleanly"
                test-every-module-imports)
            (it "default(scoped-api) activates with manifest-declared capabilities"
                test-default-activates-with-scoped-api)))
