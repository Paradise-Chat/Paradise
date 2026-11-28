(ns paradise.ui.app
  (:require
   [cljs-workers.mesh :as mesh]
   [cljs.core.async :refer [<! go]]
   [re-frame.core :as re-frame]
   [taoensso.timbre :as log]
   [promesa.core :as p]
   [clojure.string :as str]
   [re-frame.db :as db]
   [paradise.shared.client.state :as state :refer [!config]]
   [paradise.shared.utils.audio :as audio]
   [paradise.shared.utils.helpers :refer [decode-edn-field deep-merge]]
   [paradise.shared.utils.platform :as platform]
   [paradise.shared.registry :as registry]
   [reagent.core :as r]
   [reagent.dom.client :as rdom]
   [paradise.ui.navigation.spaces.bar :refer [spaces-sidebar]]
   [paradise.shared.client.key-handler :refer [global-key-listener]]
   [paradise.ui.overlays.notifications :as notifications]
   [paradise.ui.overlays.messages]
   [paradise.ui.overlays.settings.base]
   [paradise.ui.overlays.settings.plugins :refer [security-warning-modal]]
   [paradise.ui.overlays.settings.profile :refer [sidebar-profile-mini]]
   [paradise.ui.overlays.invites]
   [paradise.ui.overlays.creation]
   [paradise.ui.overlays.dm]
   [paradise.ui.overlays.emotes-manager]
   [paradise.ui.overlays.permissions-roles]
   [paradise.ui.overlays.polls]
   [paradise.ui.overlays.quick-switcher]
   [paradise.ui.overlays.lightbox]
   [paradise.ui.overlays.rooms]
   [paradise.ui.overlays.room-settings]
   [paradise.ui.overlays.profiles]
   [paradise.ui.overlays.reactions]
   [paradise.ui.overlays.tray :as tray]
   [paradise.ui.overlays.titlebar :refer [desktop-titlebar]]
   [paradise.shared.plugin-storage]
   [paradise.ui.auth.events :refer [login-screen]]
   [paradise.ui.container.call.call-container :refer [persistent-call-container]]
   [paradise.ui.container.call.native :as call]
   [paradise.shared.client.config :refer [load-config check-remote-version load-i18n eve-enabled? bundled-version
                                          claim-update-reload! clear-update-reload-guard!]]
   [paradise.shared.client.session-store :as store]
   [taoensso.tempura :as tempura :refer [tr]]
   [paradise.ui.global :as global :refer [modal-root popover-root context-menu-root make-swipe-handlers]]
   ["@capacitor/app" :refer [App]]
   ["@capacitor/core" :refer [registerPlugin]]
   ["@capacitor/keyboard" :refer [Keyboard KeyboardResize KeyboardStyle]]
   [paradise.ui.input.base :as input-base]
   [paradise.shared.utils.macros :refer [config i18n-data defui]]
   [paradise.shared.utils.svg :as icons]
   [paradise.ui.navigation.rooms.room-list :refer [room-list]]
   [paradise.ui.container.base :refer [container]]))

#_(def default-db
  {:spaces {"!space1:example.com" {:id "!space1:example.com" :name "Main Space" :parent-id nil}
            "!space2:example.com" {:id "!space2:example.com" :name "Sub Space" :parent-id "!space1:example.com"}
            "!space3:example.com" {:id "!space3:example.com" :name "Other Space" :parent-id nil}}
   :rooms {"!room1:example.com" {:id "!room1:example.com" :name "general" :parent-id "!space1:example.com"}
           "!room2:example.com" {:id "!room2:example.com" :name "random" :parent-id "!space1:example.com"}
           "!room3:example.com" {:id "!room3:example.com" :name "cljs-dev" :parent-id "!space2:example.com"}}
   :active-space-id "!space1:example.com"
   :active-room-id "!room1:example.com"})


(def default-db
  {:spaces {}
   :spaces-list []
   :space-children {}
   :rooms {}
   :rooms-unfiltered-cache {}
   :active-space-id nil
   :active-room-id nil
   :active-filter-id "people"
   :session/gen 0
   :session/sync-ready? false
   :session/pending-restore nil
   :session/pending-room-select nil
   :auth-status :checking
   :login-error nil
   :client nil
   :config config
   :locale :en
   :dictionary i18n-data
   :update-available? false})

