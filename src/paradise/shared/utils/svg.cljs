(ns paradise.shared.utils.svg
  (:require
   )
  )

(defn- build-icon-hiccup [{:keys [animate viewBox size] :as props} shapes]
  (let [final-view-box (or viewBox "0 0 24 24")
        final-size (or size "16px")]
    [:svg (merge {:viewBox final-view-box
                  :width final-size
                  :height final-size
                  :fill "none"
                  :stroke "currentColor"
                  :stroke-width "2"
                  :stroke-linecap "round"
                  :stroke-linejoin "round"
                  :class (when animate (str "animate-" (name animate)))}
                 (dissoc props :animate :viewBox :size))
     shapes]))

(defn composite-icon [{:keys [main top-left top-right bottom-left bottom-right] :as props}]
  (let [badge (fn [x y b]
                (when b
                  (let [shape (if (map? b) (:shape b) b)
                        attrs (if (map? b) (dissoc b :shape) {})]
                    [:svg (merge {:x x :y y :width "10" :height "10" :viewBox "0 0 24 24"} attrs)
                     shape])))]
    (build-icon-hiccup
     (dissoc props :main :top-left :top-right :bottom-left :bottom-right)
     [:g
      [:svg {:x "4" :y "2" :width "16" :height "16" :viewBox "0 0 24 24"}
       main]

      (badge "0" "0" top-left)
      (badge "14" "0" top-right)
      (badge "0" "14" bottom-left)
      (badge "14" "14" bottom-right)])))

(def sun-shapes
  [:path {:d "m 550.92238,108.45841 -92.91577,4.89014 35.31877,270.59588 78.24484,1.63039 58.68324,-272.22627 z m -303.62591,72.21419 -41.53958,27.91458 a 120.31687,120.31687 0 0 0 -43.6485,52.8624 l -18.86965,44.46551 231.16677,140.50667 48.90296,-48.90296 z m 574.567,3.57705 -192.69677,224.68964 48.90296,48.90296 231.47373,-136.92808 -13.32425,-40.59649 A 142.557,142.557 0 0 0 861.58663,223.98262 Z M 524.26975,399.07839 A 143.19254,127.25317 0 0 0 381.07745,526.33161 143.19254,127.25317 0 0 0 524.26975,653.58482 143.19254,127.25317 0 0 0 667.46256,526.33161 143.19254,127.25317 0 0 0 524.26975,399.07839 Z m 450.81176,45.55278 -280.91981,48.90244 0.54312,73.3547 282.00656,29.34136 3.28507,-32.14429 a 366.14382,366.14382 0 0 0 -0.27492,-77.04336 z m -892.500565,1.99523 -3.893303,37.85918 a 428.39608,428.39608 0 0 0 -0.582393,81.56298 l 2.845821,32.17685 282.00656,-29.34188 0.54312,-73.35418 z m 587.964115,153.75598 -48.90296,48.90296 196.15495,229.30022 24.32358,-24.49463 a 255.44602,255.44602 0 0 0 51.51985,-74.7975 l 16.13959,-35.71462 z m -283.099,6.87555 -252.66468,148.33875 48.90296,76.61445 68.46403,47.27256 184.20065,-223.32331 z m 185.29308,58.87186 -75.52769,5.9769 -51.07647,265.16263 80.17289,10.98795 a 174.6582,174.6582 0 0 0 53.59207,-0.95653 l 51.52296,-8.94467 z"}])

(defn sun [{:keys [color] :as props}]
  (build-icon-hiccup (merge {:viewBox "0 0 1058.3333 1058.3333"
                     :fill (or color "#fdb842")
                     :stroke "none"}
                    (dissoc props :color))
                    sun-shapes
                     ))

