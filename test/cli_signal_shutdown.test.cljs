(ns cli-signal-shutdown-test
  "SIGTERM and SIGHUP must take the graceful shutdown path.

   Only SIGINT was handled. So `kill <pid>`, `docker stop`, systemd and a
   closed terminal all took the DEFAULT action: the process died with none of
   the shutdown sequence run — MCP sockets never closed, LSP servers never got
   `shutdown`, ACP agents never got close→TERM→KILL, and background jobs (and
   anything they forked) were orphaned. Same class as the bench harness leak
   that left 87 processes alive for seven days.

   **The assertion is on `signalCode`, not the exit code, and that is the
   point.** Checked against the old code before writing this: a process killed
   by SIGTERM's default action ALSO reports exit code 143, with
   `signalCode: \"SIGTERM\"`. An exit-code assertion would have passed against
   the broken code and proved nothing. `signalCode` null means the process
   exited by itself — through `shutdown!` — which is the only thing that runs
   the cleanup.

   SIGHUP is handled only with a terminal on stdin. A handler replaces an
   inherited ignore, so an unconditional one broke `nohup nyma &` — the case
   below that keeps nohup working is as important as the one that handles it.

   (The gateway already handled SIGTERM. It deliberately does not get SIGHUP:
   for a daemon that conventionally means reload, and under a supervisor it
   never comes from a terminal.)"
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def ^:private cli-entry
  (path/join (js/process.cwd) "dist" "agent" "cli.mjs"))

(defn- built? [] (fs/existsSync cli-entry))

(defn- ^:async signal-run
  "Start a long-lived CLI (`--mode rpc` waits on stdin, no TTY needed), let it
   finish starting, send `sig`, and report how it ended."
  [sig]
  (let [home (fs/mkdtempSync (path/join (os/tmpdir) "nyma-sig-"))
        proc (js/Bun.spawn
              #js ["bun" cli-entry "--mode" "rpc" "--model" "nope/nope"]
              ;; A scratch HOME and cwd — not `(os/tmpdir)`, which is the real
              ;; temp dir and which an earlier test of mine leaked a nyma.db
              ;; into for exactly that reason.
              #js {:cwd home :stdin "pipe" :stdout "pipe" :stderr "pipe"
                   :env #js {"HOME" home
                             "PATH" (.-PATH js/process.env) "TMPDIR" (os/tmpdir)
                             "NYMA_NO_MODEL_DISCOVERY" "1"}})
        exited (atom false)
        _      (.then (.-exited proc) (fn [_] (reset! exited true)))
        ;; Wait for the handlers to be installed: they register after
        ;; extensions load, a few seconds in.
        _      (js-await (js/Bun.sleep 4000))
        early? @exited
        _      (when-not early? (.kill proc sig))
        code   (js-await (.-exited proc))]
    (try (fs/rmSync home #js {:recursive true :force true}) (catch :default _e nil))
    {:early? early? :code code :signal (.-signalCode proc)}))

(defn- ^:async pty-hup-run
  "The interactive TUI in a pty — stdin IS a terminal — sent SIGHUP."
  []
  (let [home (fs/mkdtempSync (path/join (os/tmpdir) "nyma-sig-"))
        proc (js/Bun.spawn
              #js ["bun" cli-entry "--model" "nope/nope"]
              #js {:cwd home
                   :env #js {"HOME" home "PATH" (.-PATH js/process.env) "TMPDIR" (os/tmpdir)
                             "NYMA_NO_MODEL_DISCOVERY" "1" "TERM" "xterm-256color"}
                   :terminal #js {:cols 100 :rows 30 :data (fn [_ _])}})
        exited (atom false)
        _      (.then (.-exited proc) (fn [_] (reset! exited true)))
        _      (js-await (js/Bun.sleep 4000))
        early? @exited
        _      (when-not early? (.kill proc "SIGHUP"))
        code   (js-await (.-exited proc))]
    (try (fs/rmSync home #js {:recursive true :force true}) (catch :default _e nil))
    {:early? early? :code code :signal (.-signalCode proc)}))

(defn- ^:async nohup-hup-survives?
  "`nohup` + a pipe on stdin: SIGHUP must stay ignored."
  []
  (let [home (fs/mkdtempSync (path/join (os/tmpdir) "nyma-sig-"))
        proc (js/Bun.spawn
              #js ["nohup" "bun" cli-entry "--mode" "rpc" "--model" "nope/nope"]
              #js {:cwd home :stdin "pipe" :stdout "pipe" :stderr "pipe"
                   :env #js {"HOME" home "PATH" (.-PATH js/process.env) "TMPDIR" (os/tmpdir)
                             "NYMA_NO_MODEL_DISCOVERY" "1"}})
        _      (js-await (js/Bun.sleep 4000))
        _      (.kill proc "SIGHUP")
        _      (js-await (js/Bun.sleep 1500))
        alive? (nil? (.-exitCode proc))]
    (.kill proc "SIGTERM")
    (js-await (.-exited proc))
    (try (fs/rmSync home #js {:recursive true :force true}) (catch :default _e nil))
    alive?))

((.skipIf describe (not (built?))) "signals take the graceful shutdown path"
                                   (fn []
                                     (it "SIGTERM: exits on its own, with 143"
                                         (^:async fn []
                                           (let [{:keys [early? code signal]} (js-await (signal-run "SIGTERM"))]
         ;; Guard: if the CLI died before the signal, nothing below means
         ;; anything.
                                             (-> (expect early?) (.toBe false))
         ;; THE assertion. The default action would leave "SIGTERM" here.
                                             (-> (expect signal) (.toBeNil))
                                             (-> (expect code) (.toBe 143))))
                                         30000)

                                     (it "SIGHUP with a terminal (it closed): exits on its own, with 129"
                                         (^:async fn []
                                           (let [{:keys [early? code signal]} (js-await (pty-hup-run))]
                                             (-> (expect early?) (.toBe false))
                                             (-> (expect signal) (.toBeNil))
                                             (-> (expect code) (.toBe 129))))
                                         30000)

                                     (it "SIGHUP under nohup, no terminal: ignored, still running"
                                         (^:async fn []
                                           (-> (expect (js-await (nohup-hup-survives?))) (.toBe true)))
                                         30000)))
