(ns paradise.ui.navigation.spaces.bar
  (:require
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [cljs.reader :as reader]
   ["react-virtuoso" :refer [Virtuoso]]
   [cljs-workers.core :as main]
   [paradise.shared.client.state :as state]
   [paradise.shared.client.session-store :as store]
   [cljs.core.async :refer [go <!]]
   [paradise.ui.overlays.settings.profile :refer [sidebar-profile-mini]]
   [paradise.shared.utils.svg :as icons]
   [paradise.shared.utils.macros :refer [defui]]
   [paradise.ui.global :refer [avatar]]))

(re-frame/reg-event-db
 :sdk/set-spaces-list-sync
 (fn [db [_ spaces-vec]]
   (let [spaces-map (into {} (for [s spaces-vec :when (:id s)] [(:id s) s]))]
     (-> db
         (assoc :spaces-list spaces-vec)
         (assoc :spaces spaces-map)))))

(re-frame/reg-event-db
 :sdk/update-space-view
 (fn [db [_ space-id space-items]]
   (let [children-refs (mapv (fn [item]
                               {:id        (:id item)
                                :is-space? (:is-space? item)})
                             space-items)]
     (assoc-in db [:space-children space-id] children-refs))))

(re-frame/reg-event-fx
 :sdk/paginate-space
 (fn [_ [_ space-id]]
   (main/do-with-pool! @state/!engine-pool {:handler :paginate-space
                                            :arguments {:space-id space-id}})
   {}))

(re-frame/reg-event-db
 :space/hydrate-hierarchies
 (fn [db [_ raw-cache]]
   (let [parsed-cache (try
                        (if (string? raw-cache)
                          (reader/read-string raw-cache)
                          {})
                        (catch :default _ {}))]
     (assoc db :space-hierarchies-cache parsed-cache))))

(re-frame/reg-event-fx
 :space/save-hierarchy-cache
 (fn [{:keys [db]} [_ user-id space-id fresh-rooms]]
   (let [updated-cache (assoc-in (:space-hierarchies-cache db {}) [user-id space-id] fresh-rooms)]
     (store/set-setting! "space_hierarchies" (pr-str updated-cache))
     {:db (assoc db :space-hierarchies-cache updated-cache)})))


(re-frame/reg-event-db
 :space/process-hierarchy
 (fn [db [_ root-space-id rooms]]
   (let [hierarchy-orders
         (reduce
          (fn [acc room]
            (let [parent-id       (:room_id room)
                  children-events (:children_state room)]
              (if (seq children-events)
                (let [child-map (reduce
                                 (fn [cmap event]
                                   (if (= (:type event) "m.space.child")
                                     (let [child-id (:state_key event)
                                           order    (get-in event [:content :order] "zzzz")]
                                       (assoc cmap child-id order))
                                     cmap))
                                 {} children-events)]
                  (assoc acc parent-id child-map))
                acc)))
          {} rooms)]
     (update db :space-orders merge hierarchy-orders))))


(re-frame/reg-event-fx
 :sdk/fetch-space-hierarchy
 (fn [{:keys [db]} [_ space-id]]
   (let [current-user       (:active-user-id db)
         cached-data        (get-in db [:space-hierarchies-cache current-user space-id])
         valid-cache?       (coll? cached-data)
         initial-dispatches (when (and valid-cache? (seq cached-data))
                              [[:space/process-hierarchy space-id cached-data]
                               [:rooms/cache-hierarchy-previews cached-data]])]
     (go
       (let [pool @state/!engine-pool
             res  (<! (main/do-with-pool! pool {:handler :fetch-space-hierarchy
                                                :arguments {:space-id space-id}}))]
         (when (= (:status res) "success")
           (let [fresh-rooms (:rooms res)]
             (re-frame/dispatch [:space/save-hierarchy-cache current-user space-id fresh-rooms])
             (re-frame/dispatch [:space/process-hierarchy space-id fresh-rooms])
             (re-frame/dispatch [:rooms/cache-hierarchy-previews fresh-rooms])))))
     (if initial-dispatches
       {:dispatch-n initial-dispatches}
       {}))))




(re-frame/reg-event-db
 :rooms/cache-hierarchy-previews
 (fn [db [_ rooms-list]]
   (let [new-previews (reduce (fn [acc r]
                                (let [id (or (:room_id r) (:roomId r))]
                                  (if id
                                    (assoc acc id
                                           {:name         (:name r)
                                            :avatarUrl    (or (:avatar_url r) (:avatarUrl r))
                                            :topic        (:topic r)
                                            :joined-count (str (:num_joined_members r))
                                            :is-space?    (= (:room_type r) "m.space")})
                                    acc)))
                              {}
                              rooms-list)]
     (update db :room-previews merge new-previews))))

(re-frame/reg-event-db
 :space/hydrate-space
 (fn [db [_ space-id]]
   (if (:push-routed? db)
     db
     (assoc db :active-space-id space-id))))

