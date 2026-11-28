(ns paradise.ui.overlays.settings.keybinds
  (:require
   [paradise.shared.client.key-handler :as kh]
   [re-frame.core :as re-frame]
   [reagent.core :as r]))

(defn ^:ui keybind-row [{:keys [id label-key fallback-label accelerator global?]}
                        recording-id
                        set-recording!]
  (let [tr                @(re-frame/subscribe [:i18n/tr])
        recording?        (= recording-id id)
        global-supported? (kh/global-shortcuts-supported?)
        title             (or (when label-key (tr [label-key]))
                              fallback-label
                              id)]
    [:div.settings-card-row
     [:div.settings-row-text
      [:div.settings-row-title title]
      [:div.settings-row-description
       (if (and global? global-supported?)
         (tr [:settings.keybinds/scope-global])
         (tr [:settings.keybinds/scope-in-app]))]]

     [:div.settings-actions
      (when global-supported?
        [:label.settings-toggle
         {:title (tr [:settings.keybinds/global-toggle-tooltip])}
         [:input
          {:type      "checkbox"
           :checked   (boolean global?)
           :on-change #(re-frame/dispatch
                        [:settings/set-keybind id
                         {:global? (.. % -target -checked)}])}]
         [:div.settings-toggle-track
          [:div.settings-toggle-knob]]])

      [:button.settings-keybind-btn
       {:type     "button"
        :class    (when recording? "is-active")
        :on-click #(set-recording! (if recording? nil id))}
       (if recording?
        (tr [:settings.keybinds/press-keys])
         (kh/format-accelerator accelerator))]

      [:button.settings-button.secondary.is-compact
       {:type     "button"
        :on-click #(re-frame/dispatch [:settings/reset-keybind id])}
       (tr [:settings.keybinds/reset])]]]))

(defn- mark-recording-keybind! [recording?]
  (unchecked-set js/globalThis "paradiseRecordingKeybind" (boolean recording?)))

(defn- attach-keybind-listener! [handler]
  (.addEventListener js/globalThis "keydown" handler true))

(defn- detach-keybind-listener! [handler]
  (.removeEventListener js/globalThis "keydown" handler true))

(defn ^:ui keybinds-tab []
  (r/with-let [!recording-id  (r/atom nil)
               set-recording! (fn [next-id]
                                (mark-recording-keybind! (some? next-id))
                                (reset! !recording-id next-id))
               on-key-down    (fn [^js e]
                                (when-let [active-id @!recording-id]
                                  (.preventDefault e)
                                  (.stopPropagation e)
                                  (if (= (.-key e) "Escape")
                                    (set-recording! nil)
                                    (when-let [acc (kh/event->accelerator e)]
                                      (re-frame/dispatch
                                       [:settings/set-keybind active-id {:accelerator acc}])
                                      (set-recording! nil)))))
               _              (attach-keybind-listener! on-key-down)]
    (let [tr                @(re-frame/subscribe [:i18n/tr])
          keybinds          @(re-frame/subscribe [:settings/resolved-keybinds])
          submit-enter?     @(re-frame/subscribe [:settings/submit-on-enter?])
          global-supported? (kh/global-shortcuts-supported?)]
      [:div.settings-tab-content
       [:p.settings-subview-lead
        (tr [:settings.keybinds/description])]

       (when global-supported?
         [:p.settings-subview-lead
          (tr [:settings.keybinds/global-hint])])

       [:div.settings-hub-group
        [:div.settings-hub-group-label
         (tr [:settings.advanced/prefs-subheading])]
        [:div.settings-hub-group-card
         [:div.settings-card-row.is-toggle
          [:div.settings-row-text
           [:div.settings-row-title
            (tr [:settings.advanced/submit-on-enter-title])]
           [:div.settings-row-description
            (tr [:settings.advanced/submit-on-enter-description])]]
          [:label.settings-toggle
           [:input
            {:type      "checkbox"
             :checked   (boolean submit-enter?)
             :on-change #(re-frame/dispatch
                          [:settings/set-submit-on-enter
                           (.. % -target -checked)])}]
           [:div.settings-toggle-track
            [:div.settings-toggle-knob]]]]]]

       [:div.settings-hub-group
        [:div.settings-hub-group-header
         [:span.settings-hub-group-label
          (tr [:settings.keybinds/shortcuts-subheading])]
         [:button.settings-button.secondary.is-compact
          {:type     "button"
           :on-click #(re-frame/dispatch [:settings/reset-all-keybinds])}
          (tr [:settings.keybinds/reset-all])]]

        [:div.settings-hub-group-card
         (for [item keybinds]
           ^{:key (:id item)}
           [keybind-row item @!recording-id set-recording!])]]])
    (finally
      (mark-recording-keybind! false)
      (detach-keybind-listener! on-key-down))))