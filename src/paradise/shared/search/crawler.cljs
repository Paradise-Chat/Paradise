(ns paradise.shared.search.crawler
  (:require [paradise.shared.search.index :as index]
            [promesa.core :as p]
            [taoensso.timbre :as log]))

(def ^:const default-initial-delay-ms 4500)
(def ^:const default-step-delay-ms 1400)
(def ^:const default-idle-recheck-ms 20000)
(def ^:const default-batch-size 50)
(def ^:const default-max-pages-per-room 40)
(def ^:const progress-min-interval-ms 1200)

(defonce !crawlers (atom {}))

(defn current-status [engine-id]
  (let [ek   (keyword engine-id)
        c    (get @!crawlers ek)
        sess (index/active-session ek)]
    (if-not (or c sess)
      {:supported?     false
       :enabled?       false
       :running?       false
       :indexed-events 0
       :rooms-done     0
       :rooms-total    0}
      {:supported?     true
       :engine-id      (name ek)
       :enabled?       (boolean (if c @(:!enabled? c) true))
       :running?       (boolean (and c @(:!running? c) @(:!enabled? c)))
       :indexed-events (if sess @(:!event-count sess) 0)
       :rooms-done     (if c (count @(:!done-rooms c)) 0)
       :rooms-total    (if c @(:!rooms-total c) 0)})))

(defn emit-progress!
  ([engine-id]
   (emit-progress! engine-id false))
  ([engine-id force?]
   (let [ek (keyword engine-id)]
     (when-let [c (get @!crawlers ek)]
       (when-let [emit-fn (:emit-progress-fn c)]
         (when-let [tmr (first (reset-vals! (:!emit-timer c) nil))]
           (js/clearTimeout tmr))
         (let [now     (js/Date.now)
               last-ts @(:!last-emit-ts c)
               elapsed (- now last-ts)]
           (if (or force? (>= elapsed progress-min-interval-ms))
             (do
               (reset! (:!last-emit-ts c) now)
               (emit-fn (current-status ek)))
             (let [wait-ms (max 50 (- progress-min-interval-ms elapsed))
                   tmr     (js/setTimeout
                            (fn []
                              (reset! (:!emit-timer c) nil)
                              (when (= (get @!crawlers ek) c)
                                (reset! (:!last-emit-ts c) (js/Date.now))
                                (emit-fn (current-status ek))))
                            wait-ms)]
               (reset! (:!emit-timer c) tmr)))))))))

(defn- cancel-sleep! [c]
  (when-let [st (:!sleep c)]
    (when-let [{:keys [timer res-fn]} (first (reset-vals! st nil))]
      (js/clearTimeout timer)
      (res-fn false))))

(defn- sleep-ms [c ms]
  (cancel-sleep! c)
  (js/Promise.
   (fn [res-fn _]
     (let [tmr (js/setTimeout
                (fn []
                  (reset! (:!sleep c) nil)
                  (res-fn true))
                (max 0 ms))]
       (reset! (:!sleep c) {:timer tmr :res-fn res-fn})))))

(defn- alive? [c gen]
  (and (= (get @!crawlers (:engine-id c)) c)
       (= @(:!gen c) gen)
       (some? (index/active-session (:engine-id c)))))

