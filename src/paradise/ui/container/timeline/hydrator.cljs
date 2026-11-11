(ns ^:virtualizer/skip paradise.ui.container.timeline.hydrator
  (:require [goog.object :as gobj]
            [reagent.core :as r]
            [re-frame.core :as re-frame]
            [paradise.shared.client.state :refer [!ast-handoff]]
            [virtualizer.client.hydrator :as vhydrator]))

(defonce !local-timeline-data (r/atom {}))

(def get-cached-react-tree vhydrator/get-cached-react-tree)
(def hydrate-by-paths! vhydrator/hydrate-by-paths!)
(def pojo->react vhydrator/pojo->react)
(def fast-js->clj-dmp vhydrator/fast-js->clj)

(def sanitize-js-dispatch
  (re-frame/->interceptor
   :id :sanitize-js-dispatch
   :before (fn [context]
             (update-in context [:coeffects :event] fast-js->clj-dmp))))

(re-frame/reg-global-interceptor sanitize-js-dispatch)

(re-frame/reg-event-fx
 :timeline/process-virtualized-data
 (fn [{:keys [db]} [_ room-id source]]
   (try
     (when-let [{:keys [ast-nodes paths]} (get @!ast-handoff room-id)]
       (let [compiled (vhydrator/hydrate-stream-batch! ast-nodes paths)]
         (swap! !local-timeline-data assoc-in [room-id (keyword source)] compiled)
         (swap! !ast-handoff dissoc room-id)))
     {:db (assoc db :last-worker-paint (js/Date.now))}
     (catch :default e
       (js/console.error "Error within Timeline Handler:" e)
       {:db db}))))
