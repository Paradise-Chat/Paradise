(ns paradise.ui.overlays.settings.registry
  (:require
   [paradise.shared.utils.svg :as icons]
   [re-frame.core :as re-frame]
   [reagent.core :as r]))

(defn ^:ui registries-and-engines-panel []
  (r/with-let [registry-url-input (r/atom "")]
    (let [tr                @(re-frame/subscribe [:i18n/tr])
          custom-registries @(re-frame/subscribe [:plugins/custom-registries])
          available-engines @(re-frame/subscribe [:plugins/registry-engines])]
      [:<>
       [:div.settings-hub-group
        [:div.settings-hub-group-label
         (tr [:settings.registry/custom-registries])]
        [:div.settings-hub-group-card
         [:div.settings-card-stack
          [:div.settings-input-row
           [:input.settings-input
            {:type        "text"
             :placeholder (tr [:settings.registry/placeholder-registry-url])
             :value       @registry-url-input
             :on-change   #(reset! registry-url-input (.. % -target -value))}]
           [:button.settings-button
            {:disabled (empty? @registry-url-input)
             :on-click #(do
                          (re-frame/dispatch [:registry/add-custom-url @registry-url-input])
                          (reset! registry-url-input ""))}
            (tr [:settings.registry/add-registry])]]]

         (for [reg-url custom-registries]
           ^{:key reg-url}
           [:div.settings-card-row
            [:div.settings-row-text
             [:div.settings-row-title reg-url]]
            [:div.settings-actions
             [:button.settings-button.destructive.icon-only
              {:title    (tr [:settings.registry/remove-registry])
               :on-click #(re-frame/dispatch [:registry/remove-custom-url reg-url])}
              [icons/trash]]]])]]

       [:div.settings-hub-group
        [:div.settings-hub-group-label
         (tr [:settings.registry/resolved-engines])]
        [:div.settings-hub-group-card
         (if (empty? available-engines)
           [:div.settings-empty "—"]
           (for [engine available-engines]
             ^{:key (:id engine)}
             [:div.settings-card-row
              [:div.settings-row-text
               [:div.settings-row-title
                [:span (or (:name engine) (some-> (:id engine) clojure.core/name))]
                (when-let [commit (:commit engine)]
                  [:span.settings-badge.neutral
                   (subs commit 0 (min 7 (count commit)))])]
               [:div.settings-row-description
                (:description engine (:path engine))]]]))]]])))