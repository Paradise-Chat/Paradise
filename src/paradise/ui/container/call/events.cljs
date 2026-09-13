(ns paradise.ui.container.call.events
  (:require
   [re-frame.core :as rf]
   [cljs-workers.core :as main]
   [cljs-workers.mesh :as mesh]
   [cljs.core.async :refer [go <!]]
   [paradise.shared.client.state :as state]
   [promesa.core :as p]
   [taoensso.timbre :as log]
   [paradise.ui.container.call.runtime :as rtc]
   [paradise.ui.container.call.native :as native]))

(rf/reg-event-fx
 :call/initiate-e2ee
 (fn [{:keys [db]} [_ room-id]]
     (mesh/do-with-thread! :engine-pool
                         {:handler :call/broadcast-e2ee-key
                          :arguments {:room-id room-id}})


   {:db db}))


(rf/reg-event-fx
 :call/get-key-provider
 (fn [_]
   (go
       (let [res (<! (mesh/do-with-thread!
                      :engine-pool
                      {:handler :call/get-key-provider
                       :arguments {}}))]
         (when (and res (:provider res))
           (js/console.log "Engine signaled KeyProvider ready"))))))




(rf/reg-event-fx
 :call/e2ee-key-received
 (fn [{:keys [db]} [_ {:keys [participant-id key-index key-array room-id]}]]

   (rtc/inject-key! participant-id key-index key-array)

   {:db (assoc-in db [:call :e2ee-keys participant-id key-index] key-array)}))



(rf/reg-event-fx
 :call/init-widget
 (fn [{:keys [db]} [_ room-id]]
   (js/console.log "DISPATCHED INIT WIDGET")
   (mesh/do-with-thread! :engine-pool {:handler :call/start-crypto-driver :arguments {:room-id room-id}})
   {:db db}))



(rf/reg-event-fx
 :call/start
 (fn [{:keys [db]} [_ room-id]]
   (when-let [pool @state/!engine-pool]
     (go
       (let [res (<! (main/do-with-pool! pool
                                         {:handler :call/request-sfu-token
                                          :arguments {:room-id room-id}}))]
         (if (or (= (:status res) :success) (= (:status res) "success"))
           (do
             (js/console.log "LiveKit Payload Received:" res)
             (rf/dispatch [:call/sfu-token-received {:url (:url res) :token (:token res)}]))
           (log/error "Engine failed to negotiate LiveKit token:" (:msg res))))))

   {:db (-> db
            (assoc-in [:call :active-room-id] room-id)
            (assoc-in [:call :is-active?] true)
            (assoc-in [:call :status] :requesting-token))}))

(rf/reg-event-fx
 :call/sfu-token-received
 (fn [{:keys [db]} [_ {:keys [url token]}]]
   {:db (-> db
            (assoc-in [:call :livekit-url] url)
            (assoc-in [:call :livekit-token] token)
            (assoc-in [:call :status] :connected))}))

(rf/reg-event-fx
 :call/hangup
 (fn [{:keys [db]} _]
   (when-let [pool @state/!engine-pool]
     (main/do-with-pool! pool {:handler :call/leave-sfu}))
   {:db (-> db
            (assoc-in [:call :active-room-id] nil)
            (assoc-in [:call :is-active?] false)
            (assoc-in [:call :livekit-url] nil)
            (assoc-in [:call :livekit-token] nil)
            (assoc-in [:call :e2ee-keys] nil)
            (assoc-in [:call :status] :idle))}))

(rf/reg-fx
 :livekit/set-microphone
 (fn [enabled?]
   (-> (p/let [_ (rtc/set-microphone-enabled! enabled?)]
         (rf/dispatch
          [:call/media-state-changed
           :audio
           enabled?]))

       (p/catch
        (fn [err]
          (log/error
           err
           "Failed to change microphone state")

          (rf/dispatch
           [:call/media-error
            :microphone
            err]))))))


(rf/reg-fx
 :livekit/set-camera
 (fn [enabled?]
   (-> (p/let [_ (rtc/set-camera-enabled! enabled?)]
         (rf/dispatch
          [:call/media-state-changed
           :video
           enabled?]))

       (p/catch
        (fn [err]
          (log/error
           err
           "Failed to change camera state")

          (rf/dispatch
           [:call/media-error
            :camera
            err]))))))


(rf/reg-fx
 :livekit/set-screen-share
 (fn [enabled?]
   (-> (p/let [_ (rtc/set-screen-share-enabled! enabled?)]
         (rf/dispatch
          [:call/media-state-changed
           :screen-sharing
           enabled?]))

       (p/catch
        (fn [err]
          (log/error
           err
           "Failed to change screen share state")

          (rf/dispatch
           [:call/media-error
            :screen-share
            err]))))))


(rf/reg-fx
 :livekit/inject-e2ee-key
 (fn [{:keys [participant-id
              key-index
              key-array]}]

   (-> (p/let [_ (rtc/inject-key!
                  participant-id
                  key-index
                  key-array)]
         nil)

       (p/catch
        (fn [err]
          (log/error
           err
           "Failed to inject LiveKit E2EE key"
           participant-id
           key-index)

          (rf/dispatch
           [:call/e2ee-error
            {:participant-id participant-id
             :key-index key-index
             :error err}]))))))


(rf/reg-fx
 :livekit/disconnect
 (fn [_]
   (-> (p/let [_ (rtc/disconnect!)]
         nil)

       (p/catch
        (fn [err]
          (log/warn
           err
           "LiveKit disconnect returned an error"))))))




(rf/reg-event-fx
 :call/e2ee-key-received
 (fn [_ [_ {:keys [participant-id
                   key-index
                   key-array]}]]

   {:livekit/inject-e2ee-key
    {:participant-id participant-id
     :key-index key-index
     :key-array key-array}}))


