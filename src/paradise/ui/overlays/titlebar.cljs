(ns paradise.ui.overlays.titlebar
  (:require
   ["@capacitor/core" :refer [Capacitor]]
   [paradise.shared.utils.svg :as icons]
   [paradise.shared.client.key-handler :refer [keybind-spec]]
   [reagent.core :as r]))

(defn desktop-app? []
  (= (.getPlatform Capacitor) "electron"))

(defn- get-window-bridge ^js []
  (when (desktop-app?)
    (.-paradiseWindow js/globalThis)))


(defn ^:ui desktop-titlebar []
  (when-let [^js win-bridge (get-window-bridge)]
    (r/with-let [!maximized? (r/atom false)
                 _           (-> (.isMaximized win-bridge)
                                 (.then #(reset! !maximized? (boolean %))))
                 unlisten    (.onMaximizedChange win-bridge
                                                 #(reset! !maximized? (boolean %)))]
      (let [mac? (= (.-platform win-bridge) "darwin")]
        [:header.desktop-titlebar
         [:div.desktop-titlebar-brand
          {:style {:padding-left (if mac? "72px" "12px")}}
          [:span.desktop-titlebar-title "Paradise"]]

         (when-not mac?
           [:div.desktop-titlebar-controls
            [:button.desktop-titlebar-btn
             {:title    "Minimize"
              :on-click #(.minimize win-bridge)}
             [icons/minimize {:width "12" :height "12"}]]

            [:button.desktop-titlebar-btn
             {:title    (if @!maximized? "Restore" "Maximize")
              :on-click #(.toggleMaximize win-bridge)}
             (if @!maximized?
               [icons/restore {:width "12" :height "12"}]
               [icons/maximize {:width "12" :height "12"}])]

            [:button.desktop-titlebar-btn.is-close
             {:title    "Close"
              :on-click #(.close win-bridge)}
             [icons/exit {:width "12" :height "12"}]]])])
      (finally
        (when (fn? unlisten)
          (unlisten))))))


(defmethod keybind-spec :ui/toggle-devtools [_]
  {:id              "ui/toggle-devtools"
   :label-key       :settings.keybinds/action-toggle-devtools
   :accelerator     "CommandOrControl+Shift+I"
   :global?         false
   :allow-in-input? true
   :on-trigger      (fn []
                      (when-let [^js win (.-paradiseWindow js/globalThis)]
                        (.toggleDevTools win)))})
