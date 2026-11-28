(ns paradise.ui.overlays.emotes-manager
  (:require
   [cljs-workers.core :as main]
   [cljs.core.async :refer [<! go]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [clojure.string :as str]
   [paradise.media.component :refer [media]]
   [paradise.shared.client.state :as state]
   [paradise.shared.utils.emotes :as shared-emotes]
   [paradise.shared.utils.helpers :refer [click-element-by-id!
                                          file->base64-payload
                                          ok-status?
                                          revoke-object-url!
                                          safe-lower
                                          safe-lower-trim
                                          slugify-shortcode]]
   [paradise.shared.utils.svg :as icons]
   [paradise.ui.global :refer [avatar]]
   [paradise.ui.overlays.base :refer [feedback-banners]]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [taoensso.timbre :as log]))

(defn- scope-key [scope target-id]
  [(keyword (or scope :account)) (some-> target-id str not-empty)])

(re-frame/reg-sub
 :emotes/manager-state
 (fn [db [_ scope target-id]]
   (get-in db [:emoji/manager (scope-key scope target-id)]
           {:loading?     false
            :saving?      false
            :packs        []
            :servers      []
            :capabilities {:supports-packs?  true
                           :supports-usage?  true
                           :requires-server? false}
            :error        nil
            :status-msg   nil})))

(re-frame/reg-event-db
 :emotes/clear-feedback
 (fn [db [_ scope target-id]]
   (update-in db [:emoji/manager (scope-key scope target-id)]
              dissoc :error :status-msg)))

(re-frame/reg-event-db
 :emotes/load-scope-done
 (fn [db [_ scope target-id {:keys [packs servers capabilities error]}]]
   (let [sk (scope-key scope target-id)]
     (update-in db [:emoji/manager sk]
                (fn [prev]
                  (cond-> (assoc (or prev {}) :loading? false)
                    packs        (assoc :packs (shared-emotes/normalize-emote-packs
                                                packs
                                                {:keep-empty? true :sort-images? true}))
                    servers      (assoc :servers (vec servers))
                    capabilities (assoc :capabilities capabilities)
                    error        (assoc :error error)))))))

(re-frame/reg-event-fx
 :emotes/load-scope
 (fn [{:keys [db]} [_ scope target-id]]
   (let [sc-kw (keyword (or scope :account))
         tid   (some-> target-id str not-empty)
         sk    (scope-key sc-kw tid)]
     (when-let [pool @state/!engine-pool]
       (go
         (try
           (let [res (<! (main/do-with-pool!
                          pool
                          {:handler   :fetch-emotes
                           :arguments {:source-type (name sc-kw)
                                       :room-id     tid}}))]
             (if (ok-status? res)
               (do
                 (re-frame/dispatch [:sdk/save-all-emotes sc-kw tid (:packs res)])
                 (re-frame/dispatch
                  [:emotes/load-scope-done sc-kw tid
                   {:packs        (:packs res)
                    :servers      (:servers res)
                    :capabilities (:capabilities res)}]))
               (re-frame/dispatch
                [:emotes/load-scope-done sc-kw tid
                 {:error (or (:msg res) "Failed to load custom emojis")}])))
           (catch :default e
             (re-frame/dispatch
              [:emotes/load-scope-done sc-kw tid {:error (str e)}])))))
     {:db (assoc-in db [:emoji/manager sk :loading?] true)})))

(re-frame/reg-event-db
 :emotes/mutation-done
 (fn [db [_ scope target-id {:keys [error status-msg]}]]
   (let [sk (scope-key scope target-id)]
     (update-in db [:emoji/manager sk]
                (fn [prev]
                  (-> (or prev {})
                      (assoc :saving? false)
                      (assoc :error error :status-msg status-msg)))))))

(defn- run-emote-mutation!
  [db scope target-id handler build-args-fn
   {:keys [on-success status-msg-fn error-fallback clear-feedback? log-label]}]
  (let [sc-kw (keyword (or scope :account))
        tid   (some-> target-id str not-empty)
        sk    (scope-key sc-kw tid)]
    (when-let [pool @state/!engine-pool]
      (go
        (try
          (let [args (<! (build-args-fn sc-kw tid))
                res  (<! (main/do-with-pool! pool {:handler handler :arguments args}))]
            (if (ok-status? res)
              (do
                (when on-success (on-success (:pack-id res)))
                (re-frame/dispatch
                 [:emotes/mutation-done sc-kw tid
                  {:status-msg (when status-msg-fn (status-msg-fn res))}])
                (re-frame/dispatch [:emotes/load-scope sc-kw tid])
                (re-frame/dispatch [:sdk/fetch-all-emotes {:force? true}]))
              (re-frame/dispatch
               [:emotes/mutation-done sc-kw tid
                {:error (or (:msg res) error-fallback)}])))
          (catch :default e
            (when log-label
              (log/error log-label e))
            (re-frame/dispatch [:emotes/mutation-done sc-kw tid {:error (str e)}])))))
    {:db (cond-> (assoc-in db [:emoji/manager sk :saving?] true)
           clear-feedback?
           (update-in [:emoji/manager sk] dissoc :error :status-msg))}))

(re-frame/reg-event-fx
 :emotes/upsert
 (fn [{:keys [db]} [_ {:keys [scope target-id pack-id pack-name shortcode file usage on-success]}]]
   (run-emote-mutation!
    db scope target-id :upsert-emote
    (fn [sc-kw tid]
      (go
        (let [{:keys [buffer-b64 mime filename]} (when file (<p! (file->base64-payload file)))]
          {:scope      (name sc-kw)
           :room-id    tid
           :pack-id    pack-id
           :pack-name  pack-name
           :shortcode  shortcode
           :buffer-b64 buffer-b64
           :mime       mime
           :filename   filename
           :usage      (vec (or (seq usage) ["emoticon"]))})))
    {:on-success      on-success
     :status-msg-fn   (fn [res] (str ":" (:shortcode res shortcode) ": saved"))
     :error-fallback  "Failed to upload custom emoji"
     :clear-feedback? true
     :log-label       "Custom emoji upload exception:"})))

(re-frame/reg-event-fx
 :emotes/delete
 (fn [{:keys [db]} [_ {:keys [scope target-id pack-id shortcode emote-id]}]]
   (run-emote-mutation!
    db scope target-id :delete-emote
    (fn [sc-kw tid]
      (go {:scope     (name sc-kw)
           :room-id   tid
           :pack-id   pack-id
           :shortcode shortcode
           :emote-id  emote-id}))
    {:error-fallback "Failed to delete custom emoji"})))

(re-frame/reg-event-fx
 :emotes/save-pack-meta
 (fn [{:keys [db]} [_ {:keys [scope target-id pack-id pack-name delete-pack? on-success]}]]
   (run-emote-mutation!
    db scope target-id :save-emote-pack-meta
    (fn [sc-kw tid]
      (go {:scope        (name sc-kw)
           :room-id      tid
           :pack-id      pack-id
           :pack-name    pack-name
           :delete-pack? (boolean delete-pack?)}))
    {:on-success     on-success
     :error-fallback "Failed to update pack"})))

(defn- server-selector-group [{:keys [servers eff-srv-id !selected-srv tr]}]
  [:div.settings-hub-group
   [:div.settings-hub-group-label (tr [:settings.emotes/target-server])]
   [:div.emotes-mgr-pack-bar
    (for [srv servers]
      ^{:key (:id srv)}
      [:button.emotes-mgr-pack-pill
       {:type     "button"
        :class    (when (= (:id srv) eff-srv-id) "is-active")
        :on-click #(reset! !selected-srv (:id srv))}
       [avatar {:id (:id srv) :name (:name srv) :url (:avatar srv) :size 20}]
       [:span (:name srv)]])]])

(defn- pack-selector-group
  [{:keys [shown-packs active-pack active-pid eff-scope eff-target saving?
           !selected-pack !creating-pack !new-pack-name tr]}]
  [:div.settings-hub-group
   [:div.emotes-mgr-section-head
    [:div.settings-hub-group-label (tr [:settings.emotes/packs-label])]
    (when (and active-pack (> (count shown-packs) 1) (:editable? active-pack))
      [:button.emotes-mgr-pack-del-btn
       {:type     "button"
        :disabled saving?
        :on-click #(re-frame/dispatch
                    [:emotes/save-pack-meta
                     {:scope        eff-scope
                      :target-id    eff-target
                      :pack-id      active-pid
                      :delete-pack? true
                      :on-success   (fn [_] (reset! !selected-pack nil))}])}
       [icons/trash {:width "13px" :height "13px"}]
       [:span (tr [:settings.emotes/delete-pack])]])]
   [:div.emotes-mgr-pack-bar
    (for [p shown-packs]
      ^{:key (:id p)}
      [:button.emotes-mgr-pack-pill
       {:type     "button"
        :class    (when (= (:id p) active-pid) "is-active")
        :on-click #(reset! !selected-pack (:id p))}
       [:span (:name p)]
       [:span.emotes-mgr-pack-count (count (:images p))]])
    (if @!creating-pack
      (let [cancel-pack! (fn []
                           (reset! !creating-pack false)
                           (reset! !new-pack-name ""))
            submit-pack! (fn []
                           (let [nm (str/trim @!new-pack-name)]
                             (when-not (str/blank? nm)
                               (re-frame/dispatch
                                [:emotes/save-pack-meta
                                 {:scope      eff-scope
                                  :target-id  eff-target
                                  :pack-id    (slugify-shortcode nm)
                                  :pack-name  nm
                                  :on-success (fn [pid]
                                                (reset! !selected-pack pid)
                                                (cancel-pack!))}]))))]
        [:div.emotes-mgr-new-pack-inline
         [:input.form-input.emotes-mgr-new-pack-input
          {:type        "text"
           :auto-focus  true
           :placeholder (tr [:settings.emotes/pack-name-placeholder])
           :value       @!new-pack-name
           :on-change   #(reset! !new-pack-name (.. % -target -value))
           :on-key-down (fn [e]
                          (case (.-key e)
                            "Enter"  (submit-pack!)
                            "Escape" (cancel-pack!)
                            nil))}]
         [:button.settings-button.primary
          {:type     "button"
           :disabled (or saving? (str/blank? (str/trim @!new-pack-name)))
           :on-click submit-pack!}
          (tr [:settings.emotes/create-pack])]
         [:button.settings-button.secondary
          {:type     "button"
           :on-click cancel-pack!}
          (tr [:composer.polls/cancel])]])
      [:button.emotes-mgr-pack-pill.is-add
       {:type     "button"
        :on-click #(reset! !creating-pack true)}
       [icons/plus {:width "13px" :height "13px"}]
       [:span (tr [:settings.emotes/new-pack])]])]])

(defn- emote-upload-card
  [{:keys [input-id !dragging? !preview-url !file !shortcode !usage-mode !selected-pack
           select-file! clear-file! eff-scope eff-target active-pid active-pack
           can-usage? can-upload? clean-sc usage-vec saving? error status-msg tr]}]
  [:div.settings-hub-group
   [:div.settings-hub-group-label (tr [:settings.emotes/add-heading])]
   [:div.settings-hub-group-card
    [:div.emotes-mgr-upload-card
     [:input
      {:id        input-id
       :type      "file"
       :accept    "image/png,image/gif,image/webp,image/jpeg"
       :style     {:display "none"}
       :on-change (fn [^js e]
                    (when-let [f (some-> e .-target .-files (aget 0))]
                      (select-file! f))
                    (when-let [t (.-target e)]
                      (set! (.-value t) "")))}]
     [:div.emotes-mgr-dropzone
      {:class        (cond
                       @!dragging?   "is-dragging"
                       @!preview-url "has-preview"
                       :else         nil)
       :on-click     #(click-element-by-id! input-id)
       :on-drag-over (fn [e]
                       (.preventDefault e)
                       (reset! !dragging? true))
       :on-drag-leave #(reset! !dragging? false)
       :on-drop      (fn [^js e]
                       (.preventDefault e)
                       (reset! !dragging? false)
                       (when-let [f (some-> e .-dataTransfer .-files (aget 0))]
                         (select-file! f)))}
      (if @!preview-url
        [:img.emotes-mgr-preview-img {:src @!preview-url :alt "preview"}]
        [:div.emotes-mgr-drop-placeholder
         [icons/smiley {:width "24px" :height "24px"}]
         [:span (tr [:settings.emotes/choose-image])]])]
     [:div.emotes-mgr-upload-fields
      [:div.emotes-mgr-field-row
       [:div.emotes-mgr-shortcode-wrap
        [:span.emotes-mgr-colon ":"]
        [:input.emotes-mgr-shortcode-input
         {:type        "text"
          :placeholder (tr [:settings.emotes/shortcode-placeholder])
          :value       @!shortcode
          :on-change   (fn [e]
                         (re-frame/dispatch [:emotes/clear-feedback eff-scope eff-target])
                         (reset! !shortcode (.. e -target -value)))}]
        [:span.emotes-mgr-colon ":"]]
       (when can-usage?
         [:div.emotes-mgr-usage-seg
          (for [[mode-kw lbl-kw] [[:emoji   :settings.emotes/usage-emoji]
                                  [:sticker :settings.emotes/usage-sticker]
                                  [:both    :settings.emotes/usage-both]]]
            ^{:key mode-kw}
            [:button.emotes-mgr-usage-btn
             {:type     "button"
              :class    (when (= @!usage-mode mode-kw) "is-active")
              :on-click #(reset! !usage-mode mode-kw)}
             (tr [lbl-kw])])])]
      [:div.emotes-mgr-upload-actions
       [:span.emotes-mgr-hint (tr [:settings.emotes/upload-hint])]
       [:div.emotes-mgr-action-btns
        (when @!file
          [:button.settings-button.secondary
           {:type     "button"
            :disabled saving?
            :on-click #(do (clear-file!) (reset! !shortcode ""))}
           (tr [:settings.profile/reset])])
        [:button.settings-button.primary
         {:type     "button"
          :disabled (not can-upload?)
          :on-click (fn []
                      (when can-upload?
                        (re-frame/dispatch
                         [:emotes/upsert
                          {:scope      eff-scope
                           :target-id  eff-target
                           :pack-id    active-pid
                           :pack-name  (:name active-pack)
                           :shortcode  clean-sc
                           :file       @!file
                           :usage      usage-vec
                           :on-success (fn [pid]
                                         (when pid (reset! !selected-pack pid))
                                         (clear-file!)
                                         (reset! !shortcode ""))}])))}
         (if saving?
           (tr [:settings.emotes/uploading])
           (tr [:settings.emotes/upload-btn]))]]]]]
    [feedback-banners {:error error :success status-msg}]]])

(defn- emote-collection-group
  [{:keys [active-pack active-pid all-images filtered q !filter-q
           loading? saving? eff-scope eff-target tr]}]
  [:div.settings-hub-group
   [:div.emotes-mgr-section-head
    [:div.settings-hub-group-label
     (str (or (:name active-pack) (tr [:settings.emotes/collection-heading]))
          " (" (count all-images) ")")]
    (when (> (count all-images) 4)
      [:div.emotes-mgr-filter-box
       [icons/search {:width "14px" :height "14px"}]
       [:input.emotes-mgr-filter-input
        {:type        "text"
         :placeholder (tr [:settings.emotes/filter-placeholder])
         :value       @!filter-q
         :on-change   #(reset! !filter-q (.. % -target -value))}]])]
   [:div.settings-hub-group-card
    (cond
      (and loading? (empty? all-images))
      [:div.emotes-mgr-empty (tr [:container.timeline/loading])]

      (empty? filtered)
      [:div.emotes-mgr-empty
       (if (str/blank? q)
         (tr [:settings.emotes/empty-pack])
         (tr [:composer.emotes/no-results]))]

      :else
      [:div.emotes-mgr-grid
       (for [{:keys [shortcode uri emote-id is-sticker]} filtered]
         ^{:key (str active-pid ":" shortcode)}
         [:div.emotes-mgr-item
          [:div.emotes-mgr-thumb-wrap
           [media {:uri   uri
                   :class "emotes-mgr-thumb"
                   :alt   shortcode
                   :title (str ":" shortcode ":")}]]
          [:div.emotes-mgr-item-meta
           [:span.emotes-mgr-item-code (str ":" shortcode ":")]
           (when is-sticker
             [:span.emotes-mgr-sticker-tag (tr [:composer.emotes/mode-sticker])])]
          (when (:editable? active-pack true)
            [:button.emotes-mgr-del-btn
             {:type     "button"
              :disabled saving?
              :title    (tr [:settings.emotes/delete-tooltip])
              :on-click #(re-frame/dispatch
                          [:emotes/delete
                           {:scope     eff-scope
                            :target-id eff-target
                            :pack-id   active-pid
                            :shortcode shortcode
                            :emote-id  emote-id}])}
             [icons/trash {:width "14px" :height "14px"}]])])])]])

(defn ^:ui custom-emotes-manager [{:keys [scope target-id]}]
  (r/with-let [!loaded-key    (atom nil)
               !selected-srv  (r/atom nil)
               !selected-pack (r/atom nil)
               !creating-pack (r/atom false)
               !new-pack-name (r/atom "")
               !file          (r/atom nil)
               !preview-url   (r/atom nil)
               !shortcode     (r/atom "")
               !usage-mode    (r/atom :emoji)
               !filter-q      (r/atom "")
               !dragging?     (r/atom false)
               input-id       (str "paradise-emote-file-" (random-uuid))
               clear-file!    (fn []
                                (revoke-object-url! @!preview-url)
                                (reset! !file nil)
                                (reset! !preview-url nil))
               select-file!   (fn [^js f]
                                (when f
                                  (clear-file!)
                                  (reset! !file f)
                                  (reset! !preview-url (js/URL.createObjectURL f))
                                  (when (str/blank? @!shortcode)
                                    (reset! !shortcode (slugify-shortcode (.-name f))))))]
    (let [tr          @(re-frame/subscribe [:i18n/tr])
          base-scope  (keyword (or scope :account))
          base-state  @(re-frame/subscribe [:emotes/manager-state base-scope target-id])
          base-caps   (:capabilities base-state)
          req-srv?    (and (= base-scope :account) (:requires-server? base-caps))
          servers     (:servers base-state)
          eff-srv-id  (when req-srv?
                        (or @!selected-srv (some-> (first servers) :id)))
          eff-scope   (if (and req-srv? eff-srv-id) :space base-scope)
          eff-target  (if (and req-srv? eff-srv-id) eff-srv-id target-id)
          cur-key     [base-scope target-id eff-scope eff-target]
          _           (when (not= @!loaded-key cur-key)
                        (reset! !loaded-key cur-key)
                        (re-frame/dispatch [:emotes/load-scope base-scope target-id])
                        (when (and req-srv? eff-srv-id)
                          (re-frame/dispatch [:emotes/load-scope :space eff-srv-id])))
          mgr-state   @(re-frame/subscribe [:emotes/manager-state eff-scope eff-target])
          {:keys [loading? saving? packs capabilities error status-msg]} mgr-state
          can-packs?  (and (:supports-packs? capabilities) (not req-srv?))
          can-usage?  (:supports-usage? capabilities)
          edit-packs  (filterv :editable? packs)
          shown-packs (if (seq edit-packs) edit-packs packs)
          active-pack (or (some #(when (= (:id %) @!selected-pack) %) shown-packs)
                          (first shown-packs))
          active-pid  (:id active-pack)
          all-images  (if active-pack (:images active-pack) [])
          q           (safe-lower-trim @!filter-q)
          filtered    (if (str/blank? q)
                        all-images
                        (filterv #(str/includes? (safe-lower (:shortcode %)) q) all-images))
          clean-sc    (slugify-shortcode @!shortcode)
          can-upload? (and (not saving?)
                           (some? @!file)
                           (not (str/blank? clean-sc))
                           (or (not req-srv?) (some? eff-srv-id)))
          usage-vec   (case @!usage-mode
                        :sticker ["sticker"]
                        :both    ["emoticon" "sticker"]
                        ["emoticon"])]
      [:div.emotes-mgr-root
       (when (and req-srv? (empty? servers) (not loading?))
         [:div.settings-hub-group
          [:div.settings-hub-group-card
           [:div.emotes-mgr-empty
            (tr [:settings.emotes/no-servers])]]])
       (when (and req-srv? (seq servers))
         [server-selector-group {:servers       servers
                                 :eff-srv-id    eff-srv-id
                                 :!selected-srv !selected-srv
                                 :tr            tr}])
       (when can-packs?
         [pack-selector-group {:shown-packs    shown-packs
                               :active-pack    active-pack
                               :active-pid     active-pid
                               :eff-scope      eff-scope
                               :eff-target     eff-target
                               :saving?        saving?
                               :!selected-pack !selected-pack
                               :!creating-pack !creating-pack
                               :!new-pack-name !new-pack-name
                               :tr             tr}])
       [emote-upload-card {:input-id       input-id
                           :!dragging?     !dragging?
                           :!preview-url   !preview-url
                           :!file          !file
                           :!shortcode     !shortcode
                           :!usage-mode    !usage-mode
                           :!selected-pack !selected-pack
                           :select-file!   select-file!
                           :clear-file!    clear-file!
                           :eff-scope      eff-scope
                           :eff-target     eff-target
                           :active-pid     active-pid
                           :active-pack    active-pack
                           :can-usage?     can-usage?
                           :can-upload?    can-upload?
                           :clean-sc       clean-sc
                           :usage-vec      usage-vec
                           :saving?        saving?
                           :error          error
                           :status-msg     status-msg
                           :tr             tr}]
       [emote-collection-group {:active-pack active-pack
                                :active-pid  active-pid
                                :all-images  all-images
                                :filtered    filtered
                                :q           q
                                :!filter-q   !filter-q
                                :loading?    loading?
                                :saving?     saving?
                                :eff-scope   eff-scope
                                :eff-target  eff-target
                                :tr          tr}]])
    (finally
      (clear-file!))))

(defn ^:ui user-emotes-settings-tab []
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:div.settings-tab-content
     [:p.settings-subview-lead
      (tr [:settings.emotes/description])]
     [custom-emotes-manager {:scope :account :target-id nil}]]))