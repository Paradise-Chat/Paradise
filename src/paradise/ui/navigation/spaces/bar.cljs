(ns paradise.ui.navigation.spaces.bar
  (:require
   [cljs-workers.core :as main]
   [cljs.core.async :refer [go <!]]
   [clojure.string :as str]
   [paradise.shared.client.session-store :as store]
   [paradise.shared.client.state :as state]
   [paradise.shared.utils.helpers :refer [hex->rgba normalize-id parse-edn-map resolve-dm-display]]
   [paradise.shared.utils.svg :as icons]
   [paradise.ui.global :refer [avatar long-press-props]]
   [paradise.ui.overlays.base :refer [context-menu-component
                                      context-menu-submenu
                                      modal-component]]
   [paradise.ui.overlays.rooms :refer [engine-room-option-items]]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [taoensso.timbre :as log]))

(def group-palette
  ["#5865f2" "#3ba55c" "#faa61a" "#ed4245" "#eb459e" "#9b59b6" "#00b0f4" "#1abc9c"])

(defonce !space-drag-state (r/atom nil))

(defn- active-layout-key [db]
  (or (:active-user-id db) :default))

(defn- gen-group-id []
  (str (+ 1000000000 (rand-int 1147483647))))

(defn- space-avatar-url [s]
  (or (:avatar-url s) (:avatar s) (:avatarUrl s)))

(defn- cleanup-empty-groups [{:keys [order groups]}]
  (let [clean-groups (into {}
                           (keep (fn [[gid g]]
                                   (let [sids (vec (distinct (remove nil? (:space-ids g))))]
                                     (when (seq sids)
                                       [gid (assoc g :space-ids sids)]))))
                           groups)
        clean-order  (filterv (fn [{:keys [type id]}]
                                (or (not= type :group)
                                    (contains? clean-groups id)))
                              (or order []))]
    {:order  clean-order
     :groups clean-groups}))

