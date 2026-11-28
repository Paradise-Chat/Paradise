(ns paradise.ui.overlays.room-notifications
  (:require
   [cljs-workers.mesh :as mesh]
   [cljs.core.async :refer [<! go]]
   [clojure.string :as str]
   [paradise.shared.utils.helpers :refer [decode-edn-field ok-status?]]
   [re-frame.core :as re-frame]
   [taoensso.timbre :as log]))

(re-frame/reg-sub
 :room-settings/notifications-state
 (fn [db [_ room-id]]
   (get-in db [:room-settings/notifs-by-id room-id]
           {:loading? false
            :saving?  false
            :data     nil
            :error    nil
            :saved?   false})))

(re-frame/reg-event-db
 :room-settings/clear-notifications-feedback
 (fn [db [_ room-id]]
   (update-in db [:room-settings/notifs-by-id room-id] dissoc :error :saved?)))

(re-frame/reg-event-db
 :room-settings/fetch-notifications-done
 (fn [db [_ room-id {:keys [data error]}]]
   (update-in db [:room-settings/notifs-by-id room-id]
              (fn [prev]
                (cond-> (assoc (or prev {}) :loading? false)
                  data  (assoc :data data :error nil)
                  error (assoc :error error))))))

(re-frame/reg-event-fx
 :room-settings/fetch-notifications
 (fn [{:keys [db]} [_ room-id]]
   (when room-id
     (go
       (try
         (let [res (<! (mesh/do-with-thread!
                        :engine-pool
                        {:handler   :fetch-room-notifications
                         :arguments {:room-id room-id}}))]
           (if (ok-status? res)
             (re-frame/dispatch
              [:room-settings/fetch-notifications-done room-id
               {:data (decode-edn-field res :notifications-edn :notifications)}])
             (re-frame/dispatch
              [:room-settings/fetch-notifications-done room-id
               {:error (or (:msg res) "Failed to load notification settings")}])))
         (catch :default e
           (re-frame/dispatch
            [:room-settings/fetch-notifications-done room-id {:error (str e)}])))))
   {:db (assoc-in db [:room-settings/notifs-by-id room-id :loading?] true)}))

(re-frame/reg-event-db
 :room-settings/save-notifications-done
 (fn [db [_ room-id {:keys [data error]}]]
   (update-in db [:room-settings/notifs-by-id room-id]
              (fn [prev]
                (cond-> (assoc (or prev {})
                               :saving? false
                               :saved?  (nil? error)
                               :error   error)
                  data (assoc :data data))))))

(re-frame/reg-event-fx
 :room-settings/save-notifications
 (fn [{:keys [db]} [_ {:keys [room-id mode toggles]}]]
   (when room-id
     (go
       (try
         (let [args (cond-> {:room-id room-id}
                      (some? mode)   (assoc :mode (keyword mode))
                      (map? toggles) (assoc :toggles toggles))
               res  (<! (mesh/do-with-thread!
                         :engine-pool
                         {:handler   :update-room-notifications
                          :arguments args}))]
           (if (ok-status? res)
             (re-frame/dispatch
              [:room-settings/save-notifications-done room-id
               {:data (decode-edn-field res :notifications-edn :notifications)}])
             (re-frame/dispatch
              [:room-settings/save-notifications-done room-id
               {:error (or (:msg res) "Failed to update notification settings")}])))
         (catch :default e
           (log/error "Room notifications save failed:" e)
           (re-frame/dispatch
            [:room-settings/save-notifications-done room-id {:error (str e)}])))))
   {:db (-> db
            (assoc-in [:room-settings/notifs-by-id room-id :saving?] true)
            (update-in [:room-settings/notifs-by-id room-id] dissoc :error :saved?))}))

(defn notif-mode-badge [tr mode]
  (case (some-> mode keyword)
    :default  (tr [:room-settings.notifications/badge-default])
    :all      (tr [:room-settings.notifications/badge-all])
    :mentions (tr [:room-settings.notifications/badge-mentions])
    :mute     (tr [:room-settings.notifications/badge-mute])
    nil))

(defn notif-mode-label [tr mode]
  (case (some-> mode keyword)
    :default  (tr [:room-settings.notifications/mode-default])
    :all      (tr [:room-settings.notifications/mode-all])
    :mentions (tr [:room-settings.notifications/mode-mentions])
    :mute     (tr [:room-settings.notifications/mode-mute])
    (some-> mode name)))

(defn notif-mode-desc [tr mode]
  (case (some-> mode keyword)
    :default  (tr [:room-settings.notifications/mode-default-desc])
    :all      (tr [:room-settings.notifications/mode-all-desc])
    :mentions (tr [:room-settings.notifications/mode-mentions-desc])
    :mute     (tr [:room-settings.notifications/mode-mute-desc])
    ""))

(defn effective-mode-title [tr mode def-mode]
  (if (and (= mode :default) def-mode)
    (tr [:room-settings.notifications/mode-default-effective]
        [(notif-mode-label tr def-mode)])
    (notif-mode-label tr mode)))

(defn format-notif-mode-subtitle [tr data]
  (when (and data (:supported? data true))
    (let [cur (some-> (:mode data) keyword)
          def (some-> (:default-mode data) keyword)]
      (when cur
        (effective-mode-title tr cur def)))))

(defn derive-room-notif-view-model [notif-state tr & [config]]
  (let [{:keys [loading? saving? data error saved?]} notif-state
        toggles     (:toggles data)
        toggle-vals (into {}
                          (map (fn [t] [(keyword (:id t)) (boolean (:value t))]))
                          toggles)]
    {:loading?    loading?
     :saving?     saving?
     :data        data
     :error       error
     :saved?      saved?
     :is-space?   (boolean (if (some? (:is-space? data))
                             (:is-space? data)
                             (:is-space? config)))
     :supported?  (boolean (:supported? data true))
     :can-edit?   (boolean (:can-edit? data true))
     :cur-mode    (some-> (:mode data) keyword)
     :def-mode    (some-> (:default-mode data) keyword)
     :modes       (mapv keyword (:modes data))
     :toggles     toggles
     :toggle-vals toggle-vals
     :note-text   (cond
                    (:note-key data)
                    (tr [(:note-key data)])

                    (not (str/blank? (str (:note data))))
                    (:note data)

                    :else nil)}))

(defn set-room-notif-mode! [room-id mode toggle-vals]
  (re-frame/dispatch
   [:room-settings/save-notifications
    {:room-id room-id
     :mode    mode
     :toggles (when (seq toggle-vals) toggle-vals)}]))

(defn toggle-room-notif-option! [room-id cur-mode toggle-vals toggle-id next-val]
  (re-frame/dispatch
   [:room-settings/save-notifications
    {:room-id room-id
     :mode    cur-mode
     :toggles (assoc toggle-vals toggle-id (boolean next-val))}]))