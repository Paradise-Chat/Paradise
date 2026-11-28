(ns paradise.ui.overlays.lightbox
  (:require [re-frame.core :as re-frame]
            [reagent.core :as r]
            [paradise.media.component :refer [media]]
            [paradise.ui.overlays.base :refer [modal-component]]
            [paradise.shared.utils.svg :as icons]
            [paradise.ui.global :refer [!swipe-drag-data
                                        make-swipe-handlers]]))

(defn- lightbox-top-bar [{:keys [multi? active-idx total options-open? item-name tr toggle-options! close-fn]}]
  [:div.lightbox-top-bar
   {:class           (when options-open? "has-scrim")
    :on-pointer-down #(.stopPropagation %)
    :on-click        #(.stopPropagation %)}
   [:div.lightbox-top-left
    (when multi?
      [:div.lightbox-counter
       (str (inc active-idx) " / " total)])
    (when (and options-open? (not-empty item-name))
      [:div.lightbox-title item-name])]
   [:div.lightbox-top-right
    [:button.lightbox-icon-btn.lightbox-options-btn
     {:type       "button"
      :class      (when options-open? "is-active")
      :aria-label (@tr [:lightbox/options])
      :title      (@tr [:lightbox/options])
      :on-click   (fn [e]
                    (.stopPropagation e)
                    (toggle-options!))}
     [icons/more {:size "20px"}]]
    [:button.lightbox-icon-btn.lightbox-close-btn
     {:type       "button"
      :aria-label (@tr [:lightbox/close])
      :title      (@tr [:lightbox/close])
      :on-click   (fn [e]
                    (.stopPropagation e)
                    (close-fn))}
     [icons/exit {:size "22px" :stroke-width "2.5"}]]]])

(defn- lightbox-nav-arrows [{:keys [options-open? prev-fn! next-fn!]}]
  [:<>
   [:button.lightbox-nav-btn.is-prev
    {:type            "button"
     :class           (when-not options-open? "is-subtle")
     :on-pointer-down #(.stopPropagation %)
     :on-click        (fn [e]
                        (.stopPropagation e)
                        (prev-fn!))}
    [:svg {:width "22" :height "22" :viewBox "0 0 24 24" :fill "none"
           :stroke "currentColor" :stroke-width "2.4" :stroke-linecap "round" :stroke-linejoin "round"}
     [:polyline {:points "15 18 9 12 15 6"}]]]
   [:button.lightbox-nav-btn.is-next
    {:type            "button"
     :class           (when-not options-open? "is-subtle")
     :on-pointer-down #(.stopPropagation %)
     :on-click        (fn [e]
                        (.stopPropagation e)
                        (next-fn!))}
    [:svg {:width "22" :height "22" :viewBox "0 0 24 24" :fill "none"
           :stroke "currentColor" :stroke-width "2.4" :stroke-linecap "round" :stroke-linejoin "round"}
     [:polyline {:points "9 18 15 12 9 6"}]]]])

(defn- lightbox-bottom-bar
  [{:keys [options-open? multi? gallery-items active-idx room-id step-to!
           scale reset-zoom-fn url current-item resolve-live-url
           item-source item-source-map item-name item-mime item-room-id item-event-id tr]}]
  [:div.lightbox-bottom-bar
   {:class           (if options-open? "is-visible" "is-hidden")
    :on-click        #(.stopPropagation %)
    :on-pointer-down #(.stopPropagation %)}
   (when multi?
     [:div.lightbox-filmstrip
      (map-indexed
       (fn [idx thumb-item]
         (let [selected? (= idx active-idx)
               t-uri     (or (:display-uri thumb-item)
                             (:thumbnail-source thumb-item)
                             (when-not (:video? thumb-item) (:source thumb-item)))
               t-map     (or (:display-map thumb-item)
                             (:thumbnail-source-map thumb-item)
                             (when-not (:video? thumb-item) (:source-map thumb-item)))]
           ^{:key (or (:internal-id thumb-item) (:event-id thumb-item) idx)}
           [:div.lightbox-thumb-item
            {:class    (when selected? "is-selected")
             :on-click (fn [e]
                         (.stopPropagation e)
                         (step-to! idx))}
            (when (or t-uri t-map)
              [media
               {:uri        t-uri
                :source-map t-map
                :tag-type   "img"
                :alt        "thumb"
                :mime-type  (:display-mime thumb-item)
                :room-id    (or (:room-id thumb-item) (:room_id thumb-item) room-id)
                :event-id   (:event-id thumb-item)
                :width      (:crop-size thumb-item)
                :height     (:crop-size thumb-item)
                :method     (:crop-method thumb-item)}])]))
       gallery-items)])

   [:div.lightbox-actions
    (when (> scale 1.01)
      [:button.lightbox-btn
       {:type     "button"
        :on-click (fn [e]
                    (.stopPropagation e)
                    (reset-zoom-fn))}
       [:span (str (@tr [:lightbox/reset-zoom]) " (" (.toFixed scale 1) "×)")]])
    [:a.lightbox-btn
     {:href     (or url "#")
      :target   "_blank"
      :rel      "noopener noreferrer"
      :on-click (fn [e]
                  (when (or current-item (not url))
                    (.preventDefault e)
                    (when-let [live-src (resolve-live-url)]
                      (.open js/window live-src "_blank" "noopener,noreferrer"))))}
     [:span.btn-icon [icons/external-link]]
     [:span (@tr [:lightbox/open-original])]]
    (if (or item-source item-source-map)
      [:button.lightbox-btn
       {:type     "button"
        :on-click (fn [e]
                    (.preventDefault e)
                    (.stopPropagation e)
                    (re-frame/dispatch
                     [:media/download {:uri        item-source
                                       :source-map item-source-map
                                       :filename   item-name
                                       :mime-type  item-mime
                                       :room-id    item-room-id
                                       :event-id   item-event-id}]))}
       [:span.btn-icon [icons/download]]
       [:span (@tr [:lightbox/download])]]
      [:a.lightbox-btn
       {:href     url
        :download item-name}
       [:span.btn-icon [icons/download]]
       [:span (@tr [:lightbox/download])]])]])

(defn- default-transform-state [idx]
  {:index           (or idx 0)
   :scale           1
   :last-scale      1
   :x               0
   :y               0
   :last-x          0
   :last-y          0
   :down-x          0
   :down-y          0
   :down-ts         0
   :dismiss-y       0
   :dismissing?     false
   :moved?          false
   :start-dist      0
   :options-open?   false})

(defn ^:ui image-lightbox-content [{:keys [url items index room-id filename source source-map mime-type event-id]}]
  (r/with-let [gallery-items    (vec (or items []))
               total            (count gallery-items)
               multi?           (> total 1)
               !state           (r/atom (default-transform-state index))
               !drag-state      (atom {:start-x nil :dx 0})
               !active-pointers (atom {})
               !stage-el        (atom nil)
               !tap-timer       (atom nil)
               !last-tap-ts     (atom 0)
               !gesture-mode    (atom nil)
               tr               (re-frame/subscribe [:i18n/tr])
               clear-tap-timer! (fn []
                                  (when-let [t @!tap-timer]
                                    (js/clearTimeout t)
                                    (reset! !tap-timer nil)))
               close-fn         (fn []
                                  (clear-tap-timer!)
                                  (re-frame/dispatch [:ui/close-modal]))
               reset-zoom-fn    #(swap! !state merge
                                        {:scale       1
                                         :last-scale  1
                                         :x           0
                                         :y           0
                                         :last-x      0
                                         :last-y      0
                                         :dismiss-y   0
                                         :dismissing? false
                                         :start-dist  0})
               step-to!         (fn [next-idx]
                                  (when (pos? total)
                                    (clear-tap-timer!)
                                    (reset! !active-pointers {})
                                    (reset! !gesture-mode nil)
                                    (let [keep-opts? (:options-open? @!state)]
                                      (reset! !state
                                              (assoc (default-transform-state (mod next-idx total))
                                                     :options-open? keep-opts?)))))
               prev-fn!         #(when multi? (step-to! (dec (:index @!state))))
               next-fn!         #(when multi? (step-to! (inc (:index @!state))))
               toggle-options!  #(swap! !state update :options-open? not)
               toggle-zoom!     (fn []
                                  (if (> (:scale @!state) 1.01)
                                    (reset-zoom-fn)
                                    (swap! !state merge
                                           {:scale         2.5
                                            :last-scale    2.5
                                            :x             0
                                            :y             0
                                            :dismiss-y     0
                                            :dismissing?   false
                                            :options-open? false})))
               get-dist         (fn [p1 p2]
                                  (let [dx (- (.-clientX p1) (.-clientX p2))
                                        dy (- (.-clientY p1) (.-clientY p2))]
                                    (js/Math.sqrt (+ (* dx dx) (* dy dy)))))
               swipe-handlers   (make-swipe-handlers
                                 !drag-state
                                 {:id         :lightbox
                                  :can-claim? (fn [dx dy]
                                                (and multi?
                                                     (nil? @!gesture-mode)
                                                     (<= (:scale @!state) 1)
                                                     (<= (count @!active-pointers) 1)
                                                     (> (js/Math.abs dx) 10)
                                                     (> (js/Math.abs dx) (* 1.4 (js/Math.abs dy)))))
                                  :on-move    (fn [dx]
                                                (reset! !gesture-mode :swipe-x)
                                                (swap! !state assoc :x dx :moved? true))
                                  :on-end     (fn [dx]
                                                (reset! !gesture-mode nil)
                                                (cond
                                                  (> dx 64)
                                                  (do
                                                    (prev-fn!)
                                                    (swap! !state assoc :moved? true))

                                                  (< dx -64)
                                                  (do
                                                    (next-fn!)
                                                    (swap! !state assoc :moved? true))

                                                  :else
                                                  (reset-zoom-fn)))})
               key-listener     (fn [e]
                                  (case (.-key e)
                                    "ArrowLeft"  (do (.preventDefault e) (prev-fn!))
                                    "ArrowRight" (do (.preventDefault e) (next-fn!))
                                    "Escape"     (do (.preventDefault e)
                                                     (.stopPropagation e)
                                                     (close-fn))
                                    nil))
               _                (.addEventListener js/window "keydown" key-listener true)]
    (let [{:keys [scale x y moved? dismiss-y dismissing? options-open?] active-idx :index} @!state
          active-count     (count @!active-pointers)
          current-item     (when (pos? total)
                             (nth gallery-items (mod active-idx total)))
          video?           (or (:video? current-item)
                               (= (:media-tag current-item) "Video"))
          item-room-id     (or (:room-id current-item)
                               (:room_id current-item)
                               room-id)
          item-mime        (or (get-in current-item [:info :mimetype])
                               (:display-mime current-item)
                               mime-type)
          item-source      (or (:source current-item) source)
          item-source-map  (or (:source-map current-item) source-map)
          item-event-id    (or (:event-id current-item) event-id)
          item-name        (or (:filename current-item)
                               (:caption current-item)
                               filename
                               (if video? "video" "image"))
          abs-dismiss      (js/Math.abs (double dismiss-y))
          dismiss-scale    (if dismissing?
                             (max 0.82 (- 1.0 (/ abs-dismiss 1100.0)))
                             1.0)
          backdrop-alpha   (if dismissing?
                             (max 0.14 (* 0.90 (- 1.0 (/ abs-dismiss 380.0))))
                             0.90)
          eff-scale        (* scale dismiss-scale)
          eff-y            (+ y dismiss-y)
          stage-class      (cond-> "lightbox-media-stage"
                             video?                             (str " is-video")
                             (> scale 1)                        (str " is-zoomed")
                             (or (pos? active-count) dismissing?) (str " is-dragging"))
          image-class      (if (and multi? options-open?)
                             "lightbox-image is-multi"
                             "lightbox-image")
          resolve-live-url (fn []
                             (or (when-let [stage @!stage-el]
                                   (when-let [el (.querySelector stage "img, video")]
                                     (not-empty (.-src el))))
                                 url))]
      [:div.lightbox-interaction-layer
       {:style {:background-color (str "rgba(6, 8, 12, " backdrop-alpha ")")}
        :on-click (fn [e]
                    (when (and (= (.-target e) (.-currentTarget e))
                               (not moved?))
                      (close-fn)))
        :on-wheel (fn [e]
                    (when-not video?
                      (let [zoom-factor (if (pos? (.-deltaY e)) 0.9 1.1)
                            new-scale   (max 1 (min 5 (* scale zoom-factor)))]
                        (if (<= new-scale 1)
                          (reset-zoom-fn)
                          (swap! !state assoc
                                 :scale new-scale
                                 :last-scale new-scale
                                 :options-open? false)))))
        :on-pointer-down (fn [e]
                           (let [primary? (or (= (.-pointerType e) "touch")
                                              (zero? (.-button e)))]
                             (when primary?
                               (when-not video?
                                 ((:on-pointer-down swipe-handlers) e))
                               (swap! !active-pointers assoc (.-pointerId e) e)
                               (let [ps (vals @!active-pointers)]
                                 (cond
                                   (and (not video?) (= (count ps) 2))
                                   (do
                                     (clear-tap-timer!)
                                     (reset! !gesture-mode :pinch)
                                     (reset! !drag-state {:start-x nil :dx 0})
                                     (swap! !swipe-drag-data assoc :owner :pinch)
                                     (try
                                       (.setPointerCapture (.-currentTarget e) (.-pointerId e))
                                       (catch :default _ nil))
                                     (swap! !state assoc
                                            :moved?        true
                                            :dismissing?   false
                                            :dismiss-y     0
                                            :x             0
                                            :options-open? false
                                            :start-dist    (get-dist (first ps) (second ps))
                                            :last-scale    scale))

                                   (= (count ps) 1)
                                   (do
                                     (reset! !gesture-mode nil)
                                     (swap! !state assoc
                                            :moved?      false
                                            :dismissing? false
                                            :dismiss-y   0
                                            :down-x      (.-clientX e)
                                            :down-y      (.-clientY e)
                                            :down-ts     (js/Date.now)
                                            :last-x      (.-clientX e)
                                            :last-y      (.-clientY e))))))))
        :on-pointer-move (fn [e]
                           (when-not video?
                             ((:on-pointer-move swipe-handlers) e))
                           (when (and (not= (:owner @!swipe-drag-data) :lightbox)
                                      (contains? @!active-pointers (.-pointerId e)))
                             (swap! !active-pointers assoc (.-pointerId e) e)
                             (let [ps (vals @!active-pointers)]
                               (cond
                                 (and (not video?) (= (count ps) 2))
                                 (let [new-dist  (get-dist (first ps) (second ps))
                                       {:keys [start-dist last-scale]} @!state
                                       new-scale (max 1 (min 5 (* last-scale (/ new-dist (max 1 start-dist)))))]
                                   (swap! !state assoc :scale new-scale :moved? true))

                                 (= (count ps) 1)
                                 (let [{:keys [down-x down-y]} @!state
                                       total-dx (- (.-clientX e) down-x)
                                       total-dy (- (.-clientY e) down-y)
                                       abs-dx   (js/Math.abs total-dx)
                                       abs-dy   (js/Math.abs total-dy)
                                       dragged? (> (js/Math.hypot total-dx total-dy) 8)]
                                   (cond
                                     (and (not video?) (> scale 1))
                                     (do
                                       (when dragged? (reset! !gesture-mode :pan))
                                       (swap! !state
                                              (fn [{:keys [x y last-x last-y] :as curr}]
                                                (assoc curr
                                                       :moved? (or (:moved? curr) dragged?)
                                                       :x      (+ x (- (.-clientX e) last-x))
                                                       :y      (+ y (- (.-clientY e) last-y))
                                                       :last-x (.-clientX e)
                                                       :last-y (.-clientY e)))))

                                     (or (= @!gesture-mode :dismiss-y)
                                         (and (nil? @!gesture-mode)
                                              (<= scale 1)
                                              (> abs-dy (if video? 18 10))
                                              (> abs-dy (* 1.2 abs-dx))))
                                     (do
                                       (when (not= @!gesture-mode :dismiss-y)
                                         (clear-tap-timer!)
                                         (reset! !gesture-mode :dismiss-y)
                                         (swap! !swipe-drag-data assoc :owner :lightbox-dismiss))
                                       (swap! !state assoc
                                              :moved?      true
                                              :dismissing? true
                                              :dismiss-y   total-dy))

                                     dragged?
                                     (swap! !state assoc :moved? true)))))))
        :on-pointer-up (fn [e]
                         (let [was-swiping?    (= (:owner @!swipe-drag-data) :lightbox)
                               was-dismissing? (= @!gesture-mode :dismiss-y)
                               {:keys [dismiss-y down-ts]} @!state]
                           (when-not video?
                             ((:on-pointer-up swipe-handlers) e))
                           (try
                             (.releasePointerCapture (.-currentTarget e) (.-pointerId e))
                             (catch :default _ nil))
                           (swap! !active-pointers dissoc (.-pointerId e))
                           (when (= (:owner @!swipe-drag-data) :lightbox-dismiss)
                             (swap! !swipe-drag-data assoc :owner nil))
                           (reset! !gesture-mode nil)
                           (cond
                             was-dismissing?
                             (let [elapsed  (max 1 (- (js/Date.now) (or down-ts 0)))
                                   abs-dy   (js/Math.abs (double dismiss-y))
                                   velocity (/ abs-dy elapsed)]
                               (if (or (>= abs-dy 85)
                                       (and (>= abs-dy 32) (>= velocity 0.45)))
                                 (close-fn)
                                 (swap! !state assoc :dismiss-y 0 :dismissing? false)))

                             (not was-swiping?)
                             (let [ps         (vals @!active-pointers)
                                   curr-scale (:scale @!state)]
                               (cond
                                 (<= curr-scale 1)
                                 (reset-zoom-fn)

                                 (= (count ps) 1)
                                 (let [p (first ps)]
                                   (swap! !state assoc
                                          :last-scale curr-scale
                                          :last-x     (.-clientX p)
                                          :last-y     (.-clientY p)))

                                 (zero? (count ps))
                                 (swap! !state assoc :last-scale curr-scale))))))
        :on-pointer-cancel (fn [e]
                             (when-not video?
                               ((:on-pointer-cancel swipe-handlers) e))
                             (try
                               (.releasePointerCapture (.-currentTarget e) (.-pointerId e))
                               (catch :default _ nil))
                             (when (= (:owner @!swipe-drag-data) :lightbox-dismiss)
                               (swap! !swipe-drag-data assoc :owner nil))
                             (reset! !gesture-mode nil)
                             (reset! !active-pointers {})
                             (swap! !state assoc :dismiss-y 0 :dismissing? false))}

       [lightbox-top-bar
        {:multi?          multi?
         :active-idx      active-idx
         :total           total
         :options-open?   options-open?
         :item-name       item-name
         :tr              tr
         :toggle-options! toggle-options!
         :close-fn        close-fn}]

       (when multi?
         [lightbox-nav-arrows
          {:options-open? options-open?
           :prev-fn!      prev-fn!
           :next-fn!      next-fn!}])

       [:div
        {:class    stage-class
         :ref      #(reset! !stage-el %)
         :on-click (fn [e]
                     (.stopPropagation e)
                     (when (and (not video?) (not moved?))
                       (let [now (js/Date.now)]
                         (if (< (- now @!last-tap-ts) 260)
                           (do
                             (clear-tap-timer!)
                             (reset! !last-tap-ts 0)
                             (toggle-zoom!))
                           (do
                             (reset! !last-tap-ts now)
                             (clear-tap-timer!)
                             (reset! !tap-timer
                                     (js/setTimeout
                                      (fn []
                                        (reset! !tap-timer nil)
                                        (toggle-options!))
                                      220)))))))
         :style    {:transform (str "translate3d(" x "px, " eff-y "px, 0) scale(" eff-scale ")")}}
        (if (and current-item (or (:source current-item) (:source-map current-item)))
          [:<>
           [:div.message-image-placeholder
            [:div.spinner]]
           ^{:key (or (:internal-id current-item) (:event-id current-item) active-idx)}
           [media
            {:uri        (:source current-item)
             :source-map (:source-map current-item)
             :tag-type   (if video? "video" "img")
             :controls   video?
             :alt        item-name
             :mime-type  item-mime
             :room-id    item-room-id
             :event-id   (:event-id current-item)
             :class      image-class}]]
          [:img
           {:class     image-class
            :draggable false
            :alt       item-name
            :src       url}])]

       [lightbox-bottom-bar
        {:options-open?    options-open?
         :multi?           multi?
         :gallery-items    gallery-items
         :active-idx       active-idx
         :room-id          room-id
         :step-to!         step-to!
         :scale            scale
         :reset-zoom-fn    reset-zoom-fn
         :url              url
         :current-item     current-item
         :resolve-live-url resolve-live-url
         :item-source      item-source
         :item-source-map  item-source-map
         :item-name        item-name
         :item-mime        item-mime
         :item-room-id     item-room-id
         :item-event-id    item-event-id
         :tr               tr}]])
    (finally
      (clear-tap-timer!)
      (.removeEventListener js/window "keydown" key-listener true))))

(defmethod modal-component :image-lightbox [_]
  image-lightbox-content)