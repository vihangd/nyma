(ns agent.extensions.codemode.worker
  "Runs one compiled script inside a Bun Worker.

   A Worker, not the main thread, so a script that never yields — a
   `(loop [] (recur))` — can be stopped: the host calls `terminate()` on
   timeout or Esc. Every bound function is a message to the host, which runs
   the real tool through the middleware pipeline and replies; nothing here
   touches a tool directly.

   NOT a sandbox: the Worker has Bun's full API. The host approves the script
   like bash for that reason. This file only buys interruptibility.

   Protocol
     host → {type \"run\" body bindings {ns [fn…]}}
     self → {type \"call\" id ns fn args}     host → {type \"reply\" id ok result error kind}
     self → {type \"done\" ok value logs error}"
  (:require ["squint-cljs/core.js" :as sc]))

(def ^:private AsyncFunction
  (.-constructor (js/Object.getPrototypeOf ^:async (fn [] nil))))

(def ^:private pending (js/Map.))
(def ^:private next-id (atom 0))
(def ^:private logs #js [])

(defn- seq-replacer
  "JSON.stringify replacer that writes a lazy seq / Set as the array it is.
   Without it `(js/JSON.stringify (map …))` printed `{\"realized\":false,…}` —
   a benchmark model lost two scripts to that before switching to `vec`."
  [_k v]
  ;; not `object?` — squint's is a plain-object test, and a LazyIterable is a
  ;; class instance
  (if (and v (not (string? v)) (not (array? v)) (fn? (aget v js/Symbol.iterator))
           (not (instance? js/Map v)))
    (js/Array.from v)
    v))

(defn- patch-json!
  "This realm runs only scripts, so JSON.stringify can learn about seqs."
  []
  (let [orig (.-stringify js/JSON)]
    (set! (.-stringify js/JSON)
          (fn [v r sp] (.call orig js/JSON v (or r seq-replacer) sp)))))

(defn- capture! []
  (doseq [k ["log" "info" "warn" "error" "debug"]]
    (aset js/console k
          (fn [& xs]
            (.push logs (.join (clj->js (map (fn [x] (if (string? x) x
                                                         (try (js/JSON.stringify x) (catch :default _ (str x)))))
                                             xs))
                               " "))))))

(defn- call [ns-name fn-name args]
  (js/Promise.
   (fn [resolve reject]
     (let [id (swap! next-id inc)]
       (.set pending id #js {:resolve resolve :reject reject})
       (js/postMessage #js {:type "call" :id id :ns ns-name :fn fn-name
                            :args (js/JSON.parse (js/JSON.stringify (or args #js {})))})))))

(defn- on-reply [m]
  (when-let [p (.get pending (.-id m))]
    (.delete pending (.-id m))
    (if (.-ok m)
      ((.-resolve p) (.-result m))
      ((.-reject p) (doto (js/Error. (str (.-error m)))
                      (aset "kind" (or (.-kind m) "ToolFailure"))
                      (aset "tool" (or (.-label m) (str (.-ns m) "/" (.-fn m)))))))))

(defn- namespaces
  "{ns {fn bound-fn}} as JS objects, from {ns [fn…]}."
  [bindings]
  (let [out #js {}]
    (doseq [n (js/Object.keys bindings)]
      (let [o #js {}]
        (doseq [f (aget bindings n)]
          (aset o f (fn [args] (call n f args))))
        (aset out n o)))
    out))

(defn ^:async realize
  "Await promises and turn lazy seqs into arrays, all the way down, so the
   value survives `postMessage` and reads as data. Un-awaited tool calls in a
   `map` resolve here too — `(map #(gh/get {:id %}) ids)` just works, and in
   parallel."
  [v depth]
  (cond
    (> depth 32) "…"
    (and v (fn? (.-then v))) (js-await (realize (js-await v) (inc depth)))
    (or (nil? v) (string? v) (number? v) (boolean? v)) v
    (fn? v) "<fn>"
    (array? v) (js-await (js/Promise.all (.map v #(realize % (inc depth)))))
    (and (object? v) (= js/Object (.-constructor v)))
    (let [out #js {}]
      (doseq [k (js/Object.keys v)]
        (aset out k (js-await (realize (aget v k) (inc depth)))))
      out)
    (and v (fn? (aget v js/Symbol.iterator)))
    (js-await (realize (js/Array.from v) depth))
    :else (str v)))

(defn ^:async run [m]
  (patch-json!)
  (capture!)
  (let [bindings (or (.-bindings m) #js {})
        nss      (namespaces bindings)
        names    (js/Object.keys nss)
        f        (js/Reflect.construct AsyncFunction
                                       (clj->js (concat ["squint_core"] names [(.-body m)])))]
    (try
      (let [v (js-await (js/Reflect.apply f nil (clj->js (concat [sc] (map #(aget nss %) names)))))
            v (js-await (realize v 0))]
        (js/postMessage #js {:type "done" :ok true :value v :logs logs}))
      (catch :default e
        (js/postMessage #js {:type "done" :ok false :logs logs
                             :error #js {:kind    (or (.-kind e) "RuntimeError")
                                         :message (str (or (.-message e) e))
                                         :tool    (.-tool e)}})))))

(set! js/self.onmessage
      (fn [ev]
        (let [m (.-data ev)]
          (case (.-type m)
            "run"   (run m)
            "reply" (on-reply m)
            nil))))