(def transition-shapes
 [:path {:d "M 337.77267,39.137666 204.5901,62.689195 389.01709,146.51044 a 197.21106,190.57666 0 0 1 89.56196,81.84514 l 54.00963,96.57394 -125.89154,-79.76515 A 280.83601,271.38837 0 0 0 301.02659,204.77769 L 197.00037,187.22165 91.067825,258.62556 38.7196,369.72937 85.332774,358.96001 a 950.58864,918.60978 0 0 1 200.016736,-24.95507 325.2463,314.30465 0 0 1 185.1396,51.50073 l 19.34095,12.2251 -56.60534,-5.67614 a 447.78713,432.72307 0 0 0 -144.19171,8.17417 l -97.24627,21.07211 -90.73864,113.72432 -9.443357,149.66786 97.633317,-81.83427 a 218.63739,211.28219 0 0 1 82.86471,-43.45317 l 75.22539,-20.89278 a 726.85298,702.40084 0 0 1 104.6427,-21.15736 l 64.74334,-8.34781 1.12706,81.15732 a 175.19045,169.29685 0 0 1 -7.19801,50.35351 l -19.25205,62.80753 -10.21953,75.22177 -80.69533,12.62559 c -49.61256,7.76262 -97.15741,25.42313 -139.80698,51.9312 l -89.80021,55.81364 67.64343,17.42323 a 268.99298,268.99298 0 0 0 124.14539,2.38332 l 127.7891,-27.73268 a 302.95311,302.95311 0 0 1 130.38325,0.41393 l 120.36062,26.9229 a 315.55454,315.55454 0 0 0 125.05593,2.56987 l 57.37014,-10.3787 -74.41406,-57.69725 C 809.70774,822.20009 763.34672,801.66025 714.13006,792.98003 L 629.883,778.1215 l -29.01166,-52.55545 18.95543,-70.92694 6.57478,-139.55685 123.01059,2.42724 a 248.89357,240.52051 0 0 1 67.95089,10.53735 l 63.26591,18.76526 a 149.0982,144.08237 0 0 1 58.50082,32.87706 l 71.10214,64.74437 -17.53849,-102.19532 -30.39969,-47.19609 A 160.95661,155.54185 0 0 0 857.89699,424.8485 427.78568,413.3945 0 0 0 765.6742,416.70841 l -75.49307,1.10692 104.86389,-16.66927 a 581.74601,562.17542 0 0 1 108.61094,-7.28741 122.35137,118.23534 0 0 1 84.84443,39.55676 l 31.11331,31.88487 V 348.69189 l -90.03065,-87.75742 -67.65581,9.31932 a 424.73304,410.44455 0 0 0 -113.20963,31.5464 L 660.92452,339.6754 779.39273,228.30649 a 1128.996,1091.0153 0 0 1 71.4003,-61.6474 l 26.27747,-20.82096 a 211.54678,204.43011 0 0 1 64.92265,-35.09605 L 979.95001,98.043108 837.66361,78.808626 687.8221,113.6711 660.71575,174.05553 a 668.66123,646.16673 0 0 0 -27.94146,74.43473 L 610.64128,321.04243 606.3449,191.57539 A 202.4551,195.64429 0 0 0 579.32278,100.02077 L 553.72021,57.170154 Z m 350.99873,517.313124 43.48003,27.40195 a 65.383294,62.633314 0 0 1 24.94422,29.48347 l 11.23393,27.47481 31.53968,-23.16345 a 60.645477,58.094766 0 0 1 37.2799,-11.98944 l 42.86146,0.25838 c 0,0 -52.33,-21.76136 -78.05673,-15.33808 -10.01662,2.50087 -24.01766,19.55591 -24.01766,19.55591 l -9.47281,-21.17391 a 36.773599,35.226925 0 0 0 -25.48837,-20.43854 z m -307.91971,95.63861 -51.52863,4.08295 -6.82026,17.96687 -34.85782,-16.33337 -33.34215,26.13328 25.9984,-5.38829 a 41.367203,44.582008 0 0 1 25.71317,3.5884 l 25.5819,13.2333 6.70605,-16.71267 a 30.283074,32.636488 0 0 1 18.39526,-18.07073 z m 176.52152,277.68693 c -23.81675,0.78463 -47.6671,3.27227 -65.79598,6.61303 L 367.3998,959.27271 a 370.30062,370.30062 0 0 1 -125.75202,1.45884 L 125.16084,942.04792 58.233134,1019.1957 h 8.422232 l 949.802534,-5.4338 -59.64033,-66.33756 -90.60223,14.28751 c -34.80943,5.48932 -90.89856,3.66602 -125.33446,-3.81682 -36.02535,-7.82823 -81.31965,-17.44968 -117.91373,-24.5122 -17.99423,-3.47283 -41.77718,-4.39115 -65.59394,-3.6065 z"
           }])

