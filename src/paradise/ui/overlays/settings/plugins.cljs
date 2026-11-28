(ns paradise.ui.overlays.settings.plugins
  (:require
   [clojure.string :as str]
   [paradise.shared.registry :as reg]
   [paradise.shared.utils.svg :as icons]
   [re-frame.core :as re-frame]
   [reagent.core :as r]))

(defn ^:ui plugins-installer []
  (r/with-let [plugin-url-input (r/atom "")
               theme-url-input  (r/atom "")]
    (let [tr                 @(re-frame/subscribe [:i18n/tr])
          installed-urls     (set @(re-frame/subscribe [:plugins/installed-urls]))
          managed-plugins    @(re-frame/subscribe [:plugins/managed-list])
          active-plugins     @(re-frame/subscribe [:plugins/active-list])
          active-ids         (set (map :id active-plugins))
          registry-plugins   @(re-frame/subscribe [:plugins/registry-plugins])
          disabled-urls      @(re-frame/subscribe [:plugins/disabled-urls])
          last-install-error @(re-frame/subscribe [:plugins/last-install-error])
          current-theme      @(re-frame/subscribe [:ui/current-theme])
          custom-theme?      (and (string? current-theme)
                                  (str/starts-with? current-theme "http"))]
      [:<>
       [:div.settings-hub-group
        [:div.settings-hub-group-label
         (tr [:settings.plugins/themes-title])]
        [:div.settings-hub-group-card
         [:div.settings-card-stack
          [:div.settings-input-row
           [:input.settings-input
            {:type        "text"
             :placeholder (tr [:settings.plugins/placeholder-theme-url])
             :value       @theme-url-input
             :on-change   #(reset! theme-url-input (.. % -target -value))}]
           [:button.settings-button
            {:disabled (empty? @theme-url-input)
             :on-click #(do
                          (re-frame/dispatch [:ui/switch-theme @theme-url-input])
                          (reset! theme-url-input ""))}
            (tr [:settings.plugins/apply])]]]

         (when custom-theme?
           [:div.settings-card-row
            [:div.settings-row-text
             [:div.settings-row-title
              (tr [:settings.plugins/active-custom-theme])]
             [:div.settings-row-description
              current-theme]]
            [:div.settings-actions
             [:button.settings-button.destructive.is-compact
              {:on-click #(re-frame/dispatch [:ui/reset-theme])}
              (tr [:settings.plugins/reset-default])]]])]]

       [:div.settings-hub-group
        [:div.settings-hub-group-label
         (tr [:settings.plugins/plugins-title])]
        [:div.settings-hub-group-card
         [:div.settings-card-stack
          [:div.settings-input-row
           [:input.settings-input
            {:type        "text"
             :placeholder (tr [:settings.plugins/placeholder-plugin-url])
             :value       @plugin-url-input
             :on-change   #(reset! plugin-url-input (.. % -target -value))}]
           [:button.settings-button
            {:disabled (empty? @plugin-url-input)
             :on-click #(do
                          (re-frame/dispatch [:plugins/install-from-url @plugin-url-input])
                          (reset! plugin-url-input ""))}
            (tr [:settings.plugins/install])]]
          (when last-install-error
            [:div.plugin-install-error
             [:span (str (:error last-install-error)
                         (when-let [u (:url last-install-error)]
                           (str " — " u)))]
             [:button.settings-button.secondary.is-compact
              {:on-click #(re-frame/dispatch [:plugins/clear-install-error])}
              "Dismiss"]])]]]

       (when (seq managed-plugins)
         [:div.settings-hub-group
          [:div.settings-hub-group-label
           (tr [:settings.plugins/active-plugins])]
          [:div.settings-hub-group-card
           (for [plugin managed-plugins]
             (let [url            (:source-url plugin)
                   provided-by    (:provided-by plugin)
                   loaded?        (:loaded? plugin true)
                   err-msg        (:error plugin)
                   enabled?       (not (contains? disabled-urls url))
                   can-uninstall? (or (not provided-by)
                                      (contains? installed-urls url))]
               ^{:key (or url (:id plugin))}
               [:div.settings-card-row.is-toggle
                [:div.settings-row-text
                 [:div.settings-row-title
                  [:span (:name plugin (tr [:settings.plugins/unknown-plugin]))]
                  (when-let [v (:version plugin)]
                    [:span.settings-badge.neutral
                     (str "v" v)])
                  (when provided-by
                    [:span.settings-badge
                     (clojure.core/name provided-by)])
                  (when-not loaded?
                    (if err-msg
                      [:span.settings-badge.destructive "Failed"]
                      [:span.settings-badge.neutral "Loading…"]))]
                 [:div.settings-row-description
                  (cond
                    (and (not loaded?) err-msg)
                    (str err-msg " — " url)

                    :else
                    (:description plugin (tr [:settings.plugins/no-description])))]]
                [:div.settings-actions
                 (if loaded?
                   [:label.settings-toggle
                    [:input
                     {:type      "checkbox"
                      :checked   enabled?
                      :on-change #(re-frame/dispatch
                                   [:plugins/toggle-plugin url (.. % -target -checked)])}]
                    [:div.settings-toggle-track
                     [:div.settings-toggle-knob]]]
                   (when enabled?
                     [:button.settings-button.secondary.is-compact
                      {:on-click #(re-frame/dispatch [:plugins/install-from-url url])}
                      "Retry"]))
                 (when can-uninstall?
                   [:button.settings-button.destructive.icon-only
                    {:title    (tr [:settings.plugins/uninstall-plugin])
                     :on-click #(re-frame/dispatch [:plugins/uninstall-url url])}
                    [icons/trash]])]]))]])

       (when (seq registry-plugins)
         [:div.settings-hub-group
          [:div.settings-hub-group-label
           (tr [:settings.plugins/available-in-registry])]
          [:div.settings-hub-group-card
           (for [reg-plugin registry-plugins]
             (let [m-url           (:manifest-url reg-plugin)
                   in-installed?   (contains? installed-urls m-url)
                   installed?      (or in-installed?
                                       (contains? active-ids (:id reg-plugin)))
                   matching-active (first (filter #(= (:id %) (:id reg-plugin)) active-plugins))
                   uninstall-url   (cond
                                     in-installed? m-url
                                     (and matching-active (not (:provided-by matching-active)))
                                     (:source-url matching-active)
                                     :else nil)]
               ^{:key (:id reg-plugin)}
               [:div.settings-card-row
                [:div.settings-row-text
                 [:div.settings-row-title
                  [:span (:name reg-plugin (clojure.core/name (:id reg-plugin)))]
                  [:span.settings-badge.neutral
                   (str "v" (:version reg-plugin "1.0.0"))]]
                 [:div.settings-row-description
                  (:description reg-plugin "")]]
                [:div.settings-actions
                 [:button.settings-button.is-compact
                  {:class    (when installed? "secondary")
                   :disabled installed?
                   :on-click #(re-frame/dispatch [:plugins/install-from-url m-url])}
                  (if installed?
                    (tr [:settings.plugins/installed])
                    (tr [:settings.plugins/install]))]
                 (when uninstall-url
                   [:button.settings-button.destructive.icon-only
                    {:title    (tr [:settings.plugins/uninstall-plugin])
                     :on-click #(re-frame/dispatch [:plugins/uninstall-url uninstall-url])}
                    [icons/trash]])]]))]])])))


(defn ^:ui security-warning-modal []
  (let [pending-plugin @(re-frame/subscribe [:plugins/pending-security-approval])
        tr             @(re-frame/subscribe [:i18n/tr])]
    (when pending-plugin
      (let [plugin-name (or (get-in pending-plugin [:plugin-map :name])
                            (:url pending-plugin)
                            (tr [:settings.plugins/unknown-plugin]))
            base-url    (:url pending-plugin)]
        [:div.security-modal-overlay
         {:on-click #(re-frame/dispatch [:plugins/cancel-install])}
         [:div.security-modal-card
          {:on-click #(.stopPropagation %)}
          [:h2.security-modal-title
           (tr [:settings.plugins/security-title])]
          [:p.security-modal-text
           (tr [:settings.plugins/plugin-request] [plugin-name])]

          [:div.security-script-list
           (for [script (get-in pending-plugin [:plugin-map :external-scripts])]
             (let [resolved (reg/resolve-relative-url base-url script)]
               ^{:key resolved} [:div resolved]))]

          [:p.security-modal-warning
           (tr [:settings.plugins/warning-message])]
          [:div.security-modal-actions
           [:button.settings-button.secondary
            {:on-click #(re-frame/dispatch [:plugins/cancel-install])}
            (tr [:settings.plugins/cancel])]
           [:button.settings-button.destructive
            {:on-click #(re-frame/dispatch [:plugins/approve-security-warning])}
            (tr [:settings.plugins/warning-confirmation])]]]]))))