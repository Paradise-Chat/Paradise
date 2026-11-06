(ns paradise.engine.state)


(defonce !shared-app-db (atom nil))
(defonce !client (atom nil))
(defonce !asset-urls (atom nil))
(defonce !media-cache (atom {}))
(defonce !plugin-handlers (atom {}))
(defn register-handler [id handler-fn]
  (swap! !plugin-handlers assoc id handler-fn))
