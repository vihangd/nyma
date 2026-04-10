(ns agent.ui.autocomplete-builtins
  "Three built-in completion providers that ship with nyma:

     * slash-commands — lists registered /commands; trigger :slash
     * at-file-mentions — runs the first mention provider; trigger :at
     * path-complete — directory-cached file lookup; trigger :path

   Each provider is registered into the agent's
   :autocomplete-registry when `register-all!` is called."
  (:require [agent.ui.autocomplete-provider :refer [readdir-cached]]
            ["node:path" :as path]))

;;; ─── /command provider ─────────────────────────────────

(defn- slash-provider
  "Lists every registered /command as an autocomplete item."
  [agent]
  {:trigger  :slash
   :priority 100
   :complete
   (fn [_ctx]
     (js/Promise.resolve
       (if-let [cmds @(:commands agent)]
         (let [entries (seq cmds)]
           (clj->js
             (vec (for [[name cmd] entries]
                    {:label (str "/" name)
                     :value (str "/" name)
                     :description (or (:description cmd) "")}))))
         (clj->js []))))})

;;; ─── @file provider (delegates to mention-providers) ──

(defn- at-file-provider
  "Delegates to the first registered mention-provider's search fn."
  [agent]
  {:trigger  :at
   :priority 100
   :complete
   (fn [ctx]
     (let [providers @(:mention-providers agent)
           provider  (first (vals providers))
           search    (when provider (:search provider))]
       (if search
         (try
           (-> (search (get ctx :query))
               (.then (fn [items] (or items (clj->js []))))
               (.catch (fn [_] (clj->js []))))
           (catch :default _ (js/Promise.resolve (clj->js []))))
         (js/Promise.resolve (clj->js [])))))})

;;; ─── path-prefix provider ──────────────────────────────

(defn ^:async path-complete-impl [ctx]
  (let [text  (get ctx :text)
        query (get ctx :query)
        ;; Determine the directory to list: text up to the last slash,
        ;; or '.' when there's no slash yet.
        slash-idx (.lastIndexOf (str text) "/")
        dir   (cond
                (= slash-idx -1)          "."
                (.startsWith text "./")   (.slice text 2 slash-idx)
                :else                     (.slice text 0 slash-idx))
        dir   (if (= dir "") "." dir)]
    (try
      (let [entries (js-await (readdir-cached dir))]
        (clj->js
          (vec
            (for [entry entries
                  :when (and (string? entry)
                             (or (= query "")
                                 (.startsWith (.toLowerCase entry)
                                              (.toLowerCase query))))]
              {:label entry
               :value (if (= dir ".") entry (str dir "/" entry))
               :description dir}))))
      (catch :default _ (clj->js [])))))

(defn- path-provider [_agent]
  {:trigger  :path
   :priority 50
   :complete path-complete-impl})

;;; ─── Register all ──────────────────────────────────────

(defn register-all!
  "Register every built-in provider into the agent's autocomplete
   registry. Idempotent — re-registering overwrites previous entries."
  [agent]
  (when-let [reg (:autocomplete-registry agent)]
    ((:register reg) "builtin.slash" (slash-provider agent))
    ((:register reg) "builtin.at-file" (at-file-provider agent))
    ((:register reg) "builtin.path" (path-provider agent))))
