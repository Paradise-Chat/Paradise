(ns ^:virtualizer/skip paradise.shared.net.proxy
  (:require [cljs.core.async :as async :refer [go]]
            [cljs.core.async.interop :refer-macros [<p!]]
            [cljs-workers.mesh :as mesh]
            [cljs-workers.worker :as worker]
            [clojure.string :as str]
            [goog.object :as gobj]
            [paradise.shared.net.auth :as net-auth]
            [paradise.shared.utils.encoding :refer [base64->uint8 uint8->base64]]
            [promesa.core :as p]))

(defonce !native-proxy-fn (atom nil))
(defonce !worker-proxy-mode (atom :unknown))
(defonce !worker-next-probe-ms (atom 0))
(defonce !web-auth-popup (atom nil))
(defonce !proxy-ready-listeners (atom #{}))
(defonce !proxy-event-listener-installed? (atom false))

(defn register-native-proxy! [proxy-fn]
  (reset! !native-proxy-fn proxy-fn)
  (reset! !worker-proxy-mode :unknown)
  (reset! !worker-next-probe-ms 0))

(defn host-proxy-provider []
  (when (exists? js/window)
    (or (some-> (gobj/get js/window "paradiseProxy")
                (gobj/get "provider"))
        (when (some? (gobj/get js/window "paradiseProxy"))
          "host")
        (when @!native-proxy-fn
          "custom"))))

(defn on-proxy-ready! [listener-fn]
  (when (fn? listener-fn)
    (swap! !proxy-ready-listeners conj listener-fn)
    (when-let [provider (host-proxy-provider)]
      (try
        (listener-fn {:provider provider})
        (catch :default _ nil)))
    #(swap! !proxy-ready-listeners disj listener-fn)))

(defn- notify-proxy-ready! [detail]
  (reset! !worker-proxy-mode :unknown)
  (reset! !worker-next-probe-ms 0)
  (let [provider (or (when detail (gobj/get detail "provider"))
                     (host-proxy-provider)
                     "host")]
    (doseq [cb @!proxy-ready-listeners]
      (try
        (cb {:provider provider})
        (catch :default _ nil)))))

(defn- ensure-proxy-ready-listener! []
  (when (and (exists? js/window)
             (compare-and-set! !proxy-event-listener-installed? false true))
    (.addEventListener
     js/window
     "paradise:proxy-ready"
     (fn [ev]
       (notify-proxy-ready! (when ev (.-detail ev)))))))

(defn mount-host-proxy! [proxy-obj]
  (when (and (exists? js/window) (some? proxy-obj))
    (ensure-proxy-ready-listener!)
    (let [js-proxy (if (map? proxy-obj) (clj->js proxy-obj) proxy-obj)
          provider (or (gobj/get js-proxy "provider") "host")
          version  (or (gobj/get js-proxy "version") "1.0")]
      (gobj/set js/window "paradiseProxy" js-proxy)
      (if (exists? js/CustomEvent)
        (.dispatchEvent
         js/window
         (js/CustomEvent. "paradise:proxy-ready"
                          #js {:detail #js {:provider provider
                                            :version  version}}))
        (notify-proxy-ready! #js {:provider provider :version version}))
      js-proxy)))

(defn- service-worker? []
  (and (exists? js/ServiceWorkerGlobalScope)
       (instance? js/ServiceWorkerGlobalScope js/globalThis)))

(defn- worker-mesh-available? []
  (and (not (exists? js/window))
       (not (service-worker?))
       (or (contains? @mesh/!thread-identities :main-thread)
           (some? (gobj/get js/globalThis "__mesh_host_bridge")))))

(defn- chan->promise [ch & [timeout-ms]]
  (p/create
   (fn [resolve-fn _]
     (let [done? (atom false)
           timer (when timeout-ms
                   (js/setTimeout
                    (fn []
                      (when (compare-and-set! done? false true)
                        (resolve-fn {:status "timeout"})))
                    timeout-ms))]
       (async/take! ch
                    (fn [v]
                      (when (compare-and-set! done? false true)
                        (when timer (js/clearTimeout timer))
                        (resolve-fn v))))))))

(defn proxy-available? []
  (cond
    (exists? js/window)
    (let [prov (host-proxy-provider)]
      (p/resolved {:supported (some? prov)
                   :provider  prov}))

    (worker-mesh-available?)
    (-> (chan->promise
         (mesh/do-with-thread! :main-thread
                               {:handler   :proxy-status
                                :arguments {}})
         2000)
        (p/then (fn [res]
                  (if (= (:status res) "timeout")
                    {:supported false :provider nil}
                    res)))
        (p/catch (fn [_] {:supported false :provider nil})))

    :else
    (p/resolved {:supported false :provider nil})))

(defn- sanitize-proxy-headers [target-url hdrs]
  (let [raw-map  (if (map? hdrs) hdrs (if hdrs (js->clj hdrs) {}))
        base-url (:base-url @net-auth/!auth-context)
        same?    (and base-url (net-auth/same-origin-pair? target-url base-url))]
    (if (or same? (nil? base-url))
      raw-map
      (reduce-kv (fn [acc k v]
                   (let [kl (str/lower-case (name k))]
                     (if (and (= kl "authorization")
                              (when-let [tok (:token @net-auth/!auth-context)]
                                (and (string? v) (str/includes? v (str tok)))))
                       acc
                       (assoc acc k v))))
                 {}
                 raw-map))))

(defn- prepare-proxy-target!
  [{:keys [bridge-method rpc-handler validate-fn extra-bridge-payload
           extra-rpc-args parse-res miss-res fallback-fn]}
   {:keys [url url-or-host headers request-headers] :as opts}]
  (let [target-url (or url url-or-host)
        valid-u    (validate-fn target-url)
        hdrs       (if valid-u
                     (sanitize-proxy-headers (.-href valid-u) (or headers request-headers {}))
                     {})
        fail-res   (miss-res (or target-url ""))]
    (cond
      (not valid-u)
      (p/resolved fail-res)

      (exists? js/window)
      (let [proxy-bridge (gobj/get js/window "paradiseProxy")
            prep-fn      (when proxy-bridge (gobj/get proxy-bridge bridge-method))]
        (if (fn? prep-fn)
          (let [payload (merge {:url            target-url
                                :urlOrHost      target-url
                                :headers        hdrs
                                :requestHeaders hdrs}
                               (when extra-bridge-payload (extra-bridge-payload opts)))]
            (-> (.call prep-fn proxy-bridge (clj->js payload))
                (p/then #(parse-res % target-url))
                (p/catch (fn [_] fail-res))))
          (if fallback-fn
            (fallback-fn opts)
            (p/resolved fail-res))))

      (worker-mesh-available?)
      (let [args (merge (or opts {})
                        {:url target-url :headers hdrs}
                        (when extra-rpc-args (extra-rpc-args opts)))]
        (-> (chan->promise
             (mesh/do-with-thread! :main-thread
                                   {:handler   rpc-handler
                                    :arguments args})
             3000)
            (p/then (fn [res]
                      (if (= (:status res) "timeout")
                        fail-res
                        res)))
            (p/catch (fn [_] fail-res))))

      :else
      (p/resolved fail-res))))

(defn- clear-proxy-target! [bridge-method rpc-handler url-or-host]
  (cond
    (exists? js/window)
    (let [proxy-bridge (gobj/get js/window "paradiseProxy")
          clear-fn     (when proxy-bridge (gobj/get proxy-bridge bridge-method))]
      (if (fn? clear-fn)
        (-> (.call clear-fn proxy-bridge url-or-host)
            (p/then (fn [_] {:ok true}))
            (p/catch (fn [_] {:ok false})))
        (p/resolved {:ok true})))

    (worker-mesh-available?)
    (-> (chan->promise
         (mesh/do-with-thread! :main-thread
                               {:handler   rpc-handler
                                :arguments {:url-or-host url-or-host}})
         2000)
        (p/catch (fn [_] {:ok false})))

    :else
    (p/resolved {:ok false})))

(defn- default-parse-target-res [res target-url]
  (if (object? res)
    {:ok  (boolean (if (some? (gobj/get res "ok"))
                     (gobj/get res "ok")
                     true))
     :url (or (gobj/get res "url") target-url)}
    {:ok  (boolean res)
     :url target-url}))

(defn prepare-stream-url! [opts]
  (prepare-proxy-target!
   {:bridge-method "prepareStream"
    :rpc-handler   :prepare-stream
    :validate-fn   net-auth/valid-http-url
    :parse-res     default-parse-target-res
    :miss-res      (fn [u] {:ok false :url u})}
   opts))

(defn clear-stream-url! [& [url-or-host]]
  (clear-proxy-target! "clearStream" :clear-stream url-or-host))

(defn prepare-frame-url! [opts]
  (prepare-proxy-target!
   {:bridge-method        "prepareFrame"
    :rpc-handler          :prepare-frame
    :validate-fn          net-auth/valid-http-url
    :extra-bridge-payload (fn [{:keys [embed]}] {:embed (boolean embed)})
    :parse-res            (fn [res target-url]
                            (let [base (default-parse-target-res res target-url)]
                              (assoc base :mode (or (when (object? res) (gobj/get res "mode"))
                                                    "headers"))))
    :miss-res             (fn [u] {:ok false :url u :mode nil})
    :fallback-fn          (fn [o]
                            (-> (prepare-stream-url! o)
                                (p/then #(assoc % :mode (when (:ok %) "stream-fallback")))))}
   opts))

(defn clear-frame-url! [& [url-or-host]]
  (clear-proxy-target! "clearFrame" :clear-frame url-or-host))

(defn prepare-websocket! [opts]
  (prepare-proxy-target!
   {:bridge-method        "prepareWebSocket"
    :rpc-handler          :prepare-websocket
    :validate-fn          net-auth/valid-ws-url
    :extra-bridge-payload (fn [{:keys [origin]}]
                            (when (string? origin) {:origin origin}))
    :extra-rpc-args       (fn [{:keys [origin]}]
                            (when (string? origin) {:origin origin}))
    :parse-res            default-parse-target-res
    :miss-res             (fn [u] {:ok false :url u})}
   opts))

(defn clear-websocket! [& [url-or-host]]
  (clear-proxy-target! "clearWebSocket" :clear-websocket url-or-host))

(defn open-auth-url!
  [{:keys [url title external? external
           callback-prefix callbackPrefix
           close-on-prefix closeOnPrefix
           redirect-on-prefix redirectOnPrefix] :as opts}]
  (let [valid-u      (net-auth/valid-http-url url)
        ext?         (boolean (or external? external))
        cb-prefix    (when-let [p (or callback-prefix callbackPrefix)]
                       (when (net-auth/valid-http-url p) (str/trim (str p))))
        close-prefix (when-let [p (or close-on-prefix closeOnPrefix)]
                       (when (net-auth/valid-http-url p) (str/trim (str p))))
        raw-redir    (or redirect-on-prefix redirectOnPrefix)
        redir-rule   (when (map? raw-redir)
                       (let [mp (or (:match-prefix raw-redir) (:matchPrefix raw-redir))
                             tu (or (:target-url raw-redir) (:targetUrl raw-redir))]
                         (when (and (net-auth/valid-http-url mp) (net-auth/valid-http-url tu))
                           {:matchPrefix (str/trim (str mp))
                            :targetUrl   (str/trim (str tu))})))]
    (cond
      (not valid-u)
      (p/resolved {:ok false :error "Invalid or unsupported auth URL"})

      (exists? js/window)
      (let [safe-url     (.-href valid-u)
            proxy-bridge (gobj/get js/window "paradiseProxy")
            open-win-fn  (when proxy-bridge (gobj/get proxy-bridge "openAuthWindow"))
            open-ext-fn  (when proxy-bridge (gobj/get proxy-bridge "openExternal"))
            cap-browser  (some-> (gobj/get js/window "Capacitor")
                                 (gobj/get "Plugins")
                                 (gobj/get "Browser"))]
        (cond
          (and ext? (fn? open-ext-fn))
          (-> (.call open-ext-fn proxy-bridge safe-url)
              (p/then (fn [_] {:ok true :mode "external"}))
              (p/catch (fn [e] {:ok false :error (str e)})))

          (fn? open-win-fn)
          (-> (.call open-win-fn
                     proxy-bridge
                     (clj->js (cond-> {:url      safe-url
                                       :title    (or title "Sign In")
                                       :external ext?}
                                cb-prefix    (assoc :callbackPrefix cb-prefix)
                                close-prefix (assoc :closeOnPrefix close-prefix)
                                redir-rule   (assoc :redirectOnPrefix redir-rule))))
              (p/then (fn [res]
                        {:ok           (boolean (if (some? (some-> res (gobj/get "ok")))
                                                  (gobj/get res "ok")
                                                  true))
                         :mode         (or (some-> res (gobj/get "mode")) "window")
                         :callback-url (some-> res (gobj/get "callbackUrl"))
                         :cancelled?   (boolean (some-> res (gobj/get "cancelled")))}))
              (p/catch (fn [e] {:ok false :error (str e)})))

          (and cap-browser (fn? (gobj/get cap-browser "open")))
          (-> (.open cap-browser (clj->js {:url safe-url}))
              (p/then (fn [_] {:ok true :mode "capacitor-browser"}))
              (p/catch (fn [e] {:ok false :error (str e)})))

          :else
          (let [popup (.open js/window
                             safe-url
                             (if ext? "_blank" "paradise-auth-popup")
                             (if ext?
                               "noopener,noreferrer"
                               "width=520,height=760"))]
            (when-not ext?
              (reset! !web-auth-popup popup))
            (if (and cb-prefix popup (not ext?))
              (p/create
               (fn [resolve-fn _]
                 (let [done?  (atom false)
                       !timer (atom nil)
                       stop!  (fn [res]
                                (when (compare-and-set! done? false true)
                                  (when-let [t @!timer] (js/clearInterval t))
                                  (try (.close popup) (catch :default _ nil))
                                  (when (= @!web-auth-popup popup)
                                    (reset! !web-auth-popup nil))
                                  (resolve-fn res)))]
                   (reset! !timer
                           (js/setInterval
                            (fn []
                              (cond
                                (.-closed popup)
                                (stop! {:ok false :mode "popup" :cancelled? true})

                                :else
                                (try
                                  (let [href (.. popup -location -href)]
                                    (when (and (string? href) (str/starts-with? href cb-prefix))
                                      (stop! {:ok true :mode "callback" :callback-url href})))
                                  (catch :default _ nil))))
                            250)))))
              (p/resolved {:ok (some? popup) :mode "popup"})))))

      (worker-mesh-available?)
      (-> (chan->promise
           (mesh/do-with-thread! :main-thread
                                 {:handler   :open-auth-window
                                  :arguments (cond-> (assoc opts
                                                            :url       (.-href valid-u)
                                                            :external? ext?)
                                               cb-prefix    (assoc :callback-prefix cb-prefix)
                                               close-prefix (assoc :close-on-prefix close-prefix)
                                               redir-rule   (assoc :redirect-on-prefix redir-rule))})
           (if (or cb-prefix close-prefix) 600000 4000))
          (p/catch (fn [_] {:ok false})))

      :else
      (p/resolved {:ok false :error "Window unavailable"}))))

(defn close-auth-url! []
  (cond
    (exists? js/window)
    (let [proxy-bridge (gobj/get js/window "paradiseProxy")
          close-win-fn (when proxy-bridge (gobj/get proxy-bridge "closeAuthWindow"))
          cap-browser  (some-> (gobj/get js/window "Capacitor")
                               (gobj/get "Plugins")
                               (gobj/get "Browser"))]
      (when-let [popup @!web-auth-popup]
        (reset! !web-auth-popup nil)
        (try (.close popup) (catch :default _ nil)))
      (cond
        (fn? close-win-fn)
        (-> (.call close-win-fn proxy-bridge)
            (p/then (fn [_] {:ok true}))
            (p/catch (fn [_] {:ok false})))

        (and cap-browser (fn? (gobj/get cap-browser "close")))
        (-> (.close cap-browser)
            (p/then (fn [_] {:ok true}))
            (p/catch (fn [_] {:ok false})))

        :else
        (p/resolved {:ok true})))

    (worker-mesh-available?)
    (-> (chan->promise
         (mesh/do-with-thread! :main-thread
                               {:handler   :close-auth-window
                                :arguments {}})
         2000)
        (p/catch (fn [_] {:ok false})))

    :else
    (p/resolved {:ok false})))

(defn set-auth-challenge!
  "Broadcasts an interactive auth challenge map (or nil to clear) to the host
   main thread so the login UI can display browser-handoff / IP-auth prompts."
  [challenge]
  (if (worker-mesh-available?)
    (-> (chan->promise
         (mesh/do-with-thread! :main-thread
                               {:handler   :auth-challenge
                                :arguments challenge})
         2000)
        (p/catch (fn [_] {:ok false})))
    (p/resolved {:ok false})))

(defn headers->map [hdrs]
  (cond
    (nil? hdrs)
    {}

    (map? hdrs)
    (reduce-kv (fn [m k v]
                 (if (some? v)
                   (assoc m (if (keyword? k) (subs (str k) 1) (str k)) (str v))
                   m))
               {}
               hdrs)

    (and (exists? js/Headers) (instance? js/Headers hdrs))
    (let [acc (atom {})]
      (.forEach hdrs (fn [v k] (swap! acc assoc (str k) (str v))))
      @acc)

    (object? hdrs)
    (reduce (fn [m k]
              (let [v (gobj/get hdrs k)]
                (if (some? v) (assoc m (str k) (str v)) m)))
            {}
            (js/Object.keys hdrs))

    :else
    {}))

(defn- encode-host-body [body]
  (cond
    (nil? body)    {:body nil :body-encoding nil}
    (string? body) {:body body :body-encoding "utf8"}
    :else          {:body (uint8->base64 body) :body-encoding "base64"}))

(def ^:private streaming-upload-threshold-bytes (* 256 1024))

(defn- try-streaming-host-upload [proxy-bridge {:keys [url method headers body]}]
  (let [prep-fn (when proxy-bridge (gobj/get proxy-bridge "prepareUpload"))
        m-upper (str/upper-case (or method "GET"))]
    (if (and (fn? prep-fn)
             body
             (not (string? body))
             (>= (or (.-byteLength body) 0) streaming-upload-threshold-bytes)
             (contains? #{"POST" "PUT" "PATCH"} m-upper)
             (exists? js/fetch))
      (-> (p/let [prep-res (.call prep-fn proxy-bridge (clj->js {:url url :headers (or headers {})}))]
            (when (and prep-res (gobj/get prep-res "ok") (seq (gobj/get prep-res "url")))
              (let [loopback-url (gobj/get prep-res "url")
                    fetch-hdrs   (js/Headers.)
                    _            (doseq [[k v] headers
                                         :let [lk (str/lower-case (str k))]
                                         :when (contains? #{"content-type" "accept"} lk)]
                                   (.set fetch-hdrs k (str v)))]
                (p/let [resp (js/fetch loopback-url #js {:method  m-upper
                                                         :headers fetch-hdrs
                                                         :body    body})
                        ab   (.arrayBuffer resp)]
                  {:supported     true
                   :ok            (.-ok resp)
                   :status        (.-status resp)
                   :url           (or (.get (.-headers resp) "x-paradise-final-url") url)
                   :headers       (headers->map (.-headers resp))
                   :body-encoding "raw"
                   :body          (js/Uint8Array. ab)}))))
          (p/catch (fn [_] nil)))
      (p/resolved nil))))

(defn- call-host-proxy [{:keys [url method headers body] :as req-map}]
  (let [proxy-bridge (gobj/get js/window "paradiseProxy")
        req-fn       (when proxy-bridge (gobj/get proxy-bridge "request"))]
    (p/let [streamed (try-streaming-host-upload proxy-bridge req-map)]
      (if streamed
        streamed
        (let [{enc-body :body
               body-enc :body-encoding} (encode-host-body body)
              req-payload               (cond-> {:url          url
                                                 :method       (or method "GET")
                                                 :headers      (or headers {})
                                                 :responseType "base64"}
                                          (some? enc-body) (assoc :body enc-body)
                                          (some? body-enc) (assoc :bodyEncoding body-enc))]
          (p/let [res (.call req-fn proxy-bridge (clj->js req-payload))]
            {:supported     true
             :ok            (boolean (gobj/get res "ok"))
             :status        (or (gobj/get res "status") 200)
             :url           (or (gobj/get res "url") url)
             :headers       (js->clj (gobj/get res "headers"))
             :body-encoding "base64"
             :body          (or (gobj/get res "data") "")}))))))

(defn- host-proxy-fn []
  (when (exists? js/window)
    (let [proxy-bridge (gobj/get js/window "paradiseProxy")
          req-fn       (when proxy-bridge (gobj/get proxy-bridge "request"))]
      (when (fn? req-fn)
        call-host-proxy))))

(defn- active-main-proxy-fn []
  (or @!native-proxy-fn (host-proxy-fn)))

(defn- execute-main-proxy! [req-map]
  (if-let [proxy-fn (active-main-proxy-fn)]
    (-> (proxy-fn req-map)
        (p/catch (fn [e]
                   {:supported true
                    :error     (or (gobj/get e "message") (str e))})))
    (p/resolved {:supported false})))

(defn- normalize-request-payload [opts hdr-map]
  (let [method (str/upper-case (str (or (gobj/get opts "method") "GET")))
        body   (gobj/get opts "body")]
    (cond
      (or (nil? body) (= method "GET") (= method "HEAD"))
      (p/resolved {:method method :headers hdr-map :body nil})

      (string? body)
      (p/resolved {:method method :headers hdr-map :body body})

      (and (exists? js/URLSearchParams) (instance? js/URLSearchParams body))
      (p/resolved {:method method :headers hdr-map :body (.toString body)})

      (and (exists? js/FormData) (instance? js/FormData body))
      (let [tmp        (js/Response. body)
            ctype      (.get (.-headers tmp) "content-type")
            clean-hdrs (into {}
                             (remove (fn [[k _]]
                                       (contains? #{"content-type"
                                                    "content-length"
                                                    "transfer-encoding"}
                                                  (str/lower-case (str k)))))
                             hdr-map)]
        (p/let [ab (.arrayBuffer tmp)]
          {:method  method
           :headers (cond-> clean-hdrs ctype (assoc "Content-Type" ctype))
           :body    (js/Uint8Array. ab)}))

      (and (exists? js/Blob) (instance? js/Blob body))
      (p/let [ab (.arrayBuffer body)]
        {:method method :headers hdr-map :body (js/Uint8Array. ab)})

      (instance? js/ArrayBuffer body)
      (p/resolved {:method method :headers hdr-map :body (js/Uint8Array. body)})

      (and (exists? js/ArrayBuffer) (js/ArrayBuffer.isView body))
      (p/resolved {:method  method
                   :headers hdr-map
                   :body    (js/Uint8Array. (.-buffer body)
                                            (.-byteOffset body)
                                            (.-byteLength body))})

      :else
      (p/resolved {:method method :headers hdr-map :body (str body)}))))

(defn- proxy-result->response [{:keys [status url headers body body-encoding]}]
  (let [code     (int (or status 200))
        null-b?  (contains? #{101 204 205 304} code)
        hdr-obj  (js/Headers.)
        _        (doseq [[k v] headers]
                   (when (some? v)
                     (try
                       (.set hdr-obj
                             (if (keyword? k) (subs (str k) 1) (str k))
                             (str v))
                       (catch :default _ nil))))
        _        (when (and (string? url) (not (str/blank? url)))
                   (try
                     (.set hdr-obj "x-paradise-final-url" url)
                     (catch :default _ nil)))
        raw-body (when-not null-b?
                   (cond
                     (nil? body)                nil
                     (= body-encoding "base64") (base64->uint8 (str body))
                     (string? body)             body
                     :else                      body))
        resp     (js/Response. raw-body #js {:status code :headers hdr-obj})]
    (when (and (string? url) (not (str/blank? url)))
      (try
        (js/Object.defineProperty resp "url" #js {:value url :configurable true})
        (catch :default _ nil)))
    resp))

(defn- worker-should-probe-proxy? []
  (let [mode @!worker-proxy-mode]
    (or (= mode :native)
        (= mode :unknown)
        (and (= mode :unmounted)
             (>= (js/Date.now) @!worker-next-probe-ms)))))

(defn try-native-fetch [final-url opts safe-headers]
  (let [hdr-map (headers->map safe-headers)]
    (cond
      (and (exists? js/window) (some? (active-main-proxy-fn)))
      (p/let [norm (normalize-request-payload opts hdr-map)
              res  (execute-main-proxy! (assoc norm :url final-url))]
        (cond
          (not (:supported res)) nil
          (:error res)           (throw (js/TypeError. (str "NetworkError: " (:error res))))
          :else                  (proxy-result->response res)))

      (and (worker-mesh-available?) (worker-should-probe-proxy?))
      (let [probe-timeout (when (not= @!worker-proxy-mode :native) 2500)]
        (p/let [norm (normalize-request-payload opts hdr-map)
                res  (chan->promise
                      (mesh/do-with-thread! :main-thread
                                            {:handler   :proxy-request
                                             :arguments (assoc norm :url final-url)})
                      probe-timeout)]
          (cond
            (= (:status res) "timeout")
            (do
              (js/console.warn
               "Host proxy RPC timed out; falling back to standard fetch.")
              nil)

            (= (:status res) "error")
            nil

            (false? (:supported res))
            (do
              (reset! !worker-proxy-mode :unmounted)
              (reset! !worker-next-probe-ms (+ (js/Date.now) 3000))
              nil)

            (:error res)
            (do
              (reset! !worker-proxy-mode :native)
              (reset! !worker-next-probe-ms 0)
              (throw (js/TypeError. (str "NetworkError: " (:error res)))))

            (:supported res)
            (do
              (reset! !worker-proxy-mode :native)
              (reset! !worker-next-probe-ms 0)
              (proxy-result->response res))

            :else
            nil)))

      :else
      (p/resolved nil))))

(def rpc-handler-keys
  #{:proxy-request
    :proxy-status
    :prepare-stream
    :clear-stream
    :prepare-frame
    :clear-frame
    :prepare-websocket
    :clear-websocket
    :open-auth-window
    :close-auth-window
    :auth-challenge})

(defn- unwrap-url-or-host [opts]
  (if (map? opts) (:url-or-host opts) opts))

(defn- register-async-rpc! [k handler-fn]
  (worker/register k (fn [args] (go (<p! (handler-fn args))))))

(when (exists? js/window)
  (ensure-proxy-ready-listener!)
  (doseq [[k f] [[:proxy-request execute-main-proxy!]
                 [:proxy-status (fn [_] (proxy-available?))]
                 [:prepare-stream prepare-stream-url!]
                 [:clear-stream #(clear-stream-url! (unwrap-url-or-host %))]
                 [:prepare-frame prepare-frame-url!]
                 [:clear-frame #(clear-frame-url! (unwrap-url-or-host %))]
                 [:prepare-websocket prepare-websocket!]
                 [:clear-websocket #(clear-websocket! (unwrap-url-or-host %))]
                 [:open-auth-window open-auth-url!]
                 [:close-auth-window (fn [_] (close-auth-url!))]]]
    (register-async-rpc! k f)))