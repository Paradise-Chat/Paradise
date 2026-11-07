(ns paradise.ui.input.autocomplete
  (:require
   [re-frame.core :as re-frame]
   [taoensso.timbre :as log]
   [clojure.string :as str]
   [paradise.shared.utils.helpers :refer [clean-shortcode]]
   [paradise.media.component :refer [media]]
   [paradise.ui.global :refer [avatar handle-list-navigation selectable-list]]))

(re-frame/reg-sub
 :mention/filtered-users
 (fn [[_ room-id]]
   [(re-frame/subscribe [:room/members-map room-id])
    (re-frame/subscribe [:suggestion/state])])
 (fn [[members state] _]
   (let [query (str/lower-case (or (:query state) ""))]
     (->> (vals members)
          (filter (fn [u]
                    (let [dname (str/lower-case (or (:display-name u) (:name u) ""))
                          uid   (str/lower-case (or (:id u) (:user-id u) ""))]
                      (or (str/includes? dname query)
                          (str/includes? uid query)))))
          (take 8)))))

(re-frame/reg-sub
 :emoji/filtered-suggestions
 :<- [:emoji/active-set]
 :<- [:suggestion/state]
 (fn [[packs state] _]
   (let [query (str/lower-case (str (or (:query state) "")))]
     (->> (vals packs)
          (mapcat (fn [pack]
                    (for [[raw-sc item] (:images pack)
                          :let [sc  (clean-shortcode (or (:shortcode item) raw-sc))
                                uri (or (:uri item) (:url item))]
                          :when (and (seq sc) uri)]
                      [sc (assoc item
                                 :shortcode sc
                                 :uri       uri
                                 :url       uri)])))
          (filter (fn [[sc _]]
                    (str/includes? (str/lower-case sc) query)))
          (take 10)))))

(re-frame/reg-event-db
 :suggestion/open-menu
 (fn [db [_ type rect]]
   (assoc db :suggestion/menu
          {:active? true
           :type    type
           :rect    {:top    (.-top rect)
                     :left   (.-left rect)
                     :height (.-height rect)}
           :query   ""
           :index   0})))

(re-frame/reg-event-db
 :suggestion/filter-menu
 (fn [db [_ query]]
   (assoc-in db [:suggestion/menu :query] query)))

(re-frame/reg-event-db
 :suggestion/close-menu
 (fn [db _]
   (assoc-in db [:suggestion/menu :active?] false)))

(re-frame/reg-event-db
 :suggestion/nav
 (fn [db [_ direction max-items]]
   (update-in db [:suggestion/menu :index]
              (fn [idx]
                (let [current (or idx 0)]
                  (if (= direction :next)
                    (mod (inc current) max-items)
                    (mod (dec current) max-items)))))))

(re-frame/reg-sub
 :suggestion/state
 (fn [db _]
   (:suggestion/menu db)))

(re-frame/reg-event-db
 :suggestion/set-index
 (fn [db [_ idx]]
   (assoc-in db [:suggestion/menu :index] idx)))


(defonce !active-command (atom nil))

