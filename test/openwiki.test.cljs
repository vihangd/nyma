(ns openwiki.test
  (:require ["bun:test" :refer [describe it expect beforeEach afterEach]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            [agent.extensions.openwiki.shared :as shared]
            [agent.extensions.openwiki.tools :as tools]
            [agent.extensions.openwiki.metadata :as md]
            [agent.extensions.openwiki.git :as git]
            [agent.extensions.openwiki.commands :as commands]))

;; ── shared/config ────────────────────────────────────────────────

(describe "openwiki config" (fn []
                              (it "defaults when no settings (off by default)"
                                  (fn []
                                    (-> (expect (:dir (shared/config nil))) (.toBe "openwiki"))
                                    (-> (expect (:enabled (shared/config nil))) (.toBe false))))
                              (it "overrides dir/model/enabled from settings#openwiki"
                                  (fn []
                                    (let [c (shared/config #js {:openwiki #js {:dir "docs" :model "x" :enabled true}})]
                                      (-> (expect (:dir c)) (.toBe "docs"))
                                      (-> (expect (:model c)) (.toBe "x"))
                                      (-> (expect (:enabled c)) (.toBe true))
                                      (-> (expect (:sections c)) (.toEqual (:sections shared/default-config))))))))

;; ── tools/ensure-section (AGENTS.md idempotency) ─────────────────

(describe "openwiki ensure-section" (fn []
                                      (let [section (tools/agents-section "openwiki" ["architecture" "testing"])]
                                        (it "creates when file is missing (nil)"
                                            (fn [] (-> (expect (tools/ensure-section nil section)) (.toBe section))))
                                        (it "appends once to an existing file without the section"
                                            (fn []
                                              (let [out (tools/ensure-section "# Repo\n" section)]
                                                (-> (expect (.startsWith out "# Repo")) (.toBe true))
                                                (-> (expect (.includes out "## OpenWiki")) (.toBe true)))))
                                        (it "is a no-op when the section already exists"
                                            (fn []
                                              (-> (expect (tools/ensure-section "stuff\n## OpenWiki\nx" section)) (.toBeNil)))))))

;; ── agents-section derives from configured :sections ─────────────

(describe "openwiki agents-section" (fn []
                                      (it "describes the configured sections, not a hardcoded list"
                                          (fn []
                                            (let [s (tools/agents-section "openwiki" ["frontend" "backend"])]
                                              (-> (expect (.includes s "frontend, backend")) (.toBe true))
                                              (-> (expect (.includes s "domain concepts")) (.toBe false)))))))

;; ── command handler: args is a CLJS SEQ (run-command! gives (rest parts)) ──
;; This is the regression guard for the args-as-JS-array crash.

(defn- fake-ctx []
  (let [fq (atom []) notes (atom [])]
    {:ctx #js {:agent {:follow-queue fq}
               :ui    #js {:notify (fn [m _l] (swap! notes conj m))}}
     :fq fq :notes notes}))

(describe "openwiki command handler (seq args)" (fn []
                                                  (let [handler (commands/make-handler {:dir "openwiki" :sections ["a"]})]

                                                    (it "chat primes a follow-up from a real cljs seq without throwing"
                                                        (fn []
                                                          (let [{:keys [ctx fq notes]} (fake-ctx)]
                                                            (handler (list "chat" "where" "is" "x") ctx)
                                                            (-> (expect (count @fq)) (.toBe 1))
                                                            (-> (expect (.includes (:content (first @fq)) "where is x")) (.toBe true))
                                                            (-> (expect (count @notes)) (.toBe 1)))))

                                                    (it "chat with no question notifies usage and does not prime"
                                                        (fn []
                                                          (let [{:keys [ctx fq]} (fake-ctx)]
                                                            (handler (list "chat") ctx)
                                                            (-> (expect (count @fq)) (.toBe 0)))))

                                                    (it "empty/unknown verb notifies usage, does not prime or throw"
                                                        (fn []
                                                          (let [{:keys [ctx fq notes]} (fake-ctx)]
                                                            (handler (list) ctx)
                                                            (handler (list "bogus") ctx)
                                                            (-> (expect (count @fq)) (.toBe 0))
                                                            (-> (expect (count @notes)) (.toBe 2))))))))

;; ── git/changes-since blank handling ─────────────────────────────

(describe "openwiki git" (fn []
                           (it "changes-since is empty for a blank/nil sha"
                               (fn []
                                 (-> (expect (git/changes-since "")) (.toBe ""))
                                 (-> (expect (git/changes-since nil)) (.toBe ""))))))

;; ── metadata: snapshot hash + no-op guard ────────────────────────

(def ^:private tmp (atom nil))
(beforeEach (fn []
              (let [d (path/join (os/tmpdir) (str "nyma-ow-" (js/Date.now)))]
                (fs/mkdirSync d #js {:recursive true})
                (reset! tmp d))))
(afterEach (fn []
             (when @tmp (try (fs/rmSync @tmp #js {:recursive true :force true}) (catch :default _)))))

(describe "openwiki metadata" (fn []
                                (it "snapshot-hash changes with content and is stable otherwise"
                                    (fn []
                                      (let [dir (path/join @tmp "openwiki")]
                                        (fs/mkdirSync dir #js {:recursive true})
                                        (fs/writeFileSync (path/join dir "a.md") "hello")
                                        (let [h1 (md/snapshot-hash dir)]
                                          (-> (expect (> (count h1) 0)) (.toBe true))
                                          (-> (expect (md/snapshot-hash dir)) (.toBe h1))       ; stable
                                          (fs/writeFileSync (path/join dir "a.md") "changed")
                                          (-> (expect (= (md/snapshot-hash dir) h1)) (.toBe false))))))

                                (it "excludes .last-update.json from the hash"
                                    (fn []
                                      (let [dir (path/join @tmp "openwiki")]
                                        (fs/mkdirSync dir #js {:recursive true})
                                        (fs/writeFileSync (path/join dir "a.md") "hello")
                                        (let [h1 (md/snapshot-hash dir)]
                                          (fs/writeFileSync (path/join dir ".last-update.json") "{\"x\":1}")
                                          (-> (expect (md/snapshot-hash dir)) (.toBe h1))))))

                                (it "save-metadata writes first, then no-ops when unchanged (no-op guard)"
                                    (fn []
                                      (let [dir (path/join @tmp "openwiki")]
                                        (fs/mkdirSync dir #js {:recursive true})
                                        (fs/writeFileSync (path/join dir "a.md") "hello")
                                        (let [r1 (md/save-metadata dir "init" "m")]
                                          (-> (expect (:skipped r1)) (.toBe false))
                                          (-> (expect (fs/existsSync (shared/metadata-file dir))) (.toBe true))
          ;; unchanged wiki → skipped, metadata untouched
                                          (let [r2 (md/save-metadata dir "update" "m")]
                                            (-> (expect (:skipped r2)) (.toBe true)))
          ;; real change → writes again
                                          (fs/writeFileSync (path/join dir "b.md") "new page")
                                          (-> (expect (:skipped (md/save-metadata dir "update" "m"))) (.toBe false))))))))
