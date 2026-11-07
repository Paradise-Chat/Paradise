(ns paradise.ui.container.timeline.item
  (:require [re-frame.core :as re-frame]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.db :as db]
            [cljs-workers.mesh :as mesh]
            [cljs-workers.core :as main]
            [paradise.shared.client.state :as state]
            [cljs.core.async :refer [go <!]]
            [paradise.shared.utils.helpers :refer [sanitize-custom-html hiccup->text format-divider-date format-time linkify-text truncate-name]]
            [paradise.media.component :refer [media]]
            [paradise.ui.global :refer [avatar long-press-props swipe-to-action-wrapper]]
            [paradise.ui.container.members :refer [profile-popover-trigger]]
            [paradise.ui.container.timeline.hydrator :refer [get-cached-react-tree]]
            [paradise.ui.input.base :refer [inline-editor]]
            [paradise.shared.utils.svg :as icons]
            ["react" :as react]
            [goog.object]
            ))

(re-frame/reg-event-db
 :msg/open-reaction-picker
 (fn [db [_ room-id event-or-transaction-id  x y]]
   (assoc db :active-reaction-picker {:room-id room-id :event-or-transaction-id event-or-transaction-id  :x x :y y})))

(re-frame/reg-event-db
 :msg/close-reaction-picker
 (fn [db _]
   (dissoc db :active-reaction-picker)))

(re-frame/reg-sub
 :msg/active-reaction-picker
 (fn [db _]
   (:active-reaction-picker db)))

(re-frame/reg-event-db
 :ui/open-reaction-details
 (fn [db [_ room-id reactions]]
   (assoc db :active-reaction-details {:room-id room-id :reactions reactions})))

(re-frame/reg-event-db
 :ui/close-reaction-details
 (fn [db _]
   (dissoc db :active-reaction-details)))

(re-frame/reg-sub
 :ui/active-reaction-details
 (fn [db _]
   (:active-reaction-details db)))

(re-frame/reg-sub
 :sdk/homeserver-url
 (fn [db _]
   (:homeserver-url db)))

(re-frame/reg-event-fx
 :sdk/toggle-reaction
 (fn [_ [_ room-id event-id emoji-key]]
   (mesh/do-with-thread! :engine-pool {:handler :toggle-reaction
                                            :arguments {:room-id room-id :event-id event-id :emoji emoji-key}})
   {}))

(re-frame/reg-event-fx
 :msg/toggle-pin
 (fn [_ [_ room-id msg-id]]
   (mesh/do-with-thread! :engine-pool {:handler :toggle-pin
                                            :arguments {:room-id room-id :msg-id msg-id}})
   {}))

(re-frame/reg-event-fx
 :msg/delete
 (fn [_ [_ room-id msg-id]]
   (mesh/do-with-thread! :engine-pool {:handler :redact-event
                                            :arguments {:room-id room-id :msg-id msg-id}})
   {}))



(re-frame/reg-event-fx
 :msg/edit
 (fn [_ [_ room-id item text html]]
   (let [msg-tag     (get-in item [:content :inner :tag])
         ffi-content (get-in item [:content :inner :content :ffi-content])]
     (go
       (let [res (<! (mesh/do-with-thread! :engine-pool
                                         {:handler :send-message
                                          :arguments {:room-id room-id
                                                      :text text
                                                      :html html
                                                      :context {:mode "edit"
                                                                :target (:id item)
                                                                :msg-tag msg-tag
                                                                :ffi-content ffi-content}}}))]
         (log/info "Edit dispatch result:" res))))
   {:dispatch [:input/clear-context room-id]}))

(re-frame/reg-event-fx
 :room/mark-read
 (fn [_ [_ room-id event-id]]
   (mesh/do-with-thread! :engine-pool {:handler :mark-read
                                            :arguments {:room-id room-id :event-id event-id}})
   {}))

