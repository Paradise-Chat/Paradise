(ns paradise.ui.overlays.settings.registry
  (:require
   [clojure.string :as str]
   [paradise.shared.utils.svg :as icons]
   [re-frame.core :as re-frame]
   [reagent.core :as r]))

(defn ^:ui registries-and-engines-panel []
  (r/with-let [registry-url-input (r/atom "")]
    (let [tr                @(re-frame/subscribe [:i18n/tr])
          custom-registries @(re-frame/subscribe [:plugins/custom-registries])
          available-engines @(re-frame/subscribe [:plugins/registry-engines])]
      [:<>
       [:div.settings-section
        [:h3.settings-subheading
         (tr [:settings.registry/custom-registries])]
        [:div.settings-row
         [:input.form-input.plugin-input
          {:type "text"
           :placeholder (tr [:settings.registry/placeholder-registry-url])
           :value @registry-url-input
           :on-change #(reset! registry-url-input (.. % -target -value))}]
         [:div.settings-actions
          [:button.settings-button
           {:disabled (empty? @registry-url-input)
            :on-click #(do
                         (re-frame/dispatch [:registry/add-custom-url @registry-url-input])
                         (reset! registry-url-input ""))}
           (tr [:settings.registry/add-registry])]]]

        (for [reg-url custom-registries]
          ^{:key reg-url}
          [:div.settings-row
           [:div.settings-row-text
            [:div.settings-row-title reg-url]]
           [:div.settings-actions
            [:button.settings-button.destructive
             {:title (tr [:settings.registry/remove-registry])
              :on-click #(re-frame/dispatch [:registry/remove-custom-url reg-url])}
             [icons/trash]]]])]

       [:div.settings-section
        [:h3.settings-subheading
         (tr [:settings.registry/resolved-engines])]
        (for [engine available-engines]
          ^{:key (:id engine)}
          [:div.settings-row
           [:div.settings-row-text
            [:div.settings-row-title
             (or (:name engine) (some-> (:id engine) name))
             (when-let [commit (:commit engine)]
               (str " (" (subs commit 0 (min 7 (count commit))) ")"))]
            [:div.settings-row-description
             (:description engine (:path engine))]]])]])))