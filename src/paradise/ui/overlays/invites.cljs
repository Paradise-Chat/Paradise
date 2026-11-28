(ns paradise.ui.overlays.invites
  (:require
   [paradise.ui.global :refer [avatar handle-list-navigation selectable-list]]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [clojure.string :as str]
   [paradise.shared.utils.helpers :refer [truncate-name]]
   [paradise.ui.overlays.base :refer [modal-component popover-component]]))

(defn ^:ui invite-menu-content [{:keys [target-room-id]}]
  (let [tr        @(re-frame/subscribe [:i18n/tr])
        rooms-map @(re-frame/subscribe [:rooms/unfiltered-indexed-map])
        dm-rooms  (->> (vals rooms-map)
                       (filter #(and (or (:isDirect %) (:is-direct? %) (:is-direct %))
                                     (not (or (:isGroupDm %) (:is-group-dm? %)))
                                     (not= (or (:id %) (:roomId %)) target-room-id))))
        close-fn  #(re-frame/dispatch [:ui/close-modal])]
    (r/with-let [query          (r/atom "")
                 selected-index (r/atom 0)]
      (let [lower-query    (str/lower-case @query)
            trimmed-q      (str/trim @query)
            is-raw-id?     (or (re-matches #"^@.+:.+$" trimmed-q)
                               (re-matches #"^\d{15,22}$" trimmed-q)
                               (re-matches #"^npub1[0-9a-z]{58}$" trimmed-q))
            filtered-dms   (if (empty? @query)
                             (take 15 dm-rooms)
                             (->> dm-rooms
                                  (filter #(str/includes?
                                            (str/lower-case (or (:name %) "unknown"))
                                            lower-query))
                                  (take 15)))
            final-items    (if is-raw-id?
                             (vec (cons {:is-raw-id? true :user-id trimmed-q} filtered-dms))
                             (vec filtered-dms))
            on-select-item (fn [item]
                             (when item
                               (if (:is-raw-id? item)
                                 (re-frame/dispatch [:rooms/invite-user
                                                     target-room-id
                                                     (:user-id item)])
                                 (re-frame/dispatch [:rooms/invite-from-dm
                                                     target-room-id
                                                     (or (:id item) (:roomId item))]))
                               (close-fn)))]
        [:div.quick-switcher-container
         [:input.form-input.quick-switcher-input
          {:type "text"
           :auto-focus true
           :placeholder (tr [:invites/placeholder-users])
           :value @query
           :on-change (fn [e]
                        (reset! query (.. e -target -value))
                        (reset! selected-index 0))
           :on-key-down (fn [e]
                          (when (= (.-key e) "Escape")
                            (close-fn))
                          (handle-list-navigation
                           e final-items @selected-index
                           #(reset! selected-index %)
                           on-select-item))}]
         [:div.quick-switcher-results
          [selectable-list
           {:items          final-items
            :selected-index @selected-index
            :empty-text     (tr [:invites/empty-users])
            :item-class     "quick-switcher-item"
            :key-fn         #(or (:user-id %) (:id %) (:roomId %))
            :on-highlight   #(reset! selected-index %)
            :on-select      on-select-item
            :render-item    (fn [item _]
                              (if (:is-raw-id? item)
                                [:div.invite-user-row
                                 [avatar {:id    (:user-id item)
                                          :name  "?"
                                          :size  28
                                          :shape :rounded}]
                                 [:div.invite-user-info
                                  [:span.name.raw-id (tr [:invites/invite-user-by-id])]
                                  [:span.sub-name (:user-id item)]]]
                                (let [room-id      (or (:id item) (:roomId item))
                                      members      @(re-frame/subscribe [:room/members-map room-id])
                                      profile      @(re-frame/subscribe [:sdk/profile])
                                      my-id        (:user-id profile)
                                      other        (when (seq members)
                                                     (->> (vals members)
                                                          (remove #(= (:user-id %) my-id))
                                                          first))
                                      final-avatar (or (:avatarUrl item) (:avatar item) (:avatar-url other))
                                      final-name   (truncate-name (if (and other (or (nil? (:name item)) (= (:name item) (tr [:invites/loading]))))
                                                     (:display-name other)
                                                     (:name item)) 16)]
                                  [:div.invite-user-row
                                   [avatar {:id    room-id
                                            :name  (or final-name "?")
                                            :url   final-avatar
                                            :size  28
                                            :shape :rounded}]
                                   [:div.invite-user-info
                                    [:span.name final-name]
                                    (when other
                                      [:span.sub-name (:user-id other)])]])))}]]]))))



(defn ^:ui invite-room-content [{:keys [target-space-id]}]
  (let [tr        @(re-frame/subscribe [:i18n/tr])
        rooms-map @(re-frame/subscribe [:rooms/unfiltered-indexed-map])
        eligible-rooms (->> (vals rooms-map)
                            (remove #(or (:isDirect %) (:is-direct? %) (:is-direct %))))
        close-fn  #(re-frame/dispatch [:ui/close-modal])]
    (r/with-let [query          (r/atom "")
                 selected-index (r/atom 0)]
      (let [lower-query    (str/lower-case @query)
            is-room-alias? (re-matches #"^[!#].+:.+$" @query)
            filtered-rooms (->> eligible-rooms
                                (filter #(str/includes?
                                          (str/lower-case (or (:name %) "unknown"))
                                          lower-query))
                                (sort-by (juxt #(not (or (:isSpace %) (:is-space? %)))
                                               #(str/lower-case (or (:name %) ""))))
                                (take 15))
            final-items    (if is-room-alias?
                             (vec (cons {:is-raw-id? true :room-id @query} filtered-rooms))
                             (vec filtered-rooms))
            on-select-item (fn [item]
                             (let [room-to-add (if (:is-raw-id? item)
                                                 (:room-id item)
                                                 (or (:id item) (:roomId item)))]
                               (re-frame/dispatch [:space/add-room target-space-id room-to-add])
                               (close-fn)))]
        [:div.quick-switcher-container
         [:input.form-input.quick-switcher-input
          {:type "text"
           :auto-focus true
           :placeholder (tr [:invites/placeholder-rooms])
           :value @query
           :on-change (fn [e]
                        (reset! query (.. e -target -value))
                        (reset! selected-index 0))
           :on-key-down (fn [e]
                          (when (= (.-key e) "Escape")
                            (close-fn))
                          (handle-list-navigation
                           e final-items @selected-index
                           #(reset! selected-index %)
                           on-select-item))}]
         [:div.quick-switcher-results
          [selectable-list
           {:items          final-items
            :selected-index @selected-index
            :empty-text     (tr [:invites/empty-rooms])
            :item-class     "quick-switcher-item"
            :key-fn         #(or (:room-id %) (:id %) (:roomId %))
            :on-highlight   #(reset! selected-index %)
            :on-select      on-select-item
            :render-item    (fn [item _]
                              (if (:is-raw-id? item)
                                [:div.invite-user-row
                                 [avatar {:id    (:room-id item)
                                          :name  "#"
                                          :size  28
                                          :shape :rounded}]
                                 [:div.invite-user-info
                                  [:span.name.raw-id (tr [:invites/add-by-alias-rooms])]
                                  [:span.sub-name (:room-id item)]]]
                                (let [room-id   (or (:id item) (:roomId item))
                                      is-space? (or (:isSpace item) (:is-space? item))
                                      name      (truncate-name (or (:name item) (tr [:invites/loading])) 16)
                                      avatar-url (or (:avatarUrl item) (:avatar item))]
                                  [:div.invite-user-row
                                   [avatar {:id    room-id
                                            :name  (or name "?")
                                            :url   avatar-url
                                            :size  28
                                            :shape :rounded}]
                                   [:div.invite-user-info
                                    [:span.name name]
                                    [:span.sub-name (if is-space?
                                                      (tr [:invites/space])
                                                      (tr [:invites/room]))]]])))}]]]))))

(defmethod modal-component :invite-user [_]
  invite-menu-content)

(defmethod modal-component :invite-room [_]
  invite-room-content)