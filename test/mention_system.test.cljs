(ns mention-system.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.mention-system :as ms]
            [agent.ui.mention-picker :as mp]))

;;; ─── Mention detection ─────────────────────────────────────────

(describe "mention-system:detect" (fn []
  (let [reg (ms/create-mention-registry)
        detect (:detect reg)]

    (it "finds @ at end of text"
      (fn []
        (let [result (detect "hello @")]
          (-> (expect (some? result)) (.toBe true))
          (-> (expect (:trigger result)) (.toBe "@"))
          (-> (expect (:query result)) (.toBe ""))
          (-> (expect (:start-idx result)) (.toBe 6)))))

    (it "extracts query after @"
      (fn []
        (let [result (detect "hello @src/ag")]
          (-> (expect (some? result)) (.toBe true))
          (-> (expect (:query result)) (.toBe "src/ag")))))

    (it "returns nil when no @ present"
      (fn []
        (-> (expect (detect "hello world")) (.toBeFalsy))))

    (it "returns nil for empty text"
      (fn []
        (-> (expect (detect "")) (.toBeFalsy))))

    (it "returns nil for nil text"
      (fn []
        (-> (expect (detect nil)) (.toBeFalsy))))

    (it "finds @ at start of text"
      (fn []
        (let [result (detect "@readme")]
          (-> (expect (some? result)) (.toBe true))
          (-> (expect (:query result)) (.toBe "readme"))
          (-> (expect (:start-idx result)) (.toBe 0)))))

    (it "returns nil when @ is followed by space (completed mention)"
      (fn []
        (-> (expect (detect "hello @file.txt more text")) (.toBeFalsy))))

    (it "only triggers @ preceded by whitespace or at start"
      (fn []
        ;; @ in middle of word should not trigger
        (-> (expect (detect "email@test")) (.toBeFalsy)))))))

;;; ─── Mention registry ──────────────────────────────────────────

(describe "mention-system:registry" (fn []
  (it "register adds provider, get-providers returns it"
    (fn []
      (let [reg (ms/create-mention-registry)]
        ((:register reg) "files" {:label "Files" :trigger "@"
                                   :search (fn [_q] []) :resolve (fn [_i] nil)})
        (let [providers ((:get-providers reg))]
          (-> (expect (count providers)) (.toBe 1))
          (-> (expect (:id (first providers))) (.toBe "files"))
          (-> (expect (:label (first providers))) (.toBe "Files"))))))

  (it "unregister removes provider"
    (fn []
      (let [reg (ms/create-mention-registry)]
        ((:register reg) "files" {:label "Files" :search (fn [_] [])})
        ((:unregister reg) "files")
        (-> (expect (count ((:get-providers reg)))) (.toBe 0)))))

  (it "multiple providers coexist"
    (fn []
      (let [reg (ms/create-mention-registry)]
        ((:register reg) "files" {:label "Files" :search (fn [_] [])})
        ((:register reg) "symbols" {:label "Symbols" :search (fn [_] [])})
        (-> (expect (count ((:get-providers reg)))) (.toBe 2)))))))

;;; ─── Mention picker ────────────────────────────────────────────

(describe "mention-picker" (fn []
  (it "renders with items"
    (fn []
      (let [items    [{:label "README.md" :value "README.md"}
                      {:label "package.json" :value "package.json"}]
            picker   (mp/create-picker items "" (fn [_]))]
        (let [output (.render picker 80 24)]
          (-> (expect (.includes output "README.md")) (.toBe true))
          (-> (expect (.includes output "package.json")) (.toBe true))))))

  (it "filters items by query"
    (fn []
      (let [items    [{:label "README.md" :value "README.md"}
                      {:label "package.json" :value "package.json"}]
            picker   (mp/create-picker items "read" (fn [_]))]
        (let [output (.render picker 80 24)]
          (-> (expect (.includes output "README.md")) (.toBe true))
          ;; package.json should not match "read"
          (-> (expect (.includes output "package.json")) (.toBe false))))))

  (it "enter selects current item"
    (fn []
      (let [selected (atom nil)
            items    [{:label "a.txt" :value "a.txt"}]
            picker   (mp/create-picker items "" (fn [item] (reset! selected item)))]
        (.render picker 80 24)
        (.onInput picker nil #js {:return true})
        (-> (expect (some? @selected)) (.toBe true))
        (-> (expect (:label @selected)) (.toBe "a.txt")))))

  (it "escape cancels with nil"
    (fn []
      (let [selected (atom :pending)
            items    [{:label "a.txt" :value "a.txt"}]
            picker   (mp/create-picker items "" (fn [item] (reset! selected item)))]
        (.render picker 80 24)
        (.onInput picker nil #js {:escape true})
        (-> (expect @selected) (.toBeNull)))))

  (it "shows 'No matches' when filter eliminates all items"
    (fn []
      (let [items  [{:label "a.txt" :value "a.txt"}]
            picker (mp/create-picker items "zzz" (fn [_]))]
        (let [output (.render picker 80 24)]
          (-> (expect (.includes output "No matches")) (.toBe true))))))

  (it "description is shown when present"
    (fn []
      (let [items  [{:label "utils.cljs" :value "src/utils.cljs" :description "src/"}]
            picker (mp/create-picker items "" (fn [_]))]
        (let [output (.render picker 80 24)]
          (-> (expect (.includes output "src/")) (.toBe true))))))))
