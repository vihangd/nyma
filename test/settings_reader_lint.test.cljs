(ns settings-reader-lint.test
  "Every documented settings key must be read by something.

   Six were not: steering-mode, follow-up-mode, tool-display,
   tool-display-max-lines, scrollback-mode and status-line. All six are in the
   defaults, all six are in the README's defaults block, and none has a reader
   in src/. `:status-line`'s only apparent reader is a docstring EXAMPLE in
   utils/validation.cljs.

   Two of them were never built; four were live before `acee030` and were
   deleted with the Ink UI, leaving the key, the docs, and — for scrollback — a
   doc comment pointing at two files that no longer exist. A user who sets one
   gets silence, which is the same thing they get if they set it correctly.

   This lint does not care WHY a key is dead. It requires that every key either
   has a reader or is listed below with a reason, so the next deletion has to
   make a choice rather than leave a lie behind."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [agent.settings.manager :as sm]))

(def ^:private manager-path
  (path/resolve (js/process.cwd) "src" "agent" "settings" "manager.cljs"))

;; READ FROM PRODUCTION, not mirrored. settings/manager.cljs owns the list, so
;; the startup warning and this lint cannot disagree — a lint that keeps its own
;; copy of the thing it guards is how the two capability lints drifted.
;; (A docstring here compiled to the VALUE under squint, silently making this
;; def a string and swallowing the rest of the file.)
(def known-unread sm/inert-keys)

(defn default-keys
  "Top-level keys of the settings defaults map."
  [source]
  (->> (str/split-lines source)
       (keep (fn [l] (second (re-find #"^\s{2,4}:([a-z][a-z0-9-]*)\s" l))))
       ;; `:else` is a cond arm, not a settings key.
       (remove (fn [k] (= k "else")))
       set))

(defn- readers
  "Files outside manager.cljs that mention `:key` or \"key\"."
  [k]
  (let [out (try
              (.toString
               (.execSync (js/require "node:child_process")
                          (str "grep -rl -e ':" k "\\b' -e '\"" k "\"' src/ || true")))
              (catch :default _ ""))]
    (->> (str/split (str/trim out) #"\n")
         (remove str/blank?)
         (remove (fn [f] (.includes f "settings/manager.cljs")))
         ;; A docstring example is not a reader — utils/validation.cljs cites
         ;; [:status-line :left-segments] purely to illustrate a :path field.
         (remove (fn [f] (.includes f "utils/validation.cljs")))
         vec)))

(describe "every settings key has a reader, or a written reason" (fn []

                                                                   (it "parses the defaults map"
                                                                       (fn []
        ;; Guard the guard.
                                                                         (-> (expect (> (count (default-keys (fs/readFileSync manager-path "utf8"))) 10))
                                                                             (.toBe true))
        ;; …and must include keys we know are live, or the matcher is wrong.
                                                                         (let [ks (default-keys (fs/readFileSync manager-path "utf8"))]
                                                                           (-> (expect (contains? ks "thinking")) (.toBe true))
                                                                           (-> (expect (contains? ks "compaction")) (.toBe true)))))

                                                                   (it "finds readers for a key that certainly has one"
                                                                       (fn []
        ;; Detector self-test: a broken grep would report every key as dead and
        ;; the allowlist would silently absorb it.
                                                                         (-> (expect (> (count (readers "compaction")) 0)) (.toBe true))
                                                                         (-> (expect (count (readers "definitely-not-a-real-settings-key"))) (.toBe 0))))

                                                                   (it "has no unread key that is not accounted for"
                                                                       (fn []
                                                                         (let [ks   (default-keys (fs/readFileSync manager-path "utf8"))
                                                                               dead (->> ks
                                                                                         (filter (fn [k] (zero? (count (readers k)))))
                                                                                         (remove known-unread)
                                                                                         sort vec)]
                                                                           (-> (expect (str/join ", " dead)) (.toBe "")))))

                                                                   (it "has no allowlist entry that has quietly come back to life"
                                                                       (fn []
        ;; A stale allowlist is its own failure: it would keep claiming a
        ;; working setting does nothing.
                                                                         (let [revived (->> (keys known-unread)
                                                                                            (filter (fn [k] (pos? (count (readers k)))))
                                                                                            sort vec)]
                                                                           (-> (expect (str/join ", " revived)) (.toBe "")))))

                                                                   (it "has no allowlist entry that has been removed from the defaults"
                                                                       (fn []
                                                                         (let [ks    (default-keys (fs/readFileSync manager-path "utf8"))
                                                                               stale (->> (keys known-unread) (remove ks) sort vec)]
                                                                           (-> (expect (str/join ", " stale)) (.toBe "")))))

                                                                   (it "the startup warning names every allowlisted key"
                                                                       (fn []
        ;; The allowlist is only acceptable because the user is told. If a key
        ;; is listed here it must appear in the warning, with its reason.
                                                                         (let [w (sm/inert-warning (into {} (map (fn [k] [k "x"]) (keys known-unread))))]
                                                                           (doseq [k (keys known-unread)]
                                                                             (-> (expect (.includes w k)) (.toBe true))
                                                                             (-> (expect (.includes w (get known-unread k))) (.toBe true))))))

                                                                   (it "says nothing when the user set none of them"
                                                                       (fn []
                                                                         (-> (expect (sm/inert-warning {"model" "x" "thinking" "high"})) (.toBeNil))
                                                                         (-> (expect (sm/inert-warning {})) (.toBeNil))))))

;; ── Manifest-declared extension settings ───────────────────────────────────
;; Same bargain as above, one level down: an extension declares its sections and
;; keys in extension.json, the loader registers them as defaults before
;; activation, and /settings shows them. Three ways that can lie — a declared
;; key nothing reads, a reader with nothing declared, a section name that is
;; already known-dead — get a check each.

(def ^:private ext-root
  (path/resolve (js/process.cwd) "src" "agent" "extensions"))

(defn- ext-dirs []
  (->> (vec (fs/readdirSync ext-root #js {:withFileTypes true}))
       (filter (fn [e] (.isDirectory e)))
       (map (fn [e] (.-name e)))
       (filter (fn [n] (fs/existsSync (path/join ext-root n "index.cljs"))))
       sort vec))

(defn- cljs-files
  "Every .cljs under `dir`, recursively."
  [dir]
  (->> (vec (fs/readdirSync dir #js {:withFileTypes true :recursive true}))
       (filter (fn [e] (and (.isFile e) (.endsWith (.-name e) ".cljs"))))
       (mapv (fn [e] (path/join (or (.-parentPath e) (.-path e) dir) (.-name e))))))

(defn- ext-source
  "All of one extension's own cljs source, concatenated."
  [dir]
  (->> (cljs-files (path/join ext-root dir))
       (map (fn [f] (try (fs/readFileSync f "utf8") (catch :default _ ""))))
       (str/join "\n")))

(defn- manifest [dir]
  (let [p (path/join ext-root dir "extension.json")]
    (when (fs/existsSync p)
      (try (js/JSON.parse (fs/readFileSync p "utf8")) (catch :default _ nil)))))

(defn- declared-sections
  "{section [key …]} from one manifest's `settings` block; {} when absent."
  [dir]
  (let [s (some-> (manifest dir) (aget "settings"))]
    (if (object? s)
      (reduce (fn [m sec]
                (let [v (aget s sec)]
                  (if (object? v) (assoc m sec (vec (js/Object.keys v))) m)))
              {} (js/Object.keys s))
      {})))

;; Extensions that read settings through `(.settings api …)` and legitimately
;; declare nothing. Written reasons, not a silent skip — the same rule the
;; inert-keys allowlist above lives by.
(def ^:private no-declaration-ok
  {"advisor"
   "reads only the core `roles` table ((:advisor (:roles settings))); the section belongs to settings/manager, not to this extension"
   "custom_provider_relay"
   "its setting is the top-level `providers` ARRAY; manifest-defaults expresses section -> {key -> default} only, and would register array indices as keys"
   "custom_provider_local"
   "same shape as relay: the top-level `local-models` ARRAY cannot be expressed as a section of keys"})

(describe "manifest-declared extension settings" (fn []

                                                   (it "finds the manifests" ; guard the guard
                                                       (fn []
                                                         (-> (expect (> (count (ext-dirs)) 30)) (.toBe true))
                                                         (-> (expect (count (declared-sections "todos"))) (.toBe 1))
                                                         (-> (expect (> (count (ext-source "todos")) 100)) (.toBe true))))

                                                   (it "declares no key its own sources never read"
                                                       (fn []
        ;; Only the section's own keys, not nested ones: a nested default is a
        ;; value, and names like `default` inside it are not settings keys.
                                                         (let [orphans
                                                               (->> (ext-dirs)
                                                                    (mapcat
                                                                     (fn [dir]
                                                                       (let [src (ext-source dir)]
                                                                         (->> (declared-sections dir)
                                                                              (mapcat (fn [[sec ks]]
                                                                                        (->> ks
                                                                                             (remove (fn [k]
                                                                                                       (or (.includes src (str ":" k))
                                                                                                           (.includes src (str "\"" k "\"")))))
                                                                                             (map (fn [k] (str dir "#" sec "." k))))))))))
                                                                    sort vec)]
                                                           (-> (expect (str/join ", " orphans)) (.toBe "")))))

                                                   (it "every extension that reads settings declares a settings block"
                                                       (fn []
                                                         (let [undeclared
                                                               (->> (ext-dirs)
                                                                    (filter (fn [dir] (.includes (ext-source dir) "(.settings api")))
                                                                    (remove (fn [dir] (seq (declared-sections dir))))
                                                                    (remove no-declaration-ok)
                                                                    sort vec)]
                                                           (-> (expect (str/join ", " undeclared)) (.toBe "")))))

                                                   (it "has no allowlisted extension that has since declared one"
                                                       (fn []
        ;; A stale allowlist entry would keep excusing a manifest that exists.
                                                         (let [revived (->> (keys no-declaration-ok)
                                                                            (filter (fn [dir] (seq (declared-sections dir))))
                                                                            sort vec)]
                                                           (-> (expect (str/join ", " revived)) (.toBe "")))))

                                                   (it "declares no section name that is already known-dead"
                                                       (fn []
        ;; `inert-keys` names settings the agent parses and nothing reads. An
        ;; extension claiming one as its section would resurrect the name
        ;; without resurrecting the behaviour.
                                                         (let [collisions
                                                               (->> (ext-dirs)
                                                                    (mapcat (fn [dir]
                                                                              (->> (keys (declared-sections dir))
                                                                                   (filter sm/inert-keys)
                                                                                   (map (fn [s] (str dir "#" s))))))
                                                                    sort vec)]
                                                           (-> (expect (str/join ", " collisions)) (.toBe "")))))))
