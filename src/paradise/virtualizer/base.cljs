(ns ^:virtualizer/skip paradise.virtualizer.base
  (:require [virtualizer.worker.dom-shim]
            [cljs-workers.worker :as worker]
            [paradise.shared.sci-runner.virtualizer :as sci-runner]
            [taoensso.timbre :as log]
            [paradise.virtualizer.state :as state]
            [re-frame.db :as db]
            [re-frame.core :as re-frame]
            [cognitect.transit :as t]
            [editscript.core :as e]
            [editscript.edit :as edit]
            [paradise.ui.container.timeline.base]
            [paradise.ui.navigation.rooms.room-list]
            [paradise.ui.app]
            [paradise.ui.container.timeline.item]
            [paradise.ui.container.members]
            [virtualizer.spec :as vspec]
            [virtualizer.worker.ast :as ast]
            [virtualizer.worker.pretext :as pretext]
            [virtualizer.worker.engine :as engine]
            [paradise.virtualizer.spec :as spec]))

(re-frame.loggers/set-loggers! {:warn (fn [& _])})

(ast/install-dom-shims!)

(def !event-cache (atom {}))
(def !layout-context (atom {}))
(def !room-events (atom {}))
(defonce !applying-remote-patch? (atom false))
(defonce !sync-timer (atom nil))

(defn recalculate-and-stream! [room-id source]
  (let [t-spec       (spec/timeline-layout source)
        raw-events   (get-in @!room-events [room-id source] [])
        ctx          (get @!layout-context room-id {:width 800 :theme {:font "400 15.2px Inter, sans-serif" :line-height 22.8} :measured {}})
        layout       (engine/apply-layout t-spec raw-events (:width ctx) (:theme ctx) (:measured ctx))
        laid-out     (engine/strip-layout-metadata (:items layout))
        ast-nodes    (ast/build-ast-tree t-spec laid-out)
        js-ast       (to-array
                      (map (fn [node]
                             #js {"id"           (:id node)
                                  "bottom"       (:bottom node)
                                  "height"       (:height node)
                                  "calc-height"  (:calc-height node)
                                  "layout-width" (:layout-width node)
                                  "type"         (:type node)
                                  "ts"           (:ts node)
                                  "unread?"      (:unread? node)
                                  "is-local?"    (boolean (:is-local? node))
                                  "is-own?"      (boolean (:is-own? node))
                                  "send-state"   (when-let [ss (:send-state node)]
                                                   (if (keyword? ss) (name ss) (str ss)))
                                  "read-by"      (to-array (:read-by node []))
                                  "worker-data"  (:worker-data node)})
                           ast-nodes))
        lambda-paths (ast/collect-batch-lambda-paths ast-nodes)
        ]
    (worker/stream!
     js/self
     #js {"type"         "timeline-ready"
          "room-id"      room-id
          "source"       source
          "ast-nodes"    js-ast
          "lambda-paths" lambda-paths}
     true)))

(defn queue-defer-check! []
  (when-let [t @!sync-timer]
    (js/cancelAnimationFrame t))
  (reset! !sync-timer
          (js/requestAnimationFrame
           (fn []
             (let [changed-ids (ast/check-deferred-components!)]
               (when (seq changed-ids)
                 (doseq [r-id (keys @!room-events)]
                   (swap! !layout-context update-in [r-id :measured]
                          (fn [m]
                            (reduce (fn [acc id]
                                      (dissoc acc id (keyword id) (str id)))
                                    (or m {})
                                    changed-ids)))
                   (doseq [src (keys (get @!room-events r-id))]
                     (recalculate-and-stream! r-id src)))))))))

(defn process-timeline-redraw! []
  (ast/flush-ast-cache!)
  (doseq [room-id (keys @!room-events)
          source  (keys (get @!room-events room-id))]
    (recalculate-and-stream! room-id source)))

(defn precompile-and-cache-event [t-spec e room-id]
  (let [eid    (vspec/extract-id t-spec e)
        cached (get @!event-cache eid)]
    (if (and cached (= (:raw cached) e))
      cached
      (let [new-event (vspec/precompile-item t-spec e room-id pretext/pretext-api)]
        (swap! !event-cache assoc eid (assoc new-event :raw e))
        (when cached
          (swap! ast/!item-revisions update eid (fnil inc 0))
          (swap! ast/!ast-node-cache dissoc eid)
          (swap! !layout-context update-in [room-id :measured]
                 dissoc eid (keyword eid) (str eid)))
        new-event))))

