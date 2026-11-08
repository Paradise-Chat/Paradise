(ns paradise.ui.overlays.notifications
  (:require [re-frame.core :as re-frame]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [promesa.core :as p]
            [cljs.core.async :refer [go <!]]
            [cljs.core.async.interop :refer-macros [<p!]]
            ["@capacitor/core" :refer [Capacitor registerPlugin]]
            ["@capacitor/push-notifications" :refer [PushNotifications]]
            [paradise.shared.client.state :refer [!config] :as state]
            [paradise.shared.client.session-store :as store]
            [cljs-workers.mesh :as mesh]))

(def shadow-device (registerPlugin "ShadowDevice"))

(defn- resolve-app-id []
  (let [cfg @!config]
    (case (.getPlatform Capacitor)
      "ios"     (:app-id-ios cfg)
      "android" (:app-id-android cfg)
      (or (:app-id-web cfg) (:app-id cfg)))))



(defn- strip-source-map [s]
  (when s
    (let [marker (str "/" "/# sourceMappingURL=")]
      (if-let [idx (str/last-index-of s marker)]
        (subs s 0 idx)
        s))))

(defn- fetch-notification-script [url]
  (-> (js/fetch url)
      (p/then (fn [resp]
                (when (.-ok resp)
                  (.text resp))))
      (p/then strip-source-map)
      (p/catch (fn [_] nil))))

