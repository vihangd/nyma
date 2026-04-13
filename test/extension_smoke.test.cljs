(ns extension-smoke.test
  "Smoke tests for extensions that had no load test.

   Each test imports the compiled extension module, calls its default
   export against a comprehensive mock API, and asserts the activation
   returns a deactivator. This catches:
     - compile-level breakage (e.g. Squint dropping `^:async` metadata
       from inline `fn`, which we saw in ast_tools)
     - missing global references (e.g. `js->clj` which Squint does not
       provide, which we saw in status_line)
     - any crash during activation with a minimally valid API

   Runs only the activation path — feature-specific behavior is covered
   by dedicated tests in the same suite."
  (:require ["bun:test" :refer [describe it expect]]
            ;; Import via compiled path so the ns declaration inside each
            ;; extension (which varies — some use `…name`, others
            ;; `…name.index`) doesn't affect the test's ability to load.
            ["./agent/extensions/custom_provider_qwen_cli/index.mjs" :as qwen-ext]
            ["./agent/extensions/mention_files/index.mjs" :as mention-files-ext]
            ["./agent/extensions/model_roles/index.mjs" :as model-roles-ext]
            ["./agent/extensions/stats_dashboard/index.mjs" :as stats-dashboard-ext]))

;;; ─── Comprehensive mock API ───────────────────────────────

(defn- make-mock-api []
  (let [state     (atom {:config           #js {:model #js {:modelId "test-m"}}
                         :model            #js {:modelId "test-m"}
                         :usage            {}
                         :active-executions []})
        listeners (atom {})
        commands  (atom {})
        mentions  (atom {})
        flags     (atom {})
        providers (atom {})
        tools     (atom {})
        settings  (atom {:model-roles {:default "anthropic/claude-sonnet-4-5"
                                       :fast    "anthropic/claude-haiku"}
                         :model-modes {}})]
    #js {:on                      (fn [evt handler] (swap! listeners update evt (fnil conj []) handler))
         :off                     (fn [evt handler]
                                    (swap! listeners update evt
                                      (fn [hs] (filterv #(not= % handler) (or hs [])))))
         :emit                    (fn [evt data]
                                    (doseq [h (get @listeners evt [])] (h data nil)))
         :dispatch                (fn [_evt _data])
         :getState                (fn [] (clj->js @state))
         :setState                (fn [new-state] (reset! state (js->clj-safe new-state)))
         :setModel                (fn [model-str] (swap! state assoc :model-str model-str))
         :registerCommand         (fn [name spec] (swap! commands assoc name spec))
         :unregisterCommand       (fn [name] (swap! commands dissoc name))
         :registerMentionProvider (fn [name spec] (swap! mentions assoc name spec))
         :unregisterMentionProvider (fn [name] (swap! mentions dissoc name))
         :registerFlag            (fn [name opts] (swap! flags assoc name opts))
         :getFlag                 (fn [name] (when-let [opts (get @flags name)] (.-default opts)))
         :registerProvider        (fn [name spec] (swap! providers assoc name spec))
         :unregisterProvider      (fn [name] (swap! providers dissoc name))
         :registerTool            (fn [name spec] (swap! tools assoc name spec))
         :unregisterTool          (fn [name] (swap! tools dissoc name))
         :getSettings             (fn [] (clj->js @settings))
         :updateSettings          (fn [patch] (swap! settings merge (js->clj-safe patch)))
         :log                     (fn [& _args])
         :exec                    (fn [_bin _args]
                                    (js/Promise.resolve #js {:stdout "" :stderr "" :code 0}))
         :_commands               commands
         :_mentions               mentions
         :_providers              providers
         :_listeners              listeners}))

;; Minimal js->clj for the mock's settings setter. Avoids depending on
;; any helper that might itself rely on Squint internals.
(defn- js->clj-safe [x]
  (try
    (js/JSON.parse (js/JSON.stringify x))
    (catch :default _ x)))

;;; ─── custom_provider_qwen_cli ─────────────────────────────

(describe "extension-smoke:custom_provider_qwen_cli" (fn []
  (it "loads the module and exports a default function"
    (fn []
      (-> (expect (fn? (.-default qwen-ext))) (.toBe true))))

  (it "activates without error and returns a deactivator"
    (fn []
      (let [api   (make-mock-api)
            deact ((.-default qwen-ext) api)]
        (-> (expect (fn? deact)) (.toBe true))
        (-> (expect (contains? @(.-_providers api) "qwen-cli")) (.toBe true))
        (deact)
        (-> (expect (contains? @(.-_providers api) "qwen-cli")) (.toBe false)))))))

;;; ─── mention_files ────────────────────────────────────────

(describe "extension-smoke:mention_files" (fn []
  (it "loads the module and exports a default function"
    (fn []
      (-> (expect (fn? (.-default mention-files-ext))) (.toBe true))))

  (it "activates and registers a 'files' mention provider"
    (fn []
      (let [api   (make-mock-api)
            deact ((.-default mention-files-ext) api)]
        (-> (expect (fn? deact)) (.toBe true))
        (-> (expect (contains? @(.-_mentions api) "files")) (.toBe true))
        (deact)
        (-> (expect (contains? @(.-_mentions api) "files")) (.toBe false)))))))

;;; ─── model_roles ──────────────────────────────────────────

(describe "extension-smoke:model_roles" (fn []
  (it "loads the module and exports a default function"
    (fn []
      (-> (expect (fn? (.-default model-roles-ext))) (.toBe true))))

  (it "activates and registers /role and /roles commands"
    (fn []
      (let [api   (make-mock-api)
            deact ((.-default model-roles-ext) api)]
        (-> (expect (fn? deact)) (.toBe true))
        (-> (expect (contains? @(.-_commands api) "role")) (.toBe true))
        (-> (expect (contains? @(.-_commands api) "roles")) (.toBe true))
        (deact)
        (-> (expect (contains? @(.-_commands api) "role")) (.toBe false)))))))

;;; ─── stats_dashboard ──────────────────────────────────────

(describe "extension-smoke:stats_dashboard" (fn []
  (it "loads the module and exports a default function"
    (fn []
      (-> (expect (fn? (.-default stats-dashboard-ext))) (.toBe true))))

  (it "activates and registers /stats commands"
    (fn []
      (let [api   (make-mock-api)
            deact ((.-default stats-dashboard-ext) api)]
        (-> (expect (fn? deact)) (.toBe true))
        (-> (expect (contains? @(.-_commands api) "stats")) (.toBe true))
        (deact)
        (-> (expect (contains? @(.-_commands api) "stats")) (.toBe false)))))))
