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
    [:div.settings-row
     {:style {:display         "flex"
              :align-items     "center"
              :justify-content "space-between"
              :gap             "16px"
              :padding         "12px 0"}}
     [:div.settings-row-text
      [:div.settings-row-title title]
      [:div.settings-row-description
       (if (and global? global-supported?)
         (tr [:settings.keybinds/scope-global])
         (tr [:settings.keybinds/scope-in-app]))]]

     [:div.settings-actions
      {:style {:display     "flex"
               :align-items "center"
               :gap         "10px"}}
      (when global-supported?
        [:label.settings-toggle
         {:title (tr [:settings.keybinds/global-toggle-tooltip])
                     }
         [:input
          {:type      "checkbox"
           :checked   (boolean global?)
           :on-change #(re-frame/dispatch
                        [:settings/set-keybind id
                         {:global? (.. % -target -checked)}])}]
         [:div.settings-toggle-track
          [:div.settings-toggle-knob]]])

      [:button.settings-button
       {:class    (when recording? "is-active")
        :on-click #(set-recording! (if recording? nil id))}
       (if recording?
         (tr [:settings.keybinds/press-keys])
         (kh/format-accelerator accelerator))]

      [:button.settings-button
       {:on-click #(re-frame/dispatch [:settings/reset-keybind id])}
       (tr [:settings.keybinds/reset]) ]]]))

(defn ^:ui keybinds-tab []
  (r/with-let [!recording-id (r/atom nil)
               set-recording! (fn [next-id]
                                (when (exists? js/window)
                                  (set! (.-paradiseRecordingKeybind js/window) (some? next-id)))
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
               _              (.addEventListener js/window "keydown" on-key-down true)]
    (let [tr                @(re-frame/subscribe [:i18n/tr])
          keybinds          @(re-frame/subscribe [:settings/resolved-keybinds])
          global-supported? (kh/global-shortcuts-supported?)]
      [:div.settings-tab-content
       [:div.settings-page-header
        [:h2.settings-heading
         (tr [:settings.keybinds/title])]
        [:p.settings-description
         (tr [:settings.keybinds/description])
             ]]

       [:div.settings-section
        [:div
         {:style {:display         "flex"
                  :align-items     "center"
                  :justify-content "space-between"
                  :margin-bottom   "12px"}}
         [:h3.settings-subheading
          (tr [:settings.keybinds/shortcuts-subheading])]
         [:button.settings-button
          {:on-click #(re-frame/dispatch [:settings/reset-all-keybinds])}
          (tr [:settings.keybinds/reset-all])]]

        (when global-supported?
          [:p.settings-description
           {:style {:margin-bottom "12px"}}
           (tr [:settings.keybinds/global-hint])
               ])

        (for [item keybinds]
          ^{:key (:id item)}
          [keybind-row item @!recording-id set-recording!])]])
    (finally
      (when (exists? js/window)
        (set! (.-paradiseRecordingKeybind js/window) false))
      (.removeEventListener js/window "keydown" on-key-down true))))
