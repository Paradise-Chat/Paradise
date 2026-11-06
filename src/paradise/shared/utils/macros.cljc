(ns paradise.shared.utils.macros
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [cljs.analyzer.api :as ana]
   [cljs.env :as env]
   ))

(defmacro ocall
  [obj method & args]
  `(let [obj# ~obj
         m# ~(name method)
         f# (js/goog.object.get obj# m#)]
     (if (fn? f#)
       (.apply f# obj# (cljs.core/array ~@args))
       (throw (js/Error. (str "Method " m# " is not a function (is it a property?)"))))))


(defmacro oget
  "Property access for WASM objects that hides behind prototypes."
  [obj prop]
  `(js/goog.object.get ~obj ~(name prop)))


(defmacro register-and-capture [fn-hash form]
  (let [locals (keys &env)
        clean-ks (remove #(clojure.string/includes? (name %) "__") locals)
        env-map (into {} (map (fn [k] [(keyword (name k)) k]) clean-ks))
        bind-syms (mapv symbol (map name clean-ks))
        bind-form (if (empty? bind-syms) '_ `{:keys ~bind-syms})]
    `(do
       (clojure.core/swap! paradise.shared.client.registry/!anon-fns clojure.core/assoc ~fn-hash
                           (fn [~bind-form] ~form))
       (clojure.core/let [f# ~form]
         (clojure.core/aset f# "$fn_ptr" ~fn-hash)
         (clojure.core/aset f# "$env" ~env-map)
         f#))))


(defmacro export-engine [protocol-name bootstrap-fn]
  (let [prop-name  (str (str/capitalize (name protocol-name)) "EngineBootstrap")
        global-sym (symbol "js" (str "globalThis." prop-name))]
    `(set! ~global-sym ~bootstrap-fn)))

(defmacro defoverride [comp-name args & body]
  (let [comp-str  (name comp-name)
        local-sym (symbol comp-str)]
    `(do
       (defn ~local-sym ~args ~@body)
       (let [provided-kw# (keyword ~(str comp-name))
             target-kw#   (if (namespace provided-kw#)
                            provided-kw#
                            (first (filter #(= (name %) ~comp-str)
                                           (keys @paradise.shared.client.registry/!components))))
             final-kw#    (or target-kw# provided-kw#)]

         (swap! paradise.shared.client.registry/!active-overrides assoc final-kw#
                {:plugin-id @paradise.shared.sci-runner.ui/!current-eval-plugin
                 :fn ~local-sym})))))

(defmacro expose-ns [ns-sym]
  (let [public-vars (keys (ana/ns-publics env/*compiler* ns-sym))]
    (into {}
          (map (fn [v]
                 [`(quote ~v) (symbol (str ns-sym "/" v))])
               public-vars))))



(defmacro defui [comp-name args & body]
  (let [kw           (keyword comp-name)
        default-name (symbol (str comp-name "-default"))]
    `(do
       (defn ~default-name ~args ~@body)
       (swap! paradise.shared.client.registry/!components #(if (contains? % ~kw) % (assoc % ~kw ~default-name)))
       (defn ~comp-name [& args#]
         (let [override# (get @paradise.shared.client.registry/!active-overrides ~kw)
               live#     (if override#
                           (:fn override#)
                           (get @paradise.shared.client.registry/!components ~kw))]
           (into [live#] args#))))))



(defmacro load-static-config [path]
  (list 'quote (clojure.edn/read-string (slurp path))))