(defn handle-suggestion-keydown [^js props type]
  (let [e       (.-event props)
        room-id @(re-frame/subscribe [:rooms/active-id])
        state   @(re-frame/subscribe [:suggestion/state])
        items   (if (= type :emoji)
                  @(re-frame/subscribe [:emoji/filtered-suggestions])
                  @(re-frame/subscribe [:mention/filtered-users room-id]))
        idx     (or (:index state) 0)]
    (handle-list-navigation
     e items idx
     #(re-frame/dispatch [:suggestion/set-index %])
     (fn [raw-item]
       (when-let [cmd @!active-command]
         (let [selected-item (if (= type :emoji) (second raw-item) raw-item)]
           (cmd #js {:props selected-item})))))))

(defonce !active-command (atom nil))

(defn handle-suggestion-keydown [^js props type]
  (let [e       (.-event props)
        room-id @(re-frame/subscribe [:rooms/active-id])
        state   @(re-frame/subscribe [:suggestion/state])
        items   (if (= type :emoji)
                  @(re-frame/subscribe [:emoji/filtered-suggestions])
                  @(re-frame/subscribe [:mention/filtered-users room-id]))
        idx     (or (:index state) 0)]
    (handle-list-navigation
     e items idx
     #(re-frame/dispatch [:suggestion/set-index %])
     (fn [raw-item]
       (when-let [cmd @!active-command]
         (let [selected-item (if (= type :emoji)
                               (let [[sc item] raw-item]
                                 (assoc item :shortcode (clean-shortcode (or (:shortcode item) sc))))
                               raw-item)]
           (cmd #js {:props selected-item})))))))

(defn emoji-suggestion-options []
  #js {:char    ":"
       :command (fn [^js props]
                  (let [ed        (.-editor props)
                        range     (.-range props)
                        item      (.-props (.-props props))
                        uri       (or (:uri item) (:url item))
                        shortcode (clean-shortcode (:shortcode item))]
                    (-> ed .chain .focus
                        (.deleteRange range)
                        (.insertContent #js {:type  "customEmote"
                                             :attrs #js {:id        (:id item)
                                                         :shortcode shortcode
                                                         :uri       uri
                                                         :src       uri}})
                        .run)))
       :render  (fn []
                  #js {:onStart   (fn [props]
                                    (reset! !active-command (.-command props))
                                    (re-frame/dispatch [:suggestion/open-menu :emoji (.-clientRect props)]))
                       :onUpdate  (fn [props]
                                    (reset! !active-command (.-command props))
                                    (re-frame/dispatch [:suggestion/filter-menu (.-query props)]))
                       :onKeyDown #(handle-suggestion-keydown % :emoji)
                       :onExit    (fn []
                                    (reset! !active-command nil)
                                    (re-frame/dispatch [:suggestion/close-menu]))})})

(defn user-mention-options [& [on-start on-exit]]
  #js {:char    "@"
       :command (fn [^js props]
                  (let [ed    (.-editor props)
                        range (.-range props)
                        user  (.-props (.-props props))
                        uid   (or (:id user) (:user-id user))
                        label (or (:display-name user) (:name user) uid)]
                    (-> ed .chain .focus
                        (.deleteRange range)
                        (.insertContent #js {:type  "userMention"
                                             :attrs #js {:id    uid
                                                         :label label
                                                         :uri   (:uri user)}})
                        (.insertContent " ")
                        .run)))
       :render  (fn []
                  #js {:onStart   (fn [props]
                                    (reset! !active-command (.-command props))
                                    (when on-start (on-start (.-command props)))
                                    (let [active-id @(re-frame/subscribe [:rooms/active-id])]
                                      (re-frame/dispatch [:room/fetch-members active-id]))
                                    (re-frame/dispatch [:suggestion/open-menu :mention (.-clientRect props)]))
                       :onUpdate  (fn [props]
                                    (reset! !active-command (.-command props))
                                    (re-frame/dispatch [:suggestion/filter-menu (.-query props)]))
                       :onKeyDown #(handle-suggestion-keydown % :mention)
                       :onExit    (fn []
                                    (reset! !active-command nil)
                                    (when on-exit (on-exit))
                                    (re-frame/dispatch [:suggestion/close-menu]))})})

(defn suggestion-menu [& _]
  (let [{:keys [active? type rect index]} @(re-frame/subscribe [:suggestion/state])
        room-id @(re-frame/subscribe [:rooms/active-id])
        emojis  @(re-frame/subscribe [:emoji/filtered-suggestions])
        members @(re-frame/subscribe [:mention/filtered-users room-id])
        tr      @(re-frame/subscribe [:i18n/tr])]
    (when (and active? rect)
      (let [is-emoji?     (= type :emoji)
            items         (if is-emoji? emojis members)
            max-items     (count items)
            safe-idx      (if (>= index max-items) 0 index)
            render-above? (> (:top rect) 250)]
        [:div.suggestion-popup
         {:class (when render-above? "is-above")
          :style (if render-above?
                   {:bottom (str (+ (- (.-innerHeight js/window) (:top rect)) 5) "px")
                    :left   (str (:left rect) "px")}
                   {:top    (str (+ (:top rect) (:height rect) 5) "px")
                    :left   (str (:left rect) "px")})}
         [selectable-list
          {:items          items
           :selected-index safe-idx
           :empty-text     (tr [:composer.suggestions/no-results])
           :item-class     "suggestion-item"
           :key-fn         (fn [item]
                             (if is-emoji?
                               (first item)
                               (or (:id item) (:user-id item))))
           :on-select      (fn [raw-item]
                             (when-let [cmd @!active-command]
                               (let [selected (if is-emoji?
                                                (let [[sc item] raw-item]
                                                  (assoc item :shortcode (clean-shortcode (or (:shortcode item) sc))))
                                                raw-item)]
                                 (cmd #js {:props selected}))))
           :render-item    (fn [item _selected?]
                             (if is-emoji?
                               (let [[shortcode emote-data] item
                                     uri (or (:uri emote-data) (:url emote-data))]
                                 [:<>
                                  [media {:uri   uri
                                          :class "suggestion-img"
                                          :alt   shortcode}]
                                  [:span.suggestion-text ":" shortcode ":"]])
                               (let [uid   (or (:id item) (:user-id item))
                                     dname (or (:display-name item) (:name item) uid "?")]
                                 [:<>
                                  [avatar {:id    uid
                                           :name  dname
                                           :url   (:avatar-url item)
                                           :size  24
                                           :shape :circle}]
                                  [:div.suggestion-details
                                   [:span.suggestion-name dname]
                                   [:span.suggestion-id uid]]])))}]]))))