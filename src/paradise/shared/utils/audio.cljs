(ns ^:virtualizer/skip paradise.shared.utils.audio
  (:require [paradise.shared.utils.platform :as platform]
            [taoensso.timbre :as log]))

(defonce ^:private !audio-ctx (atom nil))
(defonce ^:private !audio-unlock-wired? (atom false))
(defonce ^:private !audio-unlocked? (atom false))
(defonce ^:private !last-sound-ts (atom 0))
(defonce ^:private !wav-url-cache (atom {}))
(defonce ^:private !silent-wav-url (atom nil))
(defonce ^:private !active-audio-els (atom #{}))

(def ^:private wav-sample-rate 44100)

(defn preset-tone-specs [p-kw mention?]
  (case p-kw
    :chime
    (if mention?
      [{:offset 0.0  :type :sine :freq 1046.5 :gain 0.85 :attack 0.008 :decay 0.18}
       {:offset 0.08 :type :sine :freq 1318.5 :gain 0.90 :attack 0.008 :decay 0.20}
       {:offset 0.16 :type :sine :freq 1568.0 :gain 1.00 :attack 0.008 :decay 0.30}]
      [{:offset 0.0   :type :sine :freq 880.0  :gain 0.85 :attack 0.008 :decay 0.16}
       {:offset 0.085 :type :sine :freq 1318.5 :gain 1.00 :attack 0.008 :decay 0.24}])

    :pop
    (if mention?
      [{:offset 0.0 :type :sine :freq 440.0 :end-freq 880.0  :gain 1.00 :attack 0.005 :decay 0.08}
       {:offset 0.1 :type :sine :freq 560.0 :end-freq 1120.0 :gain 1.05 :attack 0.005 :decay 0.095}]
      [{:offset 0.0 :type :sine :freq 420.0 :end-freq 820.0  :gain 1.00 :attack 0.005 :decay 0.085}])

    :bell
    (if mention?
      [{:offset 0.0  :type :triangle :freq 1046.5 :gain 0.78 :attack 0.008 :decay 0.34}
       {:offset 0.0  :type :sine     :freq 2093.0 :gain 0.40 :attack 0.008 :decay 0.26}
       {:offset 0.11 :type :sine     :freq 1568.0 :gain 0.68 :attack 0.008 :decay 0.32}]
      [{:offset 0.0  :type :triangle :freq 880.0  :gain 0.74 :attack 0.008 :decay 0.30}
       {:offset 0.0  :type :sine     :freq 1760.0 :gain 0.35 :attack 0.008 :decay 0.22}])

    :subtle
    (if mention?
      [{:offset 0.0   :type :sine :freq 580.0 :end-freq 480.0 :gain 0.70 :attack 0.006 :decay 0.10}
       {:offset 0.095 :type :sine :freq 680.0 :end-freq 560.0 :gain 0.75 :attack 0.006 :decay 0.11}]
      [{:offset 0.0   :type :sine :freq 520.0 :end-freq 420.0 :gain 0.68 :attack 0.006 :decay 0.10}])

    nil))

(defn- write-wav-ascii! [view offset s]
  (dotimes [i (count s)]
    (.setUint8 view (+ offset i) (.charCodeAt s i))))

(defn- encode-pcm16-wav-url [samples sample-rate]
  (when (and (exists? js/ArrayBuffer)
             (exists? js/DataView)
             (exists? js/Blob)
             (exists? js/URL)
             (fn? (.-createObjectURL js/URL)))
    (let [n         (.-length samples)
          data-size (* n 2)
          buf       (js/ArrayBuffer. (+ 44 data-size))
          view      (js/DataView. buf)]
      (write-wav-ascii! view 0 "RIFF")
      (.setUint32 view 4 (+ 36 data-size) true)
      (write-wav-ascii! view 8 "WAVE")
      (write-wav-ascii! view 12 "fmt ")
      (.setUint32 view 16 16 true)
      (.setUint16 view 20 1 true)
      (.setUint16 view 22 1 true)
      (.setUint32 view 24 sample-rate true)
      (.setUint32 view 28 (* sample-rate 2) true)
      (.setUint16 view 32 2 true)
      (.setUint16 view 34 16 true)
      (write-wav-ascii! view 36 "data")
      (.setUint32 view 40 data-size true)
      (loop [i 0]
        (when (< i n)
          (let [s       (max -1.0 (min 1.0 (aget samples i)))
                int-val (int (js/Math.round (* s 32767.0)))]
            (.setInt16 view (+ 44 (* i 2)) int-val true)
            (recur (inc i)))))
      (js/URL.createObjectURL (js/Blob. #js [buf] #js {:type "audio/wav"})))))

(defn- synthesize-preset-wav-url [p-kw mention?]
  (when-let [tones (seq (preset-tone-specs p-kw mention?))]
    (let [sr        wav-sample-rate
          lead-pad  0.025
          tail-pad  0.04
          total-sec (reduce (fn [mx {:keys [offset attack decay]}]
                              (max mx (+ lead-pad offset attack decay tail-pad)))
                            0.1
                            tones)
          total-n   (int (js/Math.ceil (* total-sec sr)))
          samples   (js/Float32Array. total-n)
          two-pi    (* 2.0 js/Math.PI)
          tri-scale (/ 2.0 js/Math.PI)]
      (doseq [{:keys [offset type freq end-freq gain attack decay]} tones]
        (let [dur       (+ attack decay)
              start-idx (int (js/Math.floor (* (+ lead-pad offset) sr)))
              tone-n    (int (js/Math.ceil (* dur sr)))
              amp-scale (* 0.55 gain)
              sweep?    (and end-freq (pos? end-freq) (not= end-freq freq))
              freq-rat  (if sweep? (/ end-freq freq) 1.0)]
          (loop [k     0
                 phase 0.0]
            (when (< k tone-n)
              (let [idx (+ start-idx k)]
                (when (< idx total-n)
                  (let [t      (/ k sr)
                        env    (if (< t attack)
                                 (/ t (max 0.0001 attack))
                                 (js/Math.pow 0.001 (/ (- t attack) (max 0.0001 decay))))
                        inst-f (if sweep?
                                 (* freq (js/Math.pow freq-rat (/ t dur)))
                                 freq)
                        wave   (case type
                                 :triangle (* tri-scale (js/Math.asin (js/Math.sin phase)))
                                 (js/Math.sin phase))
                        cur    (aget samples idx)]
                    (aset samples idx (+ cur (* wave env amp-scale)))
                    (recur (inc k) (+ phase (/ (* two-pi inst-f) sr))))))))))
      (encode-pcm16-wav-url samples sr))))

(defn- get-preset-wav-url [p-kw mention?]
  (let [cache-key [p-kw (boolean mention?)]]
    (or (get @!wav-url-cache cache-key)
        (when-let [url (try
                         (synthesize-preset-wav-url p-kw mention?)
                         (catch :default e
                           (log/warn "Failed to synthesize notification WAV:" e)
                           nil))]
          (swap! !wav-url-cache assoc cache-key url)
          url))))

(defn- get-silent-wav-url []
  (or @!silent-wav-url
      (when-let [url (try
                       (encode-pcm16-wav-url (js/Float32Array. 128) 8000)
                       (catch :default _ nil))]
        (reset! !silent-wav-url url)
        url)))

(defn- get-or-create-audio-ctx! []
  (when (exists? js/window)
    (let [ctor (or (.-AudioContext js/window)
                   (.-webkitAudioContext js/window))]
      (when ctor
        (or @!audio-ctx
            (let [created (ctor.)]
              (reset! !audio-ctx created)
              created))))))

(defn init-audio-unlock!
  "Attaches one-shot user-gesture listeners to unlock AudioContext and HTMLAudio playback."
  []
  (when (and (exists? js/window) (not @!audio-unlock-wired?))
    (reset! !audio-unlock-wired? true)
    (let [events ["click" "pointerdown" "keydown" "touchend"]]
      (letfn [(detach! []
                (doseq [ev events]
                  (.removeEventListener js/window ev unlock! true)))
              (mark-unlocked! []
                (reset! !audio-unlocked? true)
                (detach!))
              (unlock! [_]
                (when-let [ctx (get-or-create-audio-ctx!)]
                  (if (= (.-state ctx) "running")
                    (mark-unlocked!)
                    (try
                      (-> (.resume ctx)
                          (.then (fn []
                                   (when (= (.-state ctx) "running")
                                     (mark-unlocked!))))
                          (.catch (fn [_] nil)))
                      (catch :default _ nil))))
                (when (and (not @!audio-unlocked?) (exists? js/Audio))
                  (when-let [silent-url (get-silent-wav-url)]
                    (try
                      (let [el (js/Audio. silent-url)]
                        (set! (.-volume el) 0.0)
                        (when-let [prom (.play el)]
                          (when (fn? (.-then prom))
                            (-> prom
                                (.then (fn [] (mark-unlocked!)))
                                (.catch (fn [_] nil))))))
                      (catch :default _ nil)))))]
        (doseq [ev events]
          (.addEventListener js/window ev unlock! true))))))

(defn audio-playback-ready?
  "Returns true when audio playback has been unlocked or is unrestricted."
  []
  (boolean (or @!audio-unlocked?
               (platform/electron-platform?)
               (when-let [ctx @!audio-ctx]
                 (= (.-state ctx) "running")))))

(defn- schedule-tone!
  [ctx start-t {:keys [type freq end-freq gain attack decay]
                :or   {type :sine attack 0.008 decay 0.18 gain 0.25}}]
  (let [osc   (.createOscillator ctx)
        amp   (.createGain ctx)
        t0    (max (.-currentTime ctx) start-t)
        t1    (+ t0 attack)
        t2    (+ t1 decay)
        g-max (max 0.0002 gain)]
    (set! (.-type osc) (name type))
    (.setValueAtTime (.-frequency osc) freq t0)
    (when (and end-freq (pos? end-freq))
      (.exponentialRampToValueAtTime (.-frequency osc) end-freq t2))
    (.setValueAtTime (.-gain amp) 0.0001 t0)
    (.linearRampToValueAtTime (.-gain amp) g-max t1)
    (.exponentialRampToValueAtTime (.-gain amp) 0.0001 t2)
    (.connect osc amp)
    (.connect amp (.-destination ctx))
    (.start osc t0)
    (.stop osc (+ t2 0.02))))

(defn- render-notification-tones! [ctx p-kw vol mention?]
  (when-let [tones (seq (preset-tone-specs p-kw mention?))]
    (let [base-t (+ (.-currentTime ctx) 0.02)
          scale  (* 0.45 (/ vol 100.0))]
      (try
        (doseq [{:keys [offset type freq end-freq gain attack decay]} tones]
          (schedule-tone! ctx
                          (+ base-t offset)
                          {:type     type
                           :freq     freq
                           :end-freq end-freq
                           :gain     (* scale gain)
                           :attack   attack
                           :decay    decay}))
        (reset! !audio-unlocked? true)
        true
        (catch :default e
          (log/warn "Web Audio notification tone playback failed:" e)
          false)))))

(defn- play-via-web-audio! [p-kw vol mention? force? now]
  (when-let [ctx (get-or-create-audio-ctx!)]
    (if (= (.-state ctx) "running")
      (do
        (when-not force?
          (reset! !last-sound-ts now))
        (render-notification-tones! ctx p-kw vol mention?))
      (try
        (-> (.resume ctx)
            (.then (fn []
                     (if (= (.-state ctx) "running")
                       (do
                         (when-not force?
                           (reset! !last-sound-ts now))
                         (render-notification-tones! ctx p-kw vol mention?))
                       (log/warn "Notification audio blocked: AudioContext state is"
                                 (.-state ctx)))))
            (.catch (fn [err]
                      (log/warn "Notification AudioContext resume failed:" err))))
        (catch :default err
          (log/warn "Notification AudioContext resume threw:" err))))))

(defn play-notification-sound!
  "Plays a synthesized notification cue via HTMLAudioElement (cached PCM WAV)
   with automatic Web Audio API fallback."
  [{:keys [preset volume mention? force?]
    :or   {preset :chime volume 70 mention? false force? false}}]
  (let [p-kw (keyword (or preset :chime))
        vol  (max 0 (min 100 (int (or volume 70))))
        now  (js/Date.now)]
    (when (and (not= p-kw :none)
               (pos? vol)
               (or force? (>= (- now @!last-sound-ts) 600)))
      (let [wav-url (when (exists? js/Audio)
                      (get-preset-wav-url p-kw mention?))]
        (if wav-url
          (try
            (let [el       (js/Audio. wav-url)
                  cleanup! (fn []
                             (swap! !active-audio-els disj el)
                             (set! (.-onended el) nil)
                             (set! (.-onerror el) nil))]
              (set! (.-volume el) (/ vol 100.0))
              (set! (.-preload el) "auto")
              (swap! !active-audio-els conj el)
              (set! (.-onended el) cleanup!)
              (set! (.-onerror el)
                    (fn [_]
                      (cleanup!)
                      (play-via-web-audio! p-kw vol mention? force? now)))
              (when-not force?
                (reset! !last-sound-ts now))
              (if-let [prom (.play el)]
                (when (fn? (.-then prom))
                  (-> prom
                      (.then (fn []
                               (reset! !audio-unlocked? true)))
                      (.catch (fn [err]
                                (cleanup!)
                                (log/warn "Notification HTMLAudio playback failed, trying Web Audio fallback:" err)
                                (play-via-web-audio! p-kw vol mention? true now)))))
                (reset! !audio-unlocked? true)))
            (catch :default err
              (log/warn "Notification HTMLAudio threw, trying Web Audio fallback:" err)
              (play-via-web-audio! p-kw vol mention? force? now)))
          (play-via-web-audio! p-kw vol mention? force? now))))))