(re-frame/reg-event-db
 :initialize-db
 (fn [_ _]
   default-db
   ))

(re-frame/reg-fx
 :ui/blur-active
 (fn [_]
   (when-let [active-el (.-activeElement js/document)]
     (.blur active-el))))

(re-frame/reg-event-fx
 :ui/set-sidebar
 (fn [{:keys [db]} [_ open?]]
   (if open?
     (let [dispatches (cond-> []
                        (or @global/!active-modal (:active-modal db))               (conj [:ui/close-modal])
                        (or @global/!active-popover (:active-popover db))           (conj [:ui/close-popover])
                        (or @global/!active-context-menu (:active-context-menu db)) (conj [:ui/close-context-menu]))]
       (merge
        {:db (assoc-in db [:ui :sidebar-open?] true)
        ;; :native/hide-keyboard nil
         :ui/blur-active nil}
        (when (seq dispatches)
          {:dispatch-n dispatches})))
     {:db (assoc-in db [:ui :sidebar-open?] false)})))


(re-frame/reg-event-db
 :ui/toggle-sidebar
 (fn [db _]
   (update-in db [:ui :sidebar-open?] not)))

(re-frame/reg-sub
 :ui/sidebar-open?
 (fn [db _]
   (get-in db [:ui :sidebar-open?] false)))

(re-frame/reg-event-db
 :ui/window-resized
 (fn [db [_ width]]
   (assoc-in db [:ui :mobile?] (platform/mobile-viewport? width))))

(re-frame/reg-sub
 :ui/mobile?
 (fn [db _]
   (get-in db [:ui :mobile?] false)))


(re-frame/reg-event-db
 :app/config-loaded
 (fn [db [_ config]]
   (assoc db :config config
             :update-available? false)))

(re-frame/reg-event-fx
 :app/poll-version
 (fn [{:keys [db]} [_ manual?]]
   (let [current-v (or bundled-version
                       (get-in db [:config :bundled-version])
                       (get-in db [:config :version]))]
     (when current-v
       (-> (check-remote-version)
           (p/then (fn [remote-v]
                     (cond
                       (and remote-v (not= (str remote-v) (str current-v)))
                       (do
                         (load-i18n {:version remote-v})
                         (re-frame/dispatch [:app/update-detected]))
                       manual?
                       (js/alert (str "You are up to date! (v" current-v ")")))))))
     {})))

(re-frame/reg-event-db
 :app/update-detected
 (fn [db _]
   (assoc db :update-available? true)))

(re-frame/reg-sub
 :app/update-available?
 (fn [db _]
   (:update-available? db false)))

(re-frame/reg-sub
 :app/version
 (fn [db _]
   (let [cfg (:config db)]
     (or bundled-version
         (when (map? cfg) (:version cfg))
         (:version config)
         "Unknown"))))

(re-frame/reg-sub
 :i18n/locale
 (fn [db _] (:locale db)))

(re-frame/reg-sub
 :i18n/dictionary
 (fn [db _] (:dictionary db)))

(re-frame/reg-sub
 :i18n/engine-dictionary
 (fn [db _] (:i18n/engine-dictionary db)))

(re-frame/reg-sub
 :i18n/plugin-dictionaries
 (fn [db _] (:i18n/plugin-dictionaries db {})))

(re-frame/reg-sub
 :i18n/tr
 :<- [:i18n/locale]
 :<- [:i18n/dictionary]
 :<- [:i18n/engine-dictionary]
 :<- [:i18n/plugin-dictionaries]
 (fn [[locale dictionary engine-dictionary plugin-dictionaries] _]
   (let [base-dict      (if (and (map? dictionary) (seq dictionary))
                          dictionary
                          i18n-data)
         plugin-dicts   (when (and (map? plugin-dictionaries) (seq plugin-dictionaries))
                          (map second (sort-by (comp str key) plugin-dictionaries)))
         effective-dict (if (or (and (map? engine-dictionary) (seq engine-dictionary))
                                (seq plugin-dicts))
                          (apply deep-merge base-dict engine-dictionary plugin-dicts)
                          base-dict)
         safe-locale    (or (some-> locale keyword) :en)]
     (if (empty? effective-dict)
       (fn [k & _] (str "[" (name (last k)) "]"))
       (partial tempura/tr {:dict effective-dict} [safe-locale :en])))))

