(ns paradise.ui.navigation.rooms.room-list
  (:require
   [re-frame.core :as re-frame]
   [taoensso.timbre :as log]
   [cljs-workers.core :as main]
   [paradise.shared.client.state :as state]
   [paradise.shared.client.session-store :as store]
   [paradise.shared.utils.macros :refer [defui]]
   [paradise.shared.utils.svg :as icons]
   ["react-virtuoso" :refer [Virtuoso]]
   [paradise.ui.navigation.rooms.entry :refer [render-room-item active-call-panel]]
   [cljs.core.async :refer [go <!]]))

(re-frame/reg-event-db
 :rooms/parent-resolved
 (fn [db _]
   (update-in db [:ui :parent-refresh-counter] (fnil inc 0))))

(re-frame/reg-event-db
 :room-list/set-home-rooms-sync
 (fn [db [_ rooms]]
   (assoc db :rooms rooms)))

(re-frame/reg-event-db
 :room-list/set-bg-rooms-sync
 (fn [db [_ rooms-list]]
   (let [rooms-map (reduce (fn [acc r]
                             (let [id (or (:id r) (:roomId r))]
                               (if id
                                 (assoc acc id r)
                                 acc)))
                           {} rooms-list)]
     (assoc db :rooms-unfiltered-cache rooms-map))))

