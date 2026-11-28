(ns paradise.ui.container.call.audio-processor
  (:require
   [paradise.shared.utils.helpers :refer [clamp01]]
   [shadow.esm :refer [dynamic-import]]
   [promesa.core :as p]
   [taoensso.timbre :as log]))

(defonce !rnnoise-module
  (atom nil))

(defonce !rnnoise-module-promise
  (atom nil))

(defonce !registration-promises
  (js/WeakMap.))

(def ^:private rnnoise-worklet-url
  "/rnnoise/rnnoise.worklet.js")

(def ^:private rnnoise-wasm-url
  "/rnnoise/rnnoise.wasm")

(defn audio-worklet-supported? []
  (and (exists? js/AudioContext)
       (exists? js/AudioWorkletNode)
       (true? js/window.isSecureContext)))

(defn- module-export [module export-name]
  (or (aget module export-name)
      (when-let [default (.-default module)]
        (aget default export-name))))

(defn load-rnnoise! []
  (cond
    @!rnnoise-module
    (p/resolved @!rnnoise-module)

    @!rnnoise-module-promise
    @!rnnoise-module-promise

    (not (audio-worklet-supported?))
    (p/rejected
     (js/Error.
      (str "RNNoise requires AudioWorklet support in a secure browser context. "
           "secureContext=" js/window.isSecureContext
           ", AudioWorkletNode=" (exists? js/AudioWorkletNode))))

    :else
    (let [promise
          (-> (dynamic-import "/rnnoise/rnnoise.mjs")
              (p/then
               (fn [module]
                 (let [RNNoiseNode (module-export module "RNNoiseNode")
                       load-assets (module-export module "rnnoise_loadAssets")]
                   (when-not RNNoiseNode
                     (throw
                      (js/Error.
                       (str "RNNoise module loaded, but RNNoiseNode was missing. "
                            "Exports: " (js/Object.keys module)))))
                   (when-not load-assets
                     (throw
                      (js/Error.
                       (str "RNNoise module loaded, but rnnoise_loadAssets was missing. "
                            "Exports: " (js/Object.keys module)))))
                   (reset! !rnnoise-module module)
                   module)))
              (p/catch
               (fn [err]
                 (reset! !rnnoise-module-promise nil)
                 (throw err))))]
      (reset! !rnnoise-module-promise promise)
      promise)))

(defn- ensure-rnnoise-registered! [audio-context]
  (if-let [existing (.get !registration-promises audio-context)]
    existing
    (let [registration
          (-> (p/let [module      (load-rnnoise!)
                      RNNoiseNode (module-export module "RNNoiseNode")
                      load-assets (module-export module "rnnoise_loadAssets")
                      assets      (load-assets #js {:scriptSrc rnnoise-worklet-url
                                                    :moduleSrc rnnoise-wasm-url})
                      _           (.register RNNoiseNode audio-context assets)]
                RNNoiseNode)
              (p/catch
               (fn [err]
                 (.delete !registration-promises audio-context)
                 (throw err))))]
      (.set !registration-promises audio-context registration)
      registration)))

(defn- document-hidden? []
  (and (exists? js/document)
       (true? (.-hidden js/document))))