(re-frame/reg-event-db
 :i18n/set-dictionary
 (fn [db [_ dict]]
   (assoc db :dictionary dict)))

(re-frame/reg-event-db
 :i18n/set-engine-dictionary
 (fn [db [_ engine-id dict]]
   (let [eng-kw  (some-> engine-id keyword)
         want-kw (:i18n/loading-engine-id db)]
     (if (or (nil? want-kw) (nil? eng-kw) (= want-kw eng-kw))
       (assoc db
              :i18n/engine-id eng-kw
              :i18n/engine-dictionary (when (map? dict) dict))
       db))))

(re-frame/reg-event-db
 :i18n/clear-engine-dictionary
 (fn [db _]
   (dissoc db :i18n/engine-id :i18n/engine-dictionary :i18n/loading-engine-id)))

(re-frame/reg-event-db
 :i18n/set-plugin-dictionary
 (fn [db [_ plugin-id dict]]
   (if-let [pid (some-> plugin-id keyword)]
     (if (and (map? dict) (seq dict))
       (assoc-in db [:i18n/plugin-dictionaries pid] dict)
       (update db :i18n/plugin-dictionaries dissoc pid))
     db)))

(re-frame/reg-event-db
 :i18n/remove-plugin-dictionary
 (fn [db [_ plugin-id]]
   (if-let [pid (some-> plugin-id keyword)]
     (update db :i18n/plugin-dictionaries dissoc pid)
     db)))

(defn- fetch-i18n-edn
  ([url]
   (fetch-i18n-edn url nil))
  ([url version-tag]
   (go
     (when (and (string? url) (not (str/blank? url)))
       (try
         (let [res (<! (registry/fetch-edn url
                                           {:version           version-tag
                                            :ttl-ms            (* 30 60 1000)
                                            :local-revalidate? true}))]
           (when (map? res) res))
         (catch :default _ nil))))))

