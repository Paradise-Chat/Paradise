(ns paradise.ui.overlays.profiles
  (:require
   [paradise.ui.overlays.base :refer [popover-component context-menu-component]]
   [taoensso.timbre :as log]
   [re-frame.core :as re-frame]
   [paradise.ui.global :refer [avatar]]
   [paradise.media.component :refer [media]]
   [paradise.shared.plugins :as plugins]))

(defn ^:ui profile-preview-content [args]
  (let [{:keys [member tags]} args
        pl         (:power-level member)
        tag-data   (get tags (keyword (str pl)))
        role-name  (:name tag-data)
        role-color (or (:color tag-data) "var(--text-primary)")
        icon-uri   (some-> tag-data :icon :key)]
    [:div.profile-preview-card
     [:div.profile-preview-cover]
     [:div.profile-preview-content
      [:div.profile-preview-avatar-wrap
       [avatar {:id   (:user-id member)
                :name (:display-name member)
                :url  (:avatar-url member)
                :size 64}]]
      [:div.profile-preview-id-block
       [:span.profile-preview-name {:style {:color role-color}}
        (:display-name member)]
       [:span.profile-preview-id
        (:user-id member)]]
      (when role-name
        [:div.profile-preview-role-row
         (when icon-uri
           [media {:uri   icon-uri
                   :class "member-item-role-icon"
                   :alt   role-name}])
         [:span.profile-preview-role-text {:style {:color role-color}}
          role-name]])
      [plugins/plugin-slot :profile-actions {:member member}]]]))


(defmethod popover-component :profile-preview [_]
  profile-preview-content)

(defn build-member-actions [{:keys [user-id display-name]} active-room]
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [{:id "view-profile"
      :label (tr [:container.member-actions/view-profile])
      :action #(log/info "View profile for:" user-id)}
     {:id "mention"
      :label (tr [:container.member-actions/mention] [display-name])
      :action #(log/info "Mention:" user-id)}
     {:id "message"
      :label (tr [:container.member-actions/message])
      :action #(log/info "DM:" user-id)}
     {:id "kick"
      :label (tr [:container.member-actions/kick])
      :class-name "text-danger"
      :action #(log/info "Kick:" user-id)}]))

(defn ^:ui member-context-menu-content [{:keys [member active-room x y close-fn]}]
  (let [items (build-member-actions member active-room)]
    [:<>
     (for [{:keys [id label dispatch action class-name icon]} items]
       ^{:key (or id label)}
       [:div.context-menu-item
        {:class class-name
         :on-click (fn [e]
                     (.stopPropagation e)
                     (when dispatch
                       (re-frame/dispatch dispatch))
                     (when action
                       (action))
                     (re-frame/dispatch [:ui/close-context-menu]))}
        (when icon [:span.item-icon icon])
        [:span.item-label label]])]))

(defmethod context-menu-component :member-actions [_]
  member-context-menu-content)