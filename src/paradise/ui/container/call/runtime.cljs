(ns paradise.ui.container.call.runtime
  (:require
   [shadow.esm :refer [dynamic-import]]
   [promesa.core :as p]
   [taoensso.timbre :as log]
   [paradise.ui.container.call.audio-processor :as audio-processor]))

(defonce !driver-instance (atom nil))
(defonce !loaded-drivers (atom {}))
(defonce !encoder-worker (atom nil))
(defonce !participants (atom []))
(defonce !pending-crypto-queue (atom []))

(defonce !voice-threshold (atom 0.12))
(defonce !voice-gate-enabled? (atom true))
(defonce !noise-suppression-enabled? (atom true))
(defonce !audio-processor (atom nil))

(defn- clamp01 [x]
  (max 0.0 (min 1.0 (or x 0.0))))

(defn resolve-media-track [^js track]
  (when track
    (let [candidate (or (.-mediaStreamTrack track)
                        (some-> (.-track track) .-mediaStreamTrack)
                        (.-track track)
                        track)]
      (when (and (exists? js/MediaStreamTrack)
                 (instance? js/MediaStreamTrack candidate))
        candidate))))

(defn- normalize-participant-tile [^js raw-tile]
  (when raw-tile
    (let [v-track (or (aget raw-tile "video-track")
                      (aget raw-tile "videoTrack"))
          a-track (or (aget raw-tile "audio-track")
                      (aget raw-tile "audioTrack"))
          parsed  (js->clj raw-tile :keywordize-keys true)]
      (assoc parsed
             :video-track (resolve-media-track v-track)
             :audio-track (resolve-media-track a-track)))))

