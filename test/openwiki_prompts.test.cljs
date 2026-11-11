(ns openwiki-prompts.test
  "The two prompts are the whole product: they carry the OKF format rules, the
   required final tool calls and the git evidence. Pin what a reader of the
   generated wiki depends on."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.openwiki.prompts :as prompts]))

(def ^:private ctx {:status " M a.cljs" :log "abc1 first" :diff "M\ta.cljs" :tree "src/\n  a.cljs"})

(describe "openwiki prompts" (fn []
                               (it "init names the dir, the sections, the final tool calls and the evidence"
                                   (fn []
                                     (let [p (prompts/init-prompt "wiki" ["domains" "guides"] ctx nil)]
                                       (doseq [s ["`wiki/index.md`" "domains, guides" "openwiki__ensure_agents_md"
                                                  "openwiki__save_metadata" "command=\"init\"" " M a.cljs" "abc1 first" "M\ta.cljs"]]
                                         (-> (expect p) (.toContain s)))
          ;; no instructions: no scope block
                                       (-> (expect p) (.not.toContain "Scope instructions")))))

                               (it "author instructions are inlined verbatim and marked as not the agent's to edit"
                                   (fn []
                                     (let [p (prompts/init-prompt "wiki" ["x"] ctx "Only document the API.\nSkip tests.")]
                                       (-> (expect p) (.toContain "Only document the API.\nSkip tests."))
                                       (-> (expect p) (.toContain "never edit this file")))
        ;; blank instructions count as none
                                     (-> (expect (prompts/init-prompt "wiki" ["x"] ctx "  \n")) (.not.toContain "Scope instructions"))))

                               (it "update carries the last metadata, the commits since, and asks for log.md"
                                   (fn []
                                     (let [p (prompts/update-prompt "docs" #js {:model "m" :commit "abc1"} "def2 later" ctx nil)]
                                       (doseq [s ["UPDATE mode" "`docs/log.md`" "\"commit\": \"abc1\"" "def2 later"
                                                  "command=\"update\"" "openwiki__save_metadata"]]
                                         (-> (expect p) (.toContain s))))))))