(defn process-timeline-events [t-spec events room-id]
  (mapv #(precompile-and-cache-event t-spec % room-id) events))

(worker/register :bind-app-db
                 (fn [{:keys [eve-payload ports]}]
                   (let [mode          (keyword (:mode eve-payload))
                         initial-state (:initial-state eve-payload)
                         db-port       (first ports)]
                     (when (= mode :async)
                       (let [reader    (t/reader :json)
                             writer    (t/writer :json)
                             start-db  (if initial-state (t/read reader initial-state) {})
                             !local-db (atom start-db)]
                         (set! (.-onmessage db-port)
                               (fn [msg]
                                 (let [edits (t/read reader (.-data msg))]
                                   (reset! !applying-remote-patch? true)
                                   (try
                                     (swap! !local-db e/patch (edit/edits->script edits))
                                     (finally
                                       (reset! !applying-remote-patch? false))))))
                         (add-watch !local-db :unified-ast-sync
                                    (fn [_ _ old-state new-state]
                                      (when-not @!applying-remote-patch?
                                        (let [edits (e/get-edits (e/diff old-state new-state {:algo :quick}))]
                                          (when (seq edits)
                                            (.postMessage db-port (t/write writer edits)))))
                                      (re-frame/clear-subscription-cache!)
                                      (if (or (not= (:locale old-state) (:locale new-state))
                                              (not= (:dictionary old-state) (:dictionary new-state)))
                                        (do
                                          (reset! !event-cache {})
                                          (process-timeline-redraw!))
                                        (queue-defer-check!))))
                         (db/set-eve-atom! !local-db)
                         {:status :db-bound-async})))))

(worker/register :cleanup-timeline
                 (fn [{:keys [room-id source]}]
                   (if source
                     (let [src (keyword source)]
                       (swap! !room-events update room-id dissoc src))
                     (do
                       (swap! !room-events dissoc room-id)
                       (when (contains? @!layout-context room-id)
                         (swap! !layout-context assoc-in [room-id :measured] {}))))
                   {:status :success}))

(worker/register :recalculate-timeline
                 (fn [{:keys [room-id]}]
                   (doseq [source (keys (get @!room-events room-id))]
                     (recalculate-and-stream! room-id source))
                   {:status :success}))

(worker/register :set-viewport
                 (fn [{:keys [visible-ids]}]
                   (reset! ast/!visible-active-ids (set visible-ids))
                   (let [dirty-hits (filterv @ast/!dirty-offscreen-ids visible-ids)]
                     (when (ast/consume-newly-visible-dirty! visible-ids)
                       (doseq [r-id (keys @!room-events)]
                         (when (seq dirty-hits)
                           (swap! !layout-context update-in [r-id :measured]
                                  (fn [m]
                                    (reduce (fn [acc id]
                                              (dissoc acc id (keyword id) (str id)))
                                            (or m {})
                                            dirty-hits))))
                         (doseq [src (keys (get @!room-events r-id))]
                           (recalculate-and-stream! r-id src)))))
                   {:status :success}))

(worker/register :recalculate-items
                 (fn [{:keys [room-id item-ids]}]
                   (doseq [id item-ids]
                     (swap! ast/!item-revisions update id (fnil inc 0))
                     (swap! ast/!ast-node-cache dissoc id)
                     (swap! ast/!deferred-component-cache dissoc id)
                     (swap! !layout-context update-in [room-id :measured] dissoc id (keyword id) (str id)))
                   (doseq [src (keys (get @!room-events room-id))]
                     (recalculate-and-stream! room-id src))
                   {:status :success}))

(worker/register :set-scrolling-state
                 (fn [{:keys [scrolling?]}]
                   (reset! ast/!is-scrolling? scrolling?)
                   (when (and (not scrolling?) @ast/!pending-defer-check?)
                     (reset! ast/!pending-defer-check? false)
                     (queue-defer-check!))
                   {:status :success}))

(worker/register :update-layout-context
                 (fn [{:keys [room-id width theme measured]}]
                   (let [old-ctx (get @!layout-context room-id)]
                     (when (or (not= width (:width old-ctx))
                               (not= theme (:theme old-ctx)))
                       (pretext/flush-cache!))
                     (swap! !layout-context assoc room-id {:width width :theme theme :measured measured})
                     (doseq [source (keys (get @!room-events room-id))]
                       (recalculate-and-stream! room-id source))
                     {:status :success})))

(worker/register :process-timeline-diff
                 (fn [{:keys [events room-id source]}]
                   (let [t-spec           (spec/timeline-layout source)
                         prev-items       (get-in @!room-events [room-id source] [])
                         prev-merged      (reduce (fn [m it] (assoc m (:id it) (:merge-with-prev? it)))
                                                  {}
                                                  prev-items)
                         processed-events (process-timeline-events t-spec events room-id)
                         enriched-events  (vspec/enrich-items t-spec processed-events)
                         seam-ids         (keep (fn [it]
                                                  (let [id (:id it)]
                                                    (when (and (contains? prev-merged id)
                                                               (not= (get prev-merged id)
                                                                     (:merge-with-prev? it)))
                                                      id)))
                                                enriched-events)]
                     (when (vspec/validate-specs?)
                       (let [batch-check (vspec/validate-prepared-batch enriched-events)]
                         (when-not (:valid? batch-check)
                           (log/warn "Paradise timeline batch invariant violation"
                                     {:room-id room-id
                                      :source  source
                                      :check   batch-check}))))
                     (when (seq seam-ids)
                       (swap! !layout-context update-in [room-id :measured]
                              (fn [m]
                                (reduce (fn [acc id]
                                          (dissoc acc id (keyword id) (str id)))
                                        (or m {})
                                        seam-ids))))
                     (swap! !room-events assoc-in [room-id source] enriched-events)
                     (recalculate-and-stream! room-id source)
                     {:status :success})))

(worker/register :eval-virtualizer-plugin
                 (fn [{:keys [plugin-id code]}]
                   (let [res (sci-runner/eval-virtualizer-plugin! plugin-id code)]
                     (when (= (:status res) :success)
                       (doseq [r-id (keys @!room-events)]
                         (swap! !layout-context assoc-in [r-id :measured] {}))
                       (process-timeline-redraw!))
                     res)))

(worker/bootstrap)