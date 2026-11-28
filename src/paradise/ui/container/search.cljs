(ns paradise.ui.container.search
  (:require
   [cljs-workers.core :as main]
   [cljs.core.async :refer [<! go]]
   [clojure.string :as str]
   [paradise.shared.client.state :as state]
   [paradise.shared.utils.helpers :refer [ok-status?]]
   [paradise.shared.utils.svg :as icons]
   [paradise.ui.container.reusable :refer [message-preview-list]]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [taoensso.timbre :as log]))

(re-frame/reg-event-fx
 :room/execute-search
 (fn [{:keys [db]} [_ room-id query scope-opt]]
   (let [scope     (keyword (or scope-opt (get-in db [:search-ui :scope] :room)))
         state-key (if (= scope :all) :all room-id)]
     (if (str/blank? query)
       (do
         (log/warn "Search aborted: Empty query")
         {})
       (do
         (go
           (when-let [pool @state/!engine-pool]
             (let [res (<! (main/do-with-pool!
                            pool
                            {:handler   :search-room
                             :arguments {:room-id room-id
                                         :query   query
                                         :scope   scope
                                         :limit   50}}))]
               (if (ok-status? (:status res))
                 (re-frame/dispatch [:room/search-success state-key (:results res)])
                 (re-frame/dispatch [:room/search-error state-key (:msg res)])))))
         {:db (-> db
                  (assoc-in [:search-ui :scope] scope)
                  (assoc-in [:search-data state-key :loading?] true)
                  (assoc-in [:search-data state-key :query] query)
                  (assoc-in [:search-data state-key :results] []))})))) )

(re-frame/reg-event-db
 :room/search-success
 (fn [db [_ state-key normalized-hits]]
   (-> db
       (assoc-in [:search-data state-key :results] (vec (or normalized-hits [])))
       (assoc-in [:search-data state-key :loading?] false))))

(re-frame/reg-event-db
 :room/search-error
 (fn [db [_ state-key err]]
   (log/error "Search failed:" err)
   (assoc-in db [:search-data state-key :loading?] false)))

(re-frame/reg-event-db
 :search/set-scope
 (fn [db [_ scope]]
   (assoc-in db [:search-ui :scope] (keyword scope))))

(re-frame/reg-event-db
 :search/receive-index-status
 (fn [db [_ status-map]]
   (assoc db
          :search/index-status
          (merge {:supported?     false
                  :enabled?       true
                  :running?       false
                  :indexed-events 0
                  :rooms-done     0
                  :rooms-total    0}
                 (dissoc status-map :status :state)))))

(re-frame/reg-event-fx
 :search/refresh-index-status
 (fn [_ _]
   (go
     (when-let [pool @state/!engine-pool]
       (let [res (<! (main/do-with-pool! pool {:handler   :search-index-status
                                               :arguments {}}))]
         (when (ok-status? (:status res))
           (re-frame/dispatch [:search/receive-index-status res])))))
   {}))

(re-frame/reg-event-fx
 :search/hydrate-crawler-enabled
 (fn [{:keys [db]} [_ enabled?]]
   (let [flag (if (nil? enabled?) true (boolean enabled?))]
     (go
       (when-let [pool @state/!engine-pool]
         (let [res (<! (main/do-with-pool!
                        pool
                        {:handler   :search-crawler-set-enabled
                         :arguments {:enabled? flag}}))]
           (when (ok-status? (:status res))
             (re-frame/dispatch [:search/receive-index-status res])))))
     {:db (assoc-in db [:settings :encrypted-search-crawler] flag)})))

(re-frame/reg-event-fx
 :search/set-crawler-enabled
 (fn [{:keys [db]} [_ enabled?]]
   (let [flag (boolean enabled?)]
     (go
       (when-let [pool @state/!engine-pool]
         (let [res (<! (main/do-with-pool!
                        pool
                        {:handler   :search-crawler-set-enabled
                         :arguments {:enabled? flag}}))]
           (when (ok-status? (:status res))
             (re-frame/dispatch [:search/receive-index-status res])))))
     {:db       (assoc-in db [:settings :encrypted-search-crawler] flag)
      :dispatch [:settings/save "encrypted_search_crawler" flag]})))