(defn- pick-next-room! [c gen]
  (let [ek         (:engine-id c)
        rooms-raw  (try ((:list-rooms-fn c)) (catch :default _ []))
        ordered    (->> rooms-raw
                        (filter #(some? (:room-id %)))
                        (sort-by #(or (:priority %) 100))
                        vec)
        done-cache @(:!done-rooms c)]
    (reset! (:!rooms-total c) (count ordered))
    (p/loop [idx 0]
      (cond
        (not (alive? c gen))
        nil

        (>= idx (count ordered))
        nil

        :else
        (let [room-entry (nth ordered idx)
              rid        (str (:room-id room-entry))]
          (if (contains? done-cache rid)
            (p/recur (inc idx))
            (p/let [cp (index/load-checkpoint! ek rid)]
              (if (:done? cp)
                (do
                  (swap! (:!done-rooms c) conj rid)
                  (p/recur (inc idx)))
                {:room-id   rid
                 :max-pages (:max-pages room-entry)
                 :cp        cp}))))))))

(defn- run-step! [c gen {:keys [room-id max-pages cp]}]
  (let [ek        (:engine-id c)
        room-cap  (or max-pages (:max-pages-per-room c) default-max-pages-per-room)
        page      (long (or (:page cp) 0))
        cursor    (:cursor cp)]
    (-> ((:crawl-step-fn c)
         {:room-id    room-id
          :cursor     cursor
          :page       page
          :batch-size (or (:batch-size c) default-batch-size)})
        (p/then
          (fn [res]
            (when (alive? c gen)
              (let [raw-events (:events res)
                    tl-items   (:timeline-items res)
                    extracted  (when (seq tl-items)
                                 (->> tl-items
                                      (keep #(index/extract-timeline-item room-id %))
                                      (keep #(when (= (:action %) :upsert) (:event %)))
                                      vec))
                    all-events (vec (concat raw-events extracted))
                    next-page  (inc page)
                    done?      (boolean (or (:done? res)
                                            (>= next-page room-cap)))]
                (p/let [_ (if (seq all-events)
                            (index/upsert-events! ek all-events)
                            (p/resolved 0))
                        _ (index/save-checkpoint!
                           ek
                           room-id
                           {:cursor     (:next-cursor res)
                            :page       next-page
                            :done?      done?
                            :updated-at (js/Date.now)})]
                  (when done?
                    (swap! (:!done-rooms c) conj room-id))
                  (emit-progress! ek done?)
                  {:retry-after-ms (:retry-after-ms res)
                   :done?          done?})))))
        (p/catch
          (fn [err]
            (log/debug "Encrypted search crawl step backoff for" room-id ":" err)
            {:retry-after-ms 5000})))))

(defn- crawl-loop! [c gen initial-wait-ms]
  (p/let [_ (sleep-ms c initial-wait-ms)]
    (p/loop [backoff-ms 0]
      (when (alive? c gen)
        (if-not @(:!enabled? c)
          (do
            (reset! (:!running? c) false)
            (emit-progress! (:engine-id c) true)
            nil)
          (p/let [_      (when (pos? backoff-ms) (sleep-ms c backoff-ms))
                  target (when (and (alive? c gen) @(:!enabled? c))
                           (pick-next-room! c gen))]
            (cond
              (not (alive? c gen))
              nil

              (not @(:!enabled? c))
              (do
                (reset! (:!running? c) false)
                (emit-progress! (:engine-id c) true)
                nil)

              (nil? target)
              (do
                (reset! (:!running? c) false)
                (emit-progress! (:engine-id c) true)
                (p/let [_ (sleep-ms c default-idle-recheck-ms)]
                  (when (and (alive? c gen) @(:!enabled? c))
                    (p/recur 0))))

              :else
              (do
                (when-not @(:!running? c)
                  (reset! (:!running? c) true)
                  (emit-progress! (:engine-id c) true))
                (p/let [step-res (run-step! c gen target)
                        wait-ms  (or (:retry-after-ms step-res)
                                     (:step-delay-ms c)
                                     default-step-delay-ms)]
                  (p/recur wait-ms))))))))))

(defn stop-crawler! [engine-id]
  (let [ek (keyword engine-id)]
    (when-let [c (get @!crawlers ek)]
      (swap! (:!gen c) inc)
      (cancel-sleep! c)
      (when-let [tmr (first (reset-vals! (:!emit-timer c) nil))]
        (js/clearTimeout tmr))
      (reset! (:!running? c) false)
      (when-let [cleanup (:on-cleanup-fn c)]
        (try (cleanup) (catch :default _ nil)))
      (swap! !crawlers dissoc ek))))

(defn start-crawler!
  [{:keys [engine-id
           enabled?
           initial-delay-ms
           step-delay-ms
           batch-size
           max-pages-per-room
           list-rooms-fn
           crawl-step-fn
           on-cleanup-fn
           emit-progress-fn]}]
  (let [ek         (keyword engine-id)
        _          (stop-crawler! ek)
        init-total (try (count (list-rooms-fn)) (catch :default _ 0))
        gen        1
        c          {:engine-id          ek
                    :!gen               (atom gen)
                    :!sleep             (atom nil)
                    :!emit-timer        (atom nil)
                    :!enabled?          (atom (if (nil? enabled?) true (boolean enabled?)))
                    :!running?          (atom false)
                    :!done-rooms        (atom #{})
                    :!rooms-total       (atom init-total)
                    :!last-emit-ts      (atom 0)
                    :step-delay-ms      (or step-delay-ms default-step-delay-ms)
                    :batch-size         (or batch-size default-batch-size)
                    :max-pages-per-room (or max-pages-per-room default-max-pages-per-room)
                    :list-rooms-fn      list-rooms-fn
                    :crawl-step-fn      crawl-step-fn
                    :on-cleanup-fn      on-cleanup-fn
                    :emit-progress-fn   emit-progress-fn}]
    (swap! !crawlers assoc ek c)
    (emit-progress! ek true)
    (when @(:!enabled? c)
      (crawl-loop! c gen (or initial-delay-ms default-initial-delay-ms)))
    c))

(defn set-enabled! [engine-id enabled?]
  (let [ek (keyword engine-id)]
    (if-let [c (get @!crawlers ek)]
      (let [next-val (boolean enabled?)
            prev-val @(:!enabled? c)]
        (reset! (:!enabled? c) next-val)
        (if (and next-val (not prev-val))
          (let [next-gen (swap! (:!gen c) inc)]
            (cancel-sleep! c)
            (crawl-loop! c next-gen 250))
          (when-not next-val
            (swap! (:!gen c) inc)
            (cancel-sleep! c)
            (reset! (:!running? c) false)
            (when-let [cleanup (:on-cleanup-fn c)]
              (try (cleanup) (catch :default _ nil)))))
        (emit-progress! ek true)
        (current-status ek))
      (current-status ek))))

(defn nudge! [engine-id]
  (let [ek (keyword engine-id)]
    (when-let [c (get @!crawlers ek)]
      (when (and @(:!enabled? c) (not @(:!running? c)))
        (let [next-gen (swap! (:!gen c) inc)]
          (cancel-sleep! c)
          (crawl-loop! c next-gen 500))))))

(defn clear-and-rebuild! [engine-id rebuild?]
  (let [ek (keyword engine-id)]
    (if-let [c (get @!crawlers ek)]
      (let [was-enabled? @(:!enabled? c)]
        (swap! (:!gen c) inc)
        (cancel-sleep! c)
        (reset! (:!running? c) false)
        (when-let [cleanup (:on-cleanup-fn c)]
          (try (cleanup) (catch :default _ nil)))
        (p/let [_ (index/clear-index! ek)]
          (reset! (:!done-rooms c) #{})
          (when (and rebuild? was-enabled?)
            (let [next-gen (swap! (:!gen c) inc)]
              (crawl-loop! c next-gen 300)))
          (emit-progress! ek true)
          (current-status ek)))
      (p/let [_ (index/clear-index! ek)]
        (current-status ek)))))