(rf/reg-event-db
 :call/e2ee-error
 (fn [db [_ error]]
   (assoc-in
    db
    [:call :e2ee-error]
    error)))



(rf/reg-event-fx
 :call/toggle-audio
 (fn [{:keys [db]} _]
   {:livekit/set-microphone
    (not
     (get-in
      db
      [:call :audio-enabled?]
      true))}))


(rf/reg-event-fx
 :call/toggle-video
 (fn [{:keys [db]} _]
   {:livekit/set-camera
    (not
     (get-in
      db
      [:call :video-enabled?]
      false))}))


(rf/reg-event-fx
 :call/toggle-screen-share
 (fn [{:keys [db]} _]
   {:livekit/set-screen-share
    (not
     (get-in
      db
      [:call :screen-sharing?]
      false))}))


(rf/reg-event-db
 :call/toggle-deafen
 (fn [db _]
   (update-in
    db
    [:call :deafened?]
    not)))



(rf/reg-event-db
 :call/media-state-changed
 (fn [db [_ kind enabled?]]
   (case kind

     :audio
     (assoc-in
      db
      [:call :audio-enabled?]
      enabled?)

     :video
     (assoc-in
      db
      [:call :video-enabled?]
      enabled?)

     :screen-sharing
     (assoc-in
      db
      [:call :screen-sharing?]
      enabled?)

     db)))


(rf/reg-event-db
 :call/update-media-state
 (fn [db [_ {:keys [audio
                    video
                    screen-sharing]}]]

   (cond-> db

     (some? audio)
     (assoc-in
      [:call :audio-enabled?]
      audio)

     (some? video)
     (assoc-in
      [:call :video-enabled?]
      video)

     (some? screen-sharing)
     (assoc-in
      [:call :screen-sharing?]
      screen-sharing))))


(rf/reg-event-db
 :call/media-error
 (fn [db [_ device err]]
   (assoc-in
    db
    [:call :media-error]
    {:device device
     :error err})))

(rf/reg-event-db
 :call/set-active-room
 (fn [db [_ room-id]]
   (log/info
    "Call: Setting active room to"
    room-id)

   (-> db
       (assoc-in
        [:call :active-room-id]
        room-id)

       (assoc-in
        [:call :audio-enabled?]
        true)

       (assoc-in
        [:call :video-enabled?]
        false)

       (assoc-in
        [:call :screen-sharing?]
        false)

       (assoc-in
        [:call :disconnecting?]
        false)

       (assoc-in
        [:call :media-error]
        nil)

       (assoc-in
        [:call :e2ee-error]
        nil))))


(rf/reg-event-db
 :call/set-active
 (fn [db [_ active?]]
   (assoc-in
    db
    [:call :is-active?]
    active?)))


(rf/reg-event-fx
 :call/hangup
 (fn [{:keys [db]} [_ opts]]
   (let [opts
         (or opts {})

         room-id
         (or (:room-id opts)
             (get-in
              db
              [:call :active-room-id]))

         skip-native?
         (get opts :skip-native? false)]

     (when (and room-id
                (not skip-native?))
       (native/end-call! room-id))

     {:db
      (assoc-in
       db
       [:call :disconnecting?]
       true)

      :livekit/disconnect
      true})))


(rf/reg-event-fx
 :call/livekit-disconnected
 (fn [{:keys [db]} [_ reason]]
   (let [room-id
         (get-in
          db
          [:call :active-room-id])

         disconnecting?
         (get-in
          db
          [:call :disconnecting?]
          false)]

     (log/info
      "LiveKit disconnected:"
      reason)

     (when (and room-id
                (not disconnecting?))
       (native/end-call! room-id))

     {:dispatch
      [:call/teardown]})))


(rf/reg-event-fx
 :call/teardown
 (fn [{:keys [db]} _]
   (rtc/reset-session!)

   {:db
    (-> db
        (assoc-in
         [:call :active-room-id]
         nil)

        (assoc-in
         [:call :is-active?]
         false)

        (assoc-in
         [:call :disconnecting?]
         false)

        (assoc-in
         [:call :screen-sharing?]
         false)

        (assoc-in
         [:call :media-error]
         nil)

        (assoc-in
         [:call :e2ee-error]
         nil)

        (assoc
         :main-focus
         :timeline))}))

(rf/reg-event-db
 :call/toggle-chat
 (fn [db _]
   (update-in
    db
    [:call :chat-open?]
    not)))

(rf/reg-sub
 :call/state
 (fn [db _]
   (:call db)))


(rf/reg-sub
 :call/active-room
 (fn [db _]
   (get-in
    db
    [:call :active-room-id])))


(rf/reg-sub
 :call/is-active?
 (fn [db _]
   (get-in
    db
    [:call :is-active?]
    false)))


(rf/reg-sub
 :call/audio-enabled?
 (fn [db _]
   (get-in
    db
    [:call :audio-enabled?]
    true)))


(rf/reg-sub
 :call/video-enabled?
 (fn [db _]
   (get-in
    db
    [:call :video-enabled?]
    false)))


(rf/reg-sub
 :call/deafened?
 (fn [db _]
   (get-in
    db
    [:call :deafened?]
    false)))


(rf/reg-sub
 :call/screen-sharing?
 (fn [db _]
   (get-in
    db
    [:call :screen-sharing?]
    false)))


(rf/reg-sub
 :call/chat-open?
 (fn [db _]
   (get-in
    db
    [:call :chat-open?]
    false)))


(rf/reg-sub
 :call/media-error
 (fn [db _]
   (get-in
    db
    [:call :media-error])))


(rf/reg-sub
 :call/e2ee-error
 (fn [db _]
   (get-in
    db
    [:call :e2ee-error])))