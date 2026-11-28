(ns paradise.ui.container.base
  (:require
   [re-frame.core :as re-frame]
   [paradise.ui.container.call.events]
   [paradise.ui.container.call.call-view :refer [call-view]]
   [paradise.ui.container.discovery :refer [discovery-view]]
   [paradise.ui.container.timeline.base :refer [timeline]]
   [paradise.ui.container.members :refer [member-list]]
   [paradise.ui.container.search :refer [search]]
   [paradise.ui.container.pins :refer [pins]]
   [paradise.ui.container.threads :refer [threads-forum-view threads-panel]]
   [paradise.shared.utils.svg :as icons]
   [taoensso.timbre :as log]
   [promesa.core :as p]
   [reagent.core :as r]
   [reagent.dom.client :as rdom]
   [paradise.shared.plugins :as plugins]))

(defn ^:ui main-content []
  (let [active-room @(re-frame/subscribe [:rooms/active-id])
        main-focus  @(re-frame/subscribe [:container/main-focus])]
    [:div.main-content
     (case main-focus
       :call      [call-view active-room]
       :thread    [threads-forum-view active-room]
       :discovery [discovery-view]
       :timeline  [timeline])]))

(defn ^:ui side-content [side-panel]
  (let [side-panel    @(re-frame/subscribe [:container/side-panel])
        active-room   @(re-frame/subscribe [:rooms/active-id])
        active-thread @(re-frame/subscribe [:threads/active-thread active-room])
        tr            @(re-frame/subscribe [:i18n/tr])]
    (when-let [panel side-panel]
      [:div.sidebar
       (when (contains? #{:members :threads :search :pins :timeline} panel)
         [:div.sidebar-header
          (if (and (= panel :threads) active-thread)
            [:div.sidebar-header-thread-nav
             [:button.sidebar-back-btn
              {:type     "button"
               :title    (tr [:container.panels/threads])
               :on-click #(re-frame/dispatch [:threads/back-to-list active-room])}
              [icons/arrow-left {:width "18px" :height "18px"}]]
             [:span.sidebar-thread-title
              (or (not-empty (:title active-thread))
                  (tr [:container.threads/untitled]))]]
            [:h3.sidebar-title
             (case panel
               :members  (tr [:container.panels/members])
               :threads  (tr [:container.panels/threads])
               :search   (tr [:container.panels/search])
               :pins     (tr [:container.panels/pins])
               :timeline "")])
          [:button.sidebar-close
           {:on-click #(if (= panel :threads)
                         (re-frame/dispatch [:threads/toggle-panel active-room])
                         (re-frame/dispatch [:container/set-side-panel nil]))}
           [icons/exit]]])

       [:div.sidebar-content
        (case panel
          :timeline [timeline :compact? true :hide-header? true]
          :members  [member-list]
          :threads  [threads-panel]
          :search   [search]
          :pins     [pins])]])))

(defn ^:ui container []
  (let [side-panel @(re-frame/subscribe [:container/side-panel])]
    [:div.room-layout
     {:class (when side-panel "with-sidebar")}
     [main-content]
     [side-content side-panel]]))