(re-frame/reg-event-fx
 :space/select
 (fn [{:keys [db]} [_ space-id]]
   (let [current-space (:active-space-id db)
         current-user       (:active-user-id db)]
     (store/set-setting! (str "last_space_" current-user) space-id)
     (if (= current-space space-id)
       {}
       {:db (-> db
                (assoc :active-space-id space-id)
                (assoc :active-room-id nil))
        :dispatch-n (if space-id
                      [[:sdk/paginate-space space-id]
                       [:sdk/fetch-room-emotes :space space-id]
                       [:sdk/fetch-space-hierarchy space-id]]
                      [[:room-list/apply-filter "people"]])}))))



(re-frame/reg-sub :spaces/all (fn [db _] (get db :spaces-list [])))
(re-frame/reg-sub :spaces/active-id (fn [db _] (:active-space-id db)))
(re-frame/reg-sub :space-children/map (fn [db _] (:space-children db)))

(re-frame/reg-sub
 :spaces/active-metadata
 :<- [:spaces/active-id]
 :<- [:spaces/all]
 (fn [[active-id all-spaces] _]
   (when active-id
     (some #(when (= (:id %) active-id) %) all-spaces))))

(re-frame/reg-sub
 :space/children
 :<- [:space-children/map]
 :<- [:rooms/all]
 (fn [[rel-map all-rooms] [_ parent-id]]
   (let [child-refs (get rel-map parent-id [])
         rich-lookup (into {} (map (fn [r] [(or (:id r) (.-roomId r)) r]) all-rooms))]
     (mapv (fn [{:keys [id is-space?]}]
             (let [rich-room (get rich-lookup id)]
               (merge {:id id :is-space? is-space? :name "Loading..."}
                      rich-room)))
           child-refs))))

(defui space-icon-item [item active-id]
  (let [{:keys [id name avatar-url is-dm?]} item
        stats (if is-dm? item @(re-frame/subscribe [:space/unread-stats id]))
        {:keys [unread? highlight? mentions msg-count]} stats
        badge-count (if is-dm? msg-count mentions)

        profile          @(re-frame/subscribe [:sdk/profile])
        my-id            (:user-id profile)
        needs-members?   is-dm?
        members-map      (when is-dm? @(re-frame/subscribe [:room/members-map id]))
        members-loading? (when is-dm? @(re-frame/subscribe [:room/members-loading? id]))]

    (when (and needs-members? (not members-loading?) (empty? members-map))
      (re-frame/dispatch [:room/fetch-members id]))

    (let [other-user   (when (and is-dm? (seq members-map))
                         (->> (vals members-map)
                              (remove #(= (:user-id %) my-id))
                              first))
          final-avatar (or avatar-url (:avatar-url other-user))
          final-name   (if (and is-dm? other-user (= name "Loading..."))
                         (:display-name other-user)
                         name)]

      [:div.space-icon-wrapper
       {:class [(when (= id active-id) "active")
                (when unread? "has-unread")
                (when highlight? "has-highlight")
                (when is-dm? "is-dm-entry")]
        :on-click #(if is-dm?
                     (do
                       (re-frame/dispatch [:space/select nil])
                       (re-frame/dispatch [:rooms/select id]))
                     (re-frame/dispatch [:space/select id]))
        :title final-name}
       (when unread? [:div.space-notification-pill])
       [avatar {:id id :name final-name :url final-avatar :size 42 :shape :squircle}]
       (when (and badge-count (pos? badge-count))
         [:div.space-mention-badge badge-count])])))

(defui virtualized-space-bar [spaces active-space-id]
  (let [space-array (to-array spaces)]
    [:> Virtuoso
     {:style {:height "100%" :width "100%"}
      :data space-array
      :itemContent
      (fn [index space-map]
        (r/as-element
         ^{:key (:id space-map)}
         [space-icon-item space-map active-space-id]))}]))




(defui spaces-sidebar []
  (let [spaces        @(re-frame/subscribe [:spaces/all])
        active-id     @(re-frame/subscribe [:spaces/active-id])
        active-dms    @(re-frame/subscribe [:rooms/active-dm-pops])
        tr            @(re-frame/subscribe [:i18n/tr])]
    [:div.sidebar-spaces
     [:div.home-group
      [:div.space-icon-wrapper
       {:class (when (nil? active-id) "active")
        :on-click #(re-frame/dispatch [:space/select nil])
        :title (tr [:navigation.spaces.bar/home])}
       [:div.home-icon-container [icons/sun {:animate :sun-pulse :size "32px"}]]]
      (for [dm active-dms]
        ^{:key (:id dm)}
        [space-icon-item dm active-id])]
     [:div.sidebar-divider]
     [:div.spaces-list-container
      [virtualized-space-bar spaces active-id]]
     [sidebar-profile-mini]]))
