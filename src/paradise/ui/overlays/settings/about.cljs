(ns paradise.ui.overlays.settings.about
  (:require
   [paradise.shared.utils.macros :refer [config]]
   [re-frame.core :as re-frame]))

(defn ^:ui about-tab []
  (let [tr       @(re-frame/subscribe [:i18n/tr])
        version  @(re-frame/subscribe [:app/version])
        app-name (:app-name config "Matrix Client")]
    [:div.settings-tab-content
     [:div.settings-page-header
      [:h2.settings-heading
       (tr [:settings.about/title])]]

     [:div.settings-section
      [:div.settings-inline-header
       [:h3.settings-subheading app-name]
       [:div.settings-badge
        (str "v" version)]]

      [:p.settings-description
       (tr [:settings.about/description]
           [app-name])]

      [:h3.settings-subheading
       (tr [:settings.about/updates-title])]

      [:p.settings-description
       (tr [:settings.about/updates-desc])]

      [:div.settings-actions
       [:button.settings-button
        {:on-click #(re-frame/dispatch
                     [:app/poll-version true])}
        (tr [:settings.about/check-updates])]]]]))

(defn ^:ui credits-tab []
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:div.settings-tab-content
     [:div.settings-page-header
      [:h2.settings-heading
       (tr [:settings.credits/title])]]

     [:div.settings-section
      [:h3.settings-subheading
       (tr [:settings.credits/art-label])]
      [:div.settings-description
       (tr [:settings.credits/art-thanks])]

      [:h3.settings-subheading
       (tr [:settings.credits/code-label])]
      [:div.settings-description
       (tr [:settings.credits/code-thanks])]

      [:h3.settings-subheading
       (tr [:settings.credits/special-label])]
      [:div.settings-description
       (tr [:settings.credits/special-thanks])]]]))

