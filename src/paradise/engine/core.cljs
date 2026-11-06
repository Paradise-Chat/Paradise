(ns paradise.engine.core
  (:require
   [clojure.string :as str]
   [cljs.core.async :refer [go <!]]
   [cljs-workers.worker :as worker]
   [goog.object :as gobj]
   [cljs.core.async.interop :refer-macros [<p!]]
   [shadow.esm :refer [dynamic-import]]
   [paradise.shared.registry :as registry]
   [paradise.shared.sci-runner.engine :as sci]
   ))

(defonce !loaded-engines (atom {}))

(defn register-engine! [id handler-map]
  (swap! !loaded-engines assoc (keyword id) (js->clj handler-map :keywordize-keys true)))

(defn get-fallback-bootstrap [protocol]
  (let [prop-name (str (str/capitalize protocol) "EngineBootstrap")]
    (js/console.error prop-name)
    (gobj/get js/globalThis prop-name)))

(defn load-protocol-engine! [protocol]
  (go
    (<! (registry/ensure-environment!))
    (try
      (let [url (registry/get-engine-url protocol)]
        (if-not url
          {:status "error" :msg (str "Unknown protocol or missing registry URL: " protocol)}
          (let [_            (<p! (dynamic-import url))
                bootstrap-fn (get-fallback-bootstrap protocol)]
            (if bootstrap-fn
              (do
                (<! (bootstrap-fn register-engine! url))
                {:status "success"})
              {:status "error" :msg (str "Engine module did not export a bootstrap function: " protocol)}))))
      (catch :default e
        {:status "error" :msg (or (.-message e) (str e))}))))

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

(worker/register :evaluate-worker-form
                (fn [{:keys [plugin-id code]}]
                  (sci/evaluate-worker-form plugin-id code)))

(worker/bootstrap)