(defn- active-notification-script []
  (go
    (let [res (<! (mesh/do-with-thread! :engine-pool
                                        {:handler   :get-asset-urls
                                         :arguments {}}))]
      (when (#{"success" :success} (:status res))
        (:push-url res)))))

(re-frame/reg-event-fx
 :push/sync-engine-script
 (fn [_ _]
   (when (contains? #{"ios" "android"} (.getPlatform Capacitor))
     (go
       (try
         (if-let [script-path (<! (active-notification-script))]
           (let [script-name (last (str/split script-path #"/"))
                 source      (<p! (fetch-notification-script script-path))]
             (log/info "Fetched push script:" script-name "bytes:" (count source))
             (when (and source (not (str/blank? source)))
               (.syncPushScript shadow-device
                                #js {:scriptName   script-name
                                     :scriptSource source})))
           (log/error "No push-url returned from engine"))
         (catch :default e
           (log/error "Failed to sync notification script:" e)))))
   {}))

(re-frame/reg-sub
 :push/active-user
 (fn [db _] (:active-push-user db)))

(re-frame/reg-event-db
 :push/set-active-user
 (fn [db [_ user-id]]
   (assoc db :active-push-user user-id
             :push-status (if user-id :enabled :disabled))))

(re-frame/reg-sub
 :push/previews-enabled?
 (fn [db _]
   (:push/previews-enabled? db true)))

(re-frame/reg-event-db
 :push/hydrate-previews-setting
 (fn [db [_ enabled?]]
   (assoc db :push/previews-enabled? enabled?)))

(re-frame/reg-event-fx
 :push/toggle-previews
 (fn [_ [_ enabled?]]
   (store/set-setting! "show_previews" enabled?)
   (when (.isNativePlatform Capacitor)
     (-> (.setPreviewPreferences shadow-device #js {:enabled enabled?})
         (.catch #(log/error "Failed to save native preview prefs:" %))))
   {:dispatch [:push/hydrate-previews-setting enabled?]}))


(re-frame/reg-event-fx
 :push/create-sleepy-shadow
 (fn [{:keys [db]} [_ credentials]]
   (when (contains? #{"ios" "android"} (.getPlatform Capacitor))
     (log/info "Creating Sleepy Shadow Device...")
     (let [user-id (or (:user-id credentials) (:userId credentials) (:active-user-id db))]
       (go
         (try
           (if-let [script-path (<! (active-notification-script))]
             (let [script-name (last (str/split script-path #"/"))
                   source      (<p! (fetch-notification-script script-path))
                   payload     (cond-> (assoc credentials
                                              :userId             user-id
                                              :notificationScript script-name)
                                 (not (str/blank? source))
                                 (assoc :scriptSource source))
                   res         (<p! (.createSleepyShadow shadow-device (clj->js payload)))]
               (log/info "Sleepy Shadow SUCCESS:" (.-status res)))
             (log/error "Sleepy Shadow FAILED: No push-url returned from engine"))
           (catch :default e
             (log/error "Sleepy Shadow FAILED:" e))))))
   {}))

(re-frame/reg-event-fx
 :push/activate-shadow
 (fn [{:keys [db]} [_ token]]
   (let [active-user (:active-user-id db)
         old-user    (:active-push-user db)
         app-id      (resolve-app-id)
         platform    (.getPlatform Capacitor)]
     (go
       (try
         (when (and old-user (string? old-user) (not= old-user active-user))
           (try
             (<p! (.deactivateShadow shadow-device
                                     #js {:pushToken token
                                          :userId    old-user
                                          :appId     app-id
                                          :platform  platform}))
             (catch js/Error e
               (log/warn "Silent deactivate cleanup error:" e))))
         (<p! (.activateShadow shadow-device
                               #js {:pushToken token
                                    :pushUrl   (:push-url @!config)
                                    :userId    active-user
                                    :appId     app-id
                                    :platform  platform}))
         (re-frame/dispatch [:push/set-active-user active-user])
         (catch js/Error err
           (log/error "Shadow Activated FAILED:" err)
           (re-frame/dispatch [:modal/open-shadow-login]))))
     {})))

(re-frame/reg-event-fx
 :push/deactivate-shadow
 (fn [{:keys [db]} _]
   (let [token       (:native-token db)
         target-user (:active-push-user db)
         app-id      (resolve-app-id)
         platform    (.getPlatform Capacitor)]
     (when (and target-user (string? target-user))
       (go
         (try
           (<p! (.deactivateShadow shadow-device
                                   #js {:pushToken (or token "")
                                        :userId    target-user
                                        :appId     app-id
                                        :platform  platform}))
           (catch js/Error e
             (log/error "Shadow Deactivated FAILED:" e)))
         (re-frame/dispatch [:push/hydrate-status nil])))
     {})))

(re-frame/reg-event-fx
 :push/verify-shadow
 (fn [_ [_ user-id recovery-key]]
   (when (.isNativePlatform Capacitor)
     (go
       (try
         (let [prep    (<! (mesh/do-with-thread!
                            :engine-pool
                            {:handler   :prepare-push-verification
                             :arguments {:user-id      user-id
                                         :recovery-key recovery-key}}))
               payload (merge {:userId      user-id
                               :recoveryKey (or recovery-key "")}
                              (:verification-data prep))]
           (<p! (.verifyShadow shadow-device (clj->js payload)))
           (log/info "Shadow device verified successfully."))
         (catch js/Error e
           (log/error "Shadow verification failed:" e)))))
   {}))

(re-frame/reg-event-fx
 :push/check-verification-status
 (fn [_ [_ user-id]]
   (when (.isNativePlatform Capacitor)
     (-> (.getShadowStatus shadow-device #js {:userId user-id})
         (.then (fn [res]
                  (when (.-isVerified res)
                    (log/info "Shadow client is already verified, skipping prompt.")
                    (re-frame/dispatch [:sdk/verification-success]))))
         (.catch #(log/error "Failed to fetch shadow status:" %))))
   {}))



(re-frame/reg-event-fx
 :push/store-native-token
 (fn [{:keys [db]} [_ token]]
   {:db (assoc db :native-token token)
    :fx [[:dispatch [:push/activate-shadow token]]]}))

(re-frame/reg-sub
 :push/native-token
 (fn [db _]
   (:native-token db)))

(defn init! []
  (when (.isNativePlatform Capacitor)
    (re-frame/dispatch [:push/sync-engine-script])
    (.addListener PushNotifications "registration"
      (fn [token-res]
        (let [token (.-value token-res)]
          (log/info "Native Push Token Received:" token)
          (re-frame/dispatch [:push/store-native-token token]))))
    (.addListener PushNotifications "registrationError"
      (fn [err]
        (log/error "Native Push Registration Error:" (.-error err))))
    (.addListener PushNotifications "pushNotificationReceived"
      (fn [notification]
        (log/info "Foreground Push Received:" notification)))
    (.addListener PushNotifications "pushNotificationActionPerformed"
      (fn [action]
        (let [data   (.. action -notification -data)
              target (or (.-push_tag data) (.-room_id data))]
          (when target
            (log/info "User tapped notification:" target)
            (re-frame/dispatch [:nav/route-from-push target])))))))

(defn url-base64->uint8-array [base64-string]
  (let [padding      (.repeat "=" (mod (- 4 (mod (.-length base64-string) 4)) 4))
        base64       (-> (.replace base64-string (js/RegExp. "-" "g") "+")
                         (.replace (js/RegExp. "_" "g") "/")
                         (str padding))
        raw-data     (js/atob base64)
        output-array (js/Uint8Array. (.-length raw-data))]
    (dotimes [i (.-length raw-data)]
      (aset output-array i (.charCodeAt raw-data i)))
    output-array))

(defn- buf->b64url [buf]
  (when buf
    (let [bytes  (js/Uint8Array. buf)
          len    (.-byteLength bytes)
          chunks #js []]
      (dotimes [i len]
        (.push chunks (js/String.fromCharCode (aget bytes i))))
      (-> (js/btoa (.join chunks ""))
          (str/replace "+" "-")
          (str/replace "/" "_")
          (str/replace #"=+$" "")))))

(defn- extract-push-keys [subscription]
  (when subscription
    (if (map? subscription)
      {:endpoint (:endpoint subscription)
       :p256dh   (or (:p256dh subscription) (get-in subscription [:keys :p256dh]))
       :auth     (or (:auth subscription) (get-in subscription [:keys :auth]))}
      (let [sub-json (try
                       (when (.-toJSON subscription)
                         (.toJSON subscription))
                       (catch :default _ nil))
            keys-obj (when sub-json (.-keys sub-json))
            endpoint (or (.-endpoint subscription)
                         (when sub-json (.-endpoint sub-json)))
            p256dh   (or (try
                           (buf->b64url (.getKey subscription "p256dh"))
                           (catch :default _ nil))
                         (when keys-obj (.-p256dh keys-obj)))
            auth     (or (try
                           (buf->b64url (.getKey subscription "auth"))
                           (catch :default _ nil))
                         (when keys-obj (.-auth keys-obj)))]
        {:endpoint endpoint
         :p256dh   p256dh
         :auth     auth}))))


(re-frame/reg-event-fx
 :push/register-pusher
 (fn [{:keys [db]} [_ subscription]]
   (let [owner-id                      (or (:active-user-id db)
                                           (:user-id db)
                                           (:auth/user-id db)
                                           "enabled")
         {:keys [endpoint p256dh auth]} (extract-push-keys subscription)
         app-id                        (resolve-app-id)
         push-url                      (:push-url @!config)]
     (cond
       (not app-id)   (log/error "Missing app-id!")
       (not push-url) (log/error "Missing push-url!")
       (not p256dh)   (log/error "Missing p256dh key from browser subscription!")
       (not auth)     (log/error "Missing auth key from browser subscription!")
       :else
       (go
         (let [res (<! (mesh/do-with-thread! :engine-pool
                                             {:handler   :register-pusher
                                              :arguments {:p256dh   p256dh
                                                          :auth     auth
                                                          :endpoint endpoint
                                                          :app-id   app-id
                                                          :push-url push-url
                                                          :app-name (:app-name @!config)
                                                          :lang     (or js/navigator.language "en")}}))]
           (if (#{"success" :success} (:status res))
             (do
               (log/info "Web Pusher registered successfully!")
               (store/set-setting! "web_push_owner" owner-id)
               (re-frame/dispatch [:push/hydrate-status owner-id]))
             (log/error "Server rejected Web Pusher:" res))))))
   {}))

(re-frame/reg-event-fx
 :push/remove-pusher
 (fn [_ [_ pushkey]]
   (let [app-id (resolve-app-id)]
     (go
       (let [res (<! (mesh/do-with-thread! :engine-pool
                                           {:handler   :remove-pusher
                                            :arguments {:pushkey pushkey
                                                        :app-id  app-id}}))]
         (if (= (:status res) "success")
           (log/info "Successfully removed Web Pusher.")
           (log/error "Failed to remove Web Pusher:" (:msg res))))))
   {}))

(re-frame/reg-event-fx
 :push/clear-all
 (fn [{:keys [db]} _]
   (go
     (let [res (<! (mesh/do-with-thread! :engine-pool {:handler :clear-all-pushers}))]
       (if (= (:status res) "success")
         (do
           (log/info "Successfully eradicated all pushers.")
           (when-not (.isNativePlatform Capacitor)
             (when-let [sw (.-serviceWorker js/navigator)]
               (-> (.-ready sw)
                   (.then (fn [reg]
                            (-> (.. reg -pushManager getSubscription)
                                (.then (fn [sub]
                                         (when sub (.unsubscribe sub))))))))))
           (when (.isNativePlatform Capacitor)
             (let [token       (:native-token db)
                   target-user (:active-push-user db)
                   app-id      (resolve-app-id)
                   platform    (.getPlatform Capacitor)]
               (when target-user
                 (.deactivateShadow shadow-device
                                    #js {:pushToken (or token "")
                                         :userId    target-user
                                         :appId     app-id
                                         :platform  platform}))))
           (store/set-setting! "web_push_owner" nil)
           (re-frame/dispatch [:push/hydrate-status nil]))
         (log/error "Failed to clear pushers:" (:msg res)))))
   {}))

(re-frame/reg-sub
 :push/status
 (fn [db _]
   (:push-status db :disabled)))

(re-frame/reg-event-db
 :push/set-status
 (fn [db [_ status]]
   (assoc db :push-status status)))

(defn check-web-push-status! [dispatch-cb]
  (if-let [sw (.-serviceWorker js/navigator)]
    (-> (.-ready sw)
        (.then (fn [reg]
                 (-> (.. reg -pushManager getSubscription)
                     (.then (fn [sub]
                              (dispatch-cb (some? sub))))))))
    (dispatch-cb false)))

(re-frame/reg-event-db
 :push/hydrate-status
 (fn [db [_ active-push-user]]
   (assoc db :active-push-user active-push-user
             :push-status (if active-push-user :enabled :disabled))))

(re-frame/reg-event-fx
 :push/check-status
 (fn [{:keys [db]} _]
   (if (.isNativePlatform Capacitor)
     (go
       (try
         (let [res       (<p! (.getActivePushUser shadow-device))
               user      (.-activeUser res)
               safe-user (if (identical? user js/undefined) nil user)]
           (re-frame/dispatch [:push/hydrate-status safe-user]))
         (catch js/Error e
           (log/error "Failed to check native push status:" e))))
     (check-web-push-status!
      (fn [is-enabled?]
        (if is-enabled?
          (-> (store/get-setting "web_push_owner")
              (.then #(re-frame/dispatch [:push/hydrate-status (or % (:active-user-id db) "enabled")])))
          (re-frame/dispatch [:push/hydrate-status nil])))))
   {}))

(re-frame/reg-event-fx
 :push/enable
 (fn [{:keys [db]} _]
   (if (.isNativePlatform Capacitor)
     (go
       (try
         (let [perm (<p! (.requestPermissions PushNotifications))]
           (if (= (.-receive perm) "granted")
             (if-let [token (:native-token db)]
               (re-frame/dispatch [:push/activate-shadow token])
               (.register PushNotifications))
             (log/warn "Native notification permission denied.")))
         (catch js/Error e
           (log/error "Native Push setup failed:" e))))
     (if-not (and (exists? js/window.PushManager) (exists? js/navigator.serviceWorker))
       (do (log/error "Push messaging is not supported.") {})
       (-> (p/let [permission (js/Notification.requestPermission)]
             (if (= permission "granted")
               (p/let [reg          (.-ready js/navigator.serviceWorker)
                       existing-sub (.getSubscription (.-pushManager reg))
                       sub          (if existing-sub
                                      existing-sub
                                      (.subscribe (.-pushManager reg)
                                                  #js {:userVisibleOnly      true
                                                       :applicationServerKey (url-base64->uint8-array (:vapid-key @!config))}))]
                 (re-frame/dispatch [:push/register-pusher (extract-push-keys sub)]))
               (log/warn "Notification permission denied.")))
           (p/catch #(log/error "Push setup failed:" %)))))
   {}))

(re-frame/reg-event-fx
 :push/disable
 (fn [_ _]
   (if (.isNativePlatform Capacitor)
     {:dispatch [:push/deactivate-shadow]}
     (do
       (when-let [sw (.-serviceWorker js/navigator)]
         (-> (.-ready sw)
             (.then (fn [reg]
                      (-> (.. reg -pushManager getSubscription)
                          (.then (fn [sub]
                                   (when sub
                                     (let [{:keys [p256dh]} (extract-push-keys sub)]
                                       (when p256dh
                                         (re-frame/dispatch [:push/remove-pusher p256dh]))
                                       (.unsubscribe sub))))))))))
       (store/set-setting! "web_push_owner" nil)
       {:dispatch [:push/hydrate-status nil]}))))