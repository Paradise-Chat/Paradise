(ns paradise.shared.sci-runner.ui
  (:require
   [cljs-workers.mesh :as mesh]
   [paradise.shared.client.registry :as registry]
   [paradise.shared.client.state :as state]
   [paradise.shared.sci-runner.factory :as factory]
   [paradise.shared.sci-runner.ui-common :as ui-common]
   [sci.core :as sci]
   [sci.ctx-store :as ctx-store]))

(defonce !current-eval-plugin (atom nil))

(declare evaluate-ui-form)

(def ui-namespaces
  (ui-common/build-ui-namespaces
   !current-eval-plugin
   {'paradise.shared.sci-runner.ui {'!current-eval-plugin !current-eval-plugin
                                    'evaluate-ui-form     (fn [plugin-id form-str]
                                                            (evaluate-ui-form plugin-id form-str))}
    'paradise.shared.client.state  {'!config      state/!config
                                    '!engine-pool state/!engine-pool}}))

(def ui-context
  (factory/build-context
   :ui
   {:!current-plugin   !current-eval-plugin
    :!overrides        registry/!active-overrides
    :!components       registry/!components
    :!slots            registry/!slots
    :!virtualizer-pool state/!virtualizer-pool}
   ui-namespaces))

(ctx-store/reset-ctx! ui-context)

(defn evaluate-ui-form [plugin-id form-str]
  (let [prev-plugin @!current-eval-plugin]
    (reset! !current-eval-plugin plugin-id)
    (try
      (let [result (sci/eval-string* ui-context form-str)]
        (mesh/do-with-thread! :virtualizer-pool
                              {:handler   :eval-virtualizer-plugin
                               :arguments {:plugin-id plugin-id
                                           :code      form-str}})
        result)
      (finally
        (reset! !current-eval-plugin prev-plugin)))))