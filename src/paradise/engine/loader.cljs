(ns paradise.engine.loader
  (:require
   [cljs.core.async :refer [go]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [clojure.edn :as edn]
   [re-frame.core :as rf]))

(defn load-engine-manifest [url]
  (go
    (let [response (<p! (js/fetch url))
          raw-text (<p! (.text response))
          manifest (edn/read-string raw-text)]
      (rf/dispatch [:engine/register-manifest manifest]))))

(rf/reg-event-db
 :engine/register-manifest
 (fn [db [_ manifest]]
   (assoc-in db [:engines (:module-id manifest)] manifest)))

(rf/reg-sub
 :engine/field-options
 (fn [db [_ module-id]]
   (get-in db [:engines module-id :engine-config :fields])))
