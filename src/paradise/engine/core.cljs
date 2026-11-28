(ns paradise.engine.core
  (:require [cljs-workers.worker :as worker]
            [cljs.core.async :refer [<! go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [clojure.string :as str]
            [goog.object :as gobj]
            [paradise.shared.client.session-store :as session-store]
            [paradise.shared.registry :as registry]
            [paradise.shared.sci-runner.engine :as sci]
            [paradise.shared.worker-loader :as worker-loader]))

(defonce !loaded-engines (atom {}))
(defonce !protocol-handlers (atom {}))
(defonce !current-protocol (atom nil))

(defn register-engine! [id handler-map]
  (swap! !loaded-engines assoc (keyword id) (js->clj handler-map :keywordize-keys true)))

(defn get-fallback-bootstrap [protocol]
  (let [prop-name (str (str/capitalize protocol) "EngineBootstrap")]
    (gobj/get js/globalThis prop-name)))

(defn- suspend-previous-protocol! [next-kw]
  (let [prev-kw @!current-protocol]
    (when (and prev-kw (not= prev-kw next-kw))
      (when-let [suspend-fn (get-in @!protocol-handlers [prev-kw :suspend-session])]
        (try
          (suspend-fn {})
          (catch :default _ nil))))))

(defn load-protocol-engine! [protocol]
  (go
    (let [{:keys [status protocol-name proto-kw url manifest] :as res}
          (<! (worker-loader/ensure-protocol-handlers!
               !protocol-handlers
               protocol
               {:on-before-switch suspend-previous-protocol!}))]
      (if (= "error" status)
        res
        (try
          (if-let [bootstrap-fn (get-fallback-bootstrap protocol-name)]
            (let [restore-fetch! (worker-loader/wrap-bootstrap-asset-fetch! protocol-name manifest)]
              (try
                (<! (bootstrap-fn register-engine! url))
                (reset! !current-protocol proto-kw)
                {:status "success"}
                (finally
                  (restore-fetch!))))
            {:status "error"
             :msg    (str "Engine module did not export a bootstrap function: "
                          protocol-name)})
          (catch :default e
            {:status "error" :msg (or (.-message e) (str e))}))))))

(worker/register :register-engine
                 (fn [payload]
                   (go
                     (try
                       (let [protocol-str (if (map? payload) (:engine-id payload) payload)]
                         (if protocol-str
                           (let [protocol-name (name protocol-str)
                                 load-res      (<! (load-protocol-engine! protocol-name))]
                             (if (= "error" (:status load-res))
                               load-res
                               (let [engine-map (get @!loaded-engines (keyword protocol-name))
                                     preload-fn (:preload engine-map)]
                                 (when preload-fn
                                   (preload-fn))
                                 {:status "success" :msg (str "Registered engine " protocol-name)})))
                           {:status "error" :msg "No engine provided."}))
                       (catch :default e
                         {:status "error" :msg (.-message e)})))))

(worker/register :list-all-stored-accounts
                 (fn [_]
                   (go
                     (try
                       (<! (registry/ensure-environment!))
                       (let [seed-engines (keys (registry/resolve-engines))
                             accounts     (<p! (session-store/list-all-stored-accounts seed-engines))]
                         {:status   "success"
                          :accounts accounts})
                       (catch :default e
                         {:status "error"
                          :msg    (or (.-message e) (str e))})))))

(worker/register :evaluate-worker-form
                (fn [{:keys [plugin-id code]}]
                  (sci/evaluate-worker-form plugin-id code)))

(worker/bootstrap)