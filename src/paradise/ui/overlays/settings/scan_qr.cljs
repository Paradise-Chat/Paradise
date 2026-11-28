(ns paradise.ui.overlays.settings.scan-qr
  (:require
   ["jsqr" :default jsQR]
   [cljs-workers.mesh :as mesh]
   [cljs-workers.worker :as worker]
   [cljs.core.async :refer [<! go]]
   [clojure.string :as str]
   [net :refer [open-auth-url!]]
   [paradise.shared.net.auth :refer [valid-http-url-str]]
   [paradise.shared.utils.encoding :refer [uint8->base64 utf8->base64]]
   [paradise.shared.utils.svg :as icons]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [taoensso.timbre :as log]))

(defn- normalize-state [st]
  (when st
    (keyword (str/lower-case (name st)))))

(when (exists? js/window)
  (worker/register
   :qr-scan-progress
   (fn [progress]
     (go
       (re-frame/dispatch [:scan-qr/set-progress progress])
       {:ok true}))))

(re-frame/reg-event-db
 :scan-qr/reset
 (fn [db _]
   (dissoc db :scan-qr/progress :scan-qr/error)))

(re-frame/reg-event-fx
 :scan-qr/set-progress
 (fn [{:keys [db]} [_ progress]]
   (let [p        (when (map? progress)
                    (let [st  (normalize-state (:state progress))
                          msg (or (:message progress) (:msg progress) (:error progress))]
                      (cond-> (assoc progress :state st)
                        (and (nil? (:message progress)) (some? msg))
                        (assoc :message (str msg)))))
         safe-url (when (and (= (:state p) :open-url) (:auto-open? p))
                    (valid-http-url-str (:url p)))]
     (when (= (:state p) :error)
       (log/warn "QR scan progress error:" (:message p) p))
     (when safe-url
       (open-auth-url! {:url safe-url :external? true}))
     {:db (assoc db
                 :scan-qr/progress p
                 :scan-qr/error    (when (= (:state p) :error)
                                     (:message p)))})))

(re-frame/reg-sub
 :scan-qr/progress
 (fn [db _]
   (:scan-qr/progress db)))