(defn- materialize-layout [db raw-layout]
  (let [spaces-list (:spaces-list db [])
        live-ids    (into [] (keep :id) spaces-list)
        groups      (:groups raw-layout {})
        grouped-ids (into #{} (mapcat :space-ids) (vals groups))
        init-order  (:order raw-layout [])
        step-1      (reduce (fn [{:keys [acc seen]} {:keys [type id] :as entry}]
                              (let [valid? (case type
                                             :group (contains? groups id)
                                             :space (and id (not (contains? grouped-ids id)))
                                             false)
                                    k      [type id]]
                                (if (and valid? (not (contains? seen k)))
                                  {:acc  (conj acc {:type type :id id})
                                   :seen (conj seen k)}
                                  {:acc acc :seen seen})))
                            {:acc [] :seen #{}}
                            init-order)
        step-2      (reduce (fn [{:keys [acc seen]} gid]
                              (let [k [:group gid]]
                                (if (contains? seen k)
                                  {:acc acc :seen seen}
                                  {:acc  (conj acc {:type :group :id gid})
                                   :seen (conj seen k)})))
                            step-1
                            (keys groups))
        step-3      (reduce (fn [{:keys [acc seen]} sid]
                              (let [k [:space sid]]
                                (if (or (contains? grouped-ids sid)
                                        (contains? seen k))
                                  {:acc acc :seen seen}
                                  {:acc  (conj acc {:type :space :id sid})
                                   :seen (conj seen k)})))
                            step-2
                            live-ids)]
    (cleanup-empty-groups {:order  (:acc step-3)
                           :groups groups})))

(defn- strip-space-from-layout [layout space-id]
  (let [next-order  (filterv #(not (and (= (:type %) :space)
                                        (= (:id %) space-id)))
                             (:order layout))
        next-groups (reduce-kv (fn [acc gid g]
                                 (assoc acc gid
                                        (update g :space-ids
                                                (fn [sids]
                                                  (filterv #(not= % space-id) sids)))))
                               {}
                               (:groups layout))]
    (cleanup-empty-groups {:order  next-order
                           :groups next-groups})))

(defn- insert-in-vec [xs item target-item position]
  (let [base (filterv #(not= % item) xs)
        idx  (first (keep-indexed (fn [i x] (when (= x target-item) i)) base))]
    (cond
      (or (nil? idx) (= position :end))
      (conj base item)
      (= position :before)
      (into (conj (subvec base 0 idx) item) (subvec base idx))
      :else
      (into (conj (subvec base 0 (inc idx)) item) (subvec base (inc idx))))))

(defn- find-space-group-id [groups space-id]
  (some (fn [[gid g]]
          (when (some #(= % space-id) (:space-ids g))
            gid))
        groups))

(defn- normalize-remote-layout [raw-layout]
  (let [m          (cond
                     (map? raw-layout)    raw-layout
                     (object? raw-layout) (js->clj raw-layout :keywordize-keys true)
                     :else                nil)
        raw-order  (or (:order m) (get m "order") [])
        raw-groups (or (:groups m) (get m "groups") {})]
    (when (map? m)
      {:order  (into []
                     (keep (fn [entry]
                             (let [em (if (map? entry)
                                        entry
                                        (js->clj entry :keywordize-keys true))
                                   t  (some-> (or (:type em) (get em "type")) keyword)
                                   id (some-> (or (:id em) (get em "id")) normalize-id not-empty)]
                               (when (and (contains? #{:space :group} t) id)
                                 {:type t :id id}))))
                     raw-order)
       :groups (reduce-kv
                (fn [acc raw-gid raw-g]
                  (let [gm    (if (map? raw-g)
                                raw-g
                                (js->clj raw-g :keywordize-keys true))
                        gid   (some-> (or (:id gm) (get gm "id") raw-gid)
                                      normalize-id
                                      not-empty)
                        sids  (->> (or (:space-ids gm) (:space_ids gm)
                                       (get gm "space-ids") (get gm "space_ids") [])
                                   (keep #(some-> % str not-empty))
                                   distinct
                                   vec)
                        gname (let [t (str/trim (str (or (:name gm) (get gm "name") "")))]
                                (if (str/blank? t) "Group" t))
                        color (or (some-> (or (:color gm) (get gm "color")) str not-empty)
                                  (first group-palette))]
                    (if (and gid (seq sids))
                      (assoc acc gid {:id        gid
                                      :name      gname
                                      :color     color
                                      :space-ids sids})
                      acc)))
                {}
                (if (map? raw-groups)
                  raw-groups
                  (js->clj raw-groups :keywordize-keys true)))})))

(defn- serialize-remote-layout [{:keys [order groups]}]
  {:order  (mapv (fn [{:keys [type id]}]
                   {:type (clojure.core/name type)
                    :id   (str id)})
                 (or order []))
   :groups (into {}
                 (map (fn [[gid g]]
                        [(str gid)
                         {:id        (str (or (:id g) gid))
                          :name      (str (or (:name g) "Group"))
                          :color     (str (or (:color g) (first group-palette)))
                          :space-ids (mapv str (:space-ids g []))}]))
                 groups)})

(defn- sync-layout-to-engine! [layout]
  (when-let [pool @state/!engine-pool]
    (let [payload (serialize-remote-layout layout)]
      (go
        (try
          (<! (main/do-with-pool! pool {:handler   :save-space-layout
                                        :arguments {:layout payload}}))
          (catch :default _ nil))))))

(defn- persist-user-layout
  ([db next-user-layout]
   (persist-user-layout db next-user-layout {:sync-remote? true}))
  ([db next-user-layout {:keys [sync-remote?] :or {sync-remote? true}}]
   (let [ukey        (active-layout-key db)
         all-layouts (assoc (:space-layouts db {}) ukey next-user-layout)]
     (store/set-setting! "space_layouts" all-layouts)
     (when sync-remote?
       (sync-layout-to-engine! next-user-layout))
     {:db (assoc db :space-layouts all-layouts)})))

(re-frame/reg-event-fx
 :sdk/set-spaces-list-sync
 (fn [{:keys [db]} [_ spaces-vec]]
   (let [spaces-map     (into {} (for [s spaces-vec :when (:id s)] [(:id s) s]))
         cur-space      (:active-space-id db)
         stale-space?   (and (some? cur-space)
                             (seq spaces-vec)
                             (not (contains? spaces-map cur-space)))
         same-spaces?   (= (:spaces-list db) spaces-vec)]
     (cond
       stale-space?
       (let [cur-room   (:active-room-id db)
             cur-user   (:active-user-id db)
             cur-engine (:active-engine db)]
         (log/warn "Active space" cur-space "is missing from synced spaces; falling back to Home.")
         (store/save-nav-state! cur-engine cur-user
                                {:space-id nil :room-id cur-room :filter-id "people"})
         {:db (-> db
                  (assoc :spaces-list spaces-vec
                         :spaces spaces-map
                         :active-space-id nil
                         :active-filter-id "people"))
          :fx [[:dispatch [:room-list/apply-filter "people" {:force? true :persist? false}]]
               [:dispatch [:session/reconcile-restored-room]]]})

       same-spaces?
       {}

       :else
       {:db (-> db
                (assoc :spaces-list spaces-vec)
                (assoc :spaces spaces-map))
        :fx (when (get-in db [:session/pending-restore :applied?])
              [[:dispatch [:session/reconcile-restored-room]]])}))))

(re-frame/reg-event-db
 :sdk/update-space-view
 (fn [db [_ space-id space-items]]
   (let [children-refs (mapv (fn [item]
                               {:id        (:id item)
                                :is-space? (:is-space? item)})
                             space-items)]
     (if (= (get-in db [:space-children space-id]) children-refs)
       db
       (assoc-in db [:space-children space-id] children-refs)))))

(re-frame/reg-event-fx
 :sdk/paginate-space
 (fn [_ [_ space-id]]
   (main/do-with-pool! @state/!engine-pool {:handler   :paginate-space
                                            :arguments {:space-id space-id}})
   {}))

(re-frame/reg-event-db
 :space/hydrate-hierarchies
 (fn [db [_ raw-cache]]
   (assoc db :space-hierarchies-cache (parse-edn-map raw-cache))))

(re-frame/reg-event-db
 :space-groups/hydrate
 (fn [db [_ raw-layouts]]
   (update db :space-layouts #(merge (parse-edn-map raw-layouts) (or % {})))))

(re-frame/reg-event-fx
 :space-groups/apply-remote-sync
 (fn [{:keys [db]} [_ raw-layout]]
   (if-let [norm (normalize-remote-layout raw-layout)]
     (let [ukey        (active-layout-key db)
           prev-groups (get-in db [:space-layouts ukey :groups] {})
           merged-grps (reduce-kv
                        (fn [acc gid g]
                          (let [prev-g     (get prev-groups gid)
                                collapsed? (if (some? prev-g)
                                             (boolean (:collapsed? prev-g))
                                             true)]
                            (assoc acc gid (assoc g :collapsed? collapsed?))))
                        {}
                        (:groups norm))
           next-layout (cleanup-empty-groups
                        {:order  (:order norm)
                         :groups merged-grps})]
       (if (= (get-in db [:space-layouts ukey]) next-layout)
         {}
         (persist-user-layout db next-layout {:sync-remote? false})))
     {})))

(re-frame/reg-event-fx
 :space-groups/toggle
 (fn [{:keys [db]} [_ group-id]]
   (let [ukey   (active-layout-key db)
         layout (materialize-layout db (get-in db [:space-layouts ukey]))]
     (if (contains? (:groups layout) group-id)
       (persist-user-layout db
                            (update-in layout [:groups group-id :collapsed?] not)
                            {:sync-remote? false})
       {}))))

(re-frame/reg-event-fx
 :space-groups/set-all-collapsed
 (fn [{:keys [db]} [_ collapsed?]]
   (let [ukey   (active-layout-key db)
         layout (materialize-layout db (get-in db [:space-layouts ukey]))
         groups (:groups layout)]
     (if (seq groups)
       (let [next-groups (reduce-kv
                          (fn [acc gid g]
                            (assoc acc gid (assoc g :collapsed? (boolean collapsed?))))
                          {}
                          groups)]
         (persist-user-layout db
                              (assoc layout :groups next-groups)
                              {:sync-remote? false}))
       {}))))

(re-frame/reg-event-fx
 :space-groups/create
 (fn [{:keys [db]} [_ {:keys [name color space-ids]}]]
   (let [clean-ids (vec (distinct (remove str/blank? space-ids)))]
     (if (empty? clean-ids)
       {}
       (let [ukey        (active-layout-key db)
             layout      (materialize-layout db (get-in db [:space-layouts ukey]))
             first-sid   (first clean-ids)
             parent-gid  (find-space-group-id (:groups layout) first-sid)
             anchor      (if parent-gid
                           {:type :group :id parent-gid}
                           {:type :space :id first-sid})
             gid         (gen-group-id)
             group-entry {:type :group :id gid}
             with-slot   (update layout :order insert-in-vec group-entry anchor :before)
             stripped    (reduce strip-space-from-layout with-slot clean-ids)
             final-name  (let [trimmed (str/trim (or name ""))]
                           (if (str/blank? trimmed) "Group" trimmed))
             next-layout (-> stripped
                             (assoc-in [:groups gid]
                                       {:id         gid
                                        :name       final-name
                                        :color      (or color (first group-palette))
                                        :collapsed? false
                                        :space-ids  clean-ids})
                             (update :order
                                     (fn [ord]
                                       (if (some #(= % group-entry) ord)
                                         ord
                                         (conj ord group-entry)))))]
         (persist-user-layout db next-layout))))))

(re-frame/reg-event-fx
 :space-groups/update
 (fn [{:keys [db]} [_ {:keys [group-id name color space-ids]}]]
   (let [ukey   (active-layout-key db)
         layout (materialize-layout db (get-in db [:space-layouts ukey]))
         existing (get-in layout [:groups group-id])]
     (if-not existing
       {}
       (let [clean-ids   (vec (distinct (remove str/blank? space-ids)))
             prev-ids    (:space-ids existing [])
             removed-ids (filterv (complement (set clean-ids)) prev-ids)
             added-ids   (filterv (complement (set prev-ids)) clean-ids)]
         (if (empty? clean-ids)
           {:dispatch [:space-groups/ungroup group-id]}
           (let [stripped    (reduce strip-space-from-layout layout added-ids)
                 final-name  (let [trimmed (str/trim (or name ""))]
                               (if (str/blank? trimmed) (:name existing "Group") trimmed))
                 group-entry {:type :group :id group-id}
                 updated     (assoc-in stripped [:groups group-id]
                                       (assoc existing
                                              :name final-name
                                              :color (or color (:color existing))
                                              :space-ids clean-ids))
                 with-rem    (reduce (fn [acc sid]
                                       (update acc :order insert-in-vec
                                               {:type :space :id sid}
                                               group-entry
                                               :after))
                                     updated
                                     (reverse removed-ids))]
             (persist-user-layout db (cleanup-empty-groups with-rem)))))))))

(re-frame/reg-event-fx
 :space-groups/add-space
 (fn [{:keys [db]} [_ group-id space-id]]
   (let [ukey   (active-layout-key db)
         layout (materialize-layout db (get-in db [:space-layouts ukey]))
         curr   (get-in layout [:groups group-id :space-ids])]
     (cond
       (not (contains? (:groups layout) group-id)) {}
       (= [space-id] curr)                         {}
       :else
       (let [stripped    (strip-space-from-layout layout space-id)
             next-layout (update-in stripped [:groups group-id :space-ids]
                                    (fn [sids] (conj (vec sids) space-id)))]
         (persist-user-layout db (cleanup-empty-groups next-layout)))))))

(re-frame/reg-event-fx
 :space-groups/remove-space
 (fn [{:keys [db]} [_ space-id]]
   (let [ukey       (active-layout-key db)
         layout     (materialize-layout db (get-in db [:space-layouts ukey]))
         parent-gid (find-space-group-id (:groups layout) space-id)]
     (if-not parent-gid
       {}
       (let [group-sids  (get-in layout [:groups parent-gid :space-ids] [])
             space-entry {:type :space :id space-id}
             group-entry {:type :group :id parent-gid}
             next-layout (if (<= (count group-sids) 1)
                           {:order  (mapv #(if (= % group-entry) space-entry %)
                                          (:order layout))
                            :groups (dissoc (:groups layout) parent-gid)}
                           (-> layout
                               (strip-space-from-layout space-id)
                               (update :order insert-in-vec space-entry group-entry :after)))]
         (persist-user-layout db (cleanup-empty-groups next-layout)))))))

(re-frame/reg-event-fx
 :space-groups/ungroup
 (fn [{:keys [db]} [_ group-id]]
   (let [ukey   (active-layout-key db)
         layout (materialize-layout db (get-in db [:space-layouts ukey]))
         group  (get-in layout [:groups group-id])]
     (if-not group
       {}
       (let [group-entry {:type :group :id group-id}
             expanded    (mapv (fn [sid] {:type :space :id sid}) (:space-ids group))
             next-order  (into []
                               (mapcat (fn [entry]
                                         (if (= entry group-entry)
                                           expanded
                                           [entry])))
                               (:order layout))
             next-layout {:order  next-order
                          :groups (dissoc (:groups layout) group-id)}]
         (persist-user-layout db (cleanup-empty-groups next-layout)))))))

(re-frame/reg-event-fx
 :space-groups/apply-drop
 (fn [{:keys [db]} [_ {:keys [op source-type source-id target-type target-id
                              group-id position default-name]}]]
   (let [ukey   (active-layout-key db)
         layout (materialize-layout db (get-in db [:space-layouts ukey]))]
     (case op
       :combine-spaces
       (if (or (nil? source-id) (nil? target-id) (= source-id target-id))
         {}
         (let [gid          (gen-group-id)
               target-entry {:type :space :id target-id}
               group-entry  {:type :group :id gid}
               stripped     (strip-space-from-layout layout source-id)
               replaced-ord (mapv #(if (= % target-entry) group-entry %) (:order stripped))
               cleaned-base (strip-space-from-layout (assoc stripped :order replaced-ord)
                                                     target-id)
               next-layout  (-> cleaned-base
                                (assoc-in [:groups gid]
                                          {:id         gid
                                           :name       (or default-name "Group")
                                           :color      (first group-palette)
                                           :collapsed? false
                                           :space-ids  [target-id source-id]})
                                (update :order
                                        (fn [ord]
                                          (if (some #(= % group-entry) ord)
                                            ord
                                            (conj ord group-entry)))))]
           (persist-user-layout db (cleanup-empty-groups next-layout))))

       :add-to-group
       {:dispatch [:space-groups/add-space group-id source-id]}

       :reorder-in-group
       (if (or (nil? group-id) (nil? source-id) (= source-id target-id)
               (not (contains? (:groups layout) group-id)))
         {}
         (let [in-same?    (= group-id (find-space-group-id (:groups layout) source-id))
               base-layout (if in-same?
                             layout
                             (strip-space-from-layout layout source-id))
               next-layout (update-in base-layout [:groups group-id :space-ids]
                                      insert-in-vec source-id target-id position)]
           (persist-user-layout db (cleanup-empty-groups next-layout))))

       :reorder-rail
       (if (or (nil? source-id)
               (and (= source-type target-type) (= source-id target-id)))
         {}
         (let [target-entry (when (and target-type target-id)
                              {:type target-type :id target-id})
               source-entry {:type source-type :id source-id}
               base-layout  (if (= source-type :space)
                              (strip-space-from-layout layout source-id)
                              layout)
               next-layout  (update base-layout :order
                                    insert-in-vec source-entry target-entry position)]
           (persist-user-layout db (cleanup-empty-groups next-layout))))

       {}))))

(re-frame/reg-event-fx
 :space/save-hierarchy-cache
 (fn [{:keys [db]} [_ user-id space-id fresh-rooms]]
   (let [prev-rooms (get-in db [:space-hierarchies-cache user-id space-id])]
     (if (= prev-rooms fresh-rooms)
       {}
       (let [updated-cache (assoc-in (:space-hierarchies-cache db {})
                                     [user-id space-id]
                                     fresh-rooms)]
         (store/set-setting! "space_hierarchies" updated-cache)
         {:db (assoc db :space-hierarchies-cache updated-cache)})))))

(re-frame/reg-event-db
 :space/process-hierarchy
 (fn [db [_ _root-space-id rooms]]
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
                                 {}
                                 children-events)]
                  (assoc acc parent-id child-map))
                acc)))
          {}
          rooms)]
     (if (empty? hierarchy-orders)
       db
       (update db :space-orders merge hierarchy-orders)))))

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
             res  (<! (main/do-with-pool! pool {:handler   :fetch-space-hierarchy
                                                :arguments {:space-id space-id}}))]
         (when (= (:status res) "success")
           (let [fresh-rooms (:rooms res)]
             (when (seq fresh-rooms)
               (re-frame/dispatch [:space/save-hierarchy-cache
                                   current-user
                                   space-id
                                   fresh-rooms])
               (re-frame/dispatch [:space/process-hierarchy space-id fresh-rooms])
               (re-frame/dispatch [:rooms/cache-hierarchy-previews fresh-rooms]))))))
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
     (if (empty? new-previews)
       db
       (update db :room-previews #(merge-with merge (or % {}) new-previews))))))

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
         current-user  (:active-user-id db)
         current-eng   (:active-engine db)
         norm-space    (some-> space-id str not-empty)
         next-filter   (if norm-space (:active-filter-id db "people") "people")]
     (when (and current-eng current-user)
       (store/save-nav-state! current-eng current-user
                              {:space-id  norm-space
                               :room-id   nil
                               :filter-id next-filter}))
     (if (= current-space norm-space)
       (let [next-db (cond-> (assoc db :session/pending-restore nil)
                       (= (:main-focus db) :discovery)
                       (assoc :main-focus :timeline))]
         (if (nil? norm-space)
           {:db next-db
            :fx [[:dispatch [:room-list/apply-filter "people" {:force? true :persist? false}]]]}
           {:db next-db}))
       {:db         (cond-> (-> db
                                (assoc :active-space-id norm-space
                                       :active-room-id nil
                                       :session/pending-restore nil
                                       :session/pending-room-select nil))
                      (= (:main-focus db) :discovery)
                      (assoc :main-focus :timeline))
        :dispatch-n (if norm-space
                      [[:sdk/paginate-space norm-space]
                       [:sdk/fetch-room-emotes :space norm-space]
                       [:sdk/fetch-space-hierarchy norm-space]]
                      [[:room-list/apply-filter "people" {:force? true :persist? false}]])}))))

(re-frame/reg-sub :spaces/all (fn [db _] (get db :spaces-list [])))
(re-frame/reg-sub :spaces/map (fn [db _] (get db :spaces {})))
(re-frame/reg-sub :spaces/active-id (fn [db _] (:active-space-id db)))
(re-frame/reg-sub :space-children/map (fn [db _] (:space-children db)))
(re-frame/reg-sub :space-layouts/all (fn [db _] (get db :space-layouts {})))
(re-frame/reg-sub :auth/active-user-id-or-default (fn [db _] (active-layout-key db)))

(re-frame/reg-sub
 :spaces/user-layout
 :<- [:space-layouts/all]
 :<- [:auth/active-user-id-or-default]
 (fn [[all-layouts ukey] _]
   (get all-layouts ukey {:order [] :groups {}})))

(re-frame/reg-sub
 :spaces/groups-list
 :<- [:spaces/user-layout]
 (fn [{:keys [order groups]} _]
   (let [ordered-gids (keep #(when (= (:type %) :group) (:id %)) order)
         all-gids     (distinct (concat ordered-gids (keys groups)))]
     (into [] (keep #(get groups %)) all-gids))))

(re-frame/reg-sub
 :spaces/rail-items
 :<- [:spaces/all]
 :<- [:spaces/user-layout]
 (fn [[all-spaces {:keys [order groups]}] _]
   (let [live-by-id  (into {} (keep (fn [s] (when (:id s) [(:id s) s]))) all-spaces)
         grouped-ids (into #{} (mapcat :space-ids) (vals groups))
         step-1      (reduce
                      (fn [{:keys [items seen-spaces seen-groups]} {:keys [type id]}]
                        (case type
                          :group
                          (if (contains? seen-groups id)
                            {:items items :seen-spaces seen-spaces :seen-groups seen-groups}
                            (if-let [g (get groups id)]
                              (let [vis (into [] (keep live-by-id) (:space-ids g))]
                                (if (seq vis)
                                  {:items       (conj items (assoc g :type :group :spaces vis))
                                   :seen-spaces seen-spaces
                                   :seen-groups (conj seen-groups id)}
                                  {:items       items
                                   :seen-spaces seen-spaces
                                   :seen-groups (conj seen-groups id)}))
                              {:items items :seen-spaces seen-spaces :seen-groups seen-groups}))

                          :space
                          (if (or (contains? grouped-ids id)
                                  (contains? seen-spaces id)
                                  (not (contains? live-by-id id)))
                            {:items items :seen-spaces seen-spaces :seen-groups seen-groups}
                            {:items       (conj items {:type  :space
                                                       :id    id
                                                       :space (get live-by-id id)})
                             :seen-spaces (conj seen-spaces id)
                             :seen-groups seen-groups})

                          {:items items :seen-spaces seen-spaces :seen-groups seen-groups}))
                      {:items [] :seen-spaces #{} :seen-groups #{}}
                      order)
         step-2      (reduce
                      (fn [{:keys [items seen-spaces seen-groups]} [gid g]]
                        (if (contains? seen-groups gid)
                          {:items items :seen-spaces seen-spaces :seen-groups seen-groups}
                          (let [vis (into [] (keep live-by-id) (:space-ids g))]
                            {:items       (if (seq vis)
                                            (conj items (assoc g :type :group :spaces vis))
                                            items)
                             :seen-spaces seen-spaces
                             :seen-groups (conj seen-groups gid)})))
                      step-1
                      groups)
         final-items (reduce
                      (fn [acc s]
                        (let [sid (:id s)]
                          (if (or (nil? sid)
                                  (contains? grouped-ids sid)
                                  (contains? (:seen-spaces step-2) sid))
                            acc
                            (conj acc {:type :space :id sid :space s}))))
                      (:items step-2)
                      all-spaces)]
     final-items)))

(re-frame/reg-sub
 :spaces/active-metadata
 :<- [:spaces/active-id]
 :<- [:spaces/map]
 :<- [:spaces/all]
 (fn [[active-id spaces-map all-spaces] _]
   (when active-id
     (or (get spaces-map active-id)
         (some #(when (= (:id %) active-id) %) all-spaces)))))

(re-frame/reg-sub
 :space/children
 :<- [:space-children/map]
 :<- [:rooms/all]
 (fn [[rel-map all-rooms] [_ parent-id]]
   (let [child-refs  (get rel-map parent-id [])
         rich-lookup (into {} (map (fn [r] [(or (:id r) (.-roomId r)) r]) all-rooms))]
     (mapv (fn [{:keys [id is-space?]}]
             (let [rich-room (get rich-lookup id)]
               (merge {:id id :is-space? is-space? :name "Loading..."}
                      rich-room)))
           child-refs))))

(defn- compute-zone-from-rect [rect client-y drag-type allow-combine?]
  (let [height (max 1 (.-height rect))
        ratio  (/ (- client-y (.-top rect)) height)]
    (cond
      (and (= drag-type :space) allow-combine?)
      (cond
        (< ratio 0.25) :before
        (> ratio 0.75) :after
        :else          :combine)
      (< ratio 0.5) :before
      :else         :after)))

(defn- compute-hover-zone [e drag-type allow-combine?]
  (compute-zone-from-rect (.getBoundingClientRect (.-currentTarget e))
                          (.-clientY e)
                          drag-type
                          allow-combine?))

(defn- clear-drag-state! []
  (reset! !space-drag-state nil))

(defn- commit-drop! [tr target-ctx]
  (when-let [{:keys [drag-type source-id hover]} @!space-drag-state]
    (let [{:keys [zone]} hover
          {:keys [kind id group-id]} target-ctx
          default-name (tr [:navigation.spaces.bar/new-group-default])]
      (clear-drag-state!)
      (cond
        (= kind :tail)
        (re-frame/dispatch [:space-groups/apply-drop
                            {:op          :reorder-rail
                             :source-type drag-type
                             :source-id   source-id
                             :position    :end}])

        (and (= drag-type :space) (= kind :group-space))
        (re-frame/dispatch [:space-groups/apply-drop
                            {:op        :reorder-in-group
                             :group-id  group-id
                             :source-id source-id
                             :target-id id
                             :position  (if (= zone :before) :before :after)}])

        (and (= drag-type :space) (= kind :standalone-space) (= zone :combine))
        (re-frame/dispatch [:space-groups/apply-drop
                            {:op           :combine-spaces
                             :source-id    source-id
                             :target-id    id
                             :default-name default-name}])

        (and (= drag-type :space) (= kind :group) (= zone :combine))
        (re-frame/dispatch [:space-groups/apply-drop
                            {:op        :add-to-group
                             :group-id  id
                             :source-id source-id}])

        (and (= kind :group-header) (= drag-type :space) (= zone :after))
        (re-frame/dispatch [:space-groups/apply-drop
                            {:op        :add-to-group
                             :group-id  id
                             :source-id source-id}])

        :else
        (re-frame/dispatch [:space-groups/apply-drop
                            {:op          :reorder-rail
                             :source-type drag-type
                             :source-id   source-id
                             :target-type (if (#{:group :group-header} kind) :group :space)
                             :target-id   id
                             :position    (if (= zone :before) :before :after)}])))))

(defn- maybe-autoscroll-rail! [client-y]
  (when-let [scroller (.querySelector js/document ".spaces-scroll-area")]
    (let [rect      (.getBoundingClientRect scroller)
          edge-zone 48
          top-dist  (- client-y (.-top rect))
          bot-dist  (- (.-bottom rect) client-y)]
      (cond
        (and (>= top-dist -24) (< top-dist edge-zone))
        (set! (.-scrollTop scroller) (- (.-scrollTop scroller) 8))

        (and (>= bot-dist -24) (< bot-dist edge-zone))
        (set! (.-scrollTop scroller) (+ (.-scrollTop scroller) 8))))))

(defn- find-drop-target-el [client-x client-y]
  (when-let [els (.elementsFromPoint js/document client-x client-y)]
    (loop [idx 0]
      (when (< idx (.-length els))
        (let [el      (aget els idx)
              matched (when el (.closest el "[data-space-drop-kind]"))]
          (if matched
            matched
            (recur (inc idx))))))))

(defn- resolve-touch-hover [el client-y drag-type source-id]
  (when el
    (let [kind-str (.getAttribute el "data-space-drop-kind")
          kind     (when (and kind-str (not (str/blank? kind-str)))
                     (keyword kind-str))
          tid      (.getAttribute el "data-space-drop-id")
          gid-raw  (.getAttribute el "data-space-drop-group-id")
          gid      (when (and gid-raw (not (str/blank? gid-raw))) gid-raw)]
      (case kind
        :tail
        {:kind :tail}

        :standalone-space
        (when (and tid (not= tid source-id))
          {:kind      :standalone-space
           :target-id tid
           :zone      (compute-zone-from-rect (.getBoundingClientRect el)
                                              client-y
                                              drag-type
                                              true)})

        :group-space
        (when (and (= drag-type :space) tid gid (not= tid source-id))
          {:kind      :group-space
           :target-id tid
           :group-id  gid
           :zone      (compute-zone-from-rect (.getBoundingClientRect el)
                                              client-y
                                              drag-type
                                              false)})

        :group
        (when (and tid (not (and (= drag-type :group) (= tid source-id))))
          {:kind      :group
           :target-id tid
           :zone      (compute-zone-from-rect (.getBoundingClientRect el)
                                              client-y
                                              drag-type
                                              true)})

        :group-header
        (when (and tid (not (and (= drag-type :group) (= tid source-id))))
          (let [raw-zone (compute-zone-from-rect (.getBoundingClientRect el)
                                                 client-y
                                                 drag-type
                                                 true)]
            {:kind      :group-header
             :target-id tid
             :zone      (if (= raw-zone :after) :combine raw-zone)}))

        nil))))

(defn- update-touch-drag-move! [client-x client-y]
  (when-let [{:keys [drag-type source-id]} @!space-drag-state]
    (maybe-autoscroll-rail! client-y)
    (let [el         (find-drop-target-el client-x client-y)
          next-hover (resolve-touch-hover el client-y drag-type source-id)]
      (swap! !space-drag-state assoc
             :armed-only? false
             :touch-pos   {:x client-x :y client-y}
             :hover       next-hover))))

(defn- finish-touch-drag! [tr cancelled?]
  (if cancelled?
    (clear-drag-state!)
    (if-let [hover (and (not (:armed-only? @!space-drag-state))
                        (:hover @!space-drag-state))]
      (commit-drop! tr {:kind     (:kind hover)
                        :id       (:target-id hover)
                        :group-id (:group-id hover)})
      (clear-drag-state!))))

(defn- make-space-touch-drag-opts [tr id group-id final-name final-avatar]
  {:on-arm
   (fn [_x _y _el]
     (reset! !space-drag-state
             {:drag-type     :space
              :source-id     id
              :from-group-id group-id
              :armed-only?   true
              :ghost         {:id   id
                              :name final-name
                              :url  final-avatar}}))
   :on-drag-start
   (fn [x y _el]
     (reset! !space-drag-state
             {:drag-type     :space
              :source-id     id
              :from-group-id group-id
              :armed-only?   false
              :touch-pos     {:x x :y y}
              :ghost         {:id   id
                              :name final-name
                              :url  final-avatar}}))
   :on-drag-move
   (fn [x y]
     (update-touch-drag-move! x y))
   :on-drag-end
   (fn [_x _y cancelled?]
     (finish-touch-drag! tr cancelled?))})

(defn- make-group-touch-drag-opts [tr id group-name folder-color]
  {:on-arm
   (fn [_x _y _el]
     (reset! !space-drag-state
             {:drag-type   :group
              :source-id   id
              :armed-only? true
              :ghost       {:id    id
                            :name  group-name
                            :color folder-color}}))
   :on-drag-start
   (fn [x y _el]
     (reset! !space-drag-state
             {:drag-type   :group
              :source-id   id
              :armed-only? false
              :touch-pos   {:x x :y y}
              :ghost       {:id    id
                            :name  group-name
                            :color folder-color}}))
   :on-drag-move
   (fn [x y]
     (update-touch-drag-move! x y))
   :on-drag-end
   (fn [_x _y cancelled?]
     (finish-touch-drag! tr cancelled?))})

(defn ^:ui space-icon-item [item active-id & [{:keys [group-id]}]]
  (let [{:keys [id is-dm? is-group-dm? recipient-avatars]} item
        item-name          (:name item)
        raw-avatar         (space-avatar-url item)
        stats              (if is-dm? item @(re-frame/subscribe [:space/unread-stats id]))
        {:keys [unread? highlight? mentions msg-count]} stats
        badge-count        (if is-dm? msg-count mentions)
        profile            @(re-frame/subscribe [:sdk/profile])
        tr                 @(re-frame/subscribe [:i18n/tr])
        drag-state         @!space-drag-state
        my-id              (:user-id profile)
        needs-members?     (and is-dm?
                                (or (= item-name "Loading...")
                                    (and (not raw-avatar)
                                         (if is-group-dm?
                                           (empty? recipient-avatars)
                                           true))))
        members-map        (when is-dm? @(re-frame/subscribe [:room/members-map id]))
        members-loading?   (when is-dm? @(re-frame/subscribe [:room/members-loading? id]))
        members-attempted? (when is-dm? @(re-frame/subscribe [:room/members-attempted? id]))]
    (when (and id
               needs-members?
               (not members-loading?)
               (not members-attempted?)
               (empty? members-map))
      (re-frame/dispatch [:room/fetch-members id]))
    (let [{:keys [other-users group-dm?]
           final-avatar :avatar-url
           final-name   :display-name}
          (resolve-dm-display {:is-dm?                is-dm?
                               :is-group-dm?          is-group-dm?
                               :members-map           members-map
                               :my-id                 my-id
                               :avatar-url            raw-avatar
                               :name                  item-name
                               :only-replace-loading? true})
          group-members (when group-dm?
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
          target-kind  (if group-id :group-space :standalone-space)
          armed-only?  (boolean (:armed-only? drag-state))
          hover-zone   (when (and (not is-dm?)
                                  drag-state
                                  (not armed-only?)
                                  (not= (:source-id drag-state) id)
                                  (or (not= (:drag-type drag-state) :group)
                                      (nil? group-id))
                                  (= (get-in drag-state [:hover :target-id]) id)
                                  (= (get-in drag-state [:hover :kind]) target-kind))
                         (get-in drag-state [:hover :zone]))
          same-src?    (and (not is-dm?)
                            (= (:drag-type drag-state) :space)
                            (= (:source-id drag-state) id))
          armed-me?    (and same-src? armed-only?)
          dragging-me? (and same-src? (not armed-only?))
          open-menu!   (fn [mx my]
                         (if is-dm?
                           (re-frame/dispatch
                            [:ui/open-context-menu :room-actions
                             {:x          mx
                              :y          my
                              :room-id    id
                              :room-name  final-name
                              :is-space?  false
                              :is-dm?     true
                              :context    :list
                              :membership "joined"}])
                           (re-frame/dispatch
                            [:ui/open-context-menu :space-bar-actions
                             {:x          mx
                              :y          my
                              :space-id   id
                              :space-name final-name
                              :group-id   group-id}])))
          touch-drag   (when-not is-dm?
                         (make-space-touch-drag-opts tr id group-id final-name final-avatar))
          drag-src-props (when-not is-dm?
                           {:draggable true
                            :on-drag-start
                            (fn [e]
                              (.stopPropagation e)
                              (let [el (.-currentTarget e)]
                                (when-let [dt (.-dataTransfer e)]
                                  (let [rect (.getBoundingClientRect el)
                                        ox   (- (.-clientX e) (.-left rect))
                                        oy   (- (.-clientY e) (.-top rect))]
                                    (set! (.-effectAllowed dt) "move")
                                    (.setData dt "text/plain" (str id))
                                    (.setDragImage dt el ox oy))))
                              (reset! !space-drag-state
                                      {:drag-type     :space
                                       :source-id     id
                                       :from-group-id group-id}))
                            :on-drag-end
                            (fn [_] (clear-drag-state!))})
          drop-tgt-props (when-not is-dm?
                           {:data-space-drop-kind     (name target-kind)
                            :data-space-drop-id       id
                            :data-space-drop-group-id group-id
                            :on-drag-over
                            (fn [e]
                              (when-let [{:keys [drag-type source-id]} @!space-drag-state]
                                (when (and (not= source-id id)
                                           (or (= drag-type :space) (nil? group-id)))
                                  (.preventDefault e)
                                  (.stopPropagation e)
                                  (let [zone (compute-hover-zone e drag-type (nil? group-id))]
                                    (when (not= (:hover @!space-drag-state)
                                                {:kind target-kind :target-id id :zone zone})
                                      (swap! !space-drag-state assoc :hover
                                             {:kind      target-kind
                                              :target-id id
                                              :zone      zone}))))))
                            :on-drop
                            (fn [e]
                              (.preventDefault e)
                              (.stopPropagation e)
                              (commit-drop! tr {:kind     target-kind
                                                :id       id
                                                :group-id group-id}))})]
      [:div.space-icon-wrapper
       (merge
        (long-press-props open-menu! touch-drag)
        drop-tgt-props
        {:class    [(when (= id active-id) "active")
                    (when unread? "has-unread")
                    (when highlight? "has-highlight")
                    (when is-dm? "is-dm-entry")
                    (when group-id "in-space-folder")
                    (when armed-me? "is-touch-armed")
                    (when dragging-me? "is-dragging")
                    (when hover-zone (str "drop-" (name hover-zone)))]
         :on-click #(if is-dm?
                      (do
                        (re-frame/dispatch [:space/select nil])
                        (re-frame/dispatch [:rooms/select id]))
                      (re-frame/dispatch [:space/select id]))
         :title    final-name})
       (when (and unread? (not= id active-id))
         [:div.space-notification-pill])
       [:div.space-icon-draggable
        drag-src-props
        [avatar (cond-> {:id    id
                         :name  final-name
                         :url   final-avatar
                         :size  42
                         :shape :squircle}
                  group-dm? (assoc :is-group? true
                                   :group-members group-members))]]
       (when (and badge-count (pos? badge-count))
         [:div.space-mention-badge badge-count])])))

(defn ^:ui space-folder-item [group active-id]
  (let [{:keys [id color collapsed? spaces]} group
        group-name     (:name group)
        folder-color   (or color (first group-palette))
        stats-map      @(re-frame/subscribe [:spaces/unread-stats-map])
        tr             @(re-frame/subscribe [:i18n/tr])
        drag-state     @!space-drag-state
        unread?        (boolean (some #(:unread? (get stats-map (:id %))) spaces))
        highlight?     (boolean (some #(:highlight? (get stats-map (:id %))) spaces))
        mentions       (reduce + 0 (map #(or (:mentions (get stats-map (:id %))) 0) spaces))
        active-inside? (boolean (some #(= (:id %) active-id) spaces))
        armed-only?    (boolean (:armed-only? drag-state))
        same-src?      (and (= (:drag-type drag-state) :group)
                            (= (:source-id drag-state) id))
        armed-me?      (and same-src? armed-only?)
        dragging-me?   (and same-src? (not armed-only?))
        open-menu!     (fn [mx my]
                         (re-frame/dispatch
                          [:ui/open-context-menu :space-group-actions
                           {:x          mx
                            :y          my
                            :group-id   id
                            :group-name group-name
                            :space-ids  (mapv :id spaces)
                            :collapsed? collapsed?}]))
        touch-drag     (make-group-touch-drag-opts tr id group-name folder-color)
        drag-src-props {:draggable true
                        :on-drag-start
                        (fn [e]
                          (.stopPropagation e)
                          (let [el (.-currentTarget e)]
                            (when-let [dt (.-dataTransfer e)]
                              (let [rect (.getBoundingClientRect el)
                                    ox   (- (.-clientX e) (.-left rect))
                                    oy   (- (.-clientY e) (.-top rect))]
                                (set! (.-effectAllowed dt) "move")
                                (.setData dt "text/plain" (str id))
                                (.setDragImage dt el ox oy))))
                          (reset! !space-drag-state {:drag-type :group
                                                     :source-id id}))
                        :on-drag-end
                        (fn [_] (clear-drag-state!))}
        make-drop-props (fn [target-kind allow-after?]
                          {:data-space-drop-kind (name target-kind)
                           :data-space-drop-id   id
                           :on-drag-over
                           (fn [e]
                             (when-let [{:keys [drag-type source-id]} @!space-drag-state]
                               (when-not (and (= drag-type :group) (= source-id id))
                                 (.preventDefault e)
                                 (.stopPropagation e)
                                 (let [raw-zone (compute-hover-zone e drag-type true)
                                       zone     (if (and (not allow-after?) (= raw-zone :after))
                                                  :combine
                                                  raw-zone)]
                                   (when (not= (:hover @!space-drag-state)
                                               {:kind target-kind :target-id id :zone zone})
                                     (swap! !space-drag-state assoc :hover
                                            {:kind      target-kind
                                             :target-id id
                                             :zone      zone}))))))
                           :on-drop
                           (fn [e]
                             (.preventDefault e)
                             (.stopPropagation e)
                             (commit-drop! tr {:kind target-kind :id id}))})]
    (if collapsed?
      (let [hover-zone (when (and drag-state
                                  (not armed-only?)
                                  (not dragging-me?)
                                  (= (get-in drag-state [:hover :target-id]) id)
                                  (= (get-in drag-state [:hover :kind]) :group))
                         (get-in drag-state [:hover :zone]))]
        [:div.space-icon-wrapper.space-folder-closed-wrapper
         (merge
          (long-press-props open-menu! touch-drag)
          (make-drop-props :group true)
          {:class    [(when active-inside? "active")
                      (when unread? "has-unread")
                      (when highlight? "has-highlight")
                      (when armed-me? "is-touch-armed")
                      (when dragging-me? "is-dragging")
                      (when hover-zone (str "drop-" (name hover-zone)))]
           :on-click #(re-frame/dispatch [:space-groups/toggle id])
           :title    group-name})
         (when (and unread? (not active-inside?))
           [:div.space-notification-pill])
         [:div.space-icon-draggable
          drag-src-props
          [:div.space-folder-closed
           {:style {:background-color (hex->rgba folder-color 0.26)}}
           [:div.space-folder-grid
            (for [s (take 4 spaces)]
              ^{:key (:id s)}
              [avatar {:id    (:id s)
                       :name  (:name s)
                       :url   (space-avatar-url s)
                       :size  16
                       :shape :space}])]]]
         (when (pos? mentions)
           [:div.space-mention-badge mentions])])
      (let [hover-zone (when (and drag-state
                                  (not armed-only?)
                                  (not dragging-me?)
                                  (= (get-in drag-state [:hover :target-id]) id)
                                  (= (get-in drag-state [:hover :kind]) :group-header))
                         (get-in drag-state [:hover :zone]))]
        [:div.space-folder-expanded
         {:class [(when armed-me? "is-touch-armed")
                  (when dragging-me? "is-dragging")]}
         [:div.space-folder-capsule-bg
          {:style {:background-color (hex->rgba folder-color 0.12)
                   :border-color     (hex->rgba folder-color 0.25)}}]
         [:div.space-icon-wrapper.space-folder-header
          (merge
           (long-press-props open-menu! touch-drag)
           (make-drop-props :group-header false)
           {:class    [(when armed-me? "is-touch-armed")
                       (when hover-zone (str "drop-" (name hover-zone)))]
            :on-click #(re-frame/dispatch [:space-groups/toggle id])
            :title    group-name})
          [:div.space-icon-draggable
           drag-src-props
           [:div.space-folder-open-btn
            {:style {:background-color (hex->rgba folder-color 0.24)
                     :color            folder-color}}
            [icons/folder-open {:size "20px"}]]]]
         [:div.space-folder-items
          (for [s spaces]
            ^{:key (:id s)}
            [space-icon-item s active-id {:group-id id}])]]))))

(defn- open-space-group-modal! [props]
  (re-frame/dispatch
   [:ui/open-modal :space-group-modal
    (merge {:backdrop-props {:class "settings-backdrop"}
            :window-props   {:class "space-group-modal-window"}}
           props)]))

(defn- open-create-space-modal! []
  (re-frame/dispatch
   [:ui/open-modal :create-space
    {:backdrop-props {:class "lightbox-backdrop"}
     :window-props   {:class "settings-window"
                      :style {:display         "flex"
                              :flex-direction  "column"
                              :align-items     "center"
                              :justify-content "center"
                              :gap             "16px"}}}]))

(defn- open-quick-switcher-modal! []
  (re-frame/dispatch
   [:ui/open-modal :quick-switcher
    {:backdrop-props {:class "lightbox-backdrop"}
     :window-props   {:style {:background "transparent"
                              :box-shadow "none"}}}]))

(defn ^:ui space-bar-context-menu [{:keys [space-id space-name group-id close-fn]}]
  (let [tr          @(re-frame/subscribe [:i18n/tr])
        groups      @(re-frame/subscribe [:spaces/groups-list])
        other-grps  (remove #(= (:id %) group-id) groups)
        finish!     (fn [f]
                      (when f (f))
                      (if close-fn
                        (close-fn)
                        (re-frame/dispatch [:ui/close-context-menu])))]
    [:<>
     [engine-room-option-items
      {:room-id       space-id
       :room-name     space-name
       :is-space?     true
       :is-dm?        false
       :context       :list
       :membership    "joined"
       :on-item-click finish!}]
     [:div.context-menu-divider]
     [context-menu-submenu
      {:id              (str "move-to-" space-id)
       :icon            [icons/folder {:size "16px"}]
       :label           (tr [:navigation.spaces.bar/move-to])
       :close-fn        close-fn
       :render-children (fn [_]
                          [:<>
                           (for [g other-grps]
                             ^{:key (:id g)}
                             [:div.context-menu-item
                              {:on-click (fn [e]
                                           (.stopPropagation e)
                                           (finish! #(re-frame/dispatch
                                                      [:space-groups/add-space (:id g) space-id])))}
                              [:span.item-icon {:style {:color (:color g)}}
                               [icons/folder {:size "16px"}]]
                              [:span.item-label (:name g)]])
                           (when group-id
                             [:<>
                              (when (seq other-grps)
                                [:div.context-menu-divider])
                              [:div.context-menu-item
                               {:on-click (fn [e]
                                            (.stopPropagation e)
                                            (finish! #(re-frame/dispatch
                                                       [:space-groups/remove-space space-id])))}
                               [:span.item-icon [icons/folder-minus {:size "16px"}]]
                               [:span.item-label (tr [:navigation.spaces.bar/remove-from-group])]]])
                           (when (or (seq other-grps) group-id)
                             [:div.context-menu-divider])
                           [:div.context-menu-item
                            {:on-click (fn [e]
                                         (.stopPropagation e)
                                         (finish! #(open-space-group-modal!
                                                    {:mode              :create
                                                     :initial-space-ids [space-id]})))}
                            [:span.item-icon [icons/folder-plus {:size "16px"}]]
                            [:span.item-label (tr [:navigation.spaces.bar/create-group])]]])}]]))

(defmethod context-menu-component :space-bar-actions [_]
  space-bar-context-menu)

(defn ^:ui spaces-home-context-menu [{:keys [close-fn]}]
  (let [tr         @(re-frame/subscribe [:i18n/tr])
        active-dms @(re-frame/subscribe [:rooms/active-dm-pops])
        all-rooms  @(re-frame/subscribe [:rooms/all])
        dm-ids     (->> (concat active-dms
                                (filter #(or (:is-dm? %) (:is_direct %)) all-rooms))
                        (keep :id)
                        distinct
                        vec)
        finish!    (fn [f]
                     (when f (f))
                     (if close-fn
                       (close-fn)
                       (re-frame/dispatch [:ui/close-context-menu])))]
    [:<>
     (when (seq dm-ids)
       [:div.context-menu-item
        {:on-click (fn [e]
                     (.stopPropagation e)
                     (finish! #(doseq [rid dm-ids]
                                 (re-frame/dispatch [:rooms/mark-read rid]))))}
        [:span.item-icon [icons/check {:size "16px"}]]
        [:span.item-label (tr [:navigation.spaces.bar/mark-all-dms-read])]])
     [:div.context-menu-item
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (finish! #(re-frame/dispatch [:dm/open-create-modal])))}
      [:span.item-icon [icons/chat-bubble {:size "16px"}]]
      [:span.item-label (tr [:navigation.spaces.bar/new-dm])]]
     [:div.context-menu-item
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (finish! open-quick-switcher-modal!))}
      [:span.item-icon [icons/search {:size "16px"}]]
      [:span.item-label (tr [:navigation.spaces.bar/quick-switcher])]]
     [:div.context-menu-item
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (finish! #(re-frame/dispatch [:discovery/open])))}
      [:span.item-icon [icons/compass {:size "16px"}]]
      [:span.item-label (tr [:navigation.spaces.bar/discover])]]
     [:div.context-menu-divider]
     [:div.context-menu-item
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (finish! open-create-space-modal!))}
      [:span.item-icon [icons/plus-circle {:size "16px"}]]
      [:span.item-label (tr [:navigation.actions/create-space])]]
     [:div.context-menu-item
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (finish! #(re-frame/dispatch [:settings/open])))}
      [:span.item-icon [icons/settings {:size "16px"}]]
      [:span.item-label (tr [:navigation.actions/settings])]]]))

(defmethod context-menu-component :spaces-home-actions [_]
  spaces-home-context-menu)

(defn ^:ui spaces-rail-context-menu [{:keys [close-fn]}]
  (let [tr              @(re-frame/subscribe [:i18n/tr])
        all-spaces      @(re-frame/subscribe [:spaces/all])
        groups          @(re-frame/subscribe [:spaces/groups-list])
        space-ids       (into [] (keep :id) all-spaces)
        any-collapsed?  (boolean (some :collapsed? groups))
        any-expanded?   (boolean (some (complement :collapsed?) groups))
        has-bulk-items? (or any-collapsed? any-expanded? (seq space-ids))
        finish!         (fn [f]
                          (when f (f))
                          (if close-fn
                            (close-fn)
                            (re-frame/dispatch [:ui/close-context-menu])))]
    [:<>
     [:div.context-menu-item
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (finish! open-create-space-modal!))}
      [:span.item-icon [icons/plus-circle {:size "16px"}]]
      [:span.item-label (tr [:navigation.actions/create-space])]]
     (when (seq space-ids)
       [:div.context-menu-item
        {:on-click (fn [e]
                     (.stopPropagation e)
                     (finish! #(open-space-group-modal! {:mode :create})))}
        [:span.item-icon [icons/folder-plus {:size "16px"}]]
        [:span.item-label (tr [:navigation.spaces.bar/create-group])]])
     [:div.context-menu-item
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (finish! #(re-frame/dispatch [:discovery/open])))}
      [:span.item-icon [icons/compass {:size "16px"}]]
      [:span.item-label (tr [:navigation.spaces.bar/discover])]]
     (when has-bulk-items?
       [:div.context-menu-divider])
     (when any-collapsed?
       [:div.context-menu-item
        {:on-click (fn [e]
                     (.stopPropagation e)
                     (finish! #(re-frame/dispatch [:space-groups/set-all-collapsed false])))}
        [:span.item-icon [icons/folder-open {:size "16px"}]]
        [:span.item-label (tr [:navigation.spaces.bar/expand-all-groups])]])
     (when any-expanded?
       [:div.context-menu-item
        {:on-click (fn [e]
                     (.stopPropagation e)
                     (finish! #(re-frame/dispatch [:space-groups/set-all-collapsed true])))}
        [:span.item-icon [icons/folder {:size "16px"}]]
        [:span.item-label (tr [:navigation.spaces.bar/collapse-all-groups])]])
     (when (seq space-ids)
       [:div.context-menu-item
        {:on-click (fn [e]
                     (.stopPropagation e)
                     (finish! #(doseq [sid space-ids]
                                 (re-frame/dispatch [:rooms/mark-read sid]))))}
        [:span.item-icon [icons/check {:size "16px"}]]
        [:span.item-label (tr [:navigation.spaces.bar/mark-all-spaces-read])]])]))

(defmethod context-menu-component :spaces-rail-actions [_]
  spaces-rail-context-menu)

(defn ^:ui space-group-context-menu [{:keys [group-id space-ids collapsed? close-fn]}]
  (let [tr      @(re-frame/subscribe [:i18n/tr])
        finish! (fn [f]
                  (when f (f))
                  (if close-fn
                    (close-fn)
                    (re-frame/dispatch [:ui/close-context-menu])))]
    [:<>
     (when (seq space-ids)
       [:div.context-menu-item
        {:on-click (fn [e]
                     (.stopPropagation e)
                     (finish! #(doseq [sid space-ids]
                                 (re-frame/dispatch [:rooms/mark-read sid]))))}
        [:span.item-icon [icons/check {:size "16px"}]]
        [:span.item-label (tr [:navigation.actions/mark-read])]])
     [:div.context-menu-item
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (finish! #(re-frame/dispatch [:space-groups/toggle group-id])))}
      [:span.item-icon (if collapsed?
                         [icons/folder-open {:size "16px"}]
                         [icons/folder {:size "16px"}])]
      [:span.item-label (if collapsed?
                          (tr [:navigation.spaces.bar/expand-group])
                          (tr [:navigation.spaces.bar/collapse-group]))]]
     [:div.context-menu-item
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (finish! #(open-space-group-modal! {:mode     :edit
                                                       :group-id group-id})))}
      [:span.item-icon [icons/edit {:size "16px"}]]
      [:span.item-label (tr [:navigation.spaces.bar/edit-group])]]
     [:div.context-menu-divider]
     [:div.context-menu-item.danger
      {:on-click (fn [e]
                   (.stopPropagation e)
                   (finish! #(re-frame/dispatch [:space-groups/ungroup group-id])))}
      [:span.item-icon [icons/trash {:size "16px"}]]
      [:span.item-label (tr [:navigation.spaces.bar/ungroup])]]]))

(defmethod context-menu-component :space-group-actions [_]
  space-group-context-menu)

(defn ^:ui space-group-modal-content [{:keys [mode group-id initial-space-ids]}]
  (let [layout   @(re-frame/subscribe [:spaces/user-layout])
        existing (when (= mode :edit) (get-in layout [:groups group-id]))]
    (r/with-let [!name     (r/atom (or (:name existing) ""))
                 !color    (r/atom (or (:color existing) (first group-palette)))
                 !selected (r/atom (vec (or (:space-ids existing) initial-space-ids [])))]
      (let [tr         @(re-frame/subscribe [:i18n/tr])
            all-spaces @(re-frame/subscribe [:spaces/all])
            close!     #(re-frame/dispatch [:ui/close-modal])
            sel-set    (set @!selected)
            submit!    (fn []
                         (if (= mode :edit)
                           (re-frame/dispatch [:space-groups/update
                                               {:group-id  group-id
                                                :name      @!name
                                                :color     @!color
                                                :space-ids @!selected}])
                           (re-frame/dispatch [:space-groups/create
                                               {:name      @!name
                                                :color     @!color
                                                :space-ids @!selected}]))
                         (close!))]
        [:div.space-group-modal
         [:h3.space-group-modal-title
          (if (= mode :edit)
            (tr [:navigation.spaces.bar/modal-edit-title])
            (tr [:navigation.spaces.bar/modal-create-title]))]
         [:div.space-group-field
          [:label (tr [:navigation.spaces.bar/group-name-label])]
          [:input.space-group-input
           {:type        "text"
            :auto-focus  true
            :placeholder (tr [:navigation.spaces.bar/group-name-placeholder])
            :value       @!name
            :on-change   #(reset! !name (.. % -target -value))
            :on-key-down #(when (and (= (.-key %) "Enter") (seq @!selected))
                            (submit!))}]]
         [:div.space-group-field
          [:label (tr [:navigation.spaces.bar/group-color-label])]
          [:div.space-group-swatches
           (for [c group-palette]
             ^{:key c}
             [:button.space-group-swatch
              {:type     "button"
               :class    (when (= c @!color) "selected")
               :style    {:background-color c}
               :on-click #(reset! !color c)}
              (when (= c @!color)
                [icons/check {:size "14px"}])])]]
         [:div.space-group-field
          [:label (tr [:navigation.spaces.bar/group-spaces-label])]
          (if (empty? all-spaces)
            [:div.space-group-empty (tr [:navigation.spaces.bar/no-spaces])]
            [:div.space-group-checklist
             (for [s all-spaces
                   :let [sid      (:id s)
                         checked? (contains? sel-set sid)]
                   :when sid]
               ^{:key sid}
               [:div.space-group-check-row
                {:class    (when checked? "checked")
                 :on-click #(swap! !selected
                                   (fn [curr]
                                     (if (some (fn [x] (= x sid)) curr)
                                       (filterv (fn [x] (not= x sid)) curr)
                                       (conj (vec curr) sid))))}
                [avatar {:id    sid
                         :name  (:name s)
                         :url   (space-avatar-url s)
                         :size  28
                         :shape :squircle}]
                [:span.space-group-check-name (:name s)]
                [:div.space-group-checkbox
                 (when checked? [icons/check {:size "12px"}])]])])]
         [:div.space-group-modal-actions
          (when (= mode :edit)
            [:button.space-group-btn.danger-outline
             {:type     "button"
              :on-click (fn []
                          (re-frame/dispatch [:space-groups/ungroup group-id])
                          (close!))}
             (tr [:navigation.spaces.bar/ungroup])])
          [:div.space-group-modal-actions-right
           [:button.space-group-btn.secondary
            {:type "button" :on-click close!}
            (tr [:navigation.spaces.bar/cancel])]
           [:button.space-group-btn.primary
            {:type     "button"
             :disabled (and (not= mode :edit) (empty? @!selected))
             :on-click submit!}
            (if (= mode :edit)
              (tr [:navigation.spaces.bar/save])
              (tr [:navigation.spaces.bar/create]))]]]]))))

(defmethod modal-component :space-group-modal [_]
  space-group-modal-content)

(defn ^:ui spaces-rail-items [rail-items active-space-id]
  (let [tr         @(re-frame/subscribe [:i18n/tr])
        drag-state @!space-drag-state
        tail-over? (= (get-in drag-state [:hover :kind]) :tail)]
    [:div.spaces-rail-list
     (for [entry rail-items]
       (if (= (:type entry) :group)
         ^{:key (str "grp-" (:id entry))}
         [space-folder-item entry active-space-id]
         ^{:key (str "spc-" (:id entry))}
         [space-icon-item (:space entry) active-space-id nil]))
     [:div.spaces-tail-dropzone
      {:class                (when tail-over? "drop-tail-active")
       :data-space-drop-kind "tail"
       :on-drag-over
       (fn [e]
         (when @!space-drag-state
           (.preventDefault e)
           (when (not= (:hover @!space-drag-state) {:kind :tail})
             (swap! !space-drag-state assoc :hover {:kind :tail}))))
       :on-drop
       (fn [e]
         (.preventDefault e)
         (.stopPropagation e)
         (commit-drop! tr {:kind :tail}))}]]))

(defn ^:ui space-touch-drag-ghost []
  (let [drag-state @!space-drag-state
        touch-pos  (:touch-pos drag-state)
        ghost      (:ghost drag-state)
        drag-type  (:drag-type drag-state)]
    (when (and touch-pos ghost (not (:armed-only? drag-state)))
      [:div.space-touch-drag-ghost
       {:style {:left (str (:x touch-pos) "px")
                :top  (str (:y touch-pos) "px")}}
       (if (= drag-type :group)
         [:div.space-folder-open-btn
          {:style {:background-color (hex->rgba (:color ghost) 0.28)
                   :color            (or (:color ghost) (first group-palette))}}
          [icons/folder {:size "22px"}]]
         [avatar {:id    (:id ghost)
                  :name  (:name ghost)
                  :url   (:url ghost)
                  :size  42
                  :shape :squircle}])])))

(defn ^:ui virtualized-space-bar [spaces active-space-id]
  (let [items (mapv (fn [s] {:type :space :id (:id s) :space s}) spaces)]
    [spaces-rail-items items active-space-id]))

(defn- rail-background-target? [e]
  (let [tgt (.-target e)]
    (or (nil? tgt)
        (not (fn? (.-closest tgt)))
        (nil? (.closest tgt ".space-icon-wrapper, .space-folder-expanded, button, a")))))

(defn- rail-background-menu-props [open-bar-menu!]
  (let [lp (long-press-props open-bar-menu!)]
    (reduce-kv
     (fn [acc k f]
       (if (= k :on-click-capture)
         (assoc acc k f)
         (assoc acc k (fn [e]
                        (when (rail-background-target? e)
                          (f e))))))
     {}
     lp)))

(defn ^:ui spaces-sidebar []
  (let [rail-items      @(re-frame/subscribe [:spaces/rail-items])
        active-id       @(re-frame/subscribe [:spaces/active-id])
        active-dms      @(re-frame/subscribe [:rooms/active-dm-pops])
        main-focus      @(re-frame/subscribe [:container/main-focus])
        in-discover?    (= main-focus :discovery)
        tr              @(re-frame/subscribe [:i18n/tr])
        open-home-menu! (fn [mx my]
                          (re-frame/dispatch
                           [:ui/open-context-menu :spaces-home-actions
                            {:x mx :y my}]))
        open-rail-menu! (fn [mx my]
                          (re-frame/dispatch
                           [:ui/open-context-menu :spaces-rail-actions
                            {:x mx :y my}]))]
    [:div.sidebar-spaces
     (rail-background-menu-props open-rail-menu!)
     [:div.sidebar-spaces-top
      [:div.space-icon-wrapper
       (merge
        (long-press-props open-home-menu!)
        {:class    (when (and (nil? active-id) (not in-discover?)) "active")
         :on-click #(re-frame/dispatch [:space/select nil])
         :title    (tr [:navigation.spaces.bar/home])})
       [:div.home-icon-container
        [icons/sun {:animate :sun-pulse :size "32px"}]]]
      [:div.sidebar-divider]]
     [:div.spaces-scroll-area
      (when (seq active-dms)
        [:div.dm-pops-group
         (for [dm active-dms]
           ^{:key (:id dm)}
           [space-icon-item dm (when-not in-discover? active-id) nil])
         [:div.sidebar-divider]])
      [spaces-rail-items rail-items (when-not in-discover? active-id)]]
     [:div.sidebar-spaces-bottom
      [:div.sidebar-divider]
      [:div.space-icon-wrapper
       {:class    (when in-discover? "active")
        :on-click #(re-frame/dispatch [:discovery/open])
        :title    (tr [:navigation.spaces.bar/discover])}
       [:div.discover-icon-container
        [icons/compass {:size "22px"}]]]]
     [space-touch-drag-ghost]]))