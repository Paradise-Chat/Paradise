(ns paradise.ui.input.drafts
  (:require [re-frame.core :as re-frame]
            [taoensso.timbre :as log]
            [promesa.core :as p]
            [clojure.string :as str]
            [paradise.shared.utils.svg :as icons]
            [paradise.shared.utils.helpers :refer [unregister-file! file->array-buffer register-file! get-file]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [cljs.core.async :refer [go <!]]
            [cljs-workers.core :as main]
            [paradise.shared.client.state :as state]))

(defn ^:ui attachment-preview [room-id attachment index]
  [:div.attachment-preview
   (let [{:keys [mimetype mime preview-url filename]} attachment
         resolved-mime (or mime mimetype "application/octet-stream")]
     (cond
       (str/starts-with? resolved-mime "image/")
       [:img.preview-image {:src preview-url}]

       (or (str/starts-with? resolved-mime "audio/")
           (str/starts-with? resolved-mime "video/"))
       [:div.preview-media-placeholder
        [:span [icons/chevron-down]]
        [:span.mime-label (last (str/split resolved-mime #"/"))]]

       :else
       [:div.preview-file
        [icons/file]
        [:span.file-name filename]]))
   [:button.remove-attachment
    {:on-click #(re-frame/dispatch [:composer/remove-attachment room-id index])}
    [icons/exit]]])

(re-frame/reg-event-fx
 :composer/clear-after-submit
 (fn [{:keys [db]} [_ room-id]]
   (doseq [att (get-in db [:drafts room-id :attachments] [])]
     (when (:preview-url att)
       (js/URL.revokeObjectURL (:preview-url att)))
     (when (:file-id att)
       (unregister-file! (:file-id att))))
   {:db (-> db
            (assoc-in [:drafts room-id :attachments] [])
            (assoc-in [:composer room-id] {:text        ""
                                           :html        ""
                                           :loaded-text ""
                                           :uploading?  false}))
    :dispatch [:composer/persist-draft room-id]}))

(re-frame/reg-event-fx
 :composer/persist-draft
 (fn [{:keys [db]} [_ room-id]]
   (let [attachments        (get-in db [:drafts room-id :attachments] [])
         {:keys [text html]} (get-in db [:composer room-id])]
     (if-let [pool @state/!engine-pool]
       (go
         (try
           (let [worker-atts
                 (<p! (p/all (map (fn [att]
                                    (p/let [f  (get-file (:file-id att))
                                            ab (file->array-buffer f)]
                                      (assoc att :buffer ab)))
                                  attachments)))
                 res (<! (main/do-with-pool! pool {:handler   :persist-draft
                                                   :arguments {:room-id     room-id
                                                               :text        (or text "")
                                                               :html        (or html "")
                                                               :attachments worker-atts}}))]
             (when (= (:status res) "error")
               (log/error "Persist failed:" (:msg res))))
           (catch :default e
             (log/error "Exception in persist-draft:" (str e)))))
       (log/warn "No engine pool for draft auto-save"))
     {})))

(re-frame/reg-sub
 :composer/cached-html
 (fn [db [_ room-id]]
   (get-in db [:composer room-id :html])))

(re-frame/reg-event-fx
 :composer/on-change
 (fn [{:keys [db]} [_ room-id text html]]
   (let [old-timer (get-in db [:composer room-id :save-timer])]
     (when old-timer (js/clearTimeout old-timer))
     {:db (update-in db [:composer room-id]
                     merge
                     {:text        text
                      :html        html
                      :loaded-text nil
                      :save-timer  (js/setTimeout
                                    #(re-frame/dispatch [:composer/persist-draft room-id])
                                    500)})})))

(re-frame/reg-event-fx
 :composer/add-attachment
 (fn [{:keys [db]} [_ room-id attachment]]
   {:db       (update-in db [:drafts room-id :attachments] (fnil conj []) attachment)
    :dispatch [:composer/persist-draft room-id]}))

(re-frame/reg-sub
 :composer/attachments
 (fn [db [_ room-id]]
   (get-in db [:drafts room-id :attachments] [])))

(re-frame/reg-event-fx
 :composer/remove-attachment
 (fn [{:keys [db]} [_ room-id index]]
   (let [attachment (get-in db [:drafts room-id :attachments index])]
     (when (:preview-url attachment)
       (js/URL.revokeObjectURL (:preview-url attachment)))
     (when (:file-id attachment)
       (unregister-file! (:file-id attachment))))
   {:db       (update-in db [:drafts room-id :attachments]
                         (fn [atts] (vec (concat (take index atts) (drop (inc index) atts)))))
    :dispatch [:composer/persist-draft room-id]}))

(re-frame/reg-event-fx
 :composer/load-draft
 (fn [_ [_ room-id]]
   (if-let [pool @state/!engine-pool]
     (go
       (let [res (<! (main/do-with-pool! pool {:handler   :load-draft
                                               :arguments {:room-id room-id}}))]
         (when (and (= (:status res) "success") (:draft res))
           (re-frame/dispatch [:composer/draft-loaded room-id (:draft res)]))))
     (log/error "No engine pool to load draft"))
   {}))

(re-frame/reg-event-fx
 :composer/draft-loaded
 (fn [{:keys [db]} [_ room-id draft]]
   (let [plain-text (:text draft)
         html-text  (:html draft)
         raw-atts   (:attachments draft)
         restored-atts
         (mapv (fn [att]
                 (let [buffer (:buffer att)
                       mime   (or (:mimetype att) (:mime att) "application/octet-stream")
                       blob   (js/Blob. #js [buffer] #js {:type mime})
                       url    (js/URL.createObjectURL blob)
                       fid    (register-file! blob)]
                   (-> att
                       (assoc :preview-url url :file-id fid :mimetype mime :mime mime)
                       (dissoc :buffer))))
               raw-atts)]
     {:db (-> db
              (assoc-in [:composer room-id :loaded-text] (or html-text plain-text))
              (assoc-in [:drafts room-id :attachments] restored-atts))})))

(re-frame/reg-sub
 :composer/loaded-text
 (fn [db [_ room-id]]
   (get-in db [:composer room-id :loaded-text] "")))
