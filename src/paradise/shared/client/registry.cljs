(ns ^:virtualizer/skip paradise.shared.client.registry
  (:require [reagent.core :as r]
            [cljs-workers.mesh]
            [virtualizer.registry :as vreg]))

(def !components vreg/!components)
(def !pure-fns vreg/!pure-fns)
(def !anon-fns vreg/!anon-fns)
(def !active-overrides vreg/!active-overrides)
(defonce !slots (r/atom {}))
(defonce !live-callbacks (atom {}))

(def resolve-anon-fn vreg/resolve-anon-fn)
(def dehydrate vreg/dehydrate)
(def register-override! vreg/register-override!)
(def clear-overrides! vreg/clear-overrides!)

(defn stash-lambdas [x]
  (cond
    (fn? x) (let [id (keyword (str "cb-" (random-uuid)))]
              (swap! !live-callbacks assoc id x)
              {:__type :plugin-cb :id id})
    (map? x) (into {} (map (fn [[k v]] [k (stash-lambdas v)]) x))
    (vector? x) (mapv stash-lambdas x)
    (seq? x) (doall (map stash-lambdas x))
    :else x))

(defn hydrate-lambdas [x]
  (cond
    (and (map? x) (= (:__type x) :plugin-cb))
    (get @!live-callbacks (:id x))

    (map? x) (into {} (map (fn [[k v]] [k (hydrate-lambdas v)]) x))
    (vector? x) (mapv hydrate-lambdas x)
    (seq? x) (doall (map hydrate-lambdas x))
    :else x))

(defn reg-slot-item [slot-id item]
  (swap! !slots update slot-id
         (fn [items]
           (let [existing (or items [])
                 item-id  (:id item)]
             (conj (if item-id
                     (into [] (remove #(= (:id %) item-id) existing))
                     existing)
                   item)))))

(defn get-slot [slot-id]
  (->> (get @!slots slot-id [])
       (sort-by #(get % :order 0))
       (map (fn [{:keys [id component]}]
              ^{:key (or id (random-uuid))} [component]))))

(defn remove-plugin-overrides! [target-plugin-id]
  (let [target-str (name target-plugin-id)]
    (swap! !active-overrides
           (fn [overrides]
             (into {} (remove (fn [[_ override-data]]
                                (and (map? override-data)
                                     (:plugin-id override-data)
                                     (= (name (:plugin-id override-data)) target-str)))
                              overrides))))))

(defn remove-plugin-slots! [target-plugin-id]
  (let [target-str (name target-plugin-id)]
    (swap! !slots
           (fn [slots]
             (into {}
                   (map (fn [[slot-id items]]
                          [slot-id (into [] (remove #(= (name (:plugin-id % "unknown")) target-str) items))])
                        slots))))))
