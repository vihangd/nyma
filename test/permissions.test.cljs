(ns permissions.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.permissions :refer [all-capabilities check parse-capabilities]]))

(describe "permissions" (fn []
  (it "check returns true for granted capability"
    (fn []
      (-> (expect (check #{:tools :events} :tools)) (.toBe true))))

  (it "check returns false for ungrated capability"
    (fn []
      (-> (expect (check #{:tools} :events)) (.toBe false))))

  (it ":all grants every capability"
    (fn []
      (-> (expect (check #{:all} :tools)) (.toBe true))
      (-> (expect (check #{:all} :events)) (.toBe true))
      (-> (expect (check #{:all} :ui)) (.toBe true))))

  (it "parse-capabilities keeps strings as set members"
    (fn []
      (let [caps (parse-capabilities ["tools" "events"])]
        (-> (expect (contains? caps "tools")) (.toBe true))
        (-> (expect (contains? caps "events")) (.toBe true)))))

  (it "parse-capabilities nil returns :all"
    (fn []
      (let [caps (parse-capabilities nil)]
        (-> (expect (contains? caps :all)) (.toBe true)))))

  (it "all-capabilities has expected set"
    (fn []
      (-> (expect (contains? all-capabilities :tools)) (.toBe true))
      (-> (expect (contains? all-capabilities :middleware)) (.toBe true))
      (-> (expect (contains? all-capabilities :ui)) (.toBe true))))

  (it "round-trip: parse-capabilities result works with check"
    (fn []
      (let [caps (parse-capabilities ["tools" "events"])]
        (-> (expect (check caps :tools)) (.toBe true))
        (-> (expect (check caps :events)) (.toBe true))
        (-> (expect (check caps :middleware)) (.toBe false)))))

  (it "round-trip: nil parse gives :all which grants everything"
    (fn []
      (let [caps (parse-capabilities nil)]
        (-> (expect (check caps :tools)) (.toBe true))
        (-> (expect (check caps :ui)) (.toBe true))
        (-> (expect (check caps :middleware)) (.toBe true)))))))