(defn transition [{:keys [color] :as props}]
  (build-icon-hiccup (merge {:viewBox "0 0 1058.3333 1058.3333"
                     :fill (or color "#ccdd51")
                     :stroke "none"}
                    (dissoc props :color))
                     transition-shapes
   ))

(def full-shapes
   (list [:rect {:width "1438.5" :height "1284.8" :x "-178" :y "-116" :fill (or bg-color "#72c7e3")}]
   [:path {:fill (or icon-color "#fffbf2") :d
           "M 337.77267,32.402502 204.5901,55.954032 389.01709,139.77528 a 197.21106,190.57666 0 0 1 89.56197,81.84513 L 532.58868,318.19436 406.69714,238.4292 A 280.83601,271.38837 0 0 0 301.02659,198.04253 L 197.00038,180.48648 91.067828,251.89039 38.719604,362.99421 85.332778,352.22485 a 950.58864,918.60978 0 0 1 200.016742,-24.95508 325.2463,314.30465 0 0 1 185.13959,51.50074 l 19.34095,12.2251 -56.60534,-5.67615 a 447.78713,432.72307 0 0 0 -144.19171,8.17418 l -97.24626,21.0721 -90.73865,113.72433 -9.443353,149.66786 97.633323,-81.83428 a 218.63739,211.28219 0 0 1 82.86471,-43.45316 l 75.22538,-20.89279 a 726.85298,702.40084 0 0 1 104.64271,-21.15736 l 64.74334,-8.34781 1.12705,81.15732 a 175.19045,169.29685 0 0 1 -7.19801,50.35351 l -19.25205,62.80754 -13.33872,98.18108 c -35.99757,6.16587 -68.09767,16.82619 -97.25195,25.27702 -38.42254,11.13739 -73.1943,18.64812 -102.46557,16.07912 -32.72027,-2.8717 -72.87859,-21.64286 -125.94426,-34.6599 -27.71435,-6.79836 -56.158372,-10.40574 -84.648581,-10.8195 v 102.50331 c 19.523298,0.39897 38.990161,2.90461 57.782011,7.51427 37.29543,9.14859 82.72622,32.18506 142.92616,37.46852 53.95049,4.73497 102.18546,-8.05427 143.71163,-20.09129 41.31116,-11.97468 77.50786,-23.32465 109.68478,-25.4341 37.93297,-2.48679 81.02242,6.85022 128.21905,18.59783 47.38459,11.79441 100.68376,26.20395 160.43672,24.52925 70.71459,-1.98193 129.92614,-22.85949 181.28919,-35.10122 V 794.46038 c -73.09109,11.28023 -132.84228,37.4337 -184.45644,38.88032 -38.70518,1.08479 -82.12212,-9.31312 -130.03289,-21.23851 -9.37252,-2.33289 -18.98495,-4.73013 -28.83441,-7.04918 l -47.59658,-86.22213 18.95544,-70.92693 6.57478,-139.55686 123.01058,2.42725 a 248.89357,240.52051 0 0 1 67.9509,10.53734 l 63.2659,18.76526 a 149.0982,144.08237 0 0 1 58.50082,32.87706 l 71.10204,64.74438 -17.53838,-102.19532 -30.39969,-47.19609 A 160.95661,155.54185 0 0 0 857.897,418.11333 427.78568,413.3945 0 0 0 765.6742,409.97325 l -75.49307,1.10692 104.86389,-16.66928 a 581.74601,562.17542 0 0 1 108.61094,-7.2874 122.35137,118.23534 0 0 1 84.84443,39.55675 l 31.11331,31.88488 V 341.95673 l -90.03065,-87.75743 -67.65581,9.31933 a 424.73304,410.44455 0 0 0 -113.20962,31.54639 l -87.79309,37.87521 118.46821,-111.36891 a 1128.996,1091.0153 0 0 1 71.4003,-61.6474 l 26.27747,-20.82095 a 211.54678,204.43011 0 0 1 64.92264,-35.09606 L 979.95001,91.307945 837.66361,72.073463 687.8221,106.93593 660.71575,167.32036 A 668.66123,646.16673 0 0 0 632.7743,241.7551 L 610.64128,314.30726 606.3449,184.84022 A 202.4551,195.64429 0 0 0 579.32278,93.285603 L 553.72022,50.43499 Z M 67.742119,924.80436 v 51.29506 c 22.132535,0.0399 44.226761,2.7419 65.494191,8.17212 42.03248,10.73216 86.83381,34.97576 141.95723,40.21046 47.87868,4.5467 92.7706,-7.252 133.36292,-19.2526 40.47154,-11.96477 78.36961,-24.30657 114.97696,-26.28364 42.51236,-2.29599 87.30995,9.0165 133.70915,21.64674 46.50872,12.66 96.6087,26.8791 150.62906,25.2031 47.00858,-1.4585 89.95245,-14.723 130.04735,-26.14516 18.27383,-5.2058 36.24989,-10.07124 53.87268,-13.69426 v -52.37923 c -24.09506,4.29111 -47.17958,10.71264 -69.23711,16.99638 -40.64281,11.57829 -79.21894,22.87568 -116.42649,24.03006 -42.77068,1.32698 -87.53635,-10.57581 -134.15305,-23.26525 -46.72622,-12.71925 -97.30095,-26.4562 -151.47602,-23.53034 -46.66061,2.52002 -88.64441,16.91084 -127.83665,28.49748 -39.07143,11.55094 -77.0388,20.54366 -112.15129,17.20928 -40.4657,-3.84271 -82.62892,-25.75831 -133.39186,-38.7196 -25.93225,-6.62126 -52.660374,-9.92965 -79.377071,-9.9906 z"
           }]))


