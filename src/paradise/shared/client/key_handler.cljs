(ns paradise.shared.client.key-handler
  (:require
   [clojure.string :as str]
   [reagent.core :as r]
   [re-frame.core :as re-frame]
   [re-frame.db :as db]))

(def storage-key "paradise.settings.keybinds")

(defn- normalize-id [id]
  (if (keyword? id)
    (subs (str id) 1)
    (str id)))

(defmulti keybind-spec identity)

(defmethod keybind-spec :default [action-id]
  nil)

(defonce !registry (r/atom {}))

(defonce !last-fired (atom {}))

(defn all-keybind-specs []
  (let [mm-entries (reduce-kv
                    (fn [acc dispatch-val method-fn]
                      (if (= dispatch-val :default)
                        acc
                        (if-let [spec (method-fn dispatch-val)]
                          (let [norm-id (normalize-id (or (:id spec) dispatch-val))]
                            (assoc acc norm-id (merge {:global? false :allow-in-input? true}
                                                      spec
                                                      {:id norm-id})))
                          acc)))
                    {}
                    (methods keybind-spec))]
    (merge mm-entries @!registry)))

(defn- get-shortcuts-bridge ^js []
  (.-paradiseShortcuts js/globalThis))

(defn global-shortcuts-supported? []
  (some? (get-shortcuts-bridge)))

(defn- load-stored-overrides []
  (when (exists? js/localStorage)
    (try
      (when-let [raw (.getItem js/localStorage storage-key)]
        (js->clj (js/JSON.parse raw) :keywordize-keys false))
      (catch :default _ nil))))

(defn- save-stored-overrides! [overrides]
  (when (exists? js/localStorage)
    (try
      (.setItem js/localStorage storage-key (js/JSON.stringify (clj->js (or overrides {}))))
      (catch :default _ nil))))

(defn- normalize-key-token [k]
  (let [low (str/lower-case (str k))]
    (case low
      ("control" "ctrl" "commandorcontrol" "cmdorctrl" "meta" "cmd" "super") "mod"
      ("alt" "option") "alt"
      "shift" "shift"
      " " "space"
      "escape" "esc"
      low)))

