(ns paradise.ui.global
  (:require
   [cljs.core.async :refer [go <!]]
   [re-frame.core :as re-frame]
   [cljs-workers.mesh :as mesh]
   [taoensso.timbre :as log]
   [clojure.string :as str]
   [reagent.core :as r]
   [paradise.shared.utils.macros :refer [defui]]
   [paradise.ui.overlays.base :refer [modal-component popover-component context-menu-component]]
   [paradise.shared.utils.svg :as icons]
   [paradise.media.component :refer [media]]
   ))

(defn click-away-wrapper
  "A reusable invisible backdrop for 'light dismiss' popovers.
   It catches clicks and prevents them from bleeding through to the app."
  [{:keys [on-close z-index]} & children]
  [:<>
   [:div.click-away-catcher
    {:on-mouse-down (fn [e]
                      (.preventDefault e)
                      (.stopPropagation e)
                      (on-close))
     :style {:position "fixed"
             :top 0 :left 0 :right 0 :bottom 0
             :z-index (or z-index 99)
             :cursor "default"}}]
   (into [:<>] children)])

(defonce !active-context-menu (r/atom nil))
(defonce !active-modal (r/atom nil))
(defonce !active-popover (r/atom nil))

(re-frame/reg-event-fx
 :ui/open-context-menu
 (fn [_ [_ id props]]
   (reset! !active-context-menu {:id id :props props})
   {}))

(re-frame/reg-event-fx
 :ui/close-context-menu
 (fn [_ _]
   (reset! !active-context-menu nil)
   {}))

(defn ^:ui context-menu-root []
  (r/with-let [!drag-y (r/atom 0)
               !start-y (r/atom 0)
               !safe-to-click? (r/atom false)
               !listener-active? (r/atom false)
               is-mobile? (<= js/window.innerWidth 768)
               enable-clicks! (fn enable-clicks! []
                                (reset! !safe-to-click? true)
                                (reset! !listener-active? false)
                                (.removeEventListener js/window "touchend" enable-clicks! true)
                                (.removeEventListener js/window "pointerup" enable-clicks! true)
                                (.removeEventListener js/window "touchcancel" enable-clicks! true))]
    (let [active-menu @!active-context-menu]
      (when-not active-menu
        (reset! !drag-y 0)
        (reset! !start-y 0)
        (reset! !safe-to-click? false)
        (when @!listener-active?
          (enable-clicks!)))

      (when (and active-menu (not @!safe-to-click?) (not @!listener-active?))
        (if is-mobile?
          (do
            (reset! !listener-active? true)
            (.addEventListener js/window "touchend" enable-clicks! true)
            (.addEventListener js/window "pointerup" enable-clicks! true)
            (.addEventListener js/window "touchcancel" enable-clicks! true))
          (enable-clicks!)))

      (when active-menu
        (let [{:keys [id props]} active-menu
              {:keys [x y]} props
              close-fn #(re-frame/dispatch [:ui/close-context-menu])
              Target   (context-menu-component id)
              menu-width  200
              menu-height 250
              render-x (if (> (+ x menu-width) js/window.innerWidth) (- x menu-width) x)
              render-y (if (> (+ y menu-height) js/window.innerHeight) (- y menu-height) y)

              handle-ptr-down (fn [e]
                                (reset! !start-y (.-clientY e))
                                (.setPointerCapture (.-target e) (.-pointerId e)))
              handle-ptr-move (fn [e]
                                (when (pos? @!start-y)
                                  (let [delta (- (.-clientY e) @!start-y)]
                                    (reset! !drag-y (max 0 delta)))))
              handle-ptr-up   (fn [e]
                                (if (> @!drag-y 100)
                                  (close-fn)
                                  (reset! !drag-y 0))
                                (reset! !start-y 0)
                                (.releasePointerCapture (.-target e) (.-pointerId e)))]

          [click-away-wrapper
           {:on-close close-fn :z-index 9998}
           [:div.universal-context-menu
            {:class (when is-mobile? "mobile-sheet")
             :on-context-menu (fn [e] (.preventDefault e))
             :on-pointer-down (when is-mobile? handle-ptr-down)
             :on-pointer-move (when is-mobile? handle-ptr-move)
             :on-pointer-up   (when is-mobile? handle-ptr-up)
             :style (if is-mobile?
                      {:transform (str "translateY(" @!drag-y "px)")
                       :transition (if (pos? @!start-y) "none" "transform 0.3s cubic-bezier(0.2, 0.8, 0.2, 1)")
                       :z-index 99999
                       :pointer-events (if @!safe-to-click? "auto" "none")}
                      {:left (str render-x "px")
                       :top  (str render-y "px")
                       :transform "none"
                       :z-index 99999
                       :pointer-events (if @!safe-to-click? "auto" "none")})}
            [:div.mobile-drag-handle]
            (when Target
              [Target (assoc props :close-fn close-fn)])]])))))

