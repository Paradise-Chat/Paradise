(ns paradise.ui.overlays.lightbox
  (:require [re-frame.core :as re-frame]
            [reagent.core :as r]
            [paradise.ui.overlays.base :refer [modal-component]]
            [paradise.shared.utils.svg :as icons]
            ))

(defn ^:ui image-lightbox-content [{:keys [url]}]
  (r/with-let [!state           (r/atom {:scale 1 :last-scale 1 :x 0 :y 0 :last-x 0 :last-y 0 :start-dist 0})
               !active-pointers (atom {})
               tr               (re-frame/subscribe [:i18n/tr])
               reset-zoom-fn    #(reset! !state {:scale 1 :last-scale 1 :x 0 :y 0 :last-x 0 :last-y 0 :start-dist 0})
               get-dist         (fn [p1 p2]
                                  (let [dx (- (.-clientX p1) (.-clientX p2))
                                        dy (- (.-clientY p1) (.-clientY p2))]
                                    (js/Math.sqrt (+ (* dx dx) (* dy dy)))))]

    (let [{:keys [scale x y]} @!state
          active-count (count @!active-pointers)
          close-fn     #(re-frame/dispatch [:ui/close-modal])]

      [:div.lightbox-interaction-layer
       {:on-click (fn [e]
              (when (<= scale 1)
                (close-fn)))
        :on-wheel (fn [e]
                    (let [zoom-factor (if (pos? (.-deltaY e)) 0.9 1.1)
                          new-scale   (max 1 (min 5 (* scale zoom-factor)))]
                      (if (<= new-scale 1)
                        (reset-zoom-fn)
                        (swap! !state assoc :scale new-scale :last-scale new-scale))))
        :on-pointer-down (fn [e]
                           (.setPointerCapture (.-target e) (.-pointerId e))
                           (swap! !active-pointers assoc (.-pointerId e) e)
                           (let [ps (vals @!active-pointers)]
                             (cond
                               (= (count ps) 2) (swap! !state assoc :start-dist (get-dist (first ps) (second ps)) :last-scale scale)
                               (= (count ps) 1) (swap! !state assoc :last-x (.-clientX e) :last-y (.-clientY e)))))
        :on-pointer-move (fn [e]
                           (when (contains? @!active-pointers (.-pointerId e))
                             (swap! !active-pointers assoc (.-pointerId e) e)
                             (let [ps (vals @!active-pointers)]
                               (cond
                                 (= (count ps) 2)
                                 (let [new-dist (get-dist (first ps) (second ps))
                                       {:keys [start-dist last-scale]} @!state
                                       new-scale (max 1 (min 5 (* last-scale (/ new-dist start-dist))))]
                                   (swap! !state assoc :scale new-scale))
                                 (and (= (count ps) 1) (> scale 1))
                                 (swap! !state (fn [{:keys [x y last-x last-y] :as curr}]
                                                 (let [dx (- (.-clientX e) last-x)
                                                       dy (- (.-clientY e) last-y)]
                                                   (assoc curr
                                                          :x (+ x dx)
                                                          :y (+ y dy)
                                                          :last-x (.-clientX e)
                                                          :last-y (.-clientY e)))))))))
        :on-pointer-up (fn [e]
                         (.releasePointerCapture (.-target e) (.-pointerId e))
                         (swap! !active-pointers dissoc (.-pointerId e))
                         (let [ps (vals @!active-pointers)
                               curr-scale (:scale @!state)]
                           (cond
                             (<= curr-scale 1) (reset-zoom-fn)
                             (= (count ps) 1)  (let [p (first ps)]
                                                 (swap! !state assoc :last-scale curr-scale :last-x (.-clientX p) :last-y (.-clientY p)))
                             (zero? (count ps)) (swap! !state assoc :last-scale curr-scale))))
        :on-pointer-cancel (fn [e]
                             (.releasePointerCapture (.-target e) (.-pointerId e))
                             (reset! !active-pointers {}))}

       [:div.lightbox-close-btn
        {:on-click close-fn :title (@tr [:lightbox/close])
         }
        [icons/exit]]

       [:img.lightbox-image
        {:src url
         :on-click (fn [e] (.stopPropagation e))
         :on-double-click (fn [e] (.stopPropagation e) (reset-zoom-fn))
         :style {:transform (str "translate(" x "px, " y "px) scale(" scale ")")
                 :transition (if (pos? active-count) "none" "transform 0.2s cubic-bezier(0.2, 0.8, 0.2, 1)")
                 :cursor (if (> scale 1) (if (pos? active-count) "grabbing" "grab") "zoom-in")
                 :touch-action "none"
                 :user-select "none"
                 :pointer-events "auto"
                 :max-width "90vw"
                 :max-height "90vh"
                 :object-fit "contain"}}]

       [:div.lightbox-actions
        [:a.lightbox-btn {:href url :target "_blank" :rel "noopener noreferrer"}
         [:span.btn-icon [icons/external-link]] [:span (@tr [:lightbox/open-original])]]
        [:a.lightbox-btn {:href url :download "image"}
         [:span.btn-icon [icons/download]] [:span (@tr [:lightbox/download])]]]])))

(defmethod modal-component :image-lightbox [_]
  image-lightbox-content)
