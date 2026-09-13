(ns paradise.ui.overlays.settings.voice-video
  (:require
   ["livekit-client" :as lkc]
   [paradise.shared.client.session-store :as store]
   [paradise.ui.container.call.runtime :as call-rtc]
   [promesa.core :as p]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [taoensso.timbre :as log]))

(def call-device-storage-keys
  {:audioinput  "call_audio_input"
   :videoinput  "call_video_input"
   :audiooutput "call_audio_output"
   })

(re-frame/reg-event-db
 :settings/hydrate-audio-input
 (fn [db [_ device-id]]
   (assoc-in db [:settings :call-devices :audioinput] device-id)))

(re-frame/reg-event-db
 :settings/hydrate-video-input
 (fn [db [_ device-id]]
   (assoc-in db [:settings :call-devices :videoinput] device-id)))

(re-frame/reg-event-db
 :settings/hydrate-audio-output
 (fn [db [_ device-id]]
   (assoc-in db [:settings :call-devices :audiooutput] device-id)))


(re-frame/reg-event-db
 :settings/hydrate-noise-suppression
 (fn [db [_ enabled?]]
   (call-rtc/set-noise-suppression-enabled! enabled?)

   (assoc-in
    db
    [:settings :noise-suppression-enabled?]
    enabled?)))

(re-frame/reg-event-fx
 :settings/hydrate-voice-threshold
 (fn [{:keys [db]} [_ threshold]]
   (let [threshold
         (if (and (number? threshold)
                  (<= 0 threshold 1))
           threshold
           0.12)]

     (call-rtc/set-voice-threshold!
      threshold)

     {:db
      (assoc-in
       db
       [:settings :voice-threshold]
       threshold)})))



(re-frame/reg-event-fx
 :settings/set-noise-suppression
 (fn [{:keys [db]} [_ enabled?]]
   (let [enabled? (boolean enabled?)]

     (store/set-setting!
      "call_noise_suppression"
      enabled?)

     (call-rtc/set-noise-suppression-enabled!
      enabled?)

     {:db
      (assoc-in
       db
       [:settings :noise-suppression-enabled?]
       enabled?)})))

(re-frame/reg-sub
 :settings/noise-suppression-enabled?
 (fn [db _]
   (get-in
    db
    [:settings :noise-suppression-enabled?]
    true)))




(re-frame/reg-event-fx
 :settings/set-voice-threshold
 (fn [{:keys [db]} [_ threshold]]
   (let [threshold
         (max 0.0
              (min 1.0 threshold))]

     (store/set-setting!
      "call_voice_threshold"
      threshold)

     (call-rtc/set-voice-threshold!
      threshold)

     {:db
      (assoc-in
       db
       [:settings :voice-threshold]
       threshold)})))


(re-frame/reg-sub
 :settings/voice-threshold
 (fn [db _]
   (get-in
    db
    [:settings :voice-threshold]
    0.12)))


(re-frame/reg-event-fx
 :settings/set-call-device
 (fn [{:keys [db]} [_ kind device-id]]
   (let [storage-key (get call-device-storage-keys kind)
         final-id    (when (seq device-id) device-id)]
     (when storage-key
       (store/set-setting! storage-key final-id))

     (when (and final-id (call-rtc/current-room))
       (-> (call-rtc/switch-device! (name kind) final-id)
           (p/catch
            (fn [err]
              (log/warn err "Failed to switch active call device" kind final-id)))))

     {:db (assoc-in db [:settings :call-devices kind] final-id)})))

(re-frame/reg-event-fx
 :settings/apply-call-devices
 (fn [{:keys [db]} _]
   (when (call-rtc/current-room)
     (doseq [[kind device-id] (get-in db [:settings :call-devices])
             :when (seq device-id)]
       (-> (call-rtc/switch-device! (name kind) device-id)
           (p/catch
            (fn [err]
              (log/warn err "Failed to apply preferred call device" kind device-id))))))
   {}))