(re-frame/reg-event-fx
 :i18n/load-engine-extensions
 (fn [{:keys [db]} [_ engine-id]]
   (if-let [eng-kw (some-> engine-id keyword)]
     (let [manifest          (or (registry/get-engine-manifest eng-kw)
                                 (get-in db [:available-engines eng-kw]))
           manifest-i18n-url (or (registry/get-engine-i18n-url eng-kw)
                                 (:i18n-url manifest)
                                 (:i18n manifest))
           ver-tag           (registry/manifest-version-tag manifest)]
       (go
         (let [bind-dict (try
                           (let [res  (<! (mesh/do-with-thread!
                                           :engine-pool
                                           {:handler   (keyword (name eng-kw) "get-i18n")
                                            :arguments {}}))
                                 dict (when (contains? #{"success" :success} (:status res))
                                        (decode-edn-field res :dictionary-edn :dictionary))]
                             (when (map? dict) dict))
                           (catch :default _ nil))
               http-dict (<! (fetch-i18n-edn manifest-i18n-url ver-tag))
               merged    (deep-merge bind-dict http-dict)]
           (re-frame/dispatch [:i18n/set-engine-dictionary eng-kw (or merged {})])))
       {:db (assoc db :i18n/loading-engine-id eng-kw)})
     {:dispatch [:i18n/clear-engine-dictionary]})))

(re-frame/reg-event-fx
 :i18n/load-plugin-extensions
 (fn [_ [_ plugin-id source-url plugin-map]]
   (when-let [pid (some-> plugin-id keyword)]
     (let [inline-dict (when (map? (:i18n plugin-map))
                         (:i18n plugin-map))
           i18n-url    (or (registry/resolve-manifest-i18n-url plugin-map source-url)
                           (registry/get-plugin-i18n-url pid))
           ver-tag     (registry/manifest-version-tag plugin-map)]
       (cond
         (and inline-dict (not i18n-url))
         {:dispatch [:i18n/set-plugin-dictionary pid inline-dict]}

         i18n-url
         (do
           (go
             (let [http-dict (<! (fetch-i18n-edn i18n-url ver-tag))
                   merged    (deep-merge inline-dict http-dict)]
               (when (seq merged)
                 (re-frame/dispatch [:i18n/set-plugin-dictionary pid merged]))))
           {})

         :else
         {})))))

(re-frame/reg-event-db
 :i18n/set-locale
 (fn [db [_ locale]]
   (assoc db :locale locale)))

(re-frame/reg-event-fx
 :ui/hydrate-theme
 (fn [{:keys [db]} [_ theme-val]]
   {:dispatch [:ui/switch-theme theme-val]}))

(re-frame/reg-sub
 :ui/current-theme
 (fn [db _]
   (:ui/current-theme db "default")))

(re-frame/reg-event-fx
 :ui/reset-theme
 (fn [{:keys [db]} _]
   {:db (assoc db :ui/current-theme "default")
    :settings/save ["theme" "default"]
    :ui/hotswap-css "css/app.css"}))


(defonce ^:private paradise-ui-plugin
  (when (and (exists? js/window) (platform/native-platform?))
    (registerPlugin "ParadiseUI")))

(defn- parse-computed-rgb [css-str]
  (when (and (string? css-str) (not (str/blank? css-str)))
    (when-let [[_ r g b a]
               (re-find
                #"rgba?\(\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)\s*,\s*(\d+(?:\.\d+)?)(?:\s*[,/]\s*(\d+(?:\.\d+)?))?\s*\)"
                (str/lower-case css-str))]
      (let [alpha (if a (js/parseFloat a) 1.0)]
        (when (> alpha 0.02)
          [(-> (js/parseFloat r) js/Math.round (max 0) (min 255))
           (-> (js/parseFloat g) js/Math.round (max 0) (min 255))
           (-> (js/parseFloat b) js/Math.round (max 0) (min 255))])))))

(defn- rgb->hex [[r g b]]
  (let [hex2 (fn [n]
               (let [s (.toString n 16)]
                 (if (< (.-length s) 2) (str "0" s) s)))]
    (str "#" (hex2 r) (hex2 g) (hex2 b))))

(defn- rgb-dark? [[r g b]]
  (< (/ (+ (* 0.2126 r) (* 0.7152 g) (* 0.0722 b)) 255.0) 0.5))

(defn- resolve-element-rgb [selectors fallback]
  (if-not (and (exists? js/document) (exists? js/window))
    fallback
    (or (some (fn [sel]
                (when-let [el (.querySelector js/document sel)]
                  (parse-computed-rgb
                   (.-backgroundColor (.getComputedStyle js/window el)))))
              selectors)
        fallback)))

(defn sync-native-theme-chrome! []
  (when (exists? js/document)
    (let [bg-rgb     (resolve-element-rgb [".app-root" "body" "html"] [49 51 56])
          header-rgb (resolve-element-rgb [".room-header" ".sidebar-header"] bg-rgb)
          bg-hex     (rgb->hex bg-rgb)
          status-hex (rgb->hex header-rgb)
          dark?      (rgb-dark? bg-rgb)
          scheme     (if dark? "dark" "light")]
      (when-let [doc-el (.-documentElement js/document)]
        (set! (.. doc-el -style -colorScheme) scheme))
      (when-let [meta-el (.getElementById js/document "paradise-theme-color")]
        (.setAttribute meta-el "content" bg-hex))
      (when (platform/native-platform?)
        (when (exists? Keyboard)
          (-> (.setStyle Keyboard
                         #js {:style (if dark?
                                       (.-Dark KeyboardStyle)
                                       (.-Light KeyboardStyle))})
              (p/catch (fn [_] nil))))
        (when paradise-ui-plugin
          (when-let [sync-fn (aget paradise-ui-plugin "syncTheme")]
            (-> (.call sync-fn
                       paradise-ui-plugin
                       #js {:color       bg-hex
                            :statusColor status-hex
                            :dark        dark?})
                (p/catch (fn [_] nil)))))))))

