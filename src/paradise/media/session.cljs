(ns ^:virtualizer/skip paradise.media.session
  (:require [cljs.core.async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [goog.object :as gobj]
            [net :as net]
            [paradise.shared.client.session-store :as store :refer [SessionStore]]
            [promesa.core :as p]))

(defn make-session-manager
  "Creates a media worker session manager for `engine-kw`.

   Options:
   - `:engine-kw`   protocol keyword passed to `SessionStore.` (e.g. `:matrix`)
   - `:normalize`   `(fn [session-map]) -> normalized-entry` or nil
   - `:extract-raw` `(fn [raw-js-session]) -> session-map` for `SessionStore`
   - `:retries`     hydration retry count (default 4)
   - `:delay-ms`    retry backoff in ms (default 200)

   Returns a map with `:!sessions`, `:!active-user-id`, `:apply!`, `:clear!`,
   `:ensure!`, `:handle-set-media-session`, and `:handle-clear-media-session`."
  [{:keys [engine-kw normalize extract-raw retries delay-ms]
    :or   {retries 4 delay-ms 200}}]
  (let [!sessions          (atom {})
        !active-user-id    (atom nil)
        !hydrating-promises (atom {})
        apply!
        (fn [opts]
          (when-let [entry (normalize opts)]
            (let [uid (str (:user-id entry))]
              (swap! !sessions assoc uid entry)
              (reset! !active-user-id uid)
              entry)))
        clear!
        (fn [{:keys [user-id all?]}]
          (if (or all? (nil? user-id))
            (do
              (reset! !sessions {})
              (reset! !active-user-id nil)
              (net/set-auth-context! nil nil))
            (let [uid (str user-id)]
              (swap! !sessions dissoc uid)
              (when (= @!active-user-id uid)
                (reset! !active-user-id nil)
                (net/set-auth-context! nil nil)))))
        read-store
        (fn [target-user-id]
          (let [s-store (SessionStore. engine-kw)]
            (p/let [saved-uid (if target-user-id
                                nil
                                (-> (store/get-setting "last_active_user")
                                    (p/catch (fn [_] nil))))
                    want-uid  (some-> (or target-user-id saved-uid) str)
                    sessions  (when want-uid (.loadSessions s-store))
                    uid       (when (and want-uid sessions (gobj/get sessions want-uid))
                                want-uid)
                    entry     (when uid (gobj/get sessions uid))
                    raw       (when entry (or (gobj/get entry "session") entry))
                    extracted (when (and uid raw)
                                (assoc (extract-raw raw) :user-id uid))]
              (when extracted
                (apply! extracted)))))
        ensure!
        (fn [target-user-id]
          (let [lookup-uid (some-> (or target-user-id @!active-user-id) str)
                cached     (when lookup-uid (get @!sessions lookup-uid))]
            (if cached
              (do
                (when (not= @!active-user-id (:user-id cached))
                  (reset! !active-user-id (:user-id cached)))
                (p/resolved cached))
              (let [flight-key (or lookup-uid ::default)]
                (if-let [in-flight (get @!hydrating-promises flight-key)]
                  in-flight
                  (let [p-inst (-> (p/loop [remaining retries]
                                     (p/let [res (read-store target-user-id)]
                                       (if (or res (<= remaining 0))
                                         res
                                         (p/do!
                                           (p/delay delay-ms)
                                           (p/recur (dec remaining))))))
                                   (p/finally
                                     (fn []
                                       (swap! !hydrating-promises dissoc flight-key))))]
                    (swap! !hydrating-promises assoc flight-key p-inst)
                    p-inst))))))
        handle-set-media-session
        (fn [{:keys [user-id server-url base-url api-base cdn-base token]}]
          (go
            (if-let [applied (apply! {:user-id    user-id
                                      :server-url server-url
                                      :base-url   base-url
                                      :api-base   api-base
                                      :cdn-base   cdn-base
                                      :token      token})]
              {:status   "success"
               :user-id  (:user-id applied)
               :base-url (:base-url applied)}
              (if user-id
                (if-let [hydrated (<p! (ensure! user-id))]
                  {:status   "success"
                   :user-id  (:user-id hydrated)
                   :base-url (:base-url hydrated)}
                  {:status "empty"})
                {:status "ignored"}))))
        handle-clear-media-session
        (fn [opts]
          (go
            (clear! (or opts {}))
            {:status "success"}))]
    {:!sessions                  !sessions
     :!active-user-id            !active-user-id
     :apply!                     apply!
     :clear!                     clear!
     :ensure!                    ensure!
     :handle-set-media-session   handle-set-media-session
     :handle-clear-media-session handle-clear-media-session}))