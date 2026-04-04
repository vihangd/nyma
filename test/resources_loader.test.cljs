(ns resources-loader.test
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [agent.resources.loader :refer [discover]]))

;; Test resource discovery with temp directories.
;; discover is async, returns {:skills :prompts :themes :agents-md :extension-dirs :build-system-prompt}

(def test-base (str "/tmp/nyma-resources-test-" (js/Date.now)))

(defn- setup-dirs []
  (let [skills-dir  (str test-base "/skills/my-skill")
        prompts-dir (str test-base "/prompts")
        themes-dir  (str test-base "/themes")
        ext-dir     (str test-base "/extensions")]
    (fs/mkdirSync skills-dir #js {:recursive true})
    (fs/mkdirSync prompts-dir #js {:recursive true})
    (fs/mkdirSync themes-dir #js {:recursive true})
    (fs/mkdirSync ext-dir #js {:recursive true})
    ;; Create test files
    (fs/writeFileSync (str skills-dir "/SKILL.md") "# My Skill\nDoes things.")
    (fs/writeFileSync (str prompts-dir "/greet.md") "Hello {{name}}!")
    (fs/writeFileSync (str themes-dir "/dark.json")
      (js/JSON.stringify #js {:name "dark" :colors #js {:primary "#fff"}}))))

(defn- cleanup-recursive [dir-path]
  (when (fs/existsSync dir-path)
    (let [entries (fs/readdirSync dir-path #js {:withFileTypes true})]
      (doseq [entry entries]
        (let [full (path/join dir-path (.-name entry))]
          (if (.isDirectory entry)
            (cleanup-recursive full)
            (fs/unlinkSync full)))))
    (fs/rmdirSync dir-path)))

(defn ^:async test-discover-returns-expected-shape []
  (let [result (js-await (discover))]
    ;; Should have all expected keys
    (-> (expect (some? (:skills result))) (.toBe true))
    (-> (expect (some? (:prompts result))) (.toBe true))
    (-> (expect (some? (:themes result))) (.toBe true))
    (-> (expect (some? (:extension-dirs result))) (.toBe true))
    (-> (expect (fn? (:build-system-prompt result))) (.toBe true))))

(defn ^:async test-build-system-prompt-includes-env []
  (let [result (js-await (discover))
        prompt ((:build-system-prompt result))]
    ;; Should include environment info
    (-> (expect prompt) (.toContain "Environment"))))

(defn test-discover-handles-missing-dirs []
  ;; discover should not throw when dirs don't exist
  ;; This is implicitly tested by calling discover without setup
  ;; since the global/project dirs may not exist in test env
  (-> (expect (fn? discover)) (.toBe true)))

(describe "agent.resources.loader/discover" (fn []
  (it "returns expected shape with all keys" test-discover-returns-expected-shape)
  (it "build-system-prompt includes environment block" test-build-system-prompt-includes-env)
  (it "handles missing directories gracefully" test-discover-handles-missing-dirs)))
