(ns paradise.ui.container.timeline.components
  (:require [re-frame.core :as re-frame]
            [clojure.string :as str]))

(defn timeline-empty-state [room-id]
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:div.timeline-empty {:style {:padding "40px" :text-align "center"}}
     (if room-id
       (tr [:container.timeline/loading])
       (tr [:container.timeline/no-room]))]))

(defn timeline-jump-button [do-jump! focus-mode?]
  (let [tr @(re-frame/subscribe [:i18n/tr])]
    [:button.jump-to-bottom
     {:on-click do-jump!}
     [:span (if focus-mode?
              (tr [:container.timeline/return-to-live])
              (tr [:container.timeline/jump-to-bottom]))]]))

(defn timeline-loading-overlay []
  [:div.spinner-wrapper
   [:div.spinner]])

(defn timeline-measuring-sticks [ruler-ref-fn]
  [:div#timeline-rulers
   {:style {:position "absolute" :visibility "hidden" :pointer-events "none" :z-index -1 :width "100%"}}
   [:div.swipe-foreground.timeline-message.is-message {:id "ruler-row" :ref ruler-ref-fn}
    [:div.timeline-avatar-wrapper {:id "ruler-avatar" :ref ruler-ref-fn}]
    [:div.timeline-content-wrapper {:id "ruler-content-wrapper" :ref ruler-ref-fn}
     [:div.timeline-header {:id "ruler-header" :ref ruler-ref-fn}
      [:span.timeline-sender-name "A"]
      [:span.timeline-timestamp "12:00 PM"]]
     [:div.timeline-reply-banner {:id "ruler-reply" :ref ruler-ref-fn} "Reply Text"]
     [:div.timeline-body {:id "ruler-text-plain" :ref ruler-ref-fn}
      [:div.message-render-container
       [:span.body "A"]]]
     [:div.timeline-body {:id "ruler-text-html" :ref ruler-ref-fn}
      [:div.message-render-container
       [:span.body.formatted [:p {:style {:margin 0}} "A"]]]]
     [:div.timeline-body
      [:pre {:id "ruler-code" :ref ruler-ref-fn} [:code "A"]]]
     [:div {:style {:display "flex"}}
      [:span.timeline-edited-label {:id "ruler-edited" :ref ruler-ref-fn} "(edited)"]]
     [:div.reactions-row {:id "ruler-reaction" :ref ruler-ref-fn}
      [:span.reaction-pill "[+1] 1"]]]]
   [:div.timeline-system-event {:id "ruler-system" :ref ruler-ref-fn} "System text"]
   [:div.timeline-item-virtual-wrapper {:id "ruler-divider" :ref ruler-ref-fn}
    [:div.timeline-date-separator
     [:div.separator-line]
     [:span.separator-text "Today"]
     [:div.separator-line]]]])

(defn- parse-px [val fallback]
  (let [v (js/parseFloat val)]
    (if (js/isNaN v) fallback v)))

(defn- box-height [el style]
  (let [rect-h (.-height (.getBoundingClientRect el))
        mt     (parse-px (.getPropertyValue style "margin-top") 0)
        mb     (parse-px (.getPropertyValue style "margin-bottom") 0)]
    (+ rect-h mt mb)))

(defn extract-timeline-metrics [acc entry]
  (let [el    (.-target entry)
        id    (.-id el)
        rect  (.getBoundingClientRect el)
        style (js/window.getComputedStyle el)]
    (case id
      "ruler-text-html"
      (let [total-h (.-height rect)
            lh      (parse-px (.getPropertyValue style "line-height") (:line-height acc 22.8))]
        (assoc acc :html-vertical-tax (max 0 (- total-h lh))))

      "ruler-row"
      (assoc acc
             :row-w          (.-width rect)
             :seq-padding    (parse-px (.getPropertyValue style "--chat-seq-padding") 10)
             :merged-padding (parse-px (.getPropertyValue style "--chat-merged-padding") 5)
             :media-margin   (parse-px (.getPropertyValue style "--chat-media-margin") 0))

      "ruler-content-wrapper"
      (assoc acc :content-w (.-width rect))

      "ruler-reply"
      (assoc acc :reply-banner-h (box-height el style))

      "ruler-text-plain"
      (let [lh       (parse-px (.getPropertyValue style "line-height") 22.8)
            total-h  (.-height rect)
            fw       (.getPropertyValue style "font-weight")
            fs       (.getPropertyValue style "font-size")
            ff       (.getPropertyValue style "font-family")
            raw-font (str/trim (.getPropertyValue style "font"))
            font-str (if (empty? raw-font) (str fw " " fs " " ff) raw-font)]
        (assoc acc
               :line-height       lh
               :text-vertical-tax (max 0 (- total-h lh))
               :text-node-w       (.-width rect)
               :font              font-str
               :text-wrap-buffer  (parse-px (.getPropertyValue style "--chat-text-wrap-buffer") 8)
               :quote-wrap-buffer (parse-px (.getPropertyValue style "--chat-quote-wrap-buffer") 19)))

      "ruler-code"
      (let [lh       (parse-px (.getPropertyValue style "line-height") 20.52)
            pt       (parse-px (.getPropertyValue style "padding-top") 14)
            pb       (parse-px (.getPropertyValue style "padding-bottom") 14)
            pl       (parse-px (.getPropertyValue style "padding-left") 12)
            pr       (parse-px (.getPropertyValue style "padding-right") 12)
            mt       (parse-px (.getPropertyValue style "margin-top") 0)
            mb       (parse-px (.getPropertyValue style "margin-bottom") 0)
            fw       (.getPropertyValue style "font-weight")
            fs       (.getPropertyValue style "font-size")
            ff       (.getPropertyValue style "font-family")
            raw-font (str/trim (.getPropertyValue style "font"))
            font-str (if (empty? raw-font) (str fw " " fs " " ff) raw-font)]
        (assoc acc
               :code-line-height lh
               :code-padding     (+ pt pb mt mb)
               :code-padding-x   (+ pl pr)
               :code-font        font-str))

      "ruler-avatar"   (assoc acc :avatar-h (.-height rect))
      "ruler-header"   (assoc acc :header-h (box-height el style))
      "ruler-edited"   (assoc acc
                              :edited-label-h (.-height rect)
                              :edited-label-w (.-width rect))
      "ruler-reaction" (let [h (box-height el style)]
                         (assoc acc :reaction-row-h h :reaction-pill-h (.-height rect)))
      "ruler-system"   (assoc acc :system-event-h (box-height el style))
      "ruler-divider"  (assoc acc :virtual-divider-h (box-height el style))
      acc)))
