(ns agent.extensions.bash-suite.output-handling
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            ["node:os" :as os]
            ["ai" :refer [tool]]
            ["zod" :as z]
            [agent.extensions.bash-suite.shared :as shared]
            [agent.tool-result-policy :as policy]
            [clojure.string :as str]))

;; ── Output registry ──────────────────────────────────────────

(def output-registry (atom {}))

;; ── Truncation logic ─────────────────────────────────────────

(defn- parse-bash-result [result-str]
  (try
    (let [parsed (js/JSON.parse result-str)]
      {:stdout   (or (.-stdout parsed) "")
       :stderr   (or (.-stderr parsed) "")
       :exitCode (.-exitCode parsed)
       :raw      parsed})
    (catch :default _e nil)))

(defn- byte-size [s]
  (count (str s)))

(defn truncate-and-save
  "Truncate stdout if over max-bytes, save full to temp file.
   Returns new result JSON string or nil if no truncation needed."
  [result-str config]
  (let [parsed (parse-bash-result result-str)]
    (when parsed
      (let [stdout     (:stdout parsed)
            stderr     (:stderr parsed)
            exit-code  (:exitCode parsed)
            stdout-size (byte-size stdout)
            max-bytes  (:max-bytes config)]
        (when (> stdout-size max-bytes)
          (let [id       (shared/generate-id)
                temp-dir (shared/temp-output-dir config)
                fpath    (path/join temp-dir (str "nyma-bash-" id ".txt"))]
            ;; Save full output to temp file
            (try
              (when-not (fs/existsSync temp-dir)
                (fs/mkdirSync temp-dir #js {:recursive true}))
              (fs/writeFileSync fpath stdout "utf8")
              (swap! output-registry assoc id
                     {:path fpath :byte-size stdout-size :created-at (js/Date.now)})
              (swap! shared/suite-stats update-in [:output-handling :temp-files-created] inc)
              (catch :default _e nil))
            ;; Middle-truncate
            (let [head-lines (:head-lines config)
                  tail-lines (:tail-lines config)
                  notice     (str "... (output truncated, " stdout-size " bytes total)\n"
                                  "To retrieve full output: use retrieve_bash_output with id \"" id "\"")
                  truncated  (shared/truncate-middle stdout head-lines tail-lines notice)
                  saved      (- stdout-size (byte-size truncated))]
              (swap! shared/suite-stats update :output-handling
                     (fn [s] (-> s
                                 (update :truncations inc)
                                 (update :bytes-saved + saved))))
              (js/JSON.stringify
               #js {:stdout   truncated
                    :stderr   stderr
                    :exitCode exit-code}))))))))

;; ── Retrieve tool ────────────────────────────────────────────

(defn- make-retrieve-tool []
  (tool
   #js {:description "Retrieve full output from a previously truncated bash command result."
        :inputSchema (.object z
                              #js {:id     (-> (.string z) (.describe "Output ID from truncation notice"))
                                   :offset (-> (.number z) (.optional)
                                               (.describe "Line offset to start from (0-based)"))
                                   :limit  (-> (.number z) (.optional)
                                               (.describe "Max lines to return (default: all)"))})
        :execute (fn [args]
                   (let [id     (.-id args)
                         offset (or (.-offset args) 0)
                         limit  (.-limit args)
                         entry  (get @output-registry (str id))]
                     (if-not entry
                       (str "No output found for id: " id)
                       (let [fpath (:path entry)]
                         (if-not (fs/existsSync fpath)
                           (str "Output file not found: " fpath)
                           (let [content (fs/readFileSync fpath "utf8")
                                 lines   (.split content "\n")
                                 sliced  (if limit
                                           (.slice lines offset (+ offset limit))
                                           (.slice lines offset))]
                             (swap! shared/suite-stats update-in
                                    [:output-handling :retrievals] inc)
                             (.join sliced "\n")))))))}))

;; ── Extension activation ─────────────────────────────────────

(defn activate [api]
  (let [config  (shared/load-config)
        oh-cfg  (:output-handling config)]

    ;; Register bash policy so the tool-result-policy envelope reflects this
    ;; extension's configured limit. Overrides the default (12000).
    ;; When output_handling is not loaded, bash falls back to the default policy.
    (policy/register-policy! "bash" {:max-string-length (:max-bytes oh-cfg)})

    ;; Middleware leave phase for output truncation
    (.addMiddleware api
                    #js {:name  "bash-suite/output-handling"
                         :leave (fn [ctx]
                                  (let [tool-name (.-tool-name ctx)]
                                    (if (and (:enabled oh-cfg)
                                             (shared/is-bash-tool? tool-name))
                                      (let [result    (.-result ctx)
                                            truncated (truncate-and-save result oh-cfg)]
                                        (when truncated
                                          (aset ctx "result" truncated))
                                        ctx)
                                      ctx)))})

    ;; Register retrieve tool
    (.registerTool api "retrieve_bash_output" (make-retrieve-tool))

    ;; Return deactivator
    (fn []
      (policy/unregister-policy! "bash")
      (.removeMiddleware api "bash-suite/output-handling")
      (.unregisterTool api "retrieve_bash_output")
      (reset! output-registry {}))))
