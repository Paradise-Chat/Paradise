(ns paradise.ui.overlays.settings.profile
  (:require
   [cljs.core.async :refer [<! go]]
   [cljs-workers.mesh :as mesh]
   [paradise.ui.global :refer [avatar]]
   [paradise.ui.overlays.base :refer [context-menu-component]]
   [re-frame.core :as re-frame]
   [taoensso.timbre :as log]))

(re-frame/reg-sub
 :sdk/profile
 (fn [db _]
   (get db
        :current-user
        {:user-id "Loading..."
         :display-name "Loading..."
         :avatar-url nil})))

(re-frame/reg-event-fx
 :sdk/fetch-own-profile
 (fn [_ _]
   (go
     (let [res
           (<! (mesh/do-with-thread!
                :engine-pool
                {:handler :fetch-profile}))]
       (when (= (:status res) "success")
         (re-frame/dispatch
          [:sdk/set-own-profile
           (:profile res)]))))
   {}))

(re-frame/reg-event-db
 :sdk/set-own-profile
 (fn [db [_ profile]]
   (assoc db :current-user profile)))

(re-frame/reg-event-fx
 :settings/update-media-preview-policy
 (fn [{:keys [db]} [_ policy]]
   (let [policy-kw (keyword policy)]
     (go
       (let [res
             (<! (mesh/do-with-thread!
                  :engine-pool
                  {:handler :set-media-preview-policy
                   :arguments {:policy policy-kw}}))]
         (when-not (= (:status res) "success")
           (log/error
            "Failed to sync media policy to Matrix:"
            (:msg res)))))
     {:db
      (assoc-in
       db
       [:settings :media-previews]
       policy-kw)})))

(re-frame/reg-event-db
 :settings/receive-media-preview-config
 (fn [db [_ policy]]
   (assoc-in
    db
    [:settings :media-previews]
    policy)))

(re-frame/reg-sub
 :settings/media-preview-policy
 (fn [db _]
   (get-in
    db
    [:settings :media-previews]
    :off)))


(defn build-profile-actions
  [tr user-id]
  [{:id "status"
    :label (tr [:settings.context-menu/set-status])
    :dispatch [:ui/open-modal :status-picker]}
   {:id "copy"
    :label (tr [:settings.context-menu/copy-id])
    :dispatch [:ui/copy-to-clipboard user-id]}
   {:id "logout"
    :label (tr [:settings.context-menu/logout])
    :class-name "danger"
    :dispatch [:sdk/logout]}])

(defn ^:ui profile-context-menu-content
  [{:keys [user-id]}]
  (let [tr    @(re-frame/subscribe [:i18n/tr])
        items (build-profile-actions tr user-id)]
    [:<>
     (for [{:keys [id label dispatch class-name icon]} items]
       ^{:key (or id label)}
       [:div.context-menu-item
        {:class class-name
         :on-click
         (fn [e]
           (.stopPropagation e)
           (when dispatch
             (re-frame/dispatch dispatch))
           (re-frame/dispatch
            [:ui/close-context-menu]))}
        (when icon
          [:span.item-icon icon])
        [:span.item-label label]])]))

(defmethod context-menu-component
  :profile-actions
  [_]
  profile-context-menu-content)

(defn ^:ui sidebar-profile-mini []
  (let [profile @(re-frame/subscribe [:sdk/profile])]
    [:div.sidebar-profile-mini
     [:div.profile-trigger
      {:on-click
       (fn [e]
         (.preventDefault e)
         (re-frame/dispatch [:settings/open]))
       :on-context-menu
       (fn [e]
         (.preventDefault e)
         (re-frame/dispatch
          [:ui/open-context-menu
           :profile-actions
           {:x (.-clientX e)
            :y (.-clientY e)
            :user-id (:user-id profile)}]))}
      [avatar
       {:id (:user-id profile)
        :name (or (:display-name profile) "?")
        :url (:avatar-url profile)
        :size 40}]
      [:div.status-dot]]]))

(defn ^:ui my-account-tab
  [profile]
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:div.settings-tab-content
     [:div.settings-page-header
      [:h2.settings-heading
       (tr [:settings.profile/title])]]

     [:div.settings-card.settings-profile-card
      [avatar
       {:id (:user-id profile)
        :name (or
               (:display-name profile)
               "?")
        :url (:avatar-url profile)
        :size 80}]

      [:div.settings-profile-info
       [:div.settings-profile-name
        (or
         (:display-name profile)
         (tr [:settings.profile/unknown-user]))]
       [:div.settings-profile-id
        (:user-id profile)]]]]))