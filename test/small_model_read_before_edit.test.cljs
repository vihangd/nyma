(ns small-model-read-before-edit.test
  "A small model will edit a file it has never read.

   `edit` matches on `old_string`, so an unread edit is the model guessing at
   text it has not seen: it either fails on a no-match or — the reason this is
   a guard and not a lint — matches somewhere unintended. little-coder ships two
   extensions for this (write-guard, read-guard-edit); nyma had nothing, which
   is what a scorecard against it turned up.

   The rule lives in `read_guard` because that module already wraps `read` and
   is therefore the only place that knows what has been read."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:path" :as path]
            [agent.extensions.small-model.read-guard :as read-guard
             :refer [edit-refusal write-refusal normalize-path]]))

(def ^:private here (path/resolve "src/agent/tools.cljs"))

(describe "edit-refusal"
          (fn []
            (it "refuses an edit to a file that was never read"
                (fn []
                  (-> (expect (edit-refusal #{} "src/agent/tools.cljs"))
                      (.toContain "has not been read"))))

            (it "allows an edit once the file has been read"
                (fn []
                  (-> (expect (edit-refusal #{here} "src/agent/tools.cljs")) (.toBeNil))))

            (it "treats ./x, x and an absolute path as the same file"
                (fn []
                  ;; The model rarely spells a path the same way twice. Matching
                  ;; on the raw string would refuse edits to files it had just
                  ;; read, which is the failure that makes people disable a guard.
                  (-> (expect (edit-refusal #{here} "./src/agent/tools.cljs")) (.toBeNil))
                  (-> (expect (edit-refusal #{here} here)) (.toBeNil))))

            (it "names the path it is refusing"
                (fn []
                  ;; A refusal the model cannot act on is just a failed turn.
                  (-> (expect (edit-refusal #{} "weird/place.txt")) (.toContain "weird/place.txt"))
                  (-> (expect (edit-refusal #{} "weird/place.txt")) (.toContain "read"))))

            (it "says nothing about a path it cannot resolve"
                (fn []
                  (-> (expect (edit-refusal #{} nil)) (.toBeNil))
                  (-> (expect (edit-refusal #{} "")) (.toBeNil))))))

(describe "write-refusal"
          (fn []
            (it "refuses a write over an existing unread file"
                (fn []
                  ;; This is the whole-file overwrite case: the model replaces
                  ;; content it never looked at.
                  (-> (expect (write-refusal #{} "src/agent/tools.cljs" true))
                      (.toContain "Refusing to write"))))

            (it "allows creating a new file"
                (fn []
                  ;; Nothing to lose, and demanding a read of a file that does
                  ;; not exist would make the guard absurd.
                  (-> (expect (write-refusal #{} "brand/new/file.txt" false)) (.toBeNil))))

            (it "allows overwriting a file that was read"
                (fn []
                  (-> (expect (write-refusal #{here} "src/agent/tools.cljs" true)) (.toBeNil))))))

(describe "normalize-path"
          (fn []
            (it "is absolute and stable across spellings"
                (fn []
                  (-> (expect (normalize-path "./a/b")) (.toBe (normalize-path "a/b")))
                  (-> (expect (.startsWith (normalize-path "a/b") "/")) (.toBe true))))

            (it "is nil for nothing"
                (fn []
                  (-> (expect (normalize-path nil)) (.toBeNil))
                  (-> (expect (normalize-path "")) (.toBeNil))))))

;;; ─── through the real overrides ─────────────────────────────

(defn- fake-api
  "Minimal tool registry with the __original chaining overrideTool relies on."
  [reg]
  #js {:overrideTool
       (fn [name def]
         (when-let [prev (get @reg name)] (set! (.-__original def) prev))
         (swap! reg assoc name def) nil)
       :unoverrideTool
       (fn [name]
         (if-let [orig (.-__original (get @reg name))]
           (swap! reg assoc name orig)
           (swap! reg dissoc name))
         nil)})

(defn- seed-tools! [reg calls]
  (doseq [n ["read" "edit" "write"]]
    (swap! reg assoc n
           #js {:description (str n " tool")
                :inputSchema #js {:type "object" :properties #js {}}
                :execute (fn [args]
                           (swap! calls conj n)
                           (js/Promise.resolve (str n " ran on " (.-path args))))})))

(defn- run! [reg name args]
  ((.-execute (get @reg name)) args))

(defn ^:async t-edit-is-refused-then-allowed []
  (let [reg   (atom {}) calls (atom [])
        _     (seed-tools! reg calls)
        stop  (read-guard/activate (fake-api reg) {:read-guard {}})
        p     "src/agent/tools.cljs"]
    ;; Unread: refused, and the underlying edit never ran.
    (let [out (js-await (run! reg "edit" #js {:path p}))]
      (-> (expect (str out)) (.toContain "has not been read"))
      (-> (expect (vec @calls)) (.toEqual #js [])))
    ;; After a read of the same file, the edit goes through.
    (js-await (run! reg "read" #js {:path p}))
    (let [out (js-await (run! reg "edit" #js {:path p}))]
      (-> (expect (str out)) (.toContain "edit ran on"))
      (-> (expect (vec @calls)) (.toEqual #js ["read" "edit"])))
    (stop)))

(defn ^:async t-a-failed-read-does-not-unlock-an-edit []
  ;; The unlock happens after the read resolves, so a read that throws must
  ;; leave the file locked — otherwise "read it first" is satisfied by trying.
  (let [reg (atom {}) calls (atom [])
        _   (seed-tools! reg calls)
        _   (swap! reg assoc "read"
                   #js {:description "read" :inputSchema #js {:type "object"}
                        :execute (fn [_] (js/Promise.reject (js/Error. "ENOENT")))})
        stop (read-guard/activate (fake-api reg) {:read-guard {}})
        p    "src/agent/tools.cljs"]
    (js-await (.catch (run! reg "read" #js {:path p}) (fn [_] nil)))
    (let [out (js-await (run! reg "edit" #js {:path p}))]
      (-> (expect (str out)) (.toContain "has not been read")))
    (stop)))

(defn ^:async t-can-be-switched-off []
  (let [reg (atom {}) calls (atom [])
        _   (seed-tools! reg calls)
        stop (read-guard/activate (fake-api reg)
                                  {:read-guard {:require-read-before-edit false}})]
    (let [out (js-await (run! reg "edit" #js {:path "src/agent/tools.cljs"}))]
      (-> (expect (str out)) (.toContain "edit ran on")))
    (stop)))

(describe "read-before-edit through the tool overrides"
          (fn []
            (it "refuses an unread edit, then allows it after a read"
                t-edit-is-refused-then-allowed)
            (it "a read that failed does not unlock the edit"
                t-a-failed-read-does-not-unlock-an-edit)
            (it "is off when require-read-before-edit is false"
                t-can-be-switched-off)))