(re-frame/reg-event-fx
 :scan-qr/submit
 (fn [{:keys [db]} [_ {:keys [text bytes-base64]}]]
   (let [clean-text (when (string? text) (str/trim text))
         b64        (or bytes-base64
                        (when (and clean-text (not (str/blank? clean-text)))
                          (utf8->base64 clean-text)))]
     (go
       (try
         (let [res       (<! (mesh/do-with-thread!
                              :engine-pool
                              {:handler   :scan-qr-code
                               :arguments {:text         clean-text
                                           :bytes-base64 b64}}))
               status-kw (some-> (:status res) name str/lower-case keyword)
               state-kw  (normalize-state (:state res))]
           (cond
             (some? state-kw)
             (re-frame/dispatch [:scan-qr/set-progress (assoc res :state state-kw)])

             (or (= status-kw :success) (= status-kw :ok))
             (when-not (= :done (:state @(re-frame/subscribe [:scan-qr/progress])))
               (re-frame/dispatch [:scan-qr/set-progress
                                   {:state   :done
                                    :title   (:title res)
                                    :message (:message res)}]))

             (= status-kw :cancelled)
             (re-frame/dispatch [:scan-qr/reset])

             :else
             (let [msg (or (:msg res)
                           (:message res)
                           (:error res)
                           "This protocol does not support QR code sign-in approval.")]
               (re-frame/dispatch [:scan-qr/set-progress
                                   {:state   :error
                                    :message (if (re-find #"(?i)no handler|unknown handler|not found" (str msg))
                                               "This protocol does not support QR code sign-in approval."
                                               (str msg))}]))))
         (catch :default e
           (log/error "QR scan submit failed:" e)
           (re-frame/dispatch [:scan-qr/set-progress
                               {:state   :error
                                :message (str (or (.-message e) e))}]))))
     {:db (assoc db
                 :scan-qr/progress {:state :connecting}
                 :scan-qr/error    nil)})))

(re-frame/reg-event-fx
 :scan-qr/confirm
 (fn [{:keys [db]} [_ opts]]
   (go
     (try
       (let [res       (<! (mesh/do-with-thread!
                            :engine-pool
                            {:handler   :confirm-qr-scan
                             :arguments (or opts {})}))
             status-kw (some-> (:status res) name str/lower-case keyword)
             state-kw  (normalize-state (:state res))]
         (cond
           (some? state-kw)
           (re-frame/dispatch [:scan-qr/set-progress (assoc res :state state-kw)])

           (or (= status-kw :success) (= status-kw :ok))
           (re-frame/dispatch [:scan-qr/set-progress
                               {:state   :done
                                :title   (:title res)
                                :message (:message res)}])

           :else
           (re-frame/dispatch [:scan-qr/set-progress
                               {:state   :error
                                :message (or (:msg res) (:message res) "Failed to approve sign-in.")}])))
       (catch :default e
         (log/error "QR scan confirm failed:" e)
         (re-frame/dispatch [:scan-qr/set-progress
                             {:state   :error
                              :message (str (or (.-message e) e))}]))))
   {:db (assoc db :scan-qr/progress {:state :syncing})}))

(re-frame/reg-event-fx
 :scan-qr/cancel
 (fn [{:keys [db]} _]
   (go
     (try
       (<! (mesh/do-with-thread!
            :engine-pool
            {:handler   :cancel-qr-scan
             :arguments {}}))
       (catch :default _ nil)))
   {:db (dissoc db :scan-qr/progress :scan-qr/error)}))

(defn- decode-rgba-frame [rgba width height]
  (try
    (when-let [^js decoded (jsQR rgba width height #js {:inversionAttempts "attemptBoth"})]
      (let [raw-text  (.-data decoded)
            bin-arr   (.-binaryData decoded)
            bytes-b64 (if (and bin-arr (pos? (.-length bin-arr)))
                        (uint8->base64 (js/Uint8Array. bin-arr))
                        (when (and (string? raw-text) (not (str/blank? raw-text)))
                          (utf8->base64 raw-text)))]
        (when (or (and (string? raw-text) (not (str/blank? raw-text)))
                  bytes-b64)
          {:text         (or raw-text "")
           :bytes-base64 bytes-b64})))
    (catch :default _ nil)))

(defn- make-barcode-detector []
  (try
    (when (and (exists? js/window) (.-BarcodeDetector js/window))
      (js/window.BarcodeDetector. #js {:formats #js ["qr_code"]}))
    (catch :default _ nil)))

(defn- stop-camera! [!cam]
  (let [{:keys [stream timer]} @!cam]
    (when timer
      (js/clearInterval timer))
    (when stream
      (try
        (doseq [track (.getTracks ^js stream)]
          (.stop ^js track))
        (catch :default _ nil)))
    (swap! !cam assoc :stream nil :timer nil :active? false)))

(defn- start-camera! [!cam !video-el on-decoded!]
  (stop-camera! !cam)
  (let [media-devices (when (and (exists? js/navigator) (.-mediaDevices js/navigator))
                        (.-mediaDevices js/navigator))]
    (if-not (and media-devices (fn? (.-getUserMedia media-devices)))
      (swap! !cam assoc :error "Camera API is not available in this browser." :starting? false)
      (do
        (swap! !cam assoc :error nil :starting? true)
        (-> (.getUserMedia media-devices
                           #js {:audio false
                                :video #js {:facingMode #js {:ideal "environment"}
                                            :width      #js {:ideal 1280}
                                            :height     #js {:ideal 720}}})
            (.then
             (fn [^js stream]
               (if (:unmounted? @!cam)
                 (doseq [track (.getTracks stream)] (.stop ^js track))
                 (let [canvas   (.createElement js/document "canvas")
                       ctx      (.getContext canvas "2d" #js {:willReadFrequently true})
                       detector (make-barcode-detector)
                       !busy    (atom false)
                       !found   (atom false)
                       tick!    (fn []
                                  (when (and (not @!found) (not @!busy))
                                    (when-let [^js vid @!video-el]
                                      (let [vw (.-videoWidth vid)
                                            vh (.-videoHeight vid)]
                                        (when (and (pos? vw) (pos? vh))
                                          (reset! !busy true)
                                          (let [max-w 640
                                                scale (if (> vw max-w) (/ max-w vw) 1.0)
                                                cw    (js/Math.round (* vw scale))
                                                ch    (js/Math.round (* vh scale))]
                                            (when (or (not= (.-width canvas) cw)
                                                      (not= (.-height canvas) ch))
                                              (set! (.-width canvas) cw)
                                              (set! (.-height canvas) ch))
                                            (.drawImage ctx vid 0 0 cw ch)
                                            (let [img-data (.getImageData ctx 0 0 cw ch)
                                                  decoded  (decode-rgba-frame (.-data img-data) cw ch)]
                                              (if decoded
                                                (do
                                                  (reset! !found true)
                                                  (reset! !busy false)
                                                  (stop-camera! !cam)
                                                  (on-decoded! decoded))
                                                (if detector
                                                  (-> (.detect ^js detector canvas)
                                                      (.then
                                                       (fn [codes]
                                                         (reset! !busy false)
                                                         (when (and (not @!found) codes (pos? (.-length codes)))
                                                           (let [^js first-code (aget codes 0)
                                                                 raw            (.-rawValue first-code)]
                                                             (when (and (string? raw) (not (str/blank? raw)))
                                                               (reset! !found true)
                                                               (stop-camera! !cam)
                                                               (on-decoded!
                                                                {:text         raw
                                                                 :bytes-base64 (utf8->base64 raw)}))))))
                                                      (.catch (fn [_] (reset! !busy false))))
                                                  (reset! !busy false))))))))))
                       timer    (js/setInterval tick! 180)]
                   (swap! !cam assoc
                          :stream    stream
                          :timer     timer
                          :active?   true
                          :starting? false
                          :error     nil)
                   (when-let [^js vid @!video-el]
                     (set! (.-srcObject vid) stream)
                     (-> (.play vid) (.catch (fn [_] nil))))))))
            (.catch
             (fn [err]
               (log/warn "Camera access failed:" err)
               (swap! !cam assoc
                      :starting? false
                      :active?   false
                      :error     (or (.-message err) "Camera permission denied.")))))))))

(defn- camera-scanner-view [{:keys [cam !cam !video-el boot-cam! tr]}]
  [:div.settings-hub-group
   [:div.settings-hub-group-card
    [:div.scan-qr-viewport-wrap
     (if (:error cam)
       [:div.scan-qr-camera-fallback
        [:div.scan-qr-fallback-icon [icons/qr-code {:size "36px"}]]
        [:div.scan-qr-fallback-text
         (tr [:settings.scan-qr/camera-unavailable])]
        [:button.settings-button.secondary.is-compact
         {:type     "button"
          :on-click boot-cam!}
         (tr [:settings.scan-qr/retry-camera])]]
       [:div.scan-qr-viewport
        [:video.scan-qr-video
         {:ref          (fn [el]
                          (reset! !video-el el)
                          (when (and el (:stream @!cam))
                            (set! (.-srcObject el) (:stream @!cam))
                            (-> (.play el) (.catch (fn [_] nil)))))
          :auto-play    true
          :muted        true
          :plays-inline true}]
        [:div.scan-qr-reticle
         [:span.scan-qr-corner.tl]
         [:span.scan-qr-corner.tr]
         [:span.scan-qr-corner.bl]
         [:span.scan-qr-corner.br]]])
     [:div.scan-qr-camera-hint
      (tr [:settings.scan-qr/camera-hint])]]]])

(defn- manual-qr-entry-view [{:keys [!manual-val !cam on-decoded! tr]}]
  [:div.settings-hub-group
   [:div.settings-hub-group-card
    [:div.settings-card-stack
     [:div.settings-field
      [:label.settings-label
       (tr [:settings.scan-qr/manual-label])]
      [:div.scan-qr-manual-row
       [:input.settings-input
        {:type        "text"
         :value       @!manual-val
         :placeholder (tr [:settings.scan-qr/manual-placeholder])
         :on-change   #(reset! !manual-val (.. % -target -value))}]
       [:button.settings-button.is-compact
        {:type     "button"
         :disabled (str/blank? @!manual-val)
         :on-click (fn []
                     (let [v (str/trim @!manual-val)]
                       (when-not (str/blank? v)
                         (stop-camera! !cam)
                         (reset! !manual-val "")
                         (on-decoded! {:text         v
                                       :bytes-base64 (utf8->base64 v)}))))}
        (tr [:settings.scan-qr/manual-submit])]]]]]])

(defn- scan-progress-view [{:keys [progress state-kw !input-val restart-scan! tr]}]
  [:div.settings-hub-group
   (case state-kw
     :done
     [:div.settings-banner.success
      [:div.settings-banner-icon [icons/check-circle-green]]
      [:div.settings-banner-copy
       [:div.settings-banner-title
        (or (:title progress) (tr [:settings.scan-qr/done-title]))]
       [:div.settings-banner-description
        (or (:message progress) (tr [:settings.scan-qr/done-subtitle]))]
       [:div.settings-actions {:style {:margin-top "12px"}}
        [:button.settings-button.secondary.is-compact
         {:type     "button"
          :on-click restart-scan!}
         (tr [:settings.scan-qr/scan-again])]]]]

     :error
     [:div.settings-banner.warning
      [:div.settings-banner-copy
       [:div.settings-banner-title
        (or (:title progress) "Unable to Sign In Device")]
       [:div.settings-banner-description
        (or (:message progress) (:msg progress) "QR code could not be processed.")]
       [:div.settings-actions {:style {:margin-top "12px"}}
        [:button.settings-button.is-compact
         {:type     "button"
          :on-click restart-scan!}
         (tr [:settings.scan-qr/scan-again])]]]]

     :check-code
     [:div.settings-hub-group-card
      [:div.settings-card-stack.scan-qr-status-card
       [:div.scan-qr-status-title
        (or (:title progress) (tr [:settings.scan-qr/check-code-title]))]
       [:div.scan-qr-status-desc
        (or (:message progress) (tr [:settings.scan-qr/check-code-hint]))]
       (when-let [code (:code progress)]
         [:div.scan-qr-code-box code])
       [:div.settings-actions
        [:button.settings-button.secondary.is-compact
         {:type     "button"
          :on-click restart-scan!}
         (tr [:settings.scan-qr/cancel])]]]]

     :confirm
     [:div.settings-hub-group-card
      [:div.settings-card-stack.scan-qr-status-card
       [:div.scan-qr-status-title
        (or (:title progress) (tr [:settings.scan-qr/confirm-title]))]
       (when-let [msg (:message progress)]
         [:div.scan-qr-status-desc msg])
       [:div.settings-actions
        [:button.settings-button.is-compact
         {:type     "button"
          :on-click #(re-frame/dispatch
                      [:scan-qr/confirm (select-keys progress [:token :fingerprint :ticket])])}
         (or (:confirm-label progress) (tr [:settings.scan-qr/confirm-button]))]
        [:button.settings-button.secondary.is-compact
         {:type     "button"
          :on-click restart-scan!}
         (tr [:settings.scan-qr/cancel])]]]]

     :input
     (let [inp     (or (:input progress) {})
           submit! (fn []
                     (let [v (str/trim (str @!input-val))]
                       (when-not (str/blank? v)
                         (reset! !input-val "")
                         (re-frame/dispatch
                          [:scan-qr/confirm
                           (assoc (select-keys progress [:token :fingerprint :ticket])
                                  :value v)]))))]
       [:div.settings-hub-group-card
        [:div.settings-card-stack.scan-qr-status-card
         [:div.scan-qr-status-title
          (or (:title progress) (tr [:settings.scan-qr/confirm-title]))]
         (when-let [msg (:message progress)]
           [:div.scan-qr-status-desc msg])
         (when-let [err (:error progress)]
           [:div.settings-error err])
         [:div.settings-field {:style {:width "100%" :max-width "300px" :text-align "left"}}
          (when-let [lbl (:label inp)]
            [:label.settings-label lbl])
          [:input.settings-input
           {:type        (or (:input-type inp) "text")
            :input-mode  (or (:input-mode inp) "text")
            :placeholder (or (:placeholder inp) "")
            :value       @!input-val
            :auto-focus  true
            :on-change   #(reset! !input-val (.. % -target -value))
            :on-key-down (fn [e]
                           (when (= (.-key e) "Enter")
                             (.preventDefault e)
                             (submit!)))}]]
         [:div.settings-actions
          [:button.settings-button.is-compact
           {:type     "button"
            :disabled (str/blank? (str @!input-val))
            :on-click submit!}
           (or (:submit-label inp) (tr [:settings.scan-qr/confirm-button]))]
          [:button.settings-button.secondary.is-compact
           {:type     "button"
            :on-click restart-scan!}
           (tr [:settings.scan-qr/cancel])]]]])

     :open-url
     (let [safe-url (valid-http-url-str (:url progress))]
       [:div.settings-hub-group-card
        [:div.settings-card-stack.scan-qr-status-card
         [:div.scan-qr-status-title
          (or (:title progress) (tr [:settings.scan-qr/confirm-title]))]
         (when-let [msg (:message progress)]
           [:div.scan-qr-status-desc msg])
         [:div.settings-actions
          (when safe-url
            [:button.settings-button.is-compact
             {:type     "button"
              :on-click #(open-auth-url! {:url safe-url :external? true})}
             (tr [:settings.scan-qr/open-link])])
          [:button.settings-button.secondary.is-compact
           {:type     "button"
            :on-click restart-scan!}
           (tr [:settings.scan-qr/cancel])]]]])

     [:div.settings-hub-group-card
      [:div.settings-card-stack.scan-qr-status-card
       [:div.scan-qr-status-title
        (or (:title progress)
            (if (= state-kw :syncing)
              (tr [:settings.scan-qr/syncing])
              (tr [:settings.scan-qr/connecting])))]
       (when-let [msg (:message progress)]
         [:div.scan-qr-status-desc msg])
       [:div.settings-actions
        [:button.settings-button.secondary.is-compact
         {:type     "button"
          :on-click restart-scan!}
         (tr [:settings.scan-qr/cancel])]]]])])

(defn ^:ui scan-qr-tab []
  (r/with-let [!cam        (r/atom {:stream nil :timer nil :active? false :starting? false :error nil :unmounted? false})
               !video-el   (atom nil)
               !manual-val (r/atom "")
               !input-val  (r/atom "")
               on-decoded! (fn [payload]
                             (re-frame/dispatch [:scan-qr/submit payload]))
               boot-cam!   (fn []
                             (start-camera! !cam !video-el on-decoded!))
               _           (do
                             (re-frame/dispatch [:scan-qr/reset])
                             (boot-cam!))]
    (let [tr            @(re-frame/subscribe [:i18n/tr])
          progress      @(re-frame/subscribe [:scan-qr/progress])
          state-kw      (:state progress)
          cam           @!cam
          restart-scan! (fn []
                          (reset! !input-val "")
                          (re-frame/dispatch [:scan-qr/cancel])
                          (boot-cam!))]
      [:div.settings-tab-content
       [:p.settings-subview-lead
        (tr [:settings.scan-qr/description])]
       (if (nil? state-kw)
         [:<>
          [camera-scanner-view {:cam cam :!cam !cam :!video-el !video-el :boot-cam! boot-cam! :tr tr}]
          [manual-qr-entry-view {:!manual-val !manual-val :!cam !cam :on-decoded! on-decoded! :tr tr}]]
         [scan-progress-view {:progress      progress
                              :state-kw      state-kw
                              :!input-val    !input-val
                              :restart-scan! restart-scan!
                              :tr            tr}])])
    (finally
      (swap! !cam assoc :unmounted? true)
      (stop-camera! !cam)
      (re-frame/dispatch [:scan-qr/cancel]))))