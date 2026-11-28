(ns paradise.binding.core
  (:require [clojure.set :as set]
            [goog.object]
            [paradise.shared.net.proxy :as net-proxy]
            [taoensso.timbre :as log]
            [cljs-workers.helpers :as workers-helpers]))

(defonce !stream-handlers (atom {}))

(defn register-handlers!
  "Allows an engine plugin to register its UI stream listeners."
  [engine-id handlers]
  (swap! !stream-handlers update engine-id merge handlers))

(defonce !active-engine (atom nil))
(defonce !streams-paused? (atom false))

(defn set-streams-paused! [paused?]
  (reset! !streams-paused? (boolean paused?)))

(defn handle-worker-stream! [data]
  (when-not @!streams-paused?
    (let [is-map?       (map? data)
          msg-type      (if is-map? (:type data) (goog.object/get data "type"))
          raw-engine-id (if is-map? (get data :engine-id) (goog.object/get data "engine-id"))
          active-eng    @!active-engine
          stale-engine? (and raw-engine-id
                             active-eng
                             (not= (keyword raw-engine-id) active-eng))
          engine-id     (keyword (or raw-engine-id active-eng))
          get-val       (fn [k-str k-kw]
                          (if is-map? (get data k-kw) (goog.object/get data k-str)))]
      (when-not stale-engine?
        (if-let [handler (get-in @!stream-handlers [engine-id msg-type])]
          (handler data get-val)
          (when msg-type
            (log/warn "Unhandled stream event:" msg-type "for engine:" engine-id)))))))

(defn set-active-engine! [engine-id]
  (reset! !active-engine engine-id))

(def internal-handlers
  (set/union
   #{:register-port
     :bind-app-db
     :register-engine
     :evaluate-worker-form
     :list-all-stored-accounts
     :recalculate-timeline
     :set-viewport
     :recalculate-items
     :set-scrolling-state
     :update-layout-context
     :process-timeline-diff
     :eval-virtualizer-plugin}
   net-proxy/rpc-handler-keys))

(defn rpc-interceptor [payload]
  (if-let [handler (:handler payload)]
    (if (or (namespace handler)
            (internal-handlers handler)
            (nil? @!active-engine))
      payload
      (assoc payload :handler (keyword (name @!active-engine) (name handler))))
    payload))

(defn init-middleware! []
  (workers-helpers/set-request-interceptor! rpc-interceptor))