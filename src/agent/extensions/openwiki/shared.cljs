(ns agent.extensions.openwiki.shared
  "Shared config, constants, and the OKF conformance check for OpenWiki.

   Config is settings-driven (.nyma/settings.json#openwiki), everything
   overridable — no hardcoded aliases. Defaults match the upstream
   langchain-ai/openwiki layout: an `openwiki/` bundle whose entry point is the
   OKF-reserved `index.md`."
  (:require [clojure.string :as str]))

(def okf-version "0.2")

(def default-config
  {:enabled  false                      ; opt-in (also via --ext-openwiki flag)
   :dir      "openwiki"                 ; output directory (repo-relative)
   :sections ["architecture" "workflows" "domain" "operations" "testing"]})

(defn config
  "Merge user settings#openwiki over defaults. `settings` is the JS object
   returned by api.getSettings (or nil)."
  [settings]
  (let [ow (when settings (aget settings "openwiki"))]
    (merge default-config
           (when ow
             (cond-> {}
               (some? (aget ow "enabled")) (assoc :enabled (aget ow "enabled"))
               (aget ow "dir")             (assoc :dir (aget ow "dir"))
               (aget ow "sections")        (assoc :sections (vec (aget ow "sections"))))))))

(defn metadata-file [dir] (str dir "/.last-update.json"))
(defn instructions-file [dir] (str dir "/INSTRUCTIONS.md"))

(defn under-dir?
  "True when `path` is inside the wiki `dir` (for progress status).

   A prefix check on the repo-relative path, not a substring one: with the
   default dir, `.includes` matched `src/agent/extensions/openwiki/git.cljs` and
   claimed the agent was documenting when it was editing this extension.

   ponytail: repo-relative only. An absolute path can't be told from a
   same-named nested directory without resolving the repo root, and this drives
   nothing but a status line — no status beats a wrong one."
  [dir path]
  (and (string? path)
       (let [p (if (.startsWith path "./") (.slice path 2) path)]
         (.startsWith p (str dir "/")))))

;; ── OKF v0.2 conformance ─────────────────────────────────────────
;; Spec: GoogleCloudPlatform/knowledge-catalog okf/SPEC.md. Three rules:
;;   1. every non-reserved .md carries a parseable YAML frontmatter block
;;   2. every frontmatter block declares a non-empty `type`
;;   3. reserved filenames (index.md, log.md) follow their structure
;; Deliberately lenient elsewhere: the spec says consumers MUST NOT reject a
;; bundle for missing optional fields, unknown types, or broken links.

(def reserved-files
  "Files exempt from the frontmatter rules. index.md and log.md are OKF's own
   reserved names; INSTRUCTIONS.md is ours — it is user-authored scope that the
   agent is told never to rewrite, so reporting it as non-conformant would have
   save_metadata inviting the agent to edit the one file it must not touch."
  #{"index.md" "log.md" "INSTRUCTIONS.md"})

(defn reserved?
  "True for an OKF reserved filename, whatever directory it sits in."
  [rel]
  (contains? reserved-files (last (.split (str rel) "/"))))

(defn parse-frontmatter
  "Split a `---` fenced YAML block off the head of `content`.
   Returns {:keys [fields body]} with `fields` nil when there is no block.
   A flat key: value scan — enough for the conformance rules, and it avoids
   pulling in a YAML dependency for a check this shallow."
  [content]
  (let [c (str content)]
    (if-not (.startsWith c "---")
      {:fields nil :body c}
      (let [end (.indexOf c "\n---" 3)]
        (if (neg? end)
          {:fields nil :body c}
          (let [block (.slice c 4 end)
                body  (.slice c (+ end 4))]
            {:fields (reduce (fn [acc line]
                               (let [i (.indexOf line ":")]
                                 (if (or (neg? i) (.startsWith (str/trim line) "#"))
                                   acc
                                   (assoc acc
                                          (str/trim (.slice line 0 i))
                                          (str/trim (.slice line (inc i)))))))
                             {}
                             (.split block "\n"))
             :body body}))))))

(defn- strip-quotes [v]
  (let [v (str/trim (str v))]
    (if (and (> (count v) 1)
             (or (and (.startsWith v "\"") (.endsWith v "\""))
                 (and (.startsWith v "'") (.endsWith v "'"))))
      (.slice v 1 -1)
      v)))

(defn page-violations
  "OKF violations for one file. `rel` is the bundle-relative path, `content`
   its text. Returns a seq of human-readable strings (empty = conformant)."
  [rel content]
  (let [{:keys [fields]} (parse-frontmatter content)]
    (cond
      ;; index.md: no frontmatter, except okf_version at the bundle root.
      (= "index.md" (last (.split (str rel) "/")))
      (cond
        (nil? fields)                     []
        (not= rel "index.md")             [(str rel ": only the bundle-root index.md may carry frontmatter")]
        (str/blank? (strip-quotes (get fields "okf_version" ""))) [(str rel ": root index.md frontmatter must declare okf_version")]
        :else                             [])

      (reserved? rel)
      (if (nil? fields) [] [(str rel ": reserved files carry no frontmatter")])

      (nil? fields)
      [(str rel ": missing YAML frontmatter (OKF requires a `---` block)")]

      (str/blank? (strip-quotes (get fields "type" "")))
      [(str rel ": frontmatter has no non-empty `type` (the one required OKF field)")]

      :else [])))

(defn conformance-report
  "Fold per-file violations into a report string, or nil when conformant.
   `pages` is a seq of [rel content]."
  [pages]
  (let [vs (mapcat (fn [[rel content]] (page-violations rel content)) pages)]
    (when (seq vs)
      (str "⚠️  OKF v" okf-version " violations (" (count vs) "):\n"
           (str/join "\n" (map #(str "  - " %) vs))))))
