(ns ^:virtualizer/skip paradise.shared.client.idb
  (:require [promesa.core :as p]
            [taoensso.timbre :as log]))

(defonce ^:private !db-promises (atom {}))
(defonce ^:private !open-dbs (atom {}))

(defn available?
  "Returns true when IndexedDB is exposed in the current runtime."
  []
  (and (exists? js/indexedDB) (some? js/indexedDB)))

(defn close-db!
  "Closes any cached open IDBDatabase handle for `db-name`."
  [db-name]
  (when-let [db (get @!open-dbs db-name)]
    (try
      (.close db)
      (catch :default _ nil)))
  (swap! !open-dbs dissoc db-name)
  (swap! !db-promises dissoc db-name))

(defn open-db
  "Opens (or returns the cached promise for) IndexedDB `db-name` at `version`.
   Installs `onversionchange` so concurrent deletes or upgrades cleanly close
   stale handles."
  [db-name version on-upgrade]
  (if-not (available?)
    (p/resolved nil)
    (if-let [existing (get @!db-promises db-name)]
      existing
      (let [prom
            (js/Promise.
             (fn [resolve _]
               (try
                 (let [req (.open js/indexedDB db-name version)]
                   (set! (.-onupgradeneeded req)
                         (fn [e]
                           (when (fn? on-upgrade)
                             (on-upgrade (.. e -target -result) e))))
                   (set! (.-onsuccess req)
                         (fn [e]
                           (let [db (.. e -target -result)]
                             (swap! !open-dbs assoc db-name db)
                             (set! (.-onversionchange db)
                                   (fn []
                                     (close-db! db-name)))
                             (resolve db))))
                   (set! (.-onerror req)
                         (fn [e]
                           (log/warn "IDB open failed for" db-name ":" (.. e -target -error))
                           (swap! !db-promises dissoc db-name)
                           (resolve nil))))
                 (catch :default e
                   (log/warn "IDB unavailable for" db-name ":" e)
                   (swap! !db-promises dissoc db-name)
                   (resolve nil)))))]
        (swap! !db-promises assoc db-name prom)
        prom))))

(defn get-record
  "Reads a single record by key `k` from `store-name`."
  [db-promise store-name k]
  (if (or (nil? k) (not (available?)))
    (p/resolved nil)
    (p/let [db db-promise]
      (if-not db
        nil
        (js/Promise.
         (fn [resolve _]
           (try
             (let [tx  (.transaction db #js [store-name] "readonly")
                   req (.get (.objectStore tx store-name) k)]
               (set! (.-onsuccess req) #(resolve (.-result req)))
               (set! (.-onerror req) #(resolve nil)))
             (catch :default _
               (resolve nil)))))))))

(defn put-record!
  "Writes `value` into `store-name`. Pass `explicit-key` for out-of-line key stores."
  ([db-promise store-name value]
   (put-record! db-promise store-name value nil))
  ([db-promise store-name value explicit-key]
   (if-not (available?)
     (p/resolved false)
     (p/let [db db-promise]
       (if-not db
         false
         (js/Promise.
          (fn [resolve _]
            (try
              (let [tx (.transaction db #js [store-name] "readwrite")
                    st (.objectStore tx store-name)]
                (if (some? explicit-key)
                  (.put st value explicit-key)
                  (.put st value))
                (set! (.-oncomplete tx) #(resolve true))
                (set! (.-onerror tx) #(resolve false))
                (set! (.-onabort tx) #(resolve false)))
              (catch :default _
                (resolve false))))))))))

(defn delete-record!
  "Deletes record `k` from `store-name`."
  [db-promise store-name k]
  (if (or (nil? k) (not (available?)))
    (p/resolved false)
    (p/let [db db-promise]
      (if-not db
        false
        (js/Promise.
         (fn [resolve _]
           (try
             (let [tx (.transaction db #js [store-name] "readwrite")]
               (.delete (.objectStore tx store-name) k)
               (set! (.-oncomplete tx) #(resolve true))
               (set! (.-onerror tx) #(resolve false))
               (set! (.-onabort tx) #(resolve false)))
             (catch :default _
               (resolve false)))))))))

(defn clear-store!
  "Clears all records in `store-name`."
  [db-promise store-name]
  (if-not (available?)
    (p/resolved false)
    (p/let [db db-promise]
      (if-not db
        false
        (js/Promise.
         (fn [resolve _]
           (try
             (let [tx (.transaction db #js [store-name] "readwrite")]
               (.clear (.objectStore tx store-name))
               (set! (.-oncomplete tx) #(resolve true))
               (set! (.-onerror tx) #(resolve false))
               (set! (.-onabort tx) #(resolve false)))
             (catch :default _
               (resolve false)))))))))

(defn delete-where!
  "Scans `store-name` (or optional `:index-name` / `:key-range`) with a readwrite
   cursor, deleting every record where `(pred-fn cursor-key cursor-value)` is
   truthy. Resolves to the number of deleted records."
  ([db-promise store-name pred-fn]
   (delete-where! db-promise store-name pred-fn nil))
  ([db-promise store-name pred-fn {:keys [index-name key-range]}]
   (if-not (available?)
     (p/resolved 0)
     (p/let [db db-promise]
       (if-not db
         0
         (js/Promise.
          (fn [resolve _]
            (try
              (let [tx     (.transaction db #js [store-name] "readwrite")
                    st     (.objectStore tx store-name)
                    source (if index-name (.index st index-name) st)
                    req    (if key-range
                             (.openCursor source key-range)
                             (.openCursor source))
                    !n     (atom 0)]
                (set! (.-onsuccess req)
                      (fn [e]
                        (when-let [cursor (.. e -target -result)]
                          (when (pred-fn (.-key cursor) (.-value cursor))
                            (swap! !n inc)
                            (.delete cursor))
                          (.continue cursor))))
                (set! (.-oncomplete tx) #(resolve @!n))
                (set! (.-onerror tx) #(resolve @!n))
                (set! (.-onabort tx) #(resolve @!n)))
              (catch :default _
                (resolve 0))))))))))

(defn delete-db!
  "Closes any cached handle for `db-name` and deletes the IndexedDB database."
  [db-name]
  (if-not (available?)
    (p/resolved false)
    (do
      (close-db! db-name)
      (js/Promise.
       (fn [resolve _]
         (try
           (let [req (.deleteDatabase js/indexedDB db-name)]
             (set! (.-onsuccess req)
                   #(do (log/info "Destroyed IDB:" db-name)
                        (resolve true)))
             (set! (.-onerror req)
                   #(do (log/error "Error deleting IDB:" db-name)
                        (resolve false)))
             (set! (.-onblocked req)
                   #(do (log/warn "BLOCKED deleting IDB:" db-name)
                        (resolve false))))
           (catch :default e
             (log/error "Fatal crash attempting to delete IDB:" db-name "Error:" e)
             (resolve false))))))))

(defn delete-dbs!
  "Deletes a collection of IndexedDB databases concurrently."
  [db-names]
  (if (seq db-names)
    (.allSettled js/Promise (into-array (map delete-db! db-names)))
    (p/resolved true)))