(ns paradise.ui.overlays.creation
  (:require
   [cljs-workers.mesh :as mesh]
   [cljs.core.async :refer [<! go]]
   [clojure.string :as str]
   [paradise.shared.utils.helpers :refer [ok-status?]]
   [paradise.shared.utils.svg :as icons]
   [paradise.ui.global :refer [avatar handle-list-navigation selectable-list]]
   [paradise.ui.overlays.base :refer [modal-component popover-component]]
   [re-frame.core :as re-frame]
   [reagent.core :as r]))

(defn ^:ui creation-select [{:keys [value options on-change direction]}]
  (r/with-let [!open? (r/atom false)]
    (let [open?        @!open?
          selected-val value
          upward?      (= direction :up)
          cur-lbl      (or (some #(when (= (:value %) selected-val) (:label %)) options)
                           (:label (first options))
                           "")]
      [:div.creation-select-dropdown
       [:button.creation-select-trigger
        {:type          "button"
         :class         (when open? "is-open")
         :aria-expanded open?
         :on-click      #(swap! !open? not)}
        [:span.creation-select-label cur-lbl]
        [:span.creation-select-chevron
         {:class (when open? "is-open")}
         [icons/chevron-down {:width "15px" :height "15px"}]]]
       (when open?
         [:<>
          [:div.creation-select-backdrop
           {:on-click #(reset! !open? false)}]
          (into
           [:div.creation-select-menu
            {:role  "listbox"
             :class (when upward? "is-upward")}]
           (mapv
            (fn [{opt-val :value opt-label :label}]
              (let [selected? (= opt-val selected-val)]
                ^{:key opt-val}
                [:button.creation-select-option
                 {:type          "button"
                  :role          "option"
                  :aria-selected selected?
                  :class         (when selected? "is-selected")
                  :on-click      (fn []
                                   (reset! !open? false)
                                   (on-change opt-val))}
                 [:span opt-label]
                 (when selected?
                   [icons/check {:width "14px" :height "14px"}])]))
            options))])])))

(defn- fetch-creation-options! [is-space? !opts !enc]
  (go
    (try
      (let [res (<! (mesh/do-with-thread!
                     :engine-pool
                     {:handler   :get-creation-options
                      :arguments {:is-space? (boolean is-space?)}}))]
        (when (and (ok-status? res)
                   (map? (:options res)))
          (reset! !opts (:options res))
          (when (contains? (:options res) :default-encrypted?)
            (reset! !enc (boolean (:default-encrypted? (:options res)))))))
      (catch :default _ nil))))

(defn- create-entity-content [{:keys [is-space? target-space-id]}]
  (r/with-let [entity-name        (r/atom "")
               room-type          (r/atom "chat")
               entity-topic       (r/atom "")
               visibility         (r/atom "Private")
               is-encrypted       (r/atom (not is-space?))
               history-visibility (r/atom "Shared")
               is-loading         (r/atom false)
               !creation-opts     (r/atom (cond-> {:show-visibility? true
                                                   :show-history?    true
                                                   :show-encryption? true}
                                            (not is-space?)
                                            (assoc :room-types [:chat :forum :voice])))
               _                  (fetch-creation-options! is-space? !creation-opts is-encrypted)]
    (let [tr        @(re-frame/subscribe [:i18n/tr])
          close-fn  #(re-frame/dispatch [:ui/close-modal])
          key-ns    (if is-space? "creation.space" "creation.room")
          k         #(keyword key-ns %)
          c-opts    @!creation-opts
          cur-name  @entity-name
          cur-type  @room-type
          cur-topic @entity-topic
          cur-vis   @visibility
          cur-enc   @is-encrypted
          cur-hist  @history-visibility
          loading?  @is-loading
          type-opts (when-not is-space?
                      (let [allowed-t (set (map name (or (:room-types c-opts) [:chat :forum :voice])))]
                        (filterv #(contains? allowed-t (:id %))
                                 [{:id   "chat"
                                   :icon icons/hash
                                   :name (tr [:creation.room/type-chat])}
                                  {:id   "forum"
                                   :icon icons/thread
                                   :name (tr [:creation.room/type-forum])}
                                  {:id   "voice"
                                   :icon icons/speaker
                                   :name (tr [:creation.room/type-voice])}])))
          vis-opts  [{:value "Private" :label (tr [:creation.visibility/private])}
                     {:value "Public"  :label (tr [:creation.visibility/public])}]
          hist-opts [{:value "Shared"        :label (tr [:creation.history/shared])}
                     {:value "Joined"        :label (tr [:creation.history/joined])}
                     {:value "Invited"       :label (tr [:creation.history/invited])}
                     {:value "WorldReadable" :label (tr [:creation.history/world])}]]
      [(if is-space? :div.create-space-container :div.create-room-container)
       [:div.creation-header
        [:h3.creation-title (tr [(k "modal-title")])]
        [:button.creation-close-btn
         {:type "button" :on-click close-fn}
         [icons/exit {:width "18px" :height "18px"}]]]

       (when (> (count type-opts) 1)
         [:div.form-group
          [:label (tr [:creation.room/type-label])]
          (into
           [:div.room-type-grid
            {:style {:grid-template-columns (str "repeat(" (count type-opts) ", 1fr)")}}]
           (mapv
            (fn [{:keys [id icon name]}]
              (let [active? (= cur-type id)]
                ^{:key id}
                [:button.room-type-option
                 {:type     "button"
                  :class    (when active? "is-active")
                  :on-click #(reset! room-type id)}
                 [icon {:width "16px" :height "16px"}]
                 [:span name]]))
            type-opts))])

       [:div.form-group
        [:label (tr [(k "name-label")])]
        [:input.form-input
         {:type        "text"
          :auto-focus  true
          :placeholder (tr [(k "name-placeholder")])
          :value       cur-name
          :on-change   #(reset! entity-name (.. % -target -value))}]]

       [:div.form-group
        [:label (tr [(k "topic-label")])]
        [:input.form-input
         {:type        "text"
          :placeholder (tr [(k "topic-placeholder")])
          :value       cur-topic
          :on-change   #(reset! entity-topic (.. % -target -value))}]]

       (when (:show-visibility? c-opts)
         [:div.form-group
          [:label (tr [(k "visibility-label")])]
          [creation-select
           {:value     cur-vis
            :options   vis-opts
            :on-change #(reset! visibility %)}]])

       (when (:show-history? c-opts)
         [:div.form-group
          [:label (tr [(k "history-label")])]
          [creation-select
           {:value     cur-hist
            :direction :up
            :options   hist-opts
            :on-change #(reset! history-visibility %)}]])

       (when (:show-encryption? c-opts)
         [:label.form-group.toggle-row
          [:span.toggle-row-text (tr [(k "encryption-label")])]
          [:span.custom-toggle
           [:input
            {:type      "checkbox"
             :checked   cur-enc
             :on-change #(swap! is-encrypted not)}]
           [:span.toggle-track
            [:span.toggle-knob]]]])

       [:div.modal-actions
        [:button.form-button.cancel-button
         {:type     "button"
          :on-click close-fn
          :disabled loading?}
         (tr [:creation.action/cancel])]
        [:button.form-button.create-button
         {:type     "button"
          :on-click (fn []
                      (when-not (str/blank? cur-name)
                        (reset! is-loading true)
                        (re-frame/dispatch
                         [:rooms/create-room
                          (cond-> {:name               cur-name
                                   :topic              cur-topic
                                   :visibility         cur-vis
                                   :is-encrypted       cur-enc
                                   :history-visibility cur-hist
                                   :is-space           (boolean is-space?)
                                   :parent-space-id    target-space-id
                                   :on-error           #(reset! is-loading false)}
                            (not is-space?)
                            (assoc :is-forum (= cur-type "forum")
                                   :is-call  (= cur-type "voice")))])))
          :disabled (or (str/blank? cur-name) loading?)}
         (if loading?
           (tr [:creation.action/creating])
           (tr [:creation.action/create]))]]])))

(defn ^:ui create-room-content [props]
  [create-entity-content (assoc props :is-space? false)])

(defn ^:ui create-space-content [props]
  [create-entity-content (assoc props :is-space? true)])


(defmethod modal-component :create-space [_]
  create-space-content)

(defmethod modal-component :create-room [_]
  create-room-content)
