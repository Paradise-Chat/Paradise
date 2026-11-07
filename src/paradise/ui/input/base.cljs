(ns paradise.ui.input.base
  (:require [promesa.core :as p]
            [cljs.core.async.interop :refer-macros [<p!]]
            [re-frame.core :as re-frame]
            [taoensso.timbre :as log]
            [clojure.string :as str]
            [paradise.ui.input.drafts :refer [attachment-preview]]
            [paradise.ui.input.composer :refer [tiptap-component extract-editor-payload prepare-html-for-editor]]
            [paradise.ui.input.emotes :refer [emoji-sticker-board]]
            [paradise.ui.input.autocomplete :refer [suggestion-menu]]
            [paradise.ui.global :refer [request-item-redraw! !active-popover]]
            [reagent.core :as r]
            [paradise.shared.utils.helpers :refer [clean-shortcode unregister-file! file->array-buffer register-file! get-file truncate-name extract-metadata]]
            [paradise.shared.utils.svg :as icons]
            [cljs.core.async :refer [go <!]]
            [cljs-workers.core :as main]
            [cljs-workers.mesh :as mesh]
            [paradise.shared.client.state :as state]
            [paradise.shared.plugins :as plugins]))

(defn handle-files! [room-id files]
  (doseq [raw-file files]
    (when raw-file
      (-> (extract-metadata raw-file)
          (p/then (fn [attachment]
                    (when attachment
                      (re-frame/dispatch [:composer/add-attachment room-id attachment]))))
          (p/catch (fn [e]
                     (log/error "Promise Rejection in drop handler metadata:" e)))))))

(re-frame/reg-event-fx
 :sdk/handle-file-drop
 (fn [_ [_ room-id files]]
   (handle-files! room-id files)
   {}))

(re-frame/reg-event-fx
 :sdk/upload-media
 (fn [{:keys [db]} [_ file on-success-event]]
   (if file
     (let [mime (.-type file)]
       (if-let [pool @state/!engine-pool]
         (go
           (try
             (let [ab  (<p! (file->array-buffer file))
                   res (<! (main/do-with-pool! pool {:handler   :upload-media
                                                     :arguments {:mime mime :buffer ab}}))]
               (if (= (:status res) "success")
                 (let [media-uri (or (:uri res) (:url res))]
                   (re-frame/dispatch (conj on-success-event media-uri)))
                 (log/error "Worker upload failed:" (:msg res))))
             (catch :default e
               (log/error "Exception generating arrayBuffer or worker:" (str e)))))
         (log/error "No engine pool for upload"))
       {:db (assoc db :uploading-files? true)})
     (do (log/warn "Missing file for upload") {}))))

