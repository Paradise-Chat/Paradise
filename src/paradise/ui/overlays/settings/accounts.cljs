(ns paradise.ui.overlays.settings.accounts
  (:require
   [cljs.core.async :refer [<! go]]
   [cljs-workers.mesh :as mesh]
   [re-frame.core :as re-frame]
   [taoensso.timbre :as log]))

(re-frame/reg-fx
 :idb/fetch-sessions
 (fn [on-success-event]
   (let [req (.open js/indexedDB "sw-vault" 1)]
     (set! (.-onerror req)
           #(log/error "Failed to open sw-vault"))
     (set! (.-onupgradeneeded req)
           (fn [e]
             (let [db (.. e -target -result)]
               (.createObjectStore
                db
                "tokens"
                #js {:keyPath "userId"}))))
     (set! (.-onsuccess req)
           (fn [e]
             (let [db (.. e -target -result)]
               (if (.contains (.-objectStoreNames db) "tokens")
                 (let [tx          (.transaction db #js ["tokens"] "readonly")
                       store       (.objectStore tx "tokens")
                       get-all-req (.getAll store)]
                   (set! (.-onsuccess get-all-req)
                         (fn [ae]
                           (let [results
                                 (js->clj
                                  (.. ae -target -result)
                                  :keywordize-keys true)]
                             (.close db)
                             (re-frame/dispatch
                              (conj on-success-event results))))))
                 (do
                   (.close db)
                   (re-frame/dispatch
                    (conj on-success-event []))))))))))

(re-frame/reg-event-fx
 :settings/load-accounts
 (fn [_ _]
   (go
     (let [res
           (<! (mesh/do-with-thread!
                :engine-pool
                {:handler :get-available-accounts}))]
       (if (= (:status res) "success")
         (re-frame/dispatch
          [:settings/set-accounts (:accounts res)])
         (log/error
          "Failed to fetch accounts from worker:"
          (:msg res)))))
   {}))

(re-frame/reg-event-db
 :settings/set-accounts
 (fn [db [_ accounts]]
   (assoc db :available-accounts accounts)))

(re-frame/reg-sub
 :settings/available-accounts
 (fn [db _]
   (:available-accounts db [])))

(defn ^:ui account-item
  [{:keys [acc is-active?]}]
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:div.settings-list-item
     {:class (when is-active? "is-active")}
     [:div.settings-list-main
      [:div.settings-list-title
       (:userId acc)]
      [:div.settings-list-description
       (:hs_url acc)]]

     [:div.settings-list-actions
      (if is-active?
        [:div.settings-badge.success
         (tr [:settings.account-item/active-status])]
        [:button.settings-button.is-compact
         {:on-click #(re-frame/dispatch
                      [:auth/switch-account
                       (:userId acc)])}
         (tr [:settings.account-item/switch-button])])]]))

(defn ^:ui accounts-tab []
  (let [tr           @(re-frame/subscribe [:i18n/tr])
        accounts     @(re-frame/subscribe [:settings/available-accounts])
        current-user @(re-frame/subscribe [:sdk/profile])]
    [:div.settings-tab-content
     [:div.settings-page-header
      [:h2.settings-heading
       (tr [:settings.accounts/title])]
      [:p.settings-description
       (tr [:settings.accounts/description])]]

     [:div.settings-section.is-fill
      [:div.settings-list.is-scrollable
       (if (empty? accounts)
         [:div.settings-empty
          (tr [:settings.accounts/empty-state])]
         (for [acc accounts]
           ^{:key (:userId acc)}
           [account-item
            {:acc acc
             :is-active?
             (= (:userId acc)
                (:user-id current-user))}]))]

      [:div.settings-actions
       [:button.settings-button
        {:on-click #(re-frame/dispatch
                     [:auth/start-login-flow])}
        (str
         "+ "
         (tr [:settings.accounts/add-account]))]]]]))
