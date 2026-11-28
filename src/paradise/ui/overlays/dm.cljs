(ns paradise.ui.overlays.dm
  (:require
   [cljs-workers.mesh :as mesh]
   [cljs.core.async :refer [<! go]]
   [clojure.string :as str]
   [paradise.shared.utils.helpers :refer [ok-status? safe-lower safe-lower-trim]]
   [paradise.shared.utils.normalize :as norm]
   [paradise.shared.utils.svg :as icons]
   [paradise.ui.global :refer [avatar]]
   [paradise.ui.overlays.base :refer [modal-component]]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [taoensso.timbre :as log]))

(defn- navigate-to-dm! [room-id]
  (when (and room-id (not (str/blank? (str room-id))))
    (re-frame/dispatch [:container/set-main-focus :timeline])
    (re-frame/dispatch [:space/select nil])
    (re-frame/dispatch [:room-list/apply-filter "people" {:persist? true}])
    (re-frame/dispatch [:rooms/select (str room-id)])))

(re-frame/reg-event-fx
 :dm/open-create-modal
 (fn [_ _]
   {:dispatch [:ui/open-modal :create-dm
               {:backdrop-props {:class "settings-backdrop"}
                :window-props   {:class "dm-modal-window"}}]}))

(re-frame/reg-event-fx
 :dm/open-with-user
 (fn [_ [_ user-id opts]]
   (let [uid      (some-> user-id str str/trim not-empty)
         on-done  (:on-done opts)
         on-error (:on-error opts)]
     (when uid
       (re-frame/dispatch [:ui/close-popover])
       (re-frame/dispatch [:ui/close-context-menu])
       (go
         (try
           (let [res (<! (mesh/do-with-thread!
                          :engine-pool
                          {:handler   :create-dm
                           :arguments {:user-ids [uid]}}))]
             (if (and (ok-status? res) (some-> (:room-id res) str not-empty))
               (do
                 (when on-done (on-done (:room-id res)))
                 (navigate-to-dm! (:room-id res)))
               (do
                 (log/warn "Failed to open DM with" uid ":" res)
                 (when on-error (on-error (or (:msg res) "Failed to open DM"))))))
           (catch :default e
             (log/error "Error opening DM with" uid ":" e)
             (when on-error (on-error (str e))))))))
   {}))

