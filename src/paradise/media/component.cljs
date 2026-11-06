(ns paradise.media.component
  (:require ["react" :as react]
            [re-frame.core :as re-frame]
            [cljs.core.async :refer [go <!]]
            [cljs-workers.mesh :as mesh]
            [clojure.string :as str]))

(defn- extract-cache-key [uri source-map width height]
  (let [base-key (or (:uri source-map)
                     (get-in source-map [:file :url])
                     (when (and source-map (not (map? source-map)))
                       (or (.-uri source-map)
                           (some-> (.-file source-map) .-url)
                           (some-> (.-plain source-map) .-url)))
                     uri)]
    (if (and base-key width height)
      (str base-key "?w=" width "&h=" height)
      base-key)))


(defn- hide-spinner! [node]
  (when node
    (when-let [prev (.-previousElementSibling node)]
      (when (and (.-classList prev)
                 (.contains (.-classList prev) "message-image-placeholder")
                 (.-style prev))
        (set! (.-display (.-style prev)) "none")))))


(defn- notify-error! [node]
  (when node
    (when-let [err-fn (.-__paradiseOnError node)]
      (err-fn))))

(defonce !native-media-cache (js/Map.))

(defn- resolve-pending-entry! [cache-key status url fallback-url]
  (when-let [entry (.get !native-media-cache cache-key)]
    (let [nodes (.-nodes entry)]
      (set! (.-status entry) status)
      (set! (.-url entry) url)
      (when nodes
        (.forEach nodes
                  (fn [node]
                    (if url
                      (set! (.-src node) url)
                      (when (= status "error")
                        (notify-error! node)))
                    (when (= status "ready")
                      (hide-spinner! node))))
        (.clear nodes))
      (when (<= (.-refs entry) 0)
        (when (and url (str/starts-with? (str url) "blob:"))
          (js/URL.revokeObjectURL url))
        (.delete !native-media-cache cache-key))
      (when (and (= status "error") (not url))
        (.delete !native-media-cache cache-key)))))

(defn- schedule-cache-cleanup! [cache-key]
  (js/setTimeout
   (fn []
     (when-let [entry (.get !native-media-cache cache-key)]
       (when (and (<= (.-refs entry) 0)
                  (not= (.-status entry) "pending"))
         (when-let [url (.-url entry)]
           (when (str/starts-with? (str url) "blob:")
             (js/URL.revokeObjectURL url)))
         (.delete !native-media-cache cache-key))))
   100))



(defn execute-media-fetch! [cache-key uri source-map mime-type fallback-url room-id event-id width height method]
  (go
    (try
      (let [res (<! (mesh/do-with-thread! :media-pool
                                          {:handler   :get-media
                                           :arguments {:room-id    room-id
                                                       :event-id   event-id
                                                       :uri        uri
                                                       :source     uri
                                                       :source-map source-map
                                                       :width      width
                                                       :height     height
                                                       :method     method}}))]
        (case (:status res)
          ("unencrypted" "direct")
          (resolve-pending-entry! cache-key "ready" (:url res) fallback-url)

          "success"
          (if-let [entry (.get !native-media-cache cache-key)]
            (if (<= (.-refs entry) 0)
              (.delete !native-media-cache cache-key)
              (let [buffer (:bytes res)
                    blob   (js/Blob. #js [buffer] #js {:type mime-type})
                    url    (js/URL.createObjectURL blob)]
                (resolve-pending-entry! cache-key "ready" url fallback-url)))
            nil)

          "error"
          (do
            (js/console.error "Media load failed:" (:msg res))
            (resolve-pending-entry! cache-key "error" fallback-url fallback-url))

          (do
            (js/console.error "Unknown status from media worker:" res)
            (resolve-pending-entry! cache-key "error" fallback-url fallback-url))))
      (catch :default e
        (js/console.error "Media fetch go-block error:" e)
        (resolve-pending-entry! cache-key "error" fallback-url fallback-url)))))

(defn- retain-media! [node cache-key uri source-map mime-type fallback-url room-id event-id width height method]
  (if-let [entry (.get !native-media-cache cache-key)]
    (do
      (set! (.-refs entry) (+ (.-refs entry) 1))
      (case (.-status entry)
        "ready"
        (do
          (set! (.-src node) (.-url entry))
          (hide-spinner! node))

        "pending"
        (.add (.-nodes entry) node)

        "error"
        (if fallback-url
          (set! (.-src node) fallback-url)
          (notify-error! node))

        nil))
    (let [nodes (js/Set.)]
      (.add nodes node)
      (.set !native-media-cache
            cache-key
            #js {:url    nil
                 :status "pending"
                 :refs   1
                 :nodes  nodes})
      (execute-media-fetch! cache-key uri source-map mime-type fallback-url room-id event-id width height method))))

