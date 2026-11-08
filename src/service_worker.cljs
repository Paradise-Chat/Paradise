(ns service-worker
  (:require [clojure.string :as str]
            [promesa.core :as p]
            ["workbox-precaching" :refer [precacheAndRoute cleanupOutdatedCaches]]
            ["workbox-routing" :refer [registerRoute]]
            ["workbox-strategies" :refer [CacheFirst]]
            ["workbox-expiration" :refer [ExpirationPlugin]])
  (:require-macros [paradise.shared.utils.macros :refer [load-static-config]]))

(defonce active-sessions (atom {}))
(defonce session-resolvers (atom []))
(def MAX_CACHE_ITEMS 300)
(def MAX_CACHE_BYTES 10485760)
(def config (load-static-config "config.edn"))
(def cache-name "paradise-media-v1")
(def sw-session-file "paradise_sw_sessions.json")

(cleanupOutdatedCaches)
(let [manifest (or js/self.__WB_MANIFEST #js [])]
  (precacheAndRoute manifest))
(set! (.-__WB_DISABLE_DEV_LOGS js/self) true)

(registerRoute
 (fn [opts]
   (let [url (.-url opts)]
     (str/ends-with? (.-pathname url) ".wasm")))
 (new CacheFirst
      #js {:cacheName "wasm-heavy-cache"
           :plugins   #js [(new ExpirationPlugin #js {:maxEntries 10})]}))

(.addEventListener js/self "message"
  (fn [event]
    (let [data      (.-data event)
          msg-type  (when data (.-type data))
          source-id (some-> event .-source .-id)]
      (cond
        (= msg-type "SKIP_WAITING")
        (.skipWaiting js/self)

        (= msg-type "SET_SESSION")
        (let [session-data (:session (js->clj data :keywordize-keys true))
              session-key  (or (:user-id session-data) source-id :default)
              full-session (assoc session-data :client-id source-id)
              next-map     (swap! active-sessions assoc session-key full-session)]
          (persist-sw-sessions! next-map)
          (doseq [resolve-fn @session-resolvers]
            (resolve-fn true))
          (reset! session-resolvers []))))))

(defn- persist-sw-sessions! [sessions-map]
  (p/catch
    (p/let [dir      (.. js/navigator -storage getDirectory)
            handle   (.getFileHandle dir sw-session-file #js {:create true})
            writable (.createWritable handle)
            _        (.write writable (js/JSON.stringify (clj->js sessions-map)))
            _        (.close writable)]
      true)
    (fn [_] nil)))

(defn- load-sw-sessions []
  (p/catch
    (p/let [dir    (.. js/navigator -storage getDirectory)
            handle (.getFileHandle dir sw-session-file)
            file   (.getFile handle)
            text   (.text file)]
      (js->clj (js/JSON.parse text) :keywordize-keys true))
    (fn [_] {})))


(defn- resolve-session [client-id user-id]
  (let [mem @active-sessions]
    (if (seq mem)
      (p/resolved
       (or (when client-id (get mem client-id))
           (when user-id (first (filter #(= (:user-id %) user-id) (vals mem))))
           (first (vals mem))))
      (p/let [stored (load-sw-sessions)]
        (when (seq stored)
          (reset! active-sessions stored)
          (or (when user-id (get stored (keyword user-id)))
              (when user-id (get stored user-id))
              (first (vals stored))))))))

(defn prune-cache! [name max-items]
  (p/let [cache (js/caches.open name)
          keys  (.keys cache)]
    (when (> (.-length keys) max-items)
      (p/let [_ (.delete cache (first keys))]
        (prune-cache! name max-items)))))

(defn- cacheable-media-response? [resp]
  (when (and resp (.-ok resp))
    (let [ct  (or (.get (.-headers resp) "content-type") "")
          len (js/parseInt (or (.get (.-headers resp) "content-length") "0") 10)]
      (and (or (str/starts-with? ct "image/")
               (str/starts-with? ct "video/")
               (str/starts-with? ct "audio/")
               (str/starts-with? ct "application/octet-stream"))
           (or (js/isNaN len) (zero? len) (< len MAX_CACHE_BYTES))))))

(defn- matches-media-path? [path session]
  (let [patterns (or (seq (:media-paths session)) ["/media/"])]
    (boolean (some #(str/includes? path %) patterns))))

(js/self.addEventListener "fetch"
  (fn [event]
    (let [request     (.-request event)
          method      (.-method request)
          request-url (js/URL. (.-url request))
          path        (.-pathname request-url)
          dest        (.-destination request)]
      (when (and (= method "GET")
                 (or (#{"image" "video" "audio"} dest)
                     (str/includes? path "/media/")))
        (.respondWith event
          (p/let [cache-key (.-href request-url)
                  cache     (js/caches.open cache-name)
                  cached    (.match cache cache-key)]
            (if cached
              cached
              (if (.has (.-headers request) "Authorization")
                (p/let [resp (js/fetch request)]
                  (when (cacheable-media-response? resp)
                    (.put cache cache-key (.clone resp)))
                  resp)
                (p/let [session (resolve-session (.-clientId event) nil)
                        token   (:token session)
                        origin  (:origin session)
                        headers (js/Headers. (.-headers request))]
                  (if (or (#{"image" "video" "audio"} dest)
                          (matches-media-path? path session))
                    (do
                      (when (and token (= (.-origin request-url) origin))
                        (.set headers "Authorization" (str "Bearer " token)))
                      (p/let [resp (js/fetch request-url #js {:headers     headers
                                                              :mode        "cors"
                                                              :credentials "omit"})]
                        (when (cacheable-media-response? resp)
                          (.put cache cache-key (.clone resp)))
                        resp))
                    (js/fetch request)))))))))))

(js/self.addEventListener "install"
  (fn [event]
    (.skipWaiting js/self)))

(js/self.addEventListener "activate"
  (fn [event]
    (.waitUntil event
      (p/all [(.clients.claim js/self)
              (prune-cache! cache-name MAX_CACHE_ITEMS)]))))

(def default-icon "/public/res/apple/apple-touch-icon-180x180.png")
(def default-badge "/public/res/apple/apple-touch-icon-72x72.png")
(def default-icon "/public/res/apple/apple-touch-icon-180x180.png")
(def default-badge "/public/res/apple/apple-touch-icon-72x72.png")

(defn- array-buffer->data-url [buf mime-type]
  (let [bytes  (js/Uint8Array. buf)
        len    (.-byteLength bytes)
        chunks #js []]
    (dotimes [i len]
      (.push chunks (js/String.fromCharCode (aget bytes i))))
    (str "data:" (or mime-type "image/png") ";base64," (js/btoa (.join chunks "")))))

(defn- build-avatar-url [session raw-uri]
  (cond
    (str/blank? (str raw-uri))
    nil

    (or (str/starts-with? (str raw-uri) "http://")
        (str/starts-with? (str raw-uri) "https://")
        (str/starts-with? (str raw-uri) "data:"))
    (str raw-uri)

    (and (:base-url session) (:thumbnail-template session))
    (let [clean-id (-> (str raw-uri)
                       (str/replace #"^[a-zA-Z0-9+.-]+://" ""))]
      (-> (:thumbnail-template session)
          (str/replace "{base-url}" (str/replace (:base-url session) #"/+$" ""))
          (str/replace "{id}" clean-id)))

    :else nil))

(defn- fetch-avatar-data-url [session avatar-uri]
  (if-let [thumb-url (build-avatar-url session avatar-uri)]
    (if (str/starts-with? thumb-url "data:")
      (p/resolved thumb-url)
      (let [token   (:token session)
            headers (if token
                      #js {:Authorization (str "Bearer " token)}
                      #js {})]
        (-> (js/fetch thumb-url #js {:headers     headers
                                     :mode        "cors"
                                     :credentials "omit"})
            (p/then (fn [resp]
                      (when (.-ok resp)
                        (p/let [mime (.get (.-headers resp) "content-type")
                                buf  (.arrayBuffer resp)]
                          (array-buffer->data-url buf mime)))))
            (p/catch (fn [_] nil)))))
    (p/resolved nil)))

(defn- normalize-push-payload [raw-map]
  (let [room-id  (or (:room_id raw-map) (:room-id raw-map) (:roomId raw-map) (:channel_id raw-map))
        event-id (or (:event_id raw-map) (:event-id raw-map) (:eventId raw-map))
        user-id  (or (:account_id raw-map) (:user_id raw-map) (:user-id raw-map) (:userId raw-map))]
    (cond-> raw-map
      room-id  (assoc :room_id room-id :room-id room-id :roomId room-id)
      event-id (assoc :event_id event-id :event-id event-id :eventId event-id)
      user-id  (assoc :account_id user-id :user_id user-id :user-id user-id :userId user-id))))

(defn- request-client-decryption [raw-map timeout-ms]
  (p/let [clients (.matchAll js/self.clients #js {:type "window" :includeUncontrolled true})
          client  (first clients)]
    (if-not client
      nil
      (js/Promise.
       (fn [resolve-fn _]
         (let [chan      (js/MessageChannel.)
               port1     (.-port1 chan)
               port2     (.-port2 chan)
               norm-data (clj->js (normalize-push-payload raw-map))
               timer     (js/setTimeout #(do (.close port1) (resolve-fn nil)) timeout-ms)]
           (set! (.-onmessage port1)
                 (fn [ev]
                   (js/clearTimeout timer)
                   (.close port1)
                   (resolve-fn (.-data ev))))
           (.postMessage client
                         #js {:type    "RESOLVE_PUSH"
                              :payload norm-data}
                         #js [port2])))))))

(defn- parse-push-event [event]
  (when-let [event-data (.-data event)]
    (try
      (let [parsed (.json event-data)]
        (or (.-notification parsed) parsed))
      (catch :default _
        (let [raw-text (try (.text event-data) (catch :default _ "New Message"))]
          #js {:title "Paradise"
               :body  (if (str/blank? raw-text) "New Message" raw-text)})))))

(defn- fallback-notification [raw]
  (let [sender     (or (:sender_display_name raw) (:sender raw) (:title raw) "Paradise")
        room-name  (or (:room_name raw) (:channel_name raw))
        encrypted? (boolean (or (:encrypted raw)
                                (some-> (:type raw) (str/includes? "encrypted"))))]
    {:title      (if room-name (str sender " in " room-name) sender)
     :body       (if encrypted?
                   "Encrypted Message"
                   (or (:content_body raw)
                       (get-in raw [:content :body])
                       (:body raw)
                       "New Message"))
     :avatar-uri (or (:avatar_uri raw)
                     (:avatar_url raw)
                     (get-in raw [:content :avatar_url])
                     (:sender_avatar_url raw)
                     (:icon raw))
     :room-id    (or (:room_id raw) (:room-id raw) (:channel_id raw))
     :unread     (or (:unread raw) (get-in raw [:counts :unread]) (get-in raw [:aps :badge]))}))

(defn- show-notification! [notif session]
  (let [title      (:title notif)
        body       (:body notif)
        avatar-uri (or (:avatar-uri notif) (:avatar_uri notif) (:avatar_url notif) (:icon notif))
        room-id    (or (:room-id notif) (:room_id notif) (:roomId notif))
        raw-unread (:unread notif)
        unread     (cond
                     (number? raw-unread) raw-unread
                     (string? raw-unread) (let [n (js/parseInt raw-unread 10)]
                                            (when-not (js/isNaN n) n))
                     :else nil)]
    (when (exists? js/navigator.setAppBadge)
      (try
        (if (and unread (pos? unread))
          (.setAppBadge js/navigator unread)
          (when (= unread 0)
            (.clearAppBadge js/navigator)))
        (catch :default _ nil)))
    (p/let [icon-url (fetch-avatar-data-url session avatar-uri)]
      (.showNotification js/self.registration (or title "Paradise")
        #js {:body  (or body "New Message")
             :icon  (or icon-url default-icon)
             :badge default-badge
             :tag   (or room-id "paradise-chat")
             :data  #js {:room_id room-id}}))))

(js/self.addEventListener "push"
  (fn [event]
    (let [raw-js  (parse-push-event event)
          raw-map (if raw-js (js->clj raw-js :keywordize-keys true) {})
          user-id (or (:account_id raw-map)
                      (:user_id raw-map)
                      (:user-id raw-map)
                      (:userId raw-map))]
      (.waitUntil event
        (p/let [session  (resolve-session nil user-id)
                resolved (when (seq raw-map) (request-client-decryption raw-map 1500))
                notif    (if resolved
                           (js->clj resolved :keywordize-keys true)
                           (fallback-notification raw-map))]
          (show-notification! notif session))))))

(js/self.addEventListener "notificationclick"
  (fn [event]
    (let [notification (.-notification event)
          room-id      (.. notification -data -room_id)]
      (.close notification)
      (.waitUntil event
        (p/let [clients (.matchAll js/self.clients #js {:type "window" :includeUncontrolled true})
                tab     (first clients)]
          (if tab
            (do
              (.focus tab)
              (.postMessage tab #js {:type    "NAVIGATE_TO_ROOM"
                                     :room_id room-id}))
            (.openWindow js/self.clients "/")))))))