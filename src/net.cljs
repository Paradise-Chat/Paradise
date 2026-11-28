(ns net
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [paradise.shared.net.auth :as net-auth]
            [paradise.shared.net.proxy :as net-proxy]
            [promesa.core :as p]))

(def !auth-context net-auth/!auth-context)
(def ensure-protocol net-auth/ensure-protocol)
(def set-auth-context! net-auth/set-auth-context!)
(def await-auth net-auth/await-auth)

(def register-native-proxy! net-proxy/register-native-proxy!)
(def host-proxy-provider net-proxy/host-proxy-provider)
(def on-proxy-ready! net-proxy/on-proxy-ready!)
(def mount-host-proxy! net-proxy/mount-host-proxy!)
(def proxy-available? net-proxy/proxy-available?)
(def prepare-stream-url! net-proxy/prepare-stream-url!)
(def clear-stream-url! net-proxy/clear-stream-url!)
(def prepare-frame-url! net-proxy/prepare-frame-url!)
(def clear-frame-url! net-proxy/clear-frame-url!)
(def prepare-websocket! net-proxy/prepare-websocket!)
(def clear-websocket! net-proxy/clear-websocket!)
(def open-auth-url! net-proxy/open-auth-url!)
(def close-auth-url! net-proxy/close-auth-url!)
(def set-auth-challenge! net-proxy/set-auth-challenge!)

(defn fetch
  ([url] (fetch url nil))
  ([raw-url options]
   (p/let [opts            (cond
                             (nil? options) #js {}
                             (map? options) (reduce-kv (fn [acc k v]
                                                         (aset acc (name k) v)
                                                         acc)
                                                       #js {}
                                                       options)
                             :else (js/Object.assign #js {} options))
           headers         (or (.-headers opts) #js {})
           hdr-map         (net-proxy/headers->map headers)
           has-auth-hdr?   (boolean
                            (some #(= "authorization" (str/lower-case (str %)))
                                  (keys hdr-map)))
           raw-auth        (gobj/get opts "auth")
           auth-disabled?  (false? raw-auth)
           explicit-auth?  (true? raw-auth)
           _               (when (and explicit-auth? (not has-auth-hdr?))
                             (net-auth/await-auth))
           {:keys [token base-url auth-fn] :as ctx} @net-auth/!auth-context
           final-url       (cond
                             (or (str/starts-with? raw-url "http://")
                                 (str/starts-with? raw-url "https://"))
                             raw-url

                             (and explicit-auth? base-url (str/starts-with? raw-url "/"))
                             (str base-url raw-url)

                             (or (str/starts-with? raw-url "/")
                                 (str/starts-with? raw-url "./")
                                 (str/starts-with? raw-url "../")
                                 (not (str/includes? raw-url "/")))
                             raw-url

                             :else
                             (net-auth/ensure-protocol raw-url))
           is-absolute?    (or (str/starts-with? final-url "http://")
                               (str/starts-with? final-url "https://"))
           same-auth-org?  (and is-absolute? base-url (net-auth/same-origin-pair? final-url base-url))
           safe-headers    (clj->js hdr-map)
           wants-auth?     (and (not auth-disabled?)
                                (not has-auth-hdr?)
                                (or same-auth-org?
                                    (and explicit-auth?
                                         (or (nil? base-url) (not is-absolute?)))))
           dyn-headers     (when (and wants-auth? auth-fn)
                             (auth-fn final-url opts))]
     (when wants-auth?
       (cond
         dyn-headers
         (doseq [[k v] (if (map? dyn-headers) dyn-headers (js->clj dyn-headers))]
           (aset safe-headers (name k) v))

         (:headers ctx)
         (doseq [[k v] (:headers ctx)]
           (aset safe-headers (name k) v))

         token
         (aset safe-headers "Authorization" (str "Bearer " token))))
     (js-delete opts "auth")
     (aset opts "headers" safe-headers)
     (when (nil? (gobj/get opts "referrerPolicy"))
       (aset opts "referrerPolicy" "no-referrer"))
     (when (and (or wants-auth? has-auth-hdr?) (nil? (gobj/get opts "credentials")))
       (aset opts "credentials" "omit"))
     (-> (p/let [proxied-resp (when (and is-absolute? (not (net-auth/same-origin? final-url)))
                                (net-proxy/try-native-fetch final-url opts safe-headers))]
           (if proxied-resp
             proxied-resp
             (js/fetch final-url opts)))
         (p/catch (fn [err]
                    (js/console.error "Fetch failed on URL:" (net-auth/redact-url-for-log final-url))
                    (throw err)))))))