(defn- release-media! [node cache-key]
  (when-let [entry (.get !native-media-cache cache-key)]
    (when-let [nodes (.-nodes entry)]
      (.delete nodes node))
    (set! (.-refs entry) (- (.-refs entry) 1))
    (when (<= (.-refs entry) 0)
      (schedule-cache-cleanup! cache-key))))

(defn create-media-ref [props]
  (let [uri           (:uri props)
        source-map    (or (:source-map props) (:sourceMap props))
        mime-type     (or (:mime-type props) (:mimeType props) "image/png")
        fallback-url  (or (:fallback-url props) (:fallbackUrl props))
        on-error      (or (:on-error props) (:onError props))
        room-id       (or (:room-id props) (:roomId props))
        event-id      (or (:event-id props) (:eventId props))
        width         (:width props)
        height        (:height props)
        method        (:method props)
        cache-key     (extract-cache-key uri source-map width height)
        !mounted-node (atom nil)]
    (fn [node]
      (if node
        (do
          (set! (.-__paradiseOnError node) on-error)
          (when (and cache-key (not= @!mounted-node node))
            (when-let [prev @!mounted-node]
              (release-media! prev cache-key))
            (reset! !mounted-node node)
            (retain-media! node cache-key uri source-map mime-type fallback-url room-id event-id width height method)))
        (when-let [prev @!mounted-node]
          (set! (.-__paradiseOnError prev) nil)
          (reset! !mounted-node nil)
          (when cache-key
            (release-media! prev cache-key)))))))

(re-frame/reg-event-fx
 :media/download
 (fn [_ [_ {:keys [uri source-map filename mime-type room-id event-id]}]]
   (go
     (try
       (let [res (<! (mesh/do-with-thread! :media-pool
                                           {:handler   :get-media
                                            :arguments {:room-id    room-id
                                                        :event-id   event-id
                                                        :uri        uri
                                                        :source     uri
                                                        :source-map source-map}}))]
         (if (= (:status res) "success")
           (let [blob (js/Blob. #js [(:bytes res)] #js {:type (or mime-type "application/octet-stream")})
                 url  (js/URL.createObjectURL blob)
                 link (js/document.createElement "a")]
             (set! (.-href link) url)
             (set! (.-download link) (or filename "download"))
             (set! (.-display (.-style link)) "none")
             (js/document.body.appendChild link)
             (.click link)
             (js/document.body.removeChild link)
             (js/setTimeout #(js/URL.revokeObjectURL url) 10000))
           (js/console.error "File download failed:" (:msg res))))
       (catch :default e
         (js/console.error "File download error:" e))))
   {}))


(defn ^:react media-native [props]
  (let [tag-type (or (:tag-type props) (:tagType props) "img")
        class    (or (:class props) (:className props) (:class-name props))
        style    (:style props)
        alt      (:alt props)
        on-error (or (:on-error props) (:onError props))
        on-click (or (:on-click props) (:onClick props))
        controls (:controls props)]
    (react/createElement tag-type
                         #js {:className class
                              :style     (clj->js style)
                              :alt       alt
                              :controls  controls
                              :onClick   on-click
                              :loading   (when (= tag-type "img") "lazy")
                              :decoding  (when (= tag-type "img") "async")
                              :onError   on-error
                              :ref       (create-media-ref props)})))

#_(swap! paradise.shared.client.registry/!components assoc
       "comp:paradise.media.component/media-native" media-native)

#_(goog.object/set media-native "$native_comp" "comp:paradise.media.component/media-native")


(defn ^:ui media [props]
  [media-native props])