(defn full [{:keys [icon-color bg-color] :as props}]
  (build-icon-hiccup
   (merge {:viewBox "0 0 1058.3333 1058.3333"
                     :stroke "none"}
                    (dissoc props :icon-color :bg-color))
                    full-shapes
   ))





(def phone-hangup-shapes
  [:path {:d "M12 9c-1.6 0-3.15.25-4.6.72v3.1c0 .39-.23.74-.58.9-.98.45-1.89.86-2.73 1.34-.14.07-.31.11-.49.11-.28 0-.53-.11-.7-.3l-2.6-2.6c-.18-.17-.3-.43-.3-.71 0-.28.11-.53.3-.7C3.12 7.32 7.32 4 12 4s8.88 3.32 11.7 6.96c.19.17.3.43.3.71 0 .28-.11.53-.3.71l-2.6 2.6c-.17.19-.42.3-.7.3-.18 0-.35-.04-.49-.11-.84-.48-1.75-.89-2.73-1.34-.35-.16-.58-.51-.58-.9v-3.1C15.15 9.25 13.6 9 12 9z"}])

(defn phone-hangup [props]
  (build-icon-hiccup (merge {:fill "currentColor" :stroke "none" :animate :hangup} props) phone-hangup-shapes))

(def mic-shapes
  [:path {:d "M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3zM19 10v2a7 7 0 0 1-14 0v-2M12 19v4M8 23h8"}])

(defn mic [props]
  (build-icon-hiccup (merge {:animate :wiggle} props) mic-shapes))

(def screen-share-shapes
  [:g
   [:rect {:x "2" :y "3" :width "20" :height "14" :rx "2" :ry "2"}]
   [:path {:d "M8 21h8M12 17v4"}]
   [:path {:d "M17 8l-5-5-5 5M12 3v9" :stroke "var(--accent-color)"}
    [:animate {:attributeName "stroke-opacity"
               :values "1;0.3;1"
               :dur "2s"
               :repeatCount "indefinite"}]]])

(defn screen-share [props]
  (build-icon-hiccup props screen-share-shapes))

(def video-shapes
  [:path {:d "M23 7l-7 5 7 5V7zM1 5h15v14H1z"}])

(defn video [props]
  (build-icon-hiccup props video-shapes))

(def mic-off-shapes
  [:g
   [:path {:d "M9 9a3 3 0 0 0 5.12 2.12M15 9.34V4a3 3 0 0 0-5.94-.6"}]
   [:path {:d "M17 16.95A7 7 0 0 1 5 12v-2m14 0v2a7 7 0 0 1-.11 1.23M12 19v4M8 23h8"}]
   [:line {:x1 "1" :y1 "1" :x2 "23" :y2 "23"
           :stroke-dasharray "32"
           :stroke-dashoffset "32"}
    [:animate {:attributeName "stroke-dashoffset"
               :from "32" :to "0"
               :dur "0.2s"
               :fill "freeze"}]]])

