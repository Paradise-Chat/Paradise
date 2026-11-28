(ns paradise.ui.overlays.permissions-roles
  (:require
   [cljs-workers.mesh :as mesh]
   [cljs.core.async :refer [<! go]]
   [clojure.string :as str]
   [paradise.shared.utils.helpers :refer [decode-edn-field
                                          normalize-keyword
                                          ok-status?
                                          resolve-item-desc
                                          resolve-item-label
                                          safe-lower
                                          safe-lower-trim]]
   [paradise.shared.utils.svg :as icons]
   [paradise.ui.global :refer [avatar]]
   [paradise.ui.overlays.base :refer [feedback-banners modal-component]]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [taoensso.timbre :as log]))

(def role-color-palette
  ["#5865f2" "#818cf8" "#06b6d4" "#10b981" "#84cc16"
   "#f59e0b" "#f97316" "#ef4444" "#ec4899" "#a855f7"
   "#14b8a6" "#949ba4"])

(def ^:private resolve-label resolve-item-label)
(def ^:private resolve-desc resolve-item-desc)

(defn- resolve-panel-icon [icon-kw & [props]]
  (icons/resolve-icon icon-kw {:props (or props {:width "14px" :height "14px"})}))

(defn- role-rank [role]
  (int (or (:rank role) (:power-level role) 0)))

(defn- member-rank [member]
  (int (or (:rank member) (:power-level member) 0)))

(defn- caller-rank [data]
  (int (or (:my-rank data) (:my-power-level data) 0)))

(defn- multi-role-assignment? [data]
  (let [rp (:roles-panel data {})
        mp (:moderation-panel data {})]
    (cond
      (contains? mp :multi-role-assignment?) (boolean (:multi-role-assignment? mp))
      (contains? rp :multi-role-assignment?) (boolean (:multi-role-assignment? rp))
      (= (keyword (:role-assign-mode mp)) :multi-role) true
      :else false)))

