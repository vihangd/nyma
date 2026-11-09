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

(describe "web_fetch address guard" (fn []
  (it "refuses private, link-local and carrier-NAT addresses; allows loopback and public"
      (fn []
        (doseq [[ip blocked] [["10.1.2.3" true] ["172.31.0.1" true] ["172.32.0.1" false] ["192.168.0.9" true]
                              ["169.254.169.254" true] ["100.64.0.1" true] ["0.0.0.0" true]
                              ["::ffff:192.168.1.1" true] ["fe80::1" true] ["fd12::1" true]
                              ["127.0.0.1" false] ["::1" false] ["8.8.8.8" false] ["2606:4700::1" false]]]
          (-> (expect #js [ip (tools/blocked-address? ip)]) (.toEqual #js [ip blocked])))))
  (it "checks every redirect hop" test-redirect)))
