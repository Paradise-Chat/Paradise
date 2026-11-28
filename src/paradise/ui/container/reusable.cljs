(ns paradise.ui.container.reusable
  (:require [clojure.string :as str]
            [paradise.shared.utils.helpers :refer [date-day-key normalize-ts-ms]]
            [paradise.shared.utils.svg :as icons]
            [paradise.ui.container.timeline.item :refer [date-divider event-tile-render]]
            [paradise.ui.global :refer [avatar]]
            [re-frame.core :as re-frame]))


(re-frame/reg-event-db
 :container/set-main-focus
 (fn [db [_ focus]]
   (assoc db :main-focus focus)))

(re-frame/reg-event-db
 :container/set-side-panel
 (fn [db [_ panel]]
   (assoc db :side-panel panel)))

(re-frame/reg-sub
 :container/main-focus
 (fn [db _]
   (:main-focus db :timeline)))

(re-frame/reg-sub
 :container/side-panel
 (fn [db _]
   (:side-panel db nil)))

(defn- room-header-prefix [{:keys [active-id final-name room-meta main-focus is-dm? is-group-dm?]}]
  (let [avatar-url (or (:avatar-url room-meta) (:avatar room-meta))]
    (cond
      is-dm?
      [:span.room-header-prefix.is-avatar
       [avatar {:id        active-id
                :name      (or final-name "?")
                :url       avatar-url
                :size      24
                :is-group? is-group-dm?}]]

      (or (= main-focus :call) (:isCall room-meta) (:is-call? room-meta))
      [:span.room-header-prefix [icons/speaker {:width "18px" :height "18px"}]]

      (or (= main-focus :thread) (:isForum room-meta) (:is-forum? room-meta))
      [:span.room-header-prefix [icons/thread {:width "18px" :height "18px"}]]

      :else
      [:span.room-header-prefix [icons/hash {:width "18px" :height "18px"}]])))

(defn ^:ui room-header [{:keys [display-name compact? active-id]}]
  (let [tr            @(re-frame/subscribe [:i18n/tr])
        main-focus    @(re-frame/subscribe [:container/main-focus])
        side-panel    @(re-frame/subscribe [:container/side-panel])
        call-active?  @(re-frame/subscribe [:call/is-active?])
        room-meta     @(re-frame/subscribe [:rooms/active-metadata])
        final-name    (or (:name room-meta) display-name active-id)
        raw-topic     (some-> (:topic room-meta) str str/trim not-empty)
        is-dm?        (boolean (or (:isDirect room-meta) (:is-direct? room-meta)))
        is-group-dm?  (boolean (or (:isGroupDm room-meta) (:is-group-dm? room-meta)))
        is-space?     (boolean (or (:isSpace room-meta) (:is-space? room-meta)))
        in-lobby?     (and (= main-focus :timeline) call-active?)
        show-actions? (or (not compact?) (= main-focus :call))
        membership    (or (:membership room-meta) "join")]
    [:header.room-header {:class (when compact? "is-compact")}
     [:div.header-left
      [:button.mobile-menu-btn
       {:type     "button"
        :on-click #(re-frame/dispatch [:ui/toggle-sidebar])}
       [icons/arrow-left {:width "20px" :height "20px"}]]
      (when active-id
        [room-header-prefix {:active-id    active-id
                             :final-name   final-name
                             :room-meta    room-meta
                             :main-focus   main-focus
                             :is-dm?       is-dm?
                             :is-group-dm? is-group-dm?}])
      (when final-name
        [:h2.room-header-name final-name])
      (when (and raw-topic (not compact?))
        [:<>
         [:span.header-title-divider]
         [:span.room-header-topic {:title raw-topic} raw-topic]])]
     (when (and show-actions? active-id)
       [:div.header-actions
        (when (or is-dm? (= main-focus :call))
          [:<>
           [:div.header-action-group
            (when is-dm?
              [:button.header-icon-btn
               {:type     "button"
                :title    (if in-lobby?
                            (tr [:container.header/join-call])
                            (tr [:container.header/start-call]))
                :class    (when (or (= main-focus :call) call-active?) "active-green")
                :on-click (fn [_]
                            (re-frame/dispatch [:call/init-widget active-id])
                            (re-frame/dispatch [:container/set-main-focus :call]))}
               [icons/phone {:width "18px" :height "18px"}]])

            (when (= main-focus :call)
              [:button.header-icon-btn
               {:type     "button"
                :title    (tr [:container.header/room-chat])
                :class    (when (= side-panel :timeline) "active")
                :on-click #(re-frame/dispatch [:container/set-side-panel
                                               (if (= side-panel :timeline) nil :timeline)])}
               [icons/chat-bubble {:width "18px" :height "18px"}]])]
           [:span.header-actions-divider]])

        [:div.header-action-group
         [:button.header-icon-btn
          {:type     "button"
           :title    (tr [:container.header/search])
           :class    (when (= side-panel :search) "active")
           :on-click #(re-frame/dispatch [:container/set-side-panel
                                          (if (= side-panel :search) nil :search)])}
          [icons/search {:width "18px" :height "18px"}]]

         (when (and (or (not is-dm?) is-group-dm?)
                    (not= main-focus :thread))
           [:button.header-icon-btn
            {:type     "button"
             :title    (tr [:container.header/threads])
             :class    (when (= side-panel :threads) "active")
             :on-click #(re-frame/dispatch [:threads/toggle-panel active-id])}
            [icons/thread {:width "18px" :height "18px"}]])

         [:button.header-icon-btn
          {:type     "button"
           :title    (tr [:container.header/pinned-messages])
           :class    (when (= side-panel :pins) "active")
           :on-click (fn []
                       (re-frame/dispatch [:container/set-side-panel
                                           (if (= side-panel :pins) nil :pins)])
                       (when-not (= side-panel :pins)
                         (re-frame/dispatch [:room/fetch-pinned-events active-id])))}
          [icons/pins {:animate :sink :width "18px" :height "18px"}]]

         (when (or (not is-dm?) is-group-dm?)
           [:button.header-icon-btn.hide-on-mobile
            {:type     "button"
             :title    (tr [:container.header/member-list])
             :class    (when (= side-panel :members) "active")
             :on-click #(re-frame/dispatch [:container/set-side-panel
                                            (if (= side-panel :members) nil :members)])}
            [icons/members {:width "18px" :height "18px"}]])]

        [:span.header-actions-divider]

        [:button.header-icon-btn
         {:type     "button"
          :title    (tr [:container.header/more-options])
          :on-click (fn [e]
                      (let [mx (.-clientX e)
                            my (.-clientY e)]
                        (re-frame/dispatch
                         [:ui/open-context-menu :room-actions
                          {:x          mx
                           :y          my
                           :room-id    active-id
                           :room-name  final-name
                           :is-space?  is-space?
                           :is-dm?     is-dm?
                           :context    :header
                           :membership membership
                           :parent-id  nil}])))}
         [icons/more-vertical {:width "18px" :height "18px"}]]])]))


