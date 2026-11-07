(ns paradise.shared.utils.helpers
  (:require
   [net :as net]
   [clojure.string :as str]
   [promesa.core :as p]
   [hickory.core :as h]
   [hickory.render :as hr]
   [clojure.walk :as walk]
   [taoensso.timbre :as log]))

(def max-tag-nesting 100)

(def permitted-tags
  #{:font :del :h1 :h2 :h3 :h4 :h5 :h6 :blockquote :p :a :ul :ol :sup :sub
    :li :b :i :u :strong :em :strike :s :code :hr :br :div :table :thead
    :tbody :tr :th :td :caption :pre :span :img :details :summary})

(def url-schemes #{"https" "http" "ftp" "mailto" "magnet" "mention"})

(def permitted-attrs
  {:font       #{:style :color :data-bg-color :data-color :data-mx-bg-color :data-mx-color}
   :span       #{:style :data-bg-color :data-color :data-spoiler :data-maths :data-pill :data-ping
                 :data-mention-id :data-md :data-mx-bg-color :data-mx-color :data-mx-spoiler
                 :data-mx-maths :data-mx-pill :data-mx-ping}
   :div        #{:data-maths :data-mx-maths}
   :blockquote #{:data-md}
   :h1         #{:data-md}
   :h2         #{:data-md}
   :h3         #{:data-md}
   :h4         #{:data-md}
   :h5         #{:data-md}
   :h6         #{:data-md}
   :pre        #{:data-md :class}
   :ol         #{:start :type :data-md}
   :ul         #{:data-md}
   :a          #{:name :target :href :rel :data-md :data-mention-id :data-user-id :data-mx-user :class}
   :img        #{:width :height :alt :title :src :shortcode :data-emote :data-emote-id
                 :data-emote-uri :data-mx-emoticon :class :className}
   :code       #{:class :data-md}
   :strong     #{:data-md}
   :i          #{:data-md}
   :em         #{:data-md}
   :u          #{:data-md}
   :s          #{:data-md}
   :del        #{:data-md}})

(def non-text-tags #{:style :script :textarea :option :noscript :reply-quote :mx-reply})

(defn clean-shortcode [raw]
  (-> (if (keyword? raw) (name raw) (str raw))
      (str/replace #":" "")
      str/trim))

(defn format-shortcode [raw]
  (let [cleaned (clean-shortcode raw)]
    (if (seq cleaned)
      (str ":" cleaned ":")
      ":emote:")))

(defn media-uri? [s]
  (and (string? s)
       (boolean (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*://" s))))

(defn- parse-style-str [style-str]
  (if (string? style-str)
    (->> (str/split style-str #";")
         (remove str/blank?)
         (map #(str/split % #":"))
         (filter #(= 2 (count %)))
         (map (fn [[k v]] [(keyword (str/trim k)) (str/trim v)]))
         (into {}))
    style-str))

(defn- transform-font-span [attrs]
  (let [bg             (or (get attrs :data-bg-color) (get attrs :data-mx-bg-color))
        fg             (or (get attrs :data-color) (get attrs :data-mx-color))
        existing-style (parse-style-str (get attrs :style))
        style-map      (cond-> (or existing-style {})
                         bg (assoc :background-color bg)
                         fg (assoc :color fg))]
    (if (seq style-map)
      (assoc attrs :style style-map)
      attrs)))

(defn- transform-a [attrs]
  (assoc attrs :rel "noopener" :target "_blank"))

(defn- valid-url? [href]
  (if (string? href)
    (let [scheme (-> href (str/split #":" 2) first str/lower-case)]
      (contains? url-schemes scheme))
    false))


(defn- transform-img [attrs]
  (let [src    (or (get attrs :data-emote-uri) (get attrs :src) "")
        alt    (get attrs :alt)
        emote? (or (contains? attrs :data-emote)
                   (contains? attrs :data-emote-id)
                   (contains? attrs :data-mx-emoticon)
                   (and (media-uri? src) (not (valid-url? src))))]
    (if (and (media-uri? src) (or emote? (valid-url? src)))
      {:tag   "comp:paradise.media.component/media-native"
       :attrs {:uri       src
;;               :mxc       src
               :emote-id  (get attrs :data-emote-id)
               :class     "timeline-emotes"
               :className "timeline-emotes"
               :alt       alt}}
      {:tag     :a
       :attrs   {:href src :rel "noopener" :target "_blank"}
       :content [(or alt src)]})))

(defn- filter-code-classes [attrs]
  (if-let [cls (:class attrs)]
    (let [classes (str/split cls #"\s+")
          valid   (filter #(str/starts-with? % "language-") classes)]
      (if (seq valid)
        (assoc attrs :class (str/join " " valid))
        (dissoc attrs :class)))
    attrs))

(defn sanitize-nodes [nodes depth]
  (if (> depth max-tag-nesting)
    []
    (into []
          (mapcat
           (fn [node]
             (cond
               (string? node)
               [node]

               (and (map? node) (= (:type node) :element))
               (let [{:keys [tag attrs content]} node]
                 (cond
                   (contains? non-text-tags tag)
                   []

                   (contains? permitted-tags tag)
                   (let [allowed-keys (get permitted-attrs tag #{})
                         clean-attrs  (select-keys attrs allowed-keys)
                         [final-tag final-attrs transformed-content]
                         (case tag
                           (:font :span) [tag (transform-font-span clean-attrs) nil]
                           :a            [tag (transform-a clean-attrs) nil]
                           :code         [tag (filter-code-classes clean-attrs) nil]
                           :img          (let [{t :tag a :attrs c :content} (transform-img clean-attrs)]
                                           [t a c])
                           [tag clean-attrs nil])
                         final-attrs  (if (and (= final-tag :a)
                                               (not (valid-url? (:href final-attrs))))
                                        (dissoc final-attrs :href)
                                        final-attrs)
                         children     (or transformed-content
                                          (sanitize-nodes content (inc depth)))]
                     [(if (seq final-attrs)
                        (into [final-tag final-attrs] children)
                        (into [final-tag] children))])

                   :else
                   (sanitize-nodes content depth)))

               :else []))
           nodes))))

(defn sanitize-custom-html [raw-html]
  (when raw-html
    (let [html-str      (str raw-html)
          raw-fragments (h/parse-fragment html-str)
          hickory-maps  (map h/as-hickory raw-fragments)]
      (sanitize-nodes hickory-maps 0))))

(defn sanitize-text [raw-text]
  (when raw-text
    (str/escape (str raw-text) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"})))

(defn linkify-text [text]
  (if (str/blank? text)
    [text]
    (let [pattern #"https?://[^\s]+"
          parts   (str/split text pattern -1)
          matches (re-seq pattern text)]
      (if (empty? matches)
        [text]
        (remove nil?
                (mapcat
                 (fn [i part]
                   (let [url (nth matches i nil)]
                     (if url
                       [part [:a {:href url :target "_blank" :rel "noopener noreferrer"} url]]
                       [part])))
                 (range (count parts))
                 parts))))))

(def block-level-tags #{:p :div :h1 :h2 :h3 :h4 :h5 :h6 :blockquote :li :tr})

(defn hiccup->text [node]
  (cond
    (string? node) node
    (vector? node)
    (let [tag        (first node)
          has-attrs? (map? (second node))
          attrs      (if has-attrs? (second node) {})
          children   (if has-attrs? (drop 2 node) (drop 1 node))
          inner-text (str/join "" (map hiccup->text children))]
      (cond
        (= tag :br) "\n"
        (contains? block-level-tags tag) (str inner-text "\n")
        (or (:uri attrs) (= tag :img) (= tag "media-native") (= tag "comp:paradise.media.component/media-native"))
        (str " " (or (:alt attrs) "[e]") " ")
        :else inner-text))
    (sequential? node) (str/join "" (map hiccup->text node))
    :else ""))

(defn process-raw-event [e source room-id]
  (let [html-txt     (or (get-in e [:content :inner :content :html]) "")
        cleaned-html (if (seq html-txt)
                       (str/replace html-txt #"(?i)<br[^>]*>\s*(?=</(?:p|div|h[1-6]|blockquote|li)>)" "")
                       "")
        safe-hiccup  (when (seq cleaned-html)
                       (sanitize-custom-html cleaned-html))
        plain-text   (if safe-hiccup
                       (hiccup->text safe-hiccup)
                       (or (:body e)
                           (get-in e [:content :body])
                           (get-in e [:content :inner :content :body])
                           (get-in e [:content :caption])
                           (get-in e [:content :inner :content :caption])
                           ""))]
    (-> e
        (assoc :timeline-source source)
        (assoc :room-id room-id)
        (assoc :clean-hiccup safe-hiccup)
        (assoc :clean-text plain-text)
        (update :type keyword)
        (assoc :raw e))))

(defonce !file-registry (atom {}))

(defn register-file! [file]
  (let [id (str (random-uuid))]
    (log/info "Registering native File in memory registry. ID:" id "Name:" (.-name file))
    (swap! !file-registry assoc id file)
    id))

(defn get-file [id]
  (let [f (get @!file-registry id)]
    (log/info "Fetching file from registry. ID:" id "Found? ->" (some? f))
    f))

(defn unregister-file! [id]
  (log/info "Unregistering file from memory registry. ID:" id)
  (swap! !file-registry dissoc id))

(defn file->array-buffer [f]
  (p/create
   (fn [resolve-fn reject]
     (try
       (if-not f
         (resolve-fn (js/ArrayBuffer. 0))
         (if (.-arrayBuffer f)
           (-> (.arrayBuffer f) (.then resolve-fn) (.catch reject))
           (let [reader (js/FileReader.)]
             (set! (.-onload reader) #(resolve-fn (.-result reader)))
             (set! (.-onerror reader) #(reject (.-error reader)))
             (.readAsArrayBuffer reader f))))
       (catch :default e
         (reject e))))))

(defn extract-metadata [raw-file]
  (p/create
   (fn [resolve-fn _]
     (try
       (if-not raw-file
         (do
           (log/warn "Extract-metadata received an empty or undefined file object. Bailing.")
           (resolve-fn nil))
         (let [mime      (or (.-type raw-file) "application/octet-stream")
               filename  (or (.-name raw-file) (str "upload-" (random-uuid)))
               size      (or (.-size raw-file) 0)
               is-image? (str/starts-with? mime "image/")
               is-video? (str/starts-with? mime "video/")]
           (log/info "Safe extract-metadata -> Name:" filename "Mime:" mime "Size:" size)
           (let [fid       (register-file! raw-file)
                 preview   (try (js/URL.createObjectURL raw-file)
                                (catch :default e
                                  (log/warn "Failed to create object URL" e)
                                  nil))
                 base-att  {:file-id     fid
                            :mime        mime
                            :filename    filename
                            :size        size
                            :preview-url preview}]
             (if (and preview (or is-image? is-video?))
               (let [el (if is-image? (js/Image.) (js/document.createElement "video"))]
                 (set! (.-onerror el)
                       (fn [e]
                         (log/warn "Failed to load media to extract dimensions." e)
                         (resolve-fn base-att)))
                 (if is-image?
                   (set! (.-onload el)
                         (fn []
                           (resolve-fn (assoc base-att :width (.-width el) :height (.-height el)))))
                   (do
                     (set! (.-preload el) "metadata")
                     (set! (.-onloadedmetadata el)
                           (fn []
                             (resolve-fn (assoc base-att :width (.-videoWidth el) :height (.-videoHeight el)))))))
                 (set! (.-src el) preview))
               (resolve-fn base-att)))))
       (catch :default e
         (log/error "Catastrophic error in extract-metadata:" e)
         (resolve-fn nil))))))

(defonce relative-formatter
  (js/Intl.RelativeTimeFormat. js/undefined #js {:numeric "auto"}))

(defonce date-formatter
  (js/Intl.DateTimeFormat. js/undefined #js {:month "long"
                                             :day "numeric"
                                             :year "numeric"}))

(defn format-divider-date [ts]
  (let [date         (js/Date. ts)
        today        (js/Date.)
        is-today     (= (.toDateString date) (.toDateString today))
        yesterday    (doto (js/Date.) (.setDate (- (.getDate today) 1)))
        is-yesterday (= (.toDateString date) (.toDateString yesterday))]
    (cond
      is-today     (.format relative-formatter 0 "day")
      is-yesterday (.format relative-formatter -1 "day")
      :else        (.format date-formatter date))))

(defn format-time [ts]
  (when ts
    (let [date (js/Date. ts)]
      (.toLocaleTimeString date js/undefined #js {:hour "numeric" :minute "2-digit"}))))

(defn truncate-name [name max-len]
  (if (> (count name) max-len)
    (str (subs name 0 max-len) "...")
    name))

(defn join-names [names]
  (let [cnt (count names)]
    (case cnt
      0 ""
      1 (first names)
      2 (str (first names) " and " (second names))
      3 (str (first names) ", " (second names) ", and " (first (last names)))
      (str (first names) ", " (second names) ", and " (- cnt 2) " others"))))


(defn format-readers [names]
  (let [joined (join-names names)]
    (if (empty? joined)
      nil
      (str joined (if (= 1 (count names))
                    " is following the conversation"
                    " are following")))))

(defn get-status-string [tr type names]
  (let [cnt (count names)
        base-path (if (= type :typing)
                    "container.timeline.status.typing"
                    "container.timeline.status.reading")]
    (case cnt
      0 ""
      1 (tr [(keyword base-path "one")] [(truncate-name (first names) 16)])
      2 (tr [(keyword base-path "two")] [(truncate-name (first names) 16) (truncate-name (second names) 16)])
      3 (tr [(keyword base-path "three")] [(truncate-name (first names) 16) (truncate-name (second names) 16) (truncate-name (nth names 2) 16)])
      (tr [(keyword base-path "many")] [(truncate-name (first names) 16) (truncate-name (second names) 16) (- cnt 2)]))))

(defn fetch-state-event [homeserver token room-id event-type state-key]
  (let [clean-hs (str/replace homeserver #"/+$" "")
        key-path (if (empty? state-key) "" (str "/" state-key))
        url      (str clean-hs "/_matrix/client/v3/rooms/" room-id "/state/" event-type key-path)]
    (-> (p/let [resp (net/fetch url #js {:headers #js {:Authorization (str "Bearer " token)}})]
          (when (.-ok resp)
            (.json resp)))
        (p/catch (constantly nil)))))

(defn fetch-room-state
  "Fetches room state and applies an optional transformation function (predicate/transducer).
   - If event-type is nil, fetches the full state array.
   - xf is a function that receives the clojurized data."
  ([homeserver token room-id]
   (fetch-room-state homeserver token room-id nil nil identity))
  ([homeserver token room-id event-type state-key xf]
   (let [clean-hs (str/replace homeserver #"/+$" "")
         key-path (if (empty? state-key) "" (str "/" state-key))
         url      (str clean-hs "/_matrix/client/v3/rooms/" room-id "/state"
                       (when event-type (str "/" event-type key-path)))]
     (-> (p/let [resp (net/fetch url #js {:headers #js {:Authorization (str "Bearer " token)}})]
           (when (.-ok resp)
             (p/let [json (.json resp)
                     data (js->clj json :keywordize-keys true)]
               (xf data))))
         (p/catch (fn [err]
                    (log/error "State fetch failed:" url err)
                    nil))))))
