(ns extension-state-corruption.test
  "A corrupt state file must not be silently replaced by an empty one.

   `load` was `(try (JSON.parse (readFileSync …)) (catch :default _ #js {}))`
   with no existsSync gate, so ENOENT and SyntaxError were indistinguishable —
   and `set` is load → assoc → write, so the very next write TRUNCATED the file
   it had just failed to read. A store holding four keys became one key, with no
   message. `.nyma/` is gitignored, so there was no recovery.

   Two extensions depend on this today: spec_driven (active-spec, phase,
   profile, pending) and agent_shell (remembered ACP session ids), plus any
   user extension holding the :state capability."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.extension-state :as est]))

(defn- with-tmp [body]
  (let [tmp  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-extstate-"))
        prev (js/process.cwd)]
    (try (.chdir js/process tmp) (body tmp)
         (finally (.chdir js/process prev)
                  (try (fs/rmSync tmp #js {:recursive true :force true})
                       (catch :default _ nil))))))

(defn- store-path [tmp ns] (path/join tmp ".nyma" "ext-state" (str ns ".json")))

(defn- write-store! [tmp ns content]
  (let [p (store-path tmp ns)]
    (fs/mkdirSync (path/dirname p) #js {:recursive true})
    (fs/writeFileSync p content "utf8")
    p))

(defn- corrupt-siblings [tmp ns]
  (let [dir (path/dirname (store-path tmp ns))]
    (->> (vec (fs/readdirSync dir))
         (filter (fn [f] (.includes (str f) ".corrupt-")))
         vec)))

(describe "extension-state: corruption is quarantined, not clobbered" (fn []

  (it "preserves the original bytes when the file will not parse"
      (fn []
        (with-tmp
          (fn [tmp]
            (let [original "{\"active-spec\": \"apps\", \"spec-phase\": tru"  ; truncated
                  _   (write-store! tmp "spec-driven" original)
                  api (est/create-state-api "spec-driven")]
              ;; A write after a failed read used to destroy the evidence.
              (.set api "fresh" "value")
              (let [saved (corrupt-siblings tmp "spec-driven")]
                (-> (expect (count saved)) (.toBe 1))
                (-> (expect (fs/readFileSync
                             (path/join (path/dirname (store-path tmp "spec-driven"))
                                        (first saved)) "utf8"))
                    (.toBe original)))
              ;; …and the store still works from empty.
              (-> (expect (.get api "fresh")) (.toBe "value")))))))

  (it "treats a missing file as first run, silently"
      (fn []
        (with-tmp
          (fn [tmp]
            (let [api (est/create-state-api "brand-new")]
              (-> (expect (.get api "anything")) (.toBeUndefined))
              (.set api "k" 1)
              (-> (expect (.get api "k")) (.toBe 1))
              ;; No quarantine file for a file that never existed.
              (-> (expect (count (corrupt-siblings tmp "brand-new"))) (.toBe 0)))))))

  (it "quarantines a JSON array or scalar, which parses but is not a store"
      (fn []
        ;; `aset` onto an array would silently produce a nonsense store.
        (with-tmp
          (fn [tmp]
            (write-store! tmp "arr" "[1,2,3]")
            (let [api (est/create-state-api "arr")]
              (.set api "k" "v")
              (-> (expect (count (corrupt-siblings tmp "arr"))) (.toBe 1))
              (-> (expect (.get api "k")) (.toBe "v")))))))

  (it "round-trips a healthy store without quarantining anything"
      (fn []
        (with-tmp
          (fn [tmp]
            (write-store! tmp "ok" "{\"a\": 1}")
            (let [api (est/create-state-api "ok")]
              (-> (expect (.get api "a")) (.toBe 1))
              (.set api "b" 2)
              (-> (expect (.get api "a")) (.toBe 1))
              (-> (expect (.get api "b")) (.toBe 2))
              (-> (expect (count (corrupt-siblings tmp "ok"))) (.toBe 0)))))))

  (it "leaves no temp file behind — writes are atomic"
      (fn []
        ;; writeFileSync straight onto the live path is what produces the
        ;; truncated JSON this whole file defends against.
        (with-tmp
          (fn [tmp]
            (let [api (est/create-state-api "atomic")]
              (.set api "k" "v")
              (let [dir (path/dirname (store-path tmp "atomic"))
                    fs' (vec (fs/readdirSync dir))]
                (-> (expect (count (filter (fn [f] (.includes (str f) ".tmp-")) fs')))
                    (.toBe 0))
                (-> (expect (vec fs')) (.toEqual #js ["atomic.json"]))))))))

  (it "names both paths in the warning"
      (fn []
        ;; Silence here is how the original loss would go unnoticed.
        (-> (expect (.includes (est/quarantine-path "/x/y.json" (js/Date. 0)) ".corrupt-"))
            (.toBe true))
        ;; Timestamped, so a second failure cannot overwrite the first copy.
        (-> (expect (= (est/quarantine-path "/x/y.json" (js/Date. 0))
                       (est/quarantine-path "/x/y.json" (js/Date. 1000))))
            (.toBe false))))))
