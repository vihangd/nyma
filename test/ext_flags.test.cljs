(ns ext-flags.test
  "Regression guard for --ext-* CLI flags.

   These were dead for every extension: flags register namespace-prefixed as
   `ns__name`, but the CLI took the short name with (last (.split name \"/\")),
   which never strips `__`, so nothing ever matched. Compounding it, extensions
   load before the CLI's late resolve pass runs, so an extension reading its own
   flag during activation saw nil regardless."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions :as ext]))

(describe "ext-flag-short-name" (fn []
                                  (it "strips the ns__ prefix registrations actually use"
                                      (fn []
                                        (-> (expect (ext/ext-flag-short-name "openwiki__openwiki")) (.toBe "openwiki"))
                                        (-> (expect (ext/ext-flag-short-name "small-model__profile")) (.toBe "profile"))))
                                  (it "still strips a slash, and leaves a bare name alone"
                                      (fn []
                                        (-> (expect (ext/ext-flag-short-name "ns/name")) (.toBe "name"))
                                        (-> (expect (ext/ext-flag-short-name "plain")) (.toBe "plain"))))))

(describe "parse-ext-flag-argv" (fn []
                                  (it "reads the bare boolean form as a present flag with no value"
                                      (fn []
                                        (let [m (ext/parse-ext-flag-argv #js ["--ext-openwiki"])]
                                          (-> (expect (contains? m "openwiki")) (.toBe true))
                                          (-> (expect (get m "openwiki")) (.toBeNil)))))
                                  (it "reads the =value form"
                                      (fn []
                                        (-> (expect (get (ext/parse-ext-flag-argv #js ["--ext-profile=fast"]) "profile"))
                                            (.toBe "fast"))))
                                  (it "ignores everything that is not --ext-*"
                                      (fn []
                                        (-> (expect (count (ext/parse-ext-flag-argv #js ["-p" "hi" "--model" "x"])))
                                            (.toBe 0))))))

(describe "coerce-flag-value" (fn []
                               (it "an absent flag stays nil so the declared default applies"
                                   (fn []
                                     (-> (expect (ext/coerce-flag-value "boolean" :absent)) (.toBeNil))))
                               (it "bare boolean is true; =false is false"
                                   (fn []
                                     (-> (expect (ext/coerce-flag-value "boolean" nil)) (.toBe true))
                                     (-> (expect (ext/coerce-flag-value "boolean" "false")) (.toBe false))))
                               (it "coerces numbers and strings by declared type"
                                   (fn []
                                     (-> (expect (ext/coerce-flag-value "number" "42")) (.toBe 42))
                                     (-> (expect (ext/coerce-flag-value "string" nil)) (.toBe ""))))))
