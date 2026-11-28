(ns service-worker
  (:require [clojure.string :as str]
            [paradise.shared.utils.encoding :refer [array-buffer->data-url]]
            [promesa.core :as p]
            ["workbox-precaching" :refer [precacheAndRoute cleanupOutdatedCaches]])
  (:require-macros [paradise.shared.utils.macros :refer [load-static-config]]))

(defonce active-sessions (atom {}))
(defonce active-client-sessions (atom {}))
(defonce active-user-id (atom nil))
(defonce session-resolvers (atom []))
(def config (load-static-config "config.edn"))
(def legacy-cache-name "paradise-media-v1")
(def sw-session-file "paradise_sw_sessions.json")

(declare persist-sw-sessions! remove-sw-sessions-file! purge-media-caches!)

(cleanupOutdatedCaches)
(let [manifest (or js/self.__WB_MANIFEST #js [])]
  (when-not (= (.. js/self -location -protocol) "capacitor-electron:")
    (precacheAndRoute manifest)))
(set! (.-__WB_DISABLE_DEV_LOGS js/self) true)

(.addEventListener js/self "message"
  (fn [event]
    (let [data      (.-data event)
          msg-type  (when data (.-type data))
          source-id (some-> event .-source .-id)]
      (cond
        (= msg-type "SKIP_WAITING")
        (.skipWaiting js/self)

        (= msg-type "CLEAR_SESSION")
        (let [payload    (when data (js->clj data :keywordize-keys true))
              target-uid (some-> (:user-id payload) str not-empty)
              all?       (or (:all? payload) (nil? target-uid))]
          (if all?
            (do
              (reset! active-sessions {})
              (reset! active-client-sessions {})
              (reset! active-user-id nil)
              (remove-sw-sessions-file!)
              (purge-media-caches! nil))
            (let [next-map (swap! active-sessions dissoc target-uid (keyword target-uid))]
              (swap! active-client-sessions
                     (fn [m]
                       (into {} (remove (fn [[_ v]] (= (str (:user-id v)) target-uid)) m))))
              (when (= (str @active-user-id) target-uid)
                (reset! active-user-id nil))
              (if (seq next-map)
                (persist-sw-sessions! next-map)
                (remove-sw-sessions-file!))
              (purge-media-caches! target-uid))))

        (= msg-type "CLEAR_ALL_SESSIONS")
        (do
          (reset! active-sessions {})
          (reset! active-client-sessions {})
          (reset! active-user-id nil)
          (remove-sw-sessions-file!)
          (purge-media-caches! nil))

        (= msg-type "SET_SESSION")
        (let [raw-session  (:session (js->clj data :keywordize-keys true))
              session-data (select-keys raw-session
                                        [:user-id :base-url :origin :token
                                         :auth-media? :media-paths :thumbnail-template])
              uid          (some-> (:user-id session-data) str not-empty)
              session-key  (or uid source-id "default")
              full-session (assoc session-data :client-id source-id)
              next-map     (swap! active-sessions assoc session-key full-session)]
          (when source-id
            (swap! active-client-sessions assoc source-id full-session))
          (reset! active-user-id uid)
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

(defn- remove-sw-sessions-file! []
  (p/catch
    (p/let [dir (.. js/navigator -storage getDirectory)]
      (.removeEntry dir sw-session-file))
    (fn [_] nil)))

(defn- purge-media-caches! [target-uid]
  (p/catch
    (p/let [keys (.keys js/caches)]
      (let [prefix (if target-uid
                     (str legacy-cache-name "::" (js/encodeURIComponent (str target-uid)))
                     legacy-cache-name)]
        (p/all
         (for [k keys
               :when (if target-uid
                       (or (= k prefix) (= k legacy-cache-name))
                       (str/starts-with? k legacy-cache-name))]
           (.delete js/caches k)))))
    (fn [_] nil)))

(defn- load-sw-sessions []
  (p/catch
    (p/let [dir    (.. js/navigator -storage getDirectory)
            handle (.getFileHandle dir sw-session-file)
            file   (.getFile handle)
            text   (.text file)]
      (let [parsed (js/JSON.parse text)]
        (reduce (fn [acc k]
                  (assoc acc k (js->clj (aget parsed k) :keywordize-keys true)))
                {}
                (js/Object.keys parsed))))
    (fn [_] {})))


(defn- find-session-by-uid [sessions-map uid]
  (when (and (seq sessions-map) uid)
    (let [uid-str (str uid)]
      (or (get sessions-map uid-str)
          (get sessions-map (keyword uid-str))
          (first (filter #(= (str (:user-id %)) uid-str) (vals sessions-map)))))))

(defn- resolve-session [client-id user-id]
  (let [mem        @active-sessions
        client-hit (when client-id (get @active-client-sessions client-id))]
    (cond
      user-id
      (if-let [hit (find-session-by-uid mem user-id)]
        (p/resolved hit)
        (p/let [stored (load-sw-sessions)]
          (when (seq stored)
            (swap! active-sessions merge stored)
            (find-session-by-uid stored user-id))))

      client-hit
      (p/resolved client-hit)

      (and client-id (seq mem))
      (p/resolved
       (or (first (filter #(= (:client-id %) client-id) (vals mem)))
           (when-let [cur @active-user-id]
             (find-session-by-uid mem cur))))

      @active-user-id
      (p/resolved (find-session-by-uid mem @active-user-id))

      :else
      (p/resolved nil))))

(def default-auth-media-paths
  ["/_matrix/client/v1/media/"
   "/_matrix/media/"])

(defn- matches-auth-media-path? [path session]
  (when (and (string? path)
             (not (false? (:auth-media? session)))
             (not (str/starts-with? path "/_matrix/media/v3/preview_url"))
             (not (str/starts-with? path "/_matrix/client/v1/media/preview_url")))
    (let [patterns (if (contains? session :media-paths)
                     (:media-paths session)
                     default-auth-media-paths)]
      (boolean (and (seq patterns)
                    (some (fn [pat]
                            (and (string? pat)
                                 (str/starts-with? pat "/")
                                 (str/starts-with? path pat)))
                          patterns))))))

(defn- likely-authenticated-path? [path]
  (and (string? path)
       (not (str/starts-with? path "/_matrix/media/v3/preview_url"))
       (not (str/starts-with? path "/_matrix/client/v1/media/preview_url"))
       (boolean (some #(str/starts-with? path %) default-auth-media-paths))))

(js/self.addEventListener "fetch"
  (fn [event]
    (let [request     (.-request event)
          method      (.-method request)
          request-url (js/URL. (.-url request))
          protocol    (.-protocol request-url)
          path        (.-pathname request-url)]
      (when (and (= method "GET")
                 (#{"http:" "https:"} protocol)
                 (likely-authenticated-path? path)
                 (not (.has (.-headers request) "Authorization")))
        (.respondWith event
          (p/let [session    (resolve-session (.-clientId event) nil)
                  token      (:token session)
                  origin     (:origin session)
                  need-auth? (and token
                                  origin
                                  (= (.-origin request-url) origin)
                                  (matches-auth-media-path? path session))]
            (if need-auth?
              (let [headers (js/Headers. (.-headers request))]
                (.set headers "Authorization" (str "Bearer " token))
                (js/fetch request-url #js {:headers        headers
                                           :mode           "cors"
                                           :credentials    "omit"
                                           :redirect       "error"
                                           :referrerPolicy "no-referrer"}))
              (js/fetch request))))))))

(js/self.addEventListener "install"
  (fn [event]
    (.skipWaiting js/self)))

(js/self.addEventListener "activate"
  (fn [event]
    (.waitUntil event
      (p/all [(.clients.claim js/self)
              (-> (.delete js/caches "wasm-heavy-cache")
                  (p/catch (fn [_] false)))
              (purge-media-caches! nil)]))))

(def default-icon "/public/res/apple/apple-touch-icon-180x180.png")
(def default-badge "/public/res/apple/apple-touch-icon-72x72.png")

(defn- build-avatar-url [session raw-uri]
  (let [s (str raw-uri)]
    (cond
      (str/blank? s)
      nil

      (or (str/starts-with? s "http://")
          (str/starts-with? s "https://")
          (str/starts-with? s "data:"))
      s

      (and (:base-url session) (:thumbnail-template session))
      (let [clean-id (str/replace s #"^[a-zA-Z0-9+.-]+://" "")]
        (when (and (not (str/blank? clean-id))
                   (not (str/includes? clean-id ".."))
                   (not (str/includes? clean-id "\\"))
                   (not (str/includes? clean-id "?"))
                   (not (str/includes? clean-id "#"))
                   (not (re-find #"\s" clean-id)))
          (-> (:thumbnail-template session)
              (str/replace "{base-url}" (str/replace (:base-url session) #"/+$" ""))
              (str/replace "{id}" clean-id))))

      :else nil)))

(defn- fetch-avatar-data-url [session avatar-uri]
  (if-let [thumb-url (build-avatar-url session avatar-uri)]
    (if (str/starts-with? thumb-url "data:")
      (p/resolved thumb-url)
      (let [parsed     (try (js/URL. thumb-url) (catch :default _ nil))
            valid-http (and parsed (#{"http:" "https:"} (.-protocol parsed)))]
        (if-not valid-http
          (p/resolved nil)
          (let [token      (:token session)
                origin     (:origin session)
                need-auth? (and token
                                origin
                                (= (.-origin parsed) origin)
                                (matches-auth-media-path? (.-pathname parsed) session))
                fetch-opts (if need-auth?
                             #js {:headers        #js {:Authorization (str "Bearer " token)}
                                  :mode           "cors"
                                  :credentials    "omit"
                                  :redirect       "error"
                                  :referrerPolicy "no-referrer"}
                             #js {:mode           "cors"
                                  :credentials    "omit"
                                  :referrerPolicy "no-referrer"})]
            (-> (js/fetch (.-href parsed) fetch-opts)
                (p/then (fn [resp]
                          (when (.-ok resp)
                            (p/let [mime (.get (.-headers resp) "content-type")
                                    buf  (.arrayBuffer resp)]
                              (array-buffer->data-url buf mime)))))
                (p/catch (fn [_] nil)))))))
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

(defn- notify-open-clients-push! [notif raw-map]
  (-> (.matchAll js/self.clients #js {:type "window" :includeUncontrolled true})
      (.then (fn [clients]
               (let [room-id  (or (:room-id notif) (:room_id notif) (:roomId notif)
                                  (:room_id raw-map) (:room-id raw-map) (:channel_id raw-map))
                     event-id (or (:event-id notif) (:event_id notif) (:eventId notif)
                                  (:event_id raw-map) (:event-id raw-map))]
                 (doseq [c clients]
                   (try
                     (.postMessage c #js {:type     "PUSH_NOTIFICATION_RECEIVED"
                                          :room_id  room-id
                                          :event_id event-id})
                     (catch :default _ nil))))))
      (.catch (fn [_] nil))))

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
          (notify-open-clients-push! notif raw-map)
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