(defn- event-ts-ms [ev]
  (normalize-ts-ms
   (or (:ts ev) (:timestamp ev) (:origin-server-ts ev) (:origin_server_ts ev))))

(defn ^:ui message-preview-item
  ([room-id event]
   [message-preview-item room-id event nil])
  ([room-id event {:keys [show-room-badge?]}]
   (let [tr             @(re-frame/subscribe [:i18n/tr])
         rooms-map      @(re-frame/subscribe [:rooms/unfiltered-indexed-map])
         target-room-id (or (:room-id event) (:room_id event) room-id)
         cross-room?    (and target-room-id room-id (not= target-room-id room-id))
         room-meta      (when (or show-room-badge? cross-room?)
                          (get rooms-map target-room-id))
         room-label     (when (or show-room-badge? cross-room?)
                          (let [nm (or (:name room-meta) (:room-name event) target-room-id)]
                            (if (:isDirect room-meta)
                              (str "@ " nm)
                              (str "# " nm))))]
     [:div.message-preview-card
      (when room-label
        [:div.message-preview-room-badge
         {:on-click #(re-frame/dispatch [:room/pretty-jump target-room-id (:id event)])}
         room-label])
      [event-tile-render event]
      [:button.jump-btn
       {:on-click #(re-frame/dispatch [:room/pretty-jump target-room-id (:id event)])}
       (tr [:container/jump-to-message])]])))

(defn ^:ui message-preview-list
  ([active-room events]
   [message-preview-list active-room events nil])
  ([active-room events {:keys [show-room-badge? sort-by-ts-desc? class]}]
   (let [ordered (if sort-by-ts-desc?
                   (sort-by #(or (event-ts-ms %) 0) > events)
                   events)
         nodes   (first
                  (reduce
                   (fn [[acc prev-day idx] ev]
                     (let [ts       (event-ts-ms ev)
                           day-key  (date-day-key ts)
                           hit-room (or (:room-id ev) (:room_id ev) active-room)
                           row-key  (str hit-room "::" (or (:id ev) idx))
                           acc'     (if (and day-key (not= day-key prev-day))
                                      (conj acc
                                            ^{:key (str "date-" idx "-" day-key)}
                                            [date-divider {:ts ts :variant :preview}])
                                      acc)]
                       [(conj acc'
                              ^{:key row-key}
                              [message-preview-item
                               active-room
                               ev
                               {:show-room-badge? show-room-badge?}])
                        (or day-key prev-day)
                        (inc idx)]))
                   [[] nil 0]
                   ordered))]
     (into [:div {:class class}] nodes))))