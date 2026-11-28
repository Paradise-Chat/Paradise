(ns paradise.ui.overlays.settings.profile
  (:require
   [cljs.core.async :refer [<! go]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [cljs-workers.mesh :as mesh]
   [clojure.string :as str]
   [paradise.media.component :refer [media]]
   [paradise.shared.plugins :as plugins]
   [paradise.shared.utils.helpers :refer [click-element-by-id!
                                          decode-edn-field
                                          file->array-buffer
                                          first-non-blank
                                          local-media-url?
                                          normalize-keyword
                                          revoke-object-url!]]
   [paradise.shared.utils.normalize :as norm]
   [paradise.shared.utils.svg :as icons]
   [paradise.ui.global :refer [avatar]]
   [paradise.ui.overlays.base :refer [context-menu-component
                                      context-menu-submenu
                                      modal-component
                                      popover-component]]
   [paradise.ui.overlays.settings.voice-video :as voice-video]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [taoensso.timbre :as log]))

(defonce ^:private !avatar-draft
  (r/atom {:file nil :preview-url nil :remove? false}))

(defonce ^:private !banner-draft
  (r/atom {:file nil :preview-url nil :remove? false}))

(defonce ^:private !profile-draft
  (r/atom {:user-id nil :dirty #{} :values {}}))

(defn- clear-media-draft! [!draft]
  (revoke-object-url! (:preview-url @!draft))
  (reset! !draft {:file nil :preview-url nil :remove? false}))

(defn- clear-avatar-draft! []
  (clear-media-draft! !avatar-draft))

(defn- clear-banner-draft! []
  (clear-media-draft! !banner-draft))

(defn- reset-profile-draft! [user-id]
  (clear-avatar-draft!)
  (clear-banner-draft!)
  (reset! !profile-draft {:user-id user-id :dirty #{} :values {}})
  (re-frame/dispatch [:profile/clear-feedback]))

(defn- ensure-profile-draft-user! [user-id]
  (when (and user-id
             (not= user-id "Loading...")
             (not= (:user-id @!profile-draft) user-id))
    (clear-avatar-draft!)
    (clear-banner-draft!)
    (reset! !profile-draft {:user-id user-id :dirty #{} :values {}})))

(defn- set-draft-field! [field-kw val]
  (swap! !profile-draft
         (fn [st]
           (-> st
               (update :dirty (fnil conj #{}) field-kw)
               (assoc-in [:values field-kw] val))))
  (re-frame/dispatch [:profile/clear-feedback]))

(defn- trigger-avatar-file-picker! []
  (click-element-by-id! "paradise-profile-avatar-input"))

(defn- trigger-banner-file-picker! []
  (click-element-by-id! "paradise-profile-banner-input"))

(defn- handle-media-file-selected! [!draft ^js e]
  (when-let [file (some-> e .-target .-files (aget 0))]
    (let [prev (:preview-url @!draft)
          url  (js/URL.createObjectURL file)]
      (revoke-object-url! prev)
      (reset! !draft {:file file :preview-url url :remove? false})
      (re-frame/dispatch [:profile/clear-feedback])))
  (when-let [target (.-target e)]
    (set! (.-value target) "")))

(defn- handle-avatar-file-selected! [^js e]
  (handle-media-file-selected! !avatar-draft e))

(defn- handle-banner-file-selected! [^js e]
  (handle-media-file-selected! !banner-draft e))

(defn- toggle-remove-media! [!draft]
  (let [{:keys [preview-url remove?]} @!draft]
    (revoke-object-url! preview-url)
    (reset! !draft {:file nil :preview-url nil :remove? (not remove?)})
    (re-frame/dispatch [:profile/clear-feedback])))

(defn- toggle-remove-avatar! []
  (toggle-remove-media! !avatar-draft))

(defn- toggle-remove-banner! []
  (toggle-remove-media! !banner-draft))

(re-frame/reg-sub
 :sdk/profile
 (fn [db _]
   (get db
        :current-user
        {:user-id      "Loading..."
         :display-name "Loading..."
         :avatar-url   nil})))

(re-frame/reg-sub
 :profile/saving?
 (fn [db _]
   (boolean (:profile/saving? db))))

(re-frame/reg-sub
 :profile/error
 (fn [db _]
   (:profile/error db)))

(re-frame/reg-sub
 :profile/saved?
 (fn [db _]
   (boolean (:profile/saved? db))))

(re-frame/reg-event-db
 :profile/clear-feedback
 (fn [db _]
   (dissoc db :profile/error :profile/saved?)))

(re-frame/reg-event-db
 :profile/save-failed
 (fn [db [_ msg]]
   (-> db
       (assoc :profile/saving? false
              :profile/error (or (not-empty (str msg)) "Failed to update profile"))
       (dissoc :profile/saved?))))

(re-frame/reg-event-fx
 :profile/save-succeeded
 (fn [{:keys [db]} [_ updated-profile]]
   (clear-avatar-draft!)
   (clear-banner-draft!)
   (reset! !profile-draft {:user-id (:user-id updated-profile)
                           :dirty   #{}
                           :values  {}})
   {:db (-> db
            (update :current-user merge updated-profile)
            (assoc :profile/saving? false
                   :profile/saved? true)
            (dissoc :profile/error))
    :fx [[:dispatch [:settings/load-accounts]]]}))

(defn- execute-profile-save! [payload]
  (let [av-file    (:file @!avatar-draft)
        av-remove? (:remove? @!avatar-draft)
        bn-file    (:file @!banner-draft)
        bn-remove? (:remove? @!banner-draft)]
    (go
      (try
        (let [av-ab   (when av-file (<p! (file->array-buffer av-file)))
              av-mime (when av-file (or (not-empty (.-type av-file)) "image/png"))
              bn-ab   (when bn-file (<p! (file->array-buffer bn-file)))
              bn-mime (when bn-file (or (not-empty (.-type bn-file)) "image/png"))
              args    (cond-> payload
                        av-ab      (assoc :avatar-buffer av-ab :avatar-mime av-mime)
                        av-remove? (assoc :remove-avatar? true)
                        bn-ab      (assoc :banner-buffer bn-ab :banner-mime bn-mime)
                        bn-remove? (assoc :remove-banner? true))
              res     (<! (mesh/do-with-thread!
                           :engine-pool
                           {:handler   :update-profile
                            :arguments args}))]
          (if (= (:status res) "success")
            (re-frame/dispatch [:profile/save-succeeded (:profile res)])
            (re-frame/dispatch [:profile/save-failed (:msg res)])))
        (catch :default e
          (log/error "Profile update exception:" e)
          (re-frame/dispatch [:profile/save-failed (or (.-message e) (str e))]))))))

(re-frame/reg-event-fx
 :sdk/update-own-profile
 (fn [{:keys [db]} [_ payload]]
   (execute-profile-save! payload)
   {:db (-> db
            (assoc :profile/saving? true)
            (dissoc :profile/error :profile/saved?))}))

(re-frame/reg-event-fx
 :sdk/fetch-own-profile
 (fn [_ _]
   (go
     (let [res (<! (mesh/do-with-thread!
                    :engine-pool
                    {:handler :fetch-profile}))]
       (when (= (:status res) "success")
         (re-frame/dispatch [:sdk/set-own-profile (:profile res)]))))
   {}))

(re-frame/reg-event-db
 :sdk/set-own-profile
 (fn [db [_ profile]]
   (update db :current-user merge profile)))

(re-frame/reg-event-fx
 :settings/update-media-preview-policy
 (fn [{:keys [db]} [_ policy]]
   (let [policy-kw (keyword policy)]
     (go
       (let [res (<! (mesh/do-with-thread!
                      :engine-pool
                      {:handler   :set-media-preview-policy
                       :arguments {:policy policy-kw}}))]
         (when-not (= (:status res) "success")
           (log/error "Failed to sync media policy:" (:msg res)))))
     {:db (assoc-in db [:settings :media-previews] policy-kw)})))

(re-frame/reg-event-db
 :settings/receive-media-preview-config
 (fn [db [_ policy]]
   (assoc-in db [:settings :media-previews] policy)))

(re-frame/reg-sub
 :settings/media-preview-policy
 (fn [db _]
   (get-in db [:settings :media-previews] :off)))

(def ^:private presence-options
  [{:id        :online
    :label-key :settings.profile/status-online
    :desc-key  :settings.profile/status-online-desc}
   {:id        :away
    :label-key :settings.profile/status-away
    :desc-key  :settings.profile/status-away-desc}
   {:id        :dnd
    :label-key :settings.profile/status-dnd
    :desc-key  :settings.profile/status-dnd-desc}
   {:id        :offline
    :label-key :settings.profile/status-offline
    :desc-key  :settings.profile/status-offline-desc}])

(defn- presence-label [tr own-status]
  (case (keyword (or own-status :online))
    :away    (tr [:settings.profile/status-away])
    :dnd     (tr [:settings.profile/status-dnd])
    :offline (tr [:settings.profile/status-offline])
    (tr [:settings.profile/status-online])))

(defn- status-submenu-items [close-fn]
  (let [tr         @(re-frame/subscribe [:i18n/tr])
        own-status @(re-frame/subscribe [:presence/own-status])]
    [:<>
     (for [{:keys [id label-key desc-key]} presence-options
           :let [active? (= own-status id)]]
       ^{:key id}
       [:div.context-menu-item
        {:class    (when active? "is-selected")
         :on-click (fn [e]
                     (.stopPropagation e)
                     (re-frame/dispatch [:presence/set-own-status id])
                     (when close-fn (close-fn)))}
        [:span.status-menu-swatch-wrap
         [:span.avatar-status {:class (name id)}]]
        [:span.context-menu-item-copy
         [:span.item-label (tr [label-key])]
         [:span.context-menu-item-sub (tr [desc-key])]]
        (when active?
          [:span.context-menu-item-check
           [icons/check {:size "14px"}]])])]))

(defn- account-switch-submenu-items [close-fn]
  (let [tr             @(re-frame/subscribe [:i18n/tr])
        accounts       @(re-frame/subscribe [:settings/available-accounts])
        current-user   @(re-frame/subscribe [:sdk/profile])
        active-user-id @(re-frame/subscribe [:auth/active-user-id])
        active-engine  @(re-frame/subscribe [:auth/active-engine])
        effective-uid  (or active-user-id (:user-id current-user))]
    [:<>
     (if (seq accounts)
       (for [acc accounts
             :let [acc-eng  (some-> (:engine acc) keyword)
                   item-key (str (or (:engine acc) "default") ":" (:userId acc))
                   title    (or (not-empty (:label acc))
                                (not-empty (:display-name acc))
                                (:userId acc))
                   sub      (norm/format-account-subtitle acc)
                   active?  (and (= (str (:userId acc)) (str effective-uid))
                                 (or (nil? acc-eng)
                                     (nil? active-engine)
                                     (= acc-eng (keyword active-engine))))]]
         ^{:key item-key}
         [:div.context-menu-item
          {:class    (when active? "is-selected")
           :on-click (fn [e]
                       (.stopPropagation e)
                       (when-not active?
                         (re-frame/dispatch
                          [:auth/switch-account (:userId acc) (:engine acc)]))
                       (when close-fn (close-fn)))}
          [avatar {:id   (:userId acc)
                   :name (or title "?")
                   :url  (:avatar-url acc)
                   :size 22}]
          [:span.context-menu-item-copy
           [:span.item-label title]
           (when-not (str/blank? sub)
             [:span.context-menu-item-sub sub])]
          (when active?
            [:span.context-menu-item-check
             [icons/check {:size "14px"}]])])
       [:div.context-menu-empty-hint
        (tr [:settings.accounts/empty-state])])
     [:div.context-menu-divider]
     [:div.context-menu-item
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (when close-fn (close-fn))
                   (re-frame/dispatch [:auth/start-login-flow]))}
      [:span.item-icon [icons/plus {:size "15px"}]]
      [:span.item-label (tr [:settings.accounts/add-account])]]
     [:div.context-menu-item
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (when close-fn (close-fn))
                   (re-frame/dispatch [:settings/open :accounts]))}
      [:span.item-icon [icons/settings {:size "15px"}]]
      [:span.item-label (tr [:settings.tabs/accounts])]]
     [:div.context-menu-divider]
     [:div.context-menu-item.danger
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (when close-fn (close-fn))
                   (re-frame/dispatch [:sdk/logout]))}
      [:span.item-icon [icons/leave {:size "15px"}]]
      [:span.item-label (tr [:settings.context-menu/logout])]]]))

(defn ^:ui profile-context-menu-content
  [{:keys [user-id close-fn]}]
  (r/with-let [_ (re-frame/dispatch [:settings/load-accounts])]
    (let [tr         @(re-frame/subscribe [:i18n/tr])
          own-status @(re-frame/subscribe [:presence/own-status])
          profile    @(re-frame/subscribe [:sdk/profile])
          copy-id    (or (:copy-id profile) (:npub profile) user-id (:user-id profile))
          finish!    (or close-fn #(re-frame/dispatch [:ui/close-context-menu]))]
      [:<>
       [:div.context-menu-item
        {:on-click (fn [e]
                     (.stopPropagation e)
                     (finish!)
                     (re-frame/dispatch [:settings/open :my-account]))}
        [:span.item-icon [icons/edit {:size "15px"}]]
        [:span.item-label (tr [:settings.profile/edit-profile])]]
       [context-menu-submenu
        {:id              "profile-ctx-status"
         :icon            [:span.status-menu-swatch-wrap
                           [:span.avatar-status {:class (name (or own-status :online))}]]
         :label           (tr [:settings.context-menu/set-status])
         :close-fn        finish!
         :render-children status-submenu-items}]
       [context-menu-submenu
        {:id              "profile-ctx-switch-account"
         :icon            [icons/members {:size "15px"}]
         :label           (tr [:settings.profile/switch-account])
         :close-fn        finish!
         :render-children account-switch-submenu-items}]
       [:div.context-menu-divider]
       [:div.context-menu-item
        {:on-click (fn [e]
                     (.stopPropagation e)
                     (re-frame/dispatch [:ui/copy-to-clipboard copy-id])
                     (finish!))}
        [:span.item-icon [icons/copy {:size "15px"}]]
        [:span.item-label (tr [:settings.context-menu/copy-id])]]
       [:div.context-menu-item.danger
        {:on-click (fn [e]
                     (.stopPropagation e)
                     (finish!)
                     (re-frame/dispatch [:sdk/logout]))}
        [:span.item-icon [icons/leave {:size "15px"}]]
        [:span.item-label (tr [:settings.context-menu/logout])]]])))

(defmethod context-menu-component
  :profile-actions
  [_]
  profile-context-menu-content)

(defn ^:ui self-profile-popout-content
  [{:keys [close-fn]}]
  (r/with-let [!status-draft (r/atom nil)
               !copied?      (r/atom false)
               _             (re-frame/dispatch [:settings/load-accounts])
               _             (re-frame/dispatch [:sdk/fetch-own-profile])]
    (let [tr            @(re-frame/subscribe [:i18n/tr])
          profile       @(re-frame/subscribe [:sdk/profile])
          active-engine @(re-frame/subscribe [:auth/active-engine])
          own-status    @(re-frame/subscribe [:presence/own-status])
          saved-msg     @(re-frame/subscribe [:presence/own-status-message])
          finish!       (or close-fn #(re-frame/dispatch [:ui/close-popover]))
          user-id       (:user-id profile)
          display-name  (or (first-non-blank (:display-name profile)
                                             (:username profile)
                                             user-id)
                            "User")
          raw-handle    (first-non-blank (:handle profile)
                                         (when-let [u (first-non-blank (:username profile))]
                                           (str "@" u)))
          handle        (when (and raw-handle (not= raw-handle display-name))
                          raw-handle)
          pronouns      (first-non-blank (:pronouns profile))
          bio           (first-non-blank (:bio profile))
          bn-url        (first-non-blank (:banner-url profile))
          bn-color      (first-non-blank (:banner-color profile))
          proto-label   (some-> active-engine clojure.core/name str/capitalize)
          copy-target   (or (:copy-id profile) (:npub profile) user-id)
          draft-val     (if (some? @!status-draft) @!status-draft (or saved-msg ""))
          dirty-msg?    (and (some? @!status-draft)
                             (not= (str/trim @!status-draft) (or saved-msg "")))
          commit-msg!   (fn []
                          (let [next-msg (str/trim (str draft-val))]
                            (re-frame/dispatch [:presence/set-own-status-message next-msg])
                            (reset! !status-draft nil)))]
      [:div.self-profile-popout
       [:div.self-profile-cover
        {:style (when (and (nil? bn-url) bn-color)
                  {:background bn-color})}
        (when bn-url
          (if (local-media-url? bn-url)
            [:img.self-profile-banner-img {:src bn-url :alt ""}]
            [media {:uri     bn-url
                    :user-id user-id
                    :class   "self-profile-banner-img"
                    :alt     ""}]))
        [:button.self-profile-cover-edit
         {:type     "button"
          :title    (tr [:settings.profile/edit-profile])
          :on-click (fn [e]
                      (.stopPropagation e)
                      (finish!)
                      (re-frame/dispatch [:settings/open :my-account]))}
         [icons/edit {:size "13px"}]]]

       [:div.self-profile-header
        [:div.self-profile-avatar-row
         [:div.self-profile-avatar-wrap
          [avatar {:id     user-id
                   :name   display-name
                   :url    (:avatar-url profile)
                   :size   62
                   :status own-status}]]
         (when proto-label
           [:span.settings-hub-proto-pill proto-label])]

        [:div.self-profile-id-block
         [:div.self-profile-name display-name]
         (when (or handle pronouns)
           [:div.self-profile-meta-row
            (when handle
              [:span.self-profile-handle handle])
            (when (and handle pronouns)
              [:span.self-profile-dot "·"])
            (when pronouns
              [:span.self-profile-pronouns pronouns])])
         (when (and user-id (not= user-id display-name) (not= user-id handle))
           [:div.self-profile-uid user-id])
         (when bio
           [:div.self-profile-bio bio])]]

       [:div.self-profile-status-box
        [:div.self-profile-status-input-row
         [:span.self-profile-status-icon
          [icons/smiley {:width "14px" :height "14px"}]]
         [:input.self-profile-status-input
          {:type        "text"
           :max-length  120
           :value       draft-val
           :placeholder (tr [:settings.profile/status-message-placeholder])
           :on-change   #(reset! !status-draft (.. % -target -value))
           :on-key-down (fn [e]
                          (case (.-key e)
                            "Enter"  (do (.preventDefault e)
                                         (commit-msg!)
                                         (.blur (.-currentTarget e)))
                            "Escape" (do (.stopPropagation e)
                                         (reset! !status-draft nil)
                                         (.blur (.-currentTarget e)))
                            nil))}]
         (cond
           dirty-msg?
           [:button.self-profile-status-btn.is-save
            {:type     "button"
             :title    (tr [:settings.profile/save-changes])
             :on-click (fn [e]
                         (.stopPropagation e)
                         (commit-msg!))}
            [icons/check {:size "13px"}]]

           (not (str/blank? saved-msg))
           [:button.self-profile-status-btn
            {:type     "button"
             :title    (tr [:settings.profile/clear-status-message])
             :on-click (fn [e]
                         (.stopPropagation e)
                         (reset! !status-draft nil)
                         (re-frame/dispatch [:presence/set-own-status-message ""]))}
            [icons/exit {:width "12px" :height "12px"}]])]]

       [:div.self-profile-menu
        [:div.context-menu-item
         {:on-click (fn [e]
                      (.stopPropagation e)
                      (finish!)
                      (re-frame/dispatch [:settings/open :my-account]))}
         [:span.item-icon [icons/edit {:size "15px"}]]
         [:span.item-label (tr [:settings.profile/edit-profile])]]

        [context-menu-submenu
         {:id              "self-popout-status"
          :icon            [:span.status-menu-swatch-wrap
                            [:span.avatar-status {:class (name (or own-status :online))}]]
          :label           (str (tr [:settings.context-menu/set-status])
                                " · "
                                (presence-label tr own-status))
          :close-fn        finish!
          :render-children status-submenu-items}]

        [context-menu-submenu
         {:id              "self-popout-switch-account"
          :icon            [icons/members {:size "15px"}]
          :label           (tr [:settings.profile/switch-account])
          :close-fn        finish!
          :render-children account-switch-submenu-items}]

        [:div.context-menu-divider]

        [:div.context-menu-item
         {:on-click (fn [e]
                      (.stopPropagation e)
                      (re-frame/dispatch [:ui/copy-to-clipboard copy-target])
                      (reset! !copied? true)
                      (js/setTimeout #(reset! !copied? false) 1200))}
         [:span.item-icon
          (if @!copied?
            [icons/check {:size "15px"}]
            [icons/copy {:size "15px"}])]
         [:span.item-label
          (if @!copied?
            (tr [:settings.profile/copied-id])
            (tr [:settings.context-menu/copy-id]))]]]])))

(defmethod popover-component
  :self-profile-popout
  [_]
  self-profile-popout-content)

(defn- audio-device-options-menu
  [{:keys [kind
           device-icon
           device-label
           volume-label
           volume-sub
           volume-event
           fallback-label
           close-fn]}]
  (r/with-let [!devices (r/atom {:audioinput [] :videoinput [] :audiooutput []})
               !error   (r/atom nil)
               _        (voice-video/load-call-devices! !devices !error)]
    (let [tr              @(re-frame/subscribe [:i18n/tr])
          selected-device @(re-frame/subscribe [:settings/call-device kind])
          volume          @(re-frame/subscribe [volume-sub])
          devices         (get @!devices kind [])
          default-label   (tr [:settings.voice-video.devices/default])
          finish!         (or close-fn #(re-frame/dispatch [:ui/close-context-menu]))
          default-active? (str/blank? (str (or selected-device "")))]
      [:<>
       [context-menu-submenu
        {:id       (str "audio-device-" (name kind))
         :icon     device-icon
         :label    device-label
         :close-fn finish!
         :render-children
         (fn [_]
           [:<>
            [:div.context-menu-item
             {:class    (when default-active? "is-selected")
              :on-click (fn [e]
                          (.stopPropagation e)
                          (re-frame/dispatch [:settings/set-call-device kind ""])
                          (finish!))}
             [:span.item-label default-label]
             (when default-active?
               [:span.context-menu-item-check [icons/check {:size "14px"}]])]
            (when (seq devices)
              [:div.context-menu-divider])
            (for [[idx device] (map-indexed vector devices)
                  :let [dev-id  (.-deviceId device)
                        active? (and (not default-active?)
                                     (= selected-device dev-id))
                        label   (voice-video/media-device-label device fallback-label idx)]]
              ^{:key (str (name kind) "-" idx "-" dev-id)}
              [:div.context-menu-item
               {:class    (when active? "is-selected")
                :on-click (fn [e]
                            (.stopPropagation e)
                            (re-frame/dispatch [:settings/set-call-device kind dev-id])
                            (finish!))}
               [:span.item-label label]
               (when active?
                 [:span.context-menu-item-check [icons/check {:size "14px"}]])])])}]

       [:div.context-menu-divider]

       [:div.context-menu-slider-block
        {:on-click        #(.stopPropagation %)
         :on-pointer-down #(.stopPropagation %)}
        [:div.context-menu-slider-header
         [:span.context-menu-slider-label volume-label]
         [:span.context-menu-slider-value
          {:title           (tr [:container.call/reset-volume])
           :on-double-click #(re-frame/dispatch [volume-event 100])}
          (str volume "%")]]
        [:input.context-menu-slider-input
         {:type      "range"
          :min       0
          :max       200
          :step      1
          :value     volume
          :on-change #(re-frame/dispatch [volume-event (.. % -target -value)])}]]

       [:div.context-menu-divider]

       [:div.context-menu-item
        {:on-click (fn [e]
                     (.stopPropagation e)
                     (finish!)
                     (re-frame/dispatch [:settings/open :voice-video]))}
        [:span.item-icon [icons/settings {:size "15px" :animate :spin}]]
        [:span.item-label (tr [:settings.tabs/voice-video])]]])))

(defn ^:ui mic-options-context-menu [{:keys [close-fn]}]
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [audio-device-options-menu
     {:kind           :audioinput
      :device-icon    [icons/mic {:width "15px" :height "15px"}]
      :device-label   (tr [:settings.voice-video.devices/input-label])
      :volume-label   (tr [:settings.voice-video.microphone/input-volume])
      :volume-sub     :settings/input-volume
      :volume-event   :settings/set-input-volume
      :fallback-label (tr [:settings.voice-video.devices/microphone])
      :close-fn       close-fn}]))

(defmethod context-menu-component
  :mic-options
  [_]
  mic-options-context-menu)

(defn ^:ui deafen-options-context-menu [{:keys [close-fn]}]
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [audio-device-options-menu
     {:kind           :audiooutput
      :device-icon    [icons/headphones {:width "15px" :height "15px"}]
      :device-label   (tr [:settings.voice-video.devices/output-label])
      :volume-label   (tr [:settings.voice-video.output/output-volume])
      :volume-sub     :settings/output-volume
      :volume-event   :settings/set-output-volume
      :fallback-label (tr [:settings.voice-video.devices/output])
      :close-fn       close-fn}]))

(defmethod context-menu-component
  :deafen-options
  [_]
  deafen-options-context-menu)

(defn ^:ui status-picker-modal [_props]
  (let [tr         @(re-frame/subscribe [:i18n/tr])
        own-status @(re-frame/subscribe [:presence/own-status])]
    [:div.status-picker-modal
     [:div.status-picker-header
      [:span.status-picker-title
       (tr [:settings.profile/status-picker-title])]
      [:button.status-picker-close
       {:type     "button"
        :on-click #(re-frame/dispatch [:ui/close-modal])}
       [icons/exit {:width "16px" :height "16px"}]]]
     [:div.status-picker-list
      (for [{:keys [id label-key desc-key]} presence-options
            :let [active? (= own-status id)]]
        ^{:key id}
        [:button.status-picker-option
         {:type     "button"
          :class    (when active? "is-active")
          :on-click (fn [_]
                      (re-frame/dispatch [:presence/set-own-status id])
                      (re-frame/dispatch [:ui/close-modal]))}
         [:span.status-picker-swatch-wrap
          [:span.avatar-status {:class (name id)}]]
         [:span.status-picker-copy
          [:span.status-picker-label (tr [label-key])]
          [:span.status-picker-desc (tr [desc-key])]]
         (when active?
           [:span.status-picker-check [icons/check {:size "16px"}]])])]]))

(defmethod modal-component
  :status-picker
  [_]
  status-picker-modal)

(defn ^:ui soundboard-popover-content [{:keys [room-id]}]
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:div.soundboard-popover
     [:div.soundboard-popover-header
      [:span.soundboard-popover-title
       [icons/music-note {:width "15px" :height "15px"}]
       [:span (tr [:container.call/soundboard])]]
      [:button.soundboard-popover-close
       {:type     "button"
        :on-click #(re-frame/dispatch [:ui/close-popover])}
       [icons/exit {:width "14px" :height "14px"}]]]
     [:div.soundboard-popover-body
      [plugins/plugin-slot :call/soundboard {:room-id room-id}
       [:div.soundboard-popover-empty
        (tr [:container.call/soundboard-empty])]]]]))

(defmethod popover-component :soundboard [_]
  soundboard-popover-content)

(defn- open-audio-options-menu! [menu-id ^js e]
  (.preventDefault e)
  (.stopPropagation e)
  (let [anchor (or (.closest (.-currentTarget e) ".floating-ctrl-split")
                   (.-currentTarget e))
        rect   (.getBoundingClientRect anchor)]
    (re-frame/dispatch
     [:ui/open-context-menu menu-id
      {:x             (.-left rect)
       :y             (.-top rect)
       :anchor-bottom (+ (- js/window.innerHeight (.-top rect)) 8)
       :width         224
       :height        180}])))

(defn ^:ui sidebar-profile-mini []
  (let [tr              @(re-frame/subscribe [:i18n/tr])
        profile         @(re-frame/subscribe [:sdk/profile])
        own-status      @(re-frame/subscribe [:presence/own-status])
        own-status-msg  @(re-frame/subscribe [:presence/own-status-message])
        active-call-id  @(re-frame/subscribe [:call/active-room])
        audio-on?       @(re-frame/subscribe [:call/audio-enabled?])
        deafened?       @(re-frame/subscribe [:call/deafened?])
        input-vol       @(re-frame/subscribe [:settings/input-volume])
        output-vol      @(re-frame/subscribe [:settings/output-volume])
        mic-muted?      (or (not audio-on?) deafened? (<= (or input-vol 100) 0))
        out-deafened?   (or deafened? (<= (or output-vol 100) 0))
        video-on?       @(re-frame/subscribe [:call/video-enabled?])
        screen-sharing? @(re-frame/subscribe [:call/screen-sharing?])
        rooms-map       (when active-call-id
                          @(re-frame/subscribe [:rooms/unfiltered-indexed-map]))
        call-room       (when active-call-id (get rooms-map active-call-id))
        call-room-name  (or (when call-room
                              (if (map? call-room)
                                (:name call-room)
                                (aget call-room "name")))
                            (tr [:container.call/active-call]))
        display-name    (or (not-empty (:display-name profile))
                            (not-empty (:username profile))
                            (:user-id profile)
                            "User")
        status-line     (or (not-empty (str/trim (str (or own-status-msg ""))))
                            (presence-label tr own-status))]
    [:div.floating-profile-panel.sidebar-profile-mini
     {:class (when active-call-id "in-call")}

     (when active-call-id
       [:div.floating-call-tray
        [:div.floating-call-header
         [:button.floating-call-info
          {:type     "button"
           :title    call-room-name
           :on-click (fn []
                       (re-frame/dispatch [:rooms/select active-call-id])
                       (re-frame/dispatch [:container/set-main-focus :call]))}
          [:span.floating-call-status-row
           [:span.floating-call-dot]
           [:span.floating-call-status (tr [:container.call/voice-connected])]]
          [:span.floating-call-room call-room-name]]
         [:button.floating-ctrl-btn.is-danger
          {:type     "button"
           :title    (tr [:container.call/disconnect])
           :on-click #(re-frame/dispatch [:call/hangup])}
          [icons/phone-hangup {:width "17px" :height "17px"}]]]

        [:div.floating-call-actions
         [:button.floating-call-action-btn
          {:type     "button"
           :class    (when video-on? "is-active")
           :title    (if video-on?
                       (tr [:container.call/stop-video])
                       (tr [:container.call/start-video]))
           :on-click #(re-frame/dispatch [:call/toggle-video])}
          (if video-on?
            [icons/video {:width "16px" :height "16px"}]
            [icons/video-off {:width "16px" :height "16px"}])]

         [:button.floating-call-action-btn
          {:type     "button"
           :class    (when screen-sharing? "is-active-green")
           :title    (if screen-sharing?
                       (tr [:container.call/stop-sharing])
                       (tr [:container.call/share-screen]))
           :on-click #(re-frame/dispatch [:call/toggle-screen-share])}
          (if screen-sharing?
            [icons/screen-share {:width "16px" :height "16px"}]
            [icons/screen-share-off {:width "16px" :height "16px"}])]

         [:button.floating-call-action-btn
          {:type     "button"
           :title    (tr [:container.call/soundboard])
           :on-click (fn [^js e]
                       (let [rect (.getBoundingClientRect (.-currentTarget e))]
                         (re-frame/dispatch
                          [:ui/open-popover :soundboard
                           {:x             (.-left rect)
                            :y             (max 12 (- (.-top rect) 216))
                            :anchor-bottom (+ (- js/window.innerHeight (.-top rect)) 8)
                            :width         260
                            :height        200
                            :backdrop?     true
                            :room-id       active-call-id}])))}
          [icons/music-note {:width "16px" :height "16px"}]]]])

     [:div.floating-profile-row
      [:div.floating-profile-identity.profile-trigger
       {:title           display-name
        :on-click        (fn [^js e]
                           (.preventDefault e)
                           (let [panel (or (.closest (.-currentTarget e) ".floating-profile-panel")
                                           (.-currentTarget e))
                                 rect  (.getBoundingClientRect panel)]
                             (re-frame/dispatch
                              [:ui/open-popover :self-profile-popout
                               {:x             (.-left rect)
                                :y             (max 12 (- (.-top rect) 380))
                                :anchor-bottom (+ (- js/window.innerHeight (.-top rect)) 8)
                                :width         300
                                :height        380
                                :backdrop?     true}])))
        :on-context-menu (fn [e]
                           (.preventDefault e)
                           (re-frame/dispatch
                            [:ui/open-context-menu
                             :profile-actions
                             {:x       (.-clientX e)
                              :y       (.-clientY e)
                              :user-id (:user-id profile)}]))}
       [avatar
        {:id     (:user-id profile)
         :name   display-name
         :url    (:avatar-url profile)
         :size   34
         :status own-status}]
       [:div.floating-profile-copy
        [:span.floating-profile-name display-name]
        [:span.floating-profile-status status-line]]]

      [:div.floating-profile-controls
       [:div.floating-ctrl-split
        {:class           (when mic-muted? "is-muted")
         :on-context-menu #(open-audio-options-menu! :mic-options %)}
        [:button.floating-ctrl-main
         {:type     "button"
          :title    (if mic-muted?
                      (tr [:container.call/unmute])
                      (tr [:container.call/mute]))
          :on-click (fn []
                      (if (and audio-on? (not deafened?) (<= (or input-vol 100) 0))
                        (re-frame/dispatch [:settings/set-input-volume 100])
                        (do
                          (when (and mic-muted? (<= (or input-vol 100) 0))
                            (re-frame/dispatch [:settings/set-input-volume 100]))
                          (re-frame/dispatch [:call/toggle-audio]))))}
         (if mic-muted?
           ^{:key :mic-off} [icons/mic-off {:width "16px" :height "16px"}]
           ^{:key :mic-on} [icons/mic {:width "16px" :height "16px"}])]
        [:button.floating-ctrl-caret
         {:type     "button"
          :title    (tr [:settings.voice-video.devices/input-label])
          :on-click #(open-audio-options-menu! :mic-options %)}
         [icons/chevron-up {:width "11px" :height "11px"}]]]

       [:div.floating-ctrl-split
        {:class           (when out-deafened? "is-muted")
         :on-context-menu #(open-audio-options-menu! :deafen-options %)}
        [:button.floating-ctrl-main
         {:type     "button"
          :title    (if out-deafened?
                      (tr [:container.call/undeafen])
                      (tr [:container.call/deafen]))
          :on-click (fn []
                      (if (and (not deafened?) (<= (or output-vol 100) 0))
                        (re-frame/dispatch [:settings/set-output-volume 100])
                        (do
                          (when (and out-deafened? (<= (or output-vol 100) 0))
                            (re-frame/dispatch [:settings/set-output-volume 100]))
                          (re-frame/dispatch [:call/toggle-deafen]))))}
         (if out-deafened?
           ^{:key :deaf-on} [icons/headphones-off {:width "16px" :height "16px"}]
           ^{:key :deaf-off} [icons/headphones {:width "16px" :height "16px"}])]
        [:button.floating-ctrl-caret
         {:type     "button"
          :title    (tr [:settings.voice-video.devices/output-label])
          :on-click #(open-audio-options-menu! :deafen-options %)}
         [icons/chevron-up {:width "11px" :height "11px"}]]]

       [:button.floating-ctrl-btn.is-settings
        {:type     "button"
         :title    (tr [:settings.groups/user-settings])
         :on-click #(re-frame/dispatch [:settings/open])}
        [icons/settings {:width "17px" :height "17px" :animate :spin}]]]]]))

(def ^:private default-profile-fields
  [{:key             :display-name
    :type            :text
    :label-key       :settings.profile/display-name-label
    :placeholder-key :settings.profile/display-name-placeholder}
   {:key             :pronouns
    :type            :text
    :max-length      60
    :label-key       :settings.profile/pronouns-label
    :placeholder-key :settings.profile/pronouns-placeholder}
   {:key             :bio
    :type            :textarea
    :max-length      500
    :label-key       :settings.profile/bio-label
    :placeholder-key :settings.profile/bio-placeholder}])

(defn- field-base-value [profile schema field-kw]
  (let [raw (if (and (= field-kw :display-name)
                     (:display-name-key schema)
                     (contains? profile (:display-name-key schema)))
              (get profile (:display-name-key schema))
              (get profile field-kw))]
    (if (and (= field-kw :display-name) (= raw "Loading..."))
      ""
      (str (or raw "")))))

(defn- compute-profile-view-state [profile active-engine]
  (let [uid          (:user-id profile)
        _            (ensure-profile-draft-user! uid)
        {:keys [dirty values]} @!profile-draft
        av-draft     @!avatar-draft
        bn-draft     @!banner-draft
        schema       (or (decode-edn-field profile :profile-schema-edn :profile-schema) {})
        fields       (vec (or (seq (:fields schema)) default-profile-fields))
        live-url?    (boolean (:live-url-preview? schema))
        base-vals    (into {}
                           (map (fn [{:keys [key]}]
                                  [key (field-base-value profile schema key)]))
                           fields)
        eff-vals     (into {}
                           (map (fn [{:keys [key]}]
                                  [key (if (contains? dirty key)
                                         (get values key "")
                                         (get base-vals key ""))]))
                           fields)
        changed-map  (into {}
                           (map (fn [{:keys [key type]}]
                                  (let [bv (get base-vals key "")
                                        ev (get eff-vals key "")
                                        eq (if (= (keyword type) :textarea)
                                             (= ev bv)
                                             (= (str/trim ev) (str/trim bv)))]
                                    [key (not eq)])))
                           fields)
        base-av-url  (str (or (:avatar-url profile) ""))
        base-bn-url  (str (or (:banner-url profile) ""))
        eff-av-url   (get eff-vals :avatar-url base-av-url)
        eff-bn-url   (get eff-vals :banner-url base-bn-url)
        av-staged?   (or (some? (:file av-draft)) (boolean (:remove? av-draft)))
        bn-staged?   (or (some? (:file bn-draft)) (boolean (:remove? bn-draft)))
        preview-av   (cond
                       (:preview-url av-draft) (:preview-url av-draft)
                       (:remove? av-draft)     nil
                       (and live-url? (not (str/blank? eff-av-url))) eff-av-url
                       :else                   (:avatar-url profile))
        preview-bn   (cond
                       (:preview-url bn-draft) (:preview-url bn-draft)
                       (:remove? bn-draft)     nil
                       (and live-url? (not (str/blank? eff-bn-url))) eff-bn-url
                       :else                   (not-empty base-bn-url))
        proto-label  (some-> active-engine clojure.core/name str/capitalize)
        save-payload (reduce (fn [acc {:keys [key]}]
                               (-> acc
                                   (assoc key (get eff-vals key ""))
                                   (assoc (keyword (str (name key) "-changed?"))
                                          (boolean (get changed-map key)))))
                             {}
                             fields)]
    {:user-id      uid
     :proto-label  proto-label
     :fields       fields
     :values       eff-vals
     :display-name (get eff-vals :display-name "")
     :banner-color (:banner-color profile)
     :preview-av   preview-av
     :preview-bn   preview-bn
     :has-avatar?  (or (not (str/blank? base-av-url)) (some? (:file av-draft)))
     :has-banner?  (or (not (str/blank? base-bn-url)) (some? (:file bn-draft)))
     :remove-av?   (boolean (:remove? av-draft))
     :remove-bn?   (boolean (:remove? bn-draft))
     :av-staged?   av-staged?
     :bn-staged?   bn-staged?
     :has-changes? (or (boolean (some true? (vals changed-map))) av-staged? bn-staged?)
     :save-payload save-payload}))

(defn- format-hero-handle [profile]
  (let [h (some-> (:handle profile) str str/trim not-empty)]
    (when (and h (not= h (:user-id profile)))
      h)))

(defn ^:ui my-account-tab
  [profile]
  (r/with-let [_ (re-frame/dispatch [:sdk/fetch-own-profile])]
    (let [tr            @(re-frame/subscribe [:i18n/tr])
          active-engine @(re-frame/subscribe [:auth/active-engine])
          saving?       @(re-frame/subscribe [:profile/saving?])
          error-msg     @(re-frame/subscribe [:profile/error])
          saved?        @(re-frame/subscribe [:profile/saved?])
          vs            (compute-profile-view-state profile active-engine)
          user-id       (:user-id vs)
          hero-name     (or (not-empty (:display-name vs))
                            (:username profile)
                            (:display-name profile)
                            (tr [:settings.profile/unknown-user]))
          hero-handle   (format-hero-handle profile)
          copy-target   (or (:copy-id profile) (:npub profile) user-id)]
      [:div.settings-tab-content
       [:input#paradise-profile-avatar-input.settings-hidden-file-input
        {:type      "file"
         :accept    "image/png,image/jpeg,image/webp,image/gif"
         :on-change handle-avatar-file-selected!}]
       [:input#paradise-profile-banner-input.settings-hidden-file-input
        {:type      "file"
         :accept    "image/png,image/jpeg,image/webp,image/gif"
         :on-change handle-banner-file-selected!}]

       [:div.settings-profile-hero
        [:div.settings-profile-banner
         {:class    [(when (:bn-staged? vs) "is-staged")
                     (when (:preview-bn vs) "has-image")]
          :style    (when (and (nil? (:preview-bn vs)) (:banner-color vs))
                      {:background (:banner-color vs)})
          :on-click (fn [_]
                      (when-not saving?
                        (trigger-banner-file-picker!)))}
         (when-let [bn-src (:preview-bn vs)]
           (if (local-media-url? bn-src)
             [:img.settings-profile-banner-img
              {:src bn-src
               :alt ""}]
             [media
              {:uri     bn-src
               :user-id user-id
               :class   "settings-profile-banner-img"
               :alt     ""}]))
         [:div.settings-profile-banner-actions
          [:button.settings-profile-banner-btn
           {:type     "button"
            :disabled saving?
            :on-click (fn [e]
                        (.stopPropagation e)
                        (trigger-banner-file-picker!))}
           (if (:has-banner? vs)
             [icons/edit {:size "13px"}]
             [icons/plus {:size "13px"}])
           [:span
            (if (:has-banner? vs)
              (tr [:settings.profile/change-banner])
              (tr [:settings.profile/add-banner]))]]
          (when (or (:has-banner? vs) (:remove-bn? vs))
            [:button.settings-profile-banner-btn
             {:type     "button"
              :class    (when-not (:remove-bn? vs) "is-danger")
              :disabled saving?
              :on-click (fn [e]
                          (.stopPropagation e)
                          (toggle-remove-banner!))}
             (when-not (:remove-bn? vs)
               [icons/trash {:size "13px"}])
             [:span
              (if (:remove-bn? vs)
                (tr [:settings.profile/undo-remove-banner])
                (tr [:settings.profile/remove-banner]))]])]]
        [:div.settings-profile-body
         [:div.settings-profile-top-row
          [:div.settings-profile-avatar-wrap
           [:button.settings-profile-avatar-btn
            {:type     "button"
             :class    (when (:av-staged? vs) "is-staged")
             :title    (tr [:settings.profile/change-avatar])
             :disabled saving?
             :on-click (fn [_] (trigger-avatar-file-picker!))}
            [avatar
             {:id   user-id
              :name (or hero-name "?")
              :url  (:preview-av vs)
              :size 72}]
            [:span.settings-profile-avatar-overlay
             [icons/edit]]]]

          [:div.settings-profile-hero-actions
           [:button.settings-button.secondary.is-compact
            {:type     "button"
             :disabled saving?
             :on-click (fn [_] (trigger-avatar-file-picker!))}
            [icons/edit {:size "14px"}]
            [:span (tr [:settings.profile/change-avatar])]]
           (when (or (:has-avatar? vs) (:remove-av? vs))
             [:button.settings-button.is-compact
              {:type     "button"
               :class    (if (:remove-av? vs) "secondary" "destructive")
               :disabled saving?
               :on-click (fn [_] (toggle-remove-avatar!))}
              (when-not (:remove-av? vs)
                [icons/trash {:size "14px"}])
              [:span
               (if (:remove-av? vs)
                 (tr [:settings.profile/undo-remove-avatar])
                 (tr [:settings.profile/remove-avatar]))]])]]

         [:div.settings-profile-info
          [:div.settings-profile-name-row
           [:span.settings-profile-name hero-name]
           (when (:proto-label vs)
             [:span.settings-hub-proto-pill (:proto-label vs)])]
          (when hero-handle
            [:div.settings-profile-handle hero-handle])
          [:div.settings-profile-id copy-target]]]]

       [:div.settings-hub-group
        [:div.settings-hub-group-label
         (tr [:settings.profile/edit-heading])]
        [:div.settings-hub-group-card
         [:div.settings-card-stack
          (for [{:keys [key type max-length label-key label placeholder-key placeholder]} (:fields vs)
                :let [fkey (keyword key)
                      ftyp (keyword (or type :text))
                      val  (get-in vs [:values fkey] "")
                      lkw  (normalize-keyword label-key)
                      pkw  (normalize-keyword placeholder-key)
                      lbl  (or (when lkw (tr [lkw])) label (name fkey))
                      ph   (or (when (= fkey :display-name) (not-empty (:username profile)))
                               (when pkw (tr [pkw]))
                               placeholder
                               "")]]
            ^{:key fkey}
            [:div.settings-field
             [:label.settings-label lbl]
             (if (= ftyp :textarea)
               [:textarea.settings-input.settings-textarea
                (cond-> {:value       val
                         :disabled    saving?
                         :placeholder ph
                         :on-change   (fn [e] (set-draft-field! fkey (.. e -target -value)))}
                  max-length (assoc :max-length max-length))]
               [:input.settings-input
                (cond-> {:type        (name ftyp)
                         :value       val
                         :disabled    saving?
                         :placeholder ph
                         :on-change   (fn [e] (set-draft-field! fkey (.. e -target -value)))}
                  max-length (assoc :max-length max-length))])])]

         [:div.settings-profile-footer
          [:div.settings-profile-status
           (cond
             error-msg
             [:div.settings-error error-msg]

             saved?
             [:span.settings-profile-saved
              [icons/check]
              (tr [:settings.profile/saved])])]

          [:div.settings-profile-footer-btns
           [:button.settings-button.secondary.is-compact
            {:type     "button"
             :disabled (or (not (:has-changes? vs)) saving?)
             :on-click (fn [_] (reset-profile-draft! user-id))}
            (tr [:settings.profile/reset])]
           [:button.settings-button.is-compact
            {:type     "button"
             :class    (when saving? "is-busy")
             :disabled (or (not (:has-changes? vs)) saving?)
             :on-click (fn [_]
                         (re-frame/dispatch
                          [:sdk/update-own-profile (:save-payload vs)]))}
            (if saving?
              (tr [:settings.profile/saving])
              (tr [:settings.profile/save-changes]))]]]]]

       [:div.settings-hub-group
        [:div.settings-hub-group-label
         (tr [:settings.profile/account-actions])]
        [:div.settings-hub-group-card
         [:button.settings-hub-item
          {:type     "button"
           :on-click #(re-frame/dispatch [:ui/open-modal :status-picker])}
          [:span.settings-hub-icon.accent-amber [icons/smiley]]
          [:span.settings-hub-item-copy
           [:span.settings-hub-item-title
            (tr [:settings.context-menu/set-status])]]
          [:span.settings-hub-chevron [icons/chevron-down]]]

         [:button.settings-hub-item
          {:type     "button"
           :on-click #(re-frame/dispatch [:ui/copy-to-clipboard copy-target])}
          [:span.settings-hub-icon.accent-indigo [icons/copy]]
          [:span.settings-hub-item-copy
           [:span.settings-hub-item-title
            (tr [:settings.context-menu/copy-id])]
           [:span.settings-hub-item-subtitle copy-target]]]

         [:button.settings-hub-item
          {:type     "button"
           :on-click #(re-frame/dispatch [:settings/set-tab :accounts])}
          [:span.settings-hub-icon.accent-sky [icons/members]]
          [:span.settings-hub-item-copy
           [:span.settings-hub-item-title
            (tr [:settings.tabs/accounts])]
           [:span.settings-hub-item-subtitle
            (tr [:settings.accounts/description])]]
          [:span.settings-hub-chevron [icons/chevron-down]]]]]

       [:div.settings-hub-group
        [:div.settings-hub-group-card
         [:button.settings-hub-item.is-danger
          {:type     "button"
           :on-click (fn [_]
                       (re-frame/dispatch [:ui/close-modal])
                       (re-frame/dispatch [:sdk/logout]))}
          [:span.settings-hub-icon.accent-danger [icons/leave]]
          [:span.settings-hub-item-copy
           [:span.settings-hub-item-title
            (tr [:settings.context-menu/logout])]]]]]])))