(re-frame/reg-sub
 :settings/call-device
 (fn [db [_ kind]]
   (get-in db [:settings :call-devices kind])))



(defn load-call-devices!
  [!devices !error]
  (-> (p/let [audio-inputs  (.getLocalDevices (.-Room lkc) "audioinput" false)
              video-inputs  (.getLocalDevices (.-Room lkc) "videoinput" false)
              audio-outputs (.getLocalDevices (.-Room lkc) "audiooutput" false)]
        (reset! !devices
                {:audioinput  (vec (array-seq audio-inputs))
                 :videoinput  (vec (array-seq video-inputs))
                 :audiooutput (vec (array-seq audio-outputs))})
        (reset! !error nil))
      (p/catch
       (fn [err]
         (log/warn err "Failed to enumerate media devices")
         (reset! !error err)))))

(defn- live-microphone-enabled?
  []
  (if-let [publication
           (call-rtc/current-microphone-publication)]
    (not (.-isMuted publication))
    false))


(defn- restore-live-microphone!
  [!restore-live-mic?]
  (let [restore? @!restore-live-mic?]
    (reset! !restore-live-mic? false)

    (if restore?
      (-> (call-rtc/set-microphone-enabled! true)
          (p/catch
           (fn [err]
             (log/warn
              err
              "Failed to restore live microphone after mic test")
             nil)))
      (p/resolved nil))))


(defn stop-mic-test!
  [!track
   !audio-el
   !processor
   !audio-context
   !raf-id
   !level
   !testing?
   !restore-live-mic?
   restore-live?]

  (reset! !testing? false)

  (when-let [raf-id @!raf-id]
    (js/cancelAnimationFrame raf-id)
    (reset! !raf-id nil))

  (when-let [audio-el @!audio-el]
    (try
      (.pause audio-el)
      (set! (.-srcObject audio-el) nil)

      (when-let [parent (.-parentNode audio-el)]
        (.removeChild parent audio-el))

      (catch :default err
        (log/debug
         err
         "Failed to remove mic monitor element")))

    (reset! !audio-el nil))

  (let [track @!track
        audio-context @!audio-context]

    (reset! !track nil)
    (reset! !processor nil)
    (reset! !audio-context nil)

    (-> (p/let [_
                (if track
                  (-> (.stopProcessor track false)
                      (p/catch
                       (fn [err]
                         (log/debug
                          err
                          "Failed to stop mic test processor"))))
                  (p/resolved nil))

                _
                (do
                  (when track
                    (try
                      (.detach track)
                      (.stop track)

                      (catch :default err
                        (log/debug
                         err
                         "Failed to stop mic test track")))))

                _
                (if (and audio-context
                         (not= "closed"
                               (.-state audio-context)))
                  (-> (.close audio-context)
                      (p/catch
                       (fn [err]
                         (log/debug
                          err
                          "Failed to close mic test AudioContext"))))
                  (p/resolved nil))

                _
                (do
                  (reset! !level 0)
                  nil)

                _
                (if restore-live?
                  (restore-live-microphone!
                   !restore-live-mic?)
                  (p/resolved nil))]

          nil)

        (p/catch
         (fn [err]
           (log/debug
            err
            "Mic test cleanup failed")

           (reset! !level 0)

           (if restore-live?
             (restore-live-microphone!
              !restore-live-mic?)
             (p/resolved nil)))))))


