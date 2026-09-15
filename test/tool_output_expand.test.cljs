(ns tool-output-expand.test
  "ctrl+o expands the last tool's output; `edit` expands to a diff; a failed
   call previews its error without being asked."
  (:require ["bun:test" :refer [describe it expect]]
            [agent.ui.chat-renderer :refer [render-message error-preview-lines]]
            [agent.ui.app-reducers :as r]
            [agent.keybinding-registry :as kbr]
            [agent.utils.ansi :refer [fg]]))

(def ^:private theme
  {:colors {:primary "#7aa2f7" :secondary "#9ece6a" :muted "#565f89"
            :border "#3b4261" :error "#f7768e" :warning "#e0af68"
            :success "#9ece6a"}})

(defn- strip-ansi [s]
  (.replace s (js/RegExp. (str (js/String.fromCharCode 27) "\\[[0-9;]*m") "g") ""))

(defn- render [msg]
  (render-message {:msg msg :width 80 :theme theme :md-cache nil}))

(defn- numbered [n] (.join (to-array (map (fn [i] (str "line " i)) (range n))) "\n"))

(defn- end-msg [& [extra]]
  (merge {:role "tool-end" :tool-name "bash" :args {:command "ls"}
          :result (numbered 12) :duration 5 :max-lines 5 :id "t1"}
         extra))