(defn accelerator->signature [accelerator]
  (when (and accelerator (not (str/blank? accelerator)))
    (let [tokens (->> (str/split accelerator #"\+")
                      (map str/trim)
                      (remove str/blank?)
                      (map normalize-key-token))
          mods   (vec (sort (distinct (filter #{"mod" "alt" "shift"} tokens))))
          main   (first (remove #{"mod" "alt" "shift"} tokens))]
      (when main
        (str/join "+" (conj mods main))))))

(defn event->accelerator [^js e]
  (let [raw-key  (or (.-key e) "")
        low-key  (str/lower-case raw-key)
        mod-key? (#{"control" "shift" "alt" "meta"} low-key)]
    (when-not mod-key?
      (let [mods     (cond-> []
                       (or (.-ctrlKey e) (.-metaKey e)) (conj "CommandOrControl")
                       (.-altKey e)                     (conj "Alt")
                       (.-shiftKey e)                   (conj "Shift"))
            norm-key (cond
                       (= raw-key " ")        "Space"
                       (= low-key "escape")   "Esc"
                       (= (count raw-key) 1)  (str/upper-case raw-key)
                       :else                  raw-key)]
        (when (or (seq mods) (re-matches #"^F\d+$" norm-key))
          (str/join "+" (conj mods norm-key)))))))

(defn format-accelerator [accelerator]
  (if (str/blank? accelerator)
    "Unbound"
    (let [mac? (and (exists? js/navigator)
                    (str/includes? (str/lower-case (or (.-platform js/navigator) "")) "mac"))]
      (-> accelerator
          (str/replace "CommandOrControl" (if mac? "Cmd" "Ctrl"))
          (str/replace "CmdOrCtrl" (if mac? "Cmd" "Ctrl"))
          (str/replace "+" " + ")))))

(defn- editable-target? [^js el]
  (when el
    (let [tag (some-> (.-tagName el) str/lower-case)]
      (or (= tag "input")
          (= tag "textarea")
          (= tag "select")
          (boolean (.-isContentEditable el))))))

(defn effective-keybind [entry overrides]
  (let [ov (get overrides (:id entry))]
    (cond
      (map? ov)
      (merge entry
             {:accelerator (get ov "accelerator" (:accelerator ov (:accelerator entry)))
              :global?     (boolean (get ov "global?" (:global? ov (:global? entry))))})

      (string? ov)
      (assoc entry :accelerator ov)

      :else
      entry)))

(defn trigger-action! [action-id]
  (let [norm-id (normalize-id action-id)]
    (when-let [{:keys [dispatch on-trigger]} (get (all-keybind-specs) norm-id)]
      (let [now  (js/Date.now)
            prev (get @!last-fired norm-id 0)]
        (when (> (- now prev) 150)
          (swap! !last-fired assoc norm-id now)
          (when dispatch
            (re-frame/dispatch dispatch))
          (when on-trigger
            (on-trigger)))))))

(defn sync-global-shortcuts! [overrides]
  (when-let [^js bridge (get-shortcuts-bridge)]
    (let [payload (->> (vals (all-keybind-specs))
                       (map #(effective-keybind % overrides))
                       (keep (fn [{:keys [id accelerator global? enabled?]}]
                               (when (and (not= enabled? false)
                                          (true? global?)
                                          (not (str/blank? accelerator)))
                                 {:id          (str id)
                                  :accelerator accelerator})))
                       vec)]
      (.setBindings bridge (clj->js payload)))))

(defn register-keybind!
  [{:keys [id] :as spec}]
  (let [norm-id (normalize-id id)
        entry   (merge {:global? false :allow-in-input? true}
                       spec
                       {:id norm-id})]
    (swap! !registry assoc norm-id entry)
    (when (exists? db/app-db)
      (sync-global-shortcuts! (get-in @db/app-db [:settings :keybinds] {})))
    norm-id))

(defn unregister-keybind! [id]
  (swap! !registry dissoc (normalize-id id))
  (when (exists? db/app-db)
    (sync-global-shortcuts! (get-in @db/app-db [:settings :keybinds] {}))))

(re-frame/reg-fx
 :keybinds/sync-electron
 (fn [overrides]
   (save-stored-overrides! overrides)
   (sync-global-shortcuts! overrides)))

(re-frame/reg-event-fx
 :settings/init-keybinds
 (fn [{:keys [db]} _]
   (let [stored (or (load-stored-overrides)
                    (get-in db [:settings :keybinds])
                    {})]
     {:db                     (assoc-in db [:settings :keybinds] stored)
      :keybinds/sync-electron stored})))

(re-frame/reg-event-fx
 :settings/set-keybind
 (fn [{:keys [db]} [_ action-id {:keys [accelerator global?]}]]
   (let [id      (normalize-id action-id)
         base    (get (all-keybind-specs) id)
         current (effective-keybind base (get-in db [:settings :keybinds] {}))
         updated {"accelerator" (if (some? accelerator) accelerator (:accelerator current))
                  "global?"     (if (some? global?) (boolean global?) (boolean (:global? current)))}
         next-kb (assoc (get-in db [:settings :keybinds] {}) id updated)]
     {:db                     (assoc-in db [:settings :keybinds] next-kb)
      :keybinds/sync-electron next-kb})))

(re-frame/reg-event-fx
 :settings/reset-keybind
 (fn [{:keys [db]} [_ action-id]]
   (let [next-kb (dissoc (get-in db [:settings :keybinds] {}) (normalize-id action-id))]
     {:db                     (assoc-in db [:settings :keybinds] next-kb)
      :keybinds/sync-electron next-kb})))

(re-frame/reg-event-fx
 :settings/reset-all-keybinds
 (fn [{:keys [db]} _]
   {:db                     (assoc-in db [:settings :keybinds] {})
    :keybinds/sync-electron {}}))

(re-frame/reg-sub
 :settings/keybind-overrides
 (fn [db _]
   (get-in db [:settings :keybinds] {})))

(re-frame/reg-sub
 :settings/resolved-keybinds
 :<- [:settings/keybind-overrides]
 (fn [overrides _]
   (->> (vals (all-keybind-specs))
        (sort-by :id)
        (mapv #(effective-keybind % overrides)))))

(defn global-key-listener []
  (r/with-let [_            (re-frame/dispatch [:settings/init-keybinds])
               unlisten-ipc (when-let [^js bridge (get-shortcuts-bridge)]
                              (.onTriggered bridge (fn [action-id]
                                                     (trigger-action! (str action-id)))))
               handler      (fn [^js e]
                              (when-not (or (.-repeat e)
                                            (.-paradiseRecordingKeybind js/globalThis))
                                (when-let [acc (event->accelerator e)]
                                  (let [sig       (accelerator->signature acc)
                                        overrides (get-in @db/app-db [:settings :keybinds] {})
                                        matched   (first
                                                   (filter
                                                    (fn [raw-entry]
                                                      (let [{:keys [accelerator enabled?]} (effective-keybind raw-entry overrides)]
                                                        (and (not= enabled? false)
                                                             (= (accelerator->signature accelerator) sig))))
                                                    (vals (all-keybind-specs))))]
                                    (when (and matched
                                               (or (:allow-in-input? matched true)
                                                   (not (editable-target? (.-target e)))))
                                      (.preventDefault e)
                                      (trigger-action! (:id matched)))))))]
    (.addEventListener js/globalThis "keydown" handler)
    [:<>]
    (finally
      (.removeEventListener js/globalThis "keydown" handler)
      (when (fn? unlisten-ipc)
        (unlisten-ipc)))))

#_(re-frame/reg-fx
 :native/hide-keyboard
 (fn [_]
   (.hide Keyboard)))