(defn start-mic-test!
  [device-id
   !track
   !audio-el
   !processor
   !audio-context
   !raf-id
   !level
   !testing?
   !threshold
   !noise-suppression
   !restore-live-mic?
   !error
   !devices]

  (let [restarting? @!testing?
        live-mic-was-enabled?
        (and (not restarting?)
             (live-microphone-enabled?))]

    (-> (p/let [_
                (stop-mic-test!
                 !track
                 !audio-el
                 !processor
                 !audio-context
                 !raf-id
                 !level
                 !testing?
                 !restore-live-mic?
                 false)

                _
                (do
                  (reset! !error nil)

                  (when-not restarting?
                    (reset! !restore-live-mic?
                            (boolean live-mic-was-enabled?))))

                _
                (if live-mic-was-enabled?
                  (call-rtc/set-microphone-enabled! false)
                  (p/resolved nil))

                processor
                (call-rtc/create-test-audio-processor)

                audio-context
                (js/AudioContext.)

                opts
                (clj->js
                 (cond->
                  {:echoCancellation true

                   :noiseSuppression false

                   :autoGainControl true
                   :channelCount 1
                   :sampleRate 48000}

                  (seq device-id)
                  (assoc :deviceId device-id)))

                _
                (do
                  (reset! !processor processor)
                  (reset! !audio-context audio-context)

                  (when-let [set-threshold
                             (aget processor "setThreshold")]
                    (set-threshold
                     @!threshold))

                  (when-let [set-suppression
                             (aget processor
                                   "setNoiseSuppressionEnabled")]
                    (set-suppression
                     @!noise-suppression)))

                _
                (if (= "suspended"
                       (.-state audio-context))
                  (.resume audio-context)
                  (p/resolved nil))

                track
                ((.-createLocalAudioTrack lkc)
                 opts)

                _
                (do
                  (reset! !track track)

                  (.setAudioContext
                   track
                   audio-context))

                _
                (.setProcessor
                 track
                 processor)]

          (reset! !testing? true)

          (let [audio-el (.attach track)]

            (reset! !audio-el audio-el)

            (set! (.-autoplay audio-el) true)
            (set! (.-muted audio-el) false)
            (set! (.-volume audio-el) 1)
            (set! (.. audio-el -style -display) "none")

            (.appendChild
             (.-body js/document)
             audio-el)

            (-> (.play audio-el)
                (p/catch
                 (fn [err]
                   (log/warn
                    err
                    "Mic test playback failed"))))

            (letfn [(tick []
                      (when @!testing?
                        (when-let [set-threshold
                                   (aget processor "setThreshold")]
                          (set-threshold
                           @!threshold))

                        (when-let [set-suppression
                                   (aget processor
                                         "setNoiseSuppressionEnabled")]
                          (set-suppression
                           @!noise-suppression))

                        (when-let [get-level
                                   (aget processor "getLevel")]
                          (reset! !level
                                  (get-level)))

                        (reset! !raf-id
                                (js/requestAnimationFrame
                                 tick))))]

              (tick))

            (load-call-devices!
             !devices
             !error)))

        (p/catch
         (fn [err]
           (log/error
            err
            "Failed to start processed microphone test")

           (reset! !error err)

           (stop-mic-test!
            !track
            !audio-el
            !processor
            !audio-context
            !raf-id
            !level
            !testing?
            !restore-live-mic?
            true))))))


(defn stop-camera-preview!
  [!track !previewing?]
  (reset! !previewing? false)
  (when-let [track @!track]
    (try
      (.detach track)
      (.stop track)
      (catch :default err
        (log/debug err "Camera preview track cleanup failed"))))
  (reset! !track nil))


