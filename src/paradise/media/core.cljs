
(ns paradise.media.core
  (:require [cljs.core.async :refer [go <!]]
            [cljs-workers.worker :as worker]
            [cljs.core.async.interop :refer-macros [<p!]]
            [shadow.esm :refer [dynamic-import]]
            [paradise.shared.registry :as registry]))

(defonce !loaded-media-engines (atom #{}))

(defn load-protocol-media! [protocol]
  (go
    (<! (registry/ensure-environment!))
    (let [proto-kw (keyword protocol)]
      (when-not (contains? @!loaded-media-engines proto-kw)
        (if-let [url (registry/get-engine-url protocol)]
          (do
            (<p! (dynamic-import url))
            (swap! !loaded-media-engines conj proto-kw))
          (throw (js/Error. (str "Unknown protocol or missing registry URL: " protocol))))))))

(worker/register
 :register-engine
 (fn [payload]
   (go
     (try
       (let [protocol-str (if (map? payload) (:engine-id payload) payload)]
         (if protocol-str
           (let [protocol-name (name protocol-str)]
             (<! (load-protocol-media! protocol-name))
             {:status "success" :msg (str "Registered media handlers for " protocol-name)})
           {:status "error" :msg "No engine provided."}))
       (catch :default e
         {:status "error" :msg (.-message e)})))))

(worker/bootstrap)