(re-frame/reg-event-fx
 :msg/flush-receipts
 (fn [{:keys [db]} _]
   (let [pending (get-in db [:read-receipts :pending])]
     (when (seq pending)
       (doseq [[room-id event-id] pending]
         (when (not= (get-in db [:read-receipts :sent room-id]) event-id)
           (go
             (try
               (let [res (<! (mesh/do-with-thread! :engine-pool
                                                 {:handler :send-receipt
                                                  :arguments {:room-id room-id :event-id event-id}}))]
                 (if (= (:status res) "success")
                   (re-frame/dispatch [:msg/receipt-sent room-id event-id])
                   (do
                     (log/error "Receipt flush failed for" room-id ":" (:msg res))
                     (when-not (str/includes? (str (:msg res)) "Timeline not found")
                       (re-frame/dispatch [:msg/requeue-receipt room-id event-id])))))
                 (catch :default e
                 (log/error "Worker crashed sending receipt:" e)
                 (re-frame/dispatch [:msg/requeue-receipt room-id event-id])))))))
     {:db (assoc-in db [:read-receipts :pending] {})})))

(re-frame/reg-event-db
 :msg/requeue-receipt
 (fn [db [_ room-id event-id]]
   (assoc-in db [:read-receipts :pending room-id] event-id)))

(re-frame/reg-event-db
 :msg/receipt-sent
 (fn [db [_ room-id event-id]]
   (assoc-in db [:read-receipts :sent room-id] event-id)))

