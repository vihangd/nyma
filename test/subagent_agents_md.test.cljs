(ns subagent-agents-md.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.subagent.agents-md :as amd]))

(def ^:private sample
  "---\nname: scout\ndescription: Fast recon\nmodel: claude-haiku-4-20250901\ntools: read, grep, ls\n---\nYou are a scout. Investigate read-only.")

(describe "subagent:agents-md" (fn []
                                 (it "parses frontmatter name + model"
                                     (fn []
                                       (let [[k cfg] (amd/parse-md sample "fallback")]
                                         (-> (expect k) (.toBe "scout"))
                                         (-> (expect (:model cfg)) (.toBe "claude-haiku-4-20250901")))))

                                 (it "splits comma-separated tools into a vector"
                                     (fn []
                                       (let [[_ cfg] (amd/parse-md sample "fallback")]
                                         (-> (expect (vec (:allowed-tools cfg))) (.toEqual ["read" "grep" "ls"])))))

                                 (it "uses body as system-prompt"
                                     (fn []
                                       (let [[_ cfg] (amd/parse-md sample "fallback")]
                                         (-> (expect (:system-prompt cfg)) (.toContain "You are a scout")))))

                                 (it "falls back to filename-derived name when no name key"
                                     (fn []
                                       (let [[k _] (amd/parse-md "---\ndescription: x\n---\nbody" "myagent")]
                                         (-> (expect k) (.toBe "myagent")))))

                                 (it "handles missing frontmatter (whole text = body)"
                                     (fn []
                                       (let [[k cfg] (amd/parse-md "just a prompt, no frontmatter" "bare")]
                                         (-> (expect k) (.toBe "bare"))
                                         (-> (expect (:system-prompt cfg)) (.toContain "just a prompt")))))))
