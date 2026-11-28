(ns paradise.ui.overlays.tray
  (:require
   [paradise.shared.client.key-handler :as key-handler]
   [paradise.shared.utils.helpers :refer [normalize-id]]
   [re-frame.core :as re-frame]
   [re-frame.db :as db]))

(defn- get-tray ^js []
  (.-paradiseTray js/globalThis))

(defn tray-supported? []
  (some? (get-tray)))

(defmulti tray-section
  (fn [section-id _section-state] section-id))

(defmethod tray-section :default [_ _]
  nil)

(defmulti tray-action! identity)

(defmethod tray-action! :default [action-id]
  (key-handler/trigger-action! action-id))

(defonce !registry (atom {}))
(defonce !item-dispatches (atom {}))
(defonce !last-tray-spec (atom nil))

(defn all-tray-sections [sections-state]
  (let [mm-entries (loop [entries (seq (methods tray-section))
                          acc     {}]
                     (if entries
                       (let [[dispatch-val method-fn] (first entries)]
                         (if (= dispatch-val :default)
                           (recur (next entries) acc)
                           (let [sec-state (get sections-state dispatch-val {})
                                 spec      (method-fn dispatch-val sec-state)]
                             (recur (next entries)
                                    (if spec
                                      (assoc acc dispatch-val (assoc spec :id dispatch-val))
                                      acc)))))
                       acc))]
    (merge mm-entries @!registry)))

(defn- section-active? [s]
  (and (not= (:enabled? s) false)
       (boolean (seq (:items s)))))

(defn- section-order [s]
  (or (:order s) 50))

(defn- sanitize-item [item]
  (dissoc item :dispatch))

(defn- collect-tray-spec [sections-state]
  (let [active      (->> (vals (all-tray-sections (or sections-state {})))
                         (filter section-active?)
                         (sort-by section-order))
        tooltip     (or (some :tooltip active) "Paradise")
        raw-items   (->> (map :items active)
                         (interpose [{:type "separator"}])
                         (apply concat)
                         vec)
        dispatches  (loop [xs  (seq raw-items)
                           acc {}]
                      (if xs
                        (let [{:keys [id dispatch]} (first xs)]
                          (recur (next xs)
                                 (if (and id dispatch)
                                   (assoc acc (normalize-id id) dispatch)
                                   acc)))
                        acc))
        clean-items (mapv sanitize-item raw-items)]
    {:tooltip    tooltip
     :items      clean-items
     :dispatches dispatches}))

(defn sync-tray! [sections-state]
  (when-let [^js tray (get-tray)]
    (let [{:keys [tooltip items dispatches]} (collect-tray-spec sections-state)
          payload                            {:tooltip tooltip :items items}]
      (reset! !item-dispatches dispatches)
      (when (not= payload @!last-tray-spec)
        (reset! !last-tray-spec payload)
        (.setTooltip tray tooltip)
        (.setMenu tray (clj->js items))))))

(defn register-tray-section!
  ([{:keys [id] :as spec}]
   (register-tray-section! id spec))
  ([section-id spec]
   (let [key-id (or section-id (:id spec))]
     (when key-id
       (if (map? spec)
         (swap! !registry assoc key-id (assoc spec :id key-id))
         (swap! !registry dissoc key-id))
       (when (exists? db/app-db)
         (sync-tray! (get-in @db/app-db [:tray :sections] {})))
       key-id))))

(defn unregister-tray-section! [section-id]
  (swap! !registry dissoc section-id)
  (when (exists? db/app-db)
    (sync-tray! (get-in @db/app-db [:tray :sections] {}))))

(defn sync-close-to-tray! [enabled?]
  (when-let [^js tray (get-tray)]
    (when (fn? (.-setCloseToTray tray))
      (.setCloseToTray tray (boolean enabled?)))))

(defn update-section-fx [fx section-id section-state]
  (let [db            (:db fx)
        prev-sections (get-in db [:tray :sections] {})
        prev-state    (get prev-sections section-id)]
    (if (= prev-state section-state)
      fx
      (let [next-sections (if (some? section-state)
                            (assoc prev-sections section-id section-state)
                            (dissoc prev-sections section-id))
            next-db       (assoc-in db [:tray :sections] next-sections)]
        (-> fx
            (assoc :db next-db)
            (assoc :tray/sync-electron next-sections))))))

(re-frame/reg-fx
 :tray/sync-electron
 (fn [sections-state]
   (sync-tray! sections-state)))

(re-frame/reg-fx
 :tray/sync-close-behavior
 (fn [enabled?]
   (sync-close-to-tray! enabled?)))

(re-frame/reg-event-fx
 :settings/hydrate-close-to-tray
 (fn [{:keys [db]} [_ saved-val]]
   (let [enabled? (if (boolean? saved-val) saved-val true)]
     {:db                       (assoc db :settings/close-to-tray enabled?)
      :tray/sync-close-behavior enabled?})))

(re-frame/reg-event-fx
 :settings/set-close-to-tray
 (fn [{:keys [db]} [_ enabled?]]
   (let [val (boolean enabled?)]
     {:db                       (assoc db :settings/close-to-tray val)
      :tray/sync-close-behavior val
      :dispatch                 [:settings/save "close_to_tray" val]})))

(re-frame/reg-sub
 :settings/close-to-tray?
 (fn [db _]
   (get db :settings/close-to-tray true)))

(re-frame/reg-event-fx
 :tray/set-section
 (fn [{:keys [db]} [_ section-id section-state]]
   (update-section-fx {:db db} section-id section-state)))

(re-frame/reg-event-fx
 :tray/clear-section
 (fn [{:keys [db]} [_ section-id]]
   (update-section-fx {:db db} section-id nil)))

(re-frame/reg-sub
 :tray/sections
 (fn [db _]
   (get-in db [:tray :sections] {})))

(defn trigger-tray-action! [action-id]
  (let [norm-id (normalize-id action-id)
        kw-id   (keyword norm-id)]
    (if-let [dispatch (get @!item-dispatches norm-id)]
      (re-frame/dispatch dispatch)
      (if (contains? (methods tray-action!) kw-id)
        (tray-action! kw-id)
        (tray-action! norm-id)))))

(defn init-tray! []
  (when-let [^js tray (get-tray)]
    (.onAction tray
               (fn [action-id]
                 (trigger-tray-action! action-id)))
    (when (exists? db/app-db)
      (sync-close-to-tray! (get @db/app-db :settings/close-to-tray true))
      (sync-tray! (get-in @db/app-db [:tray :sections] {})))))