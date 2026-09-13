(ns paradise.ui.container.reusable
  (:require [re-frame.core :as re-frame]
            [paradise.ui.container.timeline.item :refer [event-tile-render]]
            [paradise.shared.utils.svg :as icons]))


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

(defn ^:ui room-header [{:keys [display-name compact? active-id]}]
  (let [tr            @(re-frame/subscribe [:i18n/tr])
        main-focus    @(re-frame/subscribe [:container/main-focus])
        side-panel    @(re-frame/subscribe [:container/side-panel])
        call-active?  @(re-frame/subscribe [:call/is-active?])
        room-meta     @(re-frame/subscribe [:rooms/active-metadata])
        final-name    (or (:name room-meta) display-name active-id)
        is-dm?        (:isDirect room-meta)
        is-space?     (:isSpace room-meta)
        in-lobby?     (and (= main-focus :timeline) call-active?)
        show-actions? (or (not compact?) (= main-focus :call))
        membership    (or (:membership room-meta) "join")]
    [:div.room-header
     [:div.header-left
      [:button.mobile-menu-btn {:on-click #(re-frame/dispatch [:ui/toggle-sidebar])}
       [icons/arrow-left]]
      (when final-name
        [:h2.room-header-name final-name])]
     (when (and show-actions? active-id)
       [:div.header-actions
        (when is-dm?
          [:button.header-icon-btn
           {:title
            (if in-lobby?
              (tr [:container.header/join-call])
              (tr [:container.header/start-call]))
            :class (when (or (= main-focus :call) call-active?) "active-green")
            :on-click (fn [_]
                        (re-frame/dispatch [:call/init-widget active-id])
                        (re-frame/dispatch [:container/set-main-focus :call]))}
           [icons/phone]])

        (when (= main-focus :call)
          [:button.header-icon-btn
           {:title (tr [:container.header/room-chat])
            :class (when (= side-panel :timeline) "active")
            :on-click #(re-frame/dispatch [:container/set-side-panel (if (= side-panel :timeline) nil :timeline)])}
           [icons/chat-bubble]])

        [:button.header-icon-btn
         {:title (tr [:container.header/search])
          :class (when (= side-panel :search) "active")
          :on-click #(re-frame/dispatch [:container/set-side-panel :search])}
         [icons/search]]

        [:button.header-icon-btn
         {:title (tr [:container.header/pinned-messages])
          :class (when (= side-panel :pins) "active")
          :on-click (fn []
                      (re-frame/dispatch [:container/set-side-panel :pins])
                      (re-frame/dispatch [:room/fetch-pinned-events active-id]))}
         [icons/pins {:animate :sink}]]

        (when-not is-dm?
          [:button.header-icon-btn.hide-on-mobile
           {:title (tr [:container.header/member-list])
            :class (when (= side-panel :members) "active")
            :on-click #(re-frame/dispatch [:container/set-side-panel :members])}
           [icons/members]])

        [:button.header-icon-btn
         {:title (tr [:container.header/more-options])
          :on-click (fn [e]
                      (let [mx (.-clientX e)
                            my (.-clientY e)]
                        (re-frame/dispatch
                         [:ui/open-context-menu :room-actions
                          {:x mx
                           :y my
                           :room-id active-id
                           :room-name final-name
                           :is-space? is-space?
                           :is-dm? is-dm?
                           :context :header
                           :membership membership
                           :parent-id nil}])))}
         [icons/more-vertical]]])]))


(defn ^:ui message-preview-item [room-id event]
  (let [tr         @(re-frame/subscribe [:i18n/tr])
        hs-url           @(re-frame/subscribe [:sdk/homeserver-url])
        is-mobile?       @(re-frame/subscribe [:ui/mobile?])
        my-profile       @(re-frame/subscribe [:sdk/profile])
        my-id            (:user-id my-profile)]
    [:div.message-preview-card
     [event-tile-render event hs-url is-mobile? my-id tr]
     [:button.jump-btn
      {:on-click #(re-frame/dispatch [:room/pretty-jump room-id (:id event)])}
      (tr [:container/jump-to-message])]]))
