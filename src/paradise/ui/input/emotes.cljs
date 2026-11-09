(ns paradise.ui.input.emotes
  (:require
   [promesa.core :as p]
   [re-frame.core :as re-frame]
   [taoensso.timbre :as log]
   [clojure.string :as str]
   [cljs.core.async :refer [go <!]]
   [cljs-workers.core :as main]
   [paradise.shared.client.state :as state]
   [reagent.core :as r]
   [paradise.shared.utils.helpers :refer [clean-shortcode]]
   [paradise.ui.global :refer [click-away-wrapper]]
   [paradise.media.component :refer [media]]
   ["emojibase-data/en/data.json" :as emoji-data]
   ["react" :as react]))

(re-frame/reg-event-fx
 :sdk/fetch-room-emotes
 (fn [_ [_ source-type room-id]]
   (if-let [pool @state/!engine-pool]
     (go
       (let [res (<! (main/do-with-pool! pool
                                         {:handler   :fetch-emotes
                                          :arguments {:source-type (name source-type)
                                                      :room-id     room-id}}))]
         (if (= (:status res) "success")
           (re-frame/dispatch [:sdk/save-all-emotes source-type room-id (:packs res)])
           (log/error "Worker emote fetch failed:" (:msg res)))))
     (log/error "No engine pool to fetch emotes"))
   {}))

(re-frame/reg-event-fx
 :sdk/fetch-all-emotes
 (fn [{:keys [db]} _]
   (re-frame/dispatch [:sdk/fetch-room-emotes :account nil])
   (when-let [active-room (:active-room-id db)]
     (re-frame/dispatch [:sdk/fetch-room-emotes :room active-room]))
   (when-let [active-space (:active-space-id db)]
     (re-frame/dispatch [:sdk/fetch-room-emotes :space active-space]))
   {}))

(re-frame/reg-event-db
 :sdk/save-all-emotes
 (fn [db [_ source-type source-id fetched-packs]]
   (let [structured-packs
         (into {}
               (for [{:keys [pack-id data]} (remove nil? fetched-packs)
                     :let [clj-data    (if (map? data) data (js->clj data :keywordize-keys true))
                           raw-images  (or (:images clj-data) (:items clj-data))
                           pack-info   (or (:pack clj-data) clj-data)
                           usage-set   (set (:usage pack-info []))
                           is-sticker? (or (:is-sticker-pack? pack-info)
                                           (contains? usage-set "sticker"))
                           images      (into {}
                                             (for [[raw-k v] raw-images
                                                   :let [sc  (clean-shortcode raw-k)
                                                         uri (or (:uri v) (:url v))]
                                                   :when (and (seq sc) uri)]
                                               [sc {:id               (or (:id v) (str pack-id ":" sc))
                                                    :shortcode        sc
                                                    :uri              uri
                                                    :url              uri
                                                    :info             (:info v)
                                                    :search-text      (:search-text v)
                                                    :is-unicode?      false
                                                    :is-sticker-pack? is-sticker?}]))]
                     :when (seq images)]
                 [pack-id {:name             (or (:name pack-info) (:display_name pack-info) pack-id)
                           :avatar           (or (:avatar pack-info) (:avatar_url pack-info))
                           :is-sticker-pack? is-sticker?
                           :images           images}]))]
     (if (= source-type :account)
       (assoc-in db [:emoji/packs :account] structured-packs)
       (assoc-in db [:emoji/packs source-type source-id] structured-packs)))))

(re-frame/reg-sub
 :emoji/active-set
 (fn [db _]
   (let [active-space  (:active-space-id db)
         active-room   (:active-room-id db)
         account-packs (get-in db [:emoji/packs :account] {})
         space-packs   (when active-space (get-in db [:emoji/packs :space active-space] {}))
         room-packs    (when active-room (get-in db [:emoji/packs :room active-room] {}))]
     (merge account-packs space-packs room-packs))))


