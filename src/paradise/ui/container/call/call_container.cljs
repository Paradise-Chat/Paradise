(ns paradise.ui.container.call.call-container
  (:require
   [clojure.string :as str]
   [re-frame.core :as rf]
   ["react" :as react]
   [promesa.core :as p]
   [paradise.shared.plugins :as plugins]
   [paradise.ui.overlays.tray :refer [tray-section]]
   [paradise.shared.client.key-handler :refer [keybind-spec]]
   [paradise.ui.container.call.events]
   [paradise.ui.container.call.runtime :as rtc]))


(defn- clean-user-id [raw-id]
  (when (and raw-id (not (str/blank? (str raw-id))))
    (or (re-find #"^@[^:]+:[^:|]+" (str raw-id))
        (str raw-id))))

(defn- active-video? [p]
  (and (rtc/resolve-media-track (:video-track p))
       (not (:video-muted? p))))

(defn participant-volume-key [{:keys [id user-id participant-id source audio-track]}]
  (let [base-id (or (not-empty user-id)
                    (clean-user-id participant-id)
                    (clean-user-id id)
                    "unknown")
        kind    (if (and (= source "screen-share") (rtc/resolve-media-track audio-track))
                  "::stream"
                  "::user")]
    (str base-id kind)))

(defn participant-speaking? [p speaking-ids]
  (boolean
   (and (not (:audio-muted? p))
        (or (contains? speaking-ids (:id p))
            (:speaking? p)
            (:speaking p)
            (:isSpeaking p)
            (> (or (:audio-level p) (:audioLevel p) 0) 0.02)))))

(defn- compute-rms [^js buf]
  (let [len (.-length buf)]
    (if (pos? len)
      (loop [i 0
             sum 0.0]
        (if (< i len)
          (let [sample (/ (- (aget buf i) 128.0) 128.0)]
            (recur (inc i) (+ sum (* sample sample))))
          (js/Math.sqrt (/ sum len))))
      0.0)))

(defn voice-activity-monitor [{:keys [participants audio-enabled? deafened? volumes local-mutes]}]
  (let [ctx-ref    (react/useRef nil)
        nodes-ref  (react/useRef {})
        active-ref (react/useRef #{})
        state-ref  (react/useRef nil)]
    (set! (.-current state-ref)
          {:participants   participants
           :audio-enabled? audio-enabled?
           :deafened?      deafened?
           :volumes        volumes
           :local-mutes    local-mutes})
    (react/useEffect
     (fn []
       (let [AudioCtx (or js/window.AudioContext js/window.webkitAudioContext)]
         (when AudioCtx
           (let [ctx      (or (.-current ctx-ref)
                              (let [c (AudioCtx.)]
                                (set! (.-current ctx-ref) c)
                                c))
                 current  (.-current nodes-ref)
                 next-ids (set (map :id participants))
                 kept     (into {}
                                (keep (fn [[id entry]]
                                        (if (contains? next-ids id)
                                          [id entry]
                                          (do
                                            (try (.disconnect (:source entry)) (catch :default _ nil))
                                            nil))))
                                current)
                 updated  (reduce
                           (fn [acc {:keys [id audio-track]}]
                             (let [live-track (rtc/resolve-media-track audio-track)
                                   prev       (get acc id)]
                               (cond
                                 (nil? live-track)
                                 (do
                                   (when prev
                                     (try (.disconnect (:source prev)) (catch :default _ nil)))
                                   (dissoc acc id))

                                 (and prev (identical? (:track prev) live-track))
                                 acc

                                 :else
                                 (do
                                   (when prev
                                     (try (.disconnect (:source prev)) (catch :default _ nil)))
                                   (try
                                     (let [stream   (js/MediaStream. #js [live-track])
                                           source   (.createMediaStreamSource ctx stream)
                                           analyser (.createAnalyser ctx)
                                           buf      (js/Uint8Array. 128)]
                                       (set! (.-fftSize analyser) 256)
                                       (set! (.-smoothingTimeConstant analyser) 0.4)
                                       (.connect source analyser)
                                       (assoc acc id {:track       live-track
                                                      :source      source
                                                      :analyser    analyser
                                                      :buf         buf
                                                      :last-active 0}))
                                     (catch :default _
                                       (dissoc acc id)))))))
                           kept
                           participants)]
             (set! (.-current nodes-ref) updated))))
       js/undefined)
     #js [participants])
    (react/useEffect
     (fn []
       (let [interval-id
             (js/setInterval
              (fn []
                (when-let [^js ctx (.-current ctx-ref)]
                  (let [{:keys [participants audio-enabled? deafened? volumes local-mutes]}
                        (.-current state-ref)
                        nodes (.-current nodes-ref)
                        now   (js/performance.now)]
                    (when (and (seq nodes) (= (.-state ctx) "suspended"))
                      (-> (.resume ctx) (p/catch (fn [_] nil))))
                    (let [speaking-set
                          (reduce
                           (fn [acc {:keys [id local? audio-muted? source] :as p}]
                             (if (= source "screen-share")
                               acc
                               (let [entry   (get nodes id)
                                     vol-key (participant-volume-key p)
                                     vol     (get volumes vol-key 100)
                                     muted?  (or audio-muted?
                                                 (if local?
                                                   (not audio-enabled?)
                                                   (or deafened?
                                                       (contains? local-mutes vol-key)
                                                       (<= vol 0))))]
                                 (if (or muted? (nil? entry))
                                   acc
                                   (let [{:keys [^js track ^js analyser ^js buf last-active]} entry
                                         live? (and (.-enabled track)
                                                    (= (.-readyState track) "live"))
                                         rms   (if live?
                                                 (do
                                                   (.getByteTimeDomainData analyser buf)
                                                   (compute-rms buf))
                                                 0.0)
                                         next-active (if (> rms 0.018) now last-active)]
                                     (when (not= next-active last-active)
                                       (set! (.-current nodes-ref)
                                             (assoc-in (.-current nodes-ref) [id :last-active] next-active)))
                                     (if (< (- now next-active) 240)
                                       (conj acc id)
                                       acc))))))
                           #{}
                           participants)]
                      (when (not= speaking-set (.-current active-ref))
                        (set! (.-current active-ref) speaking-set)
                        (rf/dispatch [:call/set-speaking-ids speaking-set]))))))
              75)]
         (fn []
           (js/clearInterval interval-id)
           (doseq [[_ {:keys [source]}] (.-current nodes-ref)]
             (try (.disconnect source) (catch :default _ nil)))
           (set! (.-current nodes-ref) {})
           (when-let [^js ctx (.-current ctx-ref)]
             (set! (.-current ctx-ref) nil)
             (-> (.close ctx) (p/catch (fn [_] nil))))
           (rf/dispatch [:call/set-speaking-ids #{}]))))
     #js [])
    nil))

(defn volume-control-pill [{:keys [volume-key stream?]}]
  (let [volume       @(rf/subscribe [:call/participant-volume volume-key])
        local-muted? @(rf/subscribe [:call/participant-muted? volume-key])
        effective    (if local-muted? 0 volume)
        muted?       (or local-muted? (zero? volume))
        label        (cond
                       muted?  "Unmute"
                       stream? "Stream"
                       :else   "Mic")]
    [:div.call-volume-control
     {:on-click        #(.stopPropagation %)
      :on-pointer-down #(.stopPropagation %)}
     [:button.call-volume-mute-btn
      {:type     "button"
       :class    (when muted? "is-muted")
       :on-click (fn [e]
                   (.stopPropagation e)
                   (if (and (not local-muted?) (zero? volume))
                     (rf/dispatch [:call/set-volume volume-key 100])
                     (rf/dispatch [:call/toggle-local-mute volume-key])))}
      label]
     [:input.call-volume-slider
      {:type      "range"
       :min       0
       :max       100
       :step      1
       :value     effective
       :on-change #(rf/dispatch [:call/set-volume volume-key (.. % -target -value)])}]
     [:span.call-volume-value (str effective "%")]]))

(defn toggle-video-pip [video-el]
  (when (and video-el (.-pictureInPictureEnabled js/document))
    (if (= (.-pictureInPictureElement js/document) video-el)
      (-> (.exitPictureInPicture js/document) (p/catch (fn [_] nil)))
      (-> (.requestPictureInPicture video-el) (p/catch (fn [_] nil))))))

(defn remote-audio-track [{:keys [track volume muted? output-device-id]}]
  (let [audio-ref  (react/useRef nil)
        live-track (rtc/resolve-media-track track)
        norm-vol   (-> (/ (or volume 100) 100.0) (max 0.0) (min 1.0))
        eff-muted  (boolean (or muted? (<= (or volume 100) 0)))]
    (react/useEffect
     (fn []
       (when-let [el (.-current audio-ref)]
         (if live-track
           (let [stream (js/MediaStream. #js [live-track])]
             (set! (.-srcObject el) stream)
             (set! (.-volume el) norm-vol)
             (set! (.-muted el) eff-muted)
             (-> (.play el) (p/catch (fn [_] nil))))
           (set! (.-srcObject el) nil)))
       (fn []
         (when-let [el (.-current audio-ref)]
           (set! (.-srcObject el) nil))))
     #js [live-track])
    (react/useEffect
     (fn []
       (when-let [el (.-current audio-ref)]
         (set! (.-volume el) norm-vol)
         (set! (.-muted el) eff-muted))
       js/undefined)
     #js [norm-vol eff-muted])
    (react/useEffect
     (fn []
       (when-let [el (.-current audio-ref)]
         (when (fn? (.-setSinkId el))
           (-> (.setSinkId el (or output-device-id ""))
               (p/catch (fn [_] nil)))))
       js/undefined)
     #js [output-device-id])
    [:audio {:ref      audio-ref
             :autoPlay true
             :muted    eff-muted
             :style    {:display "none"}}]))

(defn mini-video-preview [{:keys [track mirror? video-ref]}]
  (let [live-track (rtc/resolve-media-track track)]
    (react/useEffect
     (fn []
       (when-let [el (.-current video-ref)]
         (if live-track
           (let [stream (js/MediaStream. #js [live-track])]
             (set! (.-srcObject el) stream)
             (-> (.play el) (p/catch (fn [_] nil))))
           (set! (.-srcObject el) nil)))
       (fn []
         (when-let [el (.-current video-ref)]
           (set! (.-srcObject el) nil))))
     #js [live-track])
    [:video.call-mini-video
     {:ref         video-ref
      :autoPlay    true
      :playsInline true
      :muted       true
      :style       (when mirror? {:transform "scaleX(-1)"})}]))

(defn persistent-mini-player []
  (let [[participants set-participants!] (react/useState @rtc/!participants)
        [drag-state set-drag-state!]     (react/useState nil)
        player-ref                       (react/useRef nil)
        video-ref                        (react/useRef nil)
        unwatched                        @(rf/subscribe [:call/unwatched-ids])
        speaking-ids                     @(rf/subscribe [:call/speaking-ids])
        corner                           @(rf/subscribe [:call/pip-corner])
        tucked?                          @(rf/subscribe [:call/pip-tucked?])
        active-call-id                   @(rf/subscribe [:call/active-room])
        rooms-map                        @(rf/subscribe [:rooms/unfiltered-indexed-map])
        room-obj                         (when active-call-id (get rooms-map active-call-id))
        room-name                        (when room-obj (aget room-obj "name"))]
    (react/useEffect
     (fn []
       (let [p-watch (keyword (gensym "mini-player-sync"))]
         (add-watch rtc/!participants p-watch
                    (fn [_ _ _ next-list]
                      (set-participants! next-list)))
         (set-participants! @rtc/!participants)
         (fn []
           (remove-watch rtc/!participants p-watch))))
     #js [])
    (let [watched-tiles (filter #(and (active-video? %)
                                      (not (:local? %))
                                      (not (contains? unwatched (:id %))))
                                participants)
          featured-tile (or (first (filter #(= (:source %) "screen-share") watched-tiles))
                            (first (filter #(participant-speaking? % speaking-ids) watched-tiles))
                            (first watched-tiles))]
      (when featured-tile
        (let [screen-share? (= (:source featured-tile) "screen-share")
              speaking?     (participant-speaking? featured-tile speaking-ids)
              vol-key       (participant-volume-key featured-tile)
              stream-audio? (and screen-share? (boolean (rtc/resolve-media-track (:audio-track featured-tile))))
              label         (or (not-empty (:display-name featured-tile))
                                (not-empty (:user-id featured-tile))
                                (clean-user-id (:participant-id featured-tile))
                                (not-empty room-name)
                                "Active Call")
              pip-supported (boolean (.-pictureInPictureEnabled js/document))
              handle-width  28
              slot-props    {:tile          featured-tile
                             :tile-ref      player-ref
                             :video-ref     video-ref
                             :screen-share? screen-share?
                             :show-video?   true
                             :spotlight?    false
                             :tucked?       tucked?
                             :vol-key       vol-key}]
          [:div.call-mini-player
           {:ref   player-ref
            :class [(str "snap-" corner)
                    (when tucked? "is-tucked")
                    (when (:active? drag-state) "is-dragging")
                    (when speaking? "is-speaking")
                    (when screen-share? "is-screen-share")]
            :style (when (:active? drag-state)
                     {:left (str (:left drag-state) "px")
                      :top  (str (:top drag-state) "px")})
            :on-pointer-down
            (fn [e]
              (when (and (= (.-button e) 0)
                         (nil? (.closest (.-target e) "button, input, .call-volume-control")))
                (when-let [el (.-current player-ref)]
                  (let [rect (.getBoundingClientRect el)]
                    (.setPointerCapture el (.-pointerId e))
                    (set-drag-state!
                     {:active?    true
                      :moved?     false
                      :pointer-id (.-pointerId e)
                      :start-x    (.-clientX e)
                      :start-y    (.-clientY e)
                      :orig-left  (.-left rect)
                      :orig-top   (.-top rect)
                      :width      (.-width rect)
                      :height     (.-height rect)
                      :left       (.-left rect)
                      :top        (.-top rect)})))))
            :on-pointer-move
            (fn [e]
              (when (and (:active? drag-state)
                         (= (.-pointerId e) (:pointer-id drag-state)))
                (let [dx        (- (.-clientX e) (:start-x drag-state))
                      dy        (- (.-clientY e) (:start-y drag-state))
                      moved?    (or (:moved? drag-state)
                                    (> (js/Math.hypot dx dy) 6))
                      vw        (.-innerWidth js/window)
                      vh        (.-innerHeight js/window)
                      w         (:width drag-state)
                      h         (:height drag-state)
                      min-left  (- handle-width w)
                      max-left  (- vw handle-width)
                      max-top   (max 8 (- vh h 8))
                      next-left (-> (+ (:orig-left drag-state) dx)
                                    (max min-left)
                                    (min max-left))
                      next-top  (-> (+ (:orig-top drag-state) dy)
                                    (max 8)
                                    (min max-top))]
                  (set-drag-state!
                   (assoc drag-state
                          :moved? moved?
                          :left   next-left
                          :top    next-top)))))
            :on-pointer-up
            (fn [e]
              (when (and (:active? drag-state)
                         (= (.-pointerId e) (:pointer-id drag-state)))
                (when-let [el (.-current player-ref)]
                  (when (.hasPointerCapture el (.-pointerId e))
                    (.releasePointerCapture el (.-pointerId e)))
                  (if (not (:moved? drag-state))
                    (do
                      (when tucked?
                        (rf/dispatch [:call/set-pip-tucked false]))
                      (set-drag-state! nil))
                    (let [rect        (.getBoundingClientRect el)
                          vw          (.-innerWidth js/window)
                          vh          (.-innerHeight js/window)
                          w           (.-width rect)
                          left        (.-left rect)
                          right       (.-right rect)
                          cx          (+ left (/ w 2))
                          cy          (+ (.-top rect) (/ (.-height rect) 2))
                          h-pos       (if (< cx (/ vw 2)) "left" "right")
                          v-pos       (if (< cy (/ vh 2)) "top" "bottom")
                          next-corner (str v-pos "-" h-pos)
                          drag-dx     (- (:left drag-state) (:orig-left drag-state))
                          tuck-thresh (* 0.22 w)
                          should-tuck (if tucked?
                                        (if (str/ends-with? corner "left")
                                          (<= drag-dx 24)
                                          (>= drag-dx -24))
                                        (or (< left (- tuck-thresh))
                                            (> right (+ vw tuck-thresh))))]
                      (rf/dispatch [:call/set-pip-corner next-corner])
                      (rf/dispatch [:call/set-pip-tucked should-tuck])
                      (set-drag-state! nil))))))
            :on-pointer-cancel
            (fn [_]
              (set-drag-state! nil))}
           [:f> mini-video-preview
            {:track     (:video-track featured-tile)
             :mirror?   (and (:local? featured-tile) (= (:source featured-tile) "camera"))
             :video-ref video-ref}]
           [plugins/plugin-slot :call/mini-player-overlay slot-props]
           [:div.call-mini-edge-handle
            [:span.call-mini-edge-pill]]
           [:div.call-mini-top-bar
            [volume-control-pill {:volume-key vol-key
                                  :stream?    stream-audio?}]
            (when pip-supported
              [:button.call-tile-action-btn
               {:on-click (fn [e]
                            (.stopPropagation e)
                            (toggle-video-pip (.-current video-ref)))}
               "Pop Out"])
            [plugins/plugin-slot :call/mini-player-actions slot-props]
            [:button.call-tile-action-btn.is-danger
              {:on-click (fn [e]
                           (.stopPropagation e)
                           (rf/dispatch [:call/stop-watching (mapv :id watched-tiles)]))}
              "Stop Watching"]]
           [:button.call-mini-bottom-bar
            {:type     "button"
             :title    (if room-name (str "Jump to " room-name) "Jump to call")
             :on-click (fn [e]
                         (.stopPropagation e)
                         (when active-call-id
                           (rf/dispatch [:rooms/select active-call-id])))}
            [:span.call-mini-name label]
            (when screen-share?
              [:span.call-mini-live-badge "LIVE"])]])))))

(defn persistent-audio-renderer [{:keys [deafened?]}]
  (let [[participants set-participants!] (react/useState @rtc/!participants)
        stage-mounted?                   @(rf/subscribe [:call/stage-mounted?])
        audio-enabled?                   @(rf/subscribe [:call/audio-enabled?])
        output-device-id                 @(rf/subscribe [:settings/call-device :audiooutput])
        volumes                          @(rf/subscribe [:call/volumes])
        local-mutes                      @(rf/subscribe [:call/local-mutes])]
    (react/useEffect
     (fn []
       (let [p-watch (keyword (gensym "audio-sync"))]
         (add-watch rtc/!participants p-watch
                    (fn [_ _ _ next-list]
                      (set-participants! next-list)))
         (set-participants! @rtc/!participants)
         (fn []
           (remove-watch rtc/!participants p-watch))))
     #js [])
    [:<>
     [:f> voice-activity-monitor
      {:participants   participants
       :audio-enabled? audio-enabled?
       :deafened?      deafened?
       :volumes        volumes
       :local-mutes    local-mutes}]
     [:div.call-audio-sink {:style {:display "none"}}
      (doall
       (for [{:keys [id local? audio-track] :as p} participants
             :let [live-track (rtc/resolve-media-track audio-track)]
             :when (and (not local?) live-track)
             :let [vol-key (participant-volume-key p)
                   vol     (get volumes vol-key 100)
                   muted?  (or deafened? (contains? local-mutes vol-key))]]
         ^{:key (str id "-audio")}
         [:f> remote-audio-track {:track            live-track
                                  :volume           vol
                                  :muted?           muted?
                                  :output-device-id output-device-id}]))]
     [plugins/plugin-slot
      :call/background-services
      {:participants     participants
       :stage-mounted?   stage-mounted?
       :audio-enabled?   audio-enabled?
       :deafened?        deafened?
       :output-device-id output-device-id
       :volumes          volumes
       :local-mutes      local-mutes}]
     (when (not stage-mounted?)
       [:f> persistent-mini-player])]))

(defn persistent-call-container [children]
  (let [is-active? @(rf/subscribe [:call/is-active?])
        deafened?  @(rf/subscribe [:call/deafened?])]
    [:div.persistent-call-host
     {:style {:width "100%" :height "100%" :position "relative"}}
     (when is-active?
       [:f> persistent-audio-renderer {:deafened? deafened?}])
     children]))

(defmethod tray-section :call [_ db]
  (let [call-state      (:call db)
        connected?      (= (:status call-state) :connected)
        audio-enabled?  (:audio-enabled? call-state true)
        deafened?       (:deafened? call-state false)
        screen-sharing? (:screen-sharing? call-state false)]
    (when connected?
      {:order   10
       :tooltip "Paradise - In Call"
       :items   [{:label   "Active Voice Call"
                  :enabled false}
                 {:id      "call/toggle-audio"
                  :label   (if audio-enabled?
                             "Mute Microphone"
                             "Unmute Microphone")}
                 {:id      "call/toggle-deafen"
                  :label   (if deafened?
                             "Undeafen Audio"
                             "Deafen Audio")}
                 {:id      "call/toggle-share"
                  :label   (if screen-sharing?
                             "Stop Screen Share"
                             "Share Screen")}
                 {:type    "separator"}
                 {:id      "call/hangup"
                  :label   "Disconnect Call"}]})))

(defmethod keybind-spec :call/toggle-audio [_]
  {:id              "call/toggle-audio"
   :label-key       :settings.keybinds/action-toggle-mute
   :accelerator     "CommandOrControl+Shift+M"
   :global?         true
   :allow-in-input? true
   :dispatch        [:call/toggle-audio]})

(defmethod keybind-spec :call/toggle-deafen [_]
  {:id              "call/toggle-deafen"
   :label-key       :settings.keybinds/action-toggle-deafen
   :accelerator     "CommandOrControl+Shift+D"
   :global?         true
   :allow-in-input? true
   :dispatch        [:call/toggle-deafen]})

(defmethod keybind-spec :call/toggle-share [_]
  {:id              "call/toggle-share"
   :label-key       :settings.keybinds/action-toggle-share
   :accelerator     "CommandOrControl+Shift+S"
   :global?         true
   :allow-in-input? true
   :dispatch        [:call/toggle-screen-share]})

(defmethod keybind-spec :call/hangup [_]
  {:id              "call/hangup"
   :label-key       :settings.keybinds/action-hangup
   :accelerator     "CommandOrControl+Shift+H"
   :global?         true
   :allow-in-input? true
   :dispatch        [:call/hangup]})
