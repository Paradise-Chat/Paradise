(ns paradise.ui.overlays.settings.base
  (:require
   [re-frame.core :as re-frame]
   [paradise.shared.utils.svg :as icons]
   [paradise.ui.overlays.base :refer [modal-component]]
   [paradise.shared.client.session-store :as store]
   [paradise.ui.overlays.settings.localization :refer [language-time-tab]]
   [paradise.ui.overlays.settings.profile :refer [my-account-tab]]
   [paradise.ui.overlays.settings.notifications :refer [notifications-tab]]
   [paradise.ui.overlays.settings.advanced :refer [advanced-tab]]
   [paradise.ui.overlays.settings.accounts :refer [accounts-tab]]
   [paradise.ui.overlays.settings.verification :refer [verification-tab]]
   [paradise.ui.overlays.settings.about :refer [credits-tab about-tab]]
   [paradise.ui.overlays.settings.voice-video :refer [voice-video-tab]]
   ))

(def settings-registry
  {;; DB Key                  Hydration Event                       Default Value
   "show_previews"         {:event :push/hydrate-previews-setting :default true      :stage  :login}
   "theme"                 {:event :ui/hydrate-theme              :default :default  :stage  :boot}
   "disabled_plugin_urls"  {:event :plugins/hydrate-disabled-urls :default []        :stage  :boot}
   "plugin_urls"           {:event :plugins/hydrate-urls          :default []        :stage  :boot}
   "space_hierarchies"    {:event :space/hydrate-hierarchies     :default {}        :stage  :login}
   "call_audio_input"     {:event :settings/hydrate-audio-input  :default nil       :stage  :boot}
   "call_video_input"     {:event :settings/hydrate-video-input  :default nil       :stage  :boot}
   "call_audio_output"    {:event :settings/hydrate-audio-output :default nil       :stage  :boot}
   "call_voice_threshold"
   {:event   :settings/hydrate-voice-threshold
    :default 0.12
    :stage   :boot}
   "call_noise_suppression"
   {:event   :settings/hydrate-noise-suppression
    :default true
    :stage   :boot}})


(re-frame/reg-event-fx
 :settings/load
 (fn [_ [_ idb-key hydrate-event default-val]]
   (-> (store/get-setting idb-key)
       (.then (fn [saved-val]
                (let [final-val (if (nil? saved-val) default-val saved-val)]
                  (re-frame/dispatch [hydrate-event final-val])))))
   {}))


(re-frame/reg-event-fx
 :settings/save
 (fn [_ [_ idb-key new-val]]
   (store/set-setting! idb-key new-val)
   {}))

(re-frame/reg-event-fx
 :app/load-settings-by-stage
 (fn [_ [_ target-stage]]
   (let [staged-settings (filter (fn [[_ config]]
                                   (= (:stage config) target-stage))
                                 settings-registry)]
     {:fx (mapv (fn [[db-key config]]
                  [:dispatch [:settings/load db-key (:event config) (:default config)]])
                staged-settings)})))


(re-frame/reg-event-fx
 :settings/open
 (fn [{:keys [db]} [_ tab-id]]
   {:db (cond-> db
          tab-id (assoc :settings/active-tab tab-id))
    :fx [[:dispatch [:ui/open-modal :settings
                     {:backdrop-props {:class "settings-backdrop"}
                      :window-props   {:class "settings-window"}}]]
         [:dispatch [:settings/load-accounts]]]}))

(re-frame/reg-event-db
 :settings/set-tab
 (fn [db [_ tab-id]]
   (assoc db :settings/active-tab tab-id)))

(re-frame/reg-sub
 :settings/active-tab
 (fn [db _]
   (:settings/active-tab db :my-account)))

(defn ^:ui settings-sidebar [active-tab]
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:div.settings-sidebar
     [:div.settings-group-label (tr [:settings.groups/user-settings])]
     (for [[id label-key] [[:my-account    :settings.tabs/my-account]
                           [:verification  :settings.tabs/verification]
                           [:accounts      :settings.tabs/accounts]
                           [:notifications :settings.tabs/notifications]
                           [:language-time :settings.tabs/language-time]]]
       ^{:key id}
       [:div.settings-tab
        {:class (when (= active-tab id) "is-active")
         :on-click #(re-frame/dispatch [:settings/set-tab id])}
        (tr [label-key])])

     [:div.settings-tab
      {:class (when (= active-tab :voice-video) "is-active")
       :on-click #(re-frame/dispatch [:settings/set-tab :voice-video])}
      "Voice & Video"]

     [:div.settings-group-label {:style {:margin-top "1rem"}}
      (tr [:settings.groups/app])]

     [:div.settings-tab
      {:class (when (= active-tab :about) "is-active")
       :on-click #(re-frame/dispatch [:settings/set-tab :about])}
      (tr [:settings.tabs/about])]

     [:div.settings-tab
      {:class (when (= active-tab :credits) "is-active")
       :on-click #(re-frame/dispatch [:settings/set-tab :credits])}
      (tr [:settings.credits/title])]

     [:div.settings-tab
      {:class (when (= active-tab :advanced) "is-active")
       :on-click #(re-frame/dispatch [:settings/set-tab :advanced])}
      (tr [:settings.tabs/advanced])]]))


(defn ^:ui settings-content [_props]
  (let [tr         @(re-frame/subscribe [:i18n/tr])
        active-tab @(re-frame/subscribe [:settings/active-tab])
        profile    @(re-frame/subscribe [:sdk/profile])]
    [:<>
     [settings-sidebar active-tab]
     [:div.settings-content
      [:div.close-button
       {:on-click #(re-frame/dispatch [:ui/close-modal])}
       [:span [icons/exit]]
       [:span.esc-text (tr [:settings.modal/esc])]]
      (case active-tab
        :my-account    [my-account-tab profile]
        :verification  [verification-tab]
        :accounts      [accounts-tab]

        :notifications [notifications-tab]
        :voice-video   [voice-video-tab]
        :language-time [language-time-tab]
        :about         [about-tab]
        :credits       [credits-tab]
        :advanced      [advanced-tab]
        [:div {:style {:color "#fff"}} (tr [:settings.modal/not-found])])]]))

(defmethod modal-component :settings [_]
  settings-content)