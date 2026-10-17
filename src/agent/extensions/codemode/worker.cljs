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
     self → {type \"log\" line}               (each println, as it happens)
     self → {type \"done\" ok value error}"
  (:require ["squint-cljs/core.js" :as sc]
            ["squint-cljs/string.js" :as sstr]
            [agent.utils.html :as html]))

(def ^:private helpers
  "Pure fns bound under `nyma/` beside its tools (analyze/helpers names them).
   All synchronous, so a script can use them inline: html->md's turndown is
   loaded before the script starts, and only when the script names it."
  {"html__GT_text" html/html->text
   "html__GT_md"   (fn [h] (or (html/html->markdown-sync h) (html/html->text h)))
   "strip_quotes"  html/strip-quotes
   "truncate"      html/truncate})

(def ^:private AsyncFunction
  (.-constructor (js/Object.getPrototypeOf ^:async (fn [] nil))))

(def ^:private pending (js/Map.))
(def ^:private next-id (atom 0))

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

(defn- printable-errors!
  "`(println \"ERR\" e)` printed `#<Error>` — squint's printer shows a class
   instance by name only — so a benchmark model that caught a failed call
   could not see why. Errors print their kind and message instead."
  []
  (aset (.-prototype js/Error) (js/Symbol.for "squint.core/-pr-writer")
        (fn [v w _opts]
          ((aget w (js/Symbol.for "squint.core/-write"))
           w (str "#error " (js/JSON.stringify (str (when (.-kind v) (str (.-kind v) ": "))
                                                    (.-message v)
                                                    (when (.-tool v) (str " (in " (.-tool v) ")")))))))))

(defn- printable-promises!
  "`(println x)` of an un-awaited call printed `#<Promise>` and the model
   kept going; say what to do."
  []
  (aset (.-prototype js/Promise) (js/Symbol.for "squint.core/-pr-writer")
        (fn [_v w _opts]
          ((aget w (js/Symbol.for "squint.core/-write")) w "#<Promise: not awaited — (js-await …) it>"))))

(defn- capture! []
  (doseq [k ["log" "info" "warn" "error" "debug"]]
    (aset js/console k
          (fn [& xs]
            (js/postMessage
             #js {:type "log"
                  :line (.join (clj->js (map (fn [x] (if (string? x) x
                                                       (try (js/JSON.stringify x) (catch :default _ (str x)))))
                                             xs))
                               " ")})))))

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
    (let [o (or (aget out "nyma") #js {})]
      (doseq [[k f] helpers] (aset o k f))
      (aset out "nyma" o))
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
  (when (.includes (str (.-body m)) "html__GT_md")
    (try (js-await (html/load-html-mods!)) (catch :default _ nil)))
  (patch-json!)
  (printable-errors!)
  (printable-promises!)
  (capture!)
  (let [bindings (or (.-bindings m) #js {})
        nss      (namespaces bindings)
        names    (js/Object.keys nss)
        ;; clojure.string without a require: `str/join`, `string/trim` and
        ;; `clojure.string/replace` compile to these three names
        f        (js/Reflect.construct AsyncFunction
                                       ;; `args` is {} at the top level, the caller's map
                                       ;; inside a saved script — so a script reads the
                                       ;; same either way and saves as it ran
                                       (clj->js (concat ["squint_core" "str" "string" "clojure_DOT_string" "args"]
                                                        names [(.-body m)])))]
    (try
      (let [v (js-await (js/Reflect.apply f nil (clj->js (concat [sc sstr sstr sstr #js {}] (map #(aget nss %) names)))))
            v (js-await (realize v 0))]
        (js/postMessage #js {:type "done" :ok true :value v}))
      (catch :default e
        (js/postMessage #js {:type "done" :ok false
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