(re-frame/reg-event-fx
 :dm/create
 (fn [_ [_ {:keys [user-ids name is-encrypted on-success on-error]}]]
   (let [clean-ids (->> (or user-ids [])
                        (keep #(some-> % str str/trim not-empty))
                        distinct
                        vec)]
     (when (seq clean-ids)
       (go
         (try
           (let [args (cond-> {:user-ids clean-ids}
                        (some-> name str str/trim not-empty)
                        (assoc :name (str/trim (str name)))

                        (some? is-encrypted)
                        (assoc :is-encrypted (boolean is-encrypted)))
                 res  (<! (mesh/do-with-thread!
                           :engine-pool
                           {:handler   :create-dm
                            :arguments args}))]
             (if (and (ok-status? res) (some-> (:room-id res) str not-empty))
               (do
                 (re-frame/dispatch [:ui/close-modal])
                 (when on-success (on-success (:room-id res)))
                 (navigate-to-dm! (:room-id res)))
               (do
                 (log/warn "Failed to create DM:" res)
                 (when on-error (on-error (or (:msg res) nil))))))
           (catch :default e
             (log/error "Error creating DM:" e)
             (when on-error (on-error (str e))))))))
   {}))

(re-frame/reg-sub
 :dm/known-users
 (fn [db _]
   (let [my-id   (some-> (or (:active-user-id db) (get-in db [:profile :user-id])) str)
         members (:room-members db)]
     (->> (vals members)
          (mapcat (fn [entry]
                    (let [d (:data entry)]
                      (cond
                        (map? d)        (vals d)
                        (sequential? d) d
                        :else           nil))))
          (reduce
           (fn [acc m]
             (if-let [nu (some-> (norm/normalize-user m) (assoc :is-friend? false))]
               (let [uid (:user-id nu)]
                 (if (and (not= uid my-id) (not (contains? acc uid)))
                   (assoc acc uid nu)
                   acc))
               acc))
           {})
          vals
          vec))))

(defn- matches-query? [user q-lower]
  (if (str/blank? q-lower)
    true
    (let [fields [(:display-name user) (:username user) (:handle user) (:user-id user)]]
      (boolean
       (some (fn [f]
               (when (string? f)
                 (str/includes? (safe-lower f) q-lower)))
             fields)))))

(defn- looks-like-direct-target? [q]
  (norm/direct-target-id? q))

(defn- merge-user-candidates [remote-users local-users my-id query]
  (let [q-lower (safe-lower-trim query)
        by-id   (reduce
                 (fn [acc u]
                   (if-let [nu (norm/normalize-user u)]
                     (if (= (:user-id nu) my-id)
                       acc
                       (assoc acc (:user-id nu) nu))
                     acc))
                 {}
                 remote-users)
        merged  (reduce
                 (fn [acc u]
                   (let [uid (:user-id u)]
                     (if (or (nil? uid) (= uid my-id) (contains? acc uid))
                       acc
                       (if (matches-query? u q-lower)
                         (assoc acc uid u)
                         acc))))
                 by-id
                 local-users)]
    (->> (vals merged)
         (filter #(matches-query? % q-lower))
         (sort-by (fn [u]
                    [(if (:is-friend? u) 0 1)
                     (safe-lower (or (:display-name u) (:user-id u)))]))
         (take 40)
         vec)))

(defn- dm-user-row [{:keys [u idx selected-ids !active-idx trimmed-q toggle-user! tr]}]
  (let [uid      (:user-id u)
        picked?  (contains? selected-ids uid)
        active?  (= idx @!active-idx)
        presence @(re-frame/subscribe [:presence/user uid (:presence u)])]
    [:div.dm-user-row
     {:class          [(when picked? "is-selected")
                       (when active? "is-highlighted")]
      :on-mouse-enter #(reset! !active-idx idx)
      :on-click       #(toggle-user! u)}
     (if (:is-direct? u)
       [:div.dm-direct-avatar
        [icons/chat-bubble {:size "16px"}]]
       [avatar {:id     uid
                :name   (:display-name u)
                :url    (:avatar-url u)
                :size   34
                :status presence}])
     [:div.dm-user-copy
      [:div.dm-user-name-row
       [:span.dm-user-name
        (if (:is-direct? u)
          (tr [:dm/direct-start] [trimmed-q])
          (:display-name u))]
       (when (:is-friend? u)
         [:span.dm-friend-badge (tr [:dm/friend-badge])])]
      [:span.dm-user-sub
       (if (:is-direct? u)
         (tr [:dm/direct-hint])
         (or (:handle u) uid))]]
     [:div.dm-user-checkbox
      {:class (when picked? "checked")}
      (when picked?
        [icons/check {:size "13px"}])]]))

(defn- dm-recipient-bar
  [{:keys [selected query display-list supports-group? !input-el !query !selected
           !active-idx schedule-search! remove-chip! submit-dm! toggle-user! close! tr]}]
  [:div.dm-search-bar
   {:on-click #(when-let [el @!input-el] (.focus el))}
   [:div.dm-search-chips
    (for [u selected]
      ^{:key (:user-id u)}
      [:span.dm-recipient-chip
       [:span.dm-chip-label (:display-name u)]
       [:button.dm-chip-remove
        {:type       "button"
         :aria-label (tr [:dm/remove-chip] [(:display-name u)])
         :on-click   (fn [e]
                       (.stopPropagation e)
                       (remove-chip! (:user-id u)))}
        [icons/exit {:size "12px"}]]])
    [:input.dm-search-input
     {:ref         #(reset! !input-el %)
      :type        "text"
      :auto-focus  true
      :placeholder (if (seq selected) "" (tr [:dm/search-placeholder]))
      :value       query
      :on-change   (fn [e]
                     (let [v (.. e -target -value)]
                       (reset! !query v)
                       (reset! !active-idx 0)
                       (schedule-search! v)))
      :on-key-down (fn [e]
                     (let [k (.-key e)]
                       (cond
                         (= k "Escape")
                         (close!)

                         (and (= k "Backspace") (str/blank? query) (seq selected))
                         (do
                           (.preventDefault e)
                           (swap! !selected #(vec (butlast %))))

                         (= k "ArrowDown")
                         (when (seq display-list)
                           (.preventDefault e)
                           (swap! !active-idx #(mod (inc %) (count display-list))))

                         (= k "ArrowUp")
                         (when (seq display-list)
                           (.preventDefault e)
                           (swap! !active-idx #(mod (dec %) (count display-list))))

                         (= k "Enter")
                         (do
                           (.preventDefault e)
                           (cond
                             (and (str/blank? query) (seq selected))
                             (submit-dm! selected)

                             (seq display-list)
                             (when-let [target (nth display-list @!active-idx nil)]
                               (if (and (empty? selected) (not supports-group?))
                                 (submit-dm! [target])
                                 (toggle-user! target))))))))}]]])

(defn- dm-results-list
  [{:keys [loading? display-list friends-list others-list selected-ids
           !active-idx trimmed-q toggle-user! tr]}]
  [:div.dm-results-scroll
   (cond
     (and loading? (empty? display-list))
     [:div.dm-results-state (tr [:dm/searching])]

     (empty? display-list)
     [:div.dm-results-state (tr [:dm/empty])]

     :else
     [:<>
      (when (seq friends-list)
        [:<>
         [:div.dm-section-label (tr [:dm/friends-heading])]
         (map-indexed
          (fn [i u]
            ^{:key (:user-id u)}
            [dm-user-row {:u            u
                          :idx          i
                          :selected-ids selected-ids
                          :!active-idx  !active-idx
                          :trimmed-q    trimmed-q
                          :toggle-user! toggle-user!
                          :tr           tr}])
          friends-list)])
      (when (seq others-list)
        [:<>
         (when (seq friends-list)
           [:div.dm-section-label (tr [:dm/results-heading])])
         (let [offset (count friends-list)]
           (map-indexed
            (fn [i u]
              ^{:key (:user-id u)}
              [dm-user-row {:u            u
                            :idx          (+ offset i)
                            :selected-ids selected-ids
                            :!active-idx  !active-idx
                            :trimmed-q    trimmed-q
                            :toggle-user! toggle-user!
                            :tr           tr}])
            others-list))])])])

(defn- dm-modal-footer [{:keys [creating? selected trimmed-q submit-dm! close! tr]}]
  [:div.dm-modal-footer
   [:button.dm-btn.is-secondary
    {:type     "button"
     :disabled creating?
     :on-click close!}
    (tr [:dm/cancel])]
   [:button.dm-btn.is-primary
    {:type     "button"
     :disabled (or creating?
                   (and (empty? selected)
                        (not (norm/direct-target-id? trimmed-q))))
     :on-click (fn []
                 (if (seq selected)
                   (submit-dm! selected)
                   (when (norm/direct-target-id? trimmed-q)
                     (submit-dm! [{:user-id trimmed-q}]))))}
    (cond
      creating?              (tr [:dm/creating])
      (> (count selected) 1) (tr [:dm/create-group-dm] [(count selected)])
      :else                  (tr [:dm/create-dm]))]])

(defn ^:ui create-dm-modal-content [_props]
  (r/with-let [!query           (r/atom "")
               !selected        (r/atom [])
               !remote-users    (r/atom [])
               !loading-search? (r/atom true)
               !supports-group? (r/atom true)
               !supports-e2ee?  (r/atom false)
               !group-name      (r/atom "")
               !is-encrypted    (r/atom true)
               !creating?       (r/atom false)
               !error           (r/atom nil)
               !active-idx      (r/atom 0)
               !search-seq      (atom 0)
               !debounce-timer  (atom nil)
               !input-el        (atom nil)
               run-search!      (fn [q]
                                  (let [req-id (swap! !search-seq inc)]
                                    (reset! !loading-search? true)
                                    (go
                                      (try
                                        (let [res (<! (mesh/do-with-thread!
                                                       :engine-pool
                                                       {:handler   :search-users
                                                        :arguments {:query (str/trim (or q ""))
                                                                    :limit 30}}))]
                                          (when (= req-id @!search-seq)
                                            (reset! !loading-search? false)
                                            (when (ok-status? res)
                                              (reset! !remote-users (vec (or (:users res) [])))
                                              (when (contains? res :supports-group-dm?)
                                                (reset! !supports-group? (boolean (:supports-group-dm? res))))
                                              (when (contains? res :supports-e2ee?)
                                                (reset! !supports-e2ee? (boolean (:supports-e2ee? res)))))))
                                        (catch :default e
                                          (log/debug "User search error:" e)
                                          (when (= req-id @!search-seq)
                                            (reset! !loading-search? false)))))))
               schedule-search! (fn [q]
                                  (when-let [t @!debounce-timer]
                                    (js/clearTimeout t))
                                  (reset! !debounce-timer
                                          (js/setTimeout
                                           (fn []
                                             (reset! !debounce-timer nil)
                                             (run-search! q))
                                           200)))
               _                (run-search! "")]
    (let [tr              @(re-frame/subscribe [:i18n/tr])
          my-id           (some-> @(re-frame/subscribe [:auth/active-user-id]) str)
          known-users     @(re-frame/subscribe [:dm/known-users])
          query           @!query
          trimmed-q       (str/trim query)
          selected        @!selected
          selected-ids    (set (map :user-id selected))
          supports-group? @!supports-group?
          supports-e2ee?  @!supports-e2ee?
          loading?        @!loading-search?
          creating?       @!creating?
          error-msg       @!error
          candidates      (merge-user-candidates @!remote-users known-users my-id query)
          exact-present?  (some #(= (safe-lower (:user-id %))
                                    (safe-lower trimmed-q))
                                candidates)
          direct-entry    (when (and (norm/direct-target-id? trimmed-q)
                                     (not exact-present?)
                                     (not= trimmed-q my-id))
                            {:user-id      trimmed-q
                             :display-name trimmed-q
                             :handle       trimmed-q
                             :is-direct?   true})
          display-list    (if direct-entry
                            (into [direct-entry] candidates)
                            candidates)
          friends-list    (filterv :is-friend? display-list)
          others-list     (filterv #(not (:is-friend? %)) display-list)
          close!          #(re-frame/dispatch [:ui/close-modal])
          submit-dm!      (fn [users-vec]
                            (when (and (seq users-vec) (not creating?))
                              (reset! !creating? true)
                              (reset! !error nil)
                              (re-frame/dispatch
                               [:dm/create
                                (cond-> {:user-ids (mapv :user-id users-vec)
                                         :on-error (fn [msg]
                                                     (reset! !creating? false)
                                                     (reset! !error
                                                             (or (not-empty (str msg))
                                                                 (tr [:dm/error-failed]))))}
                                  (> (count users-vec) 1)
                                  (assoc :name @!group-name)

                                  supports-e2ee?
                                  (assoc :is-encrypted @!is-encrypted))])))
          toggle-user!    (fn [u]
                            (let [uid (:user-id u)]
                              (reset! !error nil)
                              (if-not supports-group?
                                (if (contains? selected-ids uid)
                                  (reset! !selected [])
                                  (reset! !selected [u]))
                                (swap! !selected
                                       (fn [curr]
                                         (if (some #(= (:user-id %) uid) curr)
                                           (filterv #(not= (:user-id %) uid) curr)
                                           (conj (vec curr) u)))))
                              (when (seq query)
                                (reset! !query "")
                                (run-search! ""))
                              (when-let [el @!input-el]
                                (.focus el))))
          remove-chip!    (fn [uid]
                            (swap! !selected
                                   (fn [curr]
                                     (filterv #(not= (:user-id %) uid) curr))))]
      [:div.dm-modal-container
       [:div.dm-modal-header
        [:div.dm-modal-heading
         [:h3.dm-modal-title (tr [:dm/modal-title])]
         [:p.dm-modal-subtitle
          (if supports-group?
            (tr [:dm/modal-subtitle-group])
            (tr [:dm/modal-subtitle]))]]
        [:button.dm-close-btn
         {:type     "button"
          :on-click close!}
         [icons/exit {:size "18px"}]]]

       [dm-recipient-bar {:selected         selected
                          :query            query
                          :display-list     display-list
                          :supports-group?  supports-group?
                          :!input-el        !input-el
                          :!query           !query
                          :!selected        !selected
                          :!active-idx      !active-idx
                          :schedule-search! schedule-search!
                          :remove-chip!     remove-chip!
                          :submit-dm!       submit-dm!
                          :toggle-user!     toggle-user!
                          :close!           close!
                          :tr               tr}]

       (when error-msg
         [:div.dm-error-banner error-msg])

       [dm-results-list {:loading?     loading?
                         :display-list display-list
                         :friends-list friends-list
                         :others-list  others-list
                         :selected-ids selected-ids
                         :!active-idx  !active-idx
                         :trimmed-q    trimmed-q
                         :toggle-user! toggle-user!
                         :tr           tr}]

       (when (and supports-group? (> (count selected) 1))
         [:div.dm-extra-row
          [:label.dm-field-label (tr [:dm/group-name-label])]
          [:input.dm-group-name-input
           {:type        "text"
            :placeholder (tr [:dm/group-name-placeholder])
            :value       @!group-name
            :on-change   #(reset! !group-name (.. % -target -value))}]])

       (when supports-e2ee?
         [:label.dm-e2ee-row
          [:span.dm-e2ee-copy
           [icons/shield {:size "15px"}]
           [:span (tr [:dm/encryption-label])]]
          [:input {:type      "checkbox"
                   :checked   @!is-encrypted
                   :on-change #(swap! !is-encrypted not)}]])

       [dm-modal-footer {:creating?  creating?
                         :selected   selected
                         :trimmed-q  trimmed-q
                         :submit-dm! submit-dm!
                         :close!     close!
                         :tr         tr}]])
    (finally
      (when-let [t @!debounce-timer]
        (js/clearTimeout t)))))

(defmethod modal-component :create-dm [_]
  create-dm-modal-content)