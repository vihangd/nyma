(ns web-fetch-ssrf-test
  (:require ["bun:test" :refer [describe it expect]]
            [agent.tools :as tools]))

(defn ^:async test-redirect []
  (let [srv (js/Bun.serve #js {:port 0 :fetch (fn [_] (js/Response. nil #js {:status 302 :headers #js {:location "http://10.0.0.1/admin"}}))})]
    (try
      (let [err (try (js-await (tools/direct-fetch (str "http://127.0.0.1:" (.-port srv) "/") "text" 100)) nil
                     (catch :default e (.-message e)))]
        (-> (expect (str err)) (.toMatch #"Refused: 10\.0\.0\.1")))
      (finally (.stop srv true)))))

(defn ^:async refused [url]
  (try (js-await (tools/direct-fetch url "text" 100)) nil
       (catch :default e (.-message e))))

(defn ^:async test-schemes []
  (-> (expect (str (js-await (refused "file:///etc/hosts")))) (.toMatch #"http\(s\) URLs only"))
  (-> (expect (str (js-await (refused "http://[::ffff:169.254.169.254]/latest")))) (.toMatch #"Refused"))
  (let [srv (js/Bun.serve #js {:port 0 :fetch (fn [_] (js/Response. nil #js {:status 302 :headers #js {:location "file:///etc/hosts"}}))})]
    (try
      (-> (expect (str (js-await (refused (str "http://127.0.0.1:" (.-port srv) "/"))))) (.toMatch #"http\(s\) URLs only"))
      (finally (.stop srv true)))))

(defn ^:async test-no-fallback []
  ;; a refusal is final: the URL never reaches jina or tinyfish
  (let [orig  js/globalThis.fetch
        calls (atom [])]
    (set! js/globalThis.fetch (fn [u _] (swap! calls conj (str u)) (js/Promise.resolve (js/Response. "x"))))
    (try
      (doseq [[url prov] [["http://169.254.169.254/latest/meta-data/iam" "auto"]
                          ["file:///Users/x/.ssh/id_rsa" "auto"]
                          ["http://10.0.0.1/admin" "jina"]]]
        (let [err (try (js-await (tools/web-fetch-execute {:url url :provider prov})) nil
                       (catch :default e (.-message e)))]
          (-> (expect (str err)) (.toMatch #"^Refused"))))
      (-> (expect (count @calls)) (.toBe 0))
      (finally (set! js/globalThis.fetch orig)))))

(defn ^:async test-same-origin []
  ;; a fetch allowed by site may not be bounced to another one, and no
  ;; fallback provider gets it instead
  (let [other (js/Bun.serve #js {:port 0 :fetch (fn [_] (js/Response. "other" #js {:headers #js {"content-type" "text/plain"}}))})
        srv   (js/Bun.serve #js {:port 0 :fetch (fn [r] (if (.endsWith (.-url r) "/in")
                                                          (js/Response. "here" #js {:headers #js {"content-type" "text/plain"}})
                                                          (js/Response. nil #js {:status 302 :headers #js {:location (if (.endsWith (.-url r) "/off")
                                                                                                                     (str "http://127.0.0.1:" (.-port other) "/x")
                                                                                                                     "/in")}})))})
        base  (str "http://127.0.0.1:" (.-port srv))
        orig  js/globalThis.fetch
        calls (atom 0)]
    (try
      (-> (expect (js-await (tools/direct-fetch (str base "/stay") "text" 100 true))) (.toBe "here"))
      (-> (expect (js-await (tools/direct-fetch (str base "/off") "text" 100))) (.toBe "other"))
      ;; off the allowed site: handed back to the model, so the target is a new,
      ;; gated call; auto mode does not pass it to a fallback provider
      (-> (expect (js-await (tools/web-fetch-execute {:url (str base "/off") :same-origin true :provider "auto"})))
          (.toMatch #"^Redirected, not followed: .* redirects to http://127\.0\.0\.1:\d+/x"))
      ;; another host is handed back on any fetch
      (let [loc (js/Bun.serve #js {:port 0 :fetch (fn [_] (js/Response. nil #js {:status 301 :headers #js {:location (str "http://localhost:" (.-port other) "/x")}}))})]
        (try
          (-> (expect (js-await (tools/direct-fetch (str "http://127.0.0.1:" (.-port loc) "/") "text" 100)))
              (.toMatch #"^Redirected, not followed"))
          (finally (.stop loc true))))
      (finally (.stop srv true) (.stop other true)))))

(describe "web_fetch address guard" (fn []
  (it "sends a refused URL to no fallback provider" test-no-fallback)
  (it "takes only http(s), on every hop, and sees IPv4 inside IPv6" test-schemes)
  (it "refuses private, link-local and carrier-NAT addresses; allows loopback and public"
      (fn []
        (doseq [[ip blocked] [["10.1.2.3" true] ["172.31.0.1" true] ["172.32.0.1" false] ["192.168.0.9" true]
                              ["169.254.169.254" true] ["100.64.0.1" true] ["0.0.0.0" true]
                              ["::ffff:192.168.1.1" true] ["fe80::1" true] ["fd12::1" true]
                              ["::ffff:a9fe:a9fe" true] ["64:ff9b::a9fe:a9fe" true] ["64:ff9b::10.0.0.1" true]
                              ["::ffff:808:808" false] ["64:ff9b::8.8.8.8" false]
                              ["127.0.0.1" false] ["::1" false] ["8.8.8.8" false] ["2606:4700::1" false]]]
          (-> (expect #js [ip (tools/blocked-address? ip)]) (.toEqual #js [ip blocked])))))
  (it "checks every redirect hop" test-redirect)
  (it "hands a redirect to another host, or off an allowed site, back to the model" test-same-origin)))
