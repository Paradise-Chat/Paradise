(ns paradise.ui.overlays.tray
  (:require
   [paradise.shared.client.key-handler :as key-handler]
   [re-frame.db :as db]))

(defn- get-tray ^js []
  (.-paradiseTray js/globalThis))

(defmulti tray-section
  (fn [section-id _db] section-id))

(defmethod tray-section :default [_ _]
  nil)

(defmulti tray-action! identity)

(defmethod tray-action! :default [action-id]
  (key-handler/trigger-action! action-id))

(defonce !dynamic-sections (atom {}))

(defn register-tray-section! [section-id builder-fn]
  (swap! !dynamic-sections assoc section-id builder-fn))

(defn unregister-tray-section! [section-id]
  (swap! !dynamic-sections dissoc section-id))

(defn- collect-tray-spec [db]
  (let [mm-results  (keep (fn [[dispatch-key method-fn]]
                            (when (not= dispatch-key :default)
                              (method-fn dispatch-key db)))
                          (methods tray-section))
        dyn-results (keep (fn [[_ builder-fn]]
                            (builder-fn db))
                          @!dynamic-sections)
        active      (->> (concat mm-results dyn-results)
                         (filter #(seq (:items %)))
                         (sort-by #(or (:order %) 50)))
        tooltip     (or (some :tooltip active) "Paradise")
        items       (->> (map :items active)
                         (interpose [{:type "separator"}])
                         (apply concat)
                         vec)]
    {:tooltip tooltip
     :items   items}))

(defonce !last-tray-spec (atom nil))

(defn sync-tray-from-db! [db]
  (when-let [^js tray (get-tray)]
    (let [spec (collect-tray-spec db)]
      (when (not= spec @!last-tray-spec)
        (reset! !last-tray-spec spec)
        (.setTooltip tray (:tooltip spec))
        (.setMenu tray (clj->js (:items spec)))))))

(defn init-tray! []
  (when-let [^js tray (get-tray)]
    (.onAction tray
               (fn [action-id]
                 (let [kw-id (keyword action-id)]
                   (if (contains? (methods tray-action!) kw-id)
                     (tray-action! kw-id)
                     (tray-action! (str action-id))))))
    (add-watch db/app-db ::tray-sync
               (fn [_ _ old-db new-db]
                 (when-not (identical? old-db new-db)
                   (sync-tray-from-db! new-db))))
    (sync-tray-from-db! @db/app-db)))