(defn- resume-context-safe! [^js audio-context]
  (when (and audio-context
             (contains? #{"suspended" "interrupted"} (.-state audio-context)))
    (-> (.resume audio-context)
        (p/catch (fn [_] nil)))))

(defn- calculate-rms [^js analyser ^js samples]
  (.getFloatTimeDomainData analyser samples)
  (let [n   (.-length samples)
        sum (loop [idx   0
                   total 0.0]
              (if (< idx n)
                (let [sample (aget samples idx)]
                  (recur (inc idx) (+ total (* sample sample))))
                total))]
    (if (pos? n)
      (js/Math.sqrt (/ sum n))
      0.0)))

(defn- ramp-gate! [^js audio-context ^js gate-node open?]
  (let [^js param (.-gain gate-node)
        now       (.-currentTime audio-context)
        target    (if open? 1.0 0.0)
        duration  (if open? 0.008 0.035)]
    (.cancelScheduledValues param now)
    (.setValueAtTime param (.-value param) now)
    (.linearRampToValueAtTime param target (+ now duration))))

(defn create-processor
  [{:keys [threshold gate-enabled? ios-direct? hangover-ms on-level input-gain]
    :or   {threshold     0.004
           gate-enabled? true
           ios-direct?   false
           hangover-ms   180
           input-gain    1.0}}]
  (let [!threshold        (atom (clamp01 threshold))
        !gate-enabled?    (atom (boolean gate-enabled?))
        !ios-direct?      (atom (boolean ios-direct?))
        !input-gain-val   (atom (-> (js/Number (or input-gain 1.0)) (max 0.0) (min 2.0)))
        !audio-context    (atom nil)
        !input-track      (atom nil)
        !local-track      (atom nil)
        !source           (atom nil)
        !rnnoise          (atom nil)
        !analyser         (atom nil)
        !gate             (atom nil)
        !input-gain       (atom nil)
        !destination      (atom nil)
        !keepalive-gain   (atom nil)
        !samples          (atom nil)
        !timer-id         (atom nil)
        !running?         (atom false)
        !gate-open?       (atom false)
        !last-active      (atom 0)
        !last-tick        (atom 0)
        !muted-since      (atom 0)
        !last-restart     (atom 0)
        !ctx-check-wall   (atom 0)
        !ctx-check-time   (atom -1)
        !background-mode? (atom (document-hidden?))
        !cleanup-hooks    (atom nil)
        !level            (atom 0.0)
        !rnnoise-enabled? (atom (not ios-direct?))
        !raw-gain         (atom nil)
        !rnnoise-gain     (atom nil)
        processor         #js {}]
    (letfn
     [(force-gate-open! []
        (when (and @!audio-context @!gate (not @!gate-open?))
          (reset! !gate-open? true)
          (ramp-gate! @!audio-context @!gate true)))

      (stop-meter! []
        (reset! !running? false)
        (when-let [timer-id @!timer-id]
          (js/clearInterval timer-id)
          (reset! !timer-id nil)))

      (detach-lifecycle-hooks! []
        (when-let [cleanup @!cleanup-hooks]
          (reset! !cleanup-hooks nil)
          (try (cleanup) (catch :default _ nil))))

      (destroy-graph! []
        (stop-meter!)
        (detach-lifecycle-hooks!)
        (when-let [node @!rnnoise]
          (try
            (.update node false)
            (catch :default err
              (log/debug err "Failed to stop RNNoise worklet"))))
        (doseq [^js node [@!source
                          @!rnnoise
                          @!raw-gain
                          @!rnnoise-gain
                          @!analyser
                          @!gate
                          @!input-gain
                          @!keepalive-gain
                          @!destination]]
          (when node
            (try (.disconnect node) (catch :default _ nil))))
        (when-let [^js processed (aget processor "processedTrack")]
          (try (.stop processed) (catch :default _ nil)))
        (reset! !audio-context nil)
        (reset! !input-track nil)
        (reset! !local-track nil)
        (reset! !source nil)
        (reset! !rnnoise nil)
        (reset! !raw-gain nil)
        (reset! !rnnoise-gain nil)
        (reset! !analyser nil)
        (reset! !gate nil)
        (reset! !input-gain nil)
        (reset! !keepalive-gain nil)
        (reset! !destination nil)
        (reset! !samples nil)
        (reset! !gate-open? false)
        (reset! !last-active 0)
        (reset! !last-tick 0)
        (reset! !muted-since 0)
        (reset! !ctx-check-wall 0)
        (reset! !ctx-check-time -1)
        (reset! !level 0.0)
        (aset processor "processedTrack" nil))

      (meter-step! []
        (when @!running?
          (let [wall-now (js/performance.now)]
            (when-let [^js ctx @!audio-context]
              (resume-context-safe! ctx)
              (when (and (= (.-state ctx) "running")
                         (>= (- wall-now @!ctx-check-wall) 3000))
                (let [cur-t (.-currentTime ctx)]
                  (when (and (>= @!ctx-check-time 0)
                             (= cur-t @!ctx-check-time))
                    (-> (.suspend ctx)
                        (p/then (fn [_]
                                  (when @!audio-context
                                    (.resume @!audio-context))))
                        (p/catch (fn [_] nil))))
                  (reset! !ctx-check-wall wall-now)
                  (reset! !ctx-check-time cur-t))))
            ;; On iOS WebKit, starting an HTMLVideoElement (e.g. sub-browser video)
            ;; can briefly interrupt CoreAudioSharedUnit in foreground, leaving the
            ;; microphone MediaStreamTrack muted or ended while LocalAudioTrack.isMuted
            ;; is still false. Automatically restart the mic track if it stays dead > 1.5s.
            (when (and @!ios-direct?
                       @!input-track
                       @!local-track
                       (not (document-hidden?))
                       (not (boolean (.-isMuted ^js @!local-track)))
                       (fn? (.-restartTrack ^js @!local-track)))
              (let [^js trk   @!input-track
                    impaired? (or (not= (.-readyState trk) "live")
                                  (true? (.-muted trk)))]
                (if impaired?
                  (do
                    (when (zero? @!muted-since)
                      (reset! !muted-since wall-now))
                    (when (and (>= (- wall-now @!muted-since) 1500)
                               (>= (- wall-now @!last-restart) 6000))
                      (reset! !last-restart wall-now)
                      (reset! !muted-since 0)
                      (log/info "Re-acquiring iOS microphone track after media session interruption")
                      (-> (.restartTrack ^js @!local-track)
                          (p/catch (fn [err]
                                     (log/debug err "iOS microphone auto-restart skipped"))))))
                  (when (pos? @!muted-since)
                    (reset! !muted-since 0))))))
          (when-let [analyser @!analyser]
            (when-let [samples @!samples]
              (let [now             (js/performance.now)
                    prev-tick       @!last-tick
                    _               (reset! !last-tick now)
                    throttled?      (and (pos? prev-tick)
                                         (> (- now prev-tick) 250))
                    bg?             (or @!background-mode?
                                        (document-hidden?)
                                        throttled?)
                    level           (clamp01 (calculate-rms analyser samples))
                    threshold       @!threshold
                    close-threshold (* threshold 0.75)
                    open?           (cond
                                      (or bg? (not @!gate-enabled?))
                                      true

                                      (>= level threshold)
                                      (do
                                        (reset! !last-active now)
                                        true)

                                      (and @!gate-open? (>= level close-threshold))
                                      (do
                                        (reset! !last-active now)
                                        true)

                                      (and @!gate-open?
                                           (< (- now @!last-active) hangover-ms))
                                      true

                                      :else
                                      false)]
                (reset! !level level)
                (when (not= open? @!gate-open?)
                  (reset! !gate-open? open?)
                  (when (and @!audio-context @!gate)
                    (ramp-gate! @!audio-context @!gate open?)))
                (when on-level
                  (try
                    (on-level level open?)
                    (catch :default err
                      (log/debug err "Audio processor level callback failed")))))))))

      (start-meter! []
        (stop-meter!)
        (reset! !running? true)
        (reset! !last-tick (js/performance.now))
        (meter-step!)
        (reset! !timer-id (js/setInterval meter-step! 25)))

      (attach-lifecycle-hooks! [^js audio-context]
        (detach-lifecycle-hooks!)
        (let [on-state-change (fn []
                                (when @!running?
                                  (resume-context-safe! audio-context)))
              on-vis-change   (fn []
                                (let [hidden? (document-hidden?)]
                                  (reset! !background-mode? hidden?)
                                  (resume-context-safe! audio-context)
                                  (if hidden?
                                    (force-gate-open!)
                                    (do
                                      (reset! !last-tick (js/performance.now))
                                      (meter-step!)))))]
          (try
            (.addEventListener audio-context "statechange" on-state-change)
            (catch :default _ nil))
          (when (exists? js/document)
            (.addEventListener js/document "visibilitychange" on-vis-change))
          (reset! !cleanup-hooks
                  (fn []
                    (try
                      (.removeEventListener audio-context "statechange" on-state-change)
                      (catch :default _ nil))
                    (when (exists? js/document)
                      (.removeEventListener js/document "visibilitychange" on-vis-change))))))

      (build! [^js opts]
        (destroy-graph!)
        (let [^js audio-context (when opts (aget opts "audioContext"))
              ^js input-track   (when opts (aget opts "track"))
              ^js local-track   (when opts (aget opts "localTrack"))
              direct?           @!ios-direct?]
          (if-not (and audio-context input-track)
            (p/rejected
             (js/Error. "Audio processor did not receive audioContext/track"))
            (p/let [^js RNNoiseNode (if direct?
                                      (p/resolved nil)
                                      (-> (ensure-rnnoise-registered! audio-context)
                                          (p/catch (fn [err]
                                                     (log/warn err "RNNoise unavailable; falling back to direct mic path")
                                                     nil))))
                    _               (if (contains? #{"suspended" "interrupted"}
                                                   (.-state audio-context))
                                      (-> (.resume audio-context) (p/catch (fn [_] nil)))
                                      (p/resolved nil))]
              (let [use-rnnoise?     (and (some? RNNoiseNode) (not direct?) @!rnnoise-enabled?)
                    ^js source       (.createMediaStreamSource
                                      audio-context
                                      (js/MediaStream. #js [input-track]))
                    ^js rnnoise-node (when RNNoiseNode (new RNNoiseNode audio-context))
                    ^js analyser     (.createAnalyser audio-context)
                    ^js gate         (.createGain audio-context)
                    ^js input-gain   (.createGain audio-context)
                    ^js destination  (.createMediaStreamDestination audio-context)
                    ^js keepalive    (.createGain audio-context)
                    ^js raw-gain     (.createGain audio-context)
                    ^js rnnoise-gain (.createGain audio-context)
                    ^js mixer        (.createGain audio-context)
                    samples          (js/Float32Array. 1024)
                    init-open?       (or direct?
                                         (not @!gate-enabled?)
                                         @!background-mode?
                                         (document-hidden?))]
                (set! (.-fftSize analyser) 1024)
                (set! (.-smoothingTimeConstant analyser) 0.0)
                (set! (.. gate -gain -value) (if init-open? 1.0 0.0))
                (set! (.. input-gain -gain -value) @!input-gain-val)
                (set! (.. keepalive -gain -value) 0.0)
                (set! (.. raw-gain -gain -value) (if use-rnnoise? 0 1))
                (set! (.. rnnoise-gain -gain -value) (if use-rnnoise? 1 0))

                (.connect source raw-gain)
                (when rnnoise-node
                  (.connect source rnnoise-node)
                  (.connect rnnoise-node rnnoise-gain)
                  (.connect rnnoise-gain mixer))
                (.connect raw-gain mixer)
                (.connect mixer analyser)
                (.connect analyser gate)
                (.connect gate input-gain)
                (.connect input-gain destination)
                (.connect gate keepalive)
                (.connect keepalive (.-destination audio-context))

                (reset! !audio-context audio-context)
                (reset! !input-track input-track)
                (reset! !local-track local-track)
                (reset! !ctx-check-wall (js/performance.now))
                (reset! !ctx-check-time (.-currentTime audio-context))
                (reset! !source source)
                (reset! !rnnoise rnnoise-node)
                (reset! !raw-gain raw-gain)
                (reset! !rnnoise-gain rnnoise-gain)
                (reset! !analyser analyser)
                (reset! !gate gate)
                (reset! !input-gain input-gain)
                (reset! !keepalive-gain keepalive)
                (reset! !destination destination)
                (reset! !samples samples)
                (reset! !gate-open? init-open?)

                (aset processor "processedTrack"
                      (aget (.getAudioTracks (.-stream destination)) 0))

                (attach-lifecycle-hooks! audio-context)
                (start-meter!)

                (log/info "Audio processor initialized"
                          {:sample-rate (.-sampleRate audio-context)
                           :direct?     (not use-rnnoise?)
                           :gate-open?  init-open?})
                (js/setTimeout
                 (fn []
                   (when @!running?
                     (log/info "Audio processor signal check"
                               {:rms        @!level
                                :gate-open? @!gate-open?
                                :ctx-state  (some-> @!audio-context .-state)
                                :track-live (= (.-readyState input-track) "live")})))
                 2000)
                nil)))))]

      (aset processor "setNoiseSuppressionEnabled"
            (fn [enabled?]
              (let [enabled? (and (boolean enabled?) (not @!ios-direct?) (some? @!rnnoise))]
                (reset! !rnnoise-enabled? enabled?)
                (when (and @!audio-context @!raw-gain @!rnnoise-gain)
                  (let [now (.-currentTime @!audio-context)]
                    (.setValueAtTime (.. @!raw-gain -gain) (if enabled? 0 1) now)
                    (.setValueAtTime (.. @!rnnoise-gain -gain) (if enabled? 1 0) now)))
                enabled?)))

      (aset processor "setBackgroundMode"
            (fn [bg?]
              (let [bg? (boolean bg?)]
                (reset! !background-mode? bg?)
                (when-let [ctx @!audio-context]
                  (resume-context-safe! ctx))
                (if bg?
                  (force-gate-open!)
                  (do
                    (reset! !last-tick (js/performance.now))
                    (meter-step!)))
                bg?)))

      (aset processor "resume"
            (fn []
              (when-let [ctx @!audio-context]
                (resume-context-safe! ctx))
              (when (or @!background-mode? (document-hidden?))
                (force-gate-open!))
              nil))

      (aset processor "name" "paradise-rnnoise")
      (aset processor "init" (fn [opts] (build! opts)))
      (aset processor "restart" (fn [opts] (build! opts)))
      (aset processor "destroy"
            (fn []
              (destroy-graph!)
              (p/resolved nil)))
      (aset processor "setThreshold"
            (fn [threshold]
              (reset! !threshold (clamp01 threshold))))
      (aset processor "setInputGain"
            (fn [gain]
              (let [clamped (-> (js/Number (or gain 1.0)) (max 0.0) (min 2.0))]
                (reset! !input-gain-val clamped)
                (when (and @!audio-context @!input-gain)
                  (let [now (.-currentTime @!audio-context)]
                    (.setValueAtTime (.. @!input-gain -gain) clamped now)))
                clamped)))
      (aset processor "setGateEnabled"
            (fn [enabled?]
              (reset! !gate-enabled? (boolean enabled?))
              (when (not enabled?)
                (force-gate-open!))
              nil))
      (aset processor "getLevel" (fn [] @!level))
      (aset processor "isGateOpen" (fn [] @!gate-open?))

      processor)))