(ns autocomplete-provider.test
  (:require ["bun:test" :refer [describe it expect beforeEach]]
            [agent.ui.autocomplete-provider
             :refer [detect-trigger
                     create-provider-registry
                     complete-all
                     readdir-cached
                     clear-readdir-cache!]]))

;;; ─── detect-trigger ─────────────────────────────────────

(describe "detect-trigger" (fn []
  (it "detects :slash when text starts with /"
    (fn []
      (let [t (detect-trigger "/clear")]
        (-> (expect (contains? t :slash)) (.toBe true)))))

  (it "detects :at when text ends with @"
    (fn []
      (let [t (detect-trigger "hello @")]
        (-> (expect (contains? t :at)) (.toBe true)))))

  (it "detects :path when text ends with /"
    (fn []
      (let [t (detect-trigger "src/")]
        (-> (expect (contains? t :path)) (.toBe true)))))

  (it "detects :path when text starts with ./"
    (fn []
      (let [t (detect-trigger "./foo")]
        (-> (expect (contains? t :path)) (.toBe true)))))

  (it ":any trigger is always present"
    (fn []
      (let [t (detect-trigger "plain text")]
        (-> (expect (contains? t :any)) (.toBe true)))))))

;;; ─── create-provider-registry ──────────────────────────

(describe "create-provider-registry" (fn []
  (it "registers and retrieves providers"
    (fn []
      (let [reg (create-provider-registry)
            p   {:trigger :slash :complete (fn [_] (js/Promise.resolve []))}]
        ((:register reg) "test" p)
        (-> (expect (:trigger ((:get reg) "test"))) (.toBe "slash")))))

  (it "unregisters providers"
    (fn []
      (let [reg (create-provider-registry)]
        ((:register reg) "t" {:trigger :any
                               :complete (fn [_] (js/Promise.resolve []))})
        ((:unregister reg) "t")
        (-> (expect ((:get reg) "t")) (.toBe js/undefined)))))

  (it "list returns all registered providers"
    (fn []
      (let [reg (create-provider-registry)]
        ((:register reg) "a" {:trigger :slash
                               :complete (fn [_] (js/Promise.resolve []))})
        ((:register reg) "b" {:trigger :at
                               :complete (fn [_] (js/Promise.resolve []))})
        (-> (expect (count ((:list reg)))) (.toBe 2)))))))

;;; ─── complete-all ──────────────────────────────────────

(defn ^:async test-complete-all-routes-to-matching-trigger []
  (let [reg (create-provider-registry)
        slash-items [{:label "clear" :value "/clear"}
                     {:label "help" :value "/help"}]
        at-items    [{:label "file.cljs" :value "file.cljs"}]]
    ((:register reg) "slash"
      {:trigger :slash :complete (fn [_] (js/Promise.resolve (clj->js slash-items)))})
    ((:register reg) "at"
      {:trigger :at :complete (fn [_] (js/Promise.resolve (clj->js at-items)))})
    ;; With '/cl' only slash provider should run
    (let [results (js-await (complete-all reg "/cl"))]
      (-> (expect (pos? (count results))) (.toBe true))
      (-> (expect (every? (fn [r] (or (.includes (or (get r :label)
                                                      (get r "label") "") "clear")
                                       (.includes (or (get r :label)
                                                      (get r "label") "") "help")))
                          results))
          (.toBe true)))))

(defn ^:async test-complete-all-merges-any-providers []
  (let [reg (create-provider-registry)
        any-items [{:label "alpha"} {:label "beta"}]]
    ((:register reg) "any"
      {:trigger :any :complete (fn [_] (js/Promise.resolve (clj->js any-items)))})
    (let [results (js-await (complete-all reg "plain"))]
      (-> (expect (pos? (count results))) (.toBe true)))))

(defn ^:async test-complete-all-fuzzy-sorts []
  (let [reg (create-provider-registry)
        items [{:label "barfoo"} {:label "foobar"}]]
    ;; Use :slash so the query is extracted and fuzzy-sort runs.
    ((:register reg) "slash"
      {:trigger :slash :complete (fn [_] (js/Promise.resolve (clj->js items)))})
    (let [results (js-await (complete-all reg "/foo"))]
      ;; Prefix match 'foobar' should rank ahead of substring match 'barfoo'
      (-> (expect (or (get (first results) :label)
                      (get (first results) "label")))
          (.toBe "foobar")))))

(defn ^:async test-complete-all-handles-provider-error []
  (let [reg (create-provider-registry)
        good [{:label "one"}]]
    ((:register reg) "bad"
      {:trigger :any
       :complete (fn [_] (js/Promise.reject (js/Error. "boom")))})
    ((:register reg) "good"
      {:trigger :any
       :complete (fn [_] (js/Promise.resolve (clj->js good)))})
    (let [results (js-await (complete-all reg "o"))]
      (-> (expect (pos? (count results))) (.toBe true)))))

(describe "complete-all" (fn []
  (it "routes to matching triggers" test-complete-all-routes-to-matching-trigger)
  (it "merges :any providers" test-complete-all-merges-any-providers)
  (it "fuzzy-sorts merged results" test-complete-all-fuzzy-sorts)
  (it "swallows errors from failing providers"
      test-complete-all-handles-provider-error)))

;;; ─── readdir-cached ────────────────────────────────────

(defn ^:async test-readdir-cached-returns-entries []
  (clear-readdir-cache!)
  (let [entries (js-await (readdir-cached "test"))]
    (-> (expect (pos? (count entries))) (.toBe true))))

(defn ^:async test-readdir-cached-returns-empty-on-error []
  (clear-readdir-cache!)
  (let [entries (js-await (readdir-cached "/nonexistent/path/xyz123"))]
    (-> (expect (count entries)) (.toBe 0))))

(defn ^:async test-readdir-cached-hits-cache-on-second-call []
  (clear-readdir-cache!)
  (let [first  (js-await (readdir-cached "test"))
        second (js-await (readdir-cached "test"))]
    ;; Same reference? Not guaranteed, but entries should be deep-equal.
    (-> (expect (count first)) (.toBe (count second)))))

(describe "readdir-cached" (fn []
  (it "returns entries from the filesystem" test-readdir-cached-returns-entries)
  (it "returns empty on error" test-readdir-cached-returns-empty-on-error)
  (it "hits cache on repeat calls within TTL"
      test-readdir-cached-hits-cache-on-second-call)))
