(ns compaction-dump.test
  (:require ["bun:test" :refer [describe it expect afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            ["./agent/sessions/compaction.mjs" :refer [compact]]
            [clojure.string :as str]))

;; ── Helpers ─────────────────────────────────────────────────────

(defn- make-large-messages
  "Build messages whose combined token estimate exceeds 85k (100k * 0.85)."
  [n]
  (vec (for [i (range n)]
         {:role    (if (even? i) "user" "assistant")
          :content (apply str (repeat 1000 (str "word" i " ")))})))

(defn- make-session [messages]
  (let [appended  (atom nil)
        file-path "/tmp/test-session-abc123.jsonl"]
    {:build-context (fn [] messages)
     :get-file-path (fn [] file-path)
     :append        (fn [entry] (reset! appended entry) entry)
     :get-appended  (fn [] @appended)}))

(defn- make-events []
  (let [captured (atom nil)
        handlers (atom {})]
    {:on         (fn [event handler]
                   (swap! handlers update event conj handler))
     :emit-async (fn [event ctx]
                   (js/Promise.resolve
                    (do
                      (when (= event "before_compact")
                        (reset! captured ctx)
                        (aset ctx "summary" "test summary from hook"))
                      (doseq [h (get @handlers event [])]
                        (h ctx))
                      nil)))
     :emit       (fn [_event _data] nil)
     :get-captured (fn [] @captured)}))

;; ── Tests ────────────────────────────────────────────────────────

(def ^:private tmp-dir (path/join (os/tmpdir) "nyma-precompact"))

(afterEach
 (fn []
   (when (fs/existsSync tmp-dir)
     (doseq [f (js/Array.from (fs/readdirSync tmp-dir))]
       (try (fs/unlinkSync (path/join tmp-dir f)) (catch :default _ nil))))))

(defn ^:async test-dump-paths []
  (let [msgs   (make-large-messages 400)
        sm     (make-session msgs)
        events (make-events)]
    (js-await (compact sm "mock-model" events))
    (let [ctx ((:get-captured events))]
      (-> (expect (some? ctx)) (.toBe true))
      (-> (expect (string? (.-precompactJsonPath ctx))) (.toBe true))
      (-> (expect (string? (.-precompactTextPath ctx))) (.toBe true))
      (-> (expect (.startsWith (.-precompactJsonPath ctx) tmp-dir)) (.toBe true))
      (-> (expect (.startsWith (.-precompactTextPath ctx) tmp-dir)) (.toBe true)))))

(defn ^:async test-dump-cleanup []
  (let [msgs        (make-large-messages 400)
        sm          (make-session msgs)
        events      (make-events)
        json-path-a (atom nil)
        text-path-a (atom nil)]
    ((:on events) "before_compact"
                  (fn [ctx]
                    (reset! json-path-a (.-precompactJsonPath ctx))
                    (reset! text-path-a (.-precompactTextPath ctx))))
    (js-await (compact sm "mock-model" events))
    (when @json-path-a
      (-> (expect (fs/existsSync @json-path-a)) (.toBe false)))
    (when @text-path-a
      (-> (expect (fs/existsSync @text-path-a)) (.toBe false)))))

(defn ^:async test-dump-graceful-fallback []
  ;; session without :get-file-path — dump fails gracefully, compact continues
  (let [msgs     (make-large-messages 400)
        appended (atom nil)
        sm       {:build-context (fn [] msgs)
                  :append        (fn [e] (reset! appended e) e)}
        events   (make-events)]
    (js-await (compact sm "mock-model" events))
    (-> (expect (some? @appended)) (.toBe true))))

(describe "D2: precompact dump" (fn []
                                  (it "sets precompactJsonPath and precompactTextPath on evt-ctx" test-dump-paths)
                                  (it "dump files are cleaned up after append" test-dump-cleanup)
                                  (it "compact still succeeds when get-file-path is missing" test-dump-graceful-fallback)))

;; ── Content assertions ──────────────────────────────────────────

(def ^:private valid-gen-summary
  (str "## 1. Previous Conversation\nUser asked to implement a feature.\n\n"
       "## 2. Current Work\nWorking on src/agent/foo.cljs:42\n\n"
       "## 3. Key Technical Concepts\nClojureScript, Squint compiler.\n\n"
       "## 4. Relevant Files and Code\nsrc/agent/foo.cljs:42 — main entry point\n\n"
       "## 5. Problem Solving\nNo errors encountered.\n\n"
       "## 6. Pending Tasks and Next Steps\n- finish the impl\n  Quote: \"let's implement\""))

(defn- make-events-reading []
  "Events stub that captures the before_compact ctx AND reads dump files inline."
  (let [json-snap   (atom nil)
        text-snap   (atom nil)
        handlers    (atom {})]
    {:on         (fn [event handler]
                   (swap! handlers update event conj handler))
     :emit-async (fn [event ctx]
                   (js/Promise.resolve
                    (do
                      (when (= event "before_compact")
                        ;; Read files while they exist (before cleanup)
                        (when (.-precompactJsonPath ctx)
                          (reset! json-snap (str (fs/readFileSync (.-precompactJsonPath ctx))))
                          (reset! text-snap (str (fs/readFileSync (.-precompactTextPath ctx)))))
                        ;; Signal extension path so no real LLM call happens
                        (aset ctx "summary" valid-gen-summary))
                      (doseq [h (get @handlers event [])]
                        (h ctx))
                      nil)))
     :emit       (fn [_event _data] nil)
     :json-snap  (fn [] @json-snap)
     :text-snap  (fn [] @text-snap)}))

(defn ^:async test-dump-json-content []
  (let [msgs   (make-large-messages 400)
        sm     (make-session msgs)
        events (make-events-reading)]
    (js-await (compact sm "mock-model" events))
    (let [raw @(atom ((:json-snap events)))]
      (-> (expect (string? raw)) (.toBe true))
      (let [parsed (js/JSON.parse raw)]
        (-> (expect (some? (.-toSummarize parsed))) (.toBe true))
        (-> (expect (some? (.-toKeep parsed))) (.toBe true))
        (-> (expect (.isArray js/Array (.-toSummarize parsed))) (.toBe true))
        (-> (expect (.isArray js/Array (.-toKeep parsed))) (.toBe true))))))

(defn ^:async test-dump-text-content []
  (let [msgs   (make-large-messages 400)
        sm     (make-session msgs)
        events (make-events-reading)]
    (js-await (compact sm "mock-model" events))
    (let [raw @(atom ((:text-snap events)))]
      (-> (expect (string? raw)) (.toBe true))
      ;; format-messages wraps each message with [Role]: prefix
      (-> (expect (.includes raw "[User]:")) (.toBe true)))))

(defn- make-events-no-summary []
  "Events stub that does NOT set summary, letting compact fall to main path."
  (let [captured (atom nil)]
    {:on         (fn [_event _h] nil)
     :emit-async (fn [event ctx]
                   (when (= event "before_compact") (reset! captured ctx))
                   (js/Promise.resolve nil))
     :emit       (fn [_event _data] nil)
     :get-captured (fn [] @captured)}))

(defn- make-stub-gen [text]
  "Returns a generateText stub that always resolves with the given text."
  (fn [_opts] (js/Promise.resolve #js {:text text})))

(defn ^:async test-dump-cleanup-main-path []
  (let [msgs   (make-large-messages 400)
        sm     (make-session msgs)
        events (make-events-no-summary)]
    (js-await (compact sm "mock-model" events {:gen-fn (make-stub-gen valid-gen-summary)}))
    ;; Dump directory should be empty after main-path append
    (when (fs/existsSync tmp-dir)
      (let [files (js/Array.from (fs/readdirSync tmp-dir))]
        (-> (expect (count files)) (.toBe 0))))))

(defn ^:async test-dump-unique-names []
  (let [paths       (atom #{})
        make-ev     (fn []
                      (let [handlers (atom {})]
                        {:on         (fn [event h] (swap! handlers update event conj h))
                         :emit-async (fn [event ctx]
                                       (js/Promise.resolve
                                        (do
                                          (when (= event "before_compact")
                                            (when (.-precompactJsonPath ctx)
                                              (swap! paths conj (.-precompactJsonPath ctx)))
                                            (aset ctx "summary" valid-gen-summary))
                                          (doseq [h (get @handlers event [])] (h ctx))
                                          nil)))
                         :emit       (fn [_e _d] nil)}))]
    (js-await (compact (make-session (make-large-messages 400)) "mock-model" (make-ev)))
    (js-await (compact (make-session (make-large-messages 400)) "mock-model" (make-ev)))
    ;; Each compact should write a distinct path; if count == 1 there was a
    ;; Date.now() collision (same millisecond) — that's a real bug to fix.
    (-> (expect (count @paths)) (.toBe 2))))

(describe "D2: precompact dump — content and main path" (fn []
                                                          (it "dump JSON contains toSummarize and toKeep arrays" test-dump-json-content)
                                                          (it "dump text file contains formatted messages with [Role]: prefix" test-dump-text-content)
                                                          (it "dump files are cleaned up after main-path (non-extension) append" test-dump-cleanup-main-path)
                                                          (it "two back-to-back compactions produce distinct dump filenames" test-dump-unique-names)))