(describe "expandable tool output" (fn []
                                     (it "collapsed renders the one-liner only"
                                         (fn []
                                           (-> (expect (count (render (end-msg {:expanded false})))) (.toBe 1))))

                                     (it "expanded renders max-lines body lines under the one-liner, then a tail"
                                         (fn []
                                           (let [lines (render (end-msg {:expanded true}))
                                                 text  (mapv strip-ansi lines)]
          ;; 1 header + 5 body + 1 tail
                                             (-> (expect (count lines)) (.toBe 7))
                                             (-> (expect (nth text 1)) (.toBe "  line 0"))
                                             (-> (expect (nth text 5)) (.toBe "  line 4"))
                                             (-> (expect (.includes (nth text 6) "… 7 more lines")) (.toBe true)))))

                                     (it "no tail when the result fits"
                                         (fn []
                                           (let [lines (render (end-msg {:expanded true :result "a\nb"}))]
                                             (-> (expect (count lines)) (.toBe 3))
                                             (-> (expect (.includes (strip-ansi (last lines)) "more lines")) (.toBe false)))))

                                     (it "an expanded edit shows -/+ lines of old_string vs new_string"
                                         (fn []
                                           (let [lines (render (end-msg {:expanded true :tool-name "edit"
                                                                         :args {:path "f.txt" :old_string "keep\nold"
                                                                                :new_string "keep\nnew"}
                                                                         :result "Edit applied successfully"}))
                                                 text  (mapv strip-ansi lines)]
                                             (-> (expect (count lines)) (.toBe 4))
                                             (-> (expect (nth text 1)) (.toBe "   keep"))
                                             (-> (expect (nth text 2)) (.toBe "  -old"))
                                             (-> (expect (nth text 3)) (.toBe "  +new"))
          ;; Removed in the error colour, added in success.
                                             (-> (expect (.includes (nth lines 2) (fg "#f7768e"))) (.toBe true))
                                             (-> (expect (.includes (nth lines 3) (fg "#9ece6a"))) (.toBe true)))))

                                     (it "an expanded write shows the content it wrote"
                                         (fn []
                                           (let [text (mapv strip-ansi
                                                            (render (end-msg {:expanded true :tool-name "write"
                                                                              :args {:path "f" :content "hello\nworld"}
                                                                              :result "written"})))]
                                             (-> (expect (nth text 1)) (.toBe "  hello"))
                                             (-> (expect (nth text 2)) (.toBe "  world")))))

                                     (it "an expanded bash shows stdout lines, not the JSON envelope"
                                         (fn []
                                           (let [env  (js/JSON.stringify #js {:stdout "l1\nl2\n" :stderr "" :exitCode 0
                                                                              :timedOut false :signal nil :aborted false})
                                                 text (mapv strip-ansi (render (end-msg {:expanded true :result env})))]
                                             (-> (expect (count text)) (.toBe 3))
                                             (-> (expect (nth text 1)) (.toBe "  l1"))
                                             (-> (expect (nth text 2)) (.toBe "  l2"))
                                             (-> (expect (.includes (.join (to-array text) "\n") "{")) (.toBe false)))))

                                     (it "an expanded bash still shows stdout lines when the envelope was hard-wrapped to terminal width"
                                         (fn []
                                           (let [env  (js/Bun.wrapAnsi (js/JSON.stringify #js {:stdout "l1\nl2\n" :stderr "" :exitCode 0
                                                                              :timedOut false :signal nil :aborted false}) 40 #js {:hard true})
                                                 text (mapv strip-ansi (render (end-msg {:expanded true :result env})))]
                                             (-> (expect (count text)) (.toBe 3))
                                             (-> (expect (nth text 1)) (.toBe "  l1"))
                                             (-> (expect (nth text 2)) (.toBe "  l2"))
                                             (-> (expect (.includes (.join (to-array text) "\n") "{")) (.toBe false)))))

                                     (it "an expanded bash still parses the envelope when a hook appended text after it"
                                         (fn []
                                           ;; PostToolUse hook stdout lands after the JSON; a `}` inside
                                           ;; stdout must not end the object early.
                                           (let [env  (str (js/JSON.stringify #js {:stdout "a } b\n" :stderr "" :exitCode 0})
                                                           "\nSession status updated.\n")
                                                 text (mapv strip-ansi (render (end-msg {:expanded true :result env})))]
                                             (-> (expect (nth text 1)) (.toBe "  a } b"))
                                             (-> (expect (nth text 2)) (.toBe "  Session status updated."))
                                             (-> (expect (.includes (.join (to-array text) "\n") "{")) (.toBe false)))))

                                     (it "an expanded bash prefixes stderr with ! and shows a non-zero exit"
                                         (fn []
                                           (let [env   (js/JSON.stringify #js {:stdout "ok" :stderr "boom\n" :exitCode 2})
                                                 lines (render (end-msg {:expanded true :result env}))
                                                 text  (mapv strip-ansi lines)]
                                             (-> (expect (nth text 1)) (.toBe "  ok"))
                                             (-> (expect (nth text 2)) (.toBe "  !boom"))
                                             (-> (expect (nth text 3)) (.toBe "  exit 2"))
                                             (-> (expect (.includes (nth lines 2) (fg "#f7768e"))) (.toBe true)))))

                                     (it "a bash result that is not an envelope still renders as-is"
                                         (fn []
                                           (let [text (mapv strip-ansi (render (end-msg {:expanded true :result "plain\ntext"})))]
                                             (-> (expect (nth text 1)) (.toBe "  plain"))
                                             (-> (expect (nth text 2)) (.toBe "  text")))))

                                     (it "a failed call previews its error even when collapsed"
                                         (fn []
                                           (let [lines (render (end-msg {:expanded false :is-error true
                                                                         :result (numbered 30)}))]
                                             (-> (expect (count lines)) (.toBe (+ 2 error-preview-lines)))
                                             (-> (expect (.includes (strip-ansi (last lines)) "more lines")) (.toBe true)))))

                                     (it "a running tool has no body"
                                         (fn []
                                           (-> (expect (count (render {:role "tool-start" :tool-name "bash"
                                                                       :args {:command "ls"} :expanded true :id "x"})))
                                               (.toBe 1))))))

(describe "tool-toggle-expanded" (fn []
                                   (it "flips :expanded on the last tool message and leaves the rest alone"
                                       (fn []
                                         (let [msgs [{:role "tool-end" :id "a" :expanded false}
                                                     {:role "assistant" :id "b" :content "x"}
                                                     {:role "tool-end" :id "c" :expanded false}
                                                     {:role "user" :id "d" :content "y"}]
                                               out  (r/tool-toggle-expanded msgs)]
                                           (-> (expect (:expanded (nth out 2))) (.toBe true))
                                           (-> (expect (:expanded (nth out 0))) (.toBe false))
                                           (-> (expect (:expanded (nth (r/tool-toggle-expanded out) 2)))
                                               (.toBe false)))))

                                   (it "returns a new object for the toggled message (the pane's WeakMap keys on identity)"
                                       (fn []
                                         (let [m   {:role "tool-end" :id "a" :expanded false}
                                               out (r/tool-toggle-expanded [m])]
                                           (-> (expect (identical? m (first out))) (.toBe false)))))

                                   (it "is a no-op with no tool message"
                                       (fn []
                                         (let [msgs [{:role "user" :id "d" :content "y"}]]
                                           (-> (expect (count (r/tool-toggle-expanded msgs))) (.toBe 1)))))

                                   (it "ctrl+o on a running tool stays expanded once it finishes"
                                       (fn []
                                         (let [running (r/apply-tool-start [] {:toolName "bash" :execId "e" :args {:command "ls"}} "collapsed" 40)
                                               toggled (r/tool-toggle-expanded running)
                                               done    (r/apply-tool-end toggled {:toolName "bash" :execId "e" :result "x"} "collapsed" 40)]
                                           (-> (expect (:expanded (first toggled))) (.toBe true))
                                           (-> (expect (:role (first done))) (.toBe "tool-end"))
                                           (-> (expect (:expanded (first done))) (.toBe true)))))

                                   (it "an untouched running tool still takes the end payload's verbosity"
                                       (fn []
                                         (let [running (r/apply-tool-start [] {:toolName "bash" :execId "e" :args {:command "ls"}} "collapsed" 40)
                                               done    (r/apply-tool-end running {:toolName "bash" :execId "e" :result "x"
                                                                                  :customVerbosity "expanded"} "collapsed" 40)]
                                           (-> (expect (:expanded (first done))) (.toBe true)))))

                                   (it "the reducers stamp :expanded from the tool-display setting"
                                       (fn []
                                         (let [collapsed (r/apply-tool-end [] {:toolName "bash" :execId "e" :result "x"} "collapsed" 40)
                                               expanded  (r/apply-tool-end [] {:toolName "bash" :execId "e" :result "x"} "expanded" 40)]
                                           (-> (expect (:expanded (first collapsed))) (.toBe false))
                                           (-> (expect (:expanded (first expanded))) (.toBe true))
                                           (-> (expect (:max-lines (first expanded))) (.toBe 40)))))))

(describe "app.tools.expand" (fn []
                               (it "is bound to ctrl+o by default and rebindable from keybindings.json"
                                   (fn []
                                     (-> (expect (kbr/get-binding (kbr/create-registry) "app.tools.expand")) (.toBe "ctrl+o"))
                                     (-> (expect (kbr/get-binding (kbr/create-registry {"ctrl+t" "app.tools.expand"})
                                                                  "app.tools.expand"))
                                         (.toBe "ctrl+t"))))))
