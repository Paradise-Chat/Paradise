(ns ^:virtualizer/skip paradise.shared.utils.platform
  (:require [goog.object :as gobj]))

(defn- capacitor-global []
  (when (exists? js/window)
    (gobj/get js/window "Capacitor")))

(defn capacitor-platform
  "Returns the active Capacitor platform string (e.g. `\"ios\"`, `\"android\"`, `\"web\"`), or nil."
  []
  (try
    (when-let [^js cap (capacitor-global)]
      (when (fn? (.-getPlatform cap))
        (str (.getPlatform cap))))
    (catch :default _
      nil)))

(defn current-platform
  "Returns the active platform string, defaulting to `\"web\"`."
  []
  (or (capacitor-platform) "web"))

(defn native-platform?
  "Returns true when running inside a native Capacitor runtime (iOS or Android)."
  []
  (try
    (boolean
     (when-let [^js cap (capacitor-global)]
       (when (fn? (.-isNativePlatform cap))
         (.isNativePlatform cap))))
    (catch :default _
      false)))

(defn capacitor-mobile?
  "Returns true when running inside a native iOS or Android Capacitor shell."
  []
  (contains? #{"ios" "android"} (capacitor-platform)))

(def mobile-native? capacitor-mobile?)

(defn ios-native?
  "Returns true only inside a native iOS Capacitor shell."
  []
  (and (native-platform?) (= "ios" (capacitor-platform))))

(defn ios-runtime?
  "Returns true on native iOS Capacitor shells as well as iOS/iPadOS Safari/WebKit."
  []
  (try
    (or (= "ios" (capacitor-platform))
        (when (exists? js/navigator)
          (let [ua  (str (or (.-userAgent js/navigator) ""))
                plt (str (or (.-platform js/navigator) ""))
                mtp (js/Number (or (.-maxTouchPoints js/navigator) 0))]
            (or (boolean (re-find #"(?i)iPhone|iPad|iPod" ua))
                (and (= plt "MacIntel") (> mtp 1))))))
    (catch :default _
      false)))

(defn mobile-runtime?
  "Returns true on native mobile shells (iOS/Android) or mobile browsers."
  []
  (try
    (or (capacitor-mobile?)
        (ios-runtime?)
        (when (exists? js/navigator)
          (boolean (re-find #"(?i)Android" (str (or (.-userAgent js/navigator) ""))))))
    (catch :default _
      false)))

(defn electron-platform?
  "Returns true when running inside the Electron desktop shell."
  []
  (try
    (boolean
     (or (= "electron" (current-platform))
         (and (exists? js/window)
              (or (some? (gobj/get js/window "paradiseWindow"))
                  (some? (gobj/get js/window "paradiseNotifications"))
                  (some? (gobj/get js/window "paradiseTray"))))))
    (catch :default _
      false)))

(defn mac-os?
  "Returns true on macOS (via Electron bridge platform or navigator)."
  []
  (try
    (boolean
     (or (when (exists? js/window)
           (= "darwin" (some-> (gobj/get js/window "paradiseWindow")
                               (gobj/get "platform"))))
         (when (exists? js/navigator)
           (let [plt (str (or (.-platform js/navigator) ""))
                 ua  (str (or (.-userAgent js/navigator) ""))]
             (or (boolean (re-find #"(?i)Mac" plt))
                 (boolean (re-find #"(?i)Macintosh|Mac OS" ua)))))))
    (catch :default _
      false)))

(def mobile-breakpoint-px 768)

(defn mobile-viewport?
  "Returns true when the viewport width is at or below `mobile-breakpoint-px`."
  ([]
   (mobile-viewport? (when (exists? js/window) (.-innerWidth js/window))))
  ([width]
   (boolean (and (number? width) (<= width mobile-breakpoint-px)))))

(defn coarse-pointer?
  "Returns true when the primary pointer reports coarse (touch) precision."
  []
  (try
    (boolean
     (and (exists? js/window)
          (fn? (.-matchMedia js/window))
          (.-matches (.matchMedia js/window "(pointer: coarse)"))))
    (catch :default _
      false)))