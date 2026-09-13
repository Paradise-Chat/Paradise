(ns paradise.ui.container.call.call-view
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [promesa.core :as p]
            ["react" :as react]
            ["livekit-client" :as lkc]
            ["@livekit/components-react" :as lk]
            [paradise.ui.container.call.runtime :as rtc]
            [paradise.ui.container.reusable :refer [room-header]]))


(def GridLayout
  (.-GridLayout lk))

(def ControlBar
  (.-ControlBar lk))

(def ParticipantTile
  (.-ParticipantTile lk))


(def RoomContext
  (.-RoomContext lk))


(def track-sources
  #js [#js {:source
            (.-Camera (.-Source (.-Track lkc)))
            :withPlaceholder true}

       #js {:source
            (.-ScreenShare (.-Source (.-Track lkc)))
            :withPlaceholder false}])

(defn- sync-local-media-state! [room]
  (when-let [participant (.-localParticipant room)]
    (rf/dispatch
     [:call/update-media-state
      {:audio
       (boolean (.-isMicrophoneEnabled participant))
       :video
       (boolean (.-isCameraEnabled participant))
       :screen-sharing
       (boolean (.-isScreenShareEnabled participant))}])))


(defn custom-call-stage []
  (let [room ((.-useMaybeRoomContext lk))]
    (react/useEffect
     (fn []
       (when room
         (rtc/set-room! room)
         (sync-local-media-state! room)
         (let [room-events             (.-RoomEvent lkc)
               track-muted             (.-TrackMuted room-events)
               track-unmuted           (.-TrackUnmuted room-events)
               local-track-published   (.-LocalTrackPublished room-events)
               local-track-unpublished (.-LocalTrackUnpublished room-events)
               sync-handler            (fn [& _] (sync-local-media-state! room))]

           (.on room track-muted sync-handler)
           (.on room track-unmuted sync-handler)
           (.on room local-track-published sync-handler)
           (.on room local-track-unpublished sync-handler)

           (-> (p/let [_ (rtc/set-e2ee-enabled! true)]
                 (js/console.log "[CRYPTO] LiveKit E2EE enabled"))
               (p/catch (fn [err]
                          (js/console.error "[CRYPTO] Failed enabling LiveKit E2EE" err))))

           (fn []
             (.off room track-muted sync-handler)
             (.off room track-unmuted sync-handler)
             (.off room local-track-published sync-handler)
             (.off room local-track-unpublished sync-handler)
             (when (= room (rtc/current-room))
               (rtc/clear-room!))))))
     #js [room])
    nil))


(defn active-call-grid-inner []
  (let [tracks ((.-useTracks lk) track-sources)]
    [:div.call-grid-container
     [:div.call-grid-layout-wrapper
      [:> GridLayout {:tracks tracks :className "call-grid-layout"}
       [:> ParticipantTile]]]

     [:div.call-control-bar-wrapper
      [:> ControlBar {:variation "minimal"
                      :controls {:microphone true
                                 :camera true
                                 :screenShare true
                                 :leave true}}]]]))


(defn active-call-grid []
  (let [[room set-room!] (react/useState @rtc/!room)
        tr               @(rf/subscribe [:i18n/tr])]
    (react/useEffect
     (fn []
       (let [watch-id (keyword (gensym "room-sync"))]
         (add-watch rtc/!room watch-id
                    (fn [_ _ _ new-room]
                      (set-room! new-room)))
         (set-room! @rtc/!room)
         (fn []
           (remove-watch rtc/!room watch-id))))
     #js [])

    (if room
      [:> (.-Provider RoomContext) {:value room}
       [:f> active-call-grid-inner]]
      [:div.call-grid-loading
       (tr [:container.call/bridging-context])])))


(defn ^:ui call-view [room-id]
  (let [call-state   @(rf/subscribe [:call/state])
        room-meta    @(rf/subscribe [:rooms/active-metadata])
        tr           @(rf/subscribe [:i18n/tr])
        display-name (or (:name room-meta) room-id)
        status       (:status call-state)]

    [:div.call-view-host
     [room-header {:display-name display-name :active-id room-id :compact? false}]

     [:div.call-video-area
      (cond
        (= status :requesting-token)
        [:div.call-token-negotiation
         (tr [:container.call/negotiating-token])]

        (= status :connected)
        [:f> active-call-grid]

        :else
        [:div.call-idle-container
         [:div.call-idle-text (tr [:call/idle])]
         [:button.call-start-button
          {:on-click (fn [_]
                       (rf/dispatch [:call/init-widget room-id])
                       (js/setTimeout #(rf/dispatch [:call/start room-id]) 500))}
          (tr [:container.call/start-join])]])]]))