(defn mic-off [props]
  (build-icon-hiccup props mic-off-shapes))

(def headphones-shapes
  [:path {:d "M3 18v-6a9 9 0 0 1 18 0v6M4 15h2a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2zM18 15h2a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-2a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z"}])

(defn headphones [props]
  (build-icon-hiccup (merge {:animate :ears} props) headphones-shapes))

(def headphones-off-shapes
  [:g [:path {:d "M3 11a9 9 0 0 1 13.52-7.78M20.42 15h.58a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-2a2 2 0 0 1-2-2v-3a2 2 0 0 1 .58-1.42M2 17v3a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2v-3a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2z"}]
   [:line {:x1 "1" :y1 "1" :x2 "23" :y2 "23"
           :stroke-dasharray "32" :stroke-dashoffset "32"}
    [:animate {:attributeName "stroke-dashoffset" :from "32" :to "0" :dur "0.2s" :fill "freeze"}]]])

(defn headphones-off [props]
  (build-icon-hiccup props headphones-off-shapes))

(def video-off-shapes
  [:g [:path {:d "M16 16v3H1V5h2m4 0h9v9l7 5V7l-7 5V7.94"}]
   [:line {:x1 "1" :y1 "1" :x2 "23" :y2 "23"
           :stroke-dasharray "32" :stroke-dashoffset "32"}
    [:animate {:attributeName "stroke-dashoffset" :from "32" :to "0" :dur "0.2s" :fill "freeze"}]]])

(defn video-off [props]
  (build-icon-hiccup props video-off-shapes))

(def screen-share-off-shapes
  [:g
   [:rect {:x "2" :y "3" :width "20" :height "14" :rx "2" :ry "2"}
    [:animate {:attributeName "opacity" :from "1" :to "0.3" :dur "0.4s" :fill "freeze"}]]
   [:path {:d "M8 21h8M12 17v4"}]])

(defn screen-share-off [props]
  (build-icon-hiccup props screen-share-off-shapes))

(def speaker-base-shape
  [:path {:d "M11 5L6 9H2v6h4l5 4V5z"}])

(def speaker-waves-shape
  [:path {:d "M19.07 4.93a10 10 0 0 1 0 14.14M15.54 8.46a5 5 0 0 1 0 7.07"}])

(defn speaker [{:keys [has-call?] :as props}]
  (build-icon-hiccup (merge {:stroke-width "2.5"
                             :animate (when has-call? :waves)}
                            (dissoc props :has-call?))
                     [:g
                      speaker-base-shape
                      (when has-call? speaker-waves-shape)]))

(def settings-shapes
  [:g
   [:circle {:cx "12" :cy "12" :r "3"}]
   [:path {:d "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"}]])

(defn settings [props]
  (build-icon-hiccup props settings-shapes))

(def chat-bubble-shapes
  [:path {:d "M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"}])

(defn chat-bubble [props]
  (build-icon-hiccup props chat-bubble-shapes))

(def search-shapes
  [:g
   [:circle {:cx "11" :cy "11" :r "8"}]
   [:line {:x1 "21" :y1 "21" :x2 "16.65" :y2 "16.65"}]])

(defn search [props]
  (build-icon-hiccup (merge {:animate :scan} props) search-shapes))

(def hash-shapes
  [:g
   [:line {:x1 "4" :y1 "9" :x2 "20" :y2 "9"}]
   [:line {:x1 "4" :y1 "15" :x2 "20" :y2 "15"}]
   [:line {:x1 "10" :y1 "3" :x2 "8" :y2 "21"}]
   [:line {:x1 "16" :y1 "3" :x2 "14" :y2 "21"}]])

(defn hash [props]
  (build-icon-hiccup props hash-shapes))

(def shield-shapes
  [:g
   [:path {:d "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"}]])

(def globe-shapes
  [:g
   [:circle {:cx "12" :cy "12" :r "10"}]
   [:line {:x1 "2" :y1 "12" :x2 "22" :y2 "12"}]
   [:path {:d "M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"}]])