(defonce receipt-flusher
  (js/setInterval #(re-frame/dispatch [:msg/flush-receipts]) 2000))

(re-frame/reg-event-db
 :msg/mark-visible
 (fn [db [_ event-id]]
   (if-let [room-id (:active-room-id db)]
     (assoc-in db [:read-receipts :pending room-id] event-id)
     db)))

(re-frame/reg-event-db
 :msg/mark-visible-batch
 (fn [db [_ event-ids]]
   (if-let [room-id (:active-room-id db)]
     (reduce (fn [d id] (assoc-in d [:read-receipts :pending room-id] id)) db event-ids)
     db)))

(defonce visibility-observer
  (delay
    (js/IntersectionObserver.
    (fn [entries observer]
       (let [visible-ids (reduce
                          (fn [acc entry]
                            (if (.-isIntersecting entry)
                              (let [target   (.-target entry)
                                    event-id (.getAttribute target "data-event-id")]
                                (if (and event-id (str/starts-with? event-id "$"))
                                  (do
                                    (.unobserve observer target)
                                    (conj acc event-id))
                                  acc))
                              acc))
                          []
                          entries)]
         (when (seq visible-ids)
           (re-frame/dispatch [:msg/mark-visible-batch visible-ids]))))
     #js {:threshold 0.5})))

(defn ^:ui translated-label [k]
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:span (tr k)]))


(defn event-menu [mx my current-item active-room my-id]
  (re-frame/dispatch
   [:ui/open-context-menu :message-actions
    {:x mx :y my :item (js->clj current-item :keywordize-keys true) :active-room active-room :my-id my-id}]))

(defn ^:ui message-hover-toolbar [item active-room current-user-id]
  (let [tr @(re-frame/subscribe [:i18n/tr])
        react-str (tr [:container.timeline.item/react])
        reply-str (tr [:container.timeline.item/reply])
        edit-str  (tr [:container.timeline.item/edit-message])
        more-str  (tr [:container.timeline.item/more])]
    (fn [item active-room current-user-id tr]
      (let [msg-id (:id item)
            e-t-id (:event-or-transaction-id item)
            ]
        [:div.message-hover-toolbar
         [:div.toolbar-btn
          {:title    react-str
           :on-click (fn [e]
                       (.preventDefault e)
                       (.stopPropagation e)
                       (re-frame/dispatch
                        [:ui/open-popover :reaction-picker
                         {:room-id active-room
                          :msg-id  e-t-id
                          :x       (.-clientX e)
                          :y       (.-clientY e)
                          :width   320
                          :height  380}]))}
          [icons/smiley]]
         [:div.toolbar-btn
          {:title reply-str
           :on-click #(re-frame/dispatch
                       [:input/set-context active-room :reply item])}
          [icons/reply]]
         (when (:is-own? item)
           [:div.toolbar-btn
            {:title edit-str
             :on-click #(re-frame/dispatch
                         [:input/set-context active-room :edit
                          item])}
            [icons/edit]])
         [:div.toolbar-btn
          {:title more-str
           :on-click (fn [e]
                       (.stopPropagation e)
                       (let [mx (.-clientX e)
                             my (.-clientY e)]

                         (event-menu mx my item active-room current-user-id)))}
          [icons/more]]]))))




(def sanitize-cache (atom {}))

(defn memoized-sanitize [html]
  (if-let [hit (get @sanitize-cache html)]
    hit
    (let [res (sanitize-custom-html html)]
      (swap! sanitize-cache (fn [m]
                              (let [m' (assoc m html res)]
                                (if (> (count m') 300)
                                  (dissoc m' (first (keys m')))
                                  m'))))
      res)))


(defn ^:ui message-text [{:keys [body html]}]
   (if (seq html)
    (into [:span.body.formatted] (memoized-sanitize html))
     [:span.body (linkify-text body)]))


(defn ^:ui async-media-wrapper [event content {:keys [class default-ratio tag-type on-click controls style]}]
  (let [info        (:info content)
        w           (:w info)
        h           (:h info)
        valid-dims? (and (number? w) (pos? w) (number? h) (pos? h))
        container-style (if valid-dims?
                          {:aspect-ratio (str w " / " h)
                           :max-width (str "min(" w "px, 400px)")
                           :max-height (str "min(" h "px, 350px)")
                           :background "var(--bg-secondary)"
                           :position "relative"
                           :overflow "hidden"}
                          {:aspect-ratio (str default-ratio)
                           :max-width "300px"
                           :background "var(--bg-secondary)"
                           :position "relative"
                           :overflow "hidden"})
        alt-text (or (:caption content) (:filename content) "media")]

    [:div.media-container {:class class :style container-style}
     [:div.message-image-placeholder
      {:style {:position "absolute" :inset 0 :display "flex" :align-items "center" :justify-content "center"}}
      [:div.spinner]]
     [media
      {:uri (:source content)
       :source-map (:source-map content)
       :tag-type tag-type
       :alt alt-text
       :mime-type (get-in content [:info :mimetype])
       :room-id (:room_id event)
       :event-id (:id event)
       :on-click on-click
       :controls controls
       :style (merge {:width "100%" :height "100%" :display "block" :object-fit "contain"} style)}]]))

(defn ^:ui image-message [event content]
  [:div.image-attachment-container
   [async-media-wrapper event content
    {:class "media-image"
     :default-ratio 1.33
     :tag-type "img"
     :style {:cursor "zoom-in"}
     :on-click (fn [e]
                 (let [live-blob-url (.-src (.-currentTarget e))]
                   (re-frame/dispatch [:ui/open-modal :image-lightbox
                                       {:url live-blob-url
                                        :backdrop-props {:class "lightbox-backdrop"}
                                        :window-props   {:style {:background "transparent"
                                                                 :box-shadow "none"}}}])))}]
   (when (seq (:caption content))
     [:div.media-caption
      [message-text content]])])



(defn ^:ui video-message [event content]
  [:div.video-attachment-container
   [async-media-wrapper event content
    {:class "media-video"
     :default-ratio 1.77
     :tag-type "video"
     :controls true}]
   (when (seq (:caption content))
     [:div.media-caption
      [message-text content]])])

(defn ^:ui sticker-message [event content]
  [async-media-wrapper event content
   {:class "media-sticker"
    :default-ratio 1.0
    :tag-type "img"}])

(defn ^:ui file-message [event content tr]
  (let [{:keys [caption source source-map info]} content]
    [:div.file-attachment-container
     [:div.file-attachment
      [:a {:href      "#"
           :className "file-link"
           :on-click  (fn [e]
                        (.preventDefault e)
                        (re-frame/dispatch
                         [:media/download {:uri        source
                                           :source-map source-map
                                           :filename   caption
                                           :mime-type  (:mimetype info)
                                           :room-id    (:room_id event)
                                           :event-id   (:id event)}]))}
       [:span.file-icon [icons/file]]
       [:span.file-name (or caption (tr [:container.timeline.status/file-download]))]
       (when-let [size (:size info)]
         [:span.file-size (str "(" (quot size 1024) " KB)")])]]
     (when (seq (:caption content))
       [:div.media-caption
        [message-text content]])]))

(defn extract-first-url [text]
  (when text
    (when-let [match (re-find #"https?://[^\s\"'<>]+" text)]
      (clojure.string/replace match #"[.,:;!?]$" ""))))


(re-frame/reg-event-fx
 :media/url-preview-success
 (fn [{:keys [db]} [_ url data room-id]]
   {:db (assoc-in db [:url-previews url] {:status :success :data data})}))

(re-frame/reg-event-fx
 :media/fetch-url-preview
 (fn [{:keys [db]} [_ url room-id]]
   (when-not (get-in db [:url-previews url])
     (go
       (let [res (<! (mesh/do-with-thread! :engine-pool
                                           {:handler   :get-url-preview
                                            :arguments {:url url}}))]
         (if (= (:status res) "success")
           (re-frame/dispatch [:media/url-preview-success url (:data res) room-id])
           (re-frame/dispatch [:media/url-preview-error url])))))
   {:db (update-in db [:url-previews url] #(or % {:status :loading}))}))

(re-frame/reg-event-db
 :media/url-preview-error
 (fn [db [_ url]]
   (assoc-in db [:url-previews url] {:status :error})))

(re-frame/reg-sub
 :media/url-preview
 (fn [db [_ url]]
   (get-in db [:url-previews url])))

(re-frame/reg-event-db
 :media/play-inline
 (fn [db [_ url]]
   (assoc-in db [:inline-playing url] true)))

(re-frame/reg-sub
 :media/playing-inline?
 (fn [db [_ url]]
   (get-in db [:inline-playing url] false)))

(defn ^:defer link-preview-card [first-url room-id]
  (let [preview-state @(re-frame/subscribe [:media/url-preview first-url])]
    (if-not preview-state
      (do
        (re-frame/dispatch [:media/fetch-url-preview first-url room-id])
        [:div.link-preview-container.is-loading
         [:div.preview-skeleton]])
      (let [{:keys [status data]} preview-state]
        (cond
          (= status :loading)
          [:div.link-preview-container.is-loading
           [:div.preview-skeleton]]
          (= status :error)
          [:div {:style {:display "none"}}]
          (= status :success)
          (let [{:keys [og:title og:description og:image og:site_name]} data
                hostname (try (.-hostname (js/URL. first-url)) (catch :default _ first-url))
                site     (or og:site_name hostname)]
            (if (or og:title og:description)
              [:a.rich-embed-card {:href first-url :target "_blank" :rel "noopener noreferrer"}
               [:div.embed-content
                [:div.embed-site site]
                (when og:title [:div.embed-title og:title])
                (when og:description [:div.embed-description og:description])]
               (when og:image
                 [:div.embed-thumbnail
                  [media {:uri    og:image
                          :width  400
                          :height 200
                          :method "scale"
                          :alt    (or og:title "preview")}]])]
              [:div {:style {:display "none"}}])))))))

(defn ^:defer message-link-preview [msg-type-tag raw-body room-id]
  (let [first-url (when (#{"Text" "Notice" "Emote"} msg-type-tag)
                    (extract-first-url raw-body))]
    (if first-url
      (let [policy        @(re-frame/subscribe [:settings/media-preview-policy])
            room-meta     @(re-frame/subscribe [:rooms/active-metadata])
            is-private?   (= (:join-rule room-meta) "invite")
            show-preview? (or (= policy :on)
                              (and (= policy :private) is-private?))]
        (if show-preview?
          [link-preview-card first-url room-id]
          [:div {:style {:display "none"}}]))
      [:div {:style {:display "none"}}])))

(defn ^:ui reaction-pill [emoji count senders my-id active-room event-id reactions]
  (let [members-map @(re-frame/subscribe [:room/members-map active-room])
        hover-text  (->> senders
                         (map (fn [uid]
                                (or (:display-name (get members-map uid)) uid)))
                         (str/join ", "))]
    [:span.reaction-pill
     {:class           (when (contains? senders my-id) "active")
      :title           hover-text
      :on-context-menu (fn [e]
                         (.preventDefault e)
                         (.stopPropagation e)
                         (re-frame/dispatch
                          [:ui/open-modal :reaction-details
                           {:room-id      active-room
                            :reactions    reactions
                            :window-props {:style {:max-width "400px" :min-height "300px"}}}]))
      :on-click        (fn [e]
                         (.preventDefault e)
                         (.stopPropagation e)
                         (re-frame/dispatch [:sdk/toggle-reaction active-room event-id emoji]))
      :style           {:cursor "pointer" :user-select "none"}}
     (if (str/includes? (str emoji) "://")
       [media
        {:uri   emoji
         :class "reaction-custom"
         :style {:pointer-events "none"
                 :height         "1.2em"
                 :width          "auto"
                 :vertical-align "middle"
                 :object-fit     "contain"}
         :alt   "emote"}]
       [:span.reaction-emoji {:style {:pointer-events "none"}} emoji])
     [:span.reaction-count {:style {:pointer-events "none"}} count]]))


(defn ^:ui reaction-row [{:keys [reactions my-id active-room event-id]}]
   [:div.reactions-row
    (for [[emoji count senders] reactions]
     ^{:key emoji}
     [reaction-pill emoji count senders my-id active-room event-id reactions])])


(defn ^:ui state-event-view [{:keys [sender-name content]}]
  (let [{:keys [tag inner]} content]
    [:div.timeline-state-event
     [:span
      (case tag
        "RoomPinnedEvents" (str sender-name " updated the pinned messages.")
        "RoomName"         (str sender-name " changed the room name to: " (:name inner))
        "RoomAvatar"       (str sender-name " changed the room avatar.")
        (str sender-name " updated " tag))]]))


(defn ^:ui system-event-view [icon text]
  [:div.timeline-system-event
   [:span.system-icon icon]
   [:span.system-text text]])

(defn ^:ui date-divider [ts]
  [:div.timeline-date-separator
   [:div.separator-line]
   [:span.separator-text (format-divider-date ts)]
   [:div.separator-line]])


(defn ^:ui new-message []
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:div.new-messages-separator
     [:div.separator-line]
     [:span.separator-text (tr [:container.timeline.status/new-messages])]
     [:div.separator-line]
     ]))



(defn ^:ui virtual-item [item]
 [:div.timeline-item-virtual-wrapper
       (case (:tag item)
         "DateDivider" [date-divider (:ts item)]
         "ReadMarker" [new-message]
           [system-event-view "-" (:tag item)])])

(defn ^:ui sub-virtual-items [content-tag item sender-name]
  (let [tr               @(re-frame/subscribe [:i18n/tr])]
    (cond
      (= content-tag "RoomMembership")
      [system-event-view (tr [:container.timeline.status/membership] [(truncate-name sender-name 16)])]
      (= content-tag "ProfileChange")
      [system-event-view (tr [:container.timeline.status/profile] [(truncate-name sender-name 16)])]
      (= content-tag "State")
      [state-event-view item]
      :else
      [system-event-view (tr [:container.timeline.status/unknown-event] [content-tag])])))

(defn ^:ui timeline-avatar [content-tag merge-with-prev? popover-member custom-tags active-room]
  [:div.timeline-avatar-wrapper
   {:class (when merge-with-prev? "is-merged")}
  (when (and (= content-tag "MsgLike") (not merge-with-prev?))
    [profile-popover-trigger popover-member custom-tags active-room nil
     [avatar {:id (:user-id popover-member) :name (:display-name popover-member)
              :url (:avatar-url popover-member) :size 36 :status :online :shape :circle}]])])


(defn ^:ui timeline-header [ts merge-with-prev? popover-member custom-tags active-room]
  [:div.timeline-header
   [profile-popover-trigger popover-member custom-tags active-room nil
    [:span.timeline-sender-name (truncate-name (:display-name popover-member) 20)]]
   [:span.timeline-timestamp (format-time ts)]])


(re-frame/reg-event-fx
 :msg/fetch-reply
 (fn [{:keys [db]} [_ room-id reply-id]]
   (js/console.error room-id)
   (when-not (get-in db [:reply-cache reply-id])
     (go
       (let [res (<! (mesh/do-with-thread! :engine-pool
                                           {:handler :get-event
                                            :arguments {:room-id room-id
                                                        :event-id reply-id}}))]
         (if (= (:status res) "success")
           (re-frame/dispatch [:msg/reply-fetch-success room-id reply-id (:event res)])
           (re-frame/dispatch [:msg/reply-fetch-error room-id reply-id])))))
   {:db (update-in db [:reply-cache reply-id] #(or % {:status :loading}))}))

(re-frame/reg-event-db
 :msg/reply-fetch-error
 (fn [db [_ room-id reply-id]]
   (assoc-in db [:reply-cache reply-id] {:status :error})))


(re-frame/reg-event-fx
 :msg/reply-fetch-success
 (fn [{:keys [db]} [_ room-id reply-id data]]
   {:db (assoc-in db [:reply-cache reply-id] {:status :success :data data})}))

(re-frame/reg-sub
 :msg/reply-cache
 (fn [db [_ reply-id]]
   (get-in db [:reply-cache reply-id])))

(defn ^:defer reply-container [tr in-reply-to room-id]
  (when in-reply-to
    (let [reply-id (:event-id in-reply-to)
          {:keys [status data]} @(re-frame/subscribe [:msg/reply-cache reply-id])
          local-msg             @(re-frame/subscribe [:timeline/event room-id reply-id])
          reply-msg             (or data local-msg)]

      (when (and (not reply-msg) (not status))
        (re-frame/dispatch [:msg/fetch-reply room-id reply-id]))

      [:div.timeline-reply-banner
       {:style {:cursor "pointer"}
        :on-click (fn [e]
                    (.preventDefault e)
                    (.stopPropagation e)
                    (when room-id
                      (re-frame/dispatch [:room/pretty-jump room-id in-reply-to])))}
       [:div.reply-indicator [icons/reply]]
       [:div.reply-content
        (if reply-msg
          [:<>
           [:span.reply-sender (truncate-name (:sender-name reply-msg) 16)]
           [:span.reply-preview
            (get-in reply-msg [:content :inner :content :body]
                    (tr [:container.timeline.status/media-preview]))]]

          [:span.reply-preview (tr [:container.timeline.status/reply-loading])])]])))

(defn render-message-content [tr msg-type-tag content-map in-reply-to event]
  (let [is-edited? (:is-edited? content-map)
        raw-body   (:body content-map)
        room-id    (:room-id event)]
    [:div.message-render-container
     [reply-container tr in-reply-to room-id]
     (case msg-type-tag
       "Text"    [message-text content-map]
       "Notice"  [message-text content-map]
       "Image"   [image-message event content-map]
       "Video"   [video-message event content-map]
       "Sticker" [sticker-message event content-map]
       "File"    [file-message event content-map tr]
       [:span.body (tr [:container.timeline.status/unsupported] [msg-type-tag])])
     (when is-edited?
       [:span.timeline-edited-label (tr [:container.timeline.status/edited])])
     [message-link-preview msg-type-tag raw-body]]))


(defn ^:ui timeline-body [item content active-room is-editing-this?]
  (let [{:keys [tag inner in-reply-to]} content
        tr               @(re-frame/subscribe [:i18n/tr])]

    [:div.timeline-body
     (cond
       is-editing-this?
       [inline-editor item active-room]
       (= tag "Sticker") [render-message-content tr "Sticker" inner in-reply-to item]
       (= tag "Redacted") [:span.redacted (tr [:container.timeline.status/redacted])]
       (= tag "UnableToDecrypt") [:span.decryption-error (tr [:container.timeline.status/decryption-error])]
       (or (= tag "Message") (= tag "Notice") (= tag "Emote"))
       (let [{m-tag :tag m-content :content} (if (= tag "Message") inner content)]
         [render-message-content tr (or m-tag "Text") m-content in-reply-to item])
       :else [:span.unknown (tr [:container.timeline.status/unknown-kind] [tag]) (str "Unknown message kind: " tag)])]))


(defn ^:ui event-tile-render [item]
  (let [active-room  @(re-frame/subscribe [:rooms/active-id])
        hs-url       @(re-frame/subscribe [:sdk/homeserver-url])
        is-mobile?   @(re-frame/subscribe [:ui/mobile?])
        my-profile   @(re-frame/subscribe [:sdk/profile])
        my-id        (:user-id my-profile)]
    (fn [item]
      (let [{:keys [id sender-id sender-name sender-avatar content-tag content type reactions ts is-own? merge-with-prev?]} item
            is-editing-this? @(re-frame/subscribe [:input/is-editing-event? active-room id])
            custom-tags      @(re-frame/subscribe [:room/power-level-tags active-room])
            members-map      @(re-frame/subscribe [:room/members-map active-room])
            member-data      (get members-map sender-id)
            popover-member   {:user-id sender-id
                              :display-name (or (:display-name member-data) sender-name)
                              :avatar-url (or (:avatar-url member-data) sender-avatar)
                              :power-level (or (:power-level member-data) 0)}]
        (if (= type :virtual)
          [virtual-item item]
          (if (= content-tag "MsgLike")
            [swipe-to-action-wrapper
             {:can-edit? is-own?
              :enabled? is-mobile?
              :on-action (fn [action]
                           (re-frame/dispatch [:input/set-context active-room action item]))
              :wrapper-props (merge (long-press-props
                                     #(event-menu %1 %2 item active-room my-id))
                                    {:class (str "timeline-message is-message"
                                                 (when merge-with-prev? " is-merged"))})}

             [timeline-avatar content-tag merge-with-prev? popover-member custom-tags active-room]
             [:div.timeline-content-wrapper
              [message-hover-toolbar item active-room my-id]
              (when-not merge-with-prev?
                [timeline-header ts merge-with-prev? popover-member custom-tags active-room])
              [timeline-body item content active-room is-editing-this?]
              (when (seq reactions)
                [reaction-row {:reactions reactions
                               :my-id my-id
                               :members-map members-map
                               :active-room active-room
                               :event-id (:event-or-transaction-id item)}])]]
            [:<>
             [sub-virtual-items content-tag item sender-name]]))))))



(defn event-tile [layout-node]
  (let [id         (:id layout-node)
        unread?    (:unread? layout-node)
        pojo       (:worker-data layout-node)
        react-tree (get-cached-react-tree id pojo)]

    (react/createElement "div"
                         #js {:key id
                              :data-event-id id
                              :style #js {:width "100%"}
                              :ref (fn [node]
                                     (when @visibility-observer
                                       (if (and node unread?)
                                         (.observe @visibility-observer node)
                                         (when node
                                           (.unobserve @visibility-observer node)))))}
                         react-tree)))