(re-frame/reg-event-fx
 :ui/open-modal
 (fn [_ [_ modal-id props]]
   (reset! !active-modal {:id modal-id :props props})
   {}))

(re-frame/reg-event-fx
 :ui/close-modal
 (fn [_ _]
   (reset! !active-modal nil)
   {}))

(defn- modal-inner
  [{:keys [on-close backdrop-props window-props]} children]
  (r/with-let [handle-keyup (fn [e] (when (= (.-key e) "Escape") (on-close)))
               _ (.addEventListener js/window "keyup" handle-keyup)]
    [:div.modal-backdrop
     (merge {:on-click on-close}
            backdrop-props)
     (into [:div.modal-window
            (merge {:on-click #(.stopPropagation %)}
                   window-props)]
           children)]
    (finally
      (.removeEventListener js/window "keyup" handle-keyup))))

(defn generic-modal
  [{:keys [is-open?] :as props} & children]
  (when is-open?
    [modal-inner props children]))

(defn modal-root []
  (let [active-modal @!active-modal]
    (when active-modal
      (let [{:keys [id props]} active-modal
            close-fn           #(re-frame/dispatch [:ui/close-modal])
            TargetComponent    (modal-component id)]
        [generic-modal
         {:is-open?       true
          :on-close       close-fn
          :backdrop-props (:backdrop-props props)
          :window-props   (:window-props props)}
         [TargetComponent props]]))))

(re-frame/reg-event-fx
 :ui/open-popover
 (fn [_ [_ id props]]
   (reset! !active-popover {:id id :props props})
   {}))

(re-frame/reg-event-fx
 :ui/close-popover
 (fn [_ _]
   (reset! !active-popover nil)
   {}))

(defn popover-root []
  (let [active-popover @!active-popover]
    (when active-popover
      (let [{:keys [id props]} active-popover
            props    (if (map? props) props (js->clj props :keywordize-keys true))
            {:keys [x y width height backdrop?]
             :or {width 320 height 380 backdrop? true}} props
            close-fn #(re-frame/dispatch [:ui/close-popover])
            Target   (popover-component id)]
        (when Target
          (let [win-w js/window.innerWidth
                win-h js/window.innerHeight
                render-x (max 10 (if (> (+ x width) win-w) (- x width) x))
                render-y (max 10 (if (> (+ y height) win-h) (- y height) y))]
            [:<>
             (when backdrop?
               [:div.popover-backdrop
                {:style {:position "fixed" :top 0 :left 0 :right 0 :bottom 0 :z-index 11999}
                 :on-click close-fn
                 :on-context-menu (fn [e] (close-fn))}])
             [:div.popover-container
              {:style {:left (str render-x "px")
                       :top  (str render-y "px")
                       :position "fixed"
                       :z-index 12000}}
              [Target (assoc props :close-fn close-fn)]]]))))))



(defn start-long-press! [handler !long-press-state action-fn mx my]
  (js/setTimeout
   (fn []
     (try
       (swap! !long-press-state assoc
              :fired? true
              :scroll-handler handler)
       (.addEventListener js/window "touchmove" handler #js {:passive false :capture true})
       (if action-fn
         (action-fn mx my)
         (js/console.error "[LongPress Debug] Timer fired but action-fn is nil!"))
       (catch :default err
         (js/console.error "[LongPress Debug] Crash inside timeout! !long-press-state:" !long-press-state)
         (throw err))))
   500))

(defonce !long-press-state (atom {}))

(defn clear-long-press! []
  (let [{:keys [timer scroll-handler]} @!long-press-state]
    (when timer (js/clearTimeout timer))
    (when scroll-handler
      (.removeEventListener js/window "touchmove" scroll-handler true))
    (reset! !long-press-state {:timer nil :fired? false :start-pos nil :scroll-handler nil})))

(defn long-press-props [action-fn]
  {:on-context-menu (fn [e]
                      (try
                        (.stopPropagation e)
                        (.preventDefault e)
                        (when (and action-fn (not (:fired? @!long-press-state)))
                          (action-fn (.-clientX e) (.-clientY e)))
                        (clear-long-press!)
                        (catch :default err
                          (js/console.error "[LongPress Debug] Crash in on-context-menu. action-fn:" action-fn)
                          (throw err))))

   :on-touch-start  (fn [e]
                      (try
                        (clear-long-press!)
                        (let [touch (aget (.-touches e) 0)
                              mx    (.-clientX touch)
                              my    (.-clientY touch)
                              handler (fn [ev] (.preventDefault ev))
                              timer  (start-long-press! handler !long-press-state action-fn mx my)]
                          (swap! !long-press-state assoc
                                 :start-pos {:x mx :y my}
                                 :timer timer))
                        (catch :default err
                          (js/console.error "[LongPress Debug] Crash in on-touch-start. !long-press-state:" !long-press-state)
                          (throw err))))

   :on-touch-move   (fn [e]
                      (try
                        (let [{:keys [timer start-pos fired?]} @!long-press-state]
                          (when (and timer (not fired?))
                            (let [touch (aget (.-touches e) 0)
                                  mx    (.-clientX touch)
                                  my    (.-clientY touch)
                                  {:keys [x y]} start-pos
                                  dx    (- mx x)
                                  dy    (- my y)
                                  dist-sq (+ (* dx dx) (* dy dy))]
                              (when (> dist-sq 100)
                                (clear-long-press!)))))
                        (catch :default err
                          (js/console.error "[LongPress Debug] Crash in on-touch-move. !long-press-state:" !long-press-state)
                          (throw err))))

   :on-touch-end    (fn [e]
                      (try
                        (let [{:keys [fired?]} @!long-press-state]
                          (clear-long-press!)
                          (when fired?
                            (.stopPropagation e)
                            (.preventDefault e)))
                        (catch :default err
                          (js/console.error "[LongPress Debug] Crash in on-touch-end. !long-press-state:" !long-press-state)
                          (throw err))))

   :on-touch-cancel (fn [_] (clear-long-press!))})

(defonce !redraw-timer (atom nil))

(defn request-batched-redraw! [room-id]
  (go
    (when-let [timer @!redraw-timer]
      (js/clearTimeout timer))
    (reset! !redraw-timer
            (js/setTimeout
             #(do
                (mesh/do-with-thread! :virtualizer-pool
                                      {:handler :recalculate-timeline
                                       :arguments {:room-id room-id}}))
             50))))


(defonce !pending-redraw-items (atom {}))

(defn request-item-redraw! [room-id item-id]
  (swap! !pending-redraw-items update room-id (fnil conj #{}) item-id)

  (when-let [timer @!redraw-timer]
    (js/clearTimeout timer))

  (reset! !redraw-timer
          (js/setTimeout
           #(let [batches @!pending-redraw-items]
              (reset! !pending-redraw-items {})
              (doseq [[r-id i-ids] batches]
                (mesh/do-with-thread! :virtualizer-pool
                                      {:handler :recalculate-items
                                       :arguments {:room-id r-id
                                                   :item-ids (vec i-ids)}})))
           50)))

(def avatar-colors
  ["#5865f2" "#3ba55c" "#faa61a" "#ed4245" "#eb459e" "#9b59b6"])

(defn get-avatar-color [id]
  (if-not id
    (first avatar-colors)
    (let [hash (reduce #(+ %1 (.charCodeAt %2 0)) 0 (str id))]
      (nth avatar-colors (mod hash (count avatar-colors))))))

;; Hacky fix, but I need to clean the 'shape' up anyway haha
(defn kname [arg]
  (name arg))

(defonce !broken-urls (r/atom #{}))

(defn get-avatar-color
  ([id]
   (if-not id
     (first avatar-colors)
     (let [hash (reduce #(+ %1 (.charCodeAt %2 0)) 0 (str id))]
       (nth avatar-colors (mod hash (count avatar-colors))))))
  ([id fallback]
   (get-avatar-color (or id fallback))))

(defn avatar [{:keys [id name url size status shape] :as props :or {size 32 shape :circle}}]
  (let [url-str     (when url (str url))
        broken?     (and url-str (contains? @!broken-urls url-str))
        show-image? (and url-str (not (str/blank? url-str)) (not broken?))
        len         (count name)
        target      (if (#{:space :squircle :square} shape) 2 1)
        initials    (if (pos? len) (subs name 0 (min len target)) "?")
        bg-color    (if (= shape :none) "transparent" (get-avatar-color id name))
        local-url?  (and url-str (or (str/starts-with? url-str "blob:")
                                     (str/starts-with? url-str "data:")))
        thumb-size  (if (<= size 48) 96 256)
        dom-props   (dissoc props :id :name :url :size :status :shape)]
    (into [:div.avatar-wrapper
           (merge dom-props
                  {:class (kname shape)
                   :style (merge (:style dom-props) {:width size :height size})})]
          [(when-not show-image?
             [:div.avatar-placeholder-layer
              {:style {:background-color bg-color}}
              (when-not (= shape :none)
                [:span.avatar-text
                 {:style {:font-size (str (/ size (if (= shape :circle) 2.2 2.5)) "px")}}
                 initials])])

           (when show-image?
             (if local-url?
               [:img.avatar-img-layer
                {:src      url-str
                 :alt      ""
                 :on-error #(swap! !broken-urls conj url-str)}]
               [media
                {:uri      url-str
                 :width    thumb-size
                 :height   thumb-size
                 :method   "crop"
                 :class    "avatar-img-layer"
                 :alt      ""
                 :on-error #(swap! !broken-urls conj url-str)}]))

           (when status
             [:div.avatar-status {:class (kname status)}])])))


(defonce !swipe-drag-data (atom {:start-x nil :action nil :fg-node nil :bg-node nil :reply-node nil :edit-node nil}))


(defn make-swipe-handlers
  "Abstracts pointer event tracking for horizontal swipes.
   Safe for main-thread Reagent components.
   !drag-state should be an atom containing {:start-x nil :dx 0}."
  [!drag-state {:keys [on-start on-move on-end]}]
  (let [handle-ptr-down
        (fn [e]
          (when (= (.-button e) 0)
            (.setPointerCapture (.-target e) (.-pointerId e))
            (let [x (.-clientX e)]
              (reset! !drag-state {:start-x x :dx 0})
              (when (fn? on-start) (on-start x)))))

        handle-ptr-move
        (fn [e]
          (let [{:keys [start-x]} @!drag-state]
            (when start-x
              (let [dx (- (.-clientX e) start-x)]
                (swap! !drag-state assoc :dx dx)
                (when (fn? on-move) (on-move dx))))))

        handle-ptr-up
        (fn [e]
          (let [{:keys [start-x dx]} @!drag-state]
            (when start-x
              (.releasePointerCapture (.-target e) (.-pointerId e))
              (when (fn? on-end) (on-end dx))
              (reset! !drag-state {:start-x nil :dx 0}))))]

    {:on-pointer-down   handle-ptr-down
     :on-pointer-move   handle-ptr-move
     :on-pointer-up     handle-ptr-up
     :on-pointer-cancel handle-ptr-up}))

(defn handle-swipe-down! [e]
  (when (= (.-button e) 0)
    (let [fg         (.-currentTarget e)
          wrapper    (.-parentNode fg)
          bg         (.querySelector wrapper ".swipe-action-bg")
          reply-el   (.querySelector bg ".reply-action")
          edit-el    (.querySelector bg ".edit-action")]
      (.setPointerCapture fg (.-pointerId e))
      (set! (.-transition (.-style fg)) "none")
      (set! (.-userSelect (.-style fg)) "none")
      (when bg (set! (.-opacity (.-style bg)) "1"))
      (reset! !swipe-drag-data {:start-x (.-clientX e)
                                :action nil
                                :fg-node fg
                                :bg-node bg
                                :reply-node reply-el
                                :edit-node edit-el}))))

(defn handle-swipe-move! [e can-edit? edit-threshold reply-threshold]
  (let [{:keys [start-x fg-node bg-node reply-node edit-node]} @!swipe-drag-data]
    (when start-x
      (let [dx         (- (.-clientX e) start-x)
            pull-dist  (- dx)
            bounded-dx (max 0 (min pull-dist 180))
            new-action (cond
                         (and can-edit? (> bounded-dx edit-threshold)) :edit
                         (> bounded-dx reply-threshold)                :reply
                         :else                                         nil)]
        (swap! !swipe-drag-data assoc :action new-action)
        (when fg-node
          (set! (.-transform (.-style fg-node)) (str "translateX(-" bounded-dx "px)")))
        (when reply-node
          (set! (.-display (.-style reply-node)) (if (= new-action :reply) "flex" "none")))
        (when edit-node
          (set! (.-display (.-style edit-node)) (if (= new-action :edit) "flex" "none")))))))

(defn handle-swipe-up! [e on-action]
  (let [{:keys [start-x action fg-node bg-node]} @!swipe-drag-data]
    (when start-x
      (.releasePointerCapture (.-target e) (.-pointerId e))
      (when (and action (fn? on-action))
        (on-action action))
      (reset! !swipe-drag-data {:start-x nil :action nil :fg-node nil :bg-node nil :reply-node nil :edit-node nil})
      (when fg-node
        (set! (.-transform (.-style fg-node)) "translateX(0px)")
        (set! (.-transition (.-style fg-node)) "transform 0.25s cubic-bezier(0.2, 0.8, 0.2, 1)")
        (set! (.-userSelect (.-style fg-node)) "auto"))
      (when bg-node
        (set! (.-opacity (.-style bg-node)) "0")))))

(defn swipe-to-action-wrapper [{:keys [can-edit? on-action wrapper-props enabled?]} & children]
  (if-not enabled?
    (into [:div.swipe-foreground wrapper-props] children)
    (let [reply-threshold 50
          edit-threshold  140]
      [:div.timeline-swipe-wrapper
       {:style {:position "relative" :overflow "hidden"}}
       [:div.swipe-action-bg
        {:style {:position "absolute" :right 0 :top 0 :bottom 0 :width "100%"
                 :display "flex" :align-items "center" :justify-content "flex-end" :padding-right "16px"
                 :opacity 0
                 :z-index 0}}
        [:div.reply-action
         {:style {:display "none" :align-items "center" :color "var(--text-muted, #888)" :font-weight "bold" :font-size "0.85rem"}}
         [:span "Reply "] [icons/reply]]
        [:div.edit-action
         {:style {:display "none" :align-items "center" :color "var(--brand-experiment, #5865f2)" :font-weight "bold" :font-size "0.85rem"}}
         [:span [icons/edit] " Edit"]]]
       (into [:div.swipe-foreground
              (merge wrapper-props
                     {:on-pointer-down   #(handle-swipe-down! %)
                      :on-pointer-move   #(handle-swipe-move! % can-edit? edit-threshold reply-threshold)
                      :on-pointer-up     #(handle-swipe-up! % on-action)
                      :on-pointer-cancel #(handle-swipe-up! % on-action)
                      :style             {:position "relative"
                                          :z-index 1
                                          :background "var(--background-primary, #313338)"
                                          :touch-action "pan-y"}})]
             children)])))

(defn handle-list-navigation
  [^js e items current-index set-index-fn on-select-fn]
  (let [key   (.-key e)
        limit (count items)]
    (if (and (#{"ArrowUp" "ArrowDown" "Enter" "Tab"} key) (pos? limit))
      (do
        (case key
          "ArrowUp"   (set-index-fn (mod (dec current-index) limit))
          "ArrowDown" (set-index-fn (mod (inc current-index) limit))
          ("Enter" "Tab") (on-select-fn (nth items current-index)))
        (.preventDefault e)
        (.stopPropagation e)
        true)
      false)))

(defn selectable-list
  [{:keys [items selected-index on-select on-highlight key-fn render-item item-class empty-text]}]
  (if (empty? items)
    [:div.selectable-list-empty (or empty-text "No results found.")]
    [:div.selectable-list-container
     (doall
      (map-indexed
       (fn [idx item]
         (let [selected? (= idx selected-index)]
           ^{:key (key-fn item)}
           [:div
            {:class [(or item-class "selectable-item") (when selected? "is-selected")]
             :ref (fn [el]
                    (when (and selected? el)
                      (.scrollIntoView el #js {:block "nearest" :behavior "auto"})))
             :on-mouse-enter #(when on-highlight (on-highlight idx))
             :on-mouse-down (fn [e]
                              (.preventDefault e)
                              (.stopPropagation e)
                              (on-select item))}
            (render-item item selected?)]))
       items))]))