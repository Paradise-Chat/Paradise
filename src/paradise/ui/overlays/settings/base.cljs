(ns paradise.ui.overlays.settings.base
  (:require
   [clojure.string :as str]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [paradise.shared.utils.helpers :refer [mobile-runtime?]]
   [paradise.shared.utils.platform :as platform]
   [paradise.shared.utils.svg :as icons]
   [paradise.ui.global :refer [avatar]]
   [paradise.ui.overlays.base :refer [modal-component]]
   [paradise.shared.client.session-store :as store]
   [paradise.ui.overlays.settings.localization :refer [language-time-tab]]
   [paradise.ui.overlays.settings.profile :refer [my-account-tab]]
   [paradise.ui.overlays.settings.notifications :refer [notifications-tab]]
   [paradise.ui.overlays.settings.advanced :refer [advanced-tab]]
   [paradise.ui.overlays.settings.accounts :refer [accounts-tab]]
   [paradise.ui.overlays.settings.scan-qr :refer [scan-qr-tab]]
   [paradise.ui.overlays.settings.keybinds :refer [keybinds-tab]]
   [paradise.ui.overlays.settings.verification :refer [verification-tab]]
   [paradise.ui.overlays.settings.about :refer [credits-tab about-tab]]
   [paradise.ui.overlays.emotes-manager :refer [user-emotes-settings-tab]]
   [paradise.ui.overlays.settings.voice-video :refer [voice-video-tab]]))

(def settings-registry
  {"show_previews"          {:event :push/hydrate-previews-setting      :default true     :stage :login}
   "in_app_toasts_enabled"  {:event :notif/hydrate-toasts-enabled       :default true     :stage :boot}
   "in_app_sound_enabled"   {:event :notif/hydrate-sound-enabled        :default true     :stage :boot}
   "in_app_sound_preset"    {:event :notif/hydrate-sound-preset         :default "chime"  :stage :boot}
   "in_app_sound_volume"    {:event :notif/hydrate-sound-volume         :default 70       :stage :boot}
   "theme"                  {:event :ui/hydrate-theme                   :default :default :stage :boot}
   "custom_registries"      {:event :registry/hydrate-custom-urls       :default []       :stage :boot}
   "disabled_plugin_urls"   {:event :plugins/hydrate-disabled-urls      :default []       :stage :boot}
   "plugin_urls"            {:event :plugins/hydrate-urls               :default []       :stage :boot}
   "space_hierarchies"      {:event :space/hydrate-hierarchies          :default {}       :stage :login}
   "space_layouts"          {:event :space-groups/hydrate               :default {}       :stage :login}
   "keybinds"               {:event :settings/hydrate-keybinds          :default {}       :stage :boot}
   "call_audio_input"       {:event :settings/hydrate-audio-input       :default nil      :stage :boot}
   "call_video_input"       {:event :settings/hydrate-video-input       :default nil      :stage :boot}
   "call_audio_output"      {:event :settings/hydrate-audio-output      :default nil      :stage :boot}
   "call_input_volume"      {:event :settings/hydrate-input-volume      :default 100      :stage :boot}
   "call_output_volume"     {:event :settings/hydrate-output-volume     :default 100      :stage :boot}
   "call_audio_enabled"     {:event :call/hydrate-audio-enabled         :default true     :stage :boot}
   "call_deafened"          {:event :call/hydrate-deafened              :default false    :stage :boot}
   "call_voice_threshold"     {:event :settings/hydrate-voice-threshold   :default 0.12     :stage :boot}
   "call_noise_suppression"   {:event :settings/hydrate-noise-suppression :default true     :stage :boot}
   "submit_on_enter"          {:event :settings/hydrate-submit-on-enter   :default nil      :stage :boot}
   "close_to_tray"            {:event :settings/hydrate-close-to-tray     :default true     :stage :boot}
   "user_presence"            {:event :presence/hydrate-own-status        :default "online" :stage :login}
   "user_status_message"      {:event :presence/hydrate-own-status-message :default ""      :stage :login}
   "encrypted_search_crawler" {:event :search/hydrate-crawler-enabled     :default true     :stage :login}
   "ui_forum_view_rooms_v1"   {:event :rooms/hydrate-forum-overrides      :default {}       :stage :login}})