(re-frame/reg-event-fx
 :composer/submit
 (fn [{:keys [db]} [_ room-id payload-or-text maybe-html]]
   (let [{:keys [text html ast]} (if (map? payload-or-text)
                                   payload-or-text
                                   {:text payload-or-text :html maybe-html :ast nil})
         attachments    (get-in db [:drafts room-id :attachments])
         raw-context    (get-in db [:input-context room-id])
         worker-context (when raw-context
                          {:mode        (:mode raw-context)
                           :target      (get-in raw-context [:target :id])
                           :msg-tag     (get-in raw-context [:target :content :inner :tag])
                           :ffi-content (get-in raw-context [:target :content :inner :content :ffi-content])})
         pool           @state/!engine-pool]
     (if (seq attachments)
       (go
         (try
           (let [worker-atts
                 (<p! (p/all (map (fn [att]
                                    (p/let [f  (get-file (:file-id att))
                                            ab (if f (file->array-buffer f) (js/Promise.resolve (js/ArrayBuffer. 0)))]
                                      (assoc att :buffer ab)))
                                  attachments)))]
             (doseq [att attachments]
               (when (:preview-url att) (js/URL.revokeObjectURL (:preview-url att)))
               (when (:file-id att) (unregister-file! (:file-id att))))
             (let [res (<! (main/do-with-pool! pool {:handler   :send-attachments
                                                     :arguments {:room-id     room-id
                                                                 :attachments worker-atts
                                                                 :text        text
                                                                 :html        html
                                                                 :ast         ast}}))]
               (when-not (#{"success" :success} (:status res))
                 (log/error "Failed to send attachments:" (:msg res)))))
           (catch :default e
             (log/error "Exception in send-attachments Go block:" (str e)))))
       (go
         (try
           (log/info "Dispatching standard :send-message to worker...")
           (let [res (<! (main/do-with-pool! pool {:handler   :send-message
                                                   :arguments {:room-id room-id
                                                               :text    text
                                                               :html    html
                                                               :ast     ast
                                                               :context worker-context}}))]
             (log/info "Worker :send-message returned:" res)
             (when-not (#{"success" :success} (:status res))
               (log/error "Failed to send message:" (:msg res))))
           (catch :default e
             (log/error "Exception in send-message Go block:" (str e))))))
     {:db       (-> db
                    (assoc-in [:composer room-id] {:text "" :html "" :loaded-text "" :uploading? false})
                    (assoc-in [:drafts room-id :attachments] [])
                    (update :input-context dissoc room-id))
      :dispatch [:composer/persist-draft room-id]})))

(re-frame/reg-event-fx
 :sdk/send-sticker
 (fn [_ [_ room-id sticker-or-uri & [alt-text info]]]
   (let [{:keys [id uri shortcode] :as sticker-map}
         (if (map? sticker-or-uri)
           sticker-or-uri
           {:uri sticker-or-uri :shortcode alt-text :info info})]
     (go
       (let [res (<! (mesh/do-with-thread! :engine-pool
                                           {:handler   :send-raw-sticker
                                            :arguments {:room-id    room-id
                                                        :sticker-id id
                                                        :uri        uri
                                                        :alt-text   shortcode
                                                        :info       (:info sticker-map)}}))]
         (if (= (:status res) "success")
           (log/info "Sticker sent!")
           (log/error "Sticker failed:" (:msg res))))))
   {}))

(re-frame/reg-event-db
 :sdk/upload-complete
 (fn [db _]
   (assoc db :uploading-files? false)))

(re-frame/reg-sub
 :input/uploading?
 (fn [db _]
   (:uploading-files? db false)))

(re-frame/reg-sub
 :input/is-editing-event?
 (fn [db [_ room-id event-id]]
   (let [ctx (get-in db [:input-context room-id])]
     (and (= (:mode ctx) :edit)
          (= (-> ctx :target :id) event-id)))))

(re-frame/reg-event-db
 :input/set-context
 (fn [db [_ room-id mode target-item]]
   (when (= mode :edit)
     (request-item-redraw! room-id (:id target-item)))
   (assoc-in db [:input-context room-id] {:mode mode :target target-item})))

(re-frame/reg-event-db
 :input/clear-context
 (fn [db [_ room-id]]
   (let [context (get-in db [:input-context room-id])]
     (when (= (:mode context) :edit)
       (request-item-redraw! room-id (:id (:target context)))))
   (update db :input-context dissoc room-id)))

(re-frame/reg-sub
 :input/context
 (fn [db [_ room-id]]
   (get-in db [:input-context room-id])))

(re-frame/reg-fx
 :input/focus-composer
 (fn [should-focus?]
   (when should-focus?
     (js/setTimeout
      (fn []
        (when-let [composer (.querySelector js/document ".ProseMirror")]
          (.focus composer)))
      50))))

(defn inline-emoji-open? []
  (= (:id @!active-popover) :inline-emoji))

(defn ^:react inline-editor [item active-id]
  (r/with-let [!editor (r/atom nil)]
    (fn [item active-id]
      (let [m-content    (get-in item [:content :inner :content])
            raw-html     (:html m-content)
            initial-text (if (seq raw-html)
                           (prepare-html-for-editor raw-html)
                           (or (:body m-content) ""))
            save-edit!   (fn []
                           (when-let [ed @!editor]
                             (let [{:keys [text html]} (extract-editor-payload ed)]
                               (re-frame/dispatch [:msg/edit active-id item text html])
                               (re-frame/dispatch [:input/clear-context active-id]))))]
        [:div.inline-editor-wrapper
         [:div.inline-editor-box
          [:> tiptap-component
           #js {:activeId      active-id
                :loadedText    initial-text
                :onEditorReady #(reset! !editor %)
                :onSend        (fn [_ _] (save-edit!))}]]
         [:div.inline-editor-hint
          "escape to "
          [:span.link-btn {:on-click #(re-frame/dispatch [:input/clear-context active-id])} "cancel"]
          " \u2022 enter to "
          [:span.link-btn {:on-click save-edit!} "save"]]]))))

(defn ^:ui timeline-send-button [{:keys [submit-message! active-id]}]
  [:button.timeline-send-btn
   {:on-click (fn [e]
                (.preventDefault e)
                (.stopPropagation e)
                (submit-message!))}
   [plugins/plugin-slot :composer-send {:room-id active-id}]
   [icons/send]])

(defn ^:ui timeline-emoji-button [{:keys [on-click room-id]}]
  [:button.timeline-emoji-btn
   {:on-click (fn [e]
                (.stopPropagation e)
                (when on-click (on-click)))}
   [icons/smiley]
   [plugins/plugin-slot :composer-emojis {:room-id room-id}]])

(defn ^:ui message-input []
  (r/with-let [!editor      (r/atom nil)
               !sug-command (r/atom nil)]
    (fn []
      (let [active-id       @(re-frame/subscribe [:rooms/active-id])
            uploading?      @(re-frame/subscribe [:input/uploading?])
            attachments     @(re-frame/subscribe [:composer/attachments active-id])
            loaded-text     @(re-frame/subscribe [:composer/loaded-text active-id])
            cached-html     @(re-frame/subscribe [:composer/cached-html active-id])
            context         @(re-frame/subscribe [:input/context active-id])
            picker-open?    (inline-emoji-open?)
            tr              @(re-frame/subscribe [:i18n/tr])
            submit-message! (fn []
                              (when-let [ed @!editor]
                                (let [{:keys [text html] :as payload} (extract-editor-payload ed)
                                      can-send? (or (not (str/blank? text))
                                                    (str/includes? html "<img")
                                                    (seq attachments))]
                                  (if can-send?
                                    (do
                                      (re-frame/dispatch [:composer/submit active-id payload])
                                      (-> ed .chain .clearContent .run)
                                      true)
                                    false))))]
        [:div.timeline-input-outer
         [suggestion-menu]
         (when picker-open?
           [emoji-sticker-board
            {:inline?  true
             :on-close #(re-frame/dispatch [:ui/close-popover])
             :on-send-sticker
             (fn [sticker-payload]
               (re-frame/dispatch [:sdk/send-sticker active-id sticker-payload])
               (re-frame/dispatch [:ui/close-popover]))
             :on-insert-native
             (fn [unicode-char]
               (when-let [ed @!editor]
                 (-> ed .chain .focus (.insertContent unicode-char) .run)))
             :on-insert-emoji
             (fn [emote-or-shortcode & [maybe-url]]
               (when-let [ed @!editor]
                 (let [{:keys [id shortcode uri]} (if (map? emote-or-shortcode)
                                                    emote-or-shortcode
                                                    {:shortcode emote-or-shortcode :uri maybe-url})
                       clean-sc                   (clean-shortcode shortcode)]
                   (-> ed .chain .focus
                       (.insertContent #js {:type  "customEmote"
                                            :attrs #js {:id        id
                                                        :shortcode clean-sc
                                                        :uri       uri
                                                        :src       uri}})
                       .run))))}])
         (when (and context (= (:mode context) :reply))
           (let [sender-name (truncate-name (-> context :target :sender-name) 32)]
             [:div.input-context-banner
              [:div.context-info
               [:span (tr [:composer/replying-to] [sender-name])]]
              [:button.context-cancel-btn
               {:on-click #(re-frame/dispatch [:input/clear-context active-id])} [icons/exit]]]))
         [:div.timeline-input-wrapper
          (when (seq attachments)
            [:div.composer-attachments
             (doall
              (map-indexed (fn [idx att]
                             ^{:key (str "att-" idx)}
                             [attachment-preview active-id att idx])
                           attachments))])
          (when uploading?
            ^{:key "upload-indicator"}
            [:div.upload-indicator
             [:span.upload-text (tr [:composer/uploading])]
             [:div.upload-progress-bar [:div.upload-progress-fill]]])
          ^{:key "composer-input-row"}
          [:div.timeline-input-row
           [:label.timeline-upload-btn
            {:title (tr [:composer/upload-tooltip])}
            [:input {:type      "file"
                     :multiple  true
                     :style     {:display "none"}
                     :on-change (fn [e]
                                  (let [files      (.. e -target -files)
                                        file-array (reduce (fn [acc i]
                                                             (if-let [f (aget files i)]
                                                               (conj acc f)
                                                               acc))
                                                           []
                                                           (range (or (.-length files) 0)))]
                                    (when (seq file-array)
                                      (handle-files! active-id file-array))
                                    (set! (.-value (.-target e)) "")))}]
            [icons/plus]]
           [:div.timeline-editor-container
            [:> tiptap-component
             #js {:activeId          active-id
                  :key               (str "editor-" active-id)
                  :loadedText        loaded-text
                  :cachedHtml        cached-html
                  :onChange          (fn [text html]
                                       (re-frame/dispatch [:composer/on-change active-id text html]))
                  :onSend            (fn [_ _] (submit-message!))
                  :onFiles           (fn [files]
                                       (let [file-array (reduce (fn [acc i]
                                                                  (if-let [f (aget files i)]
                                                                    (conj acc f)
                                                                    acc))
                                                                []
                                                                (range (or (.-length files) 0)))]
                                         (when (seq file-array)
                                           (handle-files! active-id file-array))))
                  :placeholder       (tr [:composer/placeholder])
                  :onEditorReady     #(reset! !editor %)
                  :onSuggestionStart (fn [cmd] (reset! !sug-command cmd))
                  :onSuggestionExit  (fn [] (reset! !sug-command nil))}]]
           [plugins/plugin-slot :composer-actions {:room-id active-id}]
           [timeline-emoji-button {:on-click #(if picker-open?
                                                (re-frame/dispatch [:ui/close-popover])
                                                (re-frame/dispatch [:ui/open-popover :inline-emoji {}]))
                                   :active?  picker-open?
                                   :room-id  active-id}]
           [timeline-send-button {:submit-message! submit-message!
                                  :active-id       active-id
                                  :editor          @!editor
                                  :attachments     attachments}]]]]))))
