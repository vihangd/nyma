(ns tui-pty-smoke-test
  "The interactive TUI in a real pseudo-terminal, end to end.

   Thirteen test files import pi-tui and none asserted on what reaches the
   terminal, so compositor and width regressions on the full-render path were
   invisible to the suite; TUI checks were ad-hoc `expect` scripts. Bun 1.4's
   `Bun.spawn({terminal})` gives the CLI a real TTY inside `bun test`.

   It also caught a startup bug no unit test could: every fresh session
   opened with \"Resumed <file> — 1 message\".

   One drive: a reply holding a wide table and CJK, rendered at 120 columns,
   then the terminal resized to 50. Asserted: the process survives, pi-tui's
   width check never fired (directly, or via crash recovery), and the table is
   box-drawn and re-laid-out for the new width — every frame row still closes
   with its border. A block cache that ignored width would hand the 120-col
   table to the hard wrap, which chops rows, so their right border goes."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [test-util.fake-model-server :as fms]))

(def ^:private cli (path/resolve (js/process.cwd) "dist" "agent" "cli.mjs"))

(defn- strip [s]
  (-> s
      (.replace (js/RegExp. "\u001b\\[[0-9;?]*[a-zA-Z]" "g") "")
      (.replace (js/RegExp. "\u001b\\][^\u0007\u001b]*(\u0007|\u001b\\\\)" "g") "")))

(defn- ^:async wait-for [pred ms]
  (let [end (+ (js/Date.now) ms)]
    (loop []
      (cond (pred) true
            (> (js/Date.now) end) false
            :else (do (js-await (js/Bun.sleep 50)) (recur))))))

(def ^:private reply
  (str "Here:\n\n| name | description | 名前 |\n|---|---|---|\n"
       "| alpha | " (.repeat "a very long description cell that must wrap " 3) "| 漢字テキスト |\n"
       "| beta | short | 日本語 |\n\nDone 漢字漢字漢字."))

(defn- frame-rows
  "Table body rows in `text`: lines that start, after the gutter, with `│`."
  [text]
  (->> (.split text #"\r?\n")
       (map str/trim)
       (filter #(str/starts-with? % "│"))))

(defn ^:async t-wide-table-survives-resize []
  (let [srv   (fms/start!)
        _     ((:script! srv) {:text reply})
        home  (fms/temp-home! (:base-url srv))
        dec   (js/TextDecoder.)
        buf   (atom "")
        proc  (js/Bun.spawn
               #js ["bun" cli "--model" "faketest/m1"]
               #js {:cwd home
                    :env (js/Object.assign #js {} js/process.env
                                           #js {"HOME" home "NYMA_NO_MODEL_DISCOVERY" "1"
                                                "TERM" "xterm-256color"})
                    :terminal #js {:cols 120 :rows 40
                                   :data (fn [_t d] (swap! buf str (.decode dec d #js {:stream true})))}})]
    (try
      (-> (expect (js-await (wait-for #(str/includes? (strip @buf) "faketest") 15000))) (.toBe true))
      ;; A fresh HOME is a fresh session. The banner once counted the project
      ;; card as a message and said "Resumed <file> — 1 message" here, which
      ;; also suppressed the first-launch hint.
      (-> (expect (str/includes? (strip @buf) "Resumed")) (.toBe false))
      (-> (expect (str/includes? (strip @buf) "Esc aborts")) (.toBe true))
      (.write (.-terminal proc) "hello")
      (js-await (js/Bun.sleep 300))
      ;; CSI-u Enter: a plain \r inserts a newline in the editor.
      (.write (.-terminal proc) "\u001b[13u")
      (-> (expect (js-await (wait-for #(str/includes? (strip @buf) "Done") 15000))) (.toBe true))
      ;; Box-drawn at 120: Bun.markdown.ansi, not the old width-blind render.
      (-> (expect (str/includes? (strip @buf) "┌")) (.toBe true))

      (let [mark (count @buf)]
        (.resize (.-terminal proc) 50 40)
        (js-await (wait-for #(str/includes? (strip (.slice @buf mark)) "└") 5000))
        (js-await (js/Bun.sleep 500))
        (let [after (strip (.slice @buf mark))
              rows  (frame-rows after)]
          (-> (expect (nil? (.-exitCode proc))) (.toBe true))
          (-> (expect (str/includes? @buf "exceeds terminal width")) (.toBe false))
          (-> (expect (str/includes? after "Recovered from a render")) (.toBe false))
          (-> (expect (pos? (count rows))) (.toBe true))
          (doseq [r rows]
            (-> (expect #js [r (str/ends-with? r "│")]) (.toEqual #js [r true])))))
      (finally
        (.kill proc)
        (js-await (.-exited proc))
        ((:stop srv))
        (try (fs/rmSync home #js {:recursive true :force true}) (catch :default _ nil))))))

((.skipIf describe (not (fs/existsSync cli))) "TUI in a pty"
                                              (fn []
                                                (it "a wide CJK table re-lays-out across a resize, no width crash"
                                                    t-wide-table-survives-resize 45000)))
