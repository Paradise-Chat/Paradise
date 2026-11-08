(ns paradise.ui.auth.events
  (:require [re-frame.core :as re-frame]
            [clojure.string :as str]
            [reagent.core :as r]
            [paradise.shared.client.state :as state]
            [cljs-workers.core :as main]
            [cljs-workers.mesh :as mesh]
            [cljs.core.async :refer [go <!]]
            [paradise.binding.streams :as engines]
            [paradise.binding.core :as binding]
            [paradise.shared.registry :as registry]
            [paradise.shared.client.session-store :as store]
            [paradise.shared.utils.svg :as icons]
            [net :refer [set-auth-context!]]
            [service-worker-handler :refer [register-sw!]]
            [taoensso.timbre :as log]))

(re-frame/reg-event-db
 :auth/set-status
 (fn [db [_ status]]
   (assoc db :auth-status status)))

(re-frame/reg-sub
 :auth/status
 (fn [db _] (:auth-status db)))

(re-frame/reg-sub
 :auth/error
 (fn [db _] (:login-error db)))

(re-frame/reg-event-fx
 :auth/start-login-flow
 (fn [{:keys [db]} _]
   {:db (assoc db :auth-status :logged-out
               :settings/open? false
               :login-error nil)
    :fx [[:dispatch [:settings/close]]
         [:dispatch [:settings/load-accounts]]]}))



(re-frame/reg-event-fx
 :auth/login
 (fn [{:keys [db]} [_ engine-id auth-payload]]
   (binding/set-active-engine! engine-id)
   (if-let [pool @state/!engine-pool]
     (go
       (try
         (let [res (<! (main/do-with-pool! pool {:handler :login
                                                 :arguments auth-payload}))
               status-kw (keyword (:status res))]
           (if (= status-kw :success)
             (re-frame/dispatch [:auth/login-success
                                 (assoc res :credentials auth-payload
                                        :engine-id engine-id)])
             (re-frame/dispatch [:auth/login-failure (:msg res)])))
         (catch :default e
           (re-frame/dispatch [:auth/login-failure (str e)]))))
     (log/error "Cannot login: Engine pool not initialized"))

   {:db (assoc db :auth-status :authenticating
               :login-error nil)}))

(re-frame/reg-event-fx
 :auth/login-success
 (fn [{:keys [db]} [_ {:keys [user-id session-data credentials engine-id]}]]
   (when session-data
     (register-sw!)
     (set-auth-context! (or (:token session-data) (:accessToken session-data))
                        (:serverUrl session-data))
     (store/set-setting! "last_active_user" user-id)
     (store/set-setting! "last-active-engine" (name engine-id)))

   (when engine-id
     (engines/init-engine-streams! engine-id))

   {:db (assoc db
               :auth-status :logged-in
               :active-user-id user-id
               :rooms/data {}
               :timeline/events []
               :auth/session session-data)
    :fx (cond-> [[:dispatch [:app/load-settings-by-stage :login]]
                 [:dispatch [:app/restore-user-session user-id]]
                 [:dispatch [:push/check-status]]]
          (and credentials session-data)
          (conj [:dispatch [:push/create-sleepy-shadow
                            (merge credentials
                                   session-data
                                   {:userId user-id})]]))}))


(re-frame/reg-sub
 :auth/active-user-id
 (fn [db _]
   (:active-user-id db)))

(re-frame/reg-event-fx
 :auth/switch-account
 (fn [{:keys [db]} [_ target-user-id]]
   (store/set-setting! "last_active_user" target-user-id)
   {:db (assoc db :auth-status :checking
                  :settings/open? false
                  :active-user-id nil
                  :rooms/data {}
                  :timeline/events [])
    :fx [[:dispatch [:app/bootstrap target-user-id (:active-engine db)]]]}))


(re-frame/reg-event-db
 :auth/login-failure
 (fn [db [_ error-msg]]
   (assoc db :auth-status :logged-out
             :login-error error-msg)))

