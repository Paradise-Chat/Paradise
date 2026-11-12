(ns ^:virtualizer/skip paradise.virtualizer.spec
  (:require [clojure.string :as str]
            [virtualizer.protocol :as proto]
            [virtualizer.worker.ast :as ast]
            [paradise.virtualizer.state :as state]
            [paradise.ui.container.timeline.item :refer [event-tile-render]]
            [paradise.shared.utils.helpers :refer [format-divider-date
                                                   process-raw-event
                                                   sanitize-custom-html
                                                   linkify-text]]))

(defonce !sanitize-cache (atom {}))

(defn memoized-sanitize [html]
  (if-let [hit (get @!sanitize-cache html)]
    hit
    (let [res (sanitize-custom-html html)]
      (swap! !sanitize-cache
             (fn [m]
               (let [m' (assoc m html res)]
                 (if (> (count m') 300)
                   (dissoc m' (first (keys m')))
                   m'))))
      res)))

(defn get-event-id [e]
  (cond
    (and (= (:type e) :virtual) (str/includes? (str (:tag e)) "Date"))
    (str "virtual-divider-" (format-divider-date (:ts e)) "-" (:ts e))

    (and (= (:type e) :virtual) (str/includes? (str (:tag e)) "Read"))
    "read-marker"

    (= (:type e) :virtual)
    (str "virtual-" (:tag e) "-" (:ts e))

    (not-empty (:id e))
    (:id e)

    :else
    (:internal-id e)))

(defn extract-event-id [e]
  (let [e-kw (if (map? e) (update e :type keyword) e)]
    (or (get-event-id e-kw)
        (:event_id e)
        (:event-id e)
        (:internal-id e))))

(defn extract-first-url [text]
  (when text
    (when-let [match (re-find #"https?://[^\s\"'<>]+" text)]
      (str/replace match #"[.,:;!?]$" ""))))

(defn calc-reply-height [content theme-metrics]
  (if (:in-reply-to content) (:reply-banner-h theme-metrics 32.4) 0))

(defn calc-reaction-height [msg theme-metrics]
  (if (seq (:reactions msg)) (:reaction-row-h theme-metrics 24) 0))

(defn calc-media-height [content actual-tag available-w theme-metrics]
  (let [info          (or (:info content)
                          (get-in content [:inner :info])
                          (get-in content [:inner :content :info]))
        w             (js/parseFloat (:w info 0))
        h             (js/parseFloat (:h info 0))
        valid-dims?   (and (pos? w) (pos? h))
        default-ratio (case actual-tag "Video" 1.77 "Sticker" 1.0 1.33)
        calc-w        (if valid-dims? (min w available-w 400) 300)
        calc-h        (if valid-dims?
                        (min (* calc-w (/ h w)) 350)
                        (min (/ calc-w default-ratio) 350))
        media-margin  (:media-margin theme-metrics 0)
        is-edited?    (or (:is-edited? content)
                          (get-in content [:inner :content :is-edited?]))
        raw-caption   (when (not= actual-tag "Sticker")
                        (or (not-empty (:caption content))
                            (not-empty (get-in content [:inner :content :caption]))))
        edited-h      (if (and is-edited? (not raw-caption))
                        (:edited-label-h theme-metrics 16)
                        0)]
    (+ calc-h media-margin edited-h)))

(defn calc-text-height [msg available-w theme-metrics pretext-api]
  (let [{:keys [id pretext-prep is-quote? has-code-block? has-html? is-edited-calc?]} msg
        code-pad-x   (if has-code-block? (:code-padding-x theme-metrics 24) 0)
        quote-buffer (if is-quote? (:quote-wrap-buffer theme-metrics 19) 0)
        safe-w       (max 0 (- available-w code-pad-x quote-buffer))
        text-lh      (:line-height theme-metrics 22.8)
        base-lh      (if has-code-block?
                       (:code-line-height theme-metrics 20.52)
                       text-lh)
        code-padding (if has-code-block? (:code-padding theme-metrics 28) 0)
        cache-key    (str id "-" safe-w "-" (boolean is-edited-calc?))
        measured     ((:measure pretext-api) cache-key pretext-prep safe-w base-lh)
        edited-w     (:edited-label-w theme-metrics 43.5)
        edited-tax   (cond
                       (not is-edited-calc?) 0
                       has-code-block?       text-lh
                       (> (+ (:last-line-width measured) edited-w) safe-w) base-lh
                       :else                 0)
        v-tax        (if has-html?
                       (:html-vertical-tax theme-metrics 0)
                       (:text-vertical-tax theme-metrics 0))]
    (+ (:height measured) code-padding edited-tax v-tax)))

(defrecord ParadiseTimelineSpec [source]
  proto/ILayoutSpec
  (extract-id [_ raw-item]
    (extract-event-id raw-item))

  (precompile-item [_ raw-item room-id pretext-api]
    (let [processed           (process-raw-event raw-item source room-id)
          id                  (:id processed)
          content             (:content processed)
          inner               (or (get-in content [:inner :content])
                                  (:inner content)
                                  content)
          body                (or (not-empty (:body inner))
                                  (not-empty (:body content))
                                  (not-empty (:caption inner))
                                  (not-empty (:caption content))
                                  "")
          html                (or (not-empty (:html inner))
                                  (not-empty (:html content))
                                  "")
          raw-txt             (str (or (not-empty (:clean-text processed)) body))
          html-txt            (str (or (not-empty (:clean-html processed)) html))
          has-code-block?     (or (str/includes? raw-txt "```")
                                  (str/includes? html-txt "<pre>"))
          txt-str             (if (str/includes? raw-txt "```")
                                (-> raw-txt
                                    (str/replace #"```[^\n]*\n?" "")
                                    (str/replace #"```" "")
                                    (str/trim))
                                raw-txt)
          has-url?            (boolean (re-find #"https?://[^\s]+" txt-str))
          measure-str         (if has-url?
                                (str/replace txt-str #"https?://[^\s]+"
                                             (fn [url-match]
                                               (let [url (if (coll? url-match) (first url-match) url-match)]
                                                 (str/replace url #"([/=?&._:-])" "$1\u200B"))))
                                txt-str)
          is-quote?           (str/includes? html-txt "<blockquote")
          has-html?           (boolean (seq html-txt))
          first-url           (extract-first-url raw-txt)
          is-edited?          (or (:is-edited? content)
                                  (get-in content [:inner :content :is-edited?]))
          pre-compiled-hiccup (if has-html?
                                (into [:span.body.formatted] (memoized-sanitize html))
                                (into [:span.body] (linkify-text body)))
          font-str            (if has-code-block?
                                "500 13.68px \"fira code\", monospace"
                                "400 15.2px Inter, sans-serif")
          pretext-prep        ((:prepare pretext-api) measure-str font-str {:whiteSpace "pre-wrap" :wordBreak "normal"})
          pojo-content        (ast/hiccup->pojo pre-compiled-hiccup)]
      (assoc processed
             :pre-compiled-hiccup pre-compiled-hiccup
             :worker-pojo         pojo-content
             :id                  id
             :pretext-prep        pretext-prep
             :first-url           first-url
             :measure-str         measure-str
             :is-quote?           is-quote?
             :has-code-block?     has-code-block?
             :has-html?           has-html?
             :has-url?            has-url?
             :is-edited-calc?     is-edited?)))

  (enrich-items [_ items]
    (loop [remaining items
           processed (transient [])
           last-msg  nil
           unread?   false]
      (if (empty? remaining)
        (persistent! processed)
        (let [curr             (first remaining)
              curr-kw          (if (map? curr) (update curr :type keyword) curr)
              is-marker?       (= (:tag curr-kw) "ReadMarker")
              next-unread?     (or unread? is-marker?)
              curr-is-msg?     (= (:content-tag curr-kw) "MsgLike")
              stable-id        (get-event-id curr-kw)
              can-merge?       (boolean (and curr-is-msg?
                                             last-msg
                                             (= (:sender-id curr-kw) (:sender-id last-msg))
                                             (< (- (:ts curr-kw) (:ts last-msg)) 300000)))
              apply-unread?    (and next-unread? (not is-marker?))
              already-correct? (and (= (:id curr-kw) stable-id)
                                    (= (boolean (:merge-with-prev? curr-kw)) can-merge?)
                                    (= (boolean (:unread? curr-kw)) apply-unread?))
              new-item         (if already-correct?
                                 curr-kw
                                 (assoc curr-kw
                                        :id stable-id
                                        :merge-with-prev? can-merge?
                                        :unread? apply-unread?))]
          (recur (rest remaining)
                 (conj! processed new-item)
                 (if curr-is-msg? new-item nil)
                 next-unread?)))))

  (estimate-height [_ msg width theme-metrics pretext-api]
    (let [type (:type msg)
          tag  (:content-tag msg)
          id   (str (:id msg))]
      (cond
        (or (= type :virtual)
            (= type "virtual")
            (str/starts-with? id "virtual-divider")
            (= id "read-marker"))
        (:virtual-divider-h theme-metrics 49)

        (and tag (not (#{"MsgLike" "Image" "Video" "Sticker" "File" "Text"} tag)))
        (:system-event-h theme-metrics 42)

        :else
        (let [is-sequence-start? (not (:merge-with-prev? msg))
              content            (:content msg)
              avatar-h           (:avatar-h theme-metrics 32)
              row-w              (:row-w theme-metrics)
              text-node-w        (:text-node-w theme-metrics)
              horiz-chrome       (if (and row-w text-node-w (pos? row-w) (pos? text-node-w))
                                   (- row-w text-node-w)
                                   86)
              header-h           (:header-h theme-metrics 19)
              seq-padding        (:seq-padding theme-metrics 10)
              merged-padding     (:merged-padding theme-metrics 5)
              available-w        (max 0 (- width horiz-chrome))
              reply-h            (calc-reply-height content theme-metrics)
              reaction-h         (calc-reaction-height msg theme-metrics)
              tags-present       #{(:tag content) (get-in content [:inner :tag]) (:content-tag msg)}
              actual-tag         (first (filter #{"Image" "Video" "Sticker"} tags-present))
              is-media?          (boolean actual-tag)
              body-h             (if is-media?
                                   (let [m-h     (calc-media-height content actual-tag available-w theme-metrics)
                                         caption (when (not= actual-tag "Sticker")
                                                   (or (not-empty (:caption content))
                                                       (not-empty (get-in content [:inner :content :caption]))))
                                         t-h     (if caption
                                                   (calc-text-height msg available-w theme-metrics pretext-api)
                                                   0)]
                                     (+ m-h t-h))
                                   (calc-text-height msg available-w theme-metrics pretext-api))]
          (if is-sequence-start?
            (+ (max avatar-h (+ header-h body-h reply-h)) seq-padding reaction-h)
            (+ body-h reply-h merged-padding reaction-h))))))

  (ast-cache-key [_ item revision]
    (hash [(:raw item) (:unread? item) (:reactions item) (:merge-with-prev? item) revision]))

  (render-item-hiccup [_ item]
    (let [render-fn (or (get @state/!worker-overrides :event-tile-render)
                        (get @state/!worker-components :event-tile-render)
                        event-tile-render)
          outer-res (render-fn item nil false nil nil)]
      (if (fn? outer-res)
        (outer-res item nil false nil nil)
        outer-res))))
