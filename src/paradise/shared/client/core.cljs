(ns paradise.shared.client.core
  (:require
   [paradise.shared.utils.logger :as logger]
   [re-frame.core :as re-frame]
   [goog.object]
   [paradise.ui.app :as app]
   ["react" :as react]
   [re-frame.db :as db]
   [reagent.core :as r]
   [promesa.core :as p]
   [paradise.binding.core :as binding]
   [paradise.binding.streams :as streams]
   [paradise.shared.client.state :as state]
   [paradise.shared.client.config :refer [eve-enabled?]]
   [paradise.shared.client.session-store :as store]
   [cljs-workers.core :as main]
   [cljs-workers.mesh :as mesh]
   [cljs.core.async.interop :refer-macros [<p!]]
   [cljs.core.async :refer [go <!]]
   [taoensso.timbre :as log]))

(defn init-worker! []
  (go
    (when-not @state/!engine-pool
    (reset! state/!engine-pool
            (main/create-pool 1 "engine.js"
                              {:worker-opts #js {:type "module"}
                               :on-stream binding/handle-worker-stream!})))
  (when-not @state/!media-pool
    (reset! state/!media-pool
            (main/create-pool 3 "media.js"
                              {:worker-opts #js {:type "module"}})))

  (when-not @state/!virtualizer-pool
    (reset! state/!virtualizer-pool
             (main/create-pool 1 "virtualizer.js"
                              {:worker-opts #js {:type "module"}
                               :on-stream binding/handle-worker-stream!})
            ))))

(defn connect-main-to-pool! [pool pool-id]
  (let [chan (js/MessageChannel.)]
    (mesh/register-thread! pool-id (.-port1 chan))
    (main/do-with-pool! pool
                        {:handler :register-port
                         :arguments {:identity-id :main-thread :port (.-port2 chan)}
                         :transfer [:port]})))

(defn connect-pools! [pool-a id-a pool-b id-b]
  (let [chan (js/MessageChannel.)]
    (main/do-with-pool! pool-a
                        {:handler :register-port
                         :arguments {:identity-id id-b :port (.-port1 chan)}
                         :transfer [:port]})
    (main/do-with-pool! pool-b
                        {:handler :register-port
                         :arguments {:identity-id id-a :port (.-port2 chan)}
                         :transfer [:port]})))

(defn connect-loopback! [pool id-base loopback-id]
  (let [chan (js/MessageChannel.)]
    (main/do-with-pool! pool
                        {:handler :register-port
                         :arguments {:identity-id id-base :port (.-port1 chan)}
                         :transfer [:port]})
    (main/do-with-pool! pool
                        {:handler :register-port
                         :arguments {:identity-id loopback-id :port (.-port2 chan)}
                         :transfer [:port]})))


(defn bind-workers! [engine-pool media-pool virtualizer-pool app-db-payload]
  (go
    (let [db-chan  (js/MessageChannel.)
          db-port1 (.-port1 db-chan)
          db-port2 (.-port2 db-chan)]
      (if (some? app-db-payload)
        (main/do-with-pool! virtualizer-pool
                            {:handler   :bind-app-db
                             :arguments {:eve-payload app-db-payload}})
        (do
          (db/set-async-broadcaster! (fn [payload] (.postMessage db-port1 payload)))
          (set! (.-onmessage db-port1) (fn [e] (db/apply-remote-patch! (.-data e))))

          (main/do-with-pool! virtualizer-pool
                              {:handler   :bind-app-db
                               :arguments {:eve-payload {:mode :async
                                                         :initial-state (db/get-encoded-state)}
                                           :port        db-port2}
                               :transfer  [:port]}))))

    (connect-pools! engine-pool :engine-pool virtualizer-pool :virtualizer-pool)
    (connect-pools! media-pool :media-pool virtualizer-pool :virtualizer-pool)

    (connect-main-to-pool! media-pool :media-pool)
    (connect-main-to-pool! engine-pool :engine-pool)
    (connect-main-to-pool! virtualizer-pool :virtualizer-pool)

    (connect-loopback! virtualizer-pool :virtualizer-pool :virtualizer-loopback)
    (connect-loopback! engine-pool :engine-pool :engine-loopback)))

(re-frame/reg-event-fx
 :app/thread-boot
 (fn [_]
   (init-worker!)
     (let [engine-pool      @state/!engine-pool
           media-pool       @state/!media-pool
           virtualizer-pool @state/!virtualizer-pool]
       (bind-workers! engine-pool media-pool virtualizer-pool nil)
       )
   {}
   ))

(defn boot-threads []
  (go
    (<! (init-worker!))
    (let [engine-pool      @state/!engine-pool
          media-pool       @state/!media-pool
          virtualizer-pool @state/!virtualizer-pool]
      (<! (bind-workers! engine-pool media-pool virtualizer-pool nil)))))

(re-frame/reg-event-fx
 :app/bootstrap
 (fn [_ [_ target-user-id engine-id]]
   (go
       (try
         (let [boot-res  (<! (mesh/do-with-thread! :engine-pool
                                                 {:handler :bootstrap
                                                  :arguments {:target-user-id target-user-id}}))
               status-kw (keyword (:status boot-res))]
           (case status-kw
             :success (re-frame/dispatch [:auth/login-success (assoc boot-res :engine-id engine-id)])
             :empty   (re-frame/dispatch [:auth/set-status :logged-out])
             :error   (log/error "Bootstrap failed:" (or (:msg boot-res) (:message boot-res) boot-res))))
         (catch :default e
           (log/error "Fatal bootstrap error:" e))))
   {}))

(defn register-engine [protocol-str]
  (go
    (let [res (<! (mesh/do-with-thread! :engine-pool
                                        {:handler :register-engine
                                         :arguments {:engine-id protocol-str}}))]
      (if (= "success" (:status res))
        (js/console.log "Engine loaded successfully:" protocol-str)
        (js/console.error "Failed to load engine:" (:msg res))))))

(re-frame/reg-event-fx
 :engine/load-protocol
 (fn [_ [_ protocol-str]]
   (register-engine protocol-str)
   {}))

(re-frame/reg-event-fx
 :sdk/start-sync
 (fn [_ _]
   (go
     (let [pool @state/!engine-pool
           res  (<! (main/do-with-pool! pool {:handler :start-sync}))]
       (if (= (:status res) "success")
         (log/info "Main sync loop started successfully.")
         (log/error "Failed to start sync:" (:msg res)))))
   {}))

(re-frame/reg-event-fx
 :app/cold-boot
 (fn [_ [_ target-user-id requested-engine]]
   (go
     (let [saved-engine (<p! (store/get-setting "last-active-engine"))
           engine-id    (when-let [e (or requested-engine saved-engine)]
                          (keyword e))]

       (when engine-id
         (<! (register-engine engine-id))
         (binding/set-active-engine! engine-id)
         (streams/init-engine-streams! engine-id))

       (log/info "Cold boot sequence initiated. Targeting engine:" engine-id)

       (if (and target-user-id engine-id)
         (re-frame/dispatch [:app/bootstrap target-user-id engine-id])
         (re-frame/dispatch [:auth/set-status :logged-out]))))
   {}))

(re-frame/reg-event-fx
 :sdk/ignite-session
 (fn [_ _]
   {:fx [[:dispatch [:sdk/start-sync]]
         [:dispatch [:sdk/fetch-own-profile]]
         [:dispatch [:sdk/fetch-all-emotes]]]}))

(defn ^:export init []
  (go
    (binding/init-middleware!)
#_  (init-worker!)
  (<! (boot-threads))
  (app/init)
  (logger/init!)
  (log/debug  "Entering Paradise!")))