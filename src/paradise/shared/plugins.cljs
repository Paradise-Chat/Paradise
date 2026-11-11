(ns paradise.shared.plugins
  (:require
   [re-frame.core :as re-frame]
   [paradise.shared.client.registry :as registry]
   ))

(defn build-wrapped-component [base-comp wrappers]
  (reduce (fn [acc-comp plugin]
            (fn [props]
              ((:wrapper plugin) acc-comp props)))
          base-comp
          (sort-by #(get % :priority (get % :order 0)) wrappers)))

(def memoized-wrapped-component (memoize build-wrapped-component))

(defn plugin-slot [slot-id props & children]
  (let [plugins   (sort-by #(get % :order 0) (get @registry/!slots slot-id []))
        wrappers  (filter :wrapper plugins)
        injectors (filter :component plugins)
        base-comp (fn [_] (into [:<>] children))]
    [:<>
     (if (seq wrappers)
       [(memoized-wrapped-component base-comp wrappers) props]
       (into [:<>] children))
     (for [{:keys [id component]} injectors]
       ^{:key (or id (hash component))} [component props])]))

(re-frame/reg-event-db
 :plugin/register
 (fn [db [_ slot-id plugin-map]]
   (update-in db [:plugins slot-id] (fnil conj []) plugin-map)))

(re-frame/reg-event-db
 :plugin/deregister
 (fn [db [_ slot-id plugin-id]]
   (update-in db [:plugins slot-id]
              (fn [plugins]
                (into [] (remove #(= (:id %) plugin-id) plugins))))))

(re-frame/reg-sub
 :plugins/all
 (fn [db _]
   (:plugins db)))

(re-frame/reg-sub
 :plugins/by-slot
 :<- [:plugins/all]
 (fn [plugins [_ slot-id]]
   (get plugins slot-id [])))
