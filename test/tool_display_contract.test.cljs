(ns tool-display-contract.test
  "Every `:display` formatter a shipped tool declares must actually produce a
   string when the middleware calls it.

   All 13 formatters in `src/agent/extensions` are written `(fn [_name args] …)`
   while `extract-display-fields` called them with the args alone. `args` was
   therefore `undefined` inside, the first property access threw, `safe-call`
   swallowed the throw, and the field was silently dropped — so every extension
   tool rendered through the generic `k=v` preview. A questionnaire call read

     ✓ questionnaire__questionnaire questions=[object Object] · [object Object]

   The existing coverage could not see it: `middleware.test.cljs` declared its
   own 1-arity formatter, which matched no shipped tool.

   So this walks the REAL registered tools through the REAL middleware call,
   the way `extension_capability_lint` walks real manifests."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.middleware :refer [create-pipeline]]
            [agent.events :refer [create-event-bus]]
            [agent.state :refer [create-agent-store]]))

(def ^:private ext-root
  (path/resolve (js/process.cwd) "src" "agent" "extensions"))

(def sample-args
  "One args object carrying every key the shipped formatters reach for. A
   formatter that reads a key missing here returns something falsy rather than
   throwing, which the assertion below still catches."
  #js {:pattern "foo" :replacement "bar" :path "src/a.cljs" :file "src/a.cljs"
       :line 12 :col 3 :query "sym" :newName "sym2" :focus "why?"
       :questions #js [#js {:id "q1" :prompt "Q?"}]})

(defn- cljs-files [dir]
  (reduce
   (fn [acc e]
     (let [p (path/join dir (.-name e))]
       (cond
         (.isDirectory e) (into acc (cljs-files p))
         (.endsWith (.-name e) ".cljs") (conj acc p)
         :else acc)))
   []
   (vec (fs/readdirSync dir #js {:withFileTypes true}))))

(defn formatter-sites
  "[{:ext :arity}] for every `:formatArgs` literal under the extensions tree.
   Read from source because a formatter that is never registered in a test
   harness is exactly the one that rots."
  []
  (->> (vec (fs/readdirSync ext-root #js {:withFileTypes true}))
       (filter (fn [e] (.isDirectory e)))
       (mapcat
        (fn [e]
          (let [name (.-name e)
                src  (->> (cljs-files (path/join ext-root name))
                          (map (fn [f] (fs/readFileSync f "utf8")))
                          (str/join "\n"))]
            (->> (re-seq #":formatArgs\s+\(fn\s+\[([^\]]*)\]" src)
                 (map (fn [m]
                        {:ext   name
                         :arity (count (remove str/blank?
                                               (str/split (str (second m)) #"\s+")))}))))))
       vec))

(defn- run-formatter
  "Drive a tool with `display` through the real pipeline and return the
   customOneLineArgs the middleware published."
  [display]
  (let [events   (create-event-bus)
        store    (create-agent-store {:messages [] :active-tools #{} :model nil :tool-calls {}})
        pipeline (create-pipeline events store)
        captured (atom nil)
        tool     #js {:execute (fn [_] "ok") :description "mock" :display display}]
    ((:on events) "tool_execution_start" (fn [data] (reset! captured data)))
    (-> ((:execute pipeline) "mock_tool" tool sample-args)
        (.then (fn [_] (get @captured :customOneLineArgs))))))

(describe "tool :display formatters" (fn []

                                       (it "finds the formatters"
                                           (fn []
        ;; Floor check — a path typo would make every assertion below pass
        ;; vacuously, which is how a lint quietly stops linting.
                                             (-> (expect (>= (count (formatter-sites)) 12)) (.toBe true))))

                                       (it "every formatArgs takes (tool-name, args)"
                                           (fn []
        ;; The arity middleware calls with. A 1-arity formatter receives the
        ;; tool NAME as its args and reads properties off a string.
                                             (let [bad (->> (formatter-sites)
                                                            (remove (fn [f] (= 2 (:arity f))))
                                                            (map :ext)
                                                            distinct)]
                                               (-> (expect (str/join ", " bad)) (.toBe "")))))

                                       (it "the middleware really does pass two arguments"
                                           (fn []
                                             (-> (run-formatter #js {:formatArgs (fn [name args]
                                                                                   (str name "|" (.-pattern args)))})
                                                 (.then (fn [out]
                                                          (-> (expect out) (.toBe "mock_tool|foo")))))))

                                       (it "an (_name, args) formatter of the shape extensions use produces a string"
                                           (fn []
        ;; ast_tools' shape, hand-copied — this proves the CALLING CONVENTION,
        ;; not ast_tools itself. What holds the real formatters to it is
        ;; `formatter-sites` above, which reads them out of the source.
                                             (-> (run-formatter #js {:formatArgs (fn [_name args]
                                                                                   (str (.-pattern args) " in "
                                                                                        (or (.-path args) ".")))})
                                                 (.then (fn [out]
                                                          (-> (expect out) (.toBe "foo in src/a.cljs")))))))

                                       (it "detects the regression it was written for"
                                           (fn []
        ;; A 1-arity formatter under the two-arg call: `args` is undefined and
        ;; the property access throws, so safe-call drops the field. This is
        ;; what every shipped tool was doing.
                                             (-> (run-formatter #js {:formatArgs (fn [args] (.-pattern args))})
                                                 (.then (fn [out]
                                                          (-> (expect out) (.toBeUndefined)))))))))
