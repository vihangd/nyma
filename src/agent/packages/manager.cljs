(ns agent.packages.manager
  (:require ["node:child_process" :as cp]
            ["node:path" :as path]
            ["node:fs" :as fs]
            ["node:os" :as os]))

(defn- run-cmd [cmd]
  (js/Promise.
    (fn [resolve reject]
      (let [proc (cp/exec cmd
                   (fn [err stdout stderr]
                     (if err
                       (reject err)
                       (resolve {:stdout stdout :stderr stderr}))))]
        proc))))

(defn- register-package
  "Read package manifest and register extensions, skills, prompts, themes."
  [pkg-path]
  (let [pkg-json-path (path/join pkg-path "package.json")]
    (when (fs/existsSync pkg-json-path)
      (let [pkg-json (-> (fs/readFileSync pkg-json-path "utf8")
                         (js/JSON.parse)
                         (js->clj :keywordize-keys true))
            manifest  (or (:agent pkg-json) {})]
        {:extensions (get manifest :extensions ["extensions"])
         :skills     (get manifest :skills ["skills"])
         :prompts    (get manifest :prompts ["prompts"])
         :themes     (get manifest :themes ["themes"])}))))

(defn ^:async install
  "Install a package from npm, git, or local path."
  [source]
  (cond
    (.startsWith source "npm:")
    (let [pkg (subs source 4)]
      (js-await (run-cmd (str "bun add " pkg)))
      (register-package pkg))

    (.startsWith source "git:")
    (let [repo (subs source 4)
          dir  (path/join (os/homedir) ".nyma" "git"
                 (-> repo (.replace ":" "/") (.replace ".git" "")))]
      (js-await (run-cmd (str "git clone " repo " " dir)))
      (when (fs/existsSync (path/join dir "package.json"))
        (js-await (run-cmd (str "cd " dir " && bun install"))))
      (register-package dir))

    :else
    (register-package (path/resolve source))))