(defn default-submit-on-enter? []
  (not (or (platform/capacitor-mobile?)
           (and (platform/mobile-viewport?)
                (platform/coarse-pointer?)))))

(re-frame/reg-event-db
 :settings/hydrate-submit-on-enter
 (fn [db [_ saved-val]]
   (assoc db :settings/submit-on-enter
          (when (boolean? saved-val) saved-val))))

(re-frame/reg-event-fx
 :settings/set-submit-on-enter
 (fn [{:keys [db]} [_ enabled?]]
   (let [val (boolean enabled?)]
     {:db       (assoc db :settings/submit-on-enter val)
      :dispatch [:settings/save "submit_on_enter" val]})))

(re-frame/reg-sub
 :settings/submit-on-enter?
 (fn [db _]
   (let [v (:settings/submit-on-enter db)]
     (if (boolean? v)
       v
       (default-submit-on-enter?)))))

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
   {:db (assoc db :settings/active-tab tab-id)
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
   (:settings/active-tab db nil)))

(defn- build-settings-groups [tr profile accounts version]
  (let [mobile?    (mobile-runtime?)
        user-items (cond-> [{:id       :my-account
                             :title    (tr [:settings.tabs/my-account])
                             :subtitle (:user-id profile)
                             :accent   "indigo"
                             :icon     [icons/user]}
                            {:id       :verification
                             :title    (tr [:settings.tabs/verification])
                             :subtitle (tr [:settings.verification/description])
                             :accent   "emerald"
                             :icon     [icons/check]}
                            {:id       :accounts
                             :title    (tr [:settings.tabs/accounts])
                             :subtitle (tr [:settings.accounts/description])
                             :badge    (when (> (count accounts) 1) (str (count accounts)))
                             :accent   "sky"
                             :icon     [icons/members]}]
                     mobile?
                     (conj {:id       :scan-qr
                            :title    (tr [:settings.tabs/scan-qr])
                            :subtitle (tr [:settings.scan-qr/description])
                            :accent   "violet"
                            :icon     [icons/qr-code]}))]
    [{:id    :user
      :label (tr [:settings.groups/user-settings])
      :items user-items}
   {:id    :app
    :label (tr [:settings.groups/app])
    :items [{:id       :notifications
             :title    (tr [:settings.tabs/notifications])
             :subtitle (tr [:settings.notifications/description])
             :accent   "amber"
             :icon     [icons/bell]}
            {:id       :voice-video
             :title    (tr [:settings.tabs/voice-video])
             :subtitle (tr [:settings.voice-video/description])
             :accent   "teal"
             :icon     [icons/mic]}
            {:id       :emotes
             :title    (tr [:settings.tabs/emotes])
             :subtitle (tr [:settings.emotes/description])
             :accent   "amber"
             :icon     [icons/smiley]}
            {:id       :language-time
             :title    (tr [:settings.tabs/language-time])
             :subtitle (tr [:settings.language-time/description])
             :accent   "violet"
             :icon     [icons/compass]}
            {:id       :keybinds
             :title    (tr [:settings.keybinds/title])
             :subtitle (tr [:settings.keybinds/description])
             :accent   "pink"
             :icon     [icons/edit]}
            {:id       :advanced
             :title    (tr [:settings.tabs/advanced])
             :subtitle (tr [:settings.advanced/description])
             :accent   "slate"
             :icon     [icons/settings]}]}
   {:id    :info
    :label (tr [:settings.tabs/about])
    :items [{:id     :about
             :title  (tr [:settings.tabs/about])
             :badge  (when version (str "v" version))
             :accent "indigo"
             :icon   [icons/file]}
            {:id     :credits
             :title  (tr [:settings.credits/title])
             :accent "amber"
             :icon   [icons/smiley]}]}]))

