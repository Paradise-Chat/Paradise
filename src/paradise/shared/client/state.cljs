(ns paradise.shared.client.state
  (:require
   [re-frame.core :as re-frame]))

(defonce !shared-app-db (atom {}))
(defonce !eve-app-db-payload (atom nil))

(defonce !engine-pool (atom nil))
(defonce !media-pool (atom nil))
(defonce !virtualizer-pool (atom nil))
(defonce !config (atom nil))

(defonce !attachments-cache (atom {}))

(defn guard-session-gen
  "Returns a re-frame interceptor that drops an event when the generation token
  extracted by `extract-gen-fn` does not match the active `:session/gen` in
  `app-db`."
  [extract-gen-fn]
  (re-frame/->interceptor
   :id :guard-session-gen
   :before
   (fn [context]
     (let [db           (get-in context [:coeffects :db])
           event        (get-in context [:coeffects :event])
           expected-gen (extract-gen-fn event)]
       (if (and (some? expected-gen)
                (not= expected-gen (:session/gen db)))
         (assoc context :queue [] :effects {})
         context)))))

(defn room-forum-mode?
  "Returns true when `room-id` should render in forum/threads mode, either
  natively via its room preview or via the active account's forum overrides."
  [db room-id]
  (or (boolean (get-in db [:room-previews room-id :is-forum?]))
      (contains? (get db :forum-room-overrides #{}) room-id)))