(re-frame/reg-event-fx
 :search/clear-index
 (fn [_ [_ rebuild?]]
   (go
     (when-let [pool @state/!engine-pool]
       (let [res (<! (main/do-with-pool!
                      pool
                      {:handler   :search-index-clear
                       :arguments {:rebuild? (boolean rebuild?)}}))]
         (when (ok-status? (:status res))
           (re-frame/dispatch [:search/receive-index-status res])))))
   {}))

(re-frame/reg-sub
 :search/scope
 (fn [db _]
   (get-in db [:search-ui :scope] :room)))

(re-frame/reg-sub
 :room/search-state
 (fn [db [_ room-id scope-opt]]
   (let [scope     (or scope-opt (get-in db [:search-ui :scope] :room))
         state-key (if (= scope :all) :all room-id)]
     (get-in db [:search-data state-key] {:loading? false :results [] :query ""}))))

(re-frame/reg-sub
 :search/index-status
 (fn [db _]
   (merge {:supported?     false
           :enabled?       true
           :running?       false
           :indexed-events 0
           :rooms-done     0
           :rooms-total    0}
          (:search/index-status db))))

(re-frame/reg-sub
 :settings/encrypted-search-crawler
 (fn [db _]
   (get-in db [:settings :encrypted-search-crawler] true)))

(defn ^:ui search []
  (r/with-let [_            (re-frame/dispatch [:search/refresh-index-status])
               !local-query (r/atom "")]
    (let [active-room  @(re-frame/subscribe [:rooms/active-id])
          scope        @(re-frame/subscribe [:search/scope])
          search-state @(re-frame/subscribe [:room/search-state active-room scope])
          idx-status   @(re-frame/subscribe [:search/index-status])
          loading?     (:loading? search-state)
          results      (:results search-state)
          tr           @(re-frame/subscribe [:i18n/tr])
          select-scope (fn [next-scope]
                         (when (not= scope next-scope)
                           (re-frame/dispatch [:search/set-scope next-scope])
                           (when-not (str/blank? @!local-query)
                             (re-frame/dispatch [:room/execute-search
                                                 active-room
                                                 @!local-query
                                                 next-scope]))))]
      [:div.sidebar-search-container
       [:div.search-input-wrapper
        [:div.search-input-box
         [icons/search {:width "20px" :height "20px" :style {:margin-right "8px" :opacity 0.5}}]
         [:input.search-input
          {:type        "text"
           :placeholder (if (= scope :all)
                          (tr [:container.search/placeholder-all])
                          (tr [:container.search/placeholder]))
           :value       @!local-query
           :on-change   #(reset! !local-query (.. % -target -value))
           :on-key-down #(when (= (.-key %) "Enter")
                           (re-frame/dispatch [:room/execute-search
                                               active-room
                                               @!local-query
                                               scope]))}]]
        [:div.search-scope-bar
         [:button.search-scope-pill
          {:type     "button"
           :class    (when (= scope :room) "is-active")
           :on-click #(select-scope :room)}
          (tr [:container.search/scope-room])]
         [:button.search-scope-pill
          {:type     "button"
           :class    (when (= scope :all) "is-active")
           :on-click #(select-scope :all)}
          (tr [:container.search/scope-all])]]
        (when (and (:supported? idx-status) (:running? idx-status))
          [:div.search-index-banner
           [:span.search-index-dot]
           [:span (tr [:container.search/indexing-active]
                      [(:indexed-events idx-status 0)])]])]

       [:div.search-results-area
        (cond
          loading?
          [:div.search-status
           [:div.spinner]
           (tr [:container.search/loading])]

          (and (not loading?) (seq results))
          [message-preview-list
           active-room
           results
           {:class            "search-list"
            :show-room-badge? (= scope :all)}]

          (and (not loading?) (not (str/blank? (:query search-state))) (empty? results))
          [:div.search-status
           [icons/search {:width "48px" :height "48px" :style {:margin-bottom "16px" :opacity 0.3}}]
           [:div (tr [:container.search/no-results])]]

          :else
          [:div.search-status
           [icons/search {:width "48px" :height "48px" :style {:margin-bottom "16px" :opacity 0.3}}]
           [:div (tr [:container.search/initial])]])]])))