(ns paradise.shared.sci-runner.shared
  (:require [sci.core :as sci]
            [cljs.core.async.impl.dispatch :as dispatch]
            [cljs.core.async.impl.ioc-helpers :as ioc-helpers]
            [cljs.core.async.interop]
            [cljs.core.async]
            [promesa.core]
            [clojure.string]
            [paradise.shared.utils.macros :refer [expose-ns]]))

(defn jvm-return-chan [state value]
  (let [c (aget state 5)]
    (if-not (nil? value)
      (cljs.core.async/put! c value (fn [_] (cljs.core.async/close! c)))
      (cljs.core.async/close! c))
    c))

(defn jvm-process-exception [state ex]
  (let [frames (aget state 4)]
    (loop [frames frames]
      (if (empty? frames)
        (throw ex)
        (let [{:keys [catch-block catch-exception-class exception-local continue-block]} (first frames)]
          (if (and catch-block
                   (or (= catch-exception-class :default)
                       (= catch-exception-class 'java.lang.Throwable)
                       (= catch-exception-class 'js/Error)))
            (do
              (aset state 1 catch-block)
              (aset state exception-local ex)
              (aset state 4 (rest frames))
              :recur)
            (do
              (aset state 1 continue-block)
              (aset state 4 (rest frames))
              (aset state 2 ex)
              :recur)))))))

(defn jvm-run-state-machine-wrapped [state]
  (try
    (ioc-helpers/run-state-machine state)
    (catch :default ex
      (when-let [c (aget state 5)]
        (cljs.core.async/close! c))
      (throw ex))))

(defn safe-instance? [c x]
  (if (or (= c :default) (not (fn? c)))
    (some? x)
    (instance? c x)))

(def async-namespaces
  {'clojure.core    {'instance? safe-instance?}
   'cljs.core       {'instance? safe-instance?}
   'cljs.core.async (expose-ns cljs.core.async)
   'cljs.core.async.interop
   {'p->c cljs.core.async.interop/p->c
    '<p!  (sci/new-macro-var '<p!
                             (fn [_ _ p]
                               (list 'cljs.core.async/<! (list 'cljs.core.async.interop/p->c p))))}
   'cljs.core.async.impl.dispatch   {'run dispatch/run}
   'cljs.core.async.impl.ioc-helpers (merge
                                       (expose-ns cljs.core.async.impl.ioc-helpers)
                                       {'return-chan               jvm-return-chan
                                        'process-exception         jvm-process-exception
                                        'run-state-machine-wrapped jvm-run-state-machine-wrapped
                                        'aset-all!                 (sci/new-macro-var 'aset-all!
                                                                     (fn [_ _ obj & key-vals]
                                                                       (let [sym (gensym "state_arr")]
                                                                         (concat (list 'let (vector sym obj))
                                                                                 (map (fn [[k v]] (list 'aset sym k v))
                                                                                      (partition 2 key-vals))
                                                                                 (list sym)))))})})

(def common-namespaces
  (merge async-namespaces
         {'promesa.core   (expose-ns promesa.core)
          'clojure.string (expose-ns clojure.string)}))

(def common-classes
  {'js   goog/global :allow :all
   'Date js/Date})
