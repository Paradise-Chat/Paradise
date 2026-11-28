(ns paradise.ui.overlays.settings.notifications
  (:require
   [paradise.shared.utils.svg :as icons]
   [re-frame.core :as re-frame]))

(defn ^:ui notifications-tab []
  (let [tr              @(re-frame/subscribe [:i18n/tr])
        status          @(re-frame/subscribe [:push/status])
        current-user    @(re-frame/subscribe [:auth/active-user-id])
        push-owner      @(re-frame/subscribe [:push/active-user])
        is-active-here? (and (= status :enabled)
                             (= current-user push-owner))
        enabled?        @(re-frame/subscribe [:push/previews-enabled?])
        toasts-on?      @(re-frame/subscribe [:notif/toasts-enabled?])
        sound-on?       @(re-frame/subscribe [:notif/sound-enabled?])
        sound-preset    @(re-frame/subscribe [:notif/sound-preset])
        sound-volume    @(re-frame/subscribe [:notif/sound-volume])
        presets         [[:chime  :settings.notifications.presets/chime]
                         [:pop    :settings.notifications.presets/pop]
                         [:bell   :settings.notifications.presets/bell]
                         [:subtle :settings.notifications.presets/subtle]
                         [:none   :settings.notifications.presets/none]]]
    [:div.settings-tab-content
     [:p.settings-subview-lead
      (tr [:settings.notifications/description])]

     [:div.settings-hub-group
      [:div.settings-hub-group-label
       (tr [:settings.notifications/in-app-heading])]
      [:div.settings-hub-group-card
       [:div.settings-card-row.is-toggle
        [:div.settings-row-text
         [:div.settings-row-title
          (tr [:settings.notifications/in-app-toasts])]
         [:div.settings-row-description
          (tr [:settings.notifications/in-app-toasts-desc])]]
        [:label.settings-toggle
         [:input
          {:type      "checkbox"
           :checked   (boolean toasts-on?)
           :on-change #(re-frame/dispatch
                        [:notif/set-toasts-enabled
                         (.. % -target -checked)])}]
         [:div.settings-toggle-track
          [:div.settings-toggle-knob]]]]

       [:div.settings-card-row.is-toggle
        [:div.settings-row-text
         [:div.settings-row-title
          (tr [:settings.notifications/in-app-sounds])]
         [:div.settings-row-description
          (tr [:settings.notifications/in-app-sounds-desc])]]
        [:label.settings-toggle
         [:input
          {:type      "checkbox"
           :checked   (boolean sound-on?)
           :on-change #(re-frame/dispatch
                        [:notif/set-sound-enabled
                         (.. % -target -checked)])}]
         [:div.settings-toggle-track
          [:div.settings-toggle-knob]]]]

       (when sound-on?
         [:<>
          [:div.settings-card-row
           [:div.settings-row-text
            [:div.settings-row-title
             (tr [:settings.notifications/sound-preset])]
            [:div.settings-row-description
             (tr [:settings.notifications/sound-preset-desc])]]
           [:div.settings-actions {:style {:flex-wrap "nowrap"}}
            [:select.settings-input
             {:style     {:width "auto" :min-width "120px" :padding "7px 10px"}
              :value     (name (or sound-preset :chime))
              :on-change (fn [e]
                           (let [preset (keyword (.. e -target -value))]
                             (re-frame/dispatch [:notif/set-sound-preset preset])
                             (when (not= preset :none)
                               (re-frame/dispatch [:notif/test-sound preset]))))}
             (for [[pid lbl-key] presets]
               ^{:key (name pid)}
               [:option {:value (name pid)} (tr [lbl-key])])]
            [:button.settings-button.secondary.is-compact
             {:type     "button"
              :disabled (= sound-preset :none)
              :on-click #(re-frame/dispatch [:notif/test-sound sound-preset])}
             (tr [:settings.notifications/sound-test])]]]

          [:div.settings-card-row
           [:div.settings-row-text
            [:div.settings-row-title
             (tr [:settings.notifications/sound-volume])
             [:span.settings-hub-badge (str sound-volume "%")]]
            [:div.settings-row-description
             (tr [:settings.notifications/sound-volume-desc])]]
           [:input
            {:type      "range"
             :min       0
             :max       100
             :step      5
             :value     sound-volume
             :style     {:width "140px" :accent-color "#5865f2" :cursor "pointer"}
             :on-change #(re-frame/dispatch
                          [:notif/set-sound-volume
                           (js/parseInt (.. % -target -value) 10)])}]]])]]

     (when is-active-here?
       [:div.settings-banner.success
        [:div.settings-banner-icon
         [icons/check-circle-green]]
        [:div.settings-banner-copy
         [:div.settings-banner-title
          (tr [:settings.notifications.status/active-title])]
         [:div.settings-banner-description
          (tr [:settings.notifications.status/active-subtitle])]]])

     (when (and push-owner
                (not= current-user push-owner))
       [:div.settings-banner.warning
        [:div.settings-banner-copy
         [:div.settings-banner-title
          (tr [:settings.notifications.warning/another-active])]
         [:div.settings-banner-description
          (tr [:settings.notifications.warning/swap-active]
              [push-owner])]]])

     [:div.settings-hub-group
      [:div.settings-hub-group-label
       (tr [:settings.notifications/title])]
      [:div.settings-hub-group-card
       [:div.settings-card-row.is-toggle
        [:div.settings-row-text
         [:div.settings-row-title
          (tr [:settings.notifications/show-previews])]
         [:div.settings-row-description
          (tr [:settings.notifications/show-previews-description])]]
        [:label.settings-toggle
         [:input
          {:type      "checkbox"
           :checked   enabled?
           :on-change #(re-frame/dispatch
                        [:push/toggle-previews
                         (.. % -target -checked)])}]
         [:div.settings-toggle-track
          [:div.settings-toggle-knob]]]]

       [:div.settings-card-row
        [:div.settings-actions
         (when-not is-active-here?
           [:button.settings-button
            {:on-click #(re-frame/dispatch [:push/enable])}
            (if push-owner
              (tr [:settings.notifications.warning/confirm-swap])
              (tr [:settings.notifications/enable-btn]))])

         (when push-owner
           [:button.settings-button.destructive
            {:on-click #(re-frame/dispatch [:push/disable])}
            (tr [:settings.notifications/disable-btn])])

         [:button.settings-button.secondary
          {:on-click #(re-frame/dispatch [:push/clear-all])}
          (tr [:settings.notifications/clear-btn])]]]]]]))