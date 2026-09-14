(ns js-interop.test
  "Squint has clj->js but not js->clj. A `(js->clj x)` compiles to a bare
   undefined global, so it threw — and every call site wrapped it in a catch
   that swallowed the error, turning a crash into a config block silently
   discarded. headroom could not be enabled by anyone for exactly this reason."
  (:require ["bun:test" :refer [describe it expect]]
            ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [agent.utils.js-interop :as ji]
            [agent.extensions.headroom.shared :as hs]))

(describe "js-interop/js->clj*" (fn []

  (it "round-trips the shapes the old call sites actually saw"
      (fn []
        (-> (expect (get (ji/js->clj* #js {"a" 1}) "a")) (.toBe 1))
        ;; Arrays and nil are why the `(if (map? m) m (js->clj m))` guard was
        ;; only safe by accident: squint's map? is false for both, so those
        ;; branches reached the undefined global.
        (-> (expect (vec (ji/js->clj* #js ["a" "b"]))) (.toEqual #js ["a" "b"]))
        (-> (expect (ji/js->clj* nil)) (.toBeNil))))

  (it "returns nil rather than throwing on a value JSON cannot carry"
      (fn []
        (let [cyclic #js {}]
          (aset cyclic "self" cyclic)
          (-> (expect (ji/js->clj* cyclic)) (.toBeNil)))))))

(describe "js-interop/kebab-keys" (fn []

  (it "makes a camelCase config readable by kebab-case lookups"
      (fn []
        ;; Config files are documented camelCase; defaults are kebab-case
        ;; keywords. Nothing bridged them, so `proxyUrl` landed beside an
        ;; untouched `proxy-url` default and was never read.
        (let [o (ji/kebab-keys #js {"proxyUrl" "u" "minTokensToCompress" 5})]
          (-> (expect (get o "proxy-url")) (.toBe "u"))
          (-> (expect (get o "min-tokens-to-compress")) (.toBe 5))
          ;; The original spelling survives too, so a kebab-case file works.
          (-> (expect (get o "proxyUrl")) (.toBe "u")))))

  (it "leaves an already-kebab key alone"
      (fn []
        (-> (expect (get (ji/kebab-keys #js {"proxy-url" "u"}) "proxy-url")) (.toBe "u"))))))

(describe "headroom/load-config" (fn []

  (it "reads a camelCase settings block instead of silently using defaults"
      (fn []
        (let [orig (aget js/process.env "HOME")
              dir  (fs/mkdtempSync (path/join (os/tmpdir) "nyma-headroom-"))]
          (try
            (fs/mkdirSync (path/join dir ".nyma") #js {:recursive true})
            (fs/writeFileSync
             (path/join dir ".nyma" "settings.json")
             (js/JSON.stringify
              #js {:headroom #js {:enabled true
                                  :proxyUrl "http://box:9000"
                                  :compressionThreshold 0.7
                                  :minTokensToCompress 1234
                                  :disableCcr false}})
             "utf8")
            (aset js/process.env "HOME" dir)
            (let [c (hs/load-config)]
              ;; Before: every one of these was the default.
              (-> (expect (:enabled c)) (.toBe true))
              (-> (expect (:proxy-url c)) (.toBe "http://box:9000"))
              (-> (expect (:compression-threshold c)) (.toBe 0.7))
              (-> (expect (:min-tokens-to-compress c)) (.toBe 1234))
              ;; The README tells users to set this to turn CCR markers on.
              (-> (expect (:disable-ccr c)) (.toBe false)))
            (finally
              (aset js/process.env "HOME" orig)
              (fs/rmSync dir #js {:recursive true :force true}))))))))

(defn ^:async test-attempt-async-swallows-rejection []
  (let [ok  (js-await (ji/attempt-async (fn [] (js/Promise.resolve 7))))
        bad (js-await (ji/attempt-async "t" (fn [] (js/Promise.reject (js/Error. "no")))))]
    (-> (expect ok) (.toBe 7))
    (-> (expect bad) (.toBeNil))))

(describe "js-interop/attempt" (fn []
  (it "returns the value, or nil on throw"
      (fn []
        (-> (expect (ji/attempt (fn [] 3))) (.toBe 3))
        (-> (expect (ji/attempt "t" (fn [] (throw (js/Error. "boom"))))) (.toBeNil))))
  (it "attempt-async resolves the value, or nil on rejection"
      test-attempt-async-swallows-rejection)))