(re-frame/reg-fx
 :session/delete-from-store
 (fn [user-id]
   (let [store (store/->SessionStore)]
     (-> (.clear store user-id)
         (.then #(log/info "Successfully wiped OPFS session and crypto store for:" user-id))
         (.catch #(log/error "Failed to wipe session for:" user-id %))))
   (let [req (js/window.indexedDB.deleteDatabase "paradise-settings")]
     (set! (.-onsuccess req) #(log/info "Successfully wiped paradise-settings database."))
     (set! (.-onerror req) #(log/error "Failed to wipe paradise-settings."))
     (set! (.-onblocked req) #(log/warn "Wiping paradise-settings was BLOCKED by an open connection.")))))


(re-frame/reg-event-fx
 :session/nuke-single-account
 (fn [{:keys [db]} [_ target-user-id]]
   (let [is-active? (= target-user-id (:active-user-id db))]
     (merge
      {:session/wipe-settings true
       :fx [[:dispatch [:settings/load-accounts]]]}
      (when is-active?
        {:db (assoc db :auth-status :logged-out
                       :active-user-id nil
                       :rooms/data {}
                       :timeline/events [])})))))

(re-frame/reg-fx
 :session/wipe-settings
 (fn [_]
   (let [req (js/window.indexedDB.deleteDatabase "paradise-settings")]
     (set! (.-onsuccess req) #(log/info "Successfully wiped paradise-settings database."))
     (set! (.-onerror req) #(log/error "Failed to wipe paradise-settings."))
     (set! (.-onblocked req) #(log/warn "Wiping paradise-settings was BLOCKED.")))))


(re-frame/reg-event-fx
 :sdk/logout
 (fn [{:keys [db]} _]
   (let [user-id (:active-user-id db)]
     (if user-id
       (do
         (go
           (let [pool @state/!engine-pool
                 res  (<! (main/do-with-pool! pool {:handler :logout-client
                                                    :arguments {:user-id user-id}}))]
             (if (= (:status res) "success")
               (re-frame/dispatch [:session/nuke-single-account user-id])
               (log/error "Server rejected logout:" (:msg res)))))
         {})
       {:dispatch [:session/nuke-everything]}))))

(re-frame/reg-event-fx
 :session/nuke-local-state!
 (fn [{:keys [db]} _]
   (let [store-id (get-in db [:session/meta :store-id])]
     {:db {}
      :browser/hard-wipe store-id})))

(re-frame/reg-fx
 :browser/hard-wipe
 (fn [store-id]
   (.clear js/window.localStorage)
   (let [stores-to-delete (cond-> ["paradise-settings"]
                            store-id (conj (str "paradise-store-" store-id)))
         delete-promises
         (map (fn [db-name]
                (js/Promise.
                 (fn [resolve-fn]
                   (let [req (js/window.indexedDB.deleteDatabase db-name)]
                     (set! (.-onsuccess req) #(resolve-fn true))
                     (set! (.-onerror req) #(resolve-fn false))
                     (set! (.-onblocked req) #(do (log/warn db-name "is blocked!")
                                                  (resolve-fn false)))))))
              stores-to-delete)]
     (-> (js/Promise.all (clj->js delete-promises))
         (.then (fn []
                  (log/info "All databases destroyed. Reloading...")
                  (.reload js/window.location)))))))

(re-frame/reg-event-fx
 :registry/load-success
 (fn [{:keys [db]} [_ resolved-engines-map]]
   {:db (assoc db :available-engines resolved-engines-map)}))

(re-frame/reg-sub
 :registry/available-engines
 (fn [db _]
   (get db :available-engines {})))


(re-frame/reg-event-fx
 :registry/sync-manifests
 (fn [{:keys [db]} _]
   (go
     (<! (registry/ensure-environment!))
     (re-frame/dispatch [:registry/set-available-engines (registry/resolve-engines)]))
   {}))

(re-frame/reg-event-db
 :registry/set-available-engines
 (fn [db [_ engines]]
   (assoc db :available-engines engines)))

(re-frame/reg-event-fx
 :engine/fetch-manifest
 (fn [{:keys [db]} [_ url]]
   (go
     (if-let [manifest (<! (registry/fetch-edn url))]
       (let [engine-kw (keyword (:module-id manifest))
             base-dir  (subs url 0 (inc (clojure.string/last-index-of url "/")))
             js-path   (:path manifest)
             abs-path  (if (clojure.string/starts-with? js-path "http") js-path (str base-dir js-path))]
         (re-frame/dispatch [:registry/set-custom-engine engine-kw (assoc manifest :path abs-path)]))
       (js/console.error "Failed to fetch custom manifest from:" url)))
   {}))

(re-frame/reg-event-db
 :registry/set-custom-engine
 (fn [db [_ engine-kw manifest]]
   (assoc-in db [:available-engines engine-kw] manifest)))

(re-frame/reg-event-fx
 :auth/login-action
 (fn [{:keys [db]} [_ engine-id action-id payload]]
   (binding/set-active-engine! engine-id)
   (if-let [pool @state/!engine-pool]
     (go
       (try
         (let [res (<! (main/do-with-pool! pool {:handler :login-action
                                                 :arguments {:action-id action-id
                                                             :payload payload}}))
               status-kw (keyword (:status res))]
           (case status-kw
             :redirect (set! (.-href js/window.location) (:url res))
             :success (re-frame/dispatch [:auth/login-success
                                          (assoc res :credentials payload
                                                     :engine-id engine-id)])
             (re-frame/dispatch [:auth/login-failure (:msg res)])))
         (catch :default e
           (re-frame/dispatch [:auth/login-failure (str e)]))))
     (log/error "Cannot perform action: Engine pool not initialized"))

   {:db (assoc db :auth-status :authenticating
                  :login-error nil)}))

(defn ^:ui login-field [{:keys [id label type value on-change disabled]}]
  [:div.field-group.mb-4
   [:label.field-label {:for id} label]
   [:input.field-input
    {:id id
     :type (or type "text")
     :value value
     :on-change on-change
     :disabled disabled}]])

(defn ^:ui login-screen []
  (let [fields (r/atom {:engine nil
                        :show-custom? false
                        :custom-url ""
                        :auth-data {}})]
    (fn []
      (let [tr                @(re-frame/subscribe [:i18n/tr])
            error             @(re-frame/subscribe [:auth/error])
            status            @(re-frame/subscribe [:auth/status])
            accounts          @(re-frame/subscribe [:settings/available-accounts])
            available-engines @(re-frame/subscribe [:registry/available-engines])
            {:keys [engine show-custom? custom-url auth-data]} @fields
            auth?             (= status :authenticating)
            current-engine    engine
            submit-disabled?  (or auth? (nil? current-engine))
            engine-meta       (get available-engines current-engine)
            auth-fields       (get-in engine-meta [:engine-config :login :fields] [])
            build-payload     (fn []
                                (reduce (fn [acc {:keys [id default]}]
                                          (assoc acc id (get auth-data id default)))
                                        {}
                                        auth-fields))]
        [:div.auth-page-container
         [:div.auth-card
          [:div.auth-header
           [:h2 (tr [:auth/welcome])]
           [:p (tr [:auth/subtitle])]]

          (when error
            [:div.auth-error-message error])

          (when (seq accounts)
            [:div.account-chooser.mb-6
             [:h3.text-sm.font-bold.mb-2 (tr [:auth/continue-as])]
             [:div.accounts-list
              (for [acc accounts]
                ^{:key (:userId acc)}
                [:div.account-chooser-item
                 {:on-click #(re-frame/dispatch [:auth/switch-account (:userId acc)])}
                 [:div.avatar-placeholder [icons/user]]
                 [:div.account-details
                  [:div.account-id (:userId acc)]
                  (when-let [server (or (:server acc) (:server_url acc))]
                    [:div.account-server server])]])]
             [:div.divider
              (tr [:auth/divider])]])

          [:form.auth-form
           {:on-submit (fn [e]
                         (.preventDefault e)
                         (when current-engine
                           (re-frame/dispatch [:auth/login current-engine (build-payload)])))}

           [:div.field-group.mb-6
            [:label.field-label.mb-2 "Protocol Engine"]
            [:div.engine-buttons
             (for [[engine-kw data] available-engines
                   :let [selected? (= current-engine engine-kw)]]
               ^{:key engine-kw}
               [:button
                {:class (if selected? "engine-btn btn-primary" "engine-btn")
                 :type "button"
                 :disabled auth?
                 :on-click #(do
                              (swap! fields assoc :engine engine-kw :show-custom? false)
                              (re-frame/dispatch [:engine/load-protocol (name engine-kw)]))}
                (or (:name data) (str/capitalize (name engine-kw)))])
             [:button
              {:class (if show-custom? "engine-btn btn-primary" "engine-btn")
               :type "button"
               :disabled auth?
               :on-click #(swap! fields assoc :show-custom? true :engine nil)}
              "Custom..."]]

            (when show-custom?
              [:div.mt-4
               [login-field
                {:id "custom-engine-url"
                 :label "Engine Manifest URL (e.g. https://.../manifest.edn)"
                 :value custom-url
                 :disabled auth?
                 :on-change (fn [e]
                              (swap! fields assoc :custom-url (.. e -target -value)))
                 :on-blur (fn [e]
                            (let [url (.. e -target -value)]
                              (when (seq url)
                                (re-frame/dispatch [:engine/fetch-manifest url]))))}]])]

           (for [{:keys [id label type default action-id]} auth-fields]
             (if (= type :action)
               ^{:key id}
               [:button.btn-secondary.w-full.mt-4
                {:type "button"
                 :disabled auth?
                 :on-click #(re-frame/dispatch [:auth/login-action current-engine action-id (build-payload)])}
                label]

               ^{:key id}
               [login-field
                {:id (name id)
                 :label label
                 :type (if (keyword? type) (name type) (or type "text"))
                 :value (get auth-data id (or default ""))
                 :disabled auth?
                 :on-change #(swap! fields assoc-in [:auth-data id] (.. % -target -value))}]))

           [:button.btn-primary.w-full.mt-6
            {:type "submit" :disabled submit-disabled?}
            (if auth?
              (tr [:auth.actions/logging-in])
              (tr [:auth.actions/login]))]

           [:div.auth-footer
            (tr [:auth/footer-text])
            [:a.link {:href "#"} (tr [:auth.actions/register])]]]]]))))