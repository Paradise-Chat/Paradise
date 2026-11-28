(ns ^:virtualizer/skip paradise.shared.utils.haptics
  (:require ["@capacitor/haptics" :refer [Haptics ImpactStyle]]
            [paradise.shared.utils.platform :as platform]))

(defn- impact-style-for [kind]
  (case kind
    :heavy  (.-Heavy ImpactStyle)
    :medium (.-Medium ImpactStyle)
    (.-Light ImpactStyle)))

(defn- vibrate-ms-for [kind]
  (cond
    (number? kind) kind
    (= kind :heavy) 24
    (= kind :medium) 16
    (= kind :selection) 10
    :else 12))

(defn trigger-haptic!
  "Triggers a short haptic pulse (`:light`, `:medium`, `:heavy`, `:selection`, or ms)."
  ([]
   (trigger-haptic! :light))
  ([kind]
   (try
     (if (platform/native-platform?)
       (if (= kind :selection)
         (-> (.selectionChanged Haptics)
             (.catch (fn [_] nil)))
         (-> (.impact Haptics #js {:style (impact-style-for kind)})
             (.catch (fn [_] nil))))
       (when (and (exists? js/navigator) (fn? (.-vibrate js/navigator)))
         (.vibrate js/navigator (vibrate-ms-for kind))))
     (catch :default _
       nil))))