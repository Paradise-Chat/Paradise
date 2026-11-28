(ns paradise.ui.container.discovery
  (:require
   [cljs-workers.mesh :as mesh]
   [cljs.core.async :refer [<! go]]
   [clojure.string :as str]
   [paradise.media.component :refer [media]]
   [paradise.shared.utils.helpers :refer [first-non-blank local-media-url? ok-status?]]
   [paradise.shared.utils.svg :as icons]
   [paradise.ui.global :refer [avatar]]
   [re-frame.core :as re-frame]
   [reagent.core :as r]
   [taoensso.timbre :as log]))

(defonce ^:private !discovery-state
  (r/atom {:engine-id         nil
           :query             ""
           :server            ""
           :active-server     nil
           :category          nil
           :type-filter       :all
           :items             []
           :next-batch        nil
           :supports-servers? false
           :servers           []
           :categories        []
           :loading?          false
           :loading-more?     false
           :error             nil
           :joining-ids       #{}
           :joined-ids        #{}
           :direct-input      ""
           :direct-joining?   false
           :direct-error      nil}))

(defonce ^:private !request-seq (atom 0))

(defn- normalize-directory-item [raw]
  (when-let [id (first-non-blank (:id raw))]
    {:id           id
     :alias        (first-non-blank (:alias raw))
     :name         (first-non-blank (:name raw) (:alias raw) id)
     :topic        (first-non-blank (:topic raw))
     :avatar       (first-non-blank (:avatar raw))
     :banner       (first-non-blank (:banner raw))
     :member-count (when (number? (:member-count raw)) (int (:member-count raw)))
     :online-count (when (number? (:online-count raw)) (int (:online-count raw)))
     :is-space?    (boolean (:is-space? raw))
     :joined?      (boolean (:joined? raw))
     :join-rule    (:join-rule raw)
     :server       (:server raw)
     :invite-code  (:invite-code raw)
     :relay-url    (:relay-url raw)
     :via          (when (sequential? (:via raw)) (vec (:via raw)))}))

