(ns ^:virtualizer/skip paradise.engine.binding
  (:require [cljs-workers.helpers :refer [chan?]]
            [cljs-workers.mesh :as mesh]
            [cljs-workers.worker :as worker]
            [cljs.core.async :refer [<! go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [clojure.string :as str]
            [goog.object :as gobj]
            [paradise.engine.spec :as spec]
            [paradise.engine.state :as engine-state]
            [taoensso.timbre :as log]))

(defonce !validate-specs? (atom (boolean js/goog.DEBUG)))

(defn set-spec-validation!
  "Enables or disables runtime spec validation for RPCs, streams, and timeline
  batches. Defaults to `goog.DEBUG`."
  [enabled?]
  (reset! !validate-specs? (boolean enabled?)))

(defn- validate-specs? []
  (boolean @!validate-specs?))

(defn- warn-spec-violation! [validation-res msg context]
  (when-not (:valid? validation-res)
    (log/warn msg (assoc context :explain (:explain validation-res)))))

(defn register!
  "Namespaces the handler under the given engine-id (e.g. :bootstrap becomes
  :matrix/bootstrap) and registers it with `cljs-workers.worker`."
  [engine-id handler-key handler-fn]
  (let [namespaced-key (keyword (name engine-id) (name handler-key))]
    (worker/register namespaced-key handler-fn)))

(defn stream!
  "Attaches the engine-id to the stream payload so the UI knows how to route it."
  [engine-id payload]
  (let [enriched (if (map? payload)
                   (assoc payload :engine-id (name engine-id))
                   (do (js/Object.assign payload #js {:engine-id (name engine-id)})
                       payload))]
    (worker/stream! enriched)))

(defn emit!
  "Emits a stream event map tagged with `:engine-id`. When spec validation is
  enabled (default in dev builds), validates the payload against
  `::paradise.engine.spec/stream-event` and logs any schema drift."
  [engine-id payload]
  (let [enriched (if (map? payload)
                   (assoc payload :engine-id (name engine-id))
                   payload)]
    (when (and (validate-specs?) (map? enriched))
      (warn-spec-violation! (spec/validate-stream-event enriched)
                            "Engine stream spec violation"
                            {:engine-id engine-id
                             :type      (:type enriched)}))
    (stream! engine-id enriched)))

(defn push-timeline!
  "Pushes normalized timeline events directly to `:virtualizer-pool` via
  `:process-timeline-diff`, validating the batch in dev mode."
  ([engine-id room-id events]
   (push-timeline! engine-id room-id :live events))
  ([engine-id room-id source events]
   (let [batch {:events  (vec (or events []))
                :room-id room-id
                :source  source}]
     (when (validate-specs?)
       (warn-spec-violation! (spec/validate-timeline-batch batch)
                             "Engine timeline batch spec violation"
                             {:engine-id engine-id
                              :room-id   room-id
                              :source    source}))
     (mesh/do-with-thread! :virtualizer-pool
                           {:handler   :process-timeline-diff
                            :arguments batch}))))

(def ^:private permissions-panel-handlers
  #{:fetch-roles-and-permissions
    :get-permissions-panel
    :upsert-role
    :delete-role
    :reorder-roles
    :update-room-permissions})

(def ^:private edn-mirror-rules
  {:get-i18n                  [:dictionary :dictionary-edn map?]
   :fetch-room-config         [:config :config-edn map?]
   :update-room-config        [:config :config-edn map?]
   :fetch-room-notifications  [:notifications :notifications-edn map?]
   :update-room-notifications [:notifications :notifications-edn map?]
   :get-room-options          [:options :options-edn some?]
   :get-member-options        [:options :options-edn some?]})

(defn- mirror-edn-field [res [src-k edn-k pred]]
  (let [v (get res src-k)]
    (if (and (pred v) (nil? (get res edn-k)))
      (assoc res edn-k (pr-str v))
      res)))

(defn- mirror-profile-schema-edn [res]
  (if (and (map? (:profile res))
           (map? (get-in res [:profile :profile-schema]))
           (nil? (get-in res [:profile :profile-schema-edn])))
    (assoc-in res
              [:profile :profile-schema-edn]
              (pr-str (get-in res [:profile :profile-schema])))
    res))

(defn- mirror-permissions-panels-edn [res]
  (if (and (nil? (:panels-edn res))
           (or (:roles-panel res) (:permissions-panel res) (:moderation-panel res)))
    (assoc res
           :panels-edn
           (pr-str (select-keys res
                                [:capabilities :roles :overwrites :thresholds :access
                                 :roles-panel :permissions-panel :moderation-panel])))
    res))

(defn- enrich-edn-mirrors [handler-key res]
  (if-not (map? res)
    res
    (cond
      (contains? edn-mirror-rules handler-key)
      (mirror-edn-field res (get edn-mirror-rules handler-key))

      (= handler-key :fetch-profile)
      (mirror-profile-schema-edn res)

      (contains? permissions-panel-handlers handler-key)
      (mirror-permissions-panels-edn res)

      :else
      res)))

(defn- check-ret! [engine-id handler-key res]
  (let [mirrored (enrich-edn-mirrors handler-key res)]
    (when (validate-specs?)
      (warn-spec-violation! (spec/validate-rpc-ret handler-key mirrored)
                            "Engine RPC return spec violation"
                            {:engine-id engine-id
                             :handler   handler-key}))
    mirrored))

(defn- wrap-handler [engine-id handler-key handler-fn]
  (fn [args]
    (when (validate-specs?)
      (warn-spec-violation! (spec/validate-rpc-args handler-key args)
                            "Engine RPC args spec violation"
                            {:engine-id engine-id
                             :handler   handler-key}))
    (let [res (handler-fn args)]
      (if (chan? res)
        (go
          (check-ret! engine-id handler-key (<! res)))
        (check-ret! engine-id handler-key res)))))

(defn resolve-asset-urls
  "Resolves relative asset paths (`:wasm`, `:push`, `:driver`, `:encoder`)
  against `engine-url` (or `self.location.origin`) and stores the resulting
  map in `paradise.engine.state/!asset-urls`."
  [engine-url assets]
  (let [origin   (or (when (and (exists? js/self) (.-location js/self))
                       (.-origin (.-location js/self)))
                     "http://localhost")
        base-url (if (and (string? engine-url) (seq engine-url))
                   (try
                     (let [u (js/URL. engine-url origin)]
                       (.-href (js/URL. "." u)))
                     (catch :default _ (str origin "/")))
                   (str origin "/"))
        resolve1 (fn [rel]
                   (when (and (string? rel) (seq rel))
                     (try
                       (.-href (js/URL. rel base-url))
                       (catch :default _ rel))))
        urls     {:base-url    base-url
                  :wasm-url    (resolve1 (:wasm assets))
                  :push-url    (resolve1 (:push assets))
                  :driver-url  (resolve1 (:driver assets))
                  :encoder-url (resolve1 (:encoder assets))}]
    (reset! engine-state/!asset-urls urls)
    urls))

(defn- promise? [x]
  (and (some? x) (fn? (gobj/get x "then"))))

(defn- export-global-bootstrap! [engine-id bootstrap-fn]
  (let [prop-name (str (str/capitalize (name engine-id)) "EngineBootstrap")]
    (gobj/set js/globalThis prop-name bootstrap-fn)))

(defn- attach-builtin-handlers [handlers id caps i18n]
  (cond-> (or handlers {})
    (and (map? i18n) (not (contains? handlers :get-i18n)))
    (assoc :get-i18n
           (fn [_]
             {:status         "success"
              :engine-id      id
              :dictionary     i18n
              :dictionary-edn (pr-str i18n)}))

    (not (contains? handlers :get-asset-urls))
    (assoc :get-asset-urls
           (fn [_]
             (assoc (or @engine-state/!asset-urls {})
                    :status "success")))

    (not (contains? handlers :get-capabilities))
    (assoc :get-capabilities
           (fn [_]
             {:status       "success"
              :engine-id    id
              :capabilities (vec (sort caps))}))))

(defn- mirror-handler-aliases [handlers]
  (reduce (fn [acc [alias-k target-k]]
            (cond
              (and (contains? acc target-k) (not (contains? acc alias-k)))
              (assoc acc alias-k (get acc target-k))

              (and (contains? acc alias-k) (not (contains? acc target-k)))
              (assoc acc target-k (get acc alias-k))

              :else acc))
          handlers
          spec/handler-aliases))

(defn- attach-optional-fallback-stubs [handlers caps]
  (reduce (fn [acc [handler-k {:keys [capability]}]]
            (if (contains? acc handler-k)
              acc
              (let [required-here? (or (= capability :core)
                                       (contains? caps capability))]
                (if (or (not required-here?)
                        (= capability :core-optional)
                        (get-in spec/rpc-specs [handler-k :optional-in-cap?]))
                  (assoc acc handler-k
                         (fn [args]
                           (spec/default-response-for handler-k args)))
                  acc))))
          handlers
          spec/rpc-specs))

(defn- build-effective-handlers [{:keys [id capabilities i18n handlers]}]
  (let [caps (into #{} (map spec/normalize-status) capabilities)]
    (-> (attach-builtin-handlers handlers id caps i18n)
        mirror-handler-aliases
        (attach-optional-fallback-stubs caps))))

(defn- validate-descriptor! [id strict? descriptor]
  (when (validate-specs?)
    (let [{:keys [valid? explain]} (spec/validate-engine-definition descriptor)]
      (when-not valid?
        (if strict?
          (throw (ex-info (str "Invalid engine definition for " id) {:explain explain}))
          (log/warn "Engine definition spec violation" {:engine-id id :explain explain}))))))

(defn- audit-and-report-handlers! [id strict? descriptor]
  (let [audit (spec/audit-engine-handlers descriptor)]
    (when (seq (:missing audit))
      (let [msg (str "Engine " id " is missing required handlers: " (pr-str (:missing audit)))]
        (if strict?
          (throw (ex-info msg audit))
          (log/warn msg))))
    (when (seq (:unknown audit))
      (log/warn "Engine registers non-spec handlers"
                {:engine-id id :unknown (:unknown audit)}))
    audit))

(defn- make-bootstrap-fn [id assets on-bootstrap on-preload]
  (fn [registry-callback engine-url]
    (go
      (let [asset-urls (resolve-asset-urls engine-url assets)]
        (when on-bootstrap
          (let [res (on-bootstrap {:engine-url engine-url
                                   :asset-urls asset-urls})]
            (cond
              (chan? res)    (<! res)
              (promise? res) (<p! res)
              :else          res)))
        (when registry-callback
          (registry-callback
           (name id)
           #js {:preload (or on-preload (fn [] nil))}))
        {:status "success"}))))

(defn define-engine!
  "Declares and binds a Paradise protocol engine against `paradise.engine.spec`.

  Options map:
  - `:id`           (required keyword, e.g. `:nostr`)
  - `:handlers`     (required map of `{handler-kw handler-fn}`)
  - `:capabilities` (optional set/coll of capability keywords from
                     `spec/all-capabilities`)
  - `:i18n`         (optional dictionary map; auto-registers `:get-i18n`)
  - `:assets`       (optional `{:wasm :push :driver :encoder}` relative paths;
                     auto-registers `:get-asset-urls` and resolves on bootstrap)
  - `:on-bootstrap` (optional `(fn [{:keys [engine-url asset-urls]}])` returning
                     a value, Promise, or core.async channel)
  - `:on-preload`   (optional zero-arg preload fn passed to Paradise registry)
  - `:strict?`      (optional boolean; throws on missing required handlers)

  Returns `{:id :capabilities :audit :bootstrap}`."
  [{:keys [id assets on-bootstrap on-preload strict?] :as descriptor}]
  (validate-descriptor! id strict? descriptor)
  (let [audit              (audit-and-report-handlers! id strict? descriptor)
        effective-handlers (build-effective-handlers descriptor)
        bootstrap-fn       (make-bootstrap-fn id assets on-bootstrap on-preload)]
    (doseq [[handler-k handler-fn] effective-handlers]
      (register! id handler-k (wrap-handler id handler-k handler-fn)))
    (export-global-bootstrap! id bootstrap-fn)
    {:id           id
     :capabilities (:capabilities audit)
     :audit        audit
     :bootstrap    bootstrap-fn}))