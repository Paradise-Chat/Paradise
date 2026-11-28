(ns paradise.shared.sci-runner.engine
  (:require [sci.core :as sci]
            [paradise.shared.sci-runner.shared]
            [cljs-workers.worker :as worker]
            [cljs-workers.mesh :as mesh]
            [paradise.engine.state :as state]
            [paradise.shared.sci-runner.factory :as factory]))

(defonce !current-eval-plugin (atom nil))
(defonce !worker-overrides (atom {}))

(def worker-namespaces
  {'paradise.engine.state {'register-handler state/register-handler
                           '!client          state/!client
                           '!media-cache     state/!media-cache}
   'cljs-workers.mesh     {'do-with-thread! mesh/do-with-thread!}
   'cljs-workers.worker   {'register worker/register}})

(def worker-context
  (factory/build-context
   :worker
   {:!current-plugin   !current-eval-plugin
    :!overrides        !worker-overrides
    :!components       nil
    :!slots            nil
    :!virtualizer-pool nil}
   worker-namespaces))

(defn evaluate-worker-form [plugin-id form-str]
  (reset! !current-eval-plugin plugin-id)
  (try
    (sci/eval-string* worker-context form-str)
    {:status "success"}
    (catch :default e
      {:status "error" :msg (str e)})))