(defn- tab-title [tr tab-id]
  (case tab-id
    :my-account    (tr [:settings.tabs/my-account])
    :verification  (tr [:settings.tabs/verification])
    :accounts      (tr [:settings.tabs/accounts])
    :scan-qr       (tr [:settings.tabs/scan-qr])
    :notifications (tr [:settings.tabs/notifications])
    :voice-video   (tr [:settings.tabs/voice-video])
    :emotes        (tr [:settings.tabs/emotes])
    :language-time (tr [:settings.tabs/language-time])
    :keybinds      (tr [:settings.tabs/keybinds])
    :advanced      (tr [:settings.tabs/advanced])
    :about         (tr [:settings.tabs/about])
    :credits       (tr [:settings.credits/title])
    (tr [:settings.groups/user-settings])))

(defn ^:ui settings-topbar [tr active-tab]
  [:div.settings-topbar
   (if active-tab
     [:button.settings-back-btn
      {:type     "button"
       :on-click #(re-frame/dispatch [:settings/set-tab nil])}
      [:span.settings-back-icon [icons/arrow-left]]
      [:span.settings-back-root (tr [:settings.groups/user-settings])]
      [:span.settings-breadcrumb-sep "/"]
      [:span.settings-breadcrumb-current (tab-title tr active-tab)]]
     [:div.settings-topbar-title
      [:span.settings-topbar-icon [icons/settings]]
      [:span (tr [:settings.groups/user-settings])]])

   [:button.settings-close-btn
    {:type     "button"
     :title    (tr [:settings.modal/esc])
     :on-click #(re-frame/dispatch [:ui/close-modal])}
    [:span.settings-close-esc (tr [:settings.modal/esc])]
    [:span.settings-close-icon [icons/exit]]]])

(defn- text-matches-query? [q s]
  (and (some? s)
       (str/includes? (str/lower-case (str s)) q)))

(defn- filter-group-items [q items]
  (loop [rem items
         acc []]
    (if (empty? rem)
      acc
      (let [item (first rem)]
        (recur (rest rem)
               (if (or (text-matches-query? q (:title item))
                       (text-matches-query? q (:subtitle item)))
                 (conj acc item)
                 acc))))))

(defn- filter-settings-groups [groups q]
  (loop [rem groups
         acc []]
    (if (empty? rem)
      acc
      (let [grp  (first rem)
            hits (filter-group-items q (:items grp))]
        (recur (rest rem)
               (if (seq hits)
                 (conj acc (assoc grp :items hits))
                 acc))))))

(defn- render-settings-hub
  [{:keys [tr profile active-engine accounts version !query !hub-scroll-top]}]
  (let [raw-q       @!query
        q           (str/lower-case (str/trim (or raw-q "")))
        searching?  (not (str/blank? q))
        groups      (build-settings-groups tr profile accounts version)
        filtered    (if searching?
                      (filter-settings-groups groups q)
                      groups)
        logout-lbl  (tr [:settings.context-menu/logout])
        show-logout (or (not searching?)
                        (text-matches-query? q logout-lbl))
        proto-label (some-> active-engine clojure.core/name str/capitalize)
        disp-name   (or (:display-name profile)
                        (tr [:settings.profile/unknown-user]))]
    [:div.settings-hub-scroll
     {:ref       (fn [el]
                   (when (and el (pos? @!hub-scroll-top))
                     (set! (.-scrollTop el) @!hub-scroll-top)))
      :on-scroll (fn [e]
                   (reset! !hub-scroll-top (.. e -currentTarget -scrollTop)))}
     [:div.settings-hub-inner
      ^{:key "hub-search"}
      [:div.settings-hub-search
       [:span.settings-hub-search-icon [icons/search]]
       [:input.settings-hub-search-input
        {:type        "text"
         :value       raw-q
         :placeholder (str (tr [:settings.groups/user-settings]) "...")
         :on-change   (fn [e] (reset! !query (.. e -target -value)))}]
       (when searching?
         [:button.settings-hub-search-clear
          {:type     "button"
           :on-click (fn [_] (reset! !query ""))}
          [icons/exit]])]

      (when-not searching?
        ^{:key "hub-profile"}
        [:div.settings-hub-profile-card
         {:on-click #(re-frame/dispatch [:settings/set-tab :my-account])}
         [avatar {:id   (:user-id profile)
                  :name (or (:display-name profile) "?")
                  :url  (:avatar-url profile)
                  :size 52}]
         [:div.settings-hub-profile-copy
          [:div.settings-hub-profile-row
           [:span.settings-hub-profile-name disp-name]
           (when proto-label
             [:span.settings-hub-proto-pill proto-label])]
          [:div.settings-hub-profile-id (:user-id profile)]]
         [:span.settings-hub-chevron [icons/chevron-down]]])

      (if (and (empty? filtered) (not show-logout))
        ^{:key "hub-empty"}
        [:div.settings-empty (tr [:settings.modal/no-results])]
        [:<>
         (for [{:keys [id label items]} filtered]
           ^{:key id}
           [:div.settings-hub-group
            [:div.settings-hub-group-label label]
            [:div.settings-hub-group-card
             (for [item items]
               ^{:key (:id item)}
               [:button.settings-hub-item
                {:type     "button"
                 :on-click #(re-frame/dispatch [:settings/set-tab (:id item)])}
                [:span.settings-hub-icon
                 {:class (str "accent-" (:accent item))}
                 (:icon item)]
                [:span.settings-hub-item-copy
                 [:span.settings-hub-item-title (:title item)]
                 (when-let [sub (:subtitle item)]
                   (when-not (str/blank? sub)
                     [:span.settings-hub-item-subtitle sub]))]
                (when-let [badge (:badge item)]
                  [:span.settings-hub-badge badge])
                [:span.settings-hub-chevron [icons/chevron-down]]])]])

         (when show-logout
           ^{:key "hub-logout"}
           [:div.settings-hub-group
            [:div.settings-hub-group-card
             [:button.settings-hub-item.is-danger
              {:type     "button"
               :on-click (fn [_]
                           (re-frame/dispatch [:ui/close-modal])
                           (re-frame/dispatch [:sdk/logout]))}
              [:span.settings-hub-icon.accent-danger [icons/leave]]
              [:span.settings-hub-item-copy
               [:span.settings-hub-item-title logout-lbl]]]]])])]]))

(defn- handle-settings-escape! [^js e]
  (when (and (= (.-key e) "Escape")
             (not (unchecked-get js/globalThis "paradiseRecordingKeybind"))
             (some? @(re-frame/subscribe [:settings/active-tab])))
    (.preventDefault e)
    (.stopImmediatePropagation e)
    (re-frame/dispatch [:settings/set-tab nil])))

(defn ^:ui settings-content [_props]
  (r/with-let [!query          (r/atom "")
               !hub-scroll-top (atom 0)
               _               (.addEventListener js/globalThis
                                                  "keyup"
                                                  handle-settings-escape!
                                                  true)]
    (let [tr            @(re-frame/subscribe [:i18n/tr])
          active-tab    @(re-frame/subscribe [:settings/active-tab])
          profile       @(re-frame/subscribe [:sdk/profile])
          active-engine @(re-frame/subscribe [:auth/active-engine])
          accounts      @(re-frame/subscribe [:settings/available-accounts])
          version       @(re-frame/subscribe [:app/version])]
      [:div.settings-shell
       [settings-topbar tr active-tab]
       [:div.settings-body
        (if (nil? active-tab)
          (render-settings-hub
           {:tr              tr
            :profile         profile
            :active-engine   active-engine
            :accounts        accounts
            :version         version
            :!query          !query
            :!hub-scroll-top !hub-scroll-top})
          [:div.settings-subview
           {:key (clojure.core/name active-tab)}
           (case active-tab
             :my-account    [my-account-tab profile]
             :verification  [verification-tab]
             :accounts      [accounts-tab]
             :scan-qr       [scan-qr-tab]
             :notifications [notifications-tab]
             :voice-video   [voice-video-tab]
             :emotes        [user-emotes-settings-tab]
             :language-time [language-time-tab]
             :keybinds      [keybinds-tab]
             :about         [about-tab]
             :advanced      [advanced-tab]
             :credits       [credits-tab]
             [:div.settings-empty (tr [:settings.modal/tab-not-found])])])]])
    (finally
      (.removeEventListener js/globalThis "keyup" handle-settings-escape! true))))

(defmethod modal-component :settings [_]
  settings-content)