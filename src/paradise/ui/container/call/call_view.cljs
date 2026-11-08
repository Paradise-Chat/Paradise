(ns paradise.ui.container.call.call-view
  (:require
   [clojure.string :as str]
   [re-frame.core :as rf]
   ["react" :as react]
   [promesa.core :as p]
   [paradise.shared.utils.helpers :refer [format-call-participants]]
   [paradise.ui.global :refer [avatar]]
   [paradise.ui.container.call.runtime :as rtc]
   [paradise.ui.container.call.call-container :as cc]
   [paradise.ui.container.reusable :refer [room-header]]))

(defn- clean-user-id [raw-id]
  (when (and raw-id (not (str/blank? (str raw-id))))
    (or (re-find #"^@[^:]+:[^:|]+" (str raw-id))
        (str raw-id))))

(defn- toggle-fullscreen [el]
  (when el
    (if (.-fullscreenElement js/document)
      (-> (.exitFullscreen js/document) (p/catch (fn [_] nil)))
      (when (.-requestFullscreen el)
        (-> (.requestFullscreen el) (p/catch (fn [_] nil)))))))

(defn- resolve-lobby-participant [p members-map]
  (let [p-map         (cond
                        (map? p)    p
                        (string? p) {:userId p}
                        (some? p)   (js->clj p :keywordize-keys true)
                        :else       {})
        p-id          (or (not-empty (:userId p-map))
                          (not-empty (:user-id p-map))
                          (clean-user-id (:participant-id p-map))
                          (clean-user-id (:id p-map))
                          (when (string? p) (not-empty p)))
        member-info   (when (and members-map p-id)
                        (get members-map p-id))
        resolved-name (or (not-empty (:displayName p-map))
                          (not-empty (:display-name p-map))
                          (not-empty (:name p-map))
                          (not-empty (:display-name member-info))
                          (not-empty p-id)
                          "Unknown")
        resolved-url  (or (not-empty (:avatarUrl p-map))
                          (not-empty (:avatar-url p-map))
                          (not-empty (:avatar-url member-info)))]
    {:id            (or p-id resolved-name)
     :user-id       p-id
     :display-name  resolved-name
     :avatar-url    resolved-url
     :speaking?     (boolean (:speaking? p-map))
     :audio-muted?  (boolean (or (:audio-muted? p-map)
                                 (:isMuted p-map)
                                 (:muted p-map)))
     :screen-share? (= (:source p-map) "screen-share")}))

(defn media-video-element [{:keys [track mirror? video-ref]}]
  (let [internal-ref (react/useRef nil)
        active-ref   (or video-ref internal-ref)
        live-track   (rtc/resolve-media-track track)]
    (react/useEffect
     (fn []
       (when-let [el (.-current active-ref)]
         (if live-track
           (let [stream (js/MediaStream. #js [live-track])]
             (set! (.-srcObject el) stream)
             (-> (.play el) (p/catch (fn [_] nil))))
           (set! (.-srcObject el) nil)))
       (fn []
         (when-let [el (.-current active-ref)]
           (set! (.-srcObject el) nil))))
     #js [live-track])
    [:video.call-tile-video
     {:ref         active-ref
      :autoPlay    true
      :playsInline true
      :muted       true
      :style       (when mirror? {:transform "scaleX(-1)"})}]))

(defn participant-tile [{:keys [id display-name user-id participant-id avatar-url source local? audio-muted? video-muted? video-track audio-track] :as tile}
                        members-map
                        {:keys [spotlight? unwatched? on-select on-stop-watching on-watch]}]
  (let [tile-ref      (react/useRef nil)
        video-ref     (react/useRef nil)
        lookup-id     (or (not-empty user-id)
                          (clean-user-id participant-id))
        member        (when (and members-map lookup-id)
                        (get members-map lookup-id))
        resolved-name (or (not-empty (:display-name member))
                          (not-empty display-name)
                          (not-empty lookup-id)
                          (not-empty participant-id)
                          "Unknown")
        screen-share? (= source "screen-share")
        tile-label    (if screen-share?
                        (str resolved-name " (Screen)")
                        resolved-name)
        resolved-url  (or (not-empty (:avatar-url member))
                          (not-empty avatar-url))
        live-v-track  (rtc/resolve-media-track video-track)
        has-video?    (and live-v-track (not video-muted?))
        show-video?   (and has-video? (not unwatched?))
        pip-supported (boolean (.-pictureInPictureEnabled js/document))
        vol-key       (cc/participant-volume-key tile)
        stream-audio? (and screen-share? (boolean (rtc/resolve-media-track audio-track)))
        local-muted?  @(rf/subscribe [:call/participant-muted? vol-key])
        user-vol      @(rf/subscribe [:call/participant-volume vol-key])
        speaking-ids  @(rf/subscribe [:call/speaking-ids])
        is-speaking?  (and (not screen-share?)
                           (cc/participant-speaking? tile speaking-ids))]
    [:div.call-participant-tile
     {:ref      tile-ref
      :on-click (fn [e]
                  (when on-select
                    (.stopPropagation e)
                    (on-select id)))
      :class    [(when is-speaking? "is-speaking")
                 (when screen-share? "is-screen-share")
                 (when spotlight? "is-spotlight")
                 (when (and has-video? unwatched?) "is-unwatched")]}
     (cond
       show-video?
       [:f> media-video-element
        {:track     live-v-track
         :video-ref video-ref
         :mirror?   (and local? (= source "camera"))}]

       (and has-video? unwatched?)
       [:div.call-tile-placeholder.is-unwatched-stream
        [:div.call-tile-avatar
         [avatar {:id   (or lookup-id resolved-name)
                  :name resolved-name
                  :url  resolved-url
                  :size 64}]]
        [:button.call-watch-stream-btn
         {:on-click (fn [e]
                      (.stopPropagation e)
                      (when on-watch (on-watch id)))}
         (if screen-share? "Watch Stream" "Show Video")]]

       :else
       [:div.call-tile-placeholder
        [:div.call-tile-avatar
         [avatar {:id   (or lookup-id resolved-name)
                  :name resolved-name
                  :url  resolved-url
                  :size (if spotlight? 96 64)}]]])
     [:div.call-tile-actions
      {:on-click        #(.stopPropagation %)
       :on-pointer-down #(.stopPropagation %)}
      (when (not local?)
        [cc/volume-control-pill {:volume-key vol-key
                                 :stream?    stream-audio?}])
      (when (and on-select (not (and has-video? unwatched?)))
        [:button.call-tile-action-btn
         {:on-click #(on-select id)}
         (if spotlight? "Grid" "Focus")])
      (when (and show-video? pip-supported)
        [:button.call-tile-action-btn
         {:on-click #(cc/toggle-video-pip (.-current video-ref))}
         "Pop Out"])
      (when show-video?
        [:button.call-tile-action-btn
         {:on-click #(toggle-fullscreen (.-current tile-ref))}
         "Full"])
      (when (and show-video? (not local?) on-stop-watching)
        [:button.call-tile-action-btn.is-danger
         {:on-click #(on-stop-watching id)}
         "Stop Watching"])]
     [:div.call-tile-overlay
      (when is-speaking?
        [:span.call-tile-speaking-dot])
      [:span.call-tile-name tile-label]
      (when (and screen-share? show-video?)
        [:span.call-mini-live-badge "LIVE"])
      (when (or audio-muted? (and (not local?) (or local-muted? (zero? user-vol))))
        [:span.call-tile-muted-badge "Muted"])]]))

(defn screen-share-audio-picker []
  (let [[supported? set-supported!] (react/useState false)
        [open? set-open!]           (react/useState false)
        [apps set-apps!]            (react/useState [])
        [selected set-selected!]    (react/useState "NONE")
        wrapper-ref                 (react/useRef nil)]
    (react/useEffect
     (fn []
       (if-let [^js api (.-paradiseAudio js/window)]
         (-> (.isSupported api)
             (p/then (fn [ok?]
                       (set-supported! (boolean ok?))
                       (when ok?
                         (-> (.getTarget api)
                             (p/then #(when %
                                        (set-selected!
                                         (js->clj % :keywordize-keys true))))
                             (p/catch (fn [_] nil))))))
             (p/catch (fn [_] (set-supported! false))))
         (set-supported! false))
       js/undefined)
     #js [])
    (react/useEffect
     (fn []
       (if open?
         (let [on-doc-click (fn [e]
                              (when-let [el (.-current wrapper-ref)]
                                (when-not (.contains el (.-target e))
                                  (set-open! false))))]
           (.addEventListener js/document "mousedown" on-doc-click)
           (fn []
             (.removeEventListener js/document "mousedown" on-doc-click)))
         js/undefined))
     #js [open?])
    (when supported?
      (let [refresh-apps! (fn []
                            (when-let [^js api (.-paradiseAudio js/window)]
                              (-> (.listApps api)
                                  (p/then #(set-apps! (js->clj % :keywordize-keys true)))
                                  (p/catch (fn [_] (set-apps! []))))))
            choose!       (fn [target]
                            (set-selected! target)
                            (set-open! false)
                            (when-let [^js api (.-paradiseAudio js/window)]
                              (.setTarget api (clj->js target))))
            current-label (cond
                            (= selected "NONE") "Audio: Off"
                            (= selected "ALL")  "Audio: Desktop"
                            (map? selected)     (str "Audio: " (or (:label selected) "App"))
                            :else               "Stream Audio")]
        [:div.call-share-audio-picker
         {:ref wrapper-ref}
         [:button.call-control-btn.call-share-audio-btn
          {:class    [(when (not= selected "NONE") "is-active")
                      (when open? "is-open")]
           :title    current-label
           :on-click (fn [e]
                       (.stopPropagation e)
                       (let [next-open? (not open?)]
                         (when next-open?
                           (refresh-apps!))
                         (set-open! next-open?)))}
          [:span.call-share-audio-label current-label]
          [:span.call-share-audio-caret]]
         (when open?
           [:div.call-share-audio-menu
            {:on-click #(.stopPropagation %)}
            [:button.call-share-audio-item
             {:class    (when (= selected "NONE") "is-selected")
              :on-click #(choose! "NONE")}
             [:span.call-share-audio-item-text "Mute Stream Audio"]]
            [:button.call-share-audio-item
             {:class    (when (= selected "ALL") "is-selected")
              :on-click #(choose! "ALL")}
             [:span.call-share-audio-item-text "All Desktop Audio (No Echo)"]]
            (when (seq apps)
              [:div.call-share-audio-divider])
            (for [{:keys [label pid appNameLower] :as app-item} apps]
              ^{:key (str appNameLower "-" pid)}
              [:button.call-share-audio-item
               {:class    (when (and (map? selected)
                                     (= (:appNameLower selected) appNameLower)
                                     (= (:pid selected) pid))
                            "is-selected")
                :title    label
                :on-click #(choose! app-item)}
               [:span.call-share-audio-item-text label]])])]))))

(defn call-control-bar []
  (let [audio-enabled?  @(rf/subscribe [:call/audio-enabled?])
        video-enabled?  @(rf/subscribe [:call/video-enabled?])
        screen-sharing? @(rf/subscribe [:call/screen-sharing?])
        deafened?       @(rf/subscribe [:call/deafened?])]
    [:div.call-control-bar-wrapper
     [:div.call-control-bar
      [:button.call-control-btn
       {:class    (if audio-enabled? "is-active" "is-muted")
        :on-click #(rf/dispatch [:call/toggle-audio])}
       (if audio-enabled? "Mute" "Unmute")]
      [:button.call-control-btn
       {:class    (when video-enabled? "is-active")
        :on-click #(rf/dispatch [:call/toggle-video])}
       (if video-enabled? "Stop Video" "Start Video")]
      [:div.call-control-split-group
       [:button.call-control-btn
        {:class    (when screen-sharing? "is-active")
         :on-click #(rf/dispatch [:call/toggle-screen-share])}
        (if screen-sharing? "Stop Share" "Share Screen")]
       (when screen-sharing?
         [:f> screen-share-audio-picker])]
      [:button.call-control-btn
       {:class    (when deafened? "is-muted")
        :on-click #(rf/dispatch [:call/toggle-deafen])}
       (if deafened? "Undeafen" "Deafen")]
      [:button.call-control-btn.is-danger
       {:on-click #(rf/dispatch [:call/hangup])}
       "Leave"]]]))

(defn lobby-camera-preview [{:keys [active?]}]
  (let [video-ref (react/useRef nil)]
    (react/useEffect
     (fn []
       (let [!cancelled? (atom false)
             !stream     (atom nil)
             stop-stream (fn [s]
                           (when s
                             (doseq [t (.getTracks s)]
                               (.stop t))))]
         (if (and active?
                  (some-> js/navigator .-mediaDevices .-getUserMedia))
           (-> (.getUserMedia (.-mediaDevices js/navigator)
                              #js {:video true :audio false})
               (p/then (fn [stream]
                         (if @!cancelled?
                           (stop-stream stream)
                           (do
                             (reset! !stream stream)
                             (when-let [el (.-current video-ref)]
                               (set! (.-srcObject el) stream)
                               (-> (.play el) (p/catch (fn [_] nil))))))))
               (p/catch (fn [_]
                          (when-not @!cancelled?
                            (rf/dispatch [:call/media-state-changed :video false])))))
           (when-let [el (.-current video-ref)]
             (set! (.-srcObject el) nil)))
         (fn []
           (reset! !cancelled? true)
           (stop-stream @!stream)
           (reset! !stream nil)
           (when-let [el (.-current video-ref)]
             (set! (.-srcObject el) nil)))))
     #js [active?])
    [:video.call-tile-video
     {:ref         video-ref
      :autoPlay    true
      :playsInline true
      :muted       true
      :style       {:transform "scaleX(-1)"}}]))

(defn call-lobby [{:keys [room-id display-name room-avatar connecting? tr]}]
  (let [[starting? set-starting!] (react/useState false)
        raw-lobby                 @(rf/subscribe [:call/room-participants room-id])
        members-map               @(rf/subscribe [:room/members-map room-id])
        members-loading?          @(rf/subscribe [:room/members-loading? room-id])
        audio-enabled?            @(rf/subscribe [:call/audio-enabled?])
        video-enabled?            @(rf/subscribe [:call/video-enabled?])
        busy?                     (or connecting? starting?)]
    (react/useEffect
     (fn []
       (when-not connecting?
         (set-starting! false))
       js/undefined)
     #js [connecting? room-id])
    (react/useEffect
     (fn []
       (when (and room-id (empty? members-map) (not members-loading?))
         (rf/dispatch [:room/fetch-members room-id]))
       js/undefined)
     #js [room-id])
    (let [participants (->> raw-lobby
                            (map #(resolve-lobby-participant % members-map))
                            (reduce (fn [acc p]
                                      (if (some #(= (:id %) (:id p)) acc)
                                        acc
                                        (conj acc p)))
                                    []))
          has-active?  (seq participants)
          show-grid?   (or has-active? video-enabled?)
          idle-label   (or (when tr (tr [:call/idle]))
                           "No one is currently in the call")
          summary-text (if busy?
                         (or (when tr (tr [:container.call/negotiating-token]))
                             "Connecting to call...")
                         (format-call-participants
                          (mapv :display-name participants) idle-label))
          join-label   (cond
                         busy?       "Connecting..."
                         has-active? "Join Call"
                         :else       (or (when tr (tr [:container.call/start-join]))
                                         "Start Call"))]
      [:div.call-lobby-container
       [:div.call-lobby-stage
        (if show-grid?
          [:div.call-lobby-grid
           (for [{:keys [id display-name avatar-url speaking? audio-muted? screen-share?]} participants]
             ^{:key id}
             [:div.call-lobby-card
              {:class (when speaking? "is-speaking")}
              [:div.call-lobby-card-avatar
               [avatar {:id   id
                        :name display-name
                        :url  avatar-url
                        :size 72}]]
              [:div.call-tile-overlay
               [:span.call-tile-name display-name]
               (when screen-share?
                 [:span.call-mini-live-badge "LIVE"])
               (when audio-muted?
                 [:span.call-tile-muted-badge "Muted"])]])
           (when video-enabled?
             [:div.call-lobby-card.is-local-preview
              [:f> lobby-camera-preview
               {:active? (and video-enabled? (not busy?))}]
              [:div.call-tile-overlay
               [:span.call-tile-name "Camera Preview"]
               (when-not audio-enabled?
                 [:span.call-tile-muted-badge "Muted"])]])]
          [:div.call-lobby-empty-stage
           [:div.call-lobby-room-emblem
            [avatar {:id   room-id
                     :name display-name
                     :url  room-avatar
                     :size 84}]]])]
       [:div.call-lobby-panel
        [:div.call-lobby-header
         (when has-active?
           [:span.call-lobby-live-pill
            [:span.call-lobby-live-dot]
            (str (count participants) " in call")])
         [:h2.call-lobby-title display-name]
         [:div.call-lobby-subtitle summary-text]]
        [:div.call-lobby-actions
         [:button.call-control-btn
          {:class    (if audio-enabled? "is-active" "is-muted")
           :disabled busy?
           :on-click #(rf/dispatch [:call/toggle-audio])}
          (if audio-enabled? "Mic On" "Mic Muted")]
         [:button.call-control-btn
          {:class    (when video-enabled? "is-active")
           :disabled busy?
           :on-click #(rf/dispatch [:call/toggle-video])}
          (if video-enabled? "Camera On" "Camera Off")]
         [:button.call-start-button
          {:disabled busy?
           :on-click (fn [_]
                       (set-starting! true)
                       (rf/dispatch [:call/init-widget room-id])
                       (js/setTimeout #(rf/dispatch [:call/start room-id]) 500))}
          join-label]]]])))

(defn active-call-grid [room-id]
  (let [[participants set-participants!] (react/useState @rtc/!participants)
        unwatched                        @(rf/subscribe [:call/unwatched-ids])
        immersive?                       @(rf/subscribe [:call/immersive?])
        focused-id                       @(rf/subscribe [:call/focused-id])
        members-map                      @(rf/subscribe [:room/members-map room-id])]
    (react/useEffect
     (fn []
       (rf/dispatch [:call/set-stage-mounted true])
       (let [p-watch (keyword (gensym "grid-sync"))]
         (add-watch rtc/!participants p-watch
                    (fn [_ _ _ next-list]
                      (set-participants! next-list)))
         (set-participants! @rtc/!participants)
         (fn []
           (rf/dispatch [:call/set-stage-mounted false])
           (remove-watch rtc/!participants p-watch))))
     #js [])
    (let [screen-tile     (first (filter #(and (= (:source %) "screen-share")
                                               (not (contains? unwatched (:id %))))
                                         participants))
          spotlight-tile  (cond
                            (= focused-id :grid) nil
                            (some? focused-id)   (or (first (filter #(and (= (:id %) focused-id)
                                                                          (not (contains? unwatched (:id %))))
                                                                    participants))
                                                     screen-tile)
                            :else                screen-tile)
          filmstrip-tiles (if spotlight-tile
                            (remove #(= (:id %) (:id spotlight-tile)) participants)
                            participants)
          handle-stop     #(rf/dispatch [:call/stop-watching %])
          handle-watch    #(rf/dispatch [:call/watch-stream %])
          handle-focus    #(rf/dispatch [:call/set-focused-id %])]
      [:div.call-grid-container
       {:class [(when immersive? "is-immersive")
                (when spotlight-tile "has-spotlight")]}
       [:div.call-grid-layout-wrapper
        {:on-click #(rf/dispatch [:call/toggle-immersive])}
        (if spotlight-tile
          [:div.call-spotlight-layout
           [:div.call-spotlight-stage
            [:f> participant-tile
             spotlight-tile
             members-map
             {:spotlight?       true
              :unwatched?       false
              :on-select        (fn [_] (handle-focus :grid))
              :on-stop-watching handle-stop
              :on-watch         handle-watch}]]
           (when (seq filmstrip-tiles)
             [:div.call-filmstrip
              {:on-wheel (fn [e]
                           (let [el (.-currentTarget e)
                                 dy (.-deltaY e)
                                 dx (.-deltaX e)]
                             (when (and (> (.-scrollWidth el) (.-clientWidth el))
                                        (> (js/Math.abs dy) (js/Math.abs dx)))
                               (set! (.-scrollLeft el) (+ (.-scrollLeft el) dy)))))}
              (for [tile filmstrip-tiles]
                ^{:key (:id tile)}
                [:f> participant-tile
                 tile
                 members-map
                 {:spotlight?       false
                  :unwatched?       (contains? unwatched (:id tile))
                  :on-select        handle-focus
                  :on-stop-watching handle-stop
                  :on-watch         handle-watch}])])]

          [:div.call-grid-layout
           (for [tile participants]
             ^{:key (:id tile)}
             [:f> participant-tile
              tile
              members-map
              {:spotlight?       false
               :unwatched?       (contains? unwatched (:id tile))
               :on-select        handle-focus
               :on-stop-watching handle-stop
               :on-watch         handle-watch}])])]
       [call-control-bar]])))

(defn ^:ui call-view [room-id]
  (let [status       @(rf/subscribe [:call/status])
        room-meta    @(rf/subscribe [:rooms/active-metadata])
        tr           @(rf/subscribe [:i18n/tr])
        display-name (or (:name room-meta) room-id)
        room-avatar  (:avatar room-meta)
        connecting?  (or (= status :negotiating) (= status :connecting))]
    [:div.call-view-host
     [room-header {:display-name display-name :active-id room-id :compact? false}]
     [:div.call-video-area
      (if (= status :connected)
        [:f> active-call-grid room-id]
        [:f> call-lobby {:room-id      room-id
                         :display-name display-name
                         :room-avatar  room-avatar
                         :connecting?  connecting?
                         :tr           tr}])]]))