(defn start-camera-preview!
  [device-id !track !video-el !previewing? !error !devices]
  (stop-camera-preview! !track !previewing?)
  (reset! !error nil)

  (let [opts (if (seq device-id)
               #js {:deviceId device-id
                    :resolution (.-hd720 (.-VideoPresets lkc))}
               #js {:resolution (.-hd720 (.-VideoPresets lkc))})]
    (-> (p/let [track ((.-createLocalVideoTrack lkc) opts)]
          (reset! !track track)
          (reset! !previewing? true)

          (when-let [video-el @!video-el]
            (.attach track video-el))

          (load-call-devices! !devices !error))
        (p/catch
         (fn [err]
           (log/warn err "Failed to start camera preview")
           (reset! !error err)
           (stop-camera-preview! !track !previewing?))))))

(defn media-device-label
  [device fallback index]
  (let [label (.-label device)]
    (if (seq label)
      label
      (str fallback " " (inc index)))))

(defn media-device-select
  [{:keys [label kind devices selected on-change fallback-label default-label]}]
  [:div.settings-field
   [:label.settings-label label]
   [:select.settings-input
    {:value (or selected "")
     :on-change #(on-change (.. % -target -value))}
    [:option {:value ""} default-label]
    (doall
     (map-indexed
      (fn [idx device]
        ^{:key (str (name kind) "-" (.-deviceId device))}
        [:option {:value (.-deviceId device)}
         (media-device-label device fallback-label idx)])
      devices))]])


(defn- clamp01 [x]
  (max 0.0
       (min 1.0 x)))


(defn- amplitude->meter
  [amplitude]
  (let [db (* 20
              (js/Math.log10
               (max amplitude 0.00001)))
        min-db -60
        max-db -6]
    (clamp01
     (/ (- db min-db)
        (- max-db min-db)))))


(defn- meter->amplitude
  [position]
  (let [position (clamp01 position)
        min-db -60
        max-db -6
        db (+ min-db
              (* position
                 (- max-db min-db)))]
    (js/Math.pow
     10
     (/ db 20))))


(defn- mic-level-meter
  [tr level testing? threshold on-threshold-change]
  (let [segments 24
        meter-level (amplitude->meter level)
        meter-threshold (amplitude->meter threshold)
        active (int
                (js/Math.round
                 (* segments meter-level)))
        threshold-pct (* 100 meter-threshold)
        transmitting? (and testing?
                           (>= level threshold))]
    [:div.settings-meter
     [:div.settings-meter-bars
      (for [idx (range segments)]
        (let [active? (and testing?
                           (< idx active))]
          ^{:key idx}
          [:div.settings-meter-segment
           {:class (cond
                     (not active?) nil
                     transmitting? "is-detected"
                     :else "is-below-threshold")
            :style {:height (str
                             (+ 5
                                (* 0.45
                                   (min idx
                                        (- segments idx))))
                             "px")}}]))

      [:div.settings-meter-threshold
       {:style {:left (str threshold-pct "%")}}]

      [:input.settings-meter-input
       {:type "range"
        :min 0
        :max 1
        :step 0.005
        :value meter-threshold
        :aria-label (tr [:settings.voice-video.microphone/threshold-aria])
        :on-change
        (fn [e]
          (on-threshold-change
           (meter->amplitude
            (js/parseFloat
             (.. e -target -value)))))}]]

     [:div.settings-meter-meta
      [:span
       (cond
         transmitting?
         (tr [:settings.voice-video.microphone/voice-detected])

         testing?
         (tr [:settings.voice-video.microphone/below-threshold])

         :else
         (tr [:settings.voice-video.microphone/activation-threshold]))]

      [:span
       (str
        (js/Math.round
         (* meter-threshold 100))
        "%")]]]))


(defn- microphone-section
  [{:keys [tr
           devices
           audio-input
           voice-threshold
           noise-suppression-enabled?
           !mic-track
           !mic-audio-el
           !mic-processor
           !mic-audio-context
           !mic-raf-id
           !mic-level
           !mic-testing?
           !voice-threshold
           !noise-suppression
           !restore-live-mic?
           !error
           !devices]}]
  [:div.settings-section
   [:h3.settings-subheading
    (tr [:settings.voice-video.microphone/title])]

   [:p.settings-description
    (tr [:settings.voice-video.microphone/description])]

   [media-device-select
    {:label (tr [:settings.voice-video.devices/input-label])
     :kind :audioinput
     :devices (:audioinput devices)
     :selected audio-input
     :fallback-label (tr [:settings.voice-video.devices/microphone])
     :default-label (tr [:settings.voice-video.devices/default])
     :on-change
     (fn [device-id]
       (re-frame/dispatch
        [:settings/set-call-device
         :audioinput
         device-id])

       (when @!mic-testing?
         (start-mic-test!
          device-id
          !mic-track
          !mic-audio-el
          !mic-processor
          !mic-audio-context
          !mic-raf-id
          !mic-level
          !mic-testing?
          !voice-threshold
          !noise-suppression
          !restore-live-mic?
          !error
          !devices)))}]

   [:div.settings-row
    [:div.settings-row-text
     [:div.settings-row-title
      (tr [:settings.voice-video.microphone/sensitivity-title])]
     [:div.settings-row-description
      (tr [:settings.voice-video.microphone/sensitivity-description])]]

    [:button.settings-button
     {:type "button"
      :on-click
      (fn [_]
        (if @!mic-testing?
          (stop-mic-test!
           !mic-track
           !mic-audio-el
           !mic-processor
           !mic-audio-context
           !mic-raf-id
           !mic-level
           !mic-testing?
           !restore-live-mic?
           true)

          (start-mic-test!
           audio-input
           !mic-track
           !mic-audio-el
           !mic-processor
           !mic-audio-context
           !mic-raf-id
           !mic-level
           !mic-testing?
           !voice-threshold
           !noise-suppression
           !restore-live-mic?
           !error
           !devices)))}
     (if @!mic-testing?
       (tr [:settings.voice-video.microphone/stop-test])
       (tr [:settings.voice-video.microphone/test]))]]

   [mic-level-meter
    tr
    @!mic-level
    @!mic-testing?
    voice-threshold
    (fn [threshold]
      (reset! !voice-threshold threshold)
      (re-frame/dispatch
       [:settings/set-voice-threshold
        threshold]))]

   [:div.settings-row.toggle-row
    [:div.settings-row-text
     [:div.settings-row-title
      (tr [:settings.voice-video.microphone/noise-suppression-title])]
     [:div.settings-row-description
      (tr [:settings.voice-video.microphone/noise-suppression-description])]]

    [:label.settings-toggle
     [:input
      {:type "checkbox"
       :checked noise-suppression-enabled?
       :on-change
       (fn [e]
         (let [enabled? (.. e -target -checked)]
           (reset! !noise-suppression enabled?)
           (re-frame/dispatch
            [:settings/set-noise-suppression
             enabled?])))}]
     [:div.settings-toggle-track
      [:div.settings-toggle-knob]]]]])


(defn- camera-section
  [{:keys [tr
           devices
           video-input
           !camera-track
           !video-el
           !previewing?
           !error
           !devices]}]
  [:div.settings-section
   [:h3.settings-subheading
    (tr [:settings.voice-video.camera/title])]

   [:p.settings-description
    (tr [:settings.voice-video.camera/description])]

   [media-device-select
    {:label (tr [:settings.voice-video.devices/video-label])
     :kind :videoinput
     :devices (:videoinput devices)
     :selected video-input
     :fallback-label (tr [:settings.voice-video.devices/camera])
     :default-label (tr [:settings.voice-video.devices/default])
     :on-change
     (fn [device-id]
       (re-frame/dispatch
        [:settings/set-call-device
         :videoinput
         device-id])

       (when @!previewing?
         (start-camera-preview!
          device-id
          !camera-track
          !video-el
          !previewing?
          !error
          !devices)))}]

   [:div.settings-actions
    [:button.settings-button
     {:type "button"
      :on-click
      (fn [_]
        (if @!previewing?
          (stop-camera-preview!
           !camera-track
           !previewing?)

          (start-camera-preview!
           video-input
           !camera-track
           !video-el
           !previewing?
           !error
           !devices)))}
     (if @!previewing?
       (tr [:settings.voice-video.camera/stop-preview])
       (tr [:settings.voice-video.camera/preview]))]]

   [:div.settings-media-preview
    [:video.settings-media-preview-video
     {:class (when @!previewing?
               "is-active")
      :ref #(reset! !video-el %)
      :auto-play true
      :plays-inline true
      :muted true}]

    (when-not @!previewing?
      [:div.settings-media-preview-empty
       (tr [:settings.voice-video.camera/preview-off])])]])


(defn- output-section
  [{:keys [tr
           devices
           audio-output]}]
  [:div.settings-section
   [:h3.settings-subheading
    (tr [:settings.voice-video.output/title])]

   [:p.settings-description
    (tr [:settings.voice-video.output/description])]

   [media-device-select
    {:label (tr [:settings.voice-video.devices/output-label])
     :kind :audiooutput
     :devices (:audiooutput devices)
     :selected audio-output
     :fallback-label (tr [:settings.voice-video.devices/output])
     :default-label (tr [:settings.voice-video.devices/default])
     :on-change
     #(re-frame/dispatch
       [:settings/set-call-device
        :audiooutput
        %])}]

   [:p.settings-description
    (tr [:settings.voice-video.output/browser-support])]])


(defn- media-error-banner
  [tr error]
  (when error
    [:div.settings-banner.warning
     [:div.settings-banner-title
      (tr [:settings.voice-video.error/title])]
     [:div.settings-banner-description
      (or (.-message error)
          (str error))]]))


(defn ^:ui voice-video-tab []
  (r/with-let [!devices
               (r/atom
                {:audioinput []
                 :videoinput []
                 :audiooutput []})

               !error
               (r/atom nil)

               !mic-track
               (atom nil)

               !mic-audio-el
               (atom nil)

               !mic-processor
               (atom nil)

               !mic-audio-context
               (atom nil)

               !mic-raf-id
               (atom nil)

               !mic-level
               (r/atom 0)

               !voice-threshold
               (atom 0.12)

               !noise-suppression
               (atom true)

               !mic-testing?
               (r/atom false)

               !restore-live-mic?
               (atom false)

               !camera-track
               (atom nil)

               !video-el
               (atom nil)

               !previewing?
               (r/atom false)

               _
               (load-call-devices!
                !devices
                !error)]

    (let [tr
          @(re-frame/subscribe
            [:i18n/tr])

          audio-input
          @(re-frame/subscribe
            [:settings/call-device
             :audioinput])

          video-input
          @(re-frame/subscribe
            [:settings/call-device
             :videoinput])

          audio-output
          @(re-frame/subscribe
            [:settings/call-device
             :audiooutput])

          voice-threshold
          @(re-frame/subscribe
            [:settings/voice-threshold])

          noise-suppression-enabled?
          @(re-frame/subscribe
            [:settings/noise-suppression-enabled?])

          devices
          @!devices

          error
          @!error]

      (reset! !voice-threshold
              voice-threshold)

      (reset! !noise-suppression
              noise-suppression-enabled?)

      [:div.settings-tab-content
       [:div.settings-page-header
        [:h2.settings-heading
         (tr [:settings.voice-video/title])]

        [:p.settings-description
         (tr [:settings.voice-video/description])]]

       [microphone-section
        {:tr tr
         :devices devices
         :audio-input audio-input
         :voice-threshold voice-threshold
         :noise-suppression-enabled? noise-suppression-enabled?
         :!mic-track !mic-track
         :!mic-audio-el !mic-audio-el
         :!mic-processor !mic-processor
         :!mic-audio-context !mic-audio-context
         :!mic-raf-id !mic-raf-id
         :!mic-level !mic-level
         :!mic-testing? !mic-testing?
         :!voice-threshold !voice-threshold
         :!noise-suppression !noise-suppression
         :!restore-live-mic? !restore-live-mic?
         :!error !error
         :!devices !devices}]

       [camera-section
        {:tr tr
         :devices devices
         :video-input video-input
         :!camera-track !camera-track
         :!video-el !video-el
         :!previewing? !previewing?
         :!error !error
         :!devices !devices}]

       [output-section
        {:tr tr
         :devices devices
         :audio-output audio-output}]

       [media-error-banner
        tr
        error]])

    (finally
      (stop-mic-test!
       !mic-track
       !mic-audio-el
       !mic-processor
       !mic-audio-context
       !mic-raf-id
       !mic-level
       !mic-testing?
       !restore-live-mic?
       true)

      (stop-camera-preview!
       !camera-track
       !previewing?))))