(defn create-remote-worker! [script-url]
  (-> (js/fetch script-url)
      (p/then (fn [resp]
                (if (.-ok resp)
                  (.text resp)
                  (p/rejected (js/Error. (str "Failed to fetch call encoder script: " (.-status resp)))))))
      (p/then (fn [code]
                (let [blob     (js/Blob. #js [code] #js {:type "application/javascript"})
                      blob-url (js/URL.createObjectURL blob)
                      worker   (js/Worker. blob-url)]
                  (js/URL.revokeObjectURL blob-url)
                  worker)))))

(defn- load-driver-module! [driver-url]
  (if-let [cached (get @!loaded-drivers driver-url)]
    (p/resolved cached)
    (-> (dynamic-import driver-url)
        (p/then (fn [mod]
                  (let [create-fn (or (.-createCallDriver mod)
                                      (some-> (.-default mod) .-createCallDriver))]
                    (if create-fn
                      (do
                        (swap! !loaded-drivers assoc driver-url create-fn)
                        create-fn)
                      (p/rejected (js/Error. (str "Call driver missing createCallDriver export: " driver-url))))))))))

(defn get-or-create-audio-processor! []
  (or @!audio-processor
      (let [processor (audio-processor/create-processor
                       {:threshold @!voice-threshold
                        :gate-enabled? @!voice-gate-enabled?
                        :hangover-ms 180})]
        (reset! !audio-processor processor)
        (when-let [set-enabled (aget processor "setNoiseSuppressionEnabled")]
          (set-enabled @!noise-suppression-enabled?))
        processor)))

(defn create-test-audio-processor []
  (let [processor (audio-processor/create-processor
                   {:threshold @!voice-threshold
                    :gate-enabled? @!voice-gate-enabled?
                    :hangover-ms 180})]
    (when-let [set-enabled (aget processor "setNoiseSuppressionEnabled")]
      (set-enabled @!noise-suppression-enabled?))
    processor))

(defn destroy-audio-processor! []
  (when-let [processor @!audio-processor]
    (when-let [destroy (aget processor "destroy")]
      (-> (destroy)
          (p/catch (fn [err]
                     (log/debug err "Audio processor cleanup failed"))))))
  (reset! !audio-processor nil))

(defn- flush-pending-crypto! [driver]
  (when-let [inject-fn (and driver (aget driver "injectCryptoMaterial"))]
    (let [items @!pending-crypto-queue]
      (reset! !pending-crypto-queue [])
      (doseq [payload items]
        (inject-fn (clj->js payload))))))

(defn start-driver!
  [{:keys [driver-url encoder-url session-config callbacks]}]
  (p/let [create-driver (load-driver-module! driver-url)
          encoder       (if encoder-url
                          (create-remote-worker! encoder-url)
                          nil)
          _             (reset! !encoder-worker encoder)
          driver        (create-driver
                         #js {:sessionConfig (clj->js session-config)
                              :encoderWorker encoder
                              :getAudioProcessor get-or-create-audio-processor!
                              :onParticipantsChanged (fn [js-participants]
                                                       (let [parsed (into []
                                                                          (keep normalize-participant-tile)
                                                                          (array-seq (or js-participants #js [])))]
                                                         (reset! !participants parsed)
                                                         (when-let [cb (:on-participants-changed callbacks)]
                                                           (cb parsed))))
                              :onLocalMediaChanged (fn [js-state]
                                                     (when-let [cb (:on-local-media-changed callbacks)]
                                                       (cb (js->clj js-state :keywordize-keys true))))
                              :onConnected (fn []
                                             (when-let [cb (:on-connected callbacks)]
                                               (cb)))
                              :onDisconnected (fn [reason]
                                                (when-let [cb (:on-disconnected callbacks)]
                                                  (cb reason)))
                              :onSignalOut (fn [js-payload]
                                             (when-let [cb (:on-signal-out callbacks)]
                                               (cb (js->clj js-payload :keywordize-keys true))))
                              :onError (fn [category err]
                                         (when-let [cb (:on-error callbacks)]
                                           (cb (keyword category) err)))})]
    (reset! !driver-instance driver)
    (flush-pending-crypto! driver)
    (when-let [connect-fn (aget driver "connect")]
      (connect-fn))
    driver))

(defn inject-crypto-material! [payload]
  (if-let [driver @!driver-instance]
    (if-let [inject-fn (aget driver "injectCryptoMaterial")]
      (inject-fn (clj->js payload))
      (p/resolved nil))
    (do
      (swap! !pending-crypto-queue conj payload)
      (p/resolved :queued))))

(defn deliver-engine-signal! [payload]
  (if-let [driver @!driver-instance]
    (if-let [signal-fn (aget driver "onEngineSignal")]
      (signal-fn (clj->js payload))
      (p/resolved nil))
    (p/resolved nil)))

(defn set-microphone-enabled! [enabled?]
  (if-let [driver @!driver-instance]
    (.setMicrophoneEnabled driver (boolean enabled?))
    (p/rejected (js/Error. "No active call driver"))))

(defn set-camera-enabled! [enabled?]
  (if-let [driver @!driver-instance]
    (.setCameraEnabled driver (boolean enabled?))
    (p/rejected (js/Error. "No active call driver"))))

(defn set-screen-share-enabled! [enabled?]
  (if-let [driver @!driver-instance]
    (.setScreenShareEnabled driver (boolean enabled?))
    (p/rejected (js/Error. "No active call driver"))))

(defn switch-device! [kind device-id]
  (if-let [driver @!driver-instance]
    (.switchDevice driver kind device-id)
    (p/rejected (js/Error. "No active call driver"))))

(defn set-noise-suppression-enabled! [enabled?]
  (let [enabled? (boolean enabled?)]
    (reset! !noise-suppression-enabled? enabled?)
    (when-let [processor @!audio-processor]
      (when-let [set-enabled (aget processor "setNoiseSuppressionEnabled")]
        (set-enabled enabled?)))
    enabled?))

(defn set-voice-threshold! [threshold]
  (let [threshold (clamp01 threshold)]
    (reset! !voice-threshold threshold)
    (when-let [processor @!audio-processor]
      (when-let [set-threshold (aget processor "setThreshold")]
        (set-threshold threshold)))
    threshold))

(defn disconnect! []
  (if-let [driver @!driver-instance]
    (.disconnect driver)
    (p/resolved nil)))

(defn reset-session! []
  (destroy-audio-processor!)
  (when-let [worker @!encoder-worker]
    (.terminate worker)
    (reset! !encoder-worker nil))
  (reset! !driver-instance nil)
  (reset! !participants [])
  (reset! !pending-crypto-queue []))
