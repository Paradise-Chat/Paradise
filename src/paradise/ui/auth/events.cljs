(ns paradise.ui.auth.events
  (:require [re-frame.core :as re-frame]
            [clojure.string :as str]
            [reagent.core :as r]
            [paradise.shared.client.cache :as cache]
            [paradise.shared.client.idb :as idb]
            [paradise.shared.client.state :as state]
            [paradise.shared.net.auth :as net-auth]
            [paradise.shared.utils.cache :as mem-cache]
            [paradise.shared.utils.platform :as platform]
            [paradise.media.component :as media]
            [virtualizer.client.hydrator :as vhydrator]
            [cljs-workers.core :as main]
            [cljs-workers.mesh :as mesh]
            [cljs-workers.worker :as worker]
            [cljs.core.async.interop :refer-macros [<p!]]
            [cljs.core.async :refer [go <!]]
            [paradise.binding.streams :as engines]
            [paradise.binding.core :as binding]
            [paradise.shared.registry :as registry]
            [paradise.shared.client.session-store :as store]
            [paradise.shared.utils.encoding :refer [base64->uint8]]
            [paradise.shared.utils.helpers :refer [capacitor-mobile? capacitor-platform]]
            [paradise.shared.utils.qr :as qr]
            [paradise.shared.utils.svg :as icons]
            [net :refer [clear-frame-url! clear-stream-url! close-auth-url! open-auth-url! set-auth-context!]]
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

(re-frame/reg-sub
 :auth/notice
 (fn [db _] (:login-notice db)))

(re-frame/reg-sub
 :auth/challenge
 (fn [db _] (:login-challenge db)))

(re-frame/reg-event-db
 :auth/set-proxy-provider
 (fn [db [_ provider]]
   (assoc db :proxy-provider provider)))

(re-frame/reg-sub
 :auth/proxy-provider
 (fn [db _]
   (or (:proxy-provider db)
       (when (and (exists? js/window) (some? (.-paradiseProxy js/window)))
         (or (some-> (.-paradiseProxy js/window) (.-provider)) "host")))))

(defonce ^:private !login-action-seq (atom 0))

(re-frame/reg-event-db
 :auth/set-challenge
 (fn [db [_ challenge]]
   (assoc db :login-challenge (when (and (map? challenge) (seq challenge)) challenge))))

(when (exists? js/window)
  (worker/register
   :auth-challenge
   (fn [challenge]
     (go
       (re-frame/dispatch [:auth/set-challenge challenge])
       {:ok true}))))

(defn- valid-qr-image-data-url [raw]
  (when (and (string? raw) (not (str/blank? raw)))
    (let [trimmed (str/trim raw)]
      (when (and (re-find #"^data:image/(?:png|jpeg|jpg|webp|gif);base64," (str/lower-case trimmed))
                 (not (re-find #"[<>\"']" trimmed)))
        trimmed))))

(defn- valid-challenge-image-url [raw]
  (or (net-auth/valid-http-url-str raw)
      (valid-qr-image-data-url raw)))

(defn- field-visible-on-platform? [field]
  (let [platforms (:platforms field)]
    (if (empty? platforms)
      true
      (let [plat      (platform/current-platform)
            mobile?   (platform/capacitor-mobile?)
            electron? (or (platform/electron-platform?)
                          (and (exists? js/window)
                               (= "electron" (some-> (.-paradiseProxy js/window) (.-provider)))))
            norm      (set (map (fn [p]
                                  (keyword (str/lower-case (name p))))
                                platforms))]
        (boolean
         (or (and (contains? norm :desktop) (not mobile?))
             (and (contains? norm :mobile) mobile?)
             (and (contains? norm :electron) electron?)
             (and (contains? norm :web) (= plat "web") (not electron?))
             (and (contains? norm :ios) (= plat "ios"))
             (and (contains? norm :android) (= plat "android"))))))))

(re-frame/reg-event-db
 :auth/login-failure
 (fn [db [_ error-msg]]
   (set-auth-context! nil nil)
   (assoc db
          :auth-status :logged-out
          :login-challenge nil
          :login-active-action nil
          :login-notice nil
          :login-error error-msg)))

(re-frame/reg-event-db
 :auth/login-notice
 (fn [db [_ notice-msg]]
   (set-auth-context! nil nil)
   (assoc db
          :auth-status :logged-out
          :login-challenge nil
          :login-active-action nil
          :login-error nil
          :login-notice notice-msg)))

(defn- cancel-inflight-login! []
  (swap! !login-action-seq inc)
  (set-auth-context! nil nil)
  (close-auth-url!)
  (when @state/!engine-pool
    (go
      (try
        (<! (mesh/do-with-thread! :engine-pool {:handler   :cancel-login
                                                :arguments {}}))
        (catch :default _ nil)))))

(re-frame/reg-event-fx
 :auth/cancel-login
 (fn [{:keys [db]} _]
   (cancel-inflight-login!)
   {:db (assoc db
               :auth-status :logged-out
               :login-challenge nil
               :login-active-action nil
               :login-notice nil
               :login-error nil)}))


(re-frame/reg-sub
 :auth/active-user-id
 (fn [db _]
   (:active-user-id db)))

(re-frame/reg-sub
 :auth/active-engine
 (fn [db _]
   (:active-engine db)))


(re-frame/reg-event-fx
 :auth/apply-hydrated-net-context
 (fn [{:keys [db]} [_ {:keys [user-id base-url token auth-media?]}]]
   (let [http-base? (boolean (net-auth/valid-http-url-str base-url))]
     (if (and (= user-id (:active-user-id db))
              http-base?
              token
              (not (false? auth-media?)))
       (do
         (set-auth-context! token base-url)
         {:dispatch [:auth/sync-sw-only {:user-id     user-id
                                         :base-url    base-url
                                         :token       token
                                         :auth-media? (not (false? auth-media?))}]})
       {}))))

(defn reset-account-session-db
  "Resets all account-, space-, room-, and timeline-scoped state in app-db,
  clears all session-scoped in-memory caches, and advances `:session/gen`
  so stale async callbacks cannot bleed across accounts."
  [db]
  (mem-cache/clear-all-session-caches!)
  (let [next-gen (inc (or (:session/gen db) 0))]
    (-> db
        (assoc :session/gen next-gen
               :session/sync-ready? false
               :session/pending-restore nil
               :session/pending-room-select nil
               :active-user-id nil
               :active-space-id nil
               :active-room-id nil
               :active-filter-id "people"
               :main-focus :timeline
               :side-panel nil
               :auth/session nil
               :rooms/data {}
               :rooms []
               :rooms-unfiltered-cache {}
               :rooms-manual-children-idx {}
               :room-previews {}
               :rooms/membership {}
               :room-members {}
               :rooms/join-mode? false
               :forum-room-overrides #{}
               :member-options/by-key {}
               :room-options/by-key {}
               :room-settings/by-id {}
               :room-settings/notifs-by-id {}
               :room-perms/by-id {}
               :spaces/list []
               :spaces-list []
               :spaces {}
               :space-children {}
               :space-unreads {}
               :dm-pops []
               :timeline-subs {}
               :timeline-data {}
               :timeline/events []
               :timeline/jump-target-id {}
               :search-data {}
               :presence {}
               :own-status :online
               :own-status-msg ""
               :emoji/packs {}
               :emoji/frequent-items []
               :emoji/frequent-stickers [])
        (assoc-in [:ui :main-focus] :timeline)
        (assoc-in [:ui :sidebar-open?] false)
        (assoc-in [:ui :closed-drawers] #{}))))

(defn- clear-virtualizer-streams! []
  (cond
    (fn? vhydrator/clear-all-streams!)
    (vhydrator/clear-all-streams!)

    (some? vhydrator/!streams)
    (reset! vhydrator/!streams {})))

(re-frame/reg-event-fx
 :auth/start-login-flow
 (fn [{:keys [db]} _]
   (let [call-active? (get-in db [:call :is-active?] false)]
     (binding/set-streams-paused! true)
     (set-auth-context! nil nil)
     (clear-stream-url!)
     (clear-frame-url!)
     (media/clear-media-cache!)
     (clear-virtualizer-streams!)
     {:db (-> (reset-account-session-db db)
              (assoc :auth-status :logged-out
                     :settings/open? false
                     :login-challenge nil
                     :login-error nil))
      :fx (cond-> [[:dispatch [:settings/close]]
                   [:dispatch [:notif/clear-toasts]]
                   [:dispatch [:i18n/clear-engine-dictionary]]
                   [:dispatch [:engine/clear-media-session {:all? true}]]
                   [:dispatch [:auth/clear-sw-session {:all? true}]]
                   [:dispatch [:settings/load-accounts]]]
            call-active?
            (conj [:dispatch [:call/hangup]]))})))

(re-frame/reg-event-fx
 :auth/login
 (fn [{:keys [db]} [_ engine-id auth-payload]]
   (let [engine-kw  (keyword engine-id)
         action-seq (swap! !login-action-seq inc)]
     (binding/set-active-engine! engine-kw)
     (if @state/!engine-pool
       (go
         (try
           (let [res       (<! (mesh/do-with-thread! :engine-pool
                                                     {:handler   :login
                                                      :arguments auth-payload}))
                 status-kw (keyword (:status res))
                 stale?    (not= action-seq @!login-action-seq)]
             (when-not stale?
               (case status-kw
                 :success   (re-frame/dispatch [:auth/login-success
                                                (assoc res
                                                       :credentials auth-payload
                                                       :engine-id engine-kw)])
                 :cancelled (re-frame/dispatch [:auth/set-status :logged-out])
                 (re-frame/dispatch [:auth/login-failure (:msg res)]))))
           (catch :default e
             (when (= action-seq @!login-action-seq)
               (re-frame/dispatch [:auth/login-failure (str e)])))))
       (log/error "Cannot login: Engine pool not initialized"))
     {:db (assoc db
                 :auth-status :authenticating
                 :active-engine engine-kw
                 :login-challenge nil
                 :login-active-action nil
                 :login-error nil)})))

(re-frame/reg-event-fx
 :auth/login-success
 [(state/guard-session-gen #(-> % second :expected-gen))]
 (fn [{:keys [db]} [_ {:keys [user-id session-data credentials engine-id]}]]
   (let [resolved-engine (or (some-> engine-id keyword)
                             (:active-engine db)
                             :matrix)
         token           (or (:token session-data) (:accessToken session-data))
         server-url      (or (:server-url session-data) (:base-url session-data))
         http-server?    (boolean (net-auth/valid-http-url-str server-url))
         bearer-auth?    (and token http-server? (not (false? (:auth-media? session-data))))
         clean-db        (reset-account-session-db db)
         next-gen        (:session/gen clean-db)]
     (close-auth-url!)
     (media/clear-media-cache!)
     (clear-virtualizer-streams!)
     (register-sw!)
     (if bearer-auth?
       (set-auth-context! token server-url)
       (set-auth-context! nil nil))
     (when user-id
       (store/set-setting! "last_active_user" user-id))
     (when resolved-engine
       (store/set-setting! "last_active-engine" (name resolved-engine))
       (binding/set-active-engine! resolved-engine)
       (engines/init-engine-streams! resolved-engine))
     (binding/set-streams-paused! false)
     {:db (assoc clean-db
                 :auth-status :logged-in
                 :login-challenge nil
                 :login-active-action nil
                 :active-user-id user-id
                 :active-engine resolved-engine
                 :auth/session session-data)
      :fx (cond-> [[:dispatch [:i18n/load-engine-extensions resolved-engine]]
                   [:dispatch [:engine/sync-media-session
                               {:user-id    user-id
                                :server-url server-url
                                :token      token}]]
                   [:dispatch [:settings/load-accounts]]
                   [:dispatch [:app/load-settings-by-stage :login]]
                   [:dispatch [:app/restore-user-session user-id resolved-engine next-gen]]
                   [:dispatch [:push/check-status]]]
            (and credentials session-data)
            (conj [:dispatch [:push/create-sleepy-shadow
                              (merge credentials
                                     session-data
                                     {:userId user-id})]]))})))


(re-frame/reg-event-fx
 :auth/switch-account
 (fn [{:keys [db]} [_ target-user-id target-engine]]
   (let [target-eng-kw   (some-> target-engine keyword)
         prev-engine     (:active-engine db)
         same-logged-in? (and (= (:auth-status db) :logged-in)
                              (= (str target-user-id) (str (:active-user-id db)))
                              (or (nil? target-eng-kw)
                                  (= target-eng-kw prev-engine)))
         call-active?    (get-in db [:call :is-active?] false)]
     (if same-logged-in?
       {:db (assoc db :settings/open? false)
        :fx [[:dispatch [:settings/close]]]}
       (let [clean-db (reset-account-session-db db)
             next-gen (:session/gen clean-db)]
         (when (or (:login-challenge db)
                   (:login-active-action db)
                   (= (:auth-status db) :authenticating))
           (cancel-inflight-login!))
         (binding/set-streams-paused! true)
         (set-auth-context! nil nil)
         (clear-stream-url!)
         (clear-frame-url!)
         (media/clear-media-cache!)
         (clear-virtualizer-streams!)
         (go
           (let [saved-eng (<p! (-> (store/get-setting "last-active-engine")
                                    (.catch (fn [_] nil))))
                 engine-kw (or target-eng-kw
                               prev-engine
                               (some-> saved-eng keyword)
                               :matrix)]
             (<p! (store/set-setting! "last_active_user" target-user-id))
             (<p! (store/set-setting! "last-active-engine" (name engine-kw)))
             (re-frame/dispatch [:engine/switch-account-boot
                                 {:target-user-id target-user-id
                                  :engine-id      engine-kw
                                  :prev-engine-id prev-engine
                                  :expected-gen   next-gen}])))
         {:db (assoc clean-db
                     :auth-status :checking
                     :login-challenge nil
                     :login-active-action nil
                     :login-error nil
                     :settings/open? false)
          :fx (cond-> [[:dispatch [:settings/close]]
                       [:dispatch [:notif/clear-toasts]]
                       [:dispatch [:auth/clear-sw-session {:all? true}]]]
                call-active?
                (conj [:dispatch [:call/hangup]]))})))))

(re-frame/reg-event-fx
 :session/nuke-single-account
 (fn [{:keys [db]} [_ target-user-id]]
   (let [is-active? (= (str target-user-id) (str (:active-user-id db)))]
     (media/purge-user-media-cache! target-user-id)
     (cache/clear-user-meta! target-user-id)
     (when is-active?
       (binding/set-streams-paused! true)
       (set-auth-context! nil nil)
       (clear-stream-url!)
       (clear-frame-url!)
       (clear-virtualizer-streams!)
       (store/set-setting! "last_active_user" nil)
       (store/set-setting! "last-active-engine" nil))
     (merge
      {:fx (cond-> [[:dispatch [:engine/clear-media-session {:user-id target-user-id}]]
                    [:dispatch [:auth/clear-sw-session {:user-id target-user-id}]]
                    [:dispatch [:settings/load-accounts]]]
             is-active? (conj [:dispatch [:i18n/clear-engine-dictionary]]
                              [:dispatch [:notif/clear-toasts]]))}
      (when is-active?
        {:db (-> (reset-account-session-db db)
                 (assoc :auth-status :logged-out))})))))


(re-frame/reg-fx
 :session/delete-from-store
 (fn [user-id]
   (let [store (store/->SessionStore)]
     (-> (.clear store user-id)
         (.then #(log/info "Successfully wiped OPFS session and crypto store for:" user-id))
         (.catch #(log/error "Failed to wipe session for:" user-id %))))
   (idb/delete-db! "paradise-settings")))


(re-frame/reg-fx
 :session/wipe-settings
 (fn [_]
   (idb/delete-db! "paradise-settings")))

(re-frame/reg-event-fx
 :sdk/logout
 (fn [{:keys [db]} _]
   (let [user-id (:active-user-id db)]
     (if user-id
       (do
         (go
           (try
             (let [res (<! (mesh/do-with-thread! :engine-pool
                                                 {:handler   :logout-client
                                                  :arguments {:user-id user-id}}))]
               (when-not (#{"success" :success} (:status res))
                 (log/warn "Remote logout failed; wiping local session anyway:" (:msg res))))
             (catch :default e
               (log/warn "Remote logout threw; wiping local session anyway:" e))
             (finally
               (re-frame/dispatch [:session/nuke-single-account user-id]))))
         {})
       {:dispatch [:session/nuke-everything]}))))

(re-frame/reg-event-fx
 :session/nuke-local-state!
 (fn [{:keys [db]} _]
   (let [store-id (get-in db [:session/meta :store-id])]
     (set-auth-context! nil nil)
     (clear-stream-url!)
     (clear-frame-url!)
     (media/clear-media-cache!)
     (clear-virtualizer-streams!)
     {:db {}
      :fx [[:dispatch [:engine/clear-media-session {:all? true}]]
           [:dispatch [:auth/clear-sw-session {:all? true}]]]
      :browser/hard-wipe store-id})))

(re-frame/reg-fx
 :browser/hard-wipe
 (fn [store-id]
   (.clear js/window.localStorage)
   (let [stores-to-delete (cond-> ["paradise-settings" cache/cache-db-name]
                            store-id (conj (str "paradise-store-" store-id)))
         delete-promises  (map idb/delete-db! stores-to-delete)
         wipe-opfs!
         (-> (if (and (exists? js/navigator) (.-storage js/navigator) (.-getDirectory (.-storage js/navigator)))
               (-> (.getDirectory (.-storage js/navigator))
                   (.then (fn [dir]
                            (js/Promise.
                             (fn [resolve-fn]
                               (go
                                 (try
                                   (let [entries (js/Array.)
                                         iter    (.keys dir)]
                                     (loop []
                                       (let [step (<p! (.next iter))]
                                         (when-not (.-done step)
                                           (.push entries (.-value step))
                                           (recur))))
                                     (doseq [k entries
                                             :when (or (= k "paradise_sw_sessions.json")
                                                       (str/starts-with? k "sessions_"))]
                                       (try
                                         (<p! (.removeEntry dir k #js {:recursive true}))
                                         (catch :default _ nil))))
                                   (catch :default _ nil))
                                 (resolve-fn true)))))))
               (js/Promise.resolve true))
             (.catch (fn [_] true)))
         wipe-caches!
         (-> (if (exists? js/caches)
               (-> (.keys js/caches)
                   (.then (fn [ks]
                            (js/Promise.all
                             (clj->js (for [k ks :when (str/starts-with? k "paradise-media")]
                                        (.delete js/caches k)))))))
               (js/Promise.resolve true))
             (.catch (fn [_] true)))]
     (-> (js/Promise.all (clj->js (concat delete-promises [wipe-opfs! wipe-caches!])))
         (.then (fn []
                  (log/info "All local databases, OPFS sessions, and media caches destroyed. Reloading...")
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
             abs-path  (if (clojure.string/starts-with? js-path "http") js-path (str base-dir js-path))
             i18n-path (:i18n manifest)
             i18n-url  (when (string? i18n-path)
                         (registry/resolve-relative-url url i18n-path))]
         (re-frame/dispatch [:registry/set-custom-engine engine-kw
                             (cond-> (assoc manifest :path abs-path)
                               i18n-url (assoc :i18n-url i18n-url))]))
       (js/console.error "Failed to fetch custom manifest from:" url)))
   {}))

(re-frame/reg-event-db
 :registry/set-custom-engine
 (fn [db [_ engine-kw manifest]]
   (assoc-in db [:available-engines engine-kw] manifest)))

(re-frame/reg-event-fx
 :auth/login-action
 (fn [{:keys [db]} [_ engine-id action-id payload]]
   (let [engine-kw  (keyword engine-id)
         action-seq (swap! !login-action-seq inc)]
     (binding/set-active-engine! engine-kw)
     (if @state/!engine-pool
       (go
         (try
           (let [res       (<! (mesh/do-with-thread! :engine-pool
                                                     {:handler   :login-action
                                                      :arguments {:action-id action-id
                                                                  :payload   payload}}))
                 status-kw (keyword (:status res))
                 stale?    (not= action-seq @!login-action-seq)]
             (when-not stale?
               (case status-kw
                 :redirect  (if-let [safe-url (valid-redirect-http-url (:url res))]
                              (if (some? (.-paradiseProxy js/window))
                                (do
                                  (open-auth-url! {:url safe-url :external? true})
                                  (re-frame/dispatch [:auth/set-status :logged-out]))
                                (set! (.-href js/window.location) safe-url))
                              (re-frame/dispatch [:auth/login-failure "Invalid redirect URL"]))
                 :success   (re-frame/dispatch [:auth/login-success
                                                (assoc res
                                                       :credentials payload
                                                       :engine-id engine-kw)])
                 :completed (re-frame/dispatch [:auth/login-notice (:msg res)])
                 :cancelled (re-frame/dispatch [:auth/set-status :logged-out])
                 (re-frame/dispatch [:auth/login-failure (:msg res)]))))
           (catch :default e
             (when (= action-seq @!login-action-seq)
               (re-frame/dispatch [:auth/login-failure (str e)])))))
       (log/error "Cannot perform action: Engine pool not initialized"))
     {:db (assoc db
                 :auth-status :authenticating
                 :active-engine engine-kw
                 :login-challenge nil
                 :login-active-action {:engine-id engine-kw
                                       :action-id action-id
                                       :payload   payload}
                 :login-notice nil
                 :login-error nil)})))

(re-frame/reg-event-fx
 :auth/refresh-challenge
 (fn [{:keys [db]} [_ override-action-id]]
   (let [{:keys [engine-id action-id payload]} (:login-active-action db)
         target-engine (or engine-id (:active-engine db))
         target-action (or override-action-id action-id)]
     (if (and target-engine target-action)
       (do
         (swap! !login-action-seq inc)
         (when @state/!engine-pool
           (go
             (try
               (<! (mesh/do-with-thread! :engine-pool {:handler   :cancel-login
                                                       :arguments {}}))
               (catch :default _ nil))
             (re-frame/dispatch [:auth/login-action target-engine target-action (or payload {})])))
         {:db (assoc db :login-challenge nil :login-error nil)})
       {:dispatch [:auth/cancel-login]}))))

(re-frame/reg-event-fx
 :auth/submit-challenge-input
 (fn [{:keys [db]} [_ value]]
   (when @state/!engine-pool
     (go
       (let [res (<! (mesh/do-with-thread! :engine-pool
                                           {:handler   :submit-login-challenge
                                            :arguments {:value (str (or value ""))}}))]
         (when (= (keyword (:status res)) :error)
           (re-frame/dispatch [:auth/set-login-error (or (:msg res) "Invalid verification code")])))))
   {:db (assoc db :login-error nil)}))

(re-frame/reg-event-db
 :auth/set-login-error
 (fn [db [_ msg]]
   (assoc db :login-error msg)))

(defn ^:ui login-field [{:keys [id label type value on-change disabled]}]
  [:div.field-group.mb-4
   [:label.field-label {:for id} label]
   [:input.field-input
    {:id id
     :type (or type "text")
     :value value
     :on-change on-change
     :disabled disabled}]])

(defn- format-expiry-countdown [ms-left]
  (let [total-secs (max 0 (js/Math.ceil (/ ms-left 1000.0)))
        mins       (quot total-secs 60)
        secs       (rem total-secs 60)]
    (if (pos? mins)
      (str mins ":" (when (< secs 10) "0") secs)
      (str secs "s"))))

(defn- qr-code-svg [qr-data ecc]
  (try
    (let [{:keys [size matrix]} (qr/encode qr-data {:ecc (or ecc :m) :border 2})
          path-d                (qr/qr-matrix->svg-path matrix)]
      [:svg.auth-challenge-qr-svg
       {:viewBox             (str "0 0 " size " " size)
        :role                "img"
        :aria-label          "QR Code"
        :shapeRendering      "crispEdges"}
       [:rect {:width size :height size :fill "#ffffff"}]
       [:path {:d path-d :fill "#111214"}]])
    (catch :default e
      (log/warn "Failed to render QR code:" e)
      nil)))

(defn ^:ui auth-challenge-card [challenge]
  (r/with-let [now-ms      (r/atom (js/Date.now))
               input-val   (r/atom "")
               submitting? (r/atom false)
               timer       (when (exists? js/window)
                             (.setInterval js/window #(reset! now-ms (js/Date.now)) 1000))]
    (let [{:keys [title message qr-data qr-bytes qr-ecc qr-image code url
                  expires-at action-id state account-name avatar-url input]} challenge
          state-kw       (when state (keyword (str/lower-case (name state))))
          valid-qr-img   (valid-qr-image-data-url qr-image)
          valid-avatar   (valid-challenge-image-url avatar-url)
          qr-payload     (cond
                           (and (string? qr-bytes) (not (str/blank? qr-bytes)))
                           (try (base64->uint8 qr-bytes) (catch :default _ nil))

                           (and (string? qr-data) (not (str/blank? qr-data)))
                           qr-data

                           :else nil)
          has-qr?        (or (some? qr-payload)
                             (some? valid-qr-img))
          ms-left        (when (number? expires-at) (- expires-at @now-ms))
          expired?       (or (= state-kw :expired)
                             (and (number? ms-left) (<= ms-left 0)))
          scanned?       (or (= state-kw :scanned)
                             (= state-kw :confirming)
                             (and (string? account-name) (not (str/blank? account-name))))
          submit-code!   (fn []
                           (let [v (str/trim (str @input-val))]
                             (when-not (str/blank? v)
                               (reset! submitting? true)
                               (re-frame/dispatch [:auth/submit-challenge-input v])
                               (reset! input-val "")
                               (js/setTimeout #(reset! submitting? false) 600))))]
      [:div.auth-challenge-card {:class (when has-qr? "has-qr")}
       (when title
         [:div.auth-challenge-title title])
       (when message
         [:div.auth-challenge-message message])
       (when has-qr?
         [:div.auth-challenge-qr-wrap
          [:div.auth-challenge-qr-frame
           {:class (when (or expired? scanned?) "is-dimmed")}
           (cond
             (some? qr-payload)
             [qr-code-svg qr-payload qr-ecc]

             valid-qr-img
             [:img.auth-challenge-qr-img {:src valid-qr-img :alt "QR Code"}])
           (cond
             expired?
             [:div.auth-challenge-qr-overlay
              [:div.auth-challenge-qr-overlay-text "QR code expired"]
              [:button.btn-primary
               {:type     "button"
                :on-click #(re-frame/dispatch [:auth/refresh-challenge action-id])}
               "Refresh QR"]]

             scanned?
             [:div.auth-challenge-qr-overlay
              (when valid-avatar
                [:img.auth-challenge-account-avatar {:src valid-avatar :alt ""}])
              [:div.auth-challenge-qr-overlay-text
               (if (and (string? account-name) (not (str/blank? account-name)))
                 (str "Confirm on device as " account-name)
                 "Scanned — confirm on your device")]])]])
       (when (and (not has-qr?) scanned? (nil? input))
         [:div.auth-challenge-account
          (when valid-avatar
            [:img.auth-challenge-account-avatar {:src valid-avatar :alt ""}])
          [:span (or account-name "Confirm on your device")]])
       (when (map? input)
         [:div.auth-challenge-input-group
          (when-let [lbl (:label input)]
            [:label.field-label lbl])
          [:div.auth-challenge-input-row
           [:input.field-input.auth-challenge-code-input
            {:type        "text"
             :input-mode  (or (:input-mode input) "text")
             :max-length  (:max-length input)
             :placeholder (or (:placeholder input) "")
             :value       @input-val
             :disabled    @submitting?
             :auto-focus  true
             :on-change   #(reset! input-val (.. % -target -value))
             :on-key-down (fn [e]
                            (when (= (.-key e) "Enter")
                              (.preventDefault e)
                              (submit-code!)))}]
           [:button.btn-primary
            {:type     "button"
             :disabled (or @submitting? (str/blank? (str @input-val)))
             :on-click submit-code!}
            (or (:submit-label input) "Confirm")]]])
       (when (and (number? ms-left) (not expired?) (not scanned?))
         [:div.auth-challenge-expiry
          (str "Expires in " (format-expiry-countdown ms-left))])
       (when code
         [:div.auth-challenge-code code])
       [:div.auth-challenge-actions
        (when-let [safe-url (valid-redirect-http-url url)]
          [:<>
           [:button.engine-btn
            {:type     "button"
             :on-click #(open-auth-url! {:url   safe-url
                                         :title (or title "Sign In")})}
            "Open Window"]
           [:button.engine-btn
            {:type     "button"
             :on-click #(open-auth-url! {:url safe-url :external? true})}
            "Open in Browser"]])
        (when (and has-qr? (not expired?))
          [:button.engine-btn
           {:type     "button"
            :on-click #(re-frame/dispatch [:auth/refresh-challenge action-id])}
           "Refresh QR"])
        [:button.engine-btn
         {:type     "button"
          :on-click #(re-frame/dispatch [:auth/cancel-login])}
         "Cancel"]]])
    (finally
      (when (and timer (exists? js/window))
        (.clearInterval js/window timer)))))

(defn ^:ui login-screen []
  (let [fields (r/atom {:engine nil
                        :show-custom? false
                        :custom-url ""
                        :auth-data {}})]
    (fn []
      (let [tr                @(re-frame/subscribe [:i18n/tr])
            error             @(re-frame/subscribe [:auth/error])
            notice            @(re-frame/subscribe [:auth/notice])
            challenge         @(re-frame/subscribe [:auth/challenge])
            status            @(re-frame/subscribe [:auth/status])
            accounts          @(re-frame/subscribe [:settings/available-accounts])
            available-engines @(re-frame/subscribe [:registry/available-engines])
            proxy-provider    @(re-frame/subscribe [:auth/proxy-provider])
            {:keys [engine show-custom? custom-url auth-data]} @fields
            auth?             (= status :authenticating)
            current-engine    engine
            submit-disabled?  (or auth? (nil? current-engine))
            engine-meta       (get available-engines current-engine)
            requires-proxy?   (true? (get-in engine-meta [:engine-config :login :requires-proxy]))
            all-auth-fields   (get-in engine-meta [:engine-config :login :fields] [])
            auth-fields       (filterv field-visible-on-platform? all-auth-fields)
            raw-register      (get-in engine-meta [:engine-config :login :register])
            register-cfg      (when (and current-engine
                                         (map? raw-register)
                                         (field-visible-on-platform? raw-register)
                                         (or (:action-id raw-register)
                                             (valid-redirect-http-url (:url raw-register))))
                                raw-register)
            build-payload     (fn []
                                (reduce (fn [acc {:keys [id type default]}]
                                          (if (= type :action)
                                            acc
                                            (assoc acc id (get auth-data id default))))
                                        {}
                                        auth-fields))]
        [:div.auth-page-container
         [:div.auth-card
          [:div.auth-header
           [:h2 (tr [:auth/welcome])]
           [:p (tr [:auth/subtitle])]]

          (when error
            [:div.auth-error-message error])

          (when notice
            [:div.auth-notice-message notice])

          (when challenge
            [auth-challenge-card challenge])

          (when (seq accounts)
            [:div.account-chooser.mb-6
             [:h3.text-sm.font-bold.mb-2 (tr [:auth/continue-as])]
             [:div.accounts-list
              (for [acc   accounts
                    :let  [item-key (str (or (:engine acc) "default") ":" (:userId acc))
                           title    (or (not-empty (:label acc))
                                        (not-empty (:display-name acc))
                                        (:userId acc))
                           proto    (some-> (:engine acc) name str/capitalize)
                           srv      (or (:server-url acc)
                                        (:server acc)
                                        (:server_url acc)
                                        (:base-url acc)
                                        "")
                           sub      (cond
                                      (and proto (not (str/blank? srv))) (str proto " · " srv)
                                      proto                              proto
                                      :else                              srv)]]
                ^{:key item-key}
                [:div.account-chooser-item
                 {:on-click #(re-frame/dispatch
                              [:auth/switch-account (:userId acc) (:engine acc)])}
                 [:div.avatar-placeholder [icons/user]]
                 [:div.account-details
                  [:div.account-id title]
                  (when-not (str/blank? sub)
                    [:div.account-server sub])]])]
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

           (when (and requires-proxy? (nil? proxy-provider))
             [:div.auth-proxy-notice
              [:div.auth-proxy-notice-title "Host Proxy Extension Required"]
              [:div.auth-proxy-notice-body
               "This protocol requires the Paradise Proxy browser extension for cross-origin sign-in and media streaming. Install or enable the extension on this origin to continue."]])

           (for [{:keys [id label type default action-id hidden?]} auth-fields
                 :when (not (or (= type :hidden) hidden?))]
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

           (when register-cfg
             [:div.auth-footer
              (or (:prompt register-cfg) (tr [:auth/footer-text]))
              [:a.link
               {:href "#"
                :on-click (fn [e]
                            (.preventDefault e)
                            (when-not auth?
                              (cond
                                (:action-id register-cfg)
                                (re-frame/dispatch [:auth/login-action
                                                    current-engine
                                                    (:action-id register-cfg)
                                                    (build-payload)])

                                (valid-redirect-http-url (:url register-cfg))
                                (open-auth-url! {:url       (:url register-cfg)
                                                 :external? true}))))}
               (or (:label register-cfg) (tr [:auth.actions/register]))]])]]]))))