(defonce categorized-system-packs
  (let [unwrap     (fn [mod] (if (and mod (.-default mod)) (.-default mod) mod))
        raw-data   (js->clj (unwrap emoji-data) :keywordize-keys true)
        group-meta {0 {:name "Smileys & Emotion" :icon "😀"}
                    1 {:name "People & Body"     :icon "👋"}
                    3 {:name "Animals & Nature"  :icon "🐶"}
                    4 {:name "Food & Drink"      :icon "🍔"}
                    5 {:name "Travel & Places"   :icon "✈️"}
                    6 {:name "Activities"        :icon "⚽"}
                    7 {:name "Objects"           :icon "💡"}
                    8 {:name "Symbols"           :icon "💖"}
                    9 {:name "Flags"             :icon "🚩"}}]
    (->> raw-data
         (group-by :group)
         (reduce-kv (fn [acc group-id emojis]
                      (if (or (nil? group-id) (= group-id 9))
                        acc
                        (let [meta    (get group-meta group-id {:name "Other" :icon "📦"})
                              pack-id (str "system-" group-id)]
                          (assoc acc pack-id
                                 {:name            (:name meta)
                                  :hero-icon       (:icon meta)
                                  :is-unicode?     true
                                  :system-priority group-id
                                  :images          (into {}
                                                         (for [e emojis
                                                               :let [char (or (:emoji e) (:unicode e))
                                                                     slug (or (first (:shortcodes e))
                                                                              (:annotation e)
                                                                              (:name e)
                                                                              (:hexcode e))
                                                                     sc   (clean-shortcode slug)
                                                                     search-string (str/lower-case
                                                                                     (str (:name e) " " (:annotation e) " " (str/join " " (:tags e))))]
                                                               :when (and char (seq sc))]
                                                           [sc {:id          (str "unicode:" sc)
                                                                :shortcode   sc
                                                                :unicode     (str char)
                                                                :is-unicode? true
                                                                :search-text search-string}]))}))))
                    {}))))

