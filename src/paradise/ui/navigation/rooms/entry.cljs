(ns paradise.ui.navigation.rooms.entry
  (:require
   [clojure.string :as str]
   [re-frame.core :as re-frame]
   [cljs-workers.core :as main]
   [reagent.core :as r]
   [paradise.shared.client.state :as state]
   [cljs.core.async :refer [go <!]]
   [taoensso.timbre :as log]
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
        screen-sharing? @(re-frame/subscribe [:call/screen-sharing?])
        rooms-map       @(re-frame/subscribe [:rooms/unfiltered-indexed-map])
        room-obj        (when active-call-id (get rooms-map active-call-id))
        room-name       (or (when room-obj (aget room-obj "name")) "Active Call")]

    (when active-call-id
      [:div.active-call-panel
       [:div.call-info-row
        [:div.call-status-container
         {:on-click #(re-frame/dispatch [:rooms/select active-call-id])}
         [:span.call-status-label "VOICE CONNECTED"]
         [:span.call-room-name (str "/ " room-name)]]

        [:button.hangup-icon-btn
         {:title "Disconnect"
          :on-click #(re-frame/dispatch [:call/hangup])}
         [icons/phone-hangup]]]

       [:div.call-controls-grid
        [media-button audio-on?
         [icons/mic] [icons/mic-off]
         (if audio-on? "Mute" "Unmute")
         #(re-frame/dispatch [:call/toggle-audio])
         "var(--text-normal)" "#f04747"]

        [media-button (not deafened?)
         [icons/headphones] [icons/headphones-off]
         (if deafened? "Undeafen" "Deafen")
         #(re-frame/dispatch [:call/toggle-deafen])
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
   (go
     (let [res (<! (main/do-with-pool! @state/!engine-pool
                                       {:handler :join-room
                                        :arguments {:room-id room-id}}))]
       (if (= (:status res) "success")
         (do
           (log/info "Successfully joined room:" room-id)
           (re-frame/dispatch [:room/set-membership room-id "joined"])
           (re-frame/dispatch [:rooms/finish-select room-id opts]))
         (log/error "Failed to join room:" (:msg res)))))
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
       {:dispatch [:room/invite-user target-room-id (:user-id other)]}
       (do
         (log/warn "Could not resolve user from DM room" dm-room-id)
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
 (fn [{:keys [db]} [_ {:keys [name topic visibility is-encrypted history-visibility is-space parent-space-id on-error]}]]
   (go
     (let [pool @state/!engine-pool
           res  (<! (main/do-with-pool! pool {:handler :create-room
                                              :arguments {:name               name
                                                          :topic              topic
                                                          :visibility         visibility
                                                          :is-encrypted       is-encrypted
                                                          :history-visibility history-visibility
                                                          :is-space           is-space}}))]
       (if (= (:status res) "success")
         (let [new-room-id (:room-id res)]
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

(re-frame/reg-event-db
 :room/clear-membership-loading
 (fn [db [_ room-id]]
   (update db :rooms/membership-loading? dissoc room-id)))

(re-frame/reg-event-db
 :room/set-membership
 (fn [db [_ room-id status]]
   (-> db
       (assoc-in [:rooms/membership room-id] status)
       (update :rooms/membership-loading? dissoc room-id))))

(re-frame/reg-event-fx
 :room/fetch-membership
 (fn [{:keys [db]} [_ room-id]]
   (if (or (get-in db [:rooms/membership room-id])
           (get-in db [:rooms/membership-loading? room-id]))
     {}
     (do
       (go
         (let [res (<! (main/do-with-pool! @state/!engine-pool
                                           {:handler :fetch-room-membership
                                            :arguments {:room-id room-id}}))]
           (if (= (:status res) "success")
             (re-frame/dispatch [:room/set-membership room-id (:membership res)])
             (do
               (js/console.warn "Membership fetch failed for" room-id "-" (:msg res))
               (re-frame/dispatch [:room/clear-membership-loading room-id])))))
       {:db (assoc-in db [:rooms/membership-loading? room-id] true)}))))

(re-frame/reg-sub
 :room/security
 (fn [db [_ room-id]]
   (get-in db [:rooms/security room-id])))

(re-frame/reg-sub
 :room/security-loading?
 (fn [db [_ room-id]]
   (get-in db [:rooms/security-loading? room-id])))

(re-frame/reg-event-db
 :room/clear-security-loading
 (fn [db [_ room-id]]
   (update db :rooms/security-loading? dissoc room-id)))

(re-frame/reg-event-db
 :room/set-security
 (fn [db [_ room-id data]]
   (-> db
       (assoc-in [:rooms/security room-id] data)
       (update :rooms/security-loading? dissoc room-id))))

(re-frame/reg-event-fx
 :room/fetch-security
 (fn [{:keys [db]} [_ room-id]]
   (if (or (get-in db [:rooms/security room-id])
           (get-in db [:rooms/security-loading? room-id]))
     {}
     (do
       (go
         (let [res (<! (main/do-with-pool! @state/!engine-pool
                                           {:handler :fetch-room-security
                                            :arguments {:room-id room-id}}))
               ]
           (if (= (:status res) "success")
             (re-frame/dispatch [:room/set-security room-id {:is-public? (:is-public res)
                                                             :is-encrypted? (:is-encrypted res)}])
             (do
               (js/console.warn "Security fetch failed for" room-id "-" (:msg res))
               (re-frame/dispatch [:room/clear-security-loading room-id])))))
       {:db (assoc-in db [:rooms/security-loading? room-id] true)}))))

(re-frame/reg-sub
 :room/is-call?
 (fn [db [_ room-id]]
   (get-in db [:room-previews room-id :is-call?])))

(defn room-item [initial-props]
  (r/with-let [!attempted? (atom false)]
    (fn [{:keys [id name indent is-space? is-closed? is-dm? has-call?
                 active-room unread? highlight? notif-count
                 call-participants avatar-url space-id active-filter active-space open-menu-fn]}]
      (let [active?  (= id active-room)
            tr       @(re-frame/subscribe [:i18n/tr])
            is-call? @(re-frame/subscribe [:room/is-call? id])
            profile  @(re-frame/subscribe [:sdk/profile])
            my-id    (:user-id profile)
            needs-members?   (or (and is-dm? (not avatar-url)) has-call?)
            members-map      @(re-frame/subscribe [:room/members-map id])
            members-loading? @(re-frame/subscribe [:room/members-loading? id])
            membership   @(re-frame/subscribe [:room/membership id])
            mem-loading? @(re-frame/subscribe [:room/membership-loading? id])
            security     @(re-frame/subscribe [:room/security id])
            sec-loading? @(re-frame/subscribe [:room/security-loading? id])]

        (when (and id (not security) (not sec-loading?))
          (re-frame/dispatch [:room/fetch-security id]))
        (when (and id needs-members? (not members-loading?) (empty? members-map) (not @!attempted?))
          (reset! !attempted? true)
          (re-frame/dispatch [:room/fetch-members id]))

        (when (and id (not membership) (not mem-loading?))
          (re-frame/dispatch [:room/fetch-membership id]))

        (let [other-user   (when (and is-dm? (seq members-map))
                             (->> (vals members-map)
                                  (remove #(= (:user-id %) my-id))
                                  first))
              final-avatar (or avatar-url (:avatar-url other-user))
              final-name   (if (when is-dm? other-user)
                             (:display-name other-user)
                             name)
              final-membership membership

              resolved-participants (map (fn [p]
                                           (let [p-id (or (:userId p) p)
                                                 member-info (get members-map p-id)]
                                             {:id p-id
                                              :name (or (:displayName p) (:display-name member-info) p-id)
                                              :avatar-url (or (:avatarUrl p) (:avatar-url member-info))}))
                                         call-participants)]
          [:div.room-container
           (if is-space?
             [:div.room-drawer-header
              (merge {:style {:padding-left (str indent "px")}
                      :class (when is-closed? "collapsed")
                      :on-click #(do
                                   (.preventDefault %)
                                   (.stopPropagation %)
                                   (js/setTimeout
                                    (fn []
                                      (re-frame/dispatch [:rooms/toggle-drawer id]))
                                    150))
                      :on-context-menu #(open-menu-fn % (.-clientX %) (.-clientY %) final-membership)}
                     (long-press-props #(open-menu-fn nil %1 %2 final-membership)))
              [:span.drawer-arrow (if is-closed? [icons/chevron-down] [icons/chevron-down])]
              [:span.drawer-name (str/upper-case final-name)]]
             [:div.room-item
              (merge {:style {:padding-left (str indent "px")}
                      :class [(when active? "active")
                              (when unread? "has-unread")
                              (when highlight? "has-highlight")]
                      :on-pointer-up #(do
                                        (.preventDefault %)
                                        (.stopPropagation %))
                      :on-click #(do
                                   (.preventDefault %)
                                   (.stopPropagation %)
                                   (when-let [native-event (.-nativeEvent %)]
                                     (.stopImmediatePropagation native-event))
                                   (re-frame/dispatch [:rooms/select id]))
                      :on-context-menu #(open-menu-fn % (.-clientX %) (.-clientY %) final-membership)}
                     (long-press-props #(open-menu-fn nil %1 %2 final-membership)))
              (cond
                is-call?
                [icons/speaker {:has-call? has-call?}]
                (and (= active-filter "people") (or is-dm? (not space-id)))
                [avatar {:id id :name final-name :url final-avatar :size 24 :status :online}]
                :else
                [icons/dynamic-room-hash {:is-public? (:is-public? security)
                                    :is-encrypted? (:is-encrypted? security)
                                    :is-joinable? (not= final-membership "joined")}]
                )

              [:span.room-name final-name]
              [:div.room-item-right
               [:div.room-hover-actions
                (when is-call?
                  [:div.action-icon.chat-action
                   {:title
                    (tr [:navigation.room-list/view-chat])
                    :on-click (fn [e]
                                (.stopPropagation e)
                                (re-frame/dispatch [:rooms/select id {:force-lobby? true :focus-override :timeline}]))}
                   [icons/chat-bubble]])
                [:div.action-icon.settings-action
                 {:title (tr [:navigation.room-list/room-settings])
                  :on-click (fn [e]
                              (.stopPropagation e)
                              (re-frame/dispatch [:rooms/open-settings id]))}
                 [icons/settings {:animate :spin}]]]
               (when has-call?
                 [:div.call-participants.hide-on-desktop
                  (for [p resolved-participants]
                    ^{:key (str "mobile-call-" (:id p))}
                    [:div.participant-avatar-ring
                     [avatar {:id (:id p) :url (:avatar-url p) :size 20}]])])
               (when unread?
                 [:div.notification-badge
                  {:class (when highlight? "highlight")}
                  (if (> notif-count 99) "99+" notif-count)])]])
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


(defn render-room-item [tr client rooms active-space active-room closed-drawers active-filter]
  (fn [_ raw-room]
    (let [r           (if (map? raw-room) raw-room (js->clj raw-room :keywordize-keys true))
          id          (or (:id r) (:roomId r))
          room-name   (or (:name r) "Loading...")
          space?      (or (:is-space? r) (:isSpace r))
          dm?         (or (:is-direct? r) (:isDirect r) (:is-direct r))
          parent-id   (or (:parent-id r) (:isParent r))
          notif-count (or (:notification-count r) (:unreadNotificationsCount r) 0)
          high-count  (or (:highlight-count r) (:unreadMentionsCount r) 0)
          url         (or (:avatar r) (:avatarUrl r))
          depth       (or (:depth r) 0)
          call-parts  (or (:active-room-call-participants r)
                          (:activeRoomCallParticipants r)
                          (:active_room_call_participants r)
                          [])
          open-menu   (fn [e mx my membership]
                        (when e (.preventDefault e) (.stopPropagation e))

                        (re-frame/dispatch
                         [:ui/open-context-menu :room-actions
                          {:x mx
                           :y my
                           :room-id room-id
                           :room-name room-name
                           :is-space? is-space?
                           :is-dm? is-dm?
                           :context :list
                           :membership membership
                           :parent-id parent-id}]))]
      (r/as-element
       [room-item {:tr tr
                   :id id
                   :name room-name
                   :indent (* depth 12)
                   :is-space? space?
                   :is-dm? dm?
                   :is-closed? (contains? closed-drawers id)
                   :has-call? (seq call-parts)
                   :active-room active-room
                   :space-id (:id active-space)
                   :active-filter active-filter
                   :unread? (pos? notif-count)
                   :highlight? (pos? high-count)
                   :notif-count notif-count
                   :call-participants call-parts
                   :avatar-url url
                   :open-menu-fn open-menu}]))))