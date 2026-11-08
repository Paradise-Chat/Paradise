(ns service-worker-handler
  (:require
   [taoensso.timbre :as log]
   [paradise.shared.client.config :refer [check-remote-version]]
   [re-frame.db :as db]
   [promesa.core :as p]
   [re-frame.core :as re-frame]
   [cljs-workers.mesh :as mesh]
   [cljs.core.async :refer [go <!]]))

(re-frame/reg-event-fx
 :app/apply-update
 (fn [_ _]
   (if (exists? js/navigator.serviceWorker)
     (-> (.getRegistration js/navigator.serviceWorker)
         (.then (fn [reg]
                  (let [waiting-sw (when reg (.-waiting reg))]
                    (if waiting-sw
                      (do
                        (log/info "Telling new SW to activate...")
                        (.postMessage waiting-sw #js {:type "SKIP_WAITING"}))
                      (do
                        (log/info "No waiting SW found, hard reloading...")
                        (.reload js/window.location true)))))))
     (.reload js/window.location true))
   {}))

(re-frame/reg-event-fx
 :app/clear-cache-for-update
 (fn [_ _]
   (log/warn "Purging all caches and service workers...")
   (p/let [cache-keys (.keys js/caches)
           _          (p/all (map #(.delete js/caches %) cache-keys))
           regs       (.getRegistrations js/navigator.serviceWorker)
           _          (p/all (map #(.unregister %) regs))]
     (.reload js/window.location true))
   {}))

(defn register-sw! []
  (when (exists? js/navigator.serviceWorker)
    (let [sw-container js/navigator.serviceWorker
          try-sync!    (fn []
                         (-> sw-container
                             .-ready
                             (.then (fn [_]
                                      (re-frame/dispatch [:auth/sync-sw-with-worker])))))]
      (-> (.register sw-container "/sw.js" #js {:type "module"})
          (.then (fn [reg]
                   (when (.-waiting reg)
                     (.postMessage (.-waiting reg) #js {:type "SKIP_WAITING"})))))
      (try-sync!)
      (.addEventListener sw-container "controllerchange"
                         (fn []
                           (log/debug "re-frame: SW Controller changed, checking version...")
                           (try-sync!)
                           (-> (check-remote-version)
                               (p/then (fn [remote-v]
                                         (let [db        @db/app-db
                                               current-v (get-in db [:config :version] "0.0.0")]
                                           (when (and remote-v
                                                      (not= (str remote-v) (str current-v))
                                                      (not= (str current-v) "0.0.0"))
                                             (log/info "SW Update: Version mismatch detected" remote-v "vs" current-v)
                                             (re-frame/dispatch [:app/clear-cache-for-update])))))
                               (p/catch (fn [err]
                                          (log/error "SW Update: Failed to check version" err))))))
      (.addEventListener sw-container "message"
                         (fn [event]
                           (let [data     (.-data event)
                                 msg-type (when data (.-type data))]
                             (cond
                               (= msg-type "NAVIGATE_TO_ROOM")
                               (let [room-id (.-room_id data)]
                                 (log/debug "SW requested navigation to room:" room-id)
                                 (re-frame/dispatch [:rooms/select room-id]))

                               (= msg-type "RESOLVE_PUSH")
                               (when-let [reply-port (aget (.-ports event) 0)]
                                 (let [raw-payload (js->clj (.-payload data) :keywordize-keys true)
                                       with-data   (assoc raw-payload :data raw-payload)
                                       args        (assoc with-data :payload with-data)]
                                   (log/debug "SW requested push decryption for payload:" raw-payload)
                                   (go
                                     (let [res (<! (mesh/do-with-thread!
                                                    :engine-pool
                                                    {:handler   :resolve-push-notification
                                                     :arguments args}))]
                                       (if (#{"success" :success} (:status res))
                                         (.postMessage reply-port (clj->js (:notification res)))
                                         (do
                                           (log/warn "Push decryption did not resolve:" res)
                                           (.postMessage reply-port nil))))))))))))))

(re-frame/reg-event-fx
 :auth/sync-sw-with-worker
 (fn [_ _]
   (go
     (let [res (<! (mesh/do-with-thread! :engine-pool {:handler :get-client-context}))]
       (if (= (:status res) "success")
         (re-frame/dispatch [:auth/sync-sw-only (:session res)])
         (js/setTimeout #(re-frame/dispatch [:auth/sync-sw-with-worker]) 500))))
   {}))

(re-frame/reg-event-fx
 :auth/sync-sw-only
 (fn [_ [_ session]]
   {:sync-sw-auth session}))

(re-frame/reg-fx
 :sync-sw-auth
 (fn [session]
   (when (and session (exists? js/navigator.serviceWorker))
     (-> (.-ready js/navigator.serviceWorker)
         (.then (fn [reg]
                  (if-let [sw-active (.-active reg)]
                    (try
                      (let [base-url (:base-url session)
                            token    (or (:token session) (:accessToken session))
                            user-id  (or (:user-id session) (:userId session))
                            origin   (when base-url (.-origin (js/URL. base-url)))
                            payload  (assoc session
                                            :base-url base-url
                                            :token    token
                                            :user-id  user-id
                                            :origin   origin)]
                        (.postMessage sw-active #js {:type    "SET_SESSION"
                                                     :session (clj->js payload)})
                        (js/console.log "re-frame: Session synced for" user-id "at" origin))
                      (catch js/Error e
                        (js/console.error "re-frame: Failed to parse session base-url" e)))
                    (js/console.warn "re-frame: SW active worker not found"))))))))