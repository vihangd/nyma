(ns command-vocabulary.test
  "The slash picker, /help and \"did you mean\" must name commands the same
   way, and one splitter must handle quotes and flags for all of them."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.commands.parser :as parser]
            [agent.commands.resolver :refer [resolve-command]]
            [agent.commands.builtins :as builtins]
            [agent.extensions.workspace-config.aliases :as aliases]
            [agent.modes.interactive :refer [slash-command-items
                                             unknown-command-text]]))

(defn- cmd [& [overrides]]
  (merge {:description "a command" :handler (fn [_ _] nil)} (or overrides {})))

(def ^:private registry
  {"help"              (cmd)
   "spec"              (cmd)
   "model-roles__role" (cmd {:description "Switch role"})
   "secret"            (cmd {:hidden? true})
   "off"               (cmd {:enabled? (fn [] false)})
   "a__plan"           (cmd)
   "b__plan"           (cmd)})

(defn- names [items] (vec (map :name items)))

(describe
 "the slash picker offers the same names /help prints"
 (fn []
   (it "shows /role, not /model-roles__role"
       (fn []
         (-> (expect (.includes (clj->js (names (slash-command-items registry))) "role"))
             (.toBe true))
         (-> (expect (.includes (clj->js (names (slash-command-items registry)))
                                "model-roles__role"))
             (.toBe false))))

   (it "the short name it offers actually resolves to the command"
       (fn []
         (-> (expect (some? (resolve-command registry "role"))) (.toBe true))))

   (it "keeps the full key when the short name is ambiguous"
       (fn []
         (let [ns (names (slash-command-items registry))]
           ;; `plan` alone would be inert — resolve-command refuses an
           ;; ambiguous suffix match — so both keep their namespace.
           (-> (expect (.includes (clj->js ns) "a__plan")) (.toBe true))
           (-> (expect (.includes (clj->js ns) "b__plan")) (.toBe true))
           (-> (expect (.includes (clj->js ns) "plan")) (.toBe false)))))

   (it "never offers a hidden or disabled command"
       (fn []
         (let [ns (clj->js (names (slash-command-items registry)))]
           (-> (expect (.includes ns "secret")) (.toBe false))
           (-> (expect (.includes ns "off")) (.toBe false)))))

   (it "offers no name with padding whitespace the editor would insert"
       (fn []
         (doseq [n (names (slash-command-items registry))]
           (-> (expect n) (.toBe (.trim n))))))))

(describe
 "a mistyped command suggests the right one"
 (fn []
   (it "suggests /spec for /sepc (a transposition, not a prefix)"
       (fn []
         (-> (expect (.includes (unknown-command-text "sepc" registry) "/spec"))
             (.toBe true))))

   (it "suggests by prefix too"
       (fn []
         (-> (expect (.includes (unknown-command-text "hel" registry) "/help"))
             (.toBe true))))

   (it "prints the display name, not the raw ns__name key"
       (fn []
         (let [text (unknown-command-text "rol" registry)]
           (-> (expect (.includes text "/role")) (.toBe true))
           (-> (expect (.includes text "model-roles__role")) (.toBe false)))))

   (it "never suggests a hidden command"
       (fn []
         (-> (expect (.includes (unknown-command-text "secre" registry) "/secret"))
             (.toBe false))))

   (it "says nothing was close when nothing is"
       (fn []
         (let [text (unknown-command-text "zzzzqqqqq" registry)]
           (-> (expect (.includes text "Did you mean")) (.toBe false))
           (-> (expect (.includes text "/help")) (.toBe true)))))))

(describe
 "one argument splitter: quotes and flags"
 (fn []
   (it "keeps a double-quoted argument in one piece"
       (fn []
         (let [{:keys [args]} (parser/parse-command-args "\"my session name\"")]
           (-> (expect (count args)) (.toBe 1))
           (-> (expect (first args)) (.toBe "my session name")))))

   (it "splits on runs of whitespace, dropping empties"
       (fn []
         (-> (expect (js/JSON.stringify
                      (clj->js (:args (parser/parse-command-args "  a   b\tc ")))))
             (.toBe (js/JSON.stringify (clj->js ["a" "b" "c"]))))))

   (it "reads --flag, --flag=value and --no-flag"
       (fn []
         (let [{:keys [flags]} (parser/parse-command-args
                                "--force --profile=fast --no-cache")]
           (-> (expect (get flags "force")) (.toBe true))
           (-> (expect (get flags "profile")) (.toBe "fast"))
           (-> (expect (get flags "cache")) (.toBe false)))))

   (it "keeps hyphenated flag keys spelled as typed"
       (fn []
         (-> (expect (get (:flags (parser/parse-command-args "--dry-run")) "dry-run"))
             (.toBe true))))

   (it "still hands handlers every token, flags included, as today"
       (fn []
         ;; Commands that hand-parse their own `--flags` must not break.
         (-> (expect (js/JSON.stringify
                      (clj->js (:args (parser/parse-command-args "--remove foo")))))
             (.toBe (js/JSON.stringify (clj->js ["--remove" "foo"]))))))

   (it "offers the flag-free tokens separately as :positional"
       (fn []
         (-> (expect (js/JSON.stringify
                      (clj->js (:positional
                                (parser/parse-command-args "--remove foo bar")))))
             (.toBe (js/JSON.stringify (clj->js ["foo" "bar"]))))))

   (it "keeps an unterminated quote's text instead of dropping it"
       (fn []
         (-> (expect (js/JSON.stringify
                      (clj->js (:args (parser/parse-command-args "\"half open")))))
             (.toBe (js/JSON.stringify (clj->js ["half open"]))))))

   (it "handles no arguments at all"
       (fn []
         (-> (expect (count (:args (parser/parse-command-args "")))) (.toBe 0))
         (-> (expect (count (:args (parser/parse-command-args nil)))) (.toBe 0))))))

(describe
 "migrated commands read flags off ctx, not by matching '--' tokens"
 (fn []
   ;; The ctx shape interactive mode's run-command! builds.
   (let [ctx (fn [text]
               (let [p (parser/parse-command-args text)]
                 #js {:flags      (clj->js (:flags p))
                      :positional (clj->js (:positional p))}))]

     (it "/alias --remove foo sees remove=true and foo as the only positional"
         (fn []
           (let [c (ctx "--remove foo")]
             (-> (expect (get (aliases/ctx-flags c) "remove")) (.toBe true))
             (-> (expect (js/JSON.stringify (clj->js (aliases/ctx-positional c))))
                 (.toBe (js/JSON.stringify (clj->js ["foo"])))))))

     (it "/name \"Q3 planning\" keeps the quoted name as one argument"
         (fn []
           (-> (expect (js/JSON.stringify
                        (clj->js (builtins/positional-args (ctx "\"Q3 planning\"")))))
               (.toBe (js/JSON.stringify (clj->js ["Q3 planning"]))))))

     (it "falls back to the args vector when the caller is not interactive mode"
         (fn []
           (-> (expect (builtins/positional-args nil)) (.toBeNil))
           (-> (expect (builtins/positional-args #js {})) (.toBeNil))
           (-> (expect (js/JSON.stringify (clj->js (aliases/ctx-flags #js {}))))
               (.toBe "{}")))))))