(re-frame/reg-fx
 :ui/hotswap-css
 (fn [new-href]
   (when (exists? js/document)
     (let [after-swap! (fn []
                         (if (exists? js/requestAnimationFrame)
                           (js/requestAnimationFrame #(sync-native-theme-chrome!))
                           (sync-native-theme-chrome!)))
           existing    (.getElementById js/document "app-theme")
           default?    (= new-href "css/app.css")]
       (cond
         (and existing default? (= (.getAttribute existing "data-dynamic-theme") "true"))
         (do
           (.remove existing)
           (after-swap!)
           (js/setTimeout after-swap! 120))

         existing
         (do
           (set! (.-onload existing) after-swap!)
           (set! (.-href existing) new-href)
           (after-swap!)
           (js/setTimeout after-swap! 120)
           (log/info "Swapped CSS to:" new-href))

         default?
         (do
           (after-swap!)
           (js/setTimeout after-swap! 120))

         :else
         (when-let [head (.-head js/document)]
           (let [dyn-link (.createElement js/document "link")]
             (set! (.-id dyn-link) "app-theme")
             (set! (.-rel dyn-link) "stylesheet")
             (.setAttribute dyn-link "data-dynamic-theme" "true")
             (set! (.-onload dyn-link) after-swap!)
             (set! (.-href dyn-link) new-href)
             (.appendChild head dyn-link)
             (after-swap!)
             (js/setTimeout after-swap! 120)
             (log/info "Injected custom theme CSS:" new-href))))))))

(re-frame/reg-event-fx
 :ui/switch-theme
 (fn [{:keys [db]} [_ theme-val]]
   (let [safe-theme (or theme-val "default")
         theme-str  (if (keyword? safe-theme) (name safe-theme) safe-theme)
         is-url?    (str/starts-with? theme-str "http")
         href       (if is-url?
                      theme-str
                      (str "css/" (case (keyword theme-str)
                                    :discord "cordlike.css"
                                    :matrix  "matrix.css"
                                    :retro   "retro.css"
                                    "app.css")))]
     {:db             (assoc db :ui/current-theme theme-str)
      :dispatch       [:settings/save "theme" theme-str]
      :ui/hotswap-css href})))


(defn ^:ui booting-screen []
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:div.boot-container
     [:div.boot-content
      [:div.boot-logo-wrapper
       [icons/transition {:size "100px"}]
       ]
      [:div.boot-loading-text
       [:span (tr [:boot/loading-text])]]]]))

(re-frame/reg-event-fx
 :native/hardware-back
 (fn [{:keys [db]} _]
   (cond
     (or @global/!active-context-menu (get-in db [:active-context-menu]))
     {:dispatch [:ui/close-context-menu]}

     (or @global/!active-popover (get-in db [:active-popover]))
     {:dispatch [:ui/close-popover]}

     (or @global/!active-modal (get-in db [:active-modal]))
     {:dispatch [:ui/close-modal]}

     (get-in db [:ui :sidebar-open?])
     {:dispatch [:ui/set-sidebar false]}

     ;; :else
     ;; {:dispatch [:nav/go-back]}
     )))

(re-frame/reg-event-fx
 :app/visibility-changed
 (fn [{:keys [db]} [_ visible?]]
   (let [vis? (boolean visible?)]
     {:db       (assoc-in db [:ui :app-visible?] vis?)
      :dispatch [:call/visibility-changed vis?]})))

(defonce ^:private !capacitor-listeners-ready? (atom false))