(defn- active-member? [member]
  (let [m (some-> (:membership member) name safe-lower)]
    (not (contains? #{"left" "leave" "banned" "ban"} m))))

(defn- can-moderate-member? [data member self?]
  (let [multi?     (multi-role-assignment? data)
        m-rank     (member-rank member)
        my-rk      (caller-rank data)
        eq-ok?     (boolean (:can-act-on-equal-rank? data))]
    (and (not self?)
         (not (:is-owner? member))
         (if (contains? member :can-moderate?)
           (boolean (:can-moderate? member))
           (or multi?
               (< m-rank my-rk)
               (and eq-ok? (<= m-rank my-rk)))))))

(defn- resolve-rank-badge [rank role-tags roles]
  (let [tag  (get role-tags (keyword (str rank)))
        role (when-not (:name tag)
               (some #(when (= (role-rank %) rank) %) roles))]
    (cond
      (:name tag)  {:name (:name tag) :color (:color tag)}
      (:name role) {:name (:name role) :color (:color role)}
      :else        {:name nil :color "#949ba4"})))

(defn- normalize-perms-payload [res]
  (let [decoded-panels (decode-edn-field res :panels-edn)]
    (cond-> (dissoc res :panels-edn :status :msg)
      (map? decoded-panels) (merge decoded-panels))))

(re-frame/reg-sub
 :room-perms/state
 (fn [db [_ room-id]]
   (get-in db [:room-perms/by-id room-id]
           {:loading?      false
            :saving?       false
            :bans-loading? false
            :data          nil
            :bans          []
            :error         nil
            :saved?        false})))

(re-frame/reg-sub
 :room-perms/data
 (fn [db [_ room-id]]
   (get-in db [:room-perms/by-id room-id :data])))

(re-frame/reg-event-db
 :room-perms/clear-feedback
 (fn [db [_ room-id]]
   (update-in db [:room-perms/by-id room-id] dissoc :error :saved?)))

(re-frame/reg-event-db
 :room-perms/fetch-done
 (fn [db [_ room-id {:keys [data error]}]]
   (update-in db [:room-perms/by-id room-id]
              (fn [prev]
                (cond-> (assoc (or prev {}) :loading? false)
                  data  (assoc :data data)
                  error (assoc :error error))))))

(re-frame/reg-event-fx
 :room-perms/fetch
 (fn [{:keys [db]} [_ room-id parent-space-id]]
   (when room-id
     (let [args (cond-> {:room-id room-id}
                  parent-space-id (assoc :parent-space-id parent-space-id))]
       (go
         (try
           (let [res (<! (mesh/do-with-thread!
                          :engine-pool
                          {:handler   :get-permissions-panel
                           :arguments args}))]
             (if (ok-status? res)
               (re-frame/dispatch [:room-perms/fetch-done room-id
                                   {:data (normalize-perms-payload res)}])
               (re-frame/dispatch [:room-perms/fetch-done room-id
                                   {:error (or (:msg res) "Failed to load permissions")}])))
           (catch :default e
             (re-frame/dispatch [:room-perms/fetch-done room-id {:error (str e)}]))))))
   {:db (assoc-in db [:room-perms/by-id room-id :loading?] true)}))

(re-frame/reg-event-db
 :room-perms/fetch-bans-done
 (fn [db [_ room-id {:keys [bans error]}]]
   (update-in db [:room-perms/by-id room-id]
              (fn [prev]
                (cond-> (assoc (or prev {}) :bans-loading? false)
                  bans  (assoc :bans (vec bans))
                  error (assoc :error error))))))

(re-frame/reg-event-fx
 :room-perms/fetch-bans
 (fn [{:keys [db]} [_ room-id]]
   (when room-id
     (go
       (try
         (let [res (<! (mesh/do-with-thread!
                        :engine-pool
                        {:handler   :fetch-bans
                         :arguments {:room-id room-id}}))]
           (if (ok-status? res)
             (re-frame/dispatch [:room-perms/fetch-bans-done room-id
                                 {:bans (:bans res [])}])
             (re-frame/dispatch [:room-perms/fetch-bans-done room-id
                                 {:error (:msg res)}])))
         (catch :default e
           (re-frame/dispatch [:room-perms/fetch-bans-done room-id {:error (str e)}])))))
   {:db (assoc-in db [:room-perms/by-id room-id :bans-loading?] true)}))

(re-frame/reg-event-fx
 :room-perms/mutation-done
 (fn [{:keys [db]} [_ room-id {:keys [data error refresh-members? refresh-bans?]}]]
   {:db (update-in db [:room-perms/by-id room-id]
                   (fn [prev]
                     (cond-> (assoc (or prev {})
                                    :saving? false
                                    :saved?  (nil? error)
                                    :error   error)
                       data (assoc :data data))))
    :fx (cond-> [[:dispatch [:room/fetch-power-level-tags room-id]]]
          refresh-members? (conj [:dispatch [:room/fetch-members room-id]])
          refresh-bans?    (conj [:dispatch [:room-perms/fetch-bans room-id]]))}))

(defn- run-perms-mutation! [room-id handler args {:keys [on-done refresh-members? refresh-bans?]}]
  (when room-id
    (go
      (try
        (let [res (<! (mesh/do-with-thread!
                       :engine-pool
                       {:handler   handler
                        :arguments (assoc args :room-id room-id)}))]
          (if (ok-status? res)
            (do
              (when on-done (on-done res))
              (re-frame/dispatch
               [:room-perms/mutation-done room-id
                {:data             (when (:roles res) (normalize-perms-payload res))
                 :refresh-members? refresh-members?
                 :refresh-bans?    refresh-bans?}]))
            (re-frame/dispatch
             [:room-perms/mutation-done room-id
              {:error (or (:msg res) "Operation failed")}])))
        (catch :default e
          (log/error "Permission/Role mutation failed:" handler e)
          (re-frame/dispatch [:room-perms/mutation-done room-id {:error (str e)}]))))))

(defn- mark-perms-saving
  ([db room-id]
   (mark-perms-saving db room-id false))
  ([db room-id clear-feedback?]
   {:db (cond-> (assoc-in db [:room-perms/by-id room-id :saving?] true)
          clear-feedback?
          (update-in [:room-perms/by-id room-id] dissoc :error :saved?))}))

(re-frame/reg-event-fx
 :room-perms/upsert-role
 (fn [{:keys [db]} [_ {:keys [room-id role on-done]}]]
   (run-perms-mutation! room-id :upsert-role {:role role}
                        {:on-done on-done :refresh-members? true})
   (mark-perms-saving db room-id true)))

(re-frame/reg-event-fx
 :room-perms/delete-role
 (fn [{:keys [db]} [_ {:keys [room-id role-id on-done]}]]
   (run-perms-mutation! room-id :delete-role {:role-id role-id}
                        {:on-done on-done :refresh-members? true})
   (mark-perms-saving db room-id true)))

(re-frame/reg-event-fx
 :room-perms/reorder-roles
 (fn [{:keys [db]} [_ {:keys [room-id ordered-ids]}]]
   (run-perms-mutation! room-id :reorder-roles {:ordered-ids ordered-ids}
                        {:refresh-members? true})
   (mark-perms-saving db room-id)))

(re-frame/reg-event-fx
 :room-perms/update-permissions
 (fn [{:keys [db]} [_ {:keys [room-id on-done] :as payload}]]
   (run-perms-mutation! room-id :update-room-permissions
                        (dissoc payload :room-id :on-done)
                        {:on-done on-done})
   (mark-perms-saving db room-id true)))

(re-frame/reg-event-fx
 :room-perms/set-member-roles
 (fn [{:keys [db]} [_ {:keys [room-id user-id role-ids rank power-level on-done]}]]
   (let [eff-rank (or rank power-level)]
     (run-perms-mutation! room-id :set-member-roles
                          (cond-> {:user-id  user-id
                                   :role-ids role-ids}
                            (some? eff-rank) (assoc :rank eff-rank
                                                    :power-level eff-rank))
                          {:on-done on-done :refresh-members? true}))
   (mark-perms-saving db room-id)))

(re-frame/reg-event-fx
 :room-perms/kick-member
 (fn [{:keys [db]} [_ {:keys [room-id user-id reason on-done]}]]
   (run-perms-mutation! room-id :kick-member
                        {:user-id user-id :reason reason}
                        {:on-done on-done :refresh-members? true})
   (mark-perms-saving db room-id)))

(re-frame/reg-event-fx
 :room-perms/ban-member
 (fn [{:keys [db]} [_ {:keys [room-id user-id reason delete-days on-done]}]]
   (run-perms-mutation! room-id :ban-member
                        {:user-id     user-id
                         :reason      reason
                         :delete-days delete-days}
                        {:on-done          on-done
                         :refresh-members? true
                         :refresh-bans?    true})
   (mark-perms-saving db room-id)))

(re-frame/reg-event-fx
 :room-perms/unban-member
 (fn [{:keys [db]} [_ {:keys [room-id user-id on-done]}]]
   (run-perms-mutation! room-id :unban-member
                        {:user-id user-id}
                        {:on-done on-done :refresh-bans? true})
   (mark-perms-saving db room-id)))

(defn- perms-feedback-banners [tr error saved?]
  [feedback-banners {:error   error
                     :success (when saved? (tr [:room-settings/saved]))
                     :style   {:margin "0 0 12px 0"}}])

(defn- color-swatch-picker [{:keys [tr value disabled? on-change]}]
  (let [cur (when (some? value) (safe-lower value))]
    [:div.perms-color-picker-row
     (for [sw role-color-palette
           :let [active? (= cur (safe-lower sw))]]
       ^{:key sw}
       [:button.perms-color-swatch
        {:type     "button"
         :disabled disabled?
         :class    (when active? "is-active")
         :style    {:background sw}
         :title    sw
         :on-click #(on-change sw)}
        (when active?
          [icons/check {:width "12px" :height "12px"}])])
     [:label.perms-color-custom
      {:title (tr [:room-settings.roles/color-custom-title])}
      [:input
       {:type      "color"
        :disabled  disabled?
        :value     (if (and cur (re-matches #"#[0-9a-f]{6}" cur)) cur "#5865f2")
        :on-change #(on-change (.. % -target -value))}]
      [:span.perms-color-hex
       (or (not-empty value)
           (tr [:room-settings.roles/color-default]))]]
     (when (not (str/blank? (str value)))
       [:button.settings-button.secondary
        {:type     "button"
         :disabled disabled?
         :style    {:height "28px" :padding "0 10px" :font-size "0.75rem"}
         :on-click #(on-change nil)}
        (tr [:room-settings.roles/color-clear])])]))

(defn- toggle-switch [{:keys [checked? disabled? danger? on-change]}]
  [:span.custom-toggle
   {:class (when disabled? "is-disabled")}
   [:input
    {:type      "checkbox"
     :checked   (boolean checked?)
     :disabled  disabled?
     :on-change #(when-not disabled? (on-change (not checked?)))}]
   [:span.toggle-track
    {:class (when (and checked? danger?) "is-danger")}
    [:span.toggle-knob]]])

(defn- ban-history-select [{:keys [tr options value on-change long-labels? style class]}]
  (when (seq options)
    [:select.settings-input
     (cond-> {:value     (str value)
              :on-change #(on-change (js/parseInt (.. % -target -value) 10))}
       class (assoc :class class)
       style (assoc :style style))
     (for [opt options
           :let [opt-kw (normalize-keyword
                         (if long-labels?
                           (or (:long-label-key opt) (:label-key opt))
                           (:label-key opt)))]]
       ^{:key (:value opt)}
       [:option {:value (str (:value opt))}
        (if opt-kw (tr [opt-kw]) (resolve-label tr opt))])]))

(defn- dispatch-moderation-action!
  [{:keys [ban? room-id user-id reason delete-days on-done]}]
  (if ban?
    (re-frame/dispatch
     [:room-perms/ban-member
      {:room-id     room-id
       :user-id     user-id
       :reason      reason
       :delete-days delete-days
       :on-done     on-done}])
    (re-frame/dispatch
     [:room-perms/kick-member
      {:room-id room-id
       :user-id user-id
       :reason  reason
       :on-done on-done}])))

(defn- count-members-with-role [members multi-role? role]
  (if multi-role?
    (if (:everyone? role)
      (count members)
      (let [rid (str (:id role))]
        (count (filter #(some #{rid} (map str (:role-ids %))) members))))
    (let [rk (role-rank role)]
      (count (filter #(= (member-rank %) rk) members)))))

(defn- role-hierarchy-rail
  [{:keys [tr room-id roles movable members multi-role? rank-cfg can-edit?
           can-reorder? saving? creating? active-id draft-name draft-color
           on-start-create on-select on-move]}]
  [:div.perms-rail
   [:div.perms-rail-header
    [:span.perms-rail-title
     (str (tr [:room-settings.tabs/roles]) " (" (count roles) ")")]
    (when can-edit?
      [:button.settings-button.primary
       {:type     "button"
        :disabled saving?
        :style    {:height "30px" :padding "0 10px" :font-size "0.78rem"}
        :on-click on-start-create}
       [icons/plus {:width "13px" :height "13px"}]
       [:span (tr [:room-settings.roles/new-role])]])]

   [:div.perms-rail-list
    (when creating?
      [:button.perms-rail-item.is-active
       {:type "button"}
       [:span.room-settings-role-dot {:style {:background (or draft-color "#818cf8")}}]
       [:span.perms-rail-item-name
        (or (not-empty draft-name) (tr [:room-settings.roles/new-role]))]
       [:span.emotes-mgr-pack-count (tr [:room-settings.roles/draft])]])

    (for [[idx r] (map-indexed vector roles)
          :let [rid     (str (:id r))
                active? (and (not creating?) (= rid (str active-id)))
                cnt     (count-members-with-role members multi-role? r)
                can-up? (and can-reorder? can-edit? (not (:everyone? r)) (pos? idx))
                can-dn? (and can-reorder? can-edit? (not (:everyone? r))
                             (< idx (dec (count movable))))]]
      ^{:key rid}
      [:div.perms-rail-item
       {:class    (when active? "is-active")
        :on-click #(on-select rid)}
       [:span.room-settings-role-dot
        {:style {:background (or (:color r) "#949ba4")}}]
       [:div.perms-rail-item-copy
        [:span.perms-rail-item-name (:name r)]
        (when (:editable? rank-cfg)
          [:span.perms-rail-item-sub
           (tr [:room-settings.perms/power-prefix] [(role-rank r)])])]
       [:span.emotes-mgr-pack-count cnt]
       (when (or can-up? can-dn?)
         [:div.perms-order-btns
          {:on-click #(.stopPropagation %)}
          [:button.perms-order-btn
           {:type     "button"
            :disabled (or saving? (not can-up?))
            :title    (tr [:room-settings.roles/move-higher])
            :on-click #(on-move rid -1)}
           [icons/chevron-up {:width "12px" :height "12px"}]]
          [:button.perms-order-btn
           {:type     "button"
            :disabled (or saving? (not can-dn?))
            :title    (tr [:room-settings.roles/move-lower])
            :on-click #(on-move rid 1)}
           [icons/chevron-down {:width "12px" :height "12px"}]]])])]])

(defn- role-identity-card
  [{:keys [tr room-id name-val color-val rank-val attr-vals attr-toggles
           rank-cfg everyone? saving? lock-meta? lock-rank?
           on-name on-color on-rank on-attr]}]
  (let [min-rk  (int (or (:min rank-cfg) 0))
        max-rk  (int (or (:max rank-cfg) 100))
        presets (vec (or (:presets rank-cfg) []))]
    [:div.settings-hub-group
     [:div.settings-hub-group-label
      (tr [:room-settings.roles/display-heading])]
     [:div.settings-hub-group-card
      [:div.room-settings-form-body
       [:div.settings-field
        [:label.settings-label
         (tr [:room-settings.roles/name-label])]
        [:input.settings-input
         {:type      "text"
          :disabled  (or saving? lock-meta?)
          :value     name-val
          :on-change (fn [e]
                       (re-frame/dispatch [:room-perms/clear-feedback room-id])
                       (on-name (.. e -target -value)))}]]

       (when-not everyone?
         [:div.settings-field
          [:label.settings-label
           (tr [:room-settings.roles/color-label])]
          [color-swatch-picker
           {:tr        tr
            :value     color-val
            :disabled? (or saving? lock-meta?)
            :on-change (fn [c]
                         (re-frame/dispatch [:room-perms/clear-feedback room-id])
                         (on-color c))}]])

       (when-not everyone?
         (for [{:keys [key] :as toggle-def} attr-toggles]
           ^{:key key}
           [:div.perms-toggle-row
            [:div.perms-toggle-copy
             [:span.perms-toggle-title (resolve-label tr toggle-def)]
             [:span.perms-toggle-desc (resolve-desc tr toggle-def)]]
            [toggle-switch
             {:checked?  (boolean (get attr-vals key))
              :disabled? (or saving? lock-meta?)
              :on-change #(on-attr key %)}]]))

       (when (:editable? rank-cfg)
         [:div.settings-field
          [:label.settings-label
           (tr [(or (normalize-keyword (:label-key rank-cfg))
                    :room-settings.roles/power-level-label)])]
          [:div.perms-pl-input-row
           [:input.settings-input
            {:type      "number"
             :min       min-rk
             :max       max-rk
             :disabled  lock-rank?
             :style     {:width "110px"}
             :value     rank-val
             :on-change #(let [n (js/parseInt (.. % -target -value) 10)]
                           (when-not (js/isNaN n)
                             (on-rank (max min-rk (min max-rk n)))))}]
           (when (seq presets)
             [:div.perms-preset-pills
              (for [preset presets
                    :let [lvl (:value preset)
                          lbl (resolve-label tr preset)]]
                ^{:key lvl}
                [:button.perms-preset-pill
                 {:type     "button"
                  :disabled lock-rank?
                  :class    (when (= (int rank-val) lvl) "is-active")
                  :on-click #(on-rank lvl)}
                 lbl])])]])]]]))

(defn- role-permissions-toggles-card
  [{:keys [tr perm-groups perm-filter perms can-edit? managed? saving?
           on-filter on-toggle]}]
  (let [pq (safe-lower-trim perm-filter)]
    (when (seq perm-groups)
      [:div.settings-hub-group
       [:div.emotes-mgr-section-head
        [:div.settings-hub-group-label
         (tr [:room-settings.roles/perms-heading])]
        [:div.emotes-mgr-filter-box
         [icons/search {:width "14px" :height "14px"}]
         [:input.emotes-mgr-filter-input
          {:type        "text"
           :placeholder (tr [:room-settings.perms/filter-placeholder])
           :value       perm-filter
           :on-change   #(on-filter (.. % -target -value))}]]]

       (for [{:keys [id items] :as grp} perm-groups
             :let [vis-items (filterv
                              (fn [it]
                                (or (str/blank? pq)
                                    (str/includes? (safe-lower (resolve-label tr it)) pq)
                                    (str/includes? (safe-lower (resolve-desc tr it)) pq)))
                              items)]
             :when (seq vis-items)]
         ^{:key id}
         [:div.settings-hub-group-card {:style {:margin-bottom "12px"}}
          [:div.perms-group-card-header (resolve-label tr grp)]
          (for [{:keys [key danger?] :as it} vis-items
                :let [on? (boolean (get perms key))]]
            ^{:key key}
            [:div.perms-toggle-row
             [:div.perms-toggle-copy
              [:span.perms-toggle-title
               {:class (when danger? "is-danger")}
               (resolve-label tr it)]
              [:span.perms-toggle-desc (resolve-desc tr it)]]
             [toggle-switch
              {:checked?  on?
               :danger?   danger?
               :disabled? (or saving? (not can-edit?) managed?)
               :on-change #(on-toggle key %)}]])])])))

(defn- role-capability-preview-card [{:keys [tr cap-preview current-rank]}]
  (when (seq (:items cap-preview))
    [:div.settings-hub-group
     [:div.emotes-mgr-section-head
      [:div.settings-hub-group-label
       (tr [(or (normalize-keyword (:heading-key cap-preview))
                :room-settings.roles/unlocked-heading)])]
      (when-let [act-tab (:action-tab cap-preview)]
        [:button.settings-button.secondary
         {:type     "button"
          :style    {:height "28px" :padding "0 10px" :font-size "0.76rem"}
          :on-click #(re-frame/dispatch [:room-settings/set-tab (keyword act-tab)])}
         (tr [(or (normalize-keyword (:action-label-key cap-preview))
                  :room-settings.roles/configure-thresholds)])])]
     [:div.settings-hub-group-card
      [:div.perms-capability-chips
       (for [item (:items cap-preview)
             :let [req (int (or (:required-rank item) (:required-level item) 0))
                   ok? (>= (int current-rank) req)]]
         ^{:key (:key item)}
         [:div.perms-cap-chip
          {:class (if ok? "is-allowed" "is-locked")}
          (if ok?
            [icons/check {:width "13px" :height "13px"}]
            [icons/lock {:width "13px" :height "13px"}])
          [:span (resolve-label tr item)]
          [:span.emotes-mgr-pack-count (str "≥" req)]])]]]))

(defn- role-editor-footer-bar
  [{:keys [tr creating? sel-role confirm-del? saving? dirty? name-blank?
           on-toggle-del on-delete on-reset on-save]}]
  [:div.perms-footer-bar
   (if (and (not creating?) (:deletable? sel-role))
     (if confirm-del?
       [:div.perms-confirm-inline
        [:span.perms-confirm-text
         (tr [:room-settings.roles/delete-prompt] [(:name sel-role)])]
        [:button.settings-button.secondary
         {:type     "button"
          :disabled saving?
          :on-click #(on-toggle-del false)}
         (tr [:creation.action/cancel])]
        [:button.settings-button.danger
         {:type     "button"
          :disabled saving?
          :on-click on-delete}
         [icons/trash {:width "14px" :height "14px"}]
         [:span (tr [:room-settings.roles/confirm-delete])]]]
       [:button.settings-button.danger
        {:type     "button"
         :disabled saving?
         :on-click #(on-toggle-del true)}
        [icons/trash {:width "14px" :height "14px"}]
        [:span (tr [:room-settings.roles/delete-role])]])
     [:div])

   (when dirty?
     [:div.emotes-mgr-action-btns
      [:button.settings-button.secondary
       {:type     "button"
        :disabled saving?
        :on-click on-reset}
       (if creating?
         (tr [:creation.action/cancel])
         (tr [:settings.profile/reset]))]
      [:button.settings-button.primary
       {:type     "button"
        :disabled (or saving? name-blank?)
        :on-click on-save}
       (if saving?
         (tr [:settings.profile/saving])
         (tr [:settings.profile/save-changes]))]])])

(defn- extract-role-attrs [role attr-toggles]
  (into {} (map (fn [{:keys [key]}] [key (boolean (get role key))])) attr-toggles))

(defn ^:ui roles-editor-tab [{:keys [room-id]}]
  (r/with-let [!sel-id       (r/atom nil)
               !creating?    (r/atom false)
               !seed-sig     (atom nil)
               !name         (r/atom "")
               !color        (r/atom "#5865f2")
               !rank         (r/atom 0)
               !attrs        (r/atom {})
               !perms        (r/atom {})
               !perm-filter  (r/atom "")
               !confirm-del? (r/atom false)]
    (let [tr           @(re-frame/subscribe [:i18n/tr])
          p-state      @(re-frame/subscribe [:room-perms/state room-id])
          members      @(re-frame/subscribe [:room/members room-id])
          {:keys [loading? saving? data error saved?]} p-state
          rp           (:roles-panel data {})
          multi-role?  (multi-role-assignment? data)
          can-reorder? (boolean (:can-reorder? rp))
          rank-cfg     (:role-rank-config rp)
          attr-toggles (vec (or (:role-attribute-toggles rp) []))
          locked-ranks (set (or (:locked-ranks rank-cfg) #{}))
          perm-groups  (vec (or (:permission-groups rp) []))
          cap-preview  (:capability-preview rp)
          default-rk   (int (or (:default rank-cfg) 0))
          default-new  (or (:default-new-role rp)
                           {:name        (tr [:room-settings.roles/new-role])
                            :color       "#818cf8"
                            :rank        default-rk
                            :permissions {}})
          can-edit?    (boolean (:can-manage-roles? data))
          roles        (vec (:roles data []))
          creating?    @!creating?
          active-id    (when-not creating?
                         (or (when (some #(= (str (:id %)) (str @!sel-id)) roles) @!sel-id)
                             (:id (first roles))))
          sel-role     (when-not creating?
                         (some #(when (= (str (:id %)) (str active-id)) %) roles))
          sel-attrs    (when sel-role (extract-role-attrs sel-role attr-toggles))
          sig          [room-id creating? (:id sel-role) (:name sel-role)
                        (:color sel-role) (when sel-role (role-rank sel-role))
                        sel-attrs (:raw-permissions sel-role)]
          _            (when (not= @!seed-sig sig)
                         (reset! !seed-sig sig)
                         (reset! !confirm-del? false)
                         (if creating?
                           (do
                             (reset! !name (or (:name default-new)
                                               (tr [:room-settings.roles/new-role])))
                             (reset! !color (or (:color default-new) "#818cf8"))
                             (reset! !rank (int (or (:rank default-new)
                                                    (:power-level default-new)
                                                    default-rk)))
                             (reset! !attrs (extract-role-attrs {} attr-toggles))
                             (reset! !perms (or (:permissions default-new) {})))
                           (when sel-role
                             (reset! !name (or (:name sel-role) ""))
                             (reset! !color (:color sel-role))
                             (reset! !rank (role-rank sel-role))
                             (reset! !attrs sel-attrs)
                             (reset! !perms (or (:permissions sel-role) {})))))
          everyone?    (and (not creating?) (boolean (:everyone? sel-role)))
          managed?     (and (not creating?) (boolean (:managed? sel-role)))
          lock-meta?   (or (not can-edit?) managed? everyone?)
          lock-rank?   (or saving?
                           (not can-edit?)
                           (and (not creating?)
                                (contains? locked-ranks (role-rank sel-role))))
          dirty?       (or creating?
                           (when sel-role
                             (or (not= @!name (or (:name sel-role) ""))
                                 (not= @!color (:color sel-role))
                                 (and (:editable? rank-cfg)
                                      (not= (int @!rank) (role-rank sel-role)))
                                 (and (seq attr-toggles)
                                      (not= @!attrs sel-attrs))
                                 (and (seq perm-groups)
                                      (not= @!perms (or (:permissions sel-role) {}))))))
          movable      (vec (remove :everyone? roles))
          move-role!   (fn [rid dir]
                         (let [ids (mapv #(str (:id %)) movable)
                               idx (.indexOf ids (str rid))
                               nxt (+ idx dir)]
                           (when (and (>= idx 0) (>= nxt 0) (< nxt (count ids)))
                             (let [swapped (-> ids
                                               (assoc idx (nth ids nxt))
                                               (assoc nxt (nth ids idx)))]
                               (re-frame/dispatch
                                [:room-perms/reorder-roles
                                 {:room-id     room-id
                                  :ordered-ids swapped}])))))]
      [:div.settings-tab-content
       [:p.settings-subview-lead
        (tr [(or (normalize-keyword (:lead-key rp)) :room-settings.roles/lead)])]

       [perms-feedback-banners tr error saved?]

       (if (and loading? (empty? roles))
         [:div.emotes-mgr-empty (tr [:container.members/loading])]
         [:div.perms-split-layout
          [role-hierarchy-rail
           {:tr              tr
            :room-id         room-id
            :roles           roles
            :movable         movable
            :members         members
            :multi-role?     multi-role?
            :rank-cfg        rank-cfg
            :can-edit?       can-edit?
            :can-reorder?    can-reorder?
            :saving?         saving?
            :creating?       creating?
            :active-id       active-id
            :draft-name      @!name
            :draft-color     @!color
            :on-start-create (fn []
                               (re-frame/dispatch [:room-perms/clear-feedback room-id])
                               (reset! !creating? true))
            :on-select       (fn [rid]
                               (re-frame/dispatch [:room-perms/clear-feedback room-id])
                               (reset! !creating? false)
                               (reset! !sel-id rid))
            :on-move         move-role!}]

          [:div.perms-detail
           (if (and (not creating?) (nil? sel-role))
             [:div.emotes-mgr-empty (tr [:room-settings.roles/empty])]
             [:<>
              [role-identity-card
               {:tr           tr
                :room-id      room-id
                :name-val     @!name
                :color-val    @!color
                :rank-val     @!rank
                :attr-vals    @!attrs
                :attr-toggles attr-toggles
                :rank-cfg     rank-cfg
                :everyone?    everyone?
                :saving?      saving?
                :lock-meta?   lock-meta?
                :lock-rank?   lock-rank?
                :on-name      #(reset! !name %)
                :on-color     #(reset! !color %)
                :on-rank      #(reset! !rank %)
                :on-attr      #(swap! !attrs assoc %1 %2)}]

              [role-permissions-toggles-card
               {:tr          tr
                :perm-groups perm-groups
                :perm-filter @!perm-filter
                :perms       @!perms
                :can-edit?   can-edit?
                :managed?    managed?
                :saving?     saving?
                :on-filter   #(reset! !perm-filter %)
                :on-toggle   #(swap! !perms assoc %1 %2)}]

              [role-capability-preview-card
               {:tr           tr
                :cap-preview  cap-preview
                :current-rank @!rank}]

              (when can-edit?
                [role-editor-footer-bar
                 {:tr            tr
                  :creating?     creating?
                  :sel-role      sel-role
                  :confirm-del?  @!confirm-del?
                  :saving?       saving?
                  :dirty?        dirty?
                  :name-blank?   (str/blank? (str/trim @!name))
                  :on-toggle-del #(reset! !confirm-del? %)
                  :on-delete     #(re-frame/dispatch
                                   [:room-perms/delete-role
                                    {:room-id room-id
                                     :role-id (:id sel-role)
                                     :on-done (fn [_]
                                                (reset! !confirm-del? false)
                                                (reset! !sel-id nil))}])
                  :on-reset      #(if creating?
                                    (reset! !creating? false)
                                    (reset! !seed-sig nil))
                  :on-save       #(let [clean-name (str/trim @!name)
                                        role-map   (merge
                                                    {:id              (when-not creating? (:id sel-role))
                                                     :name            clean-name
                                                     :color           @!color
                                                     :rank            (int @!rank)
                                                     :power-level     (int @!rank)
                                                     :raw-permissions (:raw-permissions sel-role)
                                                     :permissions     @!perms}
                                                    @!attrs)]
                                    (re-frame/dispatch
                                     [:room-perms/upsert-role
                                      {:room-id room-id
                                       :role    role-map
                                       :on-done (fn [res]
                                                  (reset! !creating? false)
                                                  (when-let [created (some (fn [r]
                                                                             (when (= (:name r) clean-name)
                                                                               (:id r)))
                                                                           (:roles res))]
                                                    (reset! !sel-id created)))}]))}])])]])])))

(defn- override-add-target-popover
  [{:keys [tr room-id query cand-roles cand-mems on-query on-pick]}]
  [:div.perms-add-popover
   [:input.settings-input
    {:type        "text"
     :placeholder (tr [:room-settings.perms/search-targets])
     :value       query
     :style       {:height "32px" :font-size "0.8rem"}
     :on-change   #(on-query (.. % -target -value))}]
   [:div.perms-add-popover-list
    (when (seq cand-roles)
      [:div.perms-add-group-lbl (tr [:room-settings.perms/group-roles])])
    (for [r cand-roles]
      ^{:key (str "r-" (:id r))}
      [:button.perms-add-option
       {:type     "button"
        :on-click #(on-pick (str (:id r)) :role)}
       [:span.room-settings-role-dot
        {:style {:background (or (:color r) "#949ba4")}}]
       [:span (:name r)]])
    (when (seq cand-mems)
      [:div.perms-add-group-lbl (tr [:room-settings.perms/group-members])])
    (for [m cand-mems]
      ^{:key (str "m-" (:user-id m))}
      [:button.perms-add-option
       {:type     "button"
        :on-click #(on-pick (str (:user-id m)) :member)}
       [avatar {:id (:user-id m) :name (:display-name m) :url (:avatar-url m) :size 20}]
       [:span (:display-name m)]])
    (when (and (empty? cand-roles) (empty? cand-mems))
      [:div.emotes-mgr-empty {:style {:padding "12px"}}
       (tr [:room-settings.perms/no-matches])])]])

(defn- override-targets-rail
  [{:keys [tr room-id section overwrites active-tid can-edit? saving?
           add-open? add-q cand-roles cand-mems
           on-toggle-add on-add-query on-add-target on-select]}]
  [:div.perms-rail
   [:div.perms-rail-header
    [:span.perms-rail-title
     (resolve-label tr (merge {:title-key :room-settings.perms/overrides-heading} section))]
    (when can-edit?
      [:div {:style {:position "relative"}}
       [:button.settings-button.primary
        {:type     "button"
         :disabled saving?
         :style    {:height "30px" :padding "0 10px" :font-size "0.78rem"}
         :on-click on-toggle-add}
        [icons/plus {:width "13px" :height "13px"}]
        [:span (tr [:room-settings.perms/add-override])]]
       (when add-open?
         [override-add-target-popover
          {:tr         tr
           :room-id    room-id
           :query      add-q
           :cand-roles cand-roles
           :cand-mems  cand-mems
           :on-query   on-add-query
           :on-pick    on-add-target}])])]

   [:div.perms-rail-list
    (if (empty? overwrites)
      [:div.emotes-mgr-empty {:style {:padding "18px 12px"}}
       (tr [:room-settings.perms/no-overrides])]
      (for [ow overwrites
            :let [tid     (str (:id ow))
                  active? (= tid (str active-tid))]]
        ^{:key tid}
        [:button.perms-rail-item
         {:type     "button"
          :class    (when active? "is-active")
          :on-click #(on-select tid)}
         (if (= (:type ow) :member)
           [avatar {:id tid :name (:name ow) :url (:avatar-url ow) :size 22}]
           [:span.room-settings-role-dot
            {:style {:background (or (:color ow) "#949ba4")}}])
         [:div.perms-rail-item-copy
          [:span.perms-rail-item-name (:name ow)]
          [:span.perms-rail-item-sub
           (if (= (:type ow) :member)
             (tr [:room-settings.perms/type-member])
             (tr [:room-settings.perms/type-role]))]]]))]])

(defn- override-tri-state-row [{:keys [tr item state-kw disabled? on-set]}]
  (let [k (:key item)]
    [:div.perms-toggle-row
     [:div.perms-toggle-copy
      [:span.perms-toggle-title (resolve-label tr item)]
      [:span.perms-toggle-desc (resolve-desc tr item)]]
     [:div.perms-tri-switch
      [:button.perms-tri-btn.is-deny
       {:type     "button"
        :disabled disabled?
        :class    (when (= state-kw :deny) "is-selected")
        :title    (tr [:room-settings.perms/deny])
        :on-click #(on-set k :deny)}
       "✕"]
      [:button.perms-tri-btn.is-inherit
       {:type     "button"
        :disabled disabled?
        :class    (when (= state-kw :inherit) "is-selected")
        :title    (tr [:room-settings.perms/inherit])
        :on-click #(on-set k :inherit)}
       "/"]
      [:button.perms-tri-btn.is-allow
       {:type     "button"
        :disabled disabled?
        :class    (when (= state-kw :allow) "is-selected")
        :title    (tr [:room-settings.perms/allow])
        :on-click #(on-set k :allow)}
       "✓"]]]))

(defn- override-detail-panel
  [{:keys [tr sel-ow perm-groups perm-q allow-set deny-set can-edit? saving?
           dirty? on-perm-q on-set-state on-remove on-reset on-save]}]
  (let [pq (safe-lower-trim perm-q)]
    [:div.perms-detail
     (if-not sel-ow
       [:div.emotes-mgr-empty (tr [:room-settings.perms/select-override])]
       [:<>
        [:div.emotes-mgr-section-head {:style {:margin-bottom "12px"}}
         [:div.settings-hub-group-label
          (tr [:room-settings.perms/overrides-title] [(:name sel-ow)])]
         [:div.emotes-mgr-filter-box
          [icons/search {:width "14px" :height "14px"}]
          [:input.emotes-mgr-filter-input
           {:type        "text"
            :placeholder (tr [:room-settings.perms/filter-placeholder])
            :value       perm-q
            :on-change   #(on-perm-q (.. % -target -value))}]]]

        (for [{:keys [id items] :as grp} perm-groups
              :let [vis-items (filterv
                               (fn [it]
                                 (or (str/blank? pq)
                                     (str/includes? (safe-lower (resolve-label tr it)) pq)
                                     (str/includes? (safe-lower (resolve-desc tr it)) pq)))
                               items)]
              :when (seq vis-items)]
          ^{:key id}
          [:div.settings-hub-group-card {:style {:margin-bottom "12px"}}
           [:div.perms-group-card-header (resolve-label tr grp)]
           (for [{:keys [key] :as it} vis-items
                 :let [state-kw (cond
                                  (contains? allow-set key) :allow
                                  (contains? deny-set key)  :deny
                                  :else                     :inherit)]]
             ^{:key key}
             [override-tri-state-row
              {:tr        tr
               :item      it
               :state-kw  state-kw
               :disabled? (or saving? (not can-edit?))
               :on-set    on-set-state}])])

        (when can-edit?
          [:div.perms-footer-bar
           [:button.settings-button.danger
            {:type     "button"
             :disabled saving?
             :on-click on-remove}
            [icons/trash {:width "14px" :height "14px"}]
            [:span (tr [:room-settings.perms/remove-override])]]

           (when dirty?
             [:div.emotes-mgr-action-btns
              [:button.settings-button.secondary
               {:type     "button"
                :disabled saving?
                :on-click on-reset}
               (tr [:settings.profile/reset])]
              [:button.settings-button.primary
               {:type     "button"
                :disabled saving?
                :on-click on-save}
               (if saving?
                 (tr [:settings.profile/saving])
                 (tr [:settings.profile/save-changes]))]])])])]))

(defn- permission-overrides-section [{:keys [room-id section roles members saving? can-edit?]}]
  (r/with-let [!sel-tid   (r/atom nil)
               !seed-sig  (atom nil)
               !allow-set (r/atom #{})
               !deny-set  (r/atom #{})
               !add-open? (r/atom false)
               !add-q     (r/atom "")
               !perm-q    (r/atom "")]
    (let [tr          @(re-frame/subscribe [:i18n/tr])
          overwrites  (vec (:overwrites section []))
          perm-groups (vec (:permission-groups section []))
          ow-ids      (set (map #(str (:id %)) overwrites))
          active-tid  (or (when (some #(= (str (:id %)) (str @!sel-tid)) overwrites)
                            @!sel-tid)
                          (:id (first overwrites)))
          sel-ow      (some #(when (= (str (:id %)) (str active-tid)) %) overwrites)
          sig         [room-id (:id sel-ow) (:allow sel-ow) (:deny sel-ow)]
          _           (when (not= @!seed-sig sig)
                        (reset! !seed-sig sig)
                        (reset! !allow-set (set (map keyword (:allow sel-ow))))
                        (reset! !deny-set (set (map keyword (:deny sel-ow)))))
          dirty?      (when sel-ow
                        (or (not= @!allow-set (set (map keyword (:allow sel-ow))))
                            (not= @!deny-set (set (map keyword (:deny sel-ow))))))
          aq          (safe-lower-trim @!add-q)
          cand-roles  (->> roles
                           (remove #(contains? ow-ids (str (:id %))))
                           (filter #(or (str/blank? aq)
                                        (str/includes? (safe-lower (:name %)) aq))))
          cand-mems   (->> members
                           (remove #(contains? ow-ids (str (:user-id %))))
                           (filter #(or (str/blank? aq)
                                        (str/includes? (safe-lower (:display-name %)) aq)
                                        (str/includes? (safe-lower (:user-id %)) aq)))
                           (take 30))
          set-state!  (fn [kw mode]
                        (case mode
                          :deny    (do (swap! !allow-set disj kw) (swap! !deny-set conj kw))
                          :inherit (do (swap! !allow-set disj kw) (swap! !deny-set disj kw))
                          :allow   (do (swap! !deny-set disj kw) (swap! !allow-set conj kw))))]
      [:div.perms-split-layout
       [override-targets-rail
        {:tr            tr
         :room-id       room-id
         :section       section
         :overwrites    overwrites
         :active-tid    active-tid
         :can-edit?     can-edit?
         :saving?       saving?
         :add-open?     @!add-open?
         :add-q         @!add-q
         :cand-roles    cand-roles
         :cand-mems     cand-mems
         :on-toggle-add #(swap! !add-open? not)
         :on-add-query  #(reset! !add-q %)
         :on-add-target (fn [tid target-type]
                          (reset! !add-open? false)
                          (reset! !add-q "")
                          (reset! !sel-tid tid)
                          (re-frame/dispatch
                           [:room-perms/update-permissions
                            {:room-id   room-id
                             :action    :upsert-overwrite
                             :overwrite {:id    tid
                                         :type  target-type
                                         :allow []
                                         :deny  []}}]))
         :on-select     #(reset! !sel-tid %)}]

       [override-detail-panel
        {:tr           tr
         :sel-ow       sel-ow
         :perm-groups  perm-groups
         :perm-q       @!perm-q
         :allow-set    @!allow-set
         :deny-set     @!deny-set
         :can-edit?    can-edit?
         :saving?      saving?
         :dirty?       dirty?
         :on-perm-q    #(reset! !perm-q %)
         :on-set-state set-state!
         :on-remove    #(re-frame/dispatch
                         [:room-perms/update-permissions
                          {:room-id   room-id
                           :action    :delete-overwrite
                           :overwrite {:id (:id sel-ow)}}])
         :on-reset     #(reset! !seed-sig nil)
         :on-save      #(re-frame/dispatch
                         [:room-perms/update-permissions
                          {:room-id   room-id
                           :action    :upsert-overwrite
                           :overwrite {:id    (:id sel-ow)
                                       :type  (:type sel-ow)
                                       :allow (vec @!allow-set)
                                       :deny  (vec @!deny-set)}}])}]])))

(defmulti permission-section-view
  (fn [{:keys [section]}]
    (keyword (:type section))))

(defmethod permission-section-view :default [_] nil)

(defmethod permission-section-view :permission-overrides
  [{:keys [room-id section roles members saving? can-edit?]}]
  [permission-overrides-section
   {:room-id   room-id
    :section   section
    :roles     roles
    :members   members
    :saving?   saving?
    :can-edit? can-edit?}])

(defmethod permission-section-view :overwrites-matrix [ctx]
  [permission-overrides-section
   (select-keys ctx [:room-id :section :roles :members :saving? :can-edit?])])

(defmethod permission-section-view :section-group
  [{:keys [tr section draft saving? can-edit? on-update-draft]}]
  [:div.settings-hub-group
   [:div.settings-hub-group-label (resolve-label tr section)]
   [:div.settings-hub-group-card
    [:div.room-settings-form-body
     (for [fld (:fields section)
           :let [path (mapv keyword (:path fld))
                 cur  (get-in draft path (:value fld))]]
       ^{:key (:id fld)}
       (case (keyword (:type fld))
         :choice-cards
         [:div.settings-field
          [:label.settings-label (resolve-label tr fld)]
          [:div.perms-choice-grid
           (for [opt (:options fld)
                 :let [val     (:value opt)
                       active? (= cur val)]]
             ^{:key val}
             [:button.perms-choice-card
              {:type     "button"
               :disabled (or saving? (not can-edit?))
               :class    (when active? "is-active")
               :on-click #(on-update-draft path val)}
              [:span.perms-choice-title (resolve-label tr opt)]
              [:span.perms-choice-desc (resolve-desc tr opt)]])]]

         nil))]]])

(defmethod permission-section-view :thresholds
  [{:keys [tr section roles draft saving? can-edit? on-update-draft]}]
  (let [min-v       (int (or (:min section) 0))
        max-v       (int (or (:max section) 100))
        known-ranks (set (map role-rank roles))]
    [:div.settings-hub-group
     [:div.settings-hub-group-label (resolve-label tr section)]
     [:div.settings-hub-group-card
      (for [item (:items section)
            :let [k        (:key item)
                  cur-val  (int (or (get-in draft [:thresholds k]) (:value item) min-v))
                  in-tier? (contains? known-ranks cur-val)]]
        ^{:key k}
        [:div.perms-toggle-row
         [:div.perms-toggle-copy
          [:span.perms-toggle-title (resolve-label tr item)]
          [:span.perms-toggle-desc (resolve-desc tr item)]]
         [:div.perms-threshold-controls
          [:select.settings-input.perms-role-select
           {:disabled  (or saving? (not can-edit?))
            :value     (if in-tier? (str cur-val) "custom")
            :on-change (fn [e]
                         (let [v (.. e -target -value)]
                           (when (not= v "custom")
                             (on-update-draft [:thresholds k] (js/parseInt v 10)))))}
           (for [r (sort-by role-rank roles)]
             ^{:key (:id r)}
             [:option {:value (str (role-rank r))}
              (str (:name r) " (" (role-rank r) ")")])
           (when-not in-tier?
             [:option {:value "custom"}
              (tr [:room-settings.perms/custom-level] [cur-val])])]
          [:input.settings-input
           {:type      "number"
            :min       min-v
            :max       max-v
            :disabled  (or saving? (not can-edit?))
            :style     {:width "76px" :text-align "center"}
            :value     cur-val
            :on-change #(let [n (js/parseInt (.. % -target -value) 10)]
                          (when-not (js/isNaN n)
                            (on-update-draft [:thresholds k]
                                             (max min-v (min max-v n)))))}]]])]]))

(defmethod permission-section-view :control-list
  [{:keys [tr room-id parent-space-id section saving? can-edit?]}]
  [:div.settings-hub-group
   [:div.settings-hub-group-label (resolve-label tr section)]
   [:div.settings-hub-group-card
    (for [item (:items section)
          :let [itype (keyword (:type item))]]
      ^{:key (:id item)}
      [:div.perms-toggle-row
       [:div.perms-toggle-copy
        [:span.perms-toggle-title (resolve-label tr item)]
        [:span.perms-toggle-desc (resolve-desc tr item)]]
       (case itype
         :toggle
         [toggle-switch
          {:checked?  (boolean (:value item))
           :disabled? (or saving? (not can-edit?))
           :on-change (fn [v]
                        (let [payload (if-let [pk (:payload-key item)]
                                        (assoc-in {} (mapv keyword pk) v)
                                        (or (:payload item) {}))]
                          (re-frame/dispatch
                           [:room-perms/update-permissions
                            (assoc payload
                                   :room-id room-id
                                   :parent-space-id parent-space-id)])))}]

         :action-row
         (when (and (:show-button? item true) can-edit?)
           [:button.settings-button.secondary
            {:type     "button"
             :disabled saving?
             :on-click #(re-frame/dispatch
                         [:room-perms/update-permissions
                          (assoc (or (:payload item) {})
                                 :room-id room-id
                                 :parent-space-id parent-space-id)])}
            (if-let [bk (normalize-keyword (:button-key item))]
              (tr [bk])
              (:button-label item))])

         nil)])]])

(defmethod permission-section-view :info-card
  [{:keys [tr section]}]
  [:div.settings-hub-group
   [:div.settings-hub-group-label (resolve-label tr section)]
   [:div.settings-hub-group-card
    [:div.room-settings-form-body
     (when (or (:body-key section) (:body section))
       [:p.perms-toggle-desc {:style {:margin "0"}}
        (if-let [bk (normalize-keyword (:body-key section))]
          (if (seq (:body-args section))
            (tr [bk] (vec (:body-args section)))
            (tr [bk]))
          (:body section))])
     (when-let [cta (:cta section)]
       [:div.emotes-mgr-action-btns {:style {:justify-content "flex-start"}}
        [:button.settings-button.primary
         {:type     "button"
          :on-click (fn []
                      (when-let [tab (:tab cta)]
                        (re-frame/dispatch [:room-settings/set-tab (keyword tab)]))
                      (when-let [ev (:dispatch cta)]
                        (re-frame/dispatch ev)))}
         (resolve-panel-icon (:icon cta))
         [:span (resolve-label tr cta)]]])
     (when (seq (:chips section))
       [:div.room-settings-roles-bar
        (for [ch (:chips section)
              :let [lbl (or (:label ch) (resolve-label tr ch))]]
          ^{:key lbl}
          [:span.room-settings-role-chip
           {:style (when (:monospace? ch)
                     {:font-family "monospace" :font-size "0.76rem"})}
           (resolve-panel-icon (:icon ch) {:width "12px" :height "12px"})
           [:span lbl]])])]]])

(defn- extract-draft-baseline [sections]
  (reduce
   (fn [acc sec]
     (case (keyword (:type sec))
       :section-group
       (reduce (fn [a f]
                 (if-let [p (:path f)]
                   (assoc-in a (mapv keyword p) (:value f))
                   a))
               acc
               (:fields sec))

       :thresholds
       (reduce (fn [a it]
                 (assoc-in a [:thresholds (:key it)] (int (or (:value it) 0))))
               acc
               (:items sec))

       acc))
   {}
   sections))

(defn- render-declarative-sections
  [{:keys [room-id parent-space-id sections roles members saving? can-edit?]}]
  (r/with-let [!seed-sig (atom nil)
               !draft    (r/atom {})]
    (let [tr       @(re-frame/subscribe [:i18n/tr])
          baseline (extract-draft-baseline sections)
          sig      [room-id baseline]
          _        (when (not= @!seed-sig sig)
                     (reset! !seed-sig sig)
                     (reset! !draft baseline))
          draft    @!draft
          dirty?   (and (seq baseline) (not= draft baseline))]
      [:div
       (for [sec sections
             :let [sec-id (or (:id sec) (str (random-uuid)))]]
         ^{:key sec-id}
         [permission-section-view
          {:tr              tr
           :room-id         room-id
           :parent-space-id parent-space-id
           :section         sec
           :roles           roles
           :members         members
           :draft           draft
           :saving?         saving?
           :can-edit?       can-edit?
           :on-update-draft #(swap! !draft assoc-in %1 %2)}])

       (when (and can-edit? dirty?)
         [:div.perms-footer-bar
          [:div]
          [:div.emotes-mgr-action-btns
           [:button.settings-button.secondary
            {:type     "button"
             :disabled saving?
             :on-click #(reset! !seed-sig nil)}
            (tr [:settings.profile/reset])]
           [:button.settings-button.primary
            {:type     "button"
             :disabled saving?
             :on-click #(re-frame/dispatch
                         [:room-perms/update-permissions
                          (assoc draft
                                 :room-id room-id
                                 :parent-space-id parent-space-id)])}
            (if saving?
              (tr [:settings.profile/saving])
              (tr [:settings.profile/save-changes]))]]])])))

(defn ^:ui permissions-access-tab [{:keys [room-id parent-space-id]}]
  (let [tr       @(re-frame/subscribe [:i18n/tr])
        p-state  @(re-frame/subscribe [:room-perms/state room-id])
        members  @(re-frame/subscribe [:room/members room-id])
        {:keys [loading? saving? data error saved?]} p-state
        pp       (:permissions-panel data {})
        sections (vec (or (:sections pp) []))
        can-ed?  (boolean (:can-manage-permissions? data))]
    [:div.settings-tab-content
     [:p.settings-subview-lead
      (tr [(or (normalize-keyword (:lead-key pp)) :room-settings.perms/lead)])]

     [perms-feedback-banners tr error saved?]

     (if (and loading? (nil? data))
       [:div.emotes-mgr-empty (tr [:container.members/loading])]
       [render-declarative-sections
        {:room-id         room-id
         :parent-space-id parent-space-id
         :sections        sections
         :roles           (vec (:roles data []))
         :members         members
         :saving?         saving?
         :can-edit?       can-ed?}])]))

(defn- member-role-chips
  [{:keys [tr room-id uid is-owner? m-roles m-rids can-assign? saving? chip-style empty-placeholder?]}]
  [:<>
   (when is-owner?
     [:span.room-settings-role-chip {:style chip-style}
      [:span.room-settings-role-dot {:style {:background "#f59e0b"}}]
      [:span (tr [:room-settings.roles/owner-badge])]])
   (if (seq m-roles)
     (for [rb m-roles
           :let [rid (str (:id rb))]]
       ^{:key rid}
       [:span.room-settings-role-chip {:style chip-style}
        [:span.room-settings-role-dot
         {:style {:background (or (:color rb) "#818cf8")}}]
        [:span (:name rb)]
        (when can-assign?
          [:button.perms-role-remove-x
           {:type     "button"
            :disabled saving?
            :title    (tr [:room-settings.roles/remove-role])
            :on-click #(re-frame/dispatch
                        [:room-perms/set-member-roles
                         {:room-id  room-id
                          :user-id  uid
                          :role-ids (vec (disj m-rids rid))}])}
           "×"])])
     (when (and empty-placeholder? (not is-owner?))
       [:span.perms-rail-item-sub
        (tr [:room-settings.roles/no-assigned-roles])]))])

(defn- role-checklist-items [{:keys [room-id uid assignable m-rids]}]
  [:<>
   (for [ar assignable
         :let [arid (str (:id ar))
               has? (contains? m-rids arid)]]
     ^{:key arid}
     [:button.perms-add-option
      {:type     "button"
       :on-click #(let [nxt (if has?
                              (vec (disj m-rids arid))
                              (conj (vec m-rids) arid))]
                    (re-frame/dispatch
                     [:room-perms/set-member-roles
                      {:room-id  room-id
                       :user-id  uid
                       :role-ids nxt}]))}
      [:span.room-settings-role-dot
       {:style {:background (or (:color ar) "#949ba4")}}]
      [:span {:style {:flex 1}} (:name ar)]
      (when has?
        [icons/check {:width "13px" :height "13px"}])])])

(defn- single-rank-role-control
  [{:keys [tr room-id uid rank my-rank can-equal? roles badge self? allow-self?
           can-assign? saving? select-style chip-style require-roles?]}]
  (let [can-edit-target? (and can-assign?
                              (or (not self?) allow-self?)
                              (or (not require-roles?) (seq roles))
                              (or (< rank my-rank)
                                  (and can-equal? (<= rank my-rank))))
        badge-name       (or (:name badge)
                             (tr [:room-settings.perms/power-prefix] [rank]))]
    (if can-edit-target?
      [:select.settings-input.perms-role-select
       (cond-> {:disabled  saving?
                :value     (str rank)
                :on-change #(let [n (js/parseInt (.. % -target -value) 10)]
                              (when-not (js/isNaN n)
                                (re-frame/dispatch
                                 [:room-perms/set-member-roles
                                  {:room-id room-id
                                   :user-id uid
                                   :rank    n}])))}
         select-style (assoc :style select-style))
       (for [r (sort-by role-rank > roles)
             :when (<= (role-rank r) my-rank)]
         ^{:key (:id r)}
         [:option {:value (str (role-rank r))}
          (str (:name r) " (" (role-rank r) ")")])
       (when-not (some #(= (role-rank %) rank) roles)
         [:option {:value (str rank)}
          (tr [:room-settings.perms/power-prefix] [rank])])]
      [:span.room-settings-role-chip {:style chip-style}
       [:span.room-settings-role-dot
        {:style {:background (or (:color badge) "#818cf8")}}]
       [:span badge-name]])))

(defn- members-moderation-toolbar
  [{:keys [tr subtab joined-count bans-count roles multi-role? role-filter query
           on-subtab on-role-filter on-query]}]
  [:div.perms-members-toolbar
   [:div.perms-subtab-pills
    [:button.perms-subtab-pill
     {:type     "button"
      :class    (when (= subtab :active) "is-active")
      :on-click #(on-subtab :active)}
     [:span (tr [:container.panels/members])]
     [:span.emotes-mgr-pack-count joined-count]]
    [:button.perms-subtab-pill
     {:type     "button"
      :class    (when (= subtab :bans) "is-active")
      :on-click #(on-subtab :bans)}
     [icons/ban {:width "13px" :height "13px"}]
     [:span (tr [:room-settings.moderation/banned-tab])]
     [:span.emotes-mgr-pack-count bans-count]]]

   [:div.perms-toolbar-filters
    (when (and (= subtab :active) (seq roles))
      [:select.settings-input.perms-role-select
       {:value     role-filter
        :on-change #(on-role-filter (.. % -target -value))}
       [:option {:value "all"} (tr [:room-settings.roles/all-roles])]
       (for [r (remove :everyone? roles)]
         ^{:key (:id r)}
         [:option {:value (str (if multi-role? (:id r) (role-rank r)))}
          (:name r)])])

    [:div.emotes-mgr-filter-box
     [icons/search {:width "14px" :height "14px"}]
     [:input.emotes-mgr-filter-input
      {:type        "text"
       :placeholder (tr [:container.members/search-placeholder])
       :value       query
       :on-change   #(on-query (.. % -target -value))}]]]])

(defn- banned-members-list [{:keys [tr room-id bans bans-loading? can-ban? saving?]}]
  [:div.settings-hub-group-card
   (if (empty? bans)
     [:div.emotes-mgr-empty
      (if bans-loading?
        (tr [:container.members/loading])
        (tr [:room-settings.moderation/no-bans]))]
     [:div.room-settings-member-list
      (for [b bans
            :let [uid (:user-id b)]]
        ^{:key uid}
        [:div.room-settings-member-row {:style {:cursor "default"}}
         [avatar {:id uid :name (:display-name b) :url (:avatar-url b) :size 34}]
         [:div.room-settings-member-copy
          [:span.room-settings-member-name (or (:display-name b) uid)]
          [:span.room-settings-member-id
           (if (not (str/blank? (str (:reason b))))
             (str uid " · " (tr [:room-settings.moderation/reason-prefix] [(:reason b)]))
             uid)]]
         (when can-ban?
           [:button.settings-button.secondary
            {:type     "button"
             :disabled saving?
             :on-click #(re-frame/dispatch
                         [:room-perms/unban-member
                          {:room-id room-id :user-id uid}])}
            (tr [:room-settings.moderation/unban])])])])])

(defn- inline-mod-confirm-bar
  [{:keys [tr room-id uid ban? reason delete-days ban-hist saving?
           on-reason on-delete-days on-cancel on-done]}]
  [:div.perms-mod-confirm-bar
   [:input.settings-input
    {:type        "text"
     :placeholder (if ban?
                    (tr [:room-settings.moderation/ban-reason-placeholder])
                    (tr [:room-settings.moderation/kick-reason-placeholder]))
     :style       {:flex 1 :height "32px" :font-size "0.82rem"}
     :value       reason
     :on-change   #(on-reason (.. % -target -value))}]
   (when ban?
     [ban-history-select
      {:tr        tr
       :options   ban-hist
       :value     delete-days
       :class     "perms-role-select"
       :style     {:height "32px"}
       :on-change on-delete-days}])
   [:button.settings-button.secondary
    {:type     "button"
     :style    {:height "32px" :padding "0 10px"}
     :on-click on-cancel}
    (tr [:creation.action/cancel])]
   [:button.settings-button.danger
    {:type     "button"
     :disabled saving?
     :style    {:height "32px" :padding "0 12px"}
     :on-click #(dispatch-moderation-action!
                 {:ban?        ban?
                  :room-id     room-id
                  :user-id     uid
                  :reason      reason
                  :delete-days delete-days
                  :on-done     on-done})}
    (if ban?
      (tr [:room-settings.moderation/confirm-ban])
      (tr [:room-settings.moderation/confirm-kick]))]])

(defn- active-member-row
  [{:keys [tr room-id member data roles assignable role-tags multi-role?
           allow-self? can-assign? can-kick? can-ban? saving? active-uid
           pick-open? mod-action mod-reason delete-days ban-hist
           on-toggle-picker on-start-mod on-reason on-delete-days on-cancel-mod]}]
  (let [uid       (str (:user-id member))
        self?     (= uid (str active-uid))
        rk        (member-rank member)
        my-rk     (caller-rank data)
        m-roles   (:roles member)
        m-rids    (set (map str (:role-ids member)))
        badge     (resolve-rank-badge rk role-tags roles)
        top-color (or (:color (first m-roles)) (:color badge))
        can-mod?  (can-moderate-member? data member self?)
        mod-here  (when (= (:uid mod-action) uid) mod-action)]
    [:div.perms-member-block
     [:div.room-settings-member-row {:style {:cursor "default"}}
      [avatar {:id uid :name (:display-name member) :url (:avatar-url member) :size 34}]
      [:div.room-settings-member-copy
       [:span.room-settings-member-name
        {:style (when top-color {:color top-color})}
        (or (:display-name member) uid)]
       [:span.room-settings-member-id uid]]

      [:div.perms-member-roles-wrap
       (if multi-role?
         [:<>
          [member-role-chips {:tr          tr
                              :room-id     room-id
                              :uid         uid
                              :is-owner?   (:is-owner? member)
                              :m-roles     m-roles
                              :m-rids      m-rids
                              :can-assign? can-assign?
                              :saving?     saving?
                              :chip-style  {:padding "3px 8px" :font-size "0.73rem"}}]
          (when (and can-assign? (seq assignable))
            [:div {:style {:position "relative"}}
             [:button.perms-add-role-pill
              {:type     "button"
               :disabled saving?
               :title    (tr [:room-settings.roles/assign-roles])
               :on-click #(on-toggle-picker uid)}
              "+"]
             (when pick-open?
               [:div.perms-role-checklist-pop
                [role-checklist-items {:room-id    room-id
                                       :uid        uid
                                       :assignable assignable
                                       :m-rids     m-rids}]])])]

         [single-rank-role-control
          {:tr          tr
           :room-id     room-id
           :uid         uid
           :rank        rk
           :my-rank     my-rk
           :can-equal?  (boolean (:can-act-on-equal-rank? data))
           :roles       roles
           :badge       badge
           :self?       self?
           :allow-self? allow-self?
           :can-assign? can-assign?
           :saving?     saving?
           :chip-style  {:padding "3px 9px" :font-size "0.74rem"}}])]

      (when (and can-mod? (or can-kick? can-ban?))
        [:div.perms-mod-btns
         (when can-kick?
           [:button.perms-mod-icon-btn
            {:type     "button"
             :title    (tr [:container.member-actions/kick])
             :on-click #(on-start-mod uid :kick)}
            [icons/leave {:width "14px" :height "14px"}]])
         (when can-ban?
           [:button.perms-mod-icon-btn.is-danger
            {:type     "button"
             :title    (tr [:room-settings.moderation/ban])
             :on-click #(on-start-mod uid :ban)}
            [icons/ban {:width "14px" :height "14px"}]])])]

     (when mod-here
       [inline-mod-confirm-bar
        {:tr             tr
         :room-id        room-id
         :uid            uid
         :ban?           (= (:type mod-here) :ban)
         :reason         mod-reason
         :delete-days    delete-days
         :ban-hist       ban-hist
         :saving?        saving?
         :on-reason      on-reason
         :on-delete-days on-delete-days
         :on-cancel      on-cancel-mod
         :on-done        (fn [_] (on-cancel-mod))}])]))

(defn ^:ui members-moderation-tab [{:keys [room-id]}]
  (r/with-let [!subtab       (r/atom :active)
               !q            (r/atom "")
               !role-filter  (r/atom "all")
               !picker-uid   (r/atom nil)
               !mod-action   (r/atom nil)
               !mod-reason   (r/atom "")
               !delete-days  (r/atom 0)
               !bans-fetched (atom nil)]
    (let [tr           @(re-frame/subscribe [:i18n/tr])
          active-uid   @(re-frame/subscribe [:auth/active-user-id])
          members      @(re-frame/subscribe [:room/members room-id])
          mem-loading? @(re-frame/subscribe [:room/members-loading? room-id])
          role-tags    @(re-frame/subscribe [:room/power-level-tags room-id])
          p-state      @(re-frame/subscribe [:room-perms/state room-id])
          _            (when (and room-id (not= @!bans-fetched room-id))
                         (reset! !bans-fetched room-id)
                         (re-frame/dispatch [:room-perms/fetch-bans room-id]))
          {:keys [saving? data bans bans-loading? error saved?]} p-state
          mp           (:moderation-panel data {})
          multi-role?  (multi-role-assignment? data)
          allow-self?  (boolean (:allow-self-role-change? mp))
          ban-hist     (vec (or (:ban-history-options mp) []))
          roles        (vec (:roles data []))
          assignable   (vec (remove #(or (:everyone? %) (:managed? %)) roles))
          can-assign?  (boolean (:can-assign-roles? data))
          can-kick?    (boolean (:can-kick? data))
          can-ban?     (boolean (:can-ban? data))
          joined       (filterv active-member? members)
          q            (safe-lower-trim @!q)
          rf           @!role-filter
          shown-mems   (->> joined
                            (filter (fn [m]
                                      (and (or (str/blank? q)
                                               (str/includes? (safe-lower (:display-name m)) q)
                                               (str/includes? (safe-lower (:user-id m)) q))
                                           (or (= rf "all")
                                               (if multi-role?
                                                 (some #{rf} (map str (:role-ids m)))
                                                 (= (str (member-rank m)) rf))))))
                            (sort-by (fn [m] [(- (member-rank m))
                                              (safe-lower (:display-name m))]))
                            (take 250))
          shown-bans   (->> bans
                            (filter (fn [b]
                                      (or (str/blank? q)
                                          (str/includes? (safe-lower (:display-name b)) q)
                                          (str/includes? (safe-lower (:user-id b)) q)))))]
      [:div.settings-tab-content
       [:p.settings-subview-lead
        (tr [(or (normalize-keyword (:lead-key mp)) :room-settings.moderation/lead)])]

       [perms-feedback-banners tr error saved?]

       [members-moderation-toolbar
        {:tr             tr
         :subtab         @!subtab
         :joined-count   (count joined)
         :bans-count     (count bans)
         :roles          roles
         :multi-role?    multi-role?
         :role-filter    rf
         :query          @!q
         :on-subtab      (fn [st]
                           (reset! !subtab st)
                           (when (= st :bans)
                             (re-frame/dispatch [:room-perms/fetch-bans room-id])))
         :on-role-filter #(reset! !role-filter %)
         :on-query       #(reset! !q %)}]

       (if (= @!subtab :bans)
         [banned-members-list
          {:tr            tr
           :room-id       room-id
           :bans          shown-bans
           :bans-loading? bans-loading?
           :can-ban?      can-ban?
           :saving?       saving?}]

         [:div.settings-hub-group-card
          (if (empty? shown-mems)
            [:div.emotes-mgr-empty
             (if mem-loading?
               (tr [:container.members/loading])
               (tr [:container.members/no-results]))]
            [:div.room-settings-member-list
             (for [m shown-mems
                   :let [uid (str (:user-id m))]]
               ^{:key uid}
               [active-member-row
                {:tr               tr
                 :room-id          room-id
                 :member           m
                 :data             data
                 :roles            roles
                 :assignable       assignable
                 :role-tags        role-tags
                 :multi-role?      multi-role?
                 :allow-self?      allow-self?
                 :can-assign?      can-assign?
                 :can-kick?        can-kick?
                 :can-ban?         can-ban?
                 :saving?          saving?
                 :active-uid       active-uid
                 :pick-open?       (= @!picker-uid uid)
                 :mod-action       @!mod-action
                 :mod-reason       @!mod-reason
                 :delete-days      @!delete-days
                 :ban-hist         ban-hist
                 :on-toggle-picker #(swap! !picker-uid (fn [cur] (when-not (= cur %) %)))
                 :on-start-mod     (fn [target-uid kind]
                                     (reset! !mod-reason "")
                                     (reset! !delete-days 0)
                                     (reset! !mod-action {:uid target-uid :type kind}))
                 :on-reason        #(reset! !mod-reason %)
                 :on-delete-days   #(reset! !delete-days %)
                 :on-cancel-mod    #(reset! !mod-action nil)}])])])])))

(defn ^:ui member-popover-roles-and-mod [{:keys [member room-id tags]}]
  (r/with-let [!fetched-rid (atom nil)
               !pick-open?  (r/atom false)]
    (let [tr          @(re-frame/subscribe [:i18n/tr])
          active-uid  @(re-frame/subscribe [:auth/active-user-id])
          live-mems   @(re-frame/subscribe [:room/members room-id])
          p-state     @(re-frame/subscribe [:room-perms/state room-id])
          _           (when (and room-id (not= @!fetched-rid room-id) (nil? (:data p-state)))
                        (reset! !fetched-rid room-id)
                        (re-frame/dispatch [:room-perms/fetch room-id]))
          data        (:data p-state)
          mp          (:moderation-panel data {})
          saving?     (:saving? p-state)
          uid         (str (:user-id member))
          live-m      (or (some #(when (= (str (:user-id %)) uid) %) live-mems) member)
          self?       (= uid (str active-uid))
          multi-role? (multi-role-assignment? data)
          allow-self? (boolean (:allow-self-role-change? mp))
          roles       (vec (:roles data []))
          assignable  (vec (remove #(or (:everyone? %) (:managed? %)) roles))
          m-roles     (:roles live-m)
          m-rids      (set (map str (:role-ids live-m)))
          rk          (member-rank live-m)
          my-rk       (caller-rank data)
          badge       (resolve-rank-badge rk tags roles)
          can-assign? (boolean (:can-assign-roles? data))
          can-mod?    (can-moderate-member? data live-m self?)
          can-kick?   (and can-mod? (:can-kick? data))
          can-ban?    (and can-mod? (:can-ban? data))]
      (when room-id
        [:div.perms-popover-section
         [:div.perms-popover-roles-head
          [:span.profile-preview-section-label
           (tr [:room-settings/roles-heading])]
          (when (and multi-role? can-assign? (seq assignable))
            [:button.perms-add-role-pill
             {:type     "button"
              :disabled saving?
              :on-click #(swap! !pick-open? not)}
             (if @!pick-open?
               (tr [:room-settings.roles/done])
               (tr [:room-settings.roles/add-role]))])]

         (if multi-role?
           [:div.perms-popover-badges
            [member-role-chips {:tr                 tr
                                :room-id            room-id
                                :uid                uid
                                :is-owner?          (:is-owner? live-m)
                                :m-roles            m-roles
                                :m-rids             m-rids
                                :can-assign?        can-assign?
                                :saving?            saving?
                                :chip-style         {:padding "2px 7px" :font-size "0.72rem"}
                                :empty-placeholder? true}]
            (when @!pick-open?
              [:div.perms-popover-checklist
               [role-checklist-items {:room-id    room-id
                                      :uid        uid
                                      :assignable assignable
                                      :m-rids     m-rids}]])]

           [:div.perms-popover-badges
            [single-rank-role-control
             {:tr             tr
              :room-id        room-id
              :uid            uid
              :rank           rk
              :my-rank        my-rk
              :can-equal?     (boolean (:can-act-on-equal-rank? data))
              :roles          roles
              :badge          badge
              :self?          self?
              :allow-self?    allow-self?
              :can-assign?    can-assign?
              :saving?        saving?
              :require-roles? true
              :select-style   {:width "100%" :height "30px"}
              :chip-style     {:padding "2px 8px" :font-size "0.73rem"}}]])

         (when (or can-kick? can-ban?)
           [:div.perms-popover-mod-row
            (when can-kick?
              [:button.perms-popover-mod-btn
               {:type     "button"
                :on-click #(do
                             (re-frame/dispatch [:ui/close-popover])
                             (re-frame/dispatch
                              [:ui/open-modal :moderate-member
                               {:room-id room-id :member live-m :mode :kick}]))}
               [icons/leave {:width "13px" :height "13px"}]
               [:span (tr [:container.member-actions/kick])]])
            (when can-ban?
              [:button.perms-popover-mod-btn.is-danger
               {:type     "button"
                :on-click #(do
                             (re-frame/dispatch [:ui/close-popover])
                             (re-frame/dispatch
                              [:ui/open-modal :moderate-member
                               {:room-id room-id :member live-m :mode :ban}]))}
               [icons/ban {:width "13px" :height "13px"}]
               [:span (tr [:room-settings.moderation/ban])]])])]))))

(defn ^:ui moderate-member-modal [{:keys [room-id member mode]}]
  (r/with-let [!reason      (r/atom "")
               !delete-days (r/atom 0)]
    (let [tr       @(re-frame/subscribe [:i18n/tr])
          p-state  @(re-frame/subscribe [:room-perms/state room-id])
          saving?  (:saving? p-state)
          ban-hist (vec (or (get-in p-state [:data :moderation-panel :ban-history-options]) []))
          ban?     (= mode :ban)
          uid      (str (:user-id member))
          dname    (or (:display-name member) uid)
          close!   #(re-frame/dispatch [:ui/close-modal])]
      [:div.create-room-container {:style {:width "min(420px, 92vw)"}}
       [:div.creation-header
        [:h3.creation-title
         (if ban?
           (tr [:room-settings.moderation/ban-title] [dname])
           (tr [:room-settings.moderation/kick-title] [dname]))]
        [:button.creation-close-btn
         {:type "button" :on-click close!}
         [icons/exit {:width "18px" :height "18px"}]]]

       [:p.perms-toggle-desc {:style {:margin "0"}}
        (if ban?
          (tr [:room-settings.moderation/ban-desc])
          (tr [:room-settings.moderation/kick-desc]))]

       [:div.form-group
        [:label (tr [:room-settings.moderation/reason-label])]
        [:input.form-input
         {:type        "text"
          :placeholder (tr [:room-settings.moderation/reason-placeholder])
          :value       @!reason
          :on-change   #(reset! !reason (.. % -target -value))}]]

       (when (and ban? (seq ban-hist))
         [:div.form-group
          [:label (tr [:room-settings.moderation/delete-history-label])]
          [ban-history-select
           {:tr           tr
            :options      ban-hist
            :value        @!delete-days
            :long-labels? true
            :on-change    #(reset! !delete-days %)}]])

       [:div.modal-actions
        [:button.form-button.cancel-button
         {:type "button" :disabled saving? :on-click close!}
         (tr [:creation.action/cancel])]
        [:button.settings-button.danger
         {:type     "button"
          :disabled saving?
          :style    {:height "38px" :padding "0 18px"}
          :on-click #(dispatch-moderation-action!
                      {:ban?        ban?
                       :room-id     room-id
                       :user-id     uid
                       :reason      @!reason
                       :delete-days @!delete-days
                       :on-done     (fn [_] (close!))})}
         (if saving?
           (tr [:settings.profile/saving])
           (if ban?
             (tr [:room-settings.moderation/ban])
             (tr [:room-settings.moderation/kick])))]]])))

(defmethod modal-component :moderate-member [_]
  moderate-member-modal)