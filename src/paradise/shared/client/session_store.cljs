(ns paradise.shared.client.session-store
  (:require [promesa.core :as p]
            [cljs.reader :as reader]
            [taoensso.timbre :as log]))

(defn- engine-file-name [engine-id]
  (str "sessions_" (name (or engine-id :default)) ".json"))

(defn- load-raw-sessions-opfs [engine-id]
  (p/let [dir (.. js/navigator -storage getDirectory)
          file-handle (.getFileHandle dir (engine-file-name engine-id) #js {:create true})
          sync-handle (.createSyncAccessHandle file-handle)]
    (try
      (let [size (.getSize sync-handle)]
        (if (zero? size)
          #js {}
          (let [buffer (js/Uint8Array. size)]
            (.read sync-handle buffer #js {:at 0})
            (let [decoder (js/TextDecoder.)
                  text (.decode decoder buffer)]
              (.parse js/JSON text)))))
      (finally
        (.close sync-handle)))))

(defn- save-raw-sessions-opfs! [engine-id sessions-obj]
  (p/let [dir (.. js/navigator -storage getDirectory)
          file-handle (.getFileHandle dir (engine-file-name engine-id) #js {:create true})
          sync-handle (.createSyncAccessHandle file-handle)]
    (try
      (let [encoder (js/TextEncoder.)
            text (.stringify js/JSON sessions-obj)
            buffer (.encode encoder text)]
        (.truncate sync-handle 0)
        (.write sync-handle buffer #js {:at 0})
        (.flush sync-handle))
      (finally
        (.close sync-handle)))))

(defn generate-passphrase []
  (let [array (js/Uint8Array. 32)]
    (.getRandomValues js/crypto array)
    (js/btoa (.apply js/String.fromCharCode nil array))))

(defn generate-uuid []
  (if (and (exists? js/crypto) (exists? (.-randomUUID js/crypto)))
    (.randomUUID js/crypto)
    (let [template "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx"]
      (.replace template
                (js/RegExp. "[xy]" "g")
                (fn [c]
                  (let [r (bit-or (* (js/Math.random) 16) 0)
                        v (if (= c "x") r (bit-or (bit-and r 0x3) 0x8))]
                    (.toString v 16)))))))

(defn- open-settings-db []
  (p/create
   (fn [resolve-fn _]
     (let [req (.open js/indexedDB "paradise-settings" 1)]
       (set! (.-onupgradeneeded req)
             #(let [db (.. % -target -result)]
                (when-not (.contains (.-objectStoreNames db) "settings")
                  (.createObjectStore db "settings"))))
       (set! (.-onsuccess req) #(resolve-fn (.. % -target -result)))
       (set! (.-onerror req) #(resolve-fn nil))))))

(defn get-setting [key]
  (p/let [db (open-settings-db)]
    (if-not db
      (p/resolved nil)
      (p/create
       (fn [resolve-fn _]
         (let [tx    (.transaction db #js ["settings"] "readonly")
               store (.objectStore tx "settings")
               req   (.get store key)]
           (set! (.-onsuccess req)
                 #(let [raw-val (.-result req)]
                    (resolve-fn
                     (cond
                       (nil? raw-val) nil
                       (string? raw-val)
                       (let [first-char (subs raw-val 0 1)]
                         (if (#{"{" "[" "#" ":" "\""} first-char)
                           (try
                             (reader/read-string raw-val)
                             (catch :default e
                               (js/console.error "EDN Parse Error for key:" key "val:" raw-val e)
                               raw-val))
                           raw-val))
                       :else
                       (js->clj raw-val :keywordize-keys true)))))
           (set! (.-onerror req) #(resolve-fn nil))))))))

(defn set-setting! [key value]
  (p/let [db (open-settings-db)]
    (if-not db
      false
      (p/create
       (fn [resolve-fn _]
         (let [tx      (.transaction db #js ["settings"] "readwrite")
               store   (.objectStore tx "settings")
               put-req (if (some? value)
                         (.put store (pr-str value) key)
                         (.delete store key))]
           (set! (.-onsuccess put-req) #(resolve-fn true))
           (set! (.-onerror put-req) #(resolve-fn false))))))))

(defn- delete-db! [db-name]
  (js/Promise.
   (fn [resolve-fn _]
     (try
       (let [request (.deleteDatabase js/indexedDB db-name)]
         (set! (.-onsuccess request) #(do (log/info "Destroyed IDB:" db-name) (resolve-fn true)))
         (set! (.-onerror request) #(do (log/error "Error deleting IDB:" db-name) (resolve-fn false)))
         (set! (.-onblocked request) #(do (log/warn "BLOCKED deleting IDB:" db-name) (resolve-fn false))))
       (catch :default e
         (log/error "Fatal crash attempting to delete IDB:" db-name "Error:" e)
         (resolve-fn false))))))

(defn- delete-dbs! [db-names]
  (if (seq db-names)
    (.allSettled js/Promise (into-array (map delete-db! db-names)))
    (p/resolved true)))

(defn- save-session-impl! [engine-id account-id payload owned-dbs]
  (p/let [sessions (load-raw-sessions-opfs engine-id)
          existing (aget sessions account-id)
          merged   (js/Object.assign #js {} existing payload)]
    (when (seq owned-dbs)
      (aset merged "ownedDbs" (clj->js owned-dbs)))
    (aset sessions account-id merged)
    (save-raw-sessions-opfs! engine-id sessions)))

(defn- clear-session-impl! [engine-id account-id]
  (p/let [sessions  (load-raw-sessions-opfs engine-id)
          data      (aget sessions account-id)
          owned-dbs (when data (js->clj (aget data "ownedDbs")))
          _         (delete-dbs! owned-dbs)]
    (js-delete sessions account-id)
    (save-raw-sessions-opfs! engine-id sessions)))

(deftype SessionStore [engine-id]
  Object
  (loadSessions [this]
    (load-raw-sessions-opfs engine-id))

  (save [this account-id payload owned-dbs]
    (save-session-impl! engine-id account-id payload owned-dbs))

  (generatePassphrase [this]
    (generate-passphrase))

  (generateStoreId [this]
    (generate-uuid))

  (getStoreName [this store-id]
    (str "paradise-store-" (name engine-id) "-" store-id))

  (clear [this account-id]
    (clear-session-impl! engine-id account-id)))
