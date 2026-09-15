(ns ext-custom-provider-qwen-cli.test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.extensions.custom-provider-qwen-cli.index :as qwen]))

;; The verification URL comes from Qwen's server. It used to be spliced into
;; a shell string, so a URL carrying `;` or `$(…)` would have run as a
;; command. The opener must receive it as an argv element and never a shell.

(def ^:private hostile-url "https://chat.qwen.ai/verify?code=abc;rm -rf /;$(touch pwned)&x=`id`")

(describe "qwen-cli browser open"
          (fn []
            (it "builds an argv per platform with the url as a single argument"
                (fn []
                  (-> (expect (qwen/browser-open-argv "darwin" hostile-url))
                      (.toEqual ["open" hostile-url]))
                  (-> (expect (qwen/browser-open-argv "linux" hostile-url))
                      (.toEqual ["xdg-open" hostile-url]))
                  (-> (expect (qwen/browser-open-argv "win32" hostile-url))
                      (.toEqual ["rundll32" "url.dll,FileProtocolHandler" hostile-url]))))

            (it "spawns the opener with the raw url in argv, no shell"
                (fn []
                  (let [calls (atom [])
                        spy   (fn [cmd args opts]
                                (swap! calls conj {:cmd cmd :args (vec args) :opts opts})
                                #js {:unref (fn [] nil)})]
                    (qwen/open-browser! hostile-url spy)
                    (-> (expect (count @calls)) (.toBe 1))
                    (let [{:keys [cmd args opts]} (first @calls)]
                      ;; The command is a bare program name, not "sh"/"cmd".
                      (-> (expect (contains? #{"open" "xdg-open" "rundll32"} cmd)) (.toBe true))
                      ;; The url is one argument, byte-for-byte.
                      (-> (expect (last args)) (.toBe hostile-url))
                      ;; And no shell option sneaks it back through a parser.
                      (-> (expect (boolean (.-shell opts))) (.toBe false))))))

            (it "swallows a spawn failure so login still shows the url"
                (fn []
                  (let [boom (fn [& _] (throw (js/Error. "ENOENT")))]
                    (-> (expect (fn [] (qwen/open-browser! hostile-url boom)))
                        .-not
                        (.toThrow)))))))
