(ns paradise.ui.input.composer
  (:require
   [clojure.string :as str]
   [paradise.shared.utils.helpers :refer [clean-shortcode format-shortcode]]
   [paradise.media.component :refer [media]]
   [paradise.ui.input.autocomplete :refer [user-mention-options emoji-suggestion-options]]
   ["react" :as react]
   [taoensso.timbre :as log]
   [reagent.core :as r]
   ["@tiptap/react" :refer [useEditor EditorContent ReactNodeViewRenderer NodeViewWrapper]]
   ["@tiptap/starter-kit" :default StarterKit]
   ["@tiptap/extension-placeholder" :default Placeholder]
   ["@tiptap/extension-mention" :default Mention]
   ["@tiptap/extension-link" :default Link]
   ["@tiptap/core" :refer [Extension Node mergeAttributes]]
   ["prosemirror-state" :refer [Plugin PluginKey]]))

(def emoji-trigger
  (-> Mention
      (.extend #js {:name "emojiTrigger"})
      (.configure #js {:suggestion (emoji-suggestion-options)})))

(def emoji-trigger
  (-> Mention
      (.extend #js {:name "emojiTrigger"})
      (.configure #js {:suggestion (emoji-suggestion-options)})))

(def user-trigger
  (-> Mention
      (.extend #js {:name "userMention"
                    :addAttributes (fn []
                                     #js {:id    #js {:default nil}
                                          :label #js {:default nil}
                                          :uri   #js {:default nil}})
                    :renderHTML (fn [^js props]
                                  (let [node  (.-node props)
                                        id    (.. node -attrs -id)
                                        label (or (.. node -attrs -label) id)
                                        uri   (.. node -attrs -uri)
                                        ]
                                    #js ["a" #js {:href            uri
                                                  :class           "user-mention"
                                                  :data-mention-id id
                                                  :data-mx-user    id}
                                         (str "@" label)]))})
      (.configure #js {:suggestion (user-mention-options)})))

(defn renderHtml [^js props]
  (let [attrs        (.-HTMLAttributes props)
        display-code (format-shortcode (aget attrs "shortcode"))
        uri          (or (aget attrs "uri") (aget attrs "src"))
        emote-id     (aget attrs "id")]
    #js ["img" (mergeAttributes
                attrs
                #js {"data-emote"     true
                     "data-emote-id"  emote-id
                     "data-emote-uri" uri
                     "class"          "chat-input-emote"
                     "alt"            display-code
                     "shortcode"      display-code
                     "title"          display-code
                     "style"          "height: 1.5em; width: auto; vertical-align: middle; display: inline-block;"})]))

(defn prepare-html-for-editor [raw-html]
  (if (string? raw-html) raw-html ""))

(defn editor->ast [editor]
  (when editor
    (js->clj (.. editor getJSON -content) :keywordize-keys true)))

(defn get-formatted-body [editor]
  (let [json (.. editor getJSON -content)
        node->html (fn node->html [node]
                     (let [type  (.-type node)
                           attrs (.-attrs node)
                           marks (.-marks node)
                           base-content
                           (cond
                             (= type "customEmote")
                             (let [shortcode (format-shortcode (aget attrs "shortcode"))
                                   emote-id  (aget attrs "id")
                                   raw-uri   (or (aget attrs "uri")
                                                 (aget attrs "src"))]
                               (str "<img data-emote=\"\""
                                    (when emote-id (str " data-emote-id=\"" emote-id "\""))
                                    " data-emote-uri=\"" raw-uri "\""
                                    " src=\"" raw-uri "\""
                                    " alt=\"" shortcode "\" title=\"" shortcode "\" height=\"32\">"))

                             (= type "userMention")
                             (let [id    (aget attrs "id")
                                   label (or (aget attrs "label") id)
                                   uri   (or (aget attrs "uri") (str "mention://" id))]
                               (str "<a href=\"" uri "\" data-mention-id=\"" id "\">@" label "</a>"))

                             (= type "hardBreak") "<br>"
                             (= type "text") (.-text node)
                             :else "")
                           with-marks
                           (if marks
                             (reduce (fn [html mark]
                                       (case (.-type mark)
                                         "link"   (str "<a href=\"" (.. mark -attrs -href) "\">" html "</a>")
                                         "bold"   (str "<strong>" html "</strong>")
                                         "italic" (str "<em>" html "</em>")
                                         "strike" (str "<del>" html "</del>")
                                         "code"   (str "<code>" html "</code>")
                                         html))
                                     base-content
                                     (js/Array.from marks))
                             base-content)]
                       with-marks))]
    (str/join ""
              (map (fn [p]
                     (str "<p>"
                          (str/join "" (map node->html (js/Array.from (or (.-content p) #js []))))
                          "</p>"))
                   (js/Array.from json)))))

(defn extract-editor-payload [editor]
  (when editor
    {:text (.getText editor)
     :html (get-formatted-body editor)
     :ast  (editor->ast editor)}))


(defn emote-node-view [props]
  (let [node  (:node props)
        attrs (.-attrs node)
        uri   (or (aget attrs "uri") (aget attrs "src"))
        alt   (format-shortcode (aget attrs "shortcode"))]
    [:> NodeViewWrapper {:as "span" :style {:display "inline-block" :vertical-align "middle"}}
     [media
      {:uri   uri
       :class "chat-input-emote"
       :style {:height "1.5em" :width "auto"}
       :alt   alt}]]))

(defn- parse-emote-dom-node [^js node]
  (let [src (.getAttribute node "src")
        uri (or (.getAttribute node "data-emote-uri")
                src)]
    #js {:id        (.getAttribute node "data-emote-id")
         :uri       uri
         :src       (or uri src)
         :shortcode (clean-shortcode
                     (or (.getAttribute node "shortcode")
                         (.getAttribute node "alt")
                         (.getAttribute node "title")))}))

(def custom-emote
  (.create Node
   #js {:name          "customEmote"
        :inline        true
        :group         "inline"
        :selectable    true
        :atom          true
        :addAttributes (fn []
                         #js {:id        #js {:default nil}
                              :uri       #js {:default nil}
                              :src       #js {:default nil}
                              :shortcode #js {:default nil}})
        :addNodeView   (fn []
                         (ReactNodeViewRenderer (r/reactify-component emote-node-view)))
        :renderText    (fn [^js props]
                         (format-shortcode (.. props -node -attrs -shortcode)))
        :parseHTML     (fn []
                         #js [#js {:tag "img[data-emote]" :getAttrs parse-emote-dom-node}
                              #js {:tag "img"             :getAttrs parse-emote-dom-node}])
        :renderHTML    renderHtml}))

(defn safe-extract-files [event]
  (let [dt     (.-dataTransfer event)
        cd     (.-clipboardData event)
        source (or dt cd)
        arr    (js/Array.)]
    (when source
      (let [files (.-files source)
            items (.-items source)]
        (if (and items (pos? (.-length items)))
          (doseq [i (range (.-length items))]
            (let [item (aget items i)]
              (when (= (.-kind item) "file")
                (when-let [f (.getAsFile item)]
                  (.push arr f)))))
          (when (and files (pos? (.-length files)))
            (doseq [i (range (.-length files))]
              (when-let [f (aget files i)]
                (.push arr f)))))))
    arr))

(def file-drop-extension
  (.create Extension
    #js {:name "fileDropHandler"
         :addOptions (fn [] #js {:onFiles nil})
         :addProseMirrorPlugins
         (fn []
           (this-as this
             #js [(new Plugin
                       #js {:key (new PluginKey "fileDropHandler")
                            :props #js {:handleDOMEvents
                                        #js {:drop (fn [_view event]
                                                     (try
                                                       (let [files (safe-extract-files event)]
                                                         (if (pos? (.-length files))
                                                           (do
                                                             (.preventDefault event)
                                                             (when-let [on-files (.. this -options -onFiles)]
                                                               (on-files files))
                                                             true)
                                                           false))
                                                       (catch :default e
                                                         (log/error "Error processing drop event:" e)
                                                         false)))
                                             :paste (fn [view event]
                                                      (try
                                                        (let [files (safe-extract-files event)]
                                                          (if (pos? (.-length files))
                                                            (do
                                                              (.preventDefault event)
                                                              (.focus view)
                                                              (when-let [on-files (.. this -options -onFiles)]
                                                                (on-files files))
                                                              true)
                                                            false))
                                                        (catch :default e
                                                          (log/error "Error processing paste event:" e)
                                                          false)))}}})]))}))

(def submit-extension
  (.create Extension
           #js {:name "submitExtension"
                :addOptions (fn [] #js {:onSend nil})
                :addKeyboardShortcuts
                (fn []
                  (this-as this
                    #js {"Enter" (fn [context]
                                   (let [editor  (.-editor context)
                                         text    (.getText editor)
                                         html    (get-formatted-body editor)
                                         on-send (.. this -options -onSend)]
                                     (when (and on-send (on-send text html))
                                       (.commands.clearContent editor true))
                                     true))
                         "Shift-Enter" (fn [] false)}))}))

(defn tiptap-component [^js props]
  (let [active-id   (.. props -children -activeId)
        on-send     (.. props -children -onSend)
        on-files    (.. props -children -onFiles)
        on-change   (.. props -children -onChange)
        loaded-text (.. props -children -loadedText)
        cached-html (.. props -children -cachedHtml)
        placeholder (.. props -children -placeholder)
        on-ready    (.. props -children -onEditorReady)
        latest-cbs  (react/useRef #js {:onSend on-send :onFiles on-files})
        _           (set! (.-current latest-cbs) #js {:onSend on-send :onFiles on-files})
        editor      (useEditor
                     #js {:extensions #js [(.configure StarterKit #js {})
                                           (.configure Placeholder #js {:placeholder placeholder})
                                           (.configure submit-extension
                                                       #js {:onSend (fn [text html]
                                                                      (let [cb (.-onSend (.-current latest-cbs))]
                                                                        (when cb (cb text html))))})
                                           (.configure file-drop-extension
                                                       #js {:onFiles (fn [files]
                                                                       (let [cb (.-onFiles (.-current latest-cbs))]
                                                                         (when cb (cb files))))})
                                           (.configure custom-emote #js {})
                                           emoji-trigger
                                           user-trigger
                                           (.configure Link #js {:openOnClick false
                                                                 :autolink    true
                                                                 :linkOnPaste true})]
                          :content     (or cached-html loaded-text "")
                          :editable    (boolean active-id)
                          :onUpdate    (fn [ctx]
                                         (when on-change
                                           (let [ed (.-editor ctx)]
                                             (on-change (.getText ed)
                                                        (get-formatted-body ed)))))
                          :onCreate    (fn [ctx]
                                         (when on-ready
                                           (on-ready (.-editor ctx))))
                          :editorProps #js {:attributes #js {:class "tiptap-editor-surface"}}}
                     #js [active-id])]

    (react/useEffect
     (fn []
       (when (and editor loaded-text (.-schema editor))
         (let [current-html (.getHTML editor)]
           (when (not= current-html loaded-text)
             (.commands.setContent editor loaded-text))))
       js/undefined)
     #js [editor loaded-text])

    (if-not editor
      (react/createElement "div" #js {:className "timeline-input-wrapper"}
                           (react/createElement "div" #js {:className "tiptap-editor-surface"}))
      (react/createElement "div"
                           #js {:key       (str "editor-surface-" active-id)
                                :className "timeline-input-wrapper"
                                :onClick   (fn [] (.commands.focus editor))}
                           (react/createElement EditorContent #js {:editor editor})))))