(defn init-capacitor-listeners! []
  (when (compare-and-set! !capacitor-listeners-ready? false true)
    (when (and (platform/ios-native?) (exists? Keyboard))
      (try
        (when (fn? (.-setResizeMode Keyboard))
          (-> (.setResizeMode Keyboard #js {:mode "none"})
              (p/catch (fn [_] nil))))
        (when (fn? (.-setScroll Keyboard))
          (-> (.setScroll Keyboard #js {:isDisabled true})
              (p/catch (fn [_] nil))))
        (catch :default _ nil)))
    (input-base/ensure-mobile-keyboard-tracker!)
    (.addListener App "backButton"
                  (fn [_]
                    (re-frame/dispatch [:native/hardware-back])))
    (.addListener App "appStateChange"
                  (fn [^js state]
                    (let [active? (boolean (.-isActive state))]
                      (when active?
                        (sync-native-theme-chrome!))
                      (re-frame/dispatch [:app/visibility-changed active?]))))
    (when (exists? js/document)
      (.addEventListener js/document "visibilitychange"
                         (fn []
                           (re-frame/dispatch
                            [:app/visibility-changed (not (.-hidden js/document))]))))))

(defn ^:ui main-layout []
  (let [auth-status    @(re-frame/subscribe [:auth/status])
        sidebar-open?  @(re-frame/subscribe [:ui/sidebar-open?])
        update-ready?  @(re-frame/subscribe [:app/update-available?])
        active-call-id @(re-frame/subscribe [:call/active-room])
        plat           (platform/current-platform)
        cushion        (if (= plat "ios")
                         "8px"
                         "var(--safe-area-inset-bottom, env(safe-area-inset-bottom, 0px))")
        swipe-props    (make-swipe-handlers
                        :sidebar
                        {:sidebar-open? sidebar-open?
                         :on-toggle     #(re-frame/dispatch [:ui/set-sidebar %])})]
    [:<>
     [desktop-titlebar]
     (case auth-status
       :checking [booting-screen]
       (:logged-out :authenticating) [login-screen]
       :logged-in
       [:<>
        (when update-ready?
          [:div.global-update-banner
           [:span "A new version of the app is available!"]
           [:button {:on-click #(re-frame/dispatch [:app/apply-update])}
            "Refresh"]])

        [persistent-call-container]
        (into [:div.app-root
               (merge {:class         [(when sidebar-open? "sidebars-open")
                                       (when active-call-id "has-active-call")]
                       :data-platform plat
                       :style         {:touch-action   "pan-y"
                                       :padding-bottom cushion}}
                      swipe-props)]
              [[spaces-sidebar]
               [room-list]
               [sidebar-profile-mini]
               (when sidebar-open?
                 [:div.mobile-overlay {:on-click #(re-frame/dispatch [:ui/set-sidebar false])}])
               [container]])
        [modal-root]
        [popover-root]
        [context-menu-root]
        [notifications/notification-toast-root]
        [global-key-listener]]
       [:div "Unknown State"])
     [security-warning-modal]]))

(defn init-window-size-listener! []
  (re-frame/dispatch [:ui/window-resized (.-innerWidth js/window)])

  (let [resize-handler (goog.functions/debounce
                        #(re-frame/dispatch [:ui/window-resized (.-innerWidth js/window)])
                        150)]
    (.addEventListener js/window "resize" resize-handler)))


(defonce root (atom nil))

(defn mount-root []
  (when (exists? js/document)
    (when-let [doc-el (.-documentElement js/document)]
      (.setAttribute doc-el "data-platform" (platform/current-platform))))
  (re-frame/dispatch [:ui/switch-theme])
  (re-frame/clear-subscription-cache!)
  (let [container (.getElementById js/document "root")]
    (when-not @root
      (reset! root (rdom/create-root container)))
    (init-window-size-listener!)
    (init-capacitor-listeners!)
    (.render @root (r/as-element [main-layout]))))


(defn ^:export init []
  (re-frame/dispatch-sync [:initialize-db])
  (audio/init-audio-unlock!)
  (call/init-native-listeners!)
  (tray/init-tray!)
  (re-frame/dispatch [:app/load-settings-by-stage :boot])
  (re-frame/dispatch [:app/start-boot-sequence])
  (mount-root))

(defn- maybe-refresh-stale-bundle! [cfg]
  (let [native? (platform/native-platform?)]
    (if (or native? (not (:config/bundle-stale? cfg)))
      (do
        (clear-update-reload-guard!)
        false)
      (let [remote-v (some-> (:remote-version cfg) str not-empty)]
        (if (claim-update-reload! remote-v)
          (do
            (log/warn "Stale web bundle detected on boot:" (:bundled-version cfg)
                      "vs remote" remote-v "— purging runtime caches and reloading.")
            (re-frame/dispatch [:app/clear-cache-for-update])
            true)
          (do
            (log/warn "Stale web bundle still detected after reload; showing update banner.")
            (re-frame/dispatch [:app/update-detected])
            false))))))

(re-frame/reg-event-fx
 :app/start-boot-sequence
 (fn [_ _]
   (-> (load-config)
       (p/then (fn [cfg]
                 (reset! !config cfg)
                 (re-frame/dispatch-sync [:app/config-loaded cfg])
                 (log/info "Config loaded:" cfg)
                 (if (maybe-refresh-stale-bundle! cfg)
                   (p/resolved :reloading)
                   (load-i18n cfg))))
       (p/then (fn [res]
                 (when-not (= res :reloading)
                   (let [params  (js/URLSearchParams. js/window.location.search)
                         room-id (.get params "room")]
                     (when room-id
                       (log/info "Deep link detected on boot:" room-id)
                       (re-frame/dispatch [:nav/lock-deep-link room-id])
                       (.replaceState js/window.history #js {} "" (.. js/window -location -pathname)))

                     (p/let [saved-user (store/get-setting "last_active_user")]
                       (re-frame/dispatch [:app/cold-boot saved-user]))

                     (notifications/init!)
                     (js/setInterval #(re-frame/dispatch [:app/poll-version false]) (* 1000 60 15))))))
       (p/catch #(log/error "App initialization failed:" %)))
   {}))


(re-frame/reg-event-db
 :nav/lock-deep-link
 (fn [db [_ room-id]]
   (assoc db :deep-link-room room-id)))

(re-frame/reg-event-fx
 :nav/route-from-push
 (fn [{:keys [db]} [_ room-id]]
   (if (= (:auth-status db) :logged-in)
     {:dispatch [:rooms/select room-id]}
     {:dispatch [:nav/lock-deep-link room-id]})))

(re-frame/reg-event-fx
 :app/restore-user-session
 (fn [{:keys [db]} [_ user-id engine-id expected-gen]]
   (let [gen            (or expected-gen (:session/gen db))
         resolved-eng   (or (some-> engine-id keyword) (:active-engine db) :matrix)
         deep-room      (:deep-link-room db)
         pending-answer (get-in db [:call :pending-native-answer])]
     (if deep-room
       {:db (assoc db
                   :deep-link-room nil
                   :session/pending-restore {:gen        gen
                                             :space-id   nil
                                             :room-id    deep-room
                                             :filter-id  "people"
                                             :deep-link? true
                                             :applied?   false})
        :fx (cond-> [[:dispatch [:auth/set-status :logged-in]]
                     [:dispatch [:sdk/ignite-session gen]]]
              pending-answer
              (conj [:dispatch [:call/flush-pending-native-answer]]))}
       (do
         (-> (store/load-nav-state resolved-eng user-id)
             (p/then (fn [nav]
                       (re-frame/dispatch
                        [:app/apply-loaded-nav-state
                         {:gen            gen
                          :user-id        user-id
                          :nav            nav
                          :pending-answer pending-answer}])))
             (p/catch (fn [_]
                        (re-frame/dispatch
                         [:app/apply-loaded-nav-state
                          {:gen            gen
                           :user-id        user-id
                           :nav            {:space-id nil :room-id nil :filter-id "people"}
                           :pending-answer pending-answer}]))))
         {})))))

(re-frame/reg-event-fx
 :app/apply-loaded-nav-state
 [(state/guard-session-gen #(-> % second :gen))]
 (fn [{:keys [db]} [_ {:keys [gen user-id nav pending-answer]}]]
   (if (not= (str (:active-user-id db)) (str user-id))
     (do
       (log/info "Dropping stale restored nav state for" user-id "gen" gen)
       {})
     (let [space-id  (some-> (:space-id nav) str not-empty)
           room-id   (some-> (:room-id nav) str not-empty)
           filter-id (or (some-> (:filter-id nav) str not-empty) "people")
           pending   {:gen        gen
                      :space-id   space-id
                      :room-id    room-id
                      :filter-id  filter-id
                      :deep-link? false
                      :applied?   false}]
       (log/info "Queued session restore for" user-id
                 "-> Space:" space-id "| Room:" room-id "| Filter:" filter-id)
       {:db (assoc db
                   :active-space-id space-id
                   :active-filter-id filter-id
                   :session/pending-restore pending)
        :fx (cond-> [[:dispatch [:auth/set-status :logged-in]]
                     [:dispatch [:sdk/ignite-session gen]]]
              pending-answer
              (conj [:dispatch [:call/flush-pending-native-answer]]))}))))

(re-frame/reg-event-fx
 :session/on-sync-ready
 [(state/guard-session-gen second)]
 (fn [{:keys [db]} [_ gen]]
   (let [pending (:session/pending-restore db)]
     (if (and pending (= (:gen pending) gen) (not (:applied? pending)))
       (let [{:keys [space-id room-id filter-id]} pending
             f-id     (or (some-> filter-id str not-empty) "people")
             space-fx (if space-id
                        [[:dispatch [:sdk/paginate-space space-id]]
                         [:dispatch [:sdk/fetch-room-emotes :space space-id]]
                         [:dispatch [:sdk/fetch-space-hierarchy space-id]]]
                        [[:dispatch [:room-list/apply-filter f-id {:force? true :persist? false}]]])
             room-fx  (when room-id
                        [[:dispatch [:rooms/select room-id {:from-restore? true :gen gen}]]])]
         {:db (assoc db
                     :session/sync-ready? true
                     :active-space-id space-id
                     :active-filter-id f-id
                     :session/pending-restore (assoc pending :applied? true))
          :fx (into space-fx room-fx)})
       (let [cur-space  (:active-space-id db)
             cur-filter (or (:active-filter-id db) "people")
             sync-fx    (if cur-space
                          [[:dispatch [:sdk/paginate-space cur-space]]
                           [:dispatch [:sdk/fetch-room-emotes :space cur-space]]
                           [:dispatch [:sdk/fetch-space-hierarchy cur-space]]]
                          [[:dispatch [:room-list/apply-filter cur-filter {:force? true :persist? false}]]])]
         {:db (assoc db :session/sync-ready? true)
          :fx sync-fx})))))

(re-frame/reg-event-fx
 :session/reconcile-restored-room
 (fn [{:keys [db]} _]
   (let [pending (:session/pending-restore db)]
     (if-not (and pending (:applied? pending) (= (:gen pending) (:session/gen db)))
       {}
       (let [rid      (:room-id pending)
             room-obj (when rid
                        (or (get-in db [:rooms-unfiltered-cache rid])
                            (some #(when (= (or (:id %) (:roomId %)) rid) %) (:rooms db))))]
         (if-not room-obj
           {}
           (let [is-dm?    (boolean (or (:isDirect room-obj) (:is-direct? room-obj)))
                 parent-id (some-> (or (:first-parent-id room-obj) (:parent-id room-obj))
                                   str not-empty)
                 spaces    (:spaces db {})
                 cur-space (:active-space-id db)]
             (cond
               (and is-dm? (some? cur-space))
               (do
                 (store/save-nav-state! (:active-engine db) (:active-user-id db)
                                        {:space-id nil :room-id rid :filter-id "people"})
                 {:db (assoc db
                             :active-space-id nil
                             :active-filter-id "people"
                             :session/pending-restore nil)
                  :fx [[:dispatch [:room-list/apply-filter "people" {:force? true :persist? false}]]]})

               (and (not is-dm?)
                    parent-id
                    (contains? spaces parent-id)
                    (or (:deep-link? pending) (nil? cur-space)))
               (do
                 (store/save-nav-state! (:active-engine db) (:active-user-id db)
                                        {:space-id  parent-id
                                         :room-id   rid
                                         :filter-id (:active-filter-id db "people")})
                 {:db (assoc db
                             :active-space-id parent-id
                             :session/pending-restore nil)
                  :fx [[:dispatch [:sdk/paginate-space parent-id]]
                       [:dispatch [:sdk/fetch-room-emotes :space parent-id]]
                       [:dispatch [:sdk/fetch-space-hierarchy parent-id]]]})

               :else
               {:db (assoc db :session/pending-restore nil)}))))))))


(defn ^:after-load re-render []
  (load-i18n @!config {:force? true})
  (mount-root))