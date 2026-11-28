(ns paradise.ui.navigation.rooms.entry
  (:require
   [clojure.string :as str]
   [re-frame.core :as re-frame]
   [cljs-workers.core :as main]
   [reagent.core :as r]
   [paradise.shared.client.state :as state]
   [cljs.core.async :refer [go <!]]
   [taoensso.timbre :as log]
   [paradise.shared.utils.helpers :refer [get-status-string resolve-dm-display]]
   [paradise.shared.utils.svg :as icons]
   [paradise.ui.global :refer [avatar long-press-props]]))

(defn ^:ui media-button [active? icon-on icon-off title on-click color-active color-inactive]
  [:button.media-btn
   {:style {:background "transparent"
            :border "none"
            :padding "6px"
            :cursor "pointer"
            :border-radius "4px"
            :color (if active? color-active color-inactive)
            :transition "background-color 0.2s ease"}
    :title title
    :on-click on-click}
   (if active? icon-on icon-off)])

(defn ^:ui active-call-panel []
  (let [active-call-id  @(re-frame/subscribe [:call/active-room])
        audio-on?       @(re-frame/subscribe [:call/audio-enabled?])
        video-on?       @(re-frame/subscribe [:call/video-enabled?])
        deafened?       @(re-frame/subscribe [:call/deafened?])
        input-vol       @(re-frame/subscribe [:settings/input-volume])
        output-vol      @(re-frame/subscribe [:settings/output-volume])
        mic-active?     (and audio-on? (not deafened?) (pos? (or input-vol 100)))
        out-active?     (and (not deafened?) (pos? (or output-vol 100)))
        screen-sharing? @(re-frame/subscribe [:call/screen-sharing?])
        rooms-map       @(re-frame/subscribe [:rooms/unfiltered-indexed-map])
        room-obj        (when active-call-id (get rooms-map active-call-id))
        room-name       (or (when room-obj
                              (if (map? room-obj)
                                (:name room-obj)
                                (aget room-obj "name")))
                            "Active Call")]
    (when active-call-id
      [:div.active-call-panel
       [:div.call-info-row
        [:div.call-status-container
         {:on-click #(re-frame/dispatch [:rooms/select active-call-id])}
         [:span.call-status-label "VOICE CONNECTED"]
         [:span.call-room-name (str "/ " room-name)]]

        [:button.hangup-icon-btn
         {:title    "Disconnect"
          :on-click #(re-frame/dispatch [:call/hangup])}
         [icons/phone-hangup]]]

       [:div.call-controls-grid
        [media-button mic-active?
         ^{:key :mic-on} [icons/mic] ^{:key :mic-off} [icons/mic-off]
         (if mic-active? "Mute" "Unmute")
         (fn []
           (if (and audio-on? (not deafened?) (<= (or input-vol 100) 0))
             (re-frame/dispatch [:settings/set-input-volume 100])
             (do
               (when (and (not mic-active?) (<= (or input-vol 100) 0))
                 (re-frame/dispatch [:settings/set-input-volume 100]))
               (re-frame/dispatch [:call/toggle-audio]))))
         "var(--text-normal)" "#f04747"]

        [media-button out-active?
         ^{:key :deaf-off} [icons/headphones] ^{:key :deaf-on} [icons/headphones-off]
         (if out-active? "Deafen" "Undeafen")
         (fn []
           (if (and (not deafened?) (<= (or output-vol 100) 0))
             (re-frame/dispatch [:settings/set-output-volume 100])
             (do
               (when (and (not out-active?) (<= (or output-vol 100) 0))
                 (re-frame/dispatch [:settings/set-output-volume 100]))
               (re-frame/dispatch [:call/toggle-deafen]))))
         "var(--text-normal)" "#f04747"]

        [media-button video-on?
         [icons/video] [icons/video-off]
         (if video-on? "Stop Video" "Start Video")
         #(re-frame/dispatch [:call/toggle-video])
         "var(--text-normal)" "#f04747"]

        [media-button screen-sharing?
         [icons/screen-share] [icons/screen-share-off]
         (if screen-sharing? "Stop Sharing" "Share Screen")
         #(re-frame/dispatch [:call/toggle-screen-share])
         "var(--accent-color)" "var(--text-normal)"]]])))

(re-frame/reg-event-fx
 :rooms/join
 (fn [{:keys [db]} [_ room-id opts]]
   (let [gen (or (:gen opts) (:session/gen db))]
     (go
       (let [res (<! (main/do-with-pool! @state/!engine-pool
                                         {:handler   :join-room
                                          :arguments {:room-id room-id}}))]
         (if (= (:status res) "success")
           (do
             (log/info "Successfully joined room:" room-id)
             (re-frame/dispatch [:room/set-membership room-id "joined" gen])
             (re-frame/dispatch [:rooms/finish-select room-id (assoc (or opts {}) :gen gen)]))
           (log/error "Failed to join room:" (:msg res))))))
   {:db (assoc-in db [:rooms/joining? room-id] true)}))



(re-frame/reg-event-fx
 :rooms/leave
 (fn [{:keys [db]} [_ room-id]]
   (main/do-with-pool! @state/!engine-pool
                       {:handler :leave-room
                        :arguments {:room-id room-id}})
   {:db (assoc-in db [:rooms/loading-state room-id] :leaving)
    :dispatch [:rooms/clear-active-if-matches room-id]}))


(re-frame/reg-event-fx
 :rooms/invite-user
 (fn [_ [_ room-id user-id]]
   (go
     (let [res (<! (main/do-with-pool! @state/!engine-pool
                                       {:handler :invite-user
                                        :arguments {:room-id room-id :user-id user-id}}))]
       (if (= (:status res) "success")
         (log/info "Successfully invited" user-id "to" room-id)
         (log/error "Failed to invite user:" (:msg res)))))
   {}))

(re-frame/reg-event-fx
 :rooms/invite-from-dm
 (fn [{:keys [db]} [_ target-room-id dm-room-id]]
   (let [my-id   (get-in db [:profile :user-id])
         members (get-in db [:room-members dm-room-id :data])
         other   (->> (vals members)
                      (remove #(= (:user-id %) my-id))
                      first)]
     (if other
       {:dispatch [:rooms/invite-user target-room-id (:user-id other)]}
       (do
         (go
           (let [res (<! (main/do-with-pool! @state/!engine-pool
                                             {:handler   :fetch-room-members
                                              :arguments {:room-id dm-room-id}}))]
             (if (= (some-> (:status res) clojure.core/name) "success")
               (let [fetched (->> (:members res)
                                  (remove #(= (:user-id %) my-id))
                                  first)]
                 (when (:members res)
                   (re-frame/dispatch [:room/set-members dm-room-id (:members res)]))
                 (if fetched
                   (re-frame/dispatch [:rooms/invite-user target-room-id (:user-id fetched)])
                   (log/warn "Could not resolve user from DM room" dm-room-id)))
               (log/warn "Failed to fetch DM members for invite:" dm-room-id))))
         {})))))


(re-frame/reg-event-fx
 :rooms/knock
 (fn [{:keys [db]} [_ room-id]]
   (main/do-with-pool! @state/!engine-pool
                       {:handler :knock-room
                        :arguments {:room-id room-id}})
   {:db (assoc-in db [:rooms/loading-state room-id] :knocking)}))


(re-frame/reg-event-fx
 :rooms/copy-link
 (fn [{:keys [db]} [_ room-id]]
   (go
     (let [res (<! (main/do-with-pool! @state/!engine-pool
                                       {:handler :copy-room-link
                                        :arguments {:room-id room-id}}))]
       (when (= (:status res) "success")
         (-> js/navigator .-clipboard (.writeText (:link res))))))
         {}))

(re-frame/reg-event-fx
 :rooms/create-room
 (fn [{:keys [db]} [_ {:keys [name topic visibility is-encrypted history-visibility is-space is-forum is-call parent-space-id on-error]}]]
   (go
     (let [pool @state/!engine-pool
           res  (<! (main/do-with-pool! pool {:handler :create-room
                                              :arguments {:name               name
                                                          :topic              topic
                                                          :visibility         visibility
                                                          :is-encrypted       is-encrypted
                                                          :history-visibility history-visibility
                                                          :is-space           is-space
                                                          :is-forum           (boolean is-forum)
                                                          :is-call            (boolean is-call)
                                                          :space-id           parent-space-id}}))]
       (if (= (:status res) "success")
         (let [new-room-id (:room-id res)]
           (when is-forum
             (re-frame/dispatch [:rooms/mark-forum-room new-room-id true]))
           (when is-call
             (re-frame/dispatch [:rooms/set-preview new-room-id {:is-call? true :is-forum? false}]))
           (when parent-space-id
             (let [add-res (<! (main/do-with-pool! pool {:handler :space-add-child
                                                         :arguments {:space-id parent-space-id
                                                                     :child-id new-room-id}}))]
               (when (not= (:status add-res) "success")
                 (log/error "Created room, but failed to attach to space:" (:msg add-res)))))
           (re-frame/dispatch [:ui/close-modal])
           (re-frame/dispatch [:rooms/select new-room-id])
           (when parent-space-id
             (re-frame/dispatch [:sdk/fetch-space-hierarchy parent-space-id])))
         (do
           (log/error "Failed to create room/space:" (:msg res))
           (when on-error (on-error))))))
   {}))

(re-frame/reg-sub
 :room/membership
 (fn [db [_ room-id]]
   (get-in db [:rooms/membership room-id])))

(re-frame/reg-sub
 :room/membership-loading?
 (fn [db [_ room-id]]
   (get-in db [:rooms/membership-loading? room-id])))

(re-frame/reg-sub
 :room/membership-attempted?
 (fn [db [_ room-id]]
   (get-in db [:rooms/membership-attempted? room-id] false)))

(re-frame/reg-event-db
 :room/clear-membership-loading
 (fn [db [_ room-id]]
   (-> db
       (update :rooms/membership-loading? dissoc room-id)
       (assoc-in [:rooms/membership-attempted? room-id] true))))

(re-frame/reg-event-db
 :room/set-membership
 [(state/guard-session-gen #(nth % 3 nil))]
 (fn [db [_ room-id status _gen]]
   (-> db
       (assoc-in [:rooms/membership room-id] status)
       (update :rooms/membership-loading? dissoc room-id)
       (assoc-in [:rooms/membership-attempted? room-id] true))))

(re-frame/reg-event-fx
 :room/fetch-membership
 (fn [{:keys [db]} [_ room-id]]
   (if (or (nil? room-id)
           (get-in db [:rooms/membership room-id])
           (get-in db [:rooms/membership-loading? room-id])
           (get-in db [:rooms/membership-attempted? room-id]))
     {}
     (do
       (go
         (let [res (<! (main/do-with-pool! @state/!engine-pool
                                           {:handler   :fetch-room-membership
                                            :arguments {:room-id room-id}}))]
           (if (= (:status res) "success")
             (re-frame/dispatch [:room/set-membership room-id (:membership res)])
             (do
               (js/console.warn "Membership fetch failed for" room-id "-" (:msg res))
               (re-frame/dispatch [:room/clear-membership-loading room-id])))))
       {:db (-> db
                (assoc-in [:rooms/membership-loading? room-id] true)
                (assoc-in [:rooms/membership-attempted? room-id] true))}))))

(re-frame/reg-sub
 :room/security
 (fn [db [_ room-id]]
   (get-in db [:rooms/security room-id])))

(re-frame/reg-sub
 :room/security-loading?
 (fn [db [_ room-id]]
   (get-in db [:rooms/security-loading? room-id])))

(re-frame/reg-sub
 :room/security-attempted?
 (fn [db [_ room-id]]
   (get-in db [:rooms/security-attempted? room-id] false)))

(re-frame/reg-event-db
 :room/clear-security-loading
 (fn [db [_ room-id]]
   (-> db
       (update :rooms/security-loading? dissoc room-id)
       (assoc-in [:rooms/security-attempted? room-id] true))))

(re-frame/reg-event-db
 :room/set-security
 (fn [db [_ room-id data]]
   (-> db
       (assoc-in [:rooms/security room-id] data)
       (update :rooms/security-loading? dissoc room-id)
       (assoc-in [:rooms/security-attempted? room-id] true))))

(re-frame/reg-event-fx
 :room/fetch-security
 (fn [{:keys [db]} [_ room-id]]
   (if (or (nil? room-id)
           (get-in db [:rooms/security room-id])
           (get-in db [:rooms/security-loading? room-id])
           (get-in db [:rooms/security-attempted? room-id]))
     {}
     (do
       (go
         (let [res (<! (main/do-with-pool! @state/!engine-pool
                                           {:handler   :fetch-room-security
                                            :arguments {:room-id room-id}}))]
           (if (= (:status res) "success")
             (re-frame/dispatch [:room/set-security
                                 room-id
                                 {:is-public?    (:is-public res)
                                  :is-encrypted? (:is-encrypted res)}])
             (do
               (js/console.warn "Security fetch failed for" room-id "-" (:msg res))
               (re-frame/dispatch [:room/clear-security-loading room-id])))))
       {:db (-> db
                (assoc-in [:rooms/security-loading? room-id] true)
                (assoc-in [:rooms/security-attempted? room-id] true))}))))

(re-frame/reg-sub
 :room/is-call?
 (fn [db [_ room-id]]
   (get-in db [:room-previews room-id :is-call?])))

(re-frame/reg-sub
 :room/is-forum?
 (fn [db [_ room-id]]
   (state/room-forum-mode? db room-id)))

(defn room-item [_initial-props]
  (r/with-let [!attempted? (atom false)]
    (fn [{:keys [id name indent is-space? is-closed? is-dm? is-group-dm? dm-peer-id
                 recipient-avatars has-call? active-room unread? highlight?
                 notif-count call-participants avatar-url space-id
                 active-filter open-menu-fn]}]
      (let [active?         (= id active-room)
            tr              @(re-frame/subscribe [:i18n/tr])
            is-call?        @(re-frame/subscribe [:room/is-call? id])
            is-forum?       @(re-frame/subscribe [:room/is-forum? id])
            typing-raw      @(re-frame/subscribe [:room/typing-users id])
            profile         @(re-frame/subscribe [:sdk/profile])
            my-id           (:user-id profile)
            my-name         (:display-name profile)
            needs-members?  (or has-call?
                                (and is-dm?
                                     (if is-group-dm?
                                       (and (not avatar-url) (empty? recipient-avatars))
                                       (or (not avatar-url) (nil? dm-peer-id)))))
            members-map     @(re-frame/subscribe [:room/members-map id])
            mem-fetching?   @(re-frame/subscribe [:room/members-loading? id])
            mem-tried?      @(re-frame/subscribe [:room/members-attempted? id])
            membership      @(re-frame/subscribe [:room/membership id])
            mem-loading?    @(re-frame/subscribe [:room/membership-loading? id])
            mem-attempted?  @(re-frame/subscribe [:room/membership-attempted? id])
            security        @(re-frame/subscribe [:room/security id])
            sec-loading?    @(re-frame/subscribe [:room/security-loading? id])
            sec-attempted?  @(re-frame/subscribe [:room/security-attempted? id])]

        (when (and id (not is-space?) (not security) (not sec-loading?) (not sec-attempted?))
          (re-frame/dispatch [:room/fetch-security id]))
        (when (and id
                   needs-members?
                   (not mem-fetching?)
                   (not mem-tried?)
                   (empty? members-map)
                   (not @!attempted?))
          (reset! !attempted? true)
          (re-frame/dispatch [:room/fetch-members id]))
        (when (and id (not membership) (not mem-loading?) (not mem-attempted?))
          (re-frame/dispatch [:room/fetch-membership id]))

        (let [{:keys [other-users group-dm? other-user]
               final-avatar :avatar-url
               final-name   :display-name}
              (resolve-dm-display {:is-dm?       is-dm?
                                   :is-group-dm? is-group-dm?
                                   :members-map  members-map
                                   :my-id        my-id
                                   :avatar-url   avatar-url
                                   :name         name})
              peer-id               (when-not group-dm?
                                      (or dm-peer-id (:user-id other-user)))
              peer-status           @(re-frame/subscribe
                                      [:presence/user peer-id (:presence other-user)])
              typing-ids            (if (map? typing-raw) (:users typing-raw) typing-raw)
              others-typing         (filterv #(and (not= % my-id)
                                                   (or (nil? my-name) (not= % my-name)))
                                             (or typing-ids []))
              is-typing?            (and (not is-space?) (boolean (seq others-typing)))
              typing-names          (when is-typing?
                                      (mapv #(or (:display-name (get members-map %)) %)
                                            others-typing))
              typing-tooltip        (when (seq typing-names)
                                      (get-status-string
                                       tr
                                       [:container.timeline.status.typing]
                                       typing-names))
              group-members         (when group-dm?
                                      (or (seq recipient-avatars)
                                          (let [peers (mapv (fn [u]
                                                              {:id   (:user-id u)
                                                               :name (:display-name u)
                                                               :url  (:avatar-url u)})
                                                            other-users)]
                                            (if (and (= (count peers) 1) my-id)
                                              (conj peers
                                                    {:id   my-id
                                                     :name (or (:display-name profile)
                                                               (:username profile)
                                                               "Me")
                                                     :url  (:avatar-url profile)})
                                              peers))))
              final-membership      (or membership "joined")
              resolved-participants (map (fn [p]
                                           (let [p-id        (or (:userId p) p)
                                                 member-info (get members-map p-id)]
                                             {:id         p-id
                                              :name       (or (:displayName p)
                                                              (:display-name member-info)
                                                              p-id)
                                              :avatar-url (or (:avatarUrl p)
                                                              (:avatar-url member-info))}))
                                         call-participants)]
          [:div.room-container
           (if is-space?
             [:div.room-drawer-header
              (merge {:style           {:padding-left (str indent "px")}
                      :class           (when is-closed? "collapsed")
                      :on-click        #(do
                                          (.preventDefault %)
                                          (.stopPropagation %)
                                          (js/setTimeout
                                           (fn []
                                             (re-frame/dispatch [:rooms/toggle-drawer id]))
                                           150))
                      :on-context-menu #(open-menu-fn %
                                                      (.-clientX %)
                                                      (.-clientY %)
                                                      final-membership)}
                     (long-press-props #(open-menu-fn nil %1 %2 final-membership)))
              [:span.drawer-arrow (if is-closed? [icons/chevron-down] [icons/chevron-down])]
              [:span.drawer-name (str/upper-case final-name)]]
             [:div.room-item
              (merge {:style           {:padding-left (str indent "px")}
                      :class           [(when active? "active")
                                        (when unread? "has-unread")
                                        (when highlight? "has-highlight")
                                        (when is-typing? "is-typing")]
                      :on-pointer-up   #(do
                                          (.preventDefault %)
                                          (.stopPropagation %))
                      :on-click        #(do
                                          (.preventDefault %)
                                          (.stopPropagation %)
                                          (when-let [native-event (.-nativeEvent %)]
                                            (.stopImmediatePropagation native-event))
                                          (re-frame/dispatch [:rooms/select id]))
                      :on-context-menu #(open-menu-fn %
                                                      (.-clientX %)
                                                      (.-clientY %)
                                                      final-membership)}
                     (long-press-props #(open-menu-fn nil %1 %2 final-membership)))
              (cond
                is-call?
                [icons/speaker {:has-call? has-call?}]

                is-forum?
                [icons/thread]

                (or is-dm? (and (= active-filter "people") (not space-id)))
                [avatar (cond-> {:id   (or peer-id id)
                                 :name final-name
                                 :url  final-avatar
                                 :size 24}
                          group-dm?                        (assoc :is-group? true
                                                                  :group-members group-members)
                          (and (not group-dm?) peer-status) (assoc :status peer-status))]

                :else
                [icons/dynamic-room-hash {:is-public?    (:is-public? security)
                                          :is-encrypted? (:is-encrypted? security)
                                          :is-joinable?  (not= final-membership "joined")}])

              [:span.room-name final-name]
              [:div.room-item-right
               [:div.room-hover-actions
                (when is-call?
                  [:div.action-icon.chat-action
                   {:title    (tr [:navigation.room-list/view-chat])
                    :on-click (fn [e]
                                (.stopPropagation e)
                                (re-frame/dispatch [:rooms/select
                                                    id
                                                    {:force-lobby?   true
                                                     :focus-override :timeline}]))}
                   [icons/chat-bubble]])
                [:div.action-icon.settings-action
                 {:title    (tr [:navigation.room-list/room-settings])
                  :on-click (fn [e]
                              (.stopPropagation e)
                              (re-frame/dispatch [:rooms/open-settings id]))}
                 [icons/settings {:animate :spin}]]]
               (when is-typing?
                 [:div.room-typing-indicator
                  {:title typing-tooltip}
                  [:span.room-typing-dot]
                  [:span.room-typing-dot]
                  [:span.room-typing-dot]])
               (when has-call?
                 [:div.call-participants.hide-on-desktop
                  (for [p resolved-participants]
                    ^{:key (str "mobile-call-" (:id p))}
                    [:div.participant-avatar-ring
                     [avatar {:id (:id p) :url (:avatar-url p) :size 20}]])])
               (cond
                 (pos? notif-count)
                 [:div.notification-badge
                  {:class (when highlight? "highlight")}
                  (if (> notif-count 99) "99+" notif-count)]

                 unread?
                 [:div.unread-dot])]])
           (when (and has-call? (not is-closed?))
             [:div.desktop-call-list.hide-on-mobile
              {:style {:padding-left (str (+ indent 24) "px")}}
              (for [p resolved-participants]
                ^{:key (str "desktop-call-" (:id p))}
                [:div.call-participant-item
                 {:on-click (fn [e]
                              (.stopPropagation e)
                              (re-frame/dispatch [:ui/open-user-profile (:id p)]))}
                 [avatar {:id (:id p) :name (:name p) :url (:avatar-url p) :size 20}]
                 [:span.participant-name (:name p)]])])])))))

(defn render-room-item [tr _client _rooms active-space active-room closed-drawers active-filter]
  (fn [_ raw-room]
    (let [r           (if (map? raw-room) raw-room (js->clj raw-room :keywordize-keys true))
          id          (or (:id r) (:roomId r))
          room-name   (or (:name r) "Loading...")
          space?      (or (:is-space? r) (:isSpace r))
          dm?         (or (:is-direct? r) (:isDirect r) (:is-direct r))
          group-dm?   (boolean (or (:isGroupDm r) (:is-group-dm? r)))
          dm-peer-id  (or (:dmPeerId r) (:dm-peer-id r))
          recip-avs   (or (:recipientAvatars r) (:recipient-avatars r))
          parent-id   (or (:parent-id r) (:isParent r))
          notif-count (or (:notification-count r) (:unreadNotificationsCount r) 0)
          high-count  (or (:highlight-count r) (:unreadMentionsCount r) 0)
          unread-msgs (or (:unread-messages-count r) (:unreadMessagesCount r) 0)
          is-bold?    (boolean (or (:is-bold? r) (:isBold r)))
          marked-unr? (boolean (or (:is-marked-unread? r) (:isMarkedUnread r)))
          badge-count (max notif-count high-count)
          has-unread? (boolean (or (pos? badge-count)
                                   (pos? unread-msgs)
                                   is-bold?
                                   marked-unr?))
          url         (or (:avatar r) (:avatarUrl r))
          depth       (or (:depth r) 0)
          call-parts  (or (:activeRoomCallParticipants r) [])
          open-menu   (fn [e mx my membership]
                        (when e
                          (.preventDefault e)
                          (.stopPropagation e))
                        (re-frame/dispatch
                         [:ui/open-context-menu :room-actions
                          {:x          mx
                           :y          my
                           :room-id    id
                           :room-name  room-name
                           :is-space?  space?
                           :is-dm?     dm?
                           :context    :list
                           :membership membership
                           :parent-id  parent-id}]))]
      (r/as-element
       [room-item {:tr                tr
                   :id                id
                   :name              room-name
                   :indent            (* depth 12)
                   :is-space?         space?
                   :is-dm?            dm?
                   :is-group-dm?      group-dm?
                   :dm-peer-id        dm-peer-id
                   :recipient-avatars recip-avs
                   :is-closed?        (contains? closed-drawers id)
                   :has-call?         (seq call-parts)
                   :active-room       active-room
                   :space-id          (:id active-space)
                   :active-filter     active-filter
                   :unread?           has-unread?
                   :highlight?        (pos? high-count)
                   :notif-count       badge-count
                   :call-participants call-parts
                   :avatar-url        url
                   :open-menu-fn      open-menu}]))))