(def lock-shapes
  [:g
   [:rect {:x "3" :y "11" :width "18" :height "11" :rx "2" :ry "2"}]
   [:path {:d "M7 11V7a5 5 0 0 1 10 0v4"}]])

(def phone-shapes
  [:path {:d "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"}])

(defn phone [props]
  (build-icon-hiccup (merge {:width "20px" :height "20px"} props) phone-shapes))

(def pins-shapes
  [:g
   [:path {:d "M12 17v5"}]
   [:path {:d "M9 10.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24V16a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V7a1 1 0 0 1 1-1 2 2 0 0 0 0-4H8a2 2 0 0 0 0 4 1 1 0 0 1 1 1z"}]])

(defn pins [props]
  (build-icon-hiccup (merge {:width "20px" :height "20px"} props) pins-shapes))

(def members-shapes
  [:g
   [:path {:d "M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"}]
   [:circle {:cx "9" :cy "7" :r "4"}]
   [:path {:d "M23 21v-2a4 4 0 0 0-3-3.87"}]
   [:path {:d "M16 3.13a4 4 0 0 1 0 7.75"}]])

(defn members [props]
  (build-icon-hiccup (merge {:width "20px" :height "20px"} props) members-shapes))

(def more-vertical-shapes
  [:g
   [:circle {:cx "12" :cy "12" :r "1"}]
   [:circle {:cx "12" :cy "5" :r "1"}]
   [:circle {:cx "12" :cy "19" :r "1"}]])

(defn more-vertical [props]
  (build-icon-hiccup (merge {:width "20px" :height "20px"} props) more-vertical-shapes))

(def menu-shapes
  [:g
   [:line {:x1 "4" :y1 "12" :x2 "20" :y2 "12"}]
   [:line {:x1 "4" :y1 "6" :x2 "20" :y2 "6"}]
   [:line {:x1 "4" :y1 "18" :x2 "20" :y2 "18"}]])

(defn menu [props]
  (build-icon-hiccup (merge {:width "20px" :height "20px"} props) menu-shapes))

(def arrow-left-shapes
  [:g
   [:line {:x1 "19" :y1 "12" :x2 "5" :y2 "12"}]
   [:polyline {:points "12 19 5 12 12 5"}]])

(defn arrow-left [props]
  (build-icon-hiccup props arrow-left-shapes))

(def check-circle-green-shapes
  [:polyline {:points "20 6 9 17 4 12"}])

(defn check-circle-green [props]
  (build-icon-hiccup (merge {:stroke "#22c55e"} props) check-circle-green-shapes))

(def chevron-down-shapes
  [:polyline {:points "6 9 12 15 18 9"}])

(defn chevron-down [props]
  (build-icon-hiccup props chevron-down-shapes))


(def home-shapes
  [:g
   [:path {:d "M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"}]
   [:polyline {:points "9 22 9 12 15 12 15 22"}]])

(defn home [props]
  (build-icon-hiccup props home-shapes))

(def user-shapes
  [:g
   [:path {:d "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"}]
   [:circle {:cx "12" :cy "7" :r "4"}]])

(defn user [props]
  (build-icon-hiccup props user-shapes))

(def check-shapes
  [:polyline {:points "20 6 9 17 4 12"}])

(defn check [props]
  (build-icon-hiccup props check-shapes))

(def leave-shapes
  [:g
   [:path {:d "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"}]
   [:polyline {:points "16 17 21 12 16 7"}]
   [:line {:x1 "21" :y1 "12" :x2 "9" :y2 "12"}]])

(defn leave [props]
  (build-icon-hiccup props leave-shapes))

(def download-shapes
  [:g
   [:path {:d "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"}]
   [:polyline {:points "7 10 12 15 17 10"}]
   [:line {:x1 "12" :y1 "15" :x2 "12" :y2 "3"}]])

(defn download [props]
  (build-icon-hiccup props download-shapes))

(def external-link-shapes
  [:g
   [:path {:d "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"}]
   [:polyline {:points "15 3 21 3 21 9"}]
   [:line {:x1 "10" :y1 "14" :x2 "21" :y2 "3"}]])

(defn external-link [props]
  (build-icon-hiccup props external-link-shapes))

