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
        enabled?        @(re-frame/subscribe [:push/previews-enabled?])]
    [:div.settings-tab-content
     [:div.settings-page-header
      [:h2.settings-heading
       (tr [:settings.notifications/title])]
      [:p.settings-description
       (tr [:settings.notifications/description])]]

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

     [:div.settings-section
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

       [:button.settings-button.destructive
        {:on-click #(re-frame/dispatch [:push/clear-all])}
        (tr [:settings.notifications/clear-btn])]]

      [:div.settings-row.is-toggle
       [:div.settings-row-text
        [:div.settings-row-title
         (tr [:settings.notifications/show-previews])]
        [:div.settings-row-description
         (tr [:settings.notifications/show-previews-description])]]
       [:label.settings-toggle
        [:input
         {:type "checkbox"
          :checked enabled?
          :on-change #(re-frame/dispatch
                       [:push/toggle-previews
                        (.. % -target -checked)])}]
        [:div.settings-toggle-track
         [:div.settings-toggle-knob]]]]]]))
