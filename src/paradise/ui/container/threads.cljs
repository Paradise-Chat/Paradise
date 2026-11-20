(ns paradise.ui.container.threads
  (:require
   [cljs-workers.core :as main]
   [cljs.core.async :refer [<! go]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [clojure.string :as str]
   [paradise.shared.client.session-store :as store]
   [paradise.shared.client.state :as state]
   [paradise.shared.utils.helpers :refer [extract-metadata
                                          file->array-buffer
                                          format-time
                                          get-file
                                          truncate-name
                                          unregister-file!]]
   [paradise.shared.utils.svg :as icons]
   [paradise.ui.container.reusable :refer [room-header]]
   [paradise.ui.container.timeline.hydrator :refer [!local-timeline-data]]
   [paradise.ui.container.timeline.item :refer [event-tile-render]]
   [paradise.ui.global :refer [avatar]]
   [paradise.ui.input.composer :refer [extract-editor-payload tiptap-component]]
   [paradise.ui.input.drafts :refer [attachment-preview]]
   [paradise.ui.input.voice :refer [voice-recorder-bar]]
   [promesa.core :as p]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [taoensso.timbre :as log]))

(def ^:private forum-overrides-key "forum_room_overrides_v1")

(defn- ok-status? [s]
  (contains? #{"success" :success} s))

(defn thread-draft-key [room-id thread-id]
  (str room-id "::thread::" thread-id))

(defn forum-post-draft-key [room-id]
  (str room-id "::forum-post"))

(defn- default-thread-title [root-event summary]
  (or (not-empty (str/trim (str (:title summary ""))))
      (let [body (or (get-in root-event [:content :inner :content :body])
                     (get-in root-event [:content :inner :content :caption])
                     (:root-body summary)
                     "")]
        (not-empty (truncate-name (str/replace (str/trim body) #"\s+" " ") 48)))))

(defn- handle-thread-files! [draft-key files]
  (doseq [raw-file files]
    (when raw-file
      (-> (extract-metadata raw-file)
          (p/then (fn [att]
                    (when att
                      (re-frame/dispatch [:composer/add-attachment draft-key att]))))
          (p/catch (fn [e]
                     (log/error "Thread attachment metadata error:" e)))))))

(defn- filter-and-sort-forum-items [items query sort-mode]
  (let [q        (str/lower-case (str/trim (str query "")))
        filtered (if (str/blank? q)
                   items
                   (filter (fn [entry]
                             (let [haystack (str/lower-case
                                             (str (:title entry "") " "
                                                  (:root-body entry "") " "
                                                  (:root-sender entry "") " "
                                                  (:latest-body entry "")))]
                               (str/includes? haystack q)))
                           items))]
    (vec
     (case (or sort-mode :recent)
       :newest       (sort-by #(or (:root-ts %) (:latest-ts %) 0) > filtered)
       :most-replies (sort-by #(vector (or (:reply-count %) 0)
                                       (or (:latest-ts %) (:root-ts %) 0))
                              #(compare %2 %1)
                              filtered)
       (sort-by #(or (:latest-ts %) (:root-ts %) 0) > filtered)))))

(re-frame/reg-event-db
 :rooms/set-forum-overrides
 (fn [db [_ ids]]
   (assoc db :forum-room-overrides (set (keep #(some-> % str not-empty) ids)))))

(re-frame/reg-event-fx
 :rooms/mark-forum-room
 (fn [{:keys [db]} [_ room-id enabled?]]
   (let [rid     (str room-id)
         cur-set (set (get db :forum-room-overrides #{}))
         nxt-set (if enabled? (conj cur-set rid) (disj cur-set rid))]
     (store/set-setting! forum-overrides-key (vec nxt-set))
     {:db (-> db
              (assoc :forum-room-overrides nxt-set)
              (assoc-in [:room-previews rid :is-forum?] (boolean enabled?)))})))

(re-frame/reg-event-fx
 :rooms/toggle-forum-mode
 (fn [{:keys [db]} [_ room-id]]
   (let [rid          (str room-id)
         native-forum (boolean (get-in db [:room-previews rid :native-forum?]))
         cur-ovr      (set (get db :forum-room-overrides #{}))
         currently-f? (or (= (:main-focus db) :thread)
                          (contains? cur-ovr rid)
                          (boolean (get-in db [:room-previews rid :is-forum?])))
         next-forum?  (if native-forum true (not currently-f?))
         next-ovr     (if next-forum? (conj cur-ovr rid) (disj cur-ovr rid))
         active?      (= (:active-room-id db) rid)
         next-focus   (if next-forum? :thread :timeline)]
     (store/set-setting! forum-overrides-key (vec next-ovr))
     {:db         (cond-> (assoc db :forum-room-overrides next-ovr)
                    (not native-forum)
                    (assoc-in [:room-previews rid :is-forum?] next-forum?)
                    (and active? next-forum? (= (:side-panel db) :threads))
                    (assoc :side-panel nil)
                    active?
                    (assoc :main-focus next-focus
                           :ui (assoc (:ui db) :main-focus next-focus)))
      :dispatch-n (cond-> []
                    (and active? next-forum?)
                    (conj [:threads/fetch-list rid false])
                    (and active? (not next-forum?))
                    (conj [:sdk/boot-timeline rid]))})))

(defonce ^:private _init-forum-overrides
  (-> (store/get-setting forum-overrides-key)
      (p/then (fn [saved]
                (when (sequential? saved)
                  (re-frame/dispatch [:rooms/set-forum-overrides saved]))))
      (p/catch (fn [_] nil))))

(re-frame/reg-event-fx
 :threads/toggle-panel
 (fn [{:keys [db]} [_ room-id]]
   (let [cur-panel (:side-panel db)
         closing?  (= cur-panel :threads)
         cur-th    (get-in db [:threads/by-room room-id :active-thread])]
     (if closing?
       (do
         (when (and cur-th (:thread-id cur-th))
           (when-let [pool @state/!engine-pool]
             (main/do-with-pool! pool {:handler   :cleanup-thread
                                       :arguments {:room-id   room-id
                                                   :thread-id (:thread-id cur-th)}})))
         {:db (-> db
                  (assoc :side-panel nil)
                  (assoc-in [:threads/by-room room-id :active-thread] nil))})
       {:db       (assoc db :side-panel :threads)
        :dispatch [:threads/fetch-list room-id false]}))))

(re-frame/reg-event-fx
 :threads/fetch-list
 (fn [{:keys [db]} [_ room-id paginate?]]
   (when room-id
     (let [forum? (or (= (:main-focus db) :thread)
                      (boolean (get-in db [:room-previews room-id :is-forum?]))
                      (contains? (get db :forum-room-overrides #{}) room-id))]
       (when-let [pool @state/!engine-pool]
         (main/do-with-pool!
          pool
          {:handler   :list-threads
           :arguments {:room-id           room-id
                       :paginate?         (boolean paginate?)
                       :forum-mode?       (boolean forum?)
                       :include-archived? (boolean (get-in db [:threads/by-room room-id :show-archived?] false))}}))))
   {:db (assoc-in db [:threads/by-room room-id :list-loading?] true)}))

(re-frame/reg-event-fx
 :threads/set-filter
 (fn [{:keys [db]} [_ room-id show-archived?]]
   {:db       (assoc-in db [:threads/by-room room-id :show-archived?] (boolean show-archived?))
    :dispatch [:threads/fetch-list room-id false]}))

(re-frame/reg-event-db
 :threads/set-forum-search
 (fn [db [_ room-id query]]
   (assoc-in db [:threads/by-room room-id :forum-search] (str (or query "")))))

(re-frame/reg-event-db
 :threads/set-forum-sort
 (fn [db [_ room-id sort-mode]]
   (assoc-in db [:threads/by-room room-id :forum-sort] (keyword (or sort-mode :recent)))))

(re-frame/reg-event-db
 :threads/toggle-forum-composer
 (fn [db [_ room-id open?]]
   (let [cur (boolean (get-in db [:threads/by-room room-id :composer-open?]))
         nxt (if (some? open?) (boolean open?) (not cur))]
     (assoc-in db [:threads/by-room room-id :composer-open?] nxt))))

(re-frame/reg-event-db
 :threads/set-forum-post-title
 (fn [db [_ room-id title]]
   (assoc-in db [:threads/by-room room-id :forum-post-title] (str (or title "")))))

(re-frame/reg-event-db
 :threads/sync-list
 (fn [db [_ {:keys [room-id threads has-more? loading?]}]]
   (if-not room-id
     db
     (-> db
         (assoc-in [:threads/by-room room-id :items] (vec (or threads [])))
         (assoc-in [:threads/by-room room-id :has-more?] (boolean has-more?))
         (assoc-in [:threads/by-room room-id :list-loading?] (boolean loading?))))))

(re-frame/reg-event-fx
 :threads/open
 (fn [{:keys [db]} [_ room-id thread-spec]]
   (let [prev-th     (get-in db [:threads/by-room room-id :active-thread])
         root-id     (or (:root-event-id thread-spec)
                         (:id (:root-event thread-spec))
                         (:thread-id thread-spec))
         thread-id   (or (:thread-id thread-spec) root-id)
         is-new?     (boolean (:is-new? thread-spec))
         title       (default-thread-title (:root-event thread-spec) thread-spec)
         in-forum?   (= (:main-focus db) :thread)]
     (when (and prev-th
                (:thread-id prev-th)
                (not= (:thread-id prev-th) thread-id))
       (when-let [pool @state/!engine-pool]
         (main/do-with-pool! pool {:handler   :cleanup-thread
                                   :arguments {:room-id   room-id
                                               :thread-id (:thread-id prev-th)}})))
     (when-let [pool @state/!engine-pool]
       (main/do-with-pool! pool {:handler   :boot-thread
                                 :arguments {:room-id       room-id
                                             :thread-id     thread-id
                                             :root-event-id root-id
                                             :is-new?       is-new?
                                             :title         title}}))
     {:db (cond-> (assoc-in db [:threads/by-room room-id :active-thread]
                            {:thread-id     thread-id
                             :root-event-id root-id
                             :title         title
                             :custom-title  (or title "")
                             :is-new?       is-new?
                             :root-event    (:root-event thread-spec)
                             :events        []
                             :has-more?     false
                             :loading?      (not is-new?)})
            (not in-forum?)
            (assoc :side-panel :threads))})))

(re-frame/reg-event-fx
 :msg/thread
 (fn [{:keys [db]} [_ room-id msg-id maybe-summary maybe-item]]
   (let [local-room   (get @!local-timeline-data room-id {})
         cached-items (concat (get-in db [:timeline-data room-id :live] [])
                              (get-in db [:timeline-data room-id :focused] [])
                              (:live local-room [])
                              (:focused local-room []))
         root-item    (or maybe-item
                          (some #(when (= (:id %) msg-id) %) cached-items))
         summary      (or maybe-summary (:thread-summary root-item))
         has-thread?  (some? (:thread-id summary))
         thread-id    (or (:thread-id summary) msg-id)]
     {:dispatch [:threads/open
                 room-id
                 {:thread-id     thread-id
                  :root-event-id msg-id
                  :title         (:title summary)
                  :is-new?       (not has-thread?)
                  :root-event    root-item}]})))

(re-frame/reg-event-fx
 :threads/back-to-list
 (fn [{:keys [db]} [_ room-id]]
   (let [cur-th (get-in db [:threads/by-room room-id :active-thread])]
     (when (and cur-th (:thread-id cur-th))
       (when-let [pool @state/!engine-pool]
         (main/do-with-pool! pool {:handler   :cleanup-thread
                                   :arguments {:room-id   room-id
                                               :thread-id (:thread-id cur-th)}})))
     {:db       (assoc-in db [:threads/by-room room-id :active-thread] nil)
      :dispatch [:threads/fetch-list room-id false]})))

(re-frame/reg-event-db
 :threads/set-custom-title
 (fn [db [_ room-id title]]
   (assoc-in db [:threads/by-room room-id :active-thread :custom-title] title)))

(re-frame/reg-event-fx
 :threads/paginate-active
 (fn [{:keys [db]} [_ room-id]]
   (when-let [cur-th (get-in db [:threads/by-room room-id :active-thread])]
     (when-let [pool @state/!engine-pool]
       (main/do-with-pool! pool {:handler   :paginate-thread
                                 :arguments {:room-id       room-id
                                             :thread-id     (:thread-id cur-th)
                                             :root-event-id (:root-event-id cur-th)
                                             :amount        30}})))
   {:db (assoc-in db [:threads/by-room room-id :active-thread :paginating?] true)}))

(re-frame/reg-event-db
 :threads/sync-timeline
 (fn [db [_ {:keys [room-id thread-id prev-thread-id title root-event events has-more? loading?]}]]
   (let [cur-th (get-in db [:threads/by-room room-id :active-thread])]
     (if (and cur-th
              (or (= (:thread-id cur-th) thread-id)
                  (and prev-thread-id (= (:thread-id cur-th) prev-thread-id))
                  (= (:root-event-id cur-th) thread-id)))
       (update-in db [:threads/by-room room-id :active-thread]
                  (fn [st]
                    (-> st
                        (assoc :thread-id   (or thread-id (:thread-id st))
                               :title       (or (not-empty title) (:title st))
                               :is-new?     false
                               :root-event  (or root-event (:root-event st))
                               :events      (vec (or events []))
                               :has-more?   (boolean has-more?)
                               :loading?    (boolean loading?)
                               :paginating? false))))
       db))))

(re-frame/reg-event-fx
 :threads/send
 (fn [{:keys [db]} [_ room-id payload]]
   (let [cur-th      (get-in db [:threads/by-room room-id :active-thread])
         thread-id   (:thread-id cur-th)
         root-id     (:root-event-id cur-th)
         draft-key   (thread-draft-key room-id thread-id)
         {:keys [text html ast]} payload
         attachments (get-in db [:drafts draft-key :attachments] [])
         raw-ctx     (get-in db [:input-context draft-key])
         worker-ctx  (when raw-ctx
                       {:mode        (:mode raw-ctx)
                        :target      (get-in raw-ctx [:target :id])
                        :msg-tag     (get-in raw-ctx [:target :content :inner :tag])
                        :ffi-content (get-in raw-ctx [:target :content :inner :content :ffi-content])})
         final-title (or (not-empty (str/trim (str (:custom-title cur-th ""))))
                         (:title cur-th)
                         (truncate-name (str/trim (str text)) 48)
                         "Thread")]
     (when (and cur-th @state/!engine-pool)
       (let [pool @state/!engine-pool]
         (go
           (try
             (let [worker-atts
                   (if (seq attachments)
                     (<p! (p/all
                           (map (fn [att]
                                  (p/let [f  (get-file (:file-id att))
                                          ab (if f
                                               (file->array-buffer f)
                                               (p/resolved (js/ArrayBuffer. 0)))]
                                    (assoc att :buffer ab)))
                                attachments)))
                     [])
                   _   (doseq [att attachments]
                         (when (:preview-url att) (js/URL.revokeObjectURL (:preview-url att)))
                         (when (:file-id att) (unregister-file! (:file-id att))))
                   res (<! (main/do-with-pool!
                            pool
                            {:handler   :send-thread-message
                             :arguments {:room-id       room-id
                                         :thread-id     thread-id
                                         :root-event-id root-id
                                         :is-new?       (boolean (:is-new? cur-th))
                                         :title         final-title
                                         :text          text
                                         :html          html
                                         :ast           ast
                                         :attachments   worker-atts
                                         :context       worker-ctx}}))]
               (when (and (ok-status? (:status res))
                          (:thread-id res)
                          (not= (:thread-id res) thread-id))
                 (re-frame/dispatch
                  [:threads/sync-timeline
                   {:room-id        room-id
                    :prev-thread-id thread-id
                    :thread-id      (:thread-id res)
                    :title          (or (:title res) final-title)
                    :root-event     (:root-event cur-th)
                    :events         (or (:events res) (:events cur-th))
                    :has-more?      false
                    :loading?       false}])))
             (catch :default e
               (log/error "Failed to send thread message:" e))))))
     {:db (-> db
              (assoc-in [:composer draft-key] {:text "" :html "" :loaded-text "" :uploading? false})
              (assoc-in [:drafts draft-key :attachments] [])
              (update :input-context dissoc draft-key))})))

(re-frame/reg-event-fx
 :threads/create-forum-post
 (fn [{:keys [db]} [_ room-id payload]]
   (let [draft-key   (forum-post-draft-key room-id)
         raw-title   (str/trim (str (get-in db [:threads/by-room room-id :forum-post-title] "")))
         {:keys [text html ast]} payload
         attachments (get-in db [:drafts draft-key :attachments] [])
         final-title (or (not-empty raw-title)
                         (not-empty (truncate-name (str/trim (str text)) 56))
                         "Post")]
     (when (and room-id @state/!engine-pool)
       (let [pool @state/!engine-pool]
         (go
           (try
             (let [worker-atts
                   (if (seq attachments)
                     (<p! (p/all
                           (map (fn [att]
                                  (p/let [f  (get-file (:file-id att))
                                          ab (if f
                                               (file->array-buffer f)
                                               (p/resolved (js/ArrayBuffer. 0)))]
                                    (assoc att :buffer ab)))
                                attachments)))
                     [])
                   _   (doseq [att attachments]
                         (when (:preview-url att) (js/URL.revokeObjectURL (:preview-url att)))
                         (when (:file-id att) (unregister-file! (:file-id att))))
                   res (<! (main/do-with-pool!
                            pool
                            {:handler   :create-forum-thread
                             :arguments {:room-id     room-id
                                         :title       final-title
                                         :text        text
                                         :html        html
                                         :ast         ast
                                         :attachments worker-atts}}))]
               (re-frame/dispatch [:threads/set-forum-posting room-id false])
               (when (ok-status? (:status res))
                 (re-frame/dispatch [:threads/fetch-list room-id false])
                 (when-let [tid (or (:thread-id res) (:root-event-id res))]
                   (re-frame/dispatch
                    [:threads/open room-id
                     {:thread-id     tid
                      :root-event-id (or (:root-event-id res) tid)
                      :title         (or (:title res) final-title)
                      :root-event    (:root-event res)
                      :is-new?       false}]))))
             (catch :default e
               (log/error "Failed to create forum post:" e)
               (re-frame/dispatch [:threads/set-forum-posting room-id false]))))))
     {:db (-> db
              (assoc-in [:threads/by-room room-id :forum-posting?] true)
              (assoc-in [:threads/by-room room-id :composer-open?] false)
              (assoc-in [:threads/by-room room-id :forum-post-title] "")
              (assoc-in [:composer draft-key] {:text "" :html "" :loaded-text "" :uploading? false})
              (assoc-in [:drafts draft-key :attachments] []))})))

(re-frame/reg-event-db
 :threads/set-forum-posting
 (fn [db [_ room-id posting?]]
   (assoc-in db [:threads/by-room room-id :forum-posting?] (boolean posting?))))

(re-frame/reg-sub
 :threads/room-state
 (fn [db [_ room-id]]
   (merge {:items            []
           :has-more?        false
           :list-loading?    false
           :show-archived?   false
           :forum-search     ""
           :forum-sort       :recent
           :composer-open?   false
           :forum-post-title ""
           :forum-posting?   false
           :active-thread    nil}
          (get-in db [:threads/by-room room-id]))))

(re-frame/reg-sub
 :threads/active-thread
 (fn [db [_ room-id]]
   (get-in db [:threads/by-room room-id :active-thread])))

(defn ^:ui thread-list-card [room-id entry tr]
  (let [title       (or (not-empty (:title entry))
                        (not-empty (:root-body entry))
                        (tr [:container.threads/untitled]))
        reply-count (or (:reply-count entry) 0)
        latest-ts   (or (:latest-ts entry) (:root-ts entry))
        reply-label (if (= reply-count 1)
                      (tr [:container.threads/one-reply])
                      (tr [:container.threads/many-replies] [reply-count]))]
    [:div.thread-list-card
     {:on-click #(re-frame/dispatch [:threads/open room-id entry])}
     [:div.thread-card-top
      [avatar {:id    (or (:root-sender-id entry) (:root-sender entry))
               :name  (or (:root-sender entry) "?")
               :url   (:root-avatar entry)
               :size  24
               :shape :circle}]
      [:span.thread-card-author (truncate-name (or (:root-sender entry) "") 22)]
      (when latest-ts
        [:span.thread-card-time (format-time latest-ts)])]
     [:div.thread-card-title title]
     [:div.thread-card-footer
      [:span.thread-card-count
       [icons/thread {:width "13px" :height "13px"}]
       [:span reply-label]]
      (when-let [latest-body (not-empty (:latest-body entry))]
        [:span.thread-card-preview
         (if-let [ls (not-empty (:latest-sender entry))]
           (str (truncate-name ls 14) ": " latest-body)
           latest-body)])]]))

(defn ^:ui threads-list-view [room-id room-state tr]
  (let [{:keys [items has-more? list-loading? show-archived?]} room-state]
    [:div.threads-list-view
     [:div.threads-filter-bar
      [:button.threads-filter-pill
       {:type     "button"
        :class    (when-not show-archived? "is-active")
        :on-click #(re-frame/dispatch [:threads/set-filter room-id false])}
       (tr [:container.threads/filter-active])]
      [:button.threads-filter-pill
       {:type     "button"
        :class    (when show-archived? "is-active")
        :on-click #(re-frame/dispatch [:threads/set-filter room-id true])}
       (tr [:container.threads/filter-all])]]

     [:div.threads-cards-scroll
      (cond
        (and list-loading? (empty? items))
        [:div.threads-empty-state
         [:div.spinner]
         [:span (tr [:container.threads/loading])]]

        (empty? items)
        [:div.threads-empty-state
         [icons/thread {:width "44px" :height "44px" :style {:opacity 0.3}}]
         [:div.threads-empty-title (tr [:container.threads/empty-title])]
         [:div.threads-empty-hint (tr [:container.threads/empty-hint])]]

        :else
        [:<>
         (for [entry items]
           ^{:key (or (:thread-id entry) (:root-event-id entry))}
           [thread-list-card room-id entry tr])
         (when has-more?
           [:button.threads-load-more-btn
            {:type     "button"
             :disabled list-loading?
             :on-click #(re-frame/dispatch [:threads/fetch-list room-id true])}
            (tr [:container.threads/load-more])])])]]))

(defn ^:ui thread-composer [room-id active-thread tr]
  (r/with-let [!editor           (r/atom nil)
               !recording-voice? (r/atom false)]
    (let [recording?  @!recording-voice?
          thread-id   (:thread-id active-thread)
          draft-key   (thread-draft-key room-id thread-id)
          attachments @(re-frame/subscribe [:composer/attachments draft-key])
          loaded-text @(re-frame/subscribe [:composer/loaded-text draft-key])
          cached-html @(re-frame/subscribe [:composer/cached-html draft-key])
          context     @(re-frame/subscribe [:input/context draft-key])
          submit!     (fn []
                        (when-let [ed @!editor]
                          (let [{:keys [text html] :as payload} (extract-editor-payload ed)
                                can-send? (or (not (str/blank? text))
                                              (str/includes? (str html) "<img")
                                              (seq attachments))]
                            (if can-send?
                              (do
                                (re-frame/dispatch [:threads/send room-id payload])
                                (-> ed .chain .clearContent .run)
                                true)
                              false))))]
      [:div.thread-composer-outer
       (when (and context (= (:mode context) :reply))
         [:div.input-context-banner
          [:div.context-info
           [:span (tr [:composer/replying-to]
                      [(truncate-name (get-in context [:target :sender-name]) 24)])]]
          [:button.context-cancel-btn
           {:on-click #(re-frame/dispatch [:input/clear-context draft-key])}
           [icons/exit]]])
       [:div.timeline-input-wrapper.is-thread-composer
        (when (seq attachments)
          [:div.composer-attachments
           (doall
            (map-indexed
             (fn [idx att]
               ^{:key (str "th-att-" idx)}
               [attachment-preview draft-key att idx])
             attachments))])
        (if recording?
          [voice-recorder-bar
           {:room-id       room-id
            :thread-id     thread-id
            :root-event-id (:root-event-id active-thread)
            :is-new?       (boolean (:is-new? active-thread))
            :title         (or (not-empty (str/trim (str (:custom-title active-thread ""))))
                               (:title active-thread))
            :on-close      #(reset! !recording-voice? false)}]
          [:div.timeline-input-row
           [:label.timeline-upload-btn
            {:title (tr [:composer/upload-tooltip])}
            [:input {:type      "file"
                     :multiple  true
                     :style     {:display "none"}
                     :on-change (fn [e]
                                  (let [files (.. e -target -files)
                                        fvec  (reduce (fn [acc i]
                                                        (if-let [f (aget files i)]
                                                          (conj acc f)
                                                          acc))
                                                      []
                                                      (range (or (.-length files) 0)))]
                                    (when (seq fvec)
                                      (handle-thread-files! draft-key fvec))
                                    (set! (.-value (.-target e)) "")))}]
            [icons/plus]]
           [:div.timeline-editor-container
            [:> tiptap-component
             #js {:activeId      draft-key
                  :key           (str "thread-ed-" draft-key)
                  :loadedText    loaded-text
                  :cachedHtml    cached-html
                  :onChange      (fn [text html]
                                   (re-frame/dispatch [:composer/on-change draft-key text html]))
                  :onSend        (fn [_ _] (submit!))
                  :onFiles       (fn [files]
                                   (let [fvec (reduce (fn [acc i]
                                                        (if-let [f (aget files i)]
                                                          (conj acc f)
                                                          acc))
                                                      []
                                                      (range (or (.-length files) 0)))]
                                     (when (seq fvec)
                                       (handle-thread-files! draft-key fvec))))
                  :placeholder   (tr [:container.threads/reply-placeholder])
                  :onEditorReady #(reset! !editor %)}]]
           [:button.timeline-mic-btn
            {:type     "button"
             :title    (tr [:composer/record-voice-tooltip])
             :on-click #(reset! !recording-voice? true)}
            [icons/mic {:width "20px" :height "20px"}]]
           [:button.timeline-send-btn
            {:type     "button"
             :on-click (fn [e]
                         (.preventDefault e)
                         (.stopPropagation e)
                         (submit!))}
            [icons/send]]])]])))

(defn ^:ui thread-conversation-view
  ([room-id active-thread tr]
   (thread-conversation-view room-id active-thread tr nil))
  ([room-id active-thread tr {:keys [forum-mode?]}]
   (r/with-let [!scroll-el  (r/atom nil)
                !prev-count (r/atom 0)]
     (let [{:keys [thread-id root-event events has-more? loading? paginating? is-new? custom-title]} active-thread
           hs-url      @(re-frame/subscribe [:sdk/homeserver-url])
           is-mobile?  @(re-frame/subscribe [:ui/mobile?])
           my-profile  @(re-frame/subscribe [:sdk/profile])
           my-id       (:user-id my-profile)
           draft-key   (thread-draft-key room-id thread-id)
           root-id     (or (:id root-event) (:root-event-id active-thread))
           reply-items (->> events
                            (remove #(= (:id %) root-id))
                            (mapv #(assoc %
                                          :in-thread-view? true
                                          :input-scope-id  draft-key)))
           total-cnt   (count reply-items)]
       (when (and (> total-cnt @!prev-count) @!scroll-el)
         (reset! !prev-count total-cnt)
         (js/requestAnimationFrame
          (fn []
            (when-let [el @!scroll-el]
              (set! (.-scrollTop el) (.-scrollHeight el))))))
       (when (< total-cnt @!prev-count)
         (reset! !prev-count total-cnt))
       [:div.thread-conversation-view
        {:class (when forum-mode? "is-forum-detail")}
        (when (and is-new? (not forum-mode?))
          [:div.thread-new-title-bar
           [:label.thread-new-title-label (tr [:container.threads/title-label])]
           [:input.thread-new-title-input
            {:type        "text"
             :value       (or custom-title "")
             :placeholder (tr [:container.threads/title-placeholder])
             :on-change   #(re-frame/dispatch
                            [:threads/set-custom-title room-id (.. % -target -value)])}]])

        [:div.thread-messages-scroll
         {:ref #(when % (reset! !scroll-el %))}
         [:div.thread-messages-inner
          (when root-event
            [:div.thread-starter-card
             [event-tile-render
              (assoc root-event
                     :in-thread-view?  true
                     :merge-with-prev? false
                     :input-scope-id   draft-key)
              hs-url
              is-mobile?
              my-id
              tr]
             [:div.thread-replies-divider
              [:span (if (pos? total-cnt)
                       (if (= total-cnt 1)
                         (tr [:container.threads/one-reply])
                         (tr [:container.threads/many-replies] [total-cnt]))
                       (tr [:container.threads/start-prompt]))]]])

          (when has-more?
            [:button.threads-load-more-btn
             {:type     "button"
              :disabled paginating?
              :on-click #(re-frame/dispatch [:threads/paginate-active room-id])}
             (tr [:container.threads/load-older])])

          (if (and loading? (empty? reply-items))
            [:div.threads-empty-state
             [:div.spinner]
             [:span (tr [:container.threads/loading])]]
            (for [ev reply-items]
              ^{:key (:id ev)}
              [:div.thread-reply-item
               [event-tile-render ev hs-url is-mobile? my-id tr]]))]]

        [thread-composer room-id active-thread tr]]))))

(defn ^:ui threads-panel []
  (let [active-room   @(re-frame/subscribe [:rooms/active-id])
        room-state    @(re-frame/subscribe [:threads/room-state active-room])
        active-thread (:active-thread room-state)
        tr            @(re-frame/subscribe [:i18n/tr])]
    (if active-thread
      [thread-conversation-view active-room active-thread tr]
      [threads-list-view active-room room-state tr])))

(defn ^:ui forum-new-post-composer [room-id room-state tr]
  (r/with-let [!editor (r/atom nil)]
    (let [draft-key   (forum-post-draft-key room-id)
          post-title  (:forum-post-title room-state "")
          posting?    (:forum-posting? room-state false)
          attachments @(re-frame/subscribe [:composer/attachments draft-key])
          loaded-text @(re-frame/subscribe [:composer/loaded-text draft-key])
          cached-html @(re-frame/subscribe [:composer/cached-html draft-key])
          submit!     (fn []
                        (when-let [ed @!editor]
                          (let [{:keys [text html] :as payload} (extract-editor-payload ed)
                                can-post? (and (not posting?)
                                               (or (not (str/blank? post-title))
                                                   (not (str/blank? text))
                                                   (str/includes? (str html) "<img")
                                                   (seq attachments)))]
                            (if can-post?
                              (do
                                (re-frame/dispatch [:threads/create-forum-post room-id payload])
                                (-> ed .chain .clearContent .run)
                                true)
                              false))))]
      [:div.forum-composer-card
       [:div.forum-composer-field
        [:label.thread-new-title-label (tr [:container.threads/post-title-label])]
        [:input.forum-title-input
         {:type        "text"
          :auto-focus  true
          :value       post-title
          :placeholder (tr [:container.threads/post-title-placeholder])
          :on-change   #(re-frame/dispatch
                         [:threads/set-forum-post-title room-id (.. % -target -value)])}]]

       [:div.timeline-input-wrapper.is-forum-composer
        (when (seq attachments)
          [:div.composer-attachments
           (doall
            (map-indexed
             (fn [idx att]
               ^{:key (str "fp-att-" idx)}
               [attachment-preview draft-key att idx])
             attachments))])
        [:div.timeline-input-row
         [:label.timeline-upload-btn
          {:title (tr [:composer/upload-tooltip])}
          [:input {:type      "file"
                   :multiple  true
                   :style     {:display "none"}
                   :on-change (fn [e]
                                (let [files (.. e -target -files)
                                      fvec  (reduce (fn [acc i]
                                                      (if-let [f (aget files i)]
                                                        (conj acc f)
                                                        acc))
                                                    []
                                                    (range (or (.-length files) 0)))]
                                  (when (seq fvec)
                                    (handle-thread-files! draft-key fvec))
                                  (set! (.-value (.-target e)) "")))}]
          [icons/plus]]
         [:div.timeline-editor-container
          [:> tiptap-component
           #js {:activeId      draft-key
                :key           (str "forum-ed-" draft-key)
                :loadedText    loaded-text
                :cachedHtml    cached-html
                :onChange      (fn [text html]
                                 (re-frame/dispatch [:composer/on-change draft-key text html]))
                :onSend        (fn [_ _] (submit!))
                :onFiles       (fn [files]
                                 (let [fvec (reduce (fn [acc i]
                                                      (if-let [f (aget files i)]
                                                        (conj acc f)
                                                        acc))
                                                    []
                                                    (range (or (.-length files) 0)))]
                                   (when (seq fvec)
                                     (handle-thread-files! draft-key fvec))))
                :placeholder   (tr [:container.threads/post-body-placeholder])
                :onEditorReady #(reset! !editor %)}]]]]

       [:div.forum-composer-actions
        [:button.forum-btn-secondary
         {:type     "button"
          :disabled posting?
          :on-click #(re-frame/dispatch [:threads/toggle-forum-composer room-id false])}
         (tr [:container.threads/cancel])]
        [:button.forum-btn-primary
         {:type     "button"
          :disabled posting?
          :on-click (fn [e]
                      (.preventDefault e)
                      (.stopPropagation e)
                      (submit!))}
         [icons/send {:width "14px" :height "14px"}]
         [:span (tr [:container.threads/publish-post])]]]])))

(defn ^:ui forum-sort-selector [room-id forum-sort tr]
  (r/with-let [!open? (r/atom false)]
    (let [open?    @!open?
          cur-sort (or forum-sort :recent)
          options  [{:id :recent       :label (tr [:container.threads/sort-recent])}
                    {:id :newest       :label (tr [:container.threads/sort-newest])}
                    {:id :most-replies :label (tr [:container.threads/sort-replies])}]
          cur-lbl  (or (some #(when (= (:id %) cur-sort) (:label %)) options)
                       (tr [:container.threads/sort-recent]))]
      [:div.forum-sort-dropdown
       [:button.forum-sort-trigger
        {:type          "button"
         :class         (when open? "is-open")
         :aria-expanded open?
         :on-click      #(swap! !open? not)}
        [:span.forum-sort-trigger-label cur-lbl]
        [:span.forum-sort-chevron
         {:class (when open? "is-open")}
         [icons/chevron-down {:width "14px" :height "14px"}]]]
       (when open?
         [:<>
          [:div.forum-dropdown-backdrop
           {:on-click #(reset! !open? false)}]
          (into
           [:div.forum-sort-menu {:role "listbox"}]
           (mapv
            (fn [{:keys [id label]}]
              (let [selected? (= id cur-sort)]
                ^{:key (name id)}
                [:button.forum-sort-option
                 {:type          "button"
                  :role          "option"
                  :aria-selected selected?
                  :class         (when selected? "is-selected")
                  :on-click      (fn []
                                   (reset! !open? false)
                                   (re-frame/dispatch [:threads/set-forum-sort room-id id]))}
                 [:span label]
                 (when selected?
                   [icons/check {:width "14px" :height "14px"}])]))
            options))])])))

(defn ^:ui forum-post-card [room-id entry tr]
  (let [raw-title   (not-empty (str/trim (str (:title entry ""))))
        raw-body    (not-empty (str/trim (str (:root-body entry ""))))
        title       (or raw-title
                        (when raw-body (truncate-name raw-body 72))
                        (tr [:container.threads/untitled]))
        snippet     (when (and raw-body (not= raw-body title))
                      raw-body)
        reply-count (or (:reply-count entry) 0)
        latest-ts   (or (:latest-ts entry) (:root-ts entry))
        archived?   (boolean (:archived? entry))
        reply-label (if (= reply-count 1)
                      (tr [:container.threads/one-reply])
                      (tr [:container.threads/many-replies] [reply-count]))]
    [:div.forum-post-card
     {:on-click #(re-frame/dispatch [:threads/open room-id entry])}
     [:div.forum-post-header
      [:div.forum-post-author-group
       [avatar {:id    (or (:root-sender-id entry) (:root-sender entry))
                :name  (or (:root-sender entry) "?")
                :url   (:root-avatar entry)
                :size  26
                :shape :circle}]
       [:span.forum-post-author (truncate-name (or (:root-sender entry) "") 24)]
       (when archived?
         [:span.forum-post-archived-badge (tr [:container.threads/archived-badge])])]
      (when latest-ts
        [:span.forum-post-time (format-time latest-ts)])]
     [:div.forum-post-body
      [:h4.forum-post-title title]
      (when snippet
        [:p.forum-post-snippet snippet])]
     [:div.forum-post-footer
      [:span.forum-reply-pill
       [icons/thread {:width "13px" :height "13px"}]
       [:span reply-label]]
      (when-let [latest-body (not-empty (:latest-body entry))]
        [:span.forum-post-latest
         (if-let [ls (not-empty (:latest-sender entry))]
           (str (truncate-name ls 14) ": " latest-body)
           latest-body)])]]))

(defn ^:ui threads-forum-view [room-id]
  (r/with-let [!booted-room (r/atom nil)]
    (let [room-meta     @(re-frame/subscribe [:rooms/active-metadata])
          room-state    @(re-frame/subscribe [:threads/room-state room-id])
          tr            @(re-frame/subscribe [:i18n/tr])
          display-name  (when room-id
                          (or (:name room-meta) (when room-meta (.-name room-meta)) room-id))
          active-thread (:active-thread room-state)
          {:keys [items has-more? list-loading? show-archived?
                  forum-search forum-sort composer-open?]} room-state
          visible-items (filter-and-sort-forum-items items forum-search forum-sort)]
      (when (and room-id (not= @!booted-room room-id))
        (reset! !booted-room room-id)
        (re-frame/dispatch [:threads/fetch-list room-id false]))
      [:div.threads-forum-container
       [room-header {:display-name display-name
                     :active-id    room-id}]
       (if active-thread
         [:div.forum-detail-wrapper
          [:div.forum-detail-bar
           [:button.forum-back-btn
            {:type     "button"
             :on-click #(re-frame/dispatch [:threads/back-to-list room-id])}
            [icons/arrow-left {:width "16px" :height "16px"}]
            [:span (tr [:container.threads/all-posts])]]
           [:span.forum-detail-divider "/"]
           [:span.forum-detail-title
            (or (not-empty (:title active-thread))
                (tr [:container.threads/untitled]))]]
          [thread-conversation-view room-id active-thread tr {:forum-mode? true}]]

         [:div.forum-feed-wrapper
          [:div.forum-toolbar
           [:div.forum-search-box
            [icons/search {:width "15px" :height "15px"}]
            [:input.forum-search-input
             {:type        "text"
              :value       (or forum-search "")
              :placeholder (tr [:container.threads/filter-posts-placeholder])
              :on-change   #(re-frame/dispatch
                             [:threads/set-forum-search room-id (.. % -target -value)])}]
            (when-not (str/blank? (str forum-search ""))
              [:button.forum-search-clear
               {:type     "button"
                :on-click #(re-frame/dispatch [:threads/set-forum-search room-id ""])}
               [icons/exit {:width "13px" :height "13px"}]])]

           [:div.forum-toolbar-controls
            [:div.forum-segmented
             [:button.threads-filter-pill
              {:type     "button"
               :class    (when-not show-archived? "is-active")
               :on-click #(re-frame/dispatch [:threads/set-filter room-id false])}
              (tr [:container.threads/filter-active])]
             [:button.threads-filter-pill
              {:type     "button"
               :class    (when show-archived? "is-active")
               :on-click #(re-frame/dispatch [:threads/set-filter room-id true])}
              (tr [:container.threads/filter-all])]]

            [forum-sort-selector room-id forum-sort tr]

            [:button.forum-btn-primary
             {:type     "button"
              :on-click #(re-frame/dispatch [:threads/toggle-forum-composer room-id nil])}
             [icons/plus {:width "15px" :height "15px"}]
             [:span (tr [:container.threads/new-post])]]]]

          [:div.forum-posts-scroll
           [:div.forum-posts-inner
            (when composer-open?
              [forum-new-post-composer room-id room-state tr])

            (cond
              (and list-loading? (empty? items))
              [:div.threads-empty-state.is-forum-empty
               [:div.spinner]
               [:span (tr [:container.threads/loading])]]

              (empty? visible-items)
              [:div.threads-empty-state.is-forum-empty
               [icons/thread {:width "48px" :height "48px" :style {:opacity 0.3}}]
               [:div.threads-empty-title (tr [:container.threads/forum-empty-title])]
               [:div.threads-empty-hint (tr [:container.threads/forum-empty-hint])]]

              :else
              [:<>
               [:div.forum-posts-grid
                (for [entry visible-items]
                  ^{:key (or (:thread-id entry) (:root-event-id entry))}
                  [forum-post-card room-id entry tr])]
               (when has-more?
                 [:button.threads-load-more-btn
                  {:type     "button"
                   :disabled list-loading?
                   :on-click #(re-frame/dispatch [:threads/fetch-list room-id true])}
                  (tr [:container.threads/load-more])])])]]])])))