(def plus-shapes
  [:g
   [:line {:x1 "12" :y1 "5" :x2 "12" :y2 "19"}]
   [:line {:x1 "5" :y1 "12" :x2 "19" :y2 "12"}]])

(defn plus [props]
  (build-icon-hiccup (merge {:width "20px" :height "20px"} props) plus-shapes))

(def smiley-shapes
  [:g
   [:circle {:cx "12" :cy "12" :r "9"}]
   [:circle {:cx "9" :cy "9" :r "1" :fill "currentColor"}]
   [:circle {:cx "15" :cy "9" :r "1" :fill "currentColor"}]
   [:path {:d "M8 14s1.5 2 4 2 4-2 4-2"
           :fill "none"
           :stroke-linecap "round"}]])

(defn smiley [props]
  (build-icon-hiccup (merge {:width "20px" :height "20px"} props) smiley-shapes))

(def send-shapes
  [:g
   [:path {:d "M22 2L11 13"}]
   [:path {:d "M22 2L15 22L11 13L2 9L22 2Z"}]])

(defn send [props]
  (build-icon-hiccup (merge {:width "20px" :height "20px"} props) send-shapes))

(def file-shapes
  [:g
   [:path {:d "M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"}]
   [:polyline {:points "13 2 13 9 20 9"}]])

(defn file [props]
  (build-icon-hiccup props file-shapes))

(def reply-shapes
  [:g
   [:polyline {:points "9 17 4 12 9 7"}]
   [:path {:d "M20 18v-2a4 4 0 0 0-4-4H4"}]])

(defn reply [props]
  (build-icon-hiccup props reply-shapes))

(def edit-shapes
  [:g
   [:path {:d "M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"}]
   [:path {:d "M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"}]])

(defn edit [props]
  (build-icon-hiccup props edit-shapes))

(def exit-shapes
  [:g
   [:line {:x1 "18" :y1 "6" :x2 "6" :y2 "18"}]
   [:line {:x1 "6" :y1 "6" :x2 "18" :y2 "18"}]])

(defn exit [props]
  (build-icon-hiccup props exit-shapes))

(def minimize-shapes
  [:g
   [:line {:x1 "4" :y1 "12" :x2 "20" :y2 "12"}]])

(defn minimize [props]
  (build-icon-hiccup props minimize-shapes))

(def maximize-shapes
  [:g
   [:rect {:x "5" :y "5" :width "14" :height "14"}]])

(defn maximize [props]
  (build-icon-hiccup props maximize-shapes))

(def restore-shapes
  [:g
   [:path {:d "M7,7 L7,4 L20,4 L20,17 L17,17 M4,7 L17,7 L17,20 L4,20 Z"}]])

(defn restore [props]
  (build-icon-hiccup props restore-shapes))

(def thread-shapes
  [:g
   [:circle {:cx "7" :cy "7" :r "3"}]
   [:circle {:cx "17" :cy "17" :r "3"}]
   [:path {:d "M7 10v4a2 2 0 0 0 2 2h4"}]
   [:path {:d "M17 14v-4a2 2 0 0 0-2-2h-4"}]])

(defn thread [props]
  (build-icon-hiccup props thread-shapes))

(def copy-shapes
  [:g
   [:rect {:x "9" :y "9" :width "13" :height "13" :rx "2" :ry "2"}]
   [:path {:d "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"}]])

(defn copy [props]
  (build-icon-hiccup props copy-shapes))

(def link-shapes
  [:g
   [:path {:d "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"}]
   [:path {:d "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"}]])

(defn link [props]
  (build-icon-hiccup props link-shapes))

(def trash-shapes
  [:g
   [:polyline {:points "3 6 5 6 21 6"}]
   [:path {:d "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"}]
   [:line {:x1 "10" :y1 "11" :x2 "10" :y2 "17"}]
   [:line {:x1 "14" :y1 "11" :x2 "14" :y2 "17"}]])

(defn trash [props]
  (build-icon-hiccup props trash-shapes))

(def more-shapes
  [:g
   [:circle {:cx "12" :cy "12" :r "1"}]
   [:circle {:cx "19" :cy "12" :r "1"}]
   [:circle {:cx "5" :cy "12" :r "1"}]])