(defn- fetch-directory!
  [{:keys [query server category since append? engine-id]}]
  (let [req-id (swap! !request-seq inc)]
    (swap! !discovery-state
           (fn [st]
             (cond-> (assoc st
                            :engine-id engine-id
                            :error nil
                            :loading? (not append?)
                            :loading-more? (boolean append?))
               (not append?) (assoc :next-batch nil))))
    (go
      (try
        (let [args (cond-> {:query (str/trim (or query ""))
                            :limit 24}
                     (not (str/blank? (str server)))   (assoc :server (str/trim (str server)))
                     (not (str/blank? (str category))) (assoc :category (str category))
                     (not (str/blank? (str since)))    (assoc :since (str since)))
              res  (<! (mesh/do-with-thread!
                        :engine-pool
                        {:handler   :search-directory
                         :arguments args}))]
          (when (= req-id @!request-seq)
            (if (ok-status? res)
              (let [incoming (into [] (keep normalize-directory-item) (or (:items res) []))]
                (swap! !discovery-state
                       (fn [st]
                         (let [merged-items (if append?
                                              (let [seen (into #{} (map :id) (:items st))]
                                                (into (:items st)
                                                      (remove #(contains? seen (:id %)))
                                                      incoming))
                                              incoming)]
                           (cond-> (assoc st
                                          :items merged-items
                                          :next-batch (not-empty (str (or (:next-batch res) "")))
                                          :loading? false
                                          :loading-more? false
                                          :error nil)
                             (contains? res :supports-servers?)
                             (assoc :supports-servers? (boolean (:supports-servers? res)))

                             (sequential? (:servers res))
                             (assoc :servers (vec (:servers res)))

                             (some? (:active-server res))
                             (assoc :active-server (:active-server res))

                             (sequential? (:categories res))
                             (assoc :categories (vec (:categories res))))))))
              (swap! !discovery-state assoc
                     :loading? false
                     :loading-more? false
                     :error (or (not-empty (str (:msg res))) :load-failed)))))
        (catch :default e
          (log/warn "Discovery search failed:" e)
          (when (= req-id @!request-seq)
            (swap! !discovery-state assoc
                   :loading? false
                   :loading-more? false
                   :error (str e))))))))

(defn- navigate-after-join! [room-id is-space?]
  (when (and room-id (not (str/blank? (str room-id))))
    (re-frame/dispatch [:container/set-main-focus :timeline])
    (if is-space?
      (re-frame/dispatch [:space/select (str room-id)])
      (re-frame/dispatch [:rooms/select (str room-id)]))))

(defn- join-discovered-item! [item]
  (let [item-id (:id item)]
    (when (and item-id (not (contains? (:joining-ids @!discovery-state) item-id)))
      (swap! !discovery-state
             #(-> %
                  (update :joining-ids conj item-id)
                  (assoc :error nil)))
      (go
        (try
          (let [args (cond-> {:room-id item-id
                              :is-space? (boolean (:is-space? item))}
                       (:alias item)       (assoc :alias (:alias item))
                       (:via item)         (assoc :via (:via item))
                       (:invite-code item) (assoc :invite-code (:invite-code item))
                       (:relay-url item)   (assoc :relay-url (:relay-url item))
                       (:server item)      (assoc :server (:server item)))
                res  (<! (mesh/do-with-thread!
                          :engine-pool
                          {:handler   :join-room
                           :arguments args}))]
            (swap! !discovery-state update :joining-ids disj item-id)
            (if (ok-status? res)
              (let [target-id  (first-non-blank (:room-id res) item-id)
                    is-space?  (if (contains? res :is-space?)
                                 (boolean (:is-space? res))
                                 (boolean (:is-space? item)))]
                (swap! !discovery-state update :joined-ids conj item-id target-id)
                (navigate-after-join! target-id is-space?))
              (swap! !discovery-state assoc
                     :error (or (not-empty (str (:msg res))) :join-failed))))
          (catch :default e
            (log/warn "Discovery join failed:" e)
            (swap! !discovery-state
                   #(-> %
                        (update :joining-ids disj item-id)
                        (assoc :error (str e))))))))))

(defn- join-direct-target! [raw-target]
  (let [target (str/trim (or raw-target ""))]
    (when (and (not (str/blank? target)) (not (:direct-joining? @!discovery-state)))
      (swap! !discovery-state assoc :direct-joining? true :direct-error nil)
      (go
        (try
          (let [res (<! (mesh/do-with-thread!
                         :engine-pool
                         {:handler   :join-room
                          :arguments {:room-id target}}))]
            (if (ok-status? res)
              (let [target-id (first-non-blank (:room-id res) target)
                    is-space? (boolean (:is-space? res))]
                (swap! !discovery-state assoc
                       :direct-joining? false
                       :direct-input ""
                       :direct-error nil)
                (navigate-after-join! target-id is-space?))
              (swap! !discovery-state assoc
                     :direct-joining? false
                     :direct-error (or (not-empty (str (:msg res))) :join-failed))))
          (catch :default e
            (log/warn "Direct join failed:" e)
            (swap! !discovery-state assoc
                   :direct-joining? false
                   :direct-error (str e))))))))

(re-frame/reg-event-fx
 :discovery/open
 (fn [{:keys [db]} _]
   (let [eng-id (:active-engine db)
         prev   @!discovery-state]
     (when (or (not= (:engine-id prev) eng-id)
               (empty? (:items prev)))
       (when (not= (:engine-id prev) eng-id)
         (reset! !discovery-state
                 {:engine-id         eng-id
                  :query             ""
                  :server            ""
                  :active-server     nil
                  :category          nil
                  :type-filter       :all
                  :items             []
                  :next-batch        nil
                  :supports-servers? false
                  :servers           []
                  :categories        []
                  :loading?          true
                  :loading-more?     false
                  :error             nil
                  :joining-ids       #{}
                  :joined-ids        #{}
                  :direct-input      ""
                  :direct-joining?   false
                  :direct-error      nil}))
       (fetch-directory! {:query "" :engine-id eng-id}))
     {:db (-> db
              (assoc :main-focus :discovery
                     :side-panel nil)
              (assoc-in [:ui :sidebar-open?] false))})))

(defn- deterministic-banner-gradient [seed]
  (let [palettes [["#3b449b" "#5865f2"]
                  ["#0f766e" "#14b8a6"]
                  ["#7c2d12" "#ea580c"]
                  ["#581c87" "#9333ea"]
                  ["#1e3a8a" "#2563eb"]
                  ["#831843" "#db2777"]]
        h        (Math/abs (hash (str (or seed ""))))
        [c1 c2]  (nth palettes (mod h (count palettes)))]
    (str "linear-gradient(135deg, " c1 " 0%, " c2 " 100%)")))

(defn- format-count [n]
  (cond
    (nil? n)       nil
    (>= n 1000000) (str (.toFixed (/ n 1000000.0) 1) "M")
    (>= n 1000)    (str (.toFixed (/ n 1000.0) 1) "k")
    :else          (str n)))

(defn ^:ui discovery-card [item {:keys [tr active-uid joined? joining?]}]
  (let [banner-url (:banner item)
        avatar-url (:avatar item)
        is-space?  (:is-space? item)
        m-count    (format-count (:member-count item))
        o-count    (format-count (:online-count item))
        sub-label  (first-non-blank (:alias item) (:server item) (:relay-url item))]
    [:div.discovery-card
     [:div.discovery-card-banner
      {:style (when-not banner-url
                {:background (deterministic-banner-gradient (:id item))})}
      (when banner-url
        (if (local-media-url? banner-url)
          [:img.discovery-card-banner-img {:src banner-url :alt ""}]
          [media {:uri     banner-url
                  :user-id active-uid
                  :class   "discovery-card-banner-img"
                  :alt     ""}]))]
     [:div.discovery-card-body
      [:div.discovery-card-top
       [:div.discovery-card-avatar
        [avatar {:id    (:id item)
                 :name  (:name item)
                 :url   avatar-url
                 :size  44
                 :shape (if is-space? :squircle :rounded)}]]
       (when is-space?
         [:span.discovery-space-badge (tr [:discovery/space-badge])])]
      [:div.discovery-card-title (:name item)]
      (when sub-label
        [:div.discovery-card-alias sub-label])
      (when (:topic item)
        [:p.discovery-card-topic (:topic item)])
      [:div.discovery-card-footer
       [:div.discovery-card-stats
        (when o-count
          [:span.discovery-stat.is-online
           [:span.discovery-dot.is-online]
           (tr [:discovery/online-count] [o-count])])
        (when m-count
          [:span.discovery-stat
           [:span.discovery-dot]
           (tr [:discovery/members-count] [m-count])])]
       (if joined?
         [:button.discovery-card-btn.is-open
          {:type     "button"
           :on-click #(navigate-after-join! (:id item) is-space?)}
          (tr [:discovery/open-btn])]
         [:button.discovery-card-btn.is-join
          {:type     "button"
           :disabled joining?
           :on-click #(join-discovered-item! item)}
          (if joining?
            (tr [:discovery/joining-btn])
            (tr [:discovery/join-btn]))])]]]))

(defn ^:ui discovery-view []
  (r/with-let [!debounce-timer (atom nil)
               !custom-server  (r/atom "")]
    (let [tr              @(re-frame/subscribe [:i18n/tr])
          active-engine   @(re-frame/subscribe [:auth/active-engine])
          active-uid      @(re-frame/subscribe [:auth/active-user-id])
          known-rooms     @(re-frame/subscribe [:rooms/unfiltered-indexed-map])
          known-spaces    @(re-frame/subscribe [:spaces/map])
          st              @!discovery-state
          _               (when (and active-engine
                                     (not= (:engine-id st) active-engine)
                                     (not (:loading? st)))
                            (js/queueMicrotask
                             #(when (not= (:engine-id @!discovery-state) active-engine)
                                (fetch-directory! {:query "" :engine-id active-engine}))))
          {:keys [query server active-server category type-filter items next-batch
                  supports-servers? servers categories loading? loading-more? error
                  joining-ids joined-ids direct-input direct-joining? direct-error]} st
          has-spaces?     (some :is-space? items)
          has-rooms?      (some #(not (:is-space? %)) items)
          visible-items   (case type-filter
                            :spaces (filterv :is-space? items)
                            :rooms  (filterv #(not (:is-space? %)) items)
                            items)
          trigger-search! (fn [next-q next-server next-cat]
                            (when-let [t @!debounce-timer]
                              (js/clearTimeout t)
                              (reset! !debounce-timer nil))
                            (fetch-directory! {:query     next-q
                                               :server    next-server
                                               :category  next-cat
                                               :engine-id active-engine}))
          schedule-q!     (fn [next-q]
                            (swap! !discovery-state assoc :query next-q)
                            (when-let [t @!debounce-timer]
                              (js/clearTimeout t))
                            (reset! !debounce-timer
                                    (js/setTimeout
                                     (fn []
                                       (reset! !debounce-timer nil)
                                       (let [cur @!discovery-state]
                                         (fetch-directory! {:query     (:query cur)
                                                            :server    (:server cur)
                                                            :category  (:category cur)
                                                            :engine-id active-engine})))
                                     300)))
          resolve-err     (fn [e]
                            (cond
                              (= e :load-failed) (tr [:discovery/error-load])
                              (= e :join-failed) (tr [:discovery/error-join])
                              (string? e)        e
                              :else              nil))]
      [:div.discovery-page
       [:header.discovery-topbar
        [:button.mobile-menu-btn
         {:type     "button"
          :on-click #(re-frame/dispatch [:ui/toggle-sidebar])}
         [icons/arrow-left {:width "20px" :height "20px"}]]
        [:span.discovery-topbar-icon [icons/compass {:size "20px"}]]
        [:h2.discovery-topbar-title (tr [:discovery/title])]]

       [:div.discovery-scroll
        [:div.discovery-hero
         [:h1.discovery-hero-title (tr [:discovery/title])]
         [:p.discovery-hero-subtitle (tr [:discovery/subtitle])]
         [:form.discovery-search-form
          {:on-submit (fn [e]
                        (.preventDefault e)
                        (trigger-search! query server category))}
          [:span.discovery-search-icon [icons/search {:size "18px"}]]
          [:input.discovery-search-input
           {:type        "text"
            :placeholder (tr [:discovery/search-placeholder])
            :value       query
            :on-change   #(schedule-q! (.. % -target -value))}]
          [:button.discovery-search-submit
           {:type "submit"}
           (tr [:discovery/search-btn])]]

         [:form.discovery-direct-form
          {:on-submit (fn [e]
                        (.preventDefault e)
                        (join-direct-target! direct-input))}
          [:span.discovery-direct-icon [icons/link {:size "15px"}]]
          [:input.discovery-direct-input
           {:type        "text"
            :placeholder (tr [:discovery/direct-join-placeholder])
            :value       direct-input
            :on-change   #(swap! !discovery-state assoc
                                 :direct-input (.. % -target -value)
                                 :direct-error nil)}]
          [:button.discovery-direct-submit
           {:type     "submit"
            :disabled (or direct-joining? (str/blank? direct-input))}
           (if direct-joining?
             (tr [:discovery/direct-joining])
             (tr [:discovery/direct-join-btn]))]]
         (when-let [d-err (resolve-err direct-error)]
           [:div.discovery-error-banner d-err])]

        [:div.discovery-controls
         (when (or supports-servers? (seq servers))
           [:div.discovery-server-bar
            [:span.discovery-control-label (tr [:discovery/server-label])]
            [:div.discovery-pills
             (for [srv servers
                   :let [cur-srv (or (not-empty server) active-server)
                         active? (= srv cur-srv)]]
               ^{:key srv}
               [:button.discovery-pill
                {:type     "button"
                 :class    (when active? "is-active")
                 :on-click (fn []
                             (swap! !discovery-state assoc :server srv)
                             (trigger-search! query srv category))}
                srv])
             (when supports-servers?
               [:form.discovery-custom-server
                {:on-submit (fn [e]
                              (.preventDefault e)
                              (let [s (str/trim @!custom-server)]
                                (when-not (str/blank? s)
                                  (swap! !discovery-state assoc :server s)
                                  (trigger-search! query s category))))}
                [:input.discovery-server-input
                 {:type        "text"
                  :placeholder (tr [:discovery/server-placeholder])
                  :value       @!custom-server
                  :on-change   #(reset! !custom-server (.. % -target -value))}]])]])

         (when (seq categories)
           [:div.discovery-pills
            [:button.discovery-pill
             {:type     "button"
              :class    (when (nil? category) "is-active")
              :on-click (fn []
                          (swap! !discovery-state assoc :category nil)
                          (trigger-search! query server nil))}
             (tr [:discovery/filter-all])]
            (for [{cat-id :id cat-name :name} categories
                  :when cat-id]
              ^{:key cat-id}
              [:button.discovery-pill
               {:type     "button"
                :class    (when (= category (str cat-id)) "is-active")
                :on-click (fn []
                            (let [cid (str cat-id)]
                              (swap! !discovery-state assoc :category cid)
                              (trigger-search! query server cid)))}
               (or cat-name (str cat-id))])])

         (when (and has-spaces? has-rooms?)
           [:div.discovery-pills
            (for [[tf label-key] [[:all :discovery/filter-all]
                                  [:spaces :discovery/filter-spaces]
                                  [:rooms :discovery/filter-rooms]]]
              ^{:key tf}
              [:button.discovery-pill
               {:type     "button"
                :class    (when (= type-filter tf) "is-active")
                :on-click #(swap! !discovery-state assoc :type-filter tf)}
               (tr [label-key])])])]

        (when-let [err-txt (resolve-err error)]
          [:div.discovery-error-banner.is-main err-txt])

        (cond
          loading?
          [:div.discovery-state-box
           [:div.discovery-spinner]
           [:p (tr [:discovery/loading])]]

          (empty? visible-items)
          [:div.discovery-state-box
           [:div.discovery-empty-icon [icons/compass {:size "36px"}]]
           [:h3 (tr [:discovery/empty-title])]
           [:p (tr [:discovery/empty-hint])]]

          :else
          [:<>
           [:div.discovery-grid
            (for [item visible-items
                  :let [iid      (:id item)
                        joined?  (boolean (or (:joined? item)
                                              (contains? joined-ids iid)
                                              (contains? known-rooms iid)
                                              (contains? known-spaces iid)))
                        joining? (contains? joining-ids iid)]]
              ^{:key iid}
              [discovery-card item {:tr         tr
                                    :active-uid active-uid
                                    :joined?    joined?
                                    :joining?   joining?}])]
           (when next-batch
             [:div.discovery-load-more-wrap
              [:button.discovery-load-more-btn
               {:type     "button"
                :disabled loading-more?
                :on-click #(fetch-directory! {:query     query
                                              :server    server
                                              :category  category
                                              :since     next-batch
                                              :append?   true
                                              :engine-id active-engine})}
               (if loading-more?
                 (tr [:discovery/loading])
                 (tr [:discovery/load-more]))]])])]])
    (finally
      (when-let [t @!debounce-timer]
        (js/clearTimeout t)))))