(ns skill-pack.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.resources.skills :refer [discover-skills valid-name?]]))

(describe "bundled starter skill pack" (fn []

                                         (it "all skills parse and carry name + description"
                                             (fn []
                                               (let [skills (discover-skills "skills")]
                                                 (-> (expect (>= (count skills) 4)) (.toBe true))
                                                 (doseq [[name skill] skills]
                                                   (-> (expect (valid-name? name)) (.toBe true))
                                                   (-> (expect (= (:name skill) name)) (.toBe true))
                                                   (-> (expect (seq (:description skill))) (.toBeTruthy))
                                                   (-> (expect (seq (:body skill))) (.toBeTruthy))))))

                                         (it "includes the expected curated skills"
                                             (fn []
                                               (let [names (set (keys (discover-skills "skills")))]
                                                 (doseq [n ["systematic-debugging" "conventional-commits" "test-first" "pr-review"]]
                                                   (-> (expect (contains? names n)) (.toBe true))))))

                                         (it "ships no executable skill tools (instruction-only, safety)"
                                             (fn []
                                               (doseq [[_ skill] (discover-skills "skills")]
                                                 (-> (expect (:has-tools skill)) (.toBeFalsy)))))))
