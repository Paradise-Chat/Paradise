(ns paradise.shared.client.core
  (:require
   [cljs-workers.core :as main]
   [cljs-workers.mesh :as mesh]
   [cljs.core.async :refer [<! chan go promise-chan put!]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [net :as net]
   [paradise.binding.core :as binding]
   [paradise.binding.streams :as streams]
   [paradise.shared.client.capacitor-proxy :as capacitor-proxy]
   [paradise.shared.client.session-store :as store]
   [paradise.shared.client.state :as state]
   [paradise.shared.registry :as registry]
   [paradise.shared.utils.helpers :refer [capacitor-mobile? normalize-keyword ok-status?]]
   [paradise.shared.utils.logger :as logger]
   [paradise.ui.app :as app]
   [re-frame.core :as re-frame]
   [re-frame.db :as db]
   [service-worker-handler :refer [register-sw!]]
   [taoensso.timbre :as log]))

(capacitor-proxy/init!)

(def ^:private media-pool-size 3)

(defonce !retained-channels (atom []))
(defonce !threads-started? (atom false))
(defonce !media-pool-ready (promise-chan))
(defonce !media-broadcast-lock
  (let [c (chan 1)]
    (put! c true)
    c))

(defn- make-channel! []
  (let [ch (js/MessageChannel.)]
    (swap! !retained-channels conj ch)
    ch))

(defn- broadcast-media-pool! [pool make-payload]
  (go
    (<! !media-broadcast-lock)
    (let [workers-ch (:workers pool)
          pool-n     (or (:count pool) media-pool-size)
          taken      (loop [i 0 acc []]
                       (if (< i pool-n)
                         (recur (inc i) (conj acc (<! workers-ch)))
                         acc))]
      (try
        (let [chs (mapv (fn [idx w]
                          (main/do-with-worker! w (make-payload idx)))
                        (range (count taken))
                        taken)]
          (loop [rem chs
                 acc []]
            (if (empty? rem)
              acc
              (recur (rest rem) (conj acc (<! (first rem)))))))
        (finally
          (doseq [w taken]
            (put! workers-ch w))
          (put! !media-broadcast-lock true))))))


(defn init-core-workers! []
  (when-not @state/!virtualizer-pool
    (reset! state/!virtualizer-pool
            (main/create-pool 1 "virtualizer.js"
                              {:worker-opts #js {:type "module"}
                               :on-stream   binding/handle-worker-stream!})))
  (when-not @state/!engine-pool
    (reset! state/!engine-pool
            (main/create-pool 1 "engine.js"
                              {:worker-opts #js {:type "module"}
                               :on-stream   binding/handle-worker-stream!}))))

(defn ensure-media-pool! []
  (or @state/!media-pool
      (reset! state/!media-pool
              (main/create-pool media-pool-size "media.js"
                                {:worker-opts #js {:type "module"}}))))

(defn- send-port! [pool identity-id port]
  (main/do-with-pool! pool
                      {:handler   :register-port
                       :arguments {:identity-id identity-id :port port}
                       :transfer  [:port]}))

(defn bind-workers! [engine-pool virtualizer-pool app-db-payload]
  (let [main-virt      (make-channel!)
        main-eng       (make-channel!)
        main-med-chans (mapv (fn [_] (make-channel!)) (range media-pool-size))
        med-virt-chans (mapv (fn [_] (make-channel!)) (range media-pool-size))
        eng-virt       (make-channel!)
        virt-loop      (make-channel!)
        eng-loop       (make-channel!)
        db-chan        (when (nil? app-db-payload) (make-channel!))
        init-db        (when db-chan
                         (let [db-port1 (.-port1 db-chan)]
                           (db/set-async-broadcaster! (fn [payload] (.postMessage db-port1 payload)))
                           (set! (.-onmessage db-port1) (fn [e] (db/apply-remote-patch! (.-data e))))
                           (db/get-encoded-state)))]
    (mesh/register-thread! :virtualizer-pool (.-port1 main-virt))
    (mesh/register-thread! :engine-pool (.-port1 main-eng))
    (doseq [ch main-med-chans]
      (mesh/register-thread! :media-pool (.-port1 ch)))

    (go
      (if (some? app-db-payload)
        (<! (main/do-with-pool! virtualizer-pool
                                {:handler   :bind-app-db
                                 :arguments {:eve-payload app-db-payload}}))
        (<! (main/do-with-pool! virtualizer-pool
                                {:handler   :bind-app-db
                                 :arguments {:eve-payload {:mode          :async
                                                           :initial-state init-db}
                                             :port        (.-port2 db-chan)}
                                 :transfer  [:port]})))
      (<! (send-port! virtualizer-pool :virtualizer-pool (.-port1 virt-loop)))
      (<! (send-port! virtualizer-pool :virtualizer-loopback (.-port2 virt-loop)))
      (doseq [ch med-virt-chans]
        (<! (send-port! virtualizer-pool :media-pool (.-port2 ch))))
      (<! (send-port! virtualizer-pool :engine-pool (.-port2 eng-virt)))
      (<! (send-port! virtualizer-pool :main-thread (.-port2 main-virt)))

      (let [media-pool (ensure-media-pool!)]
        (<! (broadcast-media-pool!
             media-pool
             (fn [idx]
               {:handler   :register-port
                :arguments {:identity-id :virtualizer-pool
                            :port        (.-port1 (nth med-virt-chans idx))}
                :transfer  [:port]})))
        (<! (broadcast-media-pool!
             media-pool
             (fn [idx]
               {:handler   :register-port
                :arguments {:identity-id :main-thread
                            :port        (.-port2 (nth main-med-chans idx))}
                :transfer  [:port]})))
        (put! !media-pool-ready media-pool)))

    (go
      (<! (send-port! engine-pool :engine-pool (.-port1 eng-loop)))
      (<! (send-port! engine-pool :engine-loopback (.-port2 eng-loop)))
      (<! (send-port! engine-pool :virtualizer-pool (.-port1 eng-virt)))
      (<! (send-port! engine-pool :main-thread (.-port2 main-eng))))))

(defn boot-threads []
  (when (compare-and-set! !threads-started? false true)
    (init-core-workers!)
    (bind-workers! @state/!engine-pool
                   @state/!virtualizer-pool
                   nil)))

(defn- broadcast-engine-and-media! [handler arguments]
  (go
    (let [eng-ch    (mesh/do-with-thread! :engine-pool
                                          {:handler   handler
                                           :arguments arguments})
          mp        (<! !media-pool-ready)
          media-res (<! (broadcast-media-pool!
                         mp
                         (fn [_]
                           {:handler   handler
                            :arguments arguments})))
          eng-res   (<! eng-ch)]
      {:engine eng-res
       :media  media-res})))

(defn- dispatch-logged-out! []
  (net/set-auth-context! nil nil)
  (net/clear-stream-url!)
  (net/clear-frame-url!)
  (re-frame/dispatch [:auth/clear-sw-session {:all? true}])
  (re-frame/dispatch [:auth/set-status :logged-out])
  (re-frame/dispatch [:settings/load-accounts]))

(re-frame/reg-event-fx
 :app/thread-boot
 (fn [_ _]
   (boot-threads)
   {}))

(re-frame/reg-event-fx
 :app/bootstrap
 (fn [{:keys [db]} [_ target-user-id engine-id expected-gen]]
   (let [db-engine (:active-engine db)
         gen       (or expected-gen (:session/gen db))]
     (go
       (try
         (let [saved-eng       (when-not (or engine-id db-engine)
                                 (try
                                   (<p! (store/get-setting "last-active-engine"))
                                   (catch :default _ nil)))
               fallback-engine (or (normalize-keyword engine-id)
                                   (normalize-keyword db-engine)
                                   (normalize-keyword saved-eng)
                                   :matrix)
               boot-res        (<! (mesh/do-with-thread! :engine-pool
                                                         {:handler   :bootstrap
                                                          :arguments {:target-user-id target-user-id}}))
               status-kw       (keyword (:status boot-res))
               final-engine    (or (normalize-keyword (:engine-id boot-res))
                                   fallback-engine)]
           (case status-kw
             :success (re-frame/dispatch [:auth/login-success
                                          (assoc boot-res
                                                 :engine-id    final-engine
                                                 :expected-gen gen)])
             :empty   (dispatch-logged-out!)
             :error   (do
                        (log/error "Bootstrap failed:"
                                   (or (:msg boot-res) (:message boot-res) (:status boot-res)))
                        (dispatch-logged-out!))))
         (catch :default e
           (log/error "Fatal bootstrap error:" e)
           (dispatch-logged-out!)))))
   {}))

(defn register-engine [protocol-id]
  (let [protocol-str (name protocol-id)]
    (go
      (let [{eng-res :engine media-res :media}
            (<! (broadcast-engine-and-media! :register-engine
                                             {:engine-id protocol-str}))]
        (if (and (ok-status? eng-res)
                 (every? ok-status? media-res))
          (do
            (js/console.log "Engine and media pool loaded successfully:" protocol-str)
            (re-frame/dispatch [:i18n/load-engine-extensions (keyword protocol-str)]))
          (js/console.error "Failed to load engine:"
                            {:engine (:msg eng-res)
                             :media  media-res}))))))

(re-frame/reg-event-fx
 :engine/load-protocol
 (fn [_ [_ protocol-str]]
   (register-engine protocol-str)
   {}))

(re-frame/reg-event-fx
 :sdk/start-sync
 (fn [{:keys [db]} [_ expected-gen]]
   (let [gen (or expected-gen (:session/gen db))]
     (go
       (let [res (<! (mesh/do-with-thread! :engine-pool {:handler :start-sync}))]
         (if (ok-status? res)
           (do
             (log/info "Main sync loop started successfully.")
             (re-frame/dispatch [:session/on-sync-ready gen])
             (re-frame/dispatch [:search/refresh-index-status]))
           (do
             (log/error "Failed to start sync:" (:msg res))
             (re-frame/dispatch [:session/on-sync-ready gen]))))))
   {:db (dissoc db :search/index-status)}))

(defn sync-worker-media-session! [{:keys [user-id server-url token]}]
  (go
    (let [{e-res :engine m-res :media}
          (<! (broadcast-engine-and-media! :set-media-session
                                           {:user-id    user-id
                                            :server-url server-url
                                            :token      token}))
          m-hit (some #(when (ok-status? %) %) m-res)]
      (or m-hit
          (when (ok-status? e-res) e-res)))))

(defn clear-worker-media-session! [opts]
  (go
    (:engine (<! (broadcast-engine-and-media! :clear-media-session
                                              (or opts {:all? true}))))))


(re-frame/reg-event-fx
 :engine/sync-media-session
 (fn [_ [_ {:keys [user-id server-url token]}]]
   (go
     (when-let [res (<! (sync-worker-media-session!
                         {:user-id    user-id
                          :server-url server-url
                          :token      token}))]
       (when (and (or (nil? server-url) (nil? token))
                  (:base-url res)
                  (:token res))
         (re-frame/dispatch [:auth/apply-hydrated-net-context res]))))
   {}))

(re-frame/reg-event-fx
 :engine/clear-media-session
 (fn [_ [_ opts]]
   (clear-worker-media-session! opts)
   {}))

(defn- activate-engine! [engine-kw]
  (go
    (<! (register-engine engine-kw))
    (binding/set-active-engine! engine-kw)
    (streams/init-engine-streams! engine-kw)))

(re-frame/reg-event-fx
 :engine/switch-account-boot
 [(state/guard-session-gen #(-> % second :expected-gen))]
 (fn [{:keys [db]} [_ {:keys [target-user-id engine-id prev-engine-id expected-gen]}]]
   (let [engine-kw (keyword (or engine-id :matrix))
         prev-kw   (some-> prev-engine-id keyword)]
     (go
       (when prev-kw
         (try
           (<! (mesh/do-with-thread!
                :engine-pool
                {:handler   (keyword (name prev-kw) "suspend-session")
                 :arguments {}}))
           (catch :default _ nil)))
       (<! (clear-worker-media-session! {:all? true}))
       (<! (activate-engine! engine-kw))
       (re-frame/dispatch [:app/bootstrap target-user-id engine-kw expected-gen]))
     {:db (assoc db :active-engine engine-kw)})))

(re-frame/reg-event-fx
 :app/cold-boot
 (fn [{:keys [db]} [_ target-user-id requested-engine]]
   (let [gen (:session/gen db)]
     (go
       (let [saved-engine (<p! (store/get-setting "last-active-engine"))
             engine-id    (when-let [e (or requested-engine saved-engine)]
                            (keyword e))]
         (when engine-id
           (<! (activate-engine! engine-id)))
         (log/info "Cold boot sequence initiated. Targeting engine:" engine-id)
         (if engine-id
           (re-frame/dispatch [:app/bootstrap target-user-id engine-id gen])
           (dispatch-logged-out!)))))
   (cond-> {}
     requested-engine
     (assoc :db (assoc db :active-engine (keyword requested-engine))))))

(re-frame/reg-event-fx
 :sdk/ignite-session
 (fn [{:keys [db]} [_ expected-gen]]
   (let [gen (or expected-gen (:session/gen db))]
     {:fx [[:dispatch [:sdk/start-sync gen]]
           [:dispatch [:sdk/fetch-own-profile]]
           [:dispatch [:sdk/fetch-all-emotes]]]})))

(defn ^:export init []
  (binding/init-middleware!)
  (boot-threads)
  (app/init)
  (logger/init!)
  (go
    (<! (registry/ensure-environment!))
    (re-frame/dispatch [:registry/load-success (registry/resolve-engines)])
    (re-frame/dispatch [:registry/sync-manifests])
    (when-not (capacitor-mobile?)
      (js/setTimeout register-sw! 1000))))