(defn more [props]
  (build-icon-hiccup props more-shapes))

(def double-check-shapes
  [:g
   [:path {:d "M7 12l5 5L22 7"}]
   [:path {:d "M2 12l5 5L9 15"}]])

(defn double-check [props]
  (build-icon-hiccup props double-check-shapes))

(def typing-dots-shapes
  [:g
   [:circle {:cx "5" :cy "12" :r "1.5"}
    [:animate {:attributeName "opacity" :values "0.3;1;0.3" :dur "1.2s" :repeatCount "indefinite"}]]
   [:circle {:cx "12" :cy "12" :r "1.5"}
    [:animate {:attributeName "opacity" :values "0.3;1;0.3" :dur "1.2s" :begin "0.2s" :repeatCount "indefinite"}]]
   [:circle {:cx "19" :cy "12" :r "1.5"}
    [:animate {:attributeName "opacity" :values "0.3;1;0.3" :dur "1.2s" :begin "0.4s" :repeatCount "indefinite"}]]])

(defn typing-dots [props]
  (build-icon-hiccup props typing-dots-shapes))

(def door-open-shapes
  [:g
   [:path {:d "M13 4h3a2 2 0 0 1 2 2v14"}]
   [:path {:d "M2 20h3"}]
   [:path {:d "M13 20h9"}]
   [:path {:d "M10 12v.01"}]
   [:path {:d "M13 4.562v16.157a1 1 0 0 1-1.242.97L5 20V5.562a2 2 0 0 1 1.515-1.94l4-1A2 2 0 0 1 13 4.561Z"}]])

(defn door-open [props]
  (build-icon-hiccup props door-open-shapes))

(def doorbell-shapes
  [:g
   [:rect {:x "7" :y "3" :width "10" :height "18" :rx "2"}]
   [:circle {:cx "12" :cy "15" :r "2"}]
   [:line {:x1 "10" :y1 "7" :x2 "14" :y2 "7"}]
   [:line {:x1 "10" :y1 "9" :x2 "14" :y2 "9"}]])

(defn doorbell [props]
  (build-icon-hiccup props doorbell-shapes))

(def compass-shapes
  [:g
   [:circle {:cx "12" :cy "12" :r "10"}]
   [:line {:x1 "12" :y1 "2" :x2 "12" :y2 "22"}]
   [:line {:x1 "2" :y1 "12" :x2 "22" :y2 "12"}]
   [:path {:d "M16.24 7.76l-2.12 6.36-6.36 2.12 2.12-6.36z"}]])

(defn compass [props]
  (build-icon-hiccup props compass-shapes))

(defn members-plus [{:keys [size] :as props}]
  (let [final-size (or size "20px")]
    (build-icon-hiccup
     (assoc props :size final-size)
     [:g
      [:svg {:x "2" :y "2" :width "18" :height "18" :viewBox "0 0 24 24"}
       members-shapes]

      [:svg {:x "14" :y "14" :width "10" :height "10" :viewBox "0 0 24 24"}
       plus-shapes]])))

(def plus-circle-shapes
  [:g
   [:circle {:cx "12" :cy "12" :r "10"}]
   [:line {:x1 "12" :y1 "8" :x2 "12" :y2 "16"}]
   [:line {:x1 "8" :y1 "12" :x2 "16" :y2 "12"}]])

(defn plus-circle [props]
  (build-icon-hiccup props plus-circle-shapes))

(def bell-shapes
  [:g
   [:path {:d "M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9"}]
   [:path {:d "M10.3 21a1.94 1.94 0 0 0 3.4 0"}]])

(defn bell [props]
  (build-icon-hiccup props bell-shapes))

(defn dynamic-room-hash [{:keys [is-public? is-encrypted? is-joinable?] :as props}]
  (let [clean-props (dissoc props :is-public? :is-encrypted? :is-joinable?)]
    (composite-icon
     (merge clean-props
            {:main hash-shapes
             :top-right    (when is-public? globe-shapes)
             :bottom-right (when is-encrypted? shield-shapes)
             :top-left     (when is-joinable? {:shape plus-shapes
                                               :class "active-green"
                                               :size "5px"
                                               :stroke
                                               "var(--active-green, #10b981)"})}))))
