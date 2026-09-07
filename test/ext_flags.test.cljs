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

;;; ─── Defaults are coerced too ──────────────────────────────────────────────
;;
;; `coerce-flag-value` only ever saw CLI argv. `registerFlag` stored `:default`
;; RAW, and `getFlag` returns the default when there is no CLI override — so a
;; default of the wrong type reached the consumer untouched.
;;
;; One flag is exposed: desktop_notify passes `:default (:enabled config)`
;; straight from settings.json with no validation, so
;; `{"desktop-notify": {"enabled": "false"}}` stored the STRING "false", which
;; is truthy, and notifications stayed on. Every other default is a literal.
;;
;; Note this is NOT the CLI bug I first reported — `--ext-x=false` has worked
;; since registerFlag began defaulting :type to "boolean" (865b95a).

(describe "coerce-flag-default" (fn []

  (it "turns a stringly-typed boolean from settings JSON into a boolean"
      (fn []
        (-> (expect (ext/coerce-flag-default "boolean" "false")) (.toBe false))
        (-> (expect (ext/coerce-flag-default "boolean" "true"))  (.toBe true))))

  (it "leaves a real boolean alone"
      (fn []
        (-> (expect (ext/coerce-flag-default "boolean" false)) (.toBe false))
        (-> (expect (ext/coerce-flag-default "boolean" true))  (.toBe true))))

  (it "keeps nil as nil — absent is not false"
      (fn []
        ;; small_model and openwiki deliberately register no default so that
        ;; settings win; coercing nil to false would override them.
        (-> (expect (ext/coerce-flag-default "boolean" nil)) (.toBeNil))
        (-> (expect (ext/coerce-flag-default "string" nil))  (.toBeNil))))

  (it "coerces number and string defaults"
      (fn []
        (-> (expect (ext/coerce-flag-default "number" "42")) (.toBe 42))
        (-> (expect (ext/coerce-flag-default "number" 42))   (.toBe 42))
        (-> (expect (ext/coerce-flag-default "string" 7))    (.toBe "7"))))

  (it "coerce-flag-value still handles an absent type as boolean"
      (fn []
        ;; The case ext_flags never covered, and the reason the type-defaulting
        ;; behaviour went untested: registerFlag substitutes "boolean" before
        ;; calling this, so passing nil here documents the contract.
        (-> (expect (ext/coerce-flag-value "boolean" "false")) (.toBe false))
        (-> (expect (ext/coerce-flag-value "boolean" nil))     (.toBe true))))))
