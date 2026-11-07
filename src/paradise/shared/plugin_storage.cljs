(ns paradise.shared.plugin-storage
  (:require
   [re-frame.core :as rf]
   [cljs.reader :as reader]
   [clojure.string :as str]
   [cljs.core.async :refer [go <!]]
   [cljs.core.async.interop :refer-macros [<p!]]
   [promesa.core :as p]
   [cljs-workers.core :as main]
   [cljs-workers.mesh :as mesh]
   [paradise.shared.sci-runner.ui :as runner]
   [paradise.shared.client.state :as state]
   [paradise.shared.registry :as reg]
   [paradise.shared.client.registry]))

(defn inject-css! [id url]
  (when-not (.getElementById js/document id)
    (let [link (.createElement js/document "link")]
      (set! (.-id link) id)
      (set! (.-rel link) "stylesheet")
      (set! (.-href link) url)
      (.appendChild js/document.head link))))

(defn inject-script! [url]
  (p/create
   (fn [resolve-fn reject]
     (let [script (.createElement js/document "script")]
       (set! (.-src script) url)
       (set! (.-crossOrigin script) "anonymous")
       (set! (.-onload script) #(resolve-fn true))
       (set! (.-onerror script) #(reject (str "Failed to load script: " url)))
       (.appendChild js/document.head script)))))

(defn remove-css! [id]
  (when-let [link (.getElementById js/document id)]
    (.remove link)))

(defn refresh-registry-state! [custom-urls]
  (go
    (reset! reg/!user-registries custom-urls)
    (<! (reg/ensure-environment!))
    (rf/dispatch [:plugins/sync-registry-state
                  (reg/resolve-engines)
                  (reg/resolve-plugins)])))

(rf/reg-event-db
 :plugins/sync-registry-state
 (fn [db [_ engines plugins]]
   (assoc db
          :plugins/registry-engines engines
          :plugins/registry-plugins plugins)))

(rf/reg-sub
 :plugins/registry-engines
 (fn [db _]
   (vals (:plugins/registry-engines db (reg/resolve-engines)))))

(rf/reg-sub
 :plugins/registry-plugins
 (fn [db _]
   (vals (:plugins/registry-plugins db (reg/resolve-plugins)))))

(rf/reg-sub
 :plugins/custom-registries
 (fn [db _]
   (:plugins/custom-registries db [])))

(rf/reg-event-fx
 :registry/hydrate-custom-urls
 (fn [{:keys [db]} [_ urls]]
   (let [clj-urls   (js->clj urls)
         valid-urls (if (coll? clj-urls) (vec clj-urls) [])]
     (refresh-registry-state! valid-urls)
     {:db (assoc db :plugins/custom-registries valid-urls)})))

(rf/reg-event-fx
 :registry/add-custom-url
 (fn [{:keys [db]} [_ url]]
   (let [current (:plugins/custom-registries db [])
         updated (vec (distinct (conj current url)))]
     (refresh-registry-state! updated)
     {:db (assoc db :plugins/custom-registries updated)
      :dispatch [:settings/save "custom_registries" (clj->js updated)]})))

(rf/reg-event-fx
 :registry/remove-custom-url
 (fn [{:keys [db]} [_ url]]
   (let [current (:plugins/custom-registries db [])
         updated (vec (remove #(= % url) current))]
     (swap! reg/!registry-indices dissoc url)
     (refresh-registry-state! updated)
     {:db (assoc db :plugins/custom-registries updated)
      :dispatch [:settings/save "custom_registries" (clj->js updated)]})))

(rf/reg-event-fx
 :plugins/hydrate-urls
 (fn [{:keys [db]} [_ urls]]
   (let [clj-urls   (js->clj urls)
         valid-urls (if (coll? clj-urls) (vec clj-urls) [])]
     {:db         (assoc db :plugins/installed-urls valid-urls)
      :dispatch-n (mapv (fn [url] [:plugins/fetch-and-boot url nil]) valid-urls)})))

(rf/reg-sub
 :plugins/disabled-urls
 (fn [db _]
   (set (:plugins/disabled-urls db #{}))))

(rf/reg-event-fx
 :plugins/load-for-engine
 (fn [_ [_ engine-id plugin-refs]]
   (let [engine-manifest  (reg/get-engine-manifest engine-id)
         engine-base-url  (:manifest-url engine-manifest)
         registry-plugins (reg/resolve-plugins)
         dispatches
         (keep (fn [ref]
                 (cond
                   (keyword? ref)
                   (when-let [m-url (get-in registry-plugins [ref :manifest-url])]
                     [:plugins/fetch-and-boot m-url engine-id])

                   (and (string? ref) (str/starts-with? ref "http"))
                   [:plugins/fetch-and-boot ref engine-id]

                   (and (string? ref) engine-base-url)
                   [:plugins/fetch-and-boot (reg/resolve-relative-url engine-base-url ref) engine-id]

                   :else nil))
               plugin-refs)]
     {:dispatch-n (vec dispatches)})))

(rf/reg-event-fx
 :plugins/toggle-plugin
 (fn [{:keys [db]} [_ url enabled?]]
   (let [disabled     (set (:plugins/disabled-urls db #{}))
         new-disabled (if enabled? (disj disabled url) (conj disabled url))
         plugin       (->> (:plugins/active db) (vals) (filter #(= (:source-url %) url)) first)
         plugin-id    (:id plugin)]
     (when (and (not enabled?) plugin-id)
       (remove-css! (str "plugin-css-" (name plugin-id)))
       (paradise.shared.client.registry/remove-plugin-overrides! plugin-id)
       (paradise.shared.client.registry/remove-plugin-slots! plugin-id))

     {:db (assoc db :plugins/disabled-urls new-disabled)
      :fx (cond-> [[:dispatch [:settings/save "disabled_plugin_urls" (clj->js (vec new-disabled))]]]
            enabled? (conj [:dispatch [:plugins/execute-boot-sequence url plugin]]))})))

(rf/reg-event-fx
 :plugins/install-from-url
 (fn [{:keys [db]} [_ url]]
   {:db (assoc db :plugins/installing? true)
    :dispatch [:plugins/fetch-and-boot url nil]}))

(rf/reg-event-fx
 :plugins/installation-success
 (fn [{:keys [db]} [_ source-url plugin-map]]
   (let [engine-owned? (some? (:provided-by plugin-map))
         current-urls  (:plugins/installed-urls db [])
         new-urls      (if engine-owned?
                         current-urls
                         (vec (distinct (conj current-urls source-url))))]
     {:db (-> db
              (assoc :plugins/pending-security-approval nil
                     :plugins/installing? false
                     :plugins/installed-urls new-urls)
              (update-in [:plugins/active (:id plugin-map)]
                         (constantly (assoc plugin-map :source-url source-url))))
      :fx (cond-> []
            (not engine-owned?)
            (conj [:dispatch [:settings/save "plugin_urls" (clj->js new-urls)]]))})))

(rf/reg-event-fx
 :plugins/uninstall-url
 (fn [{:keys [db]} [_ url]]
   (let [current-urls (:plugins/installed-urls db [])
         new-urls     (vec (remove #(= % url) current-urls))
         plugin       (->> (:plugins/active db) (vals) (filter #(= (:source-url %) url)) first)
         plugin-id    (:id plugin)]
     (when plugin-id
       (remove-css! (str "plugin-css-" (name plugin-id)))
       (paradise.shared.client.registry/remove-plugin-overrides! plugin-id)
       (paradise.shared.client.registry/remove-plugin-slots! plugin-id))

     {:db (-> db
              (assoc :plugins/installed-urls new-urls)
              (update :plugins/active dissoc plugin-id))
      :dispatch [:settings/save "plugin_urls" (clj->js new-urls)]})))

(rf/reg-event-db
 :plugins/hydrate-disabled-urls
 (fn [db [_ urls]]
   (let [clj-urls   (js->clj urls)
         valid-urls (if (coll? clj-urls) (set clj-urls) #{})]
     (assoc db :plugins/disabled-urls valid-urls))))

(rf/reg-event-fx
 :plugins/fetch-and-boot
 (fn [{:keys [db]} [_ url & [provided-by-engine]]]
   (-> (js/fetch url)
       (.then (fn [resp]
                (if (.-ok resp) (.text resp)
                    (throw (js/Error. (str "Failed to fetch plugin: " (.-status resp)))))))
       (.then (fn [edn-string]
                (let [raw-map       (reader/read-string edn-string)
                      plugin-map    (cond-> raw-map
                                      provided-by-engine (assoc :provided-by provided-by-engine))
                      disabled-urls (:plugins/disabled-urls db #{})
                      disabled?     (contains? disabled-urls url)]
                  (if disabled?
                    (rf/dispatch [:plugins/installation-success url plugin-map])
                    (if (seq (:external-scripts plugin-map))
                      (rf/dispatch [:plugins/prompt-security-warning url plugin-map])
                      (rf/dispatch [:plugins/execute-boot-sequence url plugin-map]))))))
       (.catch (fn [err]
                 (js/console.error "Plugin boot failed for" url "-" err)
                 (rf/dispatch [:plugins/installation-failed url (str err)]))))
   {:db (assoc db :plugins/installing? true)}))

(rf/reg-event-fx
 :plugins/execute-boot-sequence
 (fn [_ [_ url plugin-map]]
   (go
     (try
       (let [full-plugin-map
             (if (:modules plugin-map)
               plugin-map
               (if-let [code-path (:path plugin-map)]
                 (let [code-url (reg/resolve-relative-url url code-path)
                       payload  (<! (reg/fetch-edn code-url))]
                   (if payload
                     (merge plugin-map payload)
                     (throw (js/Error. (str "Failed to fetch plugin payload from " code-url)))))
                 plugin-map))]

         (when-let [css-url (:theme-url full-plugin-map)]
           (inject-css! (str "plugin-css-" (name (:id full-plugin-map)))
                        (reg/resolve-relative-url url css-url)))

         (when-let [scripts (:external-scripts full-plugin-map)]
           (doseq [script-url scripts]
             (<p! (inject-script! (reg/resolve-relative-url url script-url)))))

         (when-let [modules (:modules full-plugin-map)]
           (doseq [{:keys [ns target code]} modules]
             (case target
               :engine
               (let [res (<! (mesh/do-with-thread! :engine-pool
                                                   {:handler :evaluate-worker-form
                                                    :arguments {:plugin-id (:id full-plugin-map)
                                                                :code code}}))]
                 (when (= (:status res) "error")
                   (throw (js/Error. (str "Engine Eval Failed for " ns ": " (:msg res))))))

               :ui
               (runner/evaluate-ui-form (:id full-plugin-map) code)

               (js/console.warn "Unknown target for namespace:" ns "- skipping evaluation."))))

         (rf/dispatch [:plugins/installation-success url full-plugin-map]))

       (catch :default e
         (js/console.error "Plugin Boot Sequence Failed:" e)
         (rf/dispatch [:plugins/installation-failed url (str e)]))))
   {}))

(rf/reg-event-db
 :plugins/installation-failed
 (fn [db [_ _ _]]
   (assoc db :plugins/pending-security-approval nil
             :plugins/installing? false)))

(rf/reg-event-db
 :plugins/prompt-security-warning
 (fn [db [_ url plugin-map]]
   (assoc db :plugins/pending-security-approval {:url url :plugin-map plugin-map})))

(rf/reg-event-db
 :plugins/cancel-install
 (fn [db _]
   (assoc db :plugins/pending-security-approval nil
             :plugins/installing? false)))

(rf/reg-sub
 :plugins/active-list
 (fn [db _]
   (vals (:plugins/active db {}))))

(rf/reg-sub :plugins/installed-urls (fn [db _] (:plugins/installed-urls db [])))
(rf/reg-sub :plugins/pending-security-approval (fn [db _] (:plugins/pending-security-approval db)))
