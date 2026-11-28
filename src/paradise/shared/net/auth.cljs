(ns ^:virtualizer/skip paradise.shared.net.auth
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [promesa.core :as p]))

(defn valid-http-url [raw]
  (when (and (string? raw) (not (str/blank? raw)))
    (try
      (let [u (js/URL. (str/trim raw))]
        (when (#{"http:" "https:"} (.-protocol u))
          u))
      (catch :default _ nil))))

(defn valid-http-url-str [raw]
  (some-> (valid-http-url raw) .-href))

(defn valid-ws-url [raw]
  (when (and (string? raw) (not (str/blank? raw)))
    (try
      (let [u (js/URL. (str/trim raw))]
        (when (#{"ws:" "wss:" "http:" "https:"} (.-protocol u))
          u))
      (catch :default _ nil))))

(defn same-origin-pair? [request-url base-url]
  (when-let [req-obj (valid-http-url request-url)]
    (when-let [base-obj (valid-http-url base-url)]
      (let [req-org  (.-origin req-obj)
            base-org (.-origin base-obj)]
        (and (= (.-protocol req-obj) (.-protocol base-obj))
             (not= req-org "null")
             (= req-org base-org))))))

(defn same-origin? [target-url]
  (try
    (let [loc (gobj/get js/globalThis "location")
          org (when loc (gobj/get loc "origin"))]
      (and (string? org)
           (not (str/blank? org))
           (= (.-origin (js/URL. target-url)) org)))
    (catch :default _ false)))

(defn redact-url-for-log [raw-url]
  (if-let [u (valid-http-url raw-url)]
    (str (.-origin u) (.-pathname u))
    (first (str/split (str raw-url) #"[?#]"))))

(defn ensure-protocol [url]
  (when (and (string? url) (not (str/blank? url)))
    (if (or (str/starts-with? url "http://")
            (str/starts-with? url "https://"))
      url
      (str "https://" url))))

(defonce !auth-context (atom {:token nil :base-url nil :headers nil :auth-fn nil}))

(defn set-auth-context!
  ([context-map]
   (if (nil? context-map)
     (reset! !auth-context {:token nil :base-url nil :headers nil :auth-fn nil})
     (let [base-url (when-let [u (:base-url context-map)]
                      (str/replace (ensure-protocol u) #"/+$" ""))]
       (reset! !auth-context (assoc context-map :base-url base-url)))))
  ([token base-url]
   (set-auth-context! {:token token :base-url base-url})))

(defn await-auth
  ([] (await-auth 3000))
  ([timeout-ms]
   (p/create
    (fn [resolve-fn]
      (let [{:keys [token base-url headers auth-fn] :as ctx} @!auth-context]
        (if (or (and token base-url) headers auth-fn)
          (resolve-fn ctx)
          (let [watch-key (keyword (gensym "auth-watch"))
                timer     (js/setTimeout
                           (fn []
                             (remove-watch !auth-context watch-key)
                             (resolve-fn @!auth-context))
                           timeout-ms)]
            (add-watch !auth-context watch-key
                       (fn [_ _ _ new-state]
                         (when (or (and (:token new-state) (:base-url new-state))
                                   (:headers new-state)
                                   (:auth-fn new-state))
                           (js/clearTimeout timer)
                           (remove-watch !auth-context watch-key)
                           (resolve-fn new-state)))))))))))