(defn distinct-by [f coll]
  (let [!seen (atom #{})]
    (filterv (fn [x]
               (let [k (f x)]
                 (if (contains? @!seen k)
                   false
                   (do (swap! !seen conj k)
                       true))))
             coll)))

(defn safe-get [obj k-str k-kw]
  (if (map? obj)
    (get obj k-kw)
    (let [v (aget obj k-str)]
      (if (undefined? v)
        (aget obj (name k-kw))
        v))))




(defn flatten-tree [rooms-map children-map space-orders closed-drawers previews-map join-mode? parent-id depth]
  (let [sdk-children    (get children-map parent-id [])
        manual-children (->> (vals rooms-map)
                             (filter #(= (safe-get % "first-parent-id" :first-parent-id) parent-id))
                             (map (fn [r] {:id        (or (safe-get r "id" :id) (safe-get r "roomId" :roomId))
                                           :is-space? (safe-get r "isSpace" :isSpace)})))

        orders-for-parent (get space-orders parent-id {})

        child-refs      (->> (concat sdk-children manual-children)
                             (distinct-by :id)
                             (sort-by #(get orders-for-parent (:id %) "zzzz"))
                             (vec))]
    (reduce
     (fn [acc {:keys [id is-space?]}]
       (let [summary   (get rooms-map id)
             preview   (get previews-map id)
             unjoined? (nil? summary)]
         (if (and unjoined? (not join-mode?))
           acc
           (let [item          {:id                         id
                                :is-space?                  is-space?
                                :parent-id                  parent-id
                                :depth                      depth
                                :is-unjoined?               unjoined?
                                :activeRoomCallParticipants (when summary (safe-get summary "activeRoomCallParticipants" :activeRoomCallParticipants))
                                :name                       (if summary
                                                              (or (safe-get summary "name" :name) "Loading...")
                                                              (or (:name preview) "Loading..."))
                                :avatar                     (if summary
                                                              (safe-get summary "avatar" :avatar)
                                                              (or (:avatarUrl preview) (:avatar-url preview)))
                                :unreadNotificationsCount   (if summary (or (safe-get summary "unreadNotificationsCount" :unreadNotificationsCount) 0) 0)
                                :unreadMentionsCount        (if summary (or (safe-get summary "unreadMentionsCount" :unreadMentionsCount) 0) 0)}
                 acc-with-item (conj acc item)]

             (if (and is-space? (not (contains? closed-drawers id)))
               (into acc-with-item (flatten-tree rooms-map children-map space-orders closed-drawers previews-map join-mode? id (inc depth)))
               acc-with-item)))))
     []
     child-refs)))



(re-frame/reg-sub :rooms/active-id (fn [db _] (:active-room-id db)))
(re-frame/reg-sub :space-rooms (fn [db _] (vec (or (:space-rooms db) []))))
(re-frame/reg-sub :rooms/all (fn [db _] (let [rooms (:rooms db)] (if (map? rooms) (vec (vals rooms)) (vec (or rooms []))))))


(re-frame/reg-sub
 :rooms/active-metadata
 :<- [:rooms/active-id]
 :<- [:rooms/unfiltered-indexed-map]
 (fn [[active-id rooms-map] _]
   (when active-id (get rooms-map active-id))))


(re-frame/reg-event-fx
 :room-list/paginate-global
 (fn [_ _]
   (main/do-with-pool! @state/!engine-pool {:handler :paginate-room-list})
   {}))

(re-frame/reg-event-db
 :rooms/set-preview
 (fn [db [_ room-id preview-data]]
   (assoc-in db [:room-previews room-id] preview-data)))

(re-frame/reg-sub
 :rooms/preview
 (fn [db [_ room-id]]
   (get-in db [:room-previews room-id])))

(re-frame/reg-event-db
 :rooms/toggle-join-mode
       (fn [db _]
        (update db :room-list/join-mode? not)))

(re-frame/reg-sub
 :rooms/join-mode?
       (fn [db _]
        (get db :room-list/join-mode? false)))

(re-frame/reg-sub
 :rooms/previews-map
       (fn [db _]
        (get db :room-previews {})))



(re-frame/reg-sub :rooms/unfiltered-indexed-map (fn [db _] (get db :rooms-unfiltered-cache {})))



(re-frame/reg-sub :rooms/ordered-list (fn [db _] (get db :rooms [])))


(re-frame/reg-sub :rooms/space-orders (fn [db _] (get db :space-orders {})))

(re-frame/reg-sub
 :rooms/indexed-map
 :<- [:rooms/all]
 (fn [rooms-list _]
   (reduce (fn [acc room]
             (let [id (or (:id room) (:roomId room))]
               (if id
                 (assoc acc id room)
                 acc)))
           {} rooms-list)))

(re-frame/reg-event-db
 :rooms/apply-parent-resolution
 (fn [db [_ room-id first-parent-id]]
   (-> db
       (assoc-in [:rooms-unfiltered-cache room-id :first-parent-id] first-parent-id)
       (update :rooms (fn [rooms]
                        (if (sequential? rooms)
                          (mapv (fn [r]
                                  (if (= (:id r) room-id)
                                    (assoc r :first-parent-id first-parent-id)
                                    r))
                                rooms)
                          rooms))))))

(re-frame/reg-sub
 :rooms/current-view
 :<- [:spaces/active-id]
 :<- [:rooms/ordered-list]
 :<- [:rooms/unfiltered-indexed-map]
 :<- [:space-children/map]
 :<- [:rooms/space-orders]
 :<- [:rooms/closed-drawers]
 :<- [:rooms/previews-map]
 :<- [:rooms/join-mode?]
 (fn [[active-id ordered-list indexed-map children-map space-orders closed-drawers previews-map join-mode?] _]
  (if-not active-id
   ordered-list
   (flatten-tree indexed-map children-map space-orders closed-drawers previews-map join-mode? active-id 0))))



(re-frame/reg-event-db
 :rooms/toggle-drawer
 (fn [db [_ drawer-id]]
   (update-in db [:ui :closed-drawers]
              (fn [closed]
                (if (contains? closed drawer-id) (disj closed drawer-id) (conj (or closed #{}) drawer-id))))))

(re-frame/reg-sub :rooms/closed-drawers (fn [db _] (get-in db [:ui :closed-drawers] #{})))

(re-frame/reg-sub
                                   :space/unread-stats
                                   :<- [:rooms/unfiltered-indexed-map]
                                   :<- [:space-children/map]
                                   :<- [:rooms/space-orders]
                                   (fn [[rooms-map children-map space-orders] [_ space-id]]
                                    (let [rooms-in-space (flatten-tree rooms-map children-map space-orders #{} {} false space-id 0)

                                          stats (reduce (fn [acc room]
                                                         (-> acc
                                                          (update :notifs + (get room :unreadNotificationsCount 0))
                                                          (update :mentions + (get room :unreadMentionsCount 0))))
    {:notifs 0 :mentions 0} rooms-in-space)]
                                        {:unread? (pos? (:notifs stats))
                                         :notif-count (:notifs stats)
                                         :mentions (:mentions stats)
                                         :highlight? (pos? (:mentions stats))})))

(re-frame/reg-sub
 :rooms/active-dm-pops
 :<- [:rooms/unfiltered-indexed-map]
 (fn [rooms-map _]
   (->> (vals rooms-map)
        (keep (fn [raw-room]
                (let [r           (if (map? raw-room) raw-room (js->clj raw-room :keywordize-keys true))
                      is-dm?      (or (:is-direct? r) (:isDirect r) (:is-direct r))
                      msg-count   (or (:unread-messages-count r) (:unreadMessagesCount r) 0)
                      mentions    (or (:highlight-count r) (:unreadMentionsCount r) 0)
                      ts          (or (:last_message_ts r) (:lastMessageTs r) 0)]
                  (when (and is-dm? (pos? msg-count))
                    {:id          (or (:id r) (:roomId r))
                     :name        (or (:name r) "Loading...")
                     :avatar-url  (or (:avatar r) (:avatarUrl r))
                     :is-dm?      true
                     :unread?     true
                     :highlight?  (pos? mentions)
                     :mentions    mentions
                     :msg-count   msg-count
                     :ts          ts}))))
        (sort-by :ts >)
        (map #(dissoc % :ts)))))


(defn- is-call-room? [db room-id]
  (get-in db [:room-previews room-id :is-call?] false))

(re-frame/reg-event-fx
 :rooms/hydrate-room
     (fn [{:keys [db]} [_ room-id]]
      (if (:push-routed? db)
          {}
       {:dispatch [:rooms/select room-id]})))

(re-frame/reg-event-fx
 :rooms/clear-active-if-matches
 (fn [{:keys [db]} [_ room-id]]
   (if (= (:active-room-id db) room-id)
     (let [current-user (:active-user-id db)]
       (store/set-setting! (str "last_room_" current-user) nil)
       {:db (assoc db :active-room-id nil)
        :dispatch [:sdk/cleanup-timeline room-id]})
     {})))

(re-frame/reg-event-fx
 :rooms/select
 (fn [{:keys [db]} [_ room-id opts]]
   (let [
         _ (log/error room-id)
         current-room-id (:active-room-id db)
         current-user    (:active-user-id db)
         membership      (get-in db [:rooms/membership room-id])
         _ (log/error membership)
         in-cache?       (contains? (:rooms-unfiltered-cache db) room-id)

         needs-join?     (and (some? room-id)
                           (#{"invited" "left" "unknown"} membership))]

     (if (= current-room-id room-id)
       {:db (assoc-in db [:ui :sidebar-open?] false)}
       (do
         (store/set-setting! (str "last_room_" current-user) room-id)
         (let [base-db (assoc db :active-room-id nil)
               cleanup (when current-room-id [:rooms/clear-active-if-matches current-room-id])]
           (if needs-join?
             {:db         base-db
              :dispatch-n (remove nil? [cleanup [:rooms/join room-id opts]])}
             {:db             base-db
              :dispatch-n     (remove nil? [cleanup])
              :dispatch-later [{:ms 20 :dispatch [:rooms/finish-select room-id opts]}]})))))))



(re-frame/reg-event-fx
 :rooms/finish-select
 (fn [{:keys [db]} [_ room-id opts]]
   (let [active-call-id     (get-in db [:call :active-room-id])
         already-loaded?    (get-in db [:room-members room-id :data])
         loading?           (get-in db [:room-members room-id :loading?])
         is-call-room?      (is-call-room? db room-id)

         force-lobby?       (:force-lobby? opts)
         focus-override     (:focus-override opts)
         mobile?            (get-in db [:ui :mobile?])
         join-directly?     (if force-lobby? false (not mobile?))
         wipe-call-state?   (not is-call-room?)
         swapping-calls?    (and active-call-id
                                 (not= active-call-id room-id)
                                 is-call-room?
                                 (not force-lobby?))
         current-side-panel (get-in db [:ui :side-panel])
         side-panel-update  (if (and (= current-side-panel :timeline) (not focus-override)) nil current-side-panel)
         new-focus          (if is-call-room? :call :timeline)
         base-db            (-> db
                                (assoc :active-room-id room-id)
                                (assoc-in [:ui :sidebar-open?] false)
                                (assoc-in [:ui :main-focus] new-focus)
                                (assoc-in [:call :chat-open?] false))
         dispatches         (remove nil?
                                    [(when swapping-calls? [:call/hangup {:wipe-state? wipe-call-state? :room-id active-call-id}])
                                     [:composer/load-draft room-id]
                                     [:sdk/boot-timeline room-id]
                                     (when (not (or already-loaded? loading?)) [:room/fetch-members room-id])
                                     [:sdk/fetch-room-emotes :room room-id]
                                     [:container/set-main-focus new-focus]
                                     (when focus-override [:container/set-side-panel focus-override])
                                     (when (and (= current-side-panel :timeline) (nil? side-panel-update)) [:container/set-side-panel nil])
                                     ;;(when is-call-room? [:call/init-widget room-id {:join-directly? join-directly?}])
                                     ])]
     {:db                 base-db
      :input/focus-composer (not mobile?)
      :dispatch-n         dispatches})))

(defui filter-toggle-bar []
  (let [tr            @(re-frame/subscribe [:i18n/tr])
        active-filter @(re-frame/subscribe [:room-list/active-filter])]
    [:div.filter-bar
     (for [[id label-key] [["people" :navigation.room-list.filters/people]
                           ["unread" :navigation.room-list.filters/unread]
                           ["invites" :navigation.room-list.filters/invites]
                           ["other"    :navigation.room-list.filters/all] ]]
       ^{:key id}
       [:button.filter-btn
        {:class    (when (= active-filter id) "active")
         :on-click #(re-frame/dispatch [:room-list/apply-filter id])}
        (tr [label-key])])]))



;; just dropping here for now

(re-frame/reg-event-fx
 :room-list/apply-filter
 (fn [{:keys [db]} [_ filter-id]]
   (let [current-filter (:active-filter-id db)]
     (if (= current-filter filter-id)
       {}
       (do
         (log/debug "Swapping filter to" filter-id)
         (go
           (let [pool @state/!engine-pool
                 res  (<! (main/do-with-pool! pool {:handler :set-room-filter
                                                    :arguments {:filter-id filter-id}}))]
             (if (= (:status res) "success")
               (log/debug "Worker successfully swapped filter!")
               (log/error "Worker failed to swap filter:" res))))
         {:db (assoc db :active-filter-id filter-id)})))))

(re-frame/reg-sub :room-list/active-filter (fn [db _] (get db :active-filter-id "people")))

(defui virtualized-room-list [tr client room-array active-space active-room closed-drawers active-filter]
  (let [item-renderer (render-room-item tr client room-array active-space active-room closed-drawers active-filter)]
    [:> Virtuoso
     {:style {:height "100%" :width "100%"}
      :data room-array
      :endReached #(re-frame/dispatch [:room-list/paginate-global])
      :itemContent item-renderer}]))

(defui room-list []
  (let [tr              @(re-frame/subscribe [:i18n/tr])
        rooms           @(re-frame/subscribe [:rooms/current-view])
        active-room     @(re-frame/subscribe [:rooms/active-id])
        active-space    @(re-frame/subscribe [:spaces/active-metadata])
        closed-drawers  @(re-frame/subscribe [:rooms/closed-drawers])
        active-filter   @(re-frame/subscribe [:room-list/active-filter])
        join-mode?      @(re-frame/subscribe [:rooms/join-mode?])
        client          nil
        room-array      (to-array rooms)]
    [:div.sidebar-rooms

     [:div.room-list-header-container
      {:style {:display "flex" :justify-content "space-between" :align-items "center" :padding-right "16px"}}
      (if active-space
        [:h3.room-list-header (:name active-space)]
        [:h3.room-list-header (tr [:navigation.room-list.headers/home])])

      (when active-space
        [:button.header-icon-btn
         {:class (when join-mode? "active-green")
          :title (if join-mode? "Hide Joinable Rooms" "Show Joinable Rooms")
          :on-click #(re-frame/dispatch [:rooms/toggle-join-mode])}
         [icons/compass {:size "20px"}]
         ])]

     (when-not active-space
       [filter-toggle-bar])

     (if (or (nil? room-array) (== (alength room-array) 0))
       [:div.empty-state (tr [:navigation.room-list.empty-state/no-rooms])]
       [:div.room-collection
        [virtualized-room-list tr client room-array active-space active-room closed-drawers active-filter]])
     [active-call-panel]]))

