(ns squint-interop-regression.test
  "Guards against the Squint `.-hyphenated-key` interop trap.

   `(.-tool-name x)` compiles to `x.tool_name` (hyphen munged to underscore),
   but squint maps / #js literals / parsed JSON store the key literally as
   \"tool-name\" — so the access silently returns undefined. The only correct
   reads are `(:tool-name x)` / `(aget x \"tool-name\")`.

   This bit us across ~50 sites (settings sections silently ignored, tool-name
   gating dead, compaction payload unread). The lint test below prevents the
   whole class from coming back."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.events :refer [create-event-bus]]
            [agent.core :refer [create-agent]]
            [agent.middleware :refer [create-pipeline]]
            [agent.extensions.bash-suite.output-handling :as output-handling]
            [agent.extensions.bash-suite.shared :as bash-shared]))

;; Sites where writer AND reader both go through Squint's munge (set! +
;; property read), so `.-hyphenated` is self-consistent and correct.
(def ^:private allowed
  #{"extension-api"})

(defn- cljs-files [dir]
  (mapcat (fn [entry]
            (let [p (path/join dir entry)]
              (if (.isDirectory (fs/statSync p))
                (cljs-files p)
                (when (.endsWith p ".cljs") [p]))))
          (fs/readdirSync dir)))

(describe "squint hyphenated .- access lint" (fn []
                                               (it "src/agent has no hyphenated property reads outside the allowlist"
                                                   (fn []
                                                     (let [rx    (js/RegExp. "\\(\\.-([a-z]+(?:-[a-z]+)+)[ )]" "g")
                                                           hits  (atom [])]
                                                       (doseq [f (cljs-files "src/agent")]
                                                         (let [content (fs/readFileSync f "utf8")]
                                                           (doseq [[i raw-line] (map-indexed vector (str/split content "\n"))]
                                                             ;; Scan the code BEFORE any comment — skipping whole lines that
                                                             ;; merely contain ';' let `(.-foo-bar x) ;; note` escape the lint.
                                                             ;; (A ';' inside a string still cuts the scan short — acceptable:
                                                             ;; false negatives only on lines mixing both, never false hits.)
                                                             (let [line (first (str/split raw-line ";"))]
                                                               (loop []
                                                                 (when-let [m (.exec rx line)]
                                                                   (when-not (contains? allowed (aget m 1))
                                                                     (swap! hits conj (str f ":" (inc i) " .-" (aget m 1))))
                                                                   (recur)))))))
                                                       (-> (expect (str/join "\n" @hits)) (.toBe "")))))))

(describe "middleware ctx hyphen-key access (regression)" (fn []
                                                            (it "output-handling middleware sees :tool-name on a real cljs-map ctx"
                                                                (fn []
        ;; The middleware :leave fn must read the literal "tool-name" key the
        ;; way agent.middleware threads it (cljs map assoc) — the old
        ;; (.-tool-name ctx) read `tool_name` and never matched "bash".
                                                                  (let [captured (atom nil)
                                                                        api      #js {:addMiddleware  (fn [mw] (reset! captured mw))
                                                                                      :registerTool   (fn [_ _])
                                                                                      :removeMiddleware (fn [_])
                                                                                      :unregisterTool (fn [_])}
                                                                        deact    (output-handling/activate api)
                                                                        leave    (.-leave @captured)
                                                                        big      (str/join "\n" (map (fn [i] (str "L" i " " (str/join (repeat 100 "x"))))
                                                                                                     (range 500)))
                                                                        ctx      {:tool-name "bash"
                                                                                  :result    (js/JSON.stringify #js {:stdout big :stderr "" :exitCode 0})}
                                                                        out      (leave ctx)
                                                                        result   (aget out "result")]
                                                                    (-> (expect (.includes (str result) "truncated")) (.toBe true))
                                                                    (deact))))

                                                            (it "is-bash-tool? gate rejects the undefined that .-tool-name used to produce"
                                                                (fn []
                                                                  (-> (expect (bash-shared/is-bash-tool? js/undefined)) (.toBeFalsy))
                                                                  (-> (expect (bash-shared/is-bash-tool? "bash")) (.toBeTruthy))))))

(describe "tool_execution_* event payload shape (regression)" (fn []
                                                                (it "start/end/update emit #js camelCase readable via .-toolName from extensions"
                                                                    (^:async fn []
        ;; Kebab CLJS-map payloads here left 5 extensions (expired_context,
        ;; structured_context, smart_compaction, repo_map, openwiki) reading
        ;; `.-toolName`/`.-args` as undefined — whole handlers silently dead.
        ;; Drive the REAL pipeline, not a hand-built mock payload.
                                                                      (let [events    (create-event-bus)
                                                                            seen      (atom {})
                                                                            _         ((:on events) "tool_execution_start"
                                                                                                    (fn [e] (swap! seen assoc :start [(.-toolName e) (some-> (.-args e) (aget "path"))])))
                                                                            _         ((:on events) "tool_execution_end"
                                                                                                    (fn [e] (swap! seen assoc :end [(.-toolName e) (some-> (.-args e) (aget "path")) (.-isError e)])))
                                                                            _         ((:on events) "tool_execution_update"
                                                                                                    (fn [e] (swap! seen assoc :update [(.-toolName e) (.-data e)])))
                                                                            pipeline  (create-pipeline events nil (atom (create-agent {:model "test" :system-prompt "x"})))
                                                                            tool      #js {:execute (fn [_ ext-ctx]
                                        ;; drive tool_execution_update through
                                        ;; the real onUpdate plumbing too
                                                                                                      (when (and ext-ctx (.-onUpdate ext-ctx))
                                                                                                        ((.-onUpdate ext-ctx) "chunk-1"))
                                                                                                      "ok")
                                                                                           :description "t"}]
                                                                        (js-await ((:execute pipeline) "read" tool {:path "/tmp/x"}))
                                                                        (-> (expect (first (:start @seen))) (.toBe "read"))
                                                                        (-> (expect (second (:start @seen))) (.toBe "/tmp/x"))
                                                                        (-> (expect (first (:end @seen))) (.toBe "read"))
                                                                        (-> (expect (second (:end @seen))) (.toBe "/tmp/x"))
                                                                        (-> (expect (first (:update @seen))) (.toBe "read"))
                                                                        (-> (expect (second (:update @seen))) (.toBe "chunk-1")))))))
