(ns paradise.ui.overlays.polls
  (:require
   [cljs-workers.core :as main]
   [cljs.core.async :refer [<! go]]
   [clojure.string :as str]
   [paradise.shared.client.state :as state]
   [paradise.shared.utils.helpers :refer [uint8->base64]]
   [paradise.shared.utils.svg :as icons]
   [paradise.ui.container.call.audio-processor :as audio-processor]
   [paradise.ui.container.call.runtime :as call-rtc]
   [paradise.ui.overlays.base :refer [modal-component]]
   [promesa.core :as p]
   [re-frame.core :as re-frame]
   [re-frame.db :as rf-db]
   [reagent.core :as r]
   [taoensso.timbre :as log]))

(re-frame/reg-event-fx
 :polls/create
 (fn [_ [_ {:keys [room-id question answers max-selections undisclosed? on-done on-error]}]]
   (when (and room-id @state/!engine-pool)
     (let [pool @state/!engine-pool]
       (go
         (try
           (let [res (<! (main/do-with-pool!
                          pool
                          {:handler   :create-poll
                           :arguments {:room-id        room-id
                                       :question       question
                                       :answers        (vec answers)
                                       :max-selections (max 1 (int (or max-selections 1)))
                                       :undisclosed?   (boolean undisclosed?)}}))]
             (if (contains? #{"success" :success} (:status res))
               (do
                 (when on-done (on-done))
                 (re-frame/dispatch [:ui/close-modal]))
               (do
                 (log/error "Poll creation failed:" (:msg res))
                 (when on-error (on-error)))))
           (catch :default e
             (log/error "Exception creating poll:" e)
             (when on-error (on-error)))))))
   {}))


(defn ^:ui create-poll-content [{:keys [room-id]}]
  (r/with-let [!question    (r/atom "")
               !options     (r/atom ["" ""])
               !allow-multi (r/atom false)
               !undisclosed (r/atom false)
               !submitting? (r/atom false)]
    (let [tr          @(re-frame/subscribe [:i18n/tr])
          active-room @(re-frame/subscribe [:rooms/active-id])
          target-room (or room-id active-room)
          close-fn    #(re-frame/dispatch [:ui/close-modal])
          question    @!question
          options     @!options
          allow-multi @!allow-multi
          undisclosed @!undisclosed
          submitting? @!submitting?
          clean-opts  (vec (keep #(let [s (str/trim (str %))]
                                    (when-not (str/blank? s) s))
                                 options))
          can-submit? (and (not submitting?)
                           (not (str/blank? (str/trim question)))
                           (>= (count clean-opts) 2)
                           (some? target-room))]
      [:div.create-poll-container
       [:div.creation-header
        [:div.poll-modal-title-group
         [:span.poll-modal-icon-badge
          [icons/poll {:width "18px" :height "18px"}]]
         [:h3.creation-title (tr [:composer.polls/modal-title])]]
        [:button.creation-close-btn
         {:type "button" :on-click close-fn}
         [icons/exit {:width "18px" :height "18px"}]]]

       [:div.form-group
        [:label (tr [:composer.polls/question-label])]
        [:input.form-input
         {:type        "text"
          :auto-focus  true
          :placeholder (tr [:composer.polls/question-placeholder])
          :value       question
          :on-change   #(reset! !question (.. % -target -value))}]]

       [:div.form-group
        [:div.poll-modal-label-row
         [:label (tr [:composer.polls/options-label])]
         [:span.poll-modal-counter (str (count options) " / 10")]]
        (into
         [:div.poll-modal-options]
         (map-indexed
          (fn [idx opt-val]
            ^{:key idx}
            [:div.poll-modal-option-row
             [:span.poll-modal-option-index (str (inc idx))]
             [:input.form-input
              {:type        "text"
               :placeholder (tr [:composer.polls/option-placeholder] [(inc idx)])
               :value       opt-val
               :on-change   #(swap! !options assoc idx (.. % -target -value))}]
             (when (> (count options) 2)
               [:button.poll-modal-remove-opt
                {:type     "button"
                 :on-click #(swap! !options
                                   (fn [v]
                                     (vec (concat (subvec v 0 idx)
                                                  (subvec v (inc idx))))))}
                [icons/exit {:width "14px" :height "14px"}]])])
          options))
        (when (< (count options) 10)
          [:button.poll-modal-add-opt
           {:type     "button"
            :on-click #(swap! !options conj "")}
           [icons/plus {:width "14px" :height "14px"}]
           [:span (tr [:composer.polls/add-option])]])]

       [:label.form-group.toggle-row
        [:span.toggle-row-text (tr [:composer.polls/allow-multi])]
        [:span.custom-toggle
         [:input
          {:type      "checkbox"
           :checked   allow-multi
           :on-change #(swap! !allow-multi not)}]
         [:span.toggle-track
          [:span.toggle-knob]]]]

       [:label.form-group.toggle-row
        [:span.toggle-row-text (tr [:composer.polls/undisclosed])]
        [:span.custom-toggle
         [:input
          {:type      "checkbox"
           :checked   undisclosed
           :on-change #(swap! !undisclosed not)}]
         [:span.toggle-track
          [:span.toggle-knob]]]]

       [:div.modal-actions
        [:button.form-button.cancel-button
         {:type     "button"
          :on-click close-fn
          :disabled submitting?}
         (tr [:composer.polls/cancel])]
        [:button.form-button.create-button
         {:type     "button"
          :disabled (not can-submit?)
          :on-click (fn []
                      (when can-submit?
                        (reset! !submitting? true)
                        (re-frame/dispatch
                         [:polls/create
                          {:room-id        target-room
                           :question       (str/trim question)
                           :answers        clean-opts
                           :max-selections (if allow-multi (count clean-opts) 1)
                           :undisclosed?   undisclosed
                           :on-error       #(reset! !submitting? false)}])))}
         (if submitting?
           (tr [:composer.polls/creating])
           (tr [:composer.polls/create]))]]])))

(defmethod modal-component :create-poll [_]
  create-poll-content)