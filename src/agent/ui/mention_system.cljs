(ns agent.ui.mention-system
  "Mention registry and detection.
   Extensions register providers via api.registerMentionProvider().
   Detection scans editor text for trigger characters (e.g. @).")

(defn create-mention-registry
  "Create a mention provider registry. Returns {:register :unregister :get-providers :detect}."
  []
  (let [providers (atom {})]
    {:register
     (fn [id config]
       (swap! providers assoc (str id)
         {:id       (str id)
          :trigger  (or (:trigger config) (.-trigger config) "@")
          :label    (or (:label config) (.-label config) (str id))
          :search   (or (:search config) (.-search config))
          :resolve  (or (:resolve config) (.-resolve config))}))

     :unregister
     (fn [id]
       (swap! providers dissoc (str id)))

     :get-providers
     (fn []
       (vals @providers))

     :detect
     (fn [text]
       (when (and text (pos? (count text)))
         (let [last-at (.lastIndexOf text "@")]
           (when (>= last-at 0)
             ;; Check that @ is at start of text or preceded by whitespace
             (when (or (zero? last-at)
                       (= " " (.charAt text (dec last-at)))
                       (= "\n" (.charAt text (dec last-at))))
               (let [query (subs text (inc last-at))]
                 ;; Only trigger if query has no spaces (still typing the mention)
                 (when-not (.includes query " ")
                   {:trigger   "@"
                    :query     query
                    :start-idx last-at})))))))

     :_providers providers}))
