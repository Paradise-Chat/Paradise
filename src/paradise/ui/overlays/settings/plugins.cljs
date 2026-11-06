(ns paradise.ui.overlays.settings.plugins
  (:require
   [clojure.string :as str]
   [paradise.shared.utils.svg :as icons]
   [re-frame.core :as re-frame]
   [reagent.core :as r]))

(defn ^:ui plugins-installer []
  (r/with-let [plugin-url-input (r/atom "")
               theme-url-input  (r/atom "")]
    (let [tr               @(re-frame/subscribe [:i18n/tr])
          installed-urls   (set @(re-frame/subscribe [:plugins/installed-urls]))
          active-plugins   @(re-frame/subscribe [:plugins/active-list])
          active-ids       (set (map :id active-plugins))
          registry-plugins @(re-frame/subscribe [:plugins/registry-plugins])
          disabled-urls    @(re-frame/subscribe [:plugins/disabled-urls])
          current-theme    @(re-frame/subscribe [:ui/current-theme])
          custom-theme?    (and (string? current-theme)
                                (str/starts-with? current-theme "http"))]
      [:<>
       [:div.settings-section
        [:h3.settings-subheading
         (tr [:settings.plugins/themes-title])]
        [:div.settings-row
         [:input.form-input.plugin-input
          {:type "text"
           :placeholder (tr [:settings.plugins/placeholder-theme-url])
           :value @theme-url-input
           :on-change #(reset! theme-url-input (.. % -target -value))}]
         [:div.settings-actions
          [:button.settings-button
           {:disabled (empty? @theme-url-input)
            :on-click #(do
                         (re-frame/dispatch [:ui/switch-theme @theme-url-input])
                         (reset! theme-url-input ""))}
           (tr [:settings.plugins/apply])]]]

        (when custom-theme?
          [:div.settings-row
           [:div.settings-row-text
            [:div.settings-row-title
             (tr [:settings.plugins/active-custom-theme])]
            [:div.settings-row-description
             current-theme]]
           [:div.settings-actions
            [:button.settings-button.destructive
             {:on-click #(re-frame/dispatch [:ui/reset-theme])}
             (tr [:settings.plugins/reset-default])]]])]

       [:div.settings-section
        [:h3.settings-subheading
         (tr [:settings.plugins/plugins-title])]
        [:div.settings-row
         [:input.form-input.plugin-input
          {:type "text"
           :placeholder (tr [:settings.plugins/placeholder-plugin-url])
           :value @plugin-url-input
           :on-change #(reset! plugin-url-input (.. % -target -value))}]
         [:div.settings-actions
          [:button.settings-button
           {:disabled (empty? @plugin-url-input)
            :on-click #(do
                         (re-frame/dispatch [:plugins/install-from-url @plugin-url-input])
                         (reset! plugin-url-input ""))}
           (tr [:settings.plugins/install])]]]

        (when (seq active-plugins)
          [:<>
           [:h3.settings-subheading
            (tr [:settings.plugins/active-plugins])]
           (for [plugin active-plugins]
             (let [url         (:source-url plugin)
                   provided-by (:provided-by plugin)
                   enabled?    (not (contains? disabled-urls url))]
               ^{:key (:id plugin)}
               [:div.settings-row.is-toggle
                [:div.settings-row-text
                 [:div.settings-row-title
                  (:name plugin (tr [:settings.plugins/unknown-plugin]))
                  (str " v" (:version plugin "1.0.0"))
                  (when provided-by
                    (str " (" (name provided-by) ")"))]
                 [:div.settings-row-description
                  (:description plugin (tr [:settings.plugins/no-description]))]]
                [:div.settings-actions
                 [:label.settings-toggle
                  [:input
                   {:type "checkbox"
                    :checked enabled?
                    :on-change #(re-frame/dispatch
                                 [:plugins/toggle-plugin url (.. % -target -checked)])}]
                  [:div.settings-toggle-track
                   [:div.settings-toggle-knob]]]
                 (when-not provided-by
                   [:button.settings-button.destructive
                    {:title (tr [:settings.plugins/uninstall-plugin])
                     :on-click #(re-frame/dispatch [:plugins/uninstall-url url])}
                    [icons/trash]])]]))])]

       (when (seq registry-plugins)
         [:div.settings-section
          [:h3.settings-subheading
           (tr [:settings.plugins/available-in-registry])]
          (for [reg-plugin registry-plugins]
            (let [m-url      (:manifest-url reg-plugin)
                  installed? (or (contains? installed-urls m-url)
                                 (contains? active-ids (:id reg-plugin)))]
              ^{:key (:id reg-plugin)}
              [:div.settings-row
               [:div.settings-row-text
                [:div.settings-row-title
                 (:name reg-plugin (name (:id reg-plugin)))
                 (str " v" (:version reg-plugin "1.0.0"))]
                [:div.settings-row-description
                 (:description reg-plugin "")]]
               [:div.settings-actions
                [:button.settings-button
                 {:disabled installed?
                  :on-click #(re-frame/dispatch [:plugins/install-from-url m-url])}
                 (if installed?
                   (tr [:settings.plugins/installed])
                   (tr [:settings.plugins/install]))]]]))])])))


(defn ^:ui security-warning-modal []
  (let [pending-plugin @(re-frame/subscribe [:plugins/pending-security-approval])
        tr             @(re-frame/subscribe [:i18n/tr])]
    (when pending-plugin
      [:div.security-modal-card
       [:h2.security-modal-title
        (tr [:settings.plugins/security-title])]
       [:p.security-modal-text
        (tr [:settings.plugins/plugin-request
             [:strong (:name (:plugin-map pending-plugin))]])]

       [:div.security-script-list
        (for [script (:external-scripts (:plugin-map pending-plugin))]
          ^{:key script} [:div script])]

       [:p.security-modal-warning
        (tr [:settings.plugins/warning-message])]
       [:div.settings-actions
        [:button.settings-button
         {:on-click #(re-frame/dispatch [:plugins/cancel-install])}
         (tr [:settings.plugins/cancel])]
        [:button.settings-button.destructive
         {:on-click #(re-frame/dispatch [:plugins/execute-boot-sequence
                                         (:url pending-plugin)
                                         (:plugin-map pending-plugin)])}
         (tr [:settings.plugins/warning-confirmation])]]])))