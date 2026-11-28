(ns service-worker-handler
  (:require
   [clojure.string :as str]
   [taoensso.timbre :as log]
   [paradise.shared.client.cache :as cache]
   [paradise.shared.client.config :refer [check-remote-version bundled-version claim-update-reload!]]
   [re-frame.db :as db]
   [promesa.core :as p]
   [re-frame.core :as re-frame]
   [cljs-workers.mesh :as mesh]
   [cljs.core.async :refer [go <!]]))

(defonce !explicit-update-reload? (atom false))

(defn- clear-app-caches!
  "Purges runtime asset caches (IDB assets/meta + non-media CacheStorage) while
   preserving cached user media (avatars, emotes, stickers)."
  []
  (p/all
   [(-> (cache/clear-runtime-assets!)
        (p/catch (fn [_] nil)))
    (if (exists? js/caches)
      (-> (.keys js/caches)
          (p/then (fn [cache-keys]
                    (p/all
                     (for [k cache-keys
                           :when (not (str/starts-with? (str k) "paradise-media-v1"))]
                       (.delete js/caches k)))))
          (p/catch (fn [_] nil)))
      (p/resolved nil))]))

(re-frame/reg-event-fx
 :app/apply-update
 (fn [_ _]
   (-> (clear-app-caches!)
       (p/then
        (fn [_]
          (if (exists? js/navigator.serviceWorker)
            (-> (.getRegistration js/navigator.serviceWorker)
                (.then (fn [reg]
                         (let [waiting-sw (when reg (.-waiting reg))]
                           (if waiting-sw
                             (do
                               (log/info "Telling new SW to activate...")
                               (reset! !explicit-update-reload? true)
                               (.postMessage waiting-sw #js {:type "SKIP_WAITING"})
                               (js/setTimeout
                                (fn []
                                  (when @!explicit-update-reload?
                                    (reset! !explicit-update-reload? false)
                                    (.reload js/window.location)))
                                1500))
                             (do
                               (log/info "No waiting SW found, hard reloading...")
                               (.reload js/window.location))))))
                (.catch (fn [_]
                          (.reload js/window.location))))
            (.reload js/window.location)))))
   {}))

(re-frame/reg-event-fx
 :app/clear-cache-for-update
 (fn [_ _]
   (log/warn "Purging runtime caches and service workers for update...")
   (-> (p/let [_    (clear-app-caches!)
               regs (if (exists? js/navigator.serviceWorker)
                      (-> (.getRegistrations js/navigator.serviceWorker)
                          (p/catch (fn [_] #js [])))
                      (p/resolved #js []))
               _    (p/all (map #(-> (.unregister %) (p/catch (fn [_] false))) regs))]
         (.reload js/window.location))
       (p/catch (fn [_]
                  (.reload js/window.location))))
   {}))

(defonce !sw-listeners-installed? (atom false))
(defonce !had-sw-controller? (atom false))

(defn- activate-waiting-sw! [sw-worker]
  (when sw-worker
    (.postMessage sw-worker #js {:type "SKIP_WAITING"})))

(defn- watch-sw-registration! [sw-container reg]
  (when reg
    (when-let [waiting-sw (.-waiting reg)]
      (activate-waiting-sw! waiting-sw))
    (.addEventListener
     reg
     "updatefound"
     (fn []
       (when-let [installing-sw (.-installing reg)]
         (.addEventListener
          installing-sw
          "statechange"
          (fn []
            (when (and (= (.-state installing-sw) "installed")
                       (.-controller sw-container))
              (activate-waiting-sw! installing-sw)))))))
    (-> (.update reg)
        (.catch (fn [_] nil)))))

(defn register-sw! []
  (when (exists? js/navigator.serviceWorker)
    (let [sw-container js/navigator.serviceWorker
          try-sync!    (fn []
                         (-> sw-container
                             .-ready
                             (.then (fn [_]
                                      (re-frame/dispatch [:auth/sync-sw-with-worker])))))]
      (when (.-controller sw-container)
        (reset! !had-sw-controller? true))
      (-> (.register sw-container "/sw.js" #js {:type "module" :updateViaCache "none"})
          (.then (fn [reg]
                   (watch-sw-registration! sw-container reg)))
          (.catch (fn [err]
                    (log/warn "SW registration skipped or failed:" err))))
      (try-sync!)
      (when (compare-and-set! !sw-listeners-installed? false true)
        (.addEventListener
         sw-container
         "controllerchange"
         (fn []
           (if (compare-and-set! !explicit-update-reload? true false)
             (.reload js/window.location)
             (let [replaced-existing? @!had-sw-controller?]
               (reset! !had-sw-controller? true)
               (log/debug "re-frame: SW Controller changed, checking version...")
               (try-sync!)
               (-> (check-remote-version)
                   (p/then
                    (fn [remote-v]
                      (let [db        @db/app-db
                            current-v (or bundled-version
                                          (get-in db [:config :bundled-version])
                                          (get-in db [:config :version]))
                            mismatch? (and remote-v
                                           current-v
                                           (not= (str remote-v) (str current-v)))]
                        (cond
                          mismatch?
                          (do
                            (log/info "SW Update: Version mismatch detected"
                                      remote-v "vs" current-v)
                            (if (claim-update-reload! remote-v)
                              (re-frame/dispatch [:app/clear-cache-for-update])
                              (re-frame/dispatch [:app/update-detected])))

                          replaced-existing?
                          (do
                            (log/info "SW Update: New service worker took control; prompting refresh.")
                            (-> (cache/clear-runtime-assets!)
                                (p/catch (fn [_] nil)))
                            (re-frame/dispatch [:app/update-detected]))))))
                   (p/catch (fn [err]
                              (log/error "SW Update: Failed to check version" err)
                              (when replaced-existing?
                                (re-frame/dispatch [:app/update-detected])))))))))
        (.addEventListener
         sw-container
         "message"
         (fn [event]
           (let [data     (.-data event)
                 msg-type (when data (.-type data))]
             (cond
               (= msg-type "NAVIGATE_TO_ROOM")
               (let [room-id (.-room_id data)]
                 (log/debug "SW requested navigation to room:" room-id)
                 (re-frame/dispatch [:rooms/select room-id]))

               (= msg-type "PUSH_NOTIFICATION_RECEIVED")
               (re-frame/dispatch [:notif/push-audio-cue
                                   {:room-id  (.-room_id data)
                                    :event-id (.-event_id data)}])

               (= msg-type "RESOLVE_PUSH")
               (when-let [reply-port (aget (.-ports event) 0)]
                 (let [raw-payload (js->clj (.-payload data) :keywordize-keys true)
                       with-data   (assoc raw-payload :data raw-payload)
                       args        (assoc with-data :payload with-data)]
                   (log/debug "SW requested push decryption")
                   (go
                     (let [res (<! (mesh/do-with-thread!
                                    :engine-pool
                                    {:handler   :resolve-push-notification
                                     :arguments args}))]
                       (if (#{"success" :success} (:status res))
                         (.postMessage reply-port (clj->js (:notification res)))
                         (do
                           (log/warn "Push decryption did not resolve:" (:status res))
                           (.postMessage reply-port nil)))))))))))))))

(re-frame/reg-event-fx
 :auth/sync-sw-with-worker
 (fn [{:keys [db]} [_ attempt]]
   (let [tries (or attempt 0)]
     (if (= (:auth-status db) :logged-in)
       (do
         (go
           (let [res (<! (mesh/do-with-thread! :engine-pool {:handler :get-client-context}))]
             (if (#{"success" :success} (:status res))
               (re-frame/dispatch [:auth/sync-sw-only (:session res)])
               (when (< tries 6)
                 (js/setTimeout
                  #(re-frame/dispatch [:auth/sync-sw-with-worker (inc tries)])
                  750)))))
         {})
       {:clear-sw-auth {:all? true}}))))

(re-frame/reg-event-fx
 :auth/sync-sw-only
 (fn [_ [_ session]]
   {:sync-sw-auth session}))

(re-frame/reg-event-fx
 :auth/clear-sw-session
 (fn [_ [_ opts]]
   {:clear-sw-auth (or opts {:all? true})}))

(re-frame/reg-fx
 :clear-sw-auth
 (fn [{:keys [user-id all?]}]
   (when (exists? js/navigator.serviceWorker)
     (-> (.-ready js/navigator.serviceWorker)
         (.then (fn [reg]
                  (when-let [sw-active (.-active reg)]
                    (if (or all? (nil? user-id))
                      (.postMessage sw-active #js {:type "CLEAR_ALL_SESSIONS"})
                      (.postMessage sw-active #js {:type    "CLEAR_SESSION"
                                                   :user-id (str user-id)})))))
         (.catch (fn [_] nil))))))

(re-frame/reg-fx
 :sync-sw-auth
 (fn [session]
   (when (and session (exists? js/navigator.serviceWorker))
     (-> (.-ready js/navigator.serviceWorker)
         (.then (fn [reg]
                  (if-let [sw-active (.-active reg)]
                    (try
                      (let [base-url    (or (:server-url session)
                                            (:base-url session))
                            parsed-u    (when base-url (js/URL. base-url))
                            valid-http? (and parsed-u (#{"http:" "https:"} (.-protocol parsed-u)))
                            origin      (when valid-http? (.-origin parsed-u))
                            auth-media? (not (false? (:auth-media? session)))
                            token       (when (and valid-http? auth-media?)
                                          (or (:token session) (:accessToken session)))
                            user-id     (or (:user-id session) (:userId session))
                            payload     (cond-> {:user-id     user-id
                                                 :base-url    (when valid-http? base-url)
                                                 :origin      origin
                                                 :auth-media? auth-media?}
                                          token
                                          (assoc :token token)

                                          (contains? session :media-paths)
                                          (assoc :media-paths (:media-paths session))

                                          (:thumbnail-template session)
                                          (assoc :thumbnail-template (:thumbnail-template session)))]
                        (.postMessage sw-active #js {:type    "SET_SESSION"
                                                     :session (clj->js payload)})
                        (js/console.log "re-frame: Session synced for" user-id "at" origin))
                      (catch js/Error e
                        (js/console.error "re-frame: Failed to parse session base-url" e)))
                    (js/console.warn "re-frame: SW active worker not found"))))))))