(defn ^:ui emoji-sticker-board [{:keys [on-close on-insert-emoji on-insert-native on-send-sticker inline?]}]
  (re-frame/dispatch [:sdk/fetch-all-emotes])
  (let [selected-pack-id (r/atom nil)
        sticker-mode?    (r/atom true)
        search-query     (r/atom "")
        tr-sub           (re-frame/subscribe [:i18n/tr])]
    (fn [{:keys [on-close on-insert-emoji on-insert-native on-send-sticker inline?]}]
      (let [tr             @tr-sub
            custom-packs   @(re-frame/subscribe [:emoji/active-set])
            is-mobile?     @(re-frame/subscribe [:ui/mobile?])
            packs          (merge categorized-system-packs custom-packs)
            sorted-ids     (sort-by (fn [id]
                                      (let [p (get packs id)]
                                        [(if (:is-unicode? p) 1 0)
                                         (if (:is-sticker-pack? p) 0 1)
                                         (get p :system-priority 999)
                                         (or (:name p) (str id))]))
                                    (keys packs))
            active-id      (or @selected-pack-id (first sorted-ids))
            active-pack    (get packs active-id)
            q              (str/lower-case @search-query)
            is-searching?  (not-empty q)
            search-results (when is-searching?
                             (->> packs
                                  vals
                                  (mapcat (fn [pack]
                                            (map (fn [[raw-shortcode item]]
                                                   (let [shortcode (clean-shortcode (or (:shortcode item) raw-shortcode))
                                                         uri       (or (:uri item) (:url item))]
                                                     [shortcode (assoc item
                                                                       :shortcode shortcode
                                                                       :uri uri
                                                                       :url uri
                                                                       :is-unicode? (:is-unicode? pack)
                                                                       :is-sticker-pack? (:is-sticker-pack? pack))]))
                                                 (:images pack))))
                                  (filter (fn [[shortcode item]]
                                            (or (str/includes? (str/lower-case shortcode) q)
                                                (and (:search-text item) (str/includes? (:search-text item) q)))))
                                  (take 150)))
            render-cell    (fn [cell-key shortcode item unicode-pack? _sticker-pack?]
                             (let [uri     (or (:uri item) (:url item))
                                   payload {:id        (:id item)
                                            :shortcode shortcode
                                            :uri       uri
                                            :url       uri
                                            :info      (:info item)}]
                               ^{:key cell-key}
                               [:div.emoji-cell
                                {:on-click (fn []
                                             (cond
                                               unicode-pack?
                                               (on-insert-native (:unicode item))

                                               @sticker-mode?
                                               (on-send-sticker payload)

                                               :else
                                               (on-insert-emoji payload)))}
                                (if unicode-pack?
                                  [:span.native-emoji {:title shortcode} (:unicode item)]
                                  [media {:uri   uri
                                          :class "emoji-img"
                                          :title shortcode
                                          :alt   shortcode}])]))
            content        [:div.emoji-popover
                            (if (empty? packs)
                              [:div.emoji-loading (tr [:composer.emotes/no-emotes])]
                              [:div.emoji-container
                               (when-not is-searching?
                                 ^{:key "sidebar"}
                                 [:div.emoji-sidebar
                                  (for [pack-id sorted-ids]
                                    (let [pack-data (get packs pack-id)
                                          pname     (or (:name pack-data) (str pack-id))
                                          avatar    (:avatar pack-data)]
                                      ^{:key pack-id}
                                      [:div.sidebar-pack-item
                                       {:class    (when (= active-id pack-id) "active")
                                        :on-click #(reset! selected-pack-id pack-id)
                                        :title    pname}
                                       (cond
                                         (:is-unicode? pack-data)
                                         [:span.pack-text-icon (:hero-icon pack-data)]

                                         avatar
                                         [media {:uri   avatar
                                                 :class "pack-icon"
                                                 :alt   pname}]

                                         :else
                                         [:span.pack-text-icon (subs pname 0 (min 2 (count pname)))])]))])
                               ^{:key "grid-area"}
                               [:div.emoji-grid-area
                                [:div.emoji-search-bar {:style {:padding "8px 12px" :border-bottom "1px solid var(--border-color)"}}
                                 [:input.form-input
                                  {:type        "text"
                                   :placeholder (tr [:composer.emotes/search-placeholder])
                                   :auto-focus  (not is-mobile?)
                                   :value       @search-query
                                   :on-change   #(reset! search-query (.. % -target -value))}]]
                                [:div.emoji-pack-header
                                 [:div.emoji-pack-title
                                  (if is-searching?
                                    (tr [:composer.emotes/search-results] "Search Results")
                                    (or (:name active-pack) (tr [:composer.emotes/default-title])))]
                                 [:div.emoji-mode-switch
                                  [:button {:class    (when-not @sticker-mode? "active")
                                            :on-click #(reset! sticker-mode? false)}
                                   (tr [:composer.emotes/mode-inline])]
                                  [:button {:class    (when @sticker-mode? "active")
                                            :on-click #(reset! sticker-mode? true)}
                                   (tr [:composer.emotes/mode-sticker])]]]
                                (if is-searching?
                                  [:div.emoji-grid
                                   (if (empty? search-results)
                                     [:div.emoji-empty-state (tr [:composer.emotes/no-results])]
                                     (for [[shortcode item] search-results]
                                       (render-cell (str "search-" (:id item) "-" shortcode)
                                                    shortcode
                                                    item
                                                    (:is-unicode? item)
                                                    (:is-sticker-pack? item))))]
                                  [:div.emoji-grid
                                   (for [[raw-shortcode item] (get active-pack :images {})
                                         :let [shortcode (clean-shortcode (or (:shortcode item) raw-shortcode))]]
                                     (render-cell shortcode
                                                  shortcode
                                                  item
                                                  (:is-unicode? active-pack)
                                                  (:is-sticker-pack? active-pack)))])]])]]
        (if inline?
          [click-away-wrapper
           {:on-close on-close :z-index 19}
           content]
          content)))))