(ns plugin-build
  (:require
   [paradise.shared.utils.macros]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [clojure.walk :as walk]
   [clojure.string :as str]
   [cljs.tagged-literals :as tags]
   [clojure.core.async]
   [clojure.tools.analyzer.passes.jvm.validate]))

(defn inject-defer-boundaries [form]
  (walk/postwalk
   (fn [x]
     (if (and (seq? x)
              (symbol? (first x))
              (#{'defn 'defoverride} (first x))
              (symbol? (second x)))
       (let [sym (second x)]
         (if (:defer (meta sym))
           (let [clean-sym (with-meta sym (dissoc (meta sym) :defer))
                 clean-x   (cons (first x) (cons clean-sym (drop 2 x)))]
             (list 'do
                   clean-x
                   (list 'clojure.core/aset clean-sym "$defer_boundary" true)))
           x))
       x))
   form))

(defn desugar-js-literals [form]
  (walk/prewalk
   (fn [x]
     (if (instance? cljs.tagged_literals.JSValue x)
       (list 'cljs.core/clj->js (desugar-js-literals (:val x)))
       x))
   form))


(defn sanitize-ast [form]
  (walk/prewalk
    (fn [x]
      (cond
        (record? x) (into {} x)
        (or (seq? x) (vector? x) (map? x) (set? x) (list? x)) x
        (or (symbol? x) (keyword? x) (string? x) (number? x) (boolean? x) (nil? x)) x
        (instance? java.util.regex.Pattern x) x
        (instance? cljs.tagged_literals.JSValue x)
        (list 'cljs.core/clj->js (desugar-js-literals (:val x)))
        (instance? java.lang.Class x)
        (symbol (.getName ^java.lang.Class x))
        (instance? clojure.lang.Var x)
        (symbol (str (.name (.ns ^clojure.lang.Var x)) "/" (.sym ^clojure.lang.Var x)))
        :else
        (do
          (println "\n SCRUBBED JVM OBJECT \n")
          (println "Type: " (type x))
          (println "Value:" x)
          (println "----------------------------------\n")
          (symbol (str "ROGUE_OBJECT_" (.getSimpleName (type x)))))))
    form))


(def catch-all-targets
  #{:default
    'java.lang.Throwable java.lang.Throwable 'Throwable
    'java.lang.Exception java.lang.Exception 'Exception
    'js/Error 'js/Object})



(defn scrub-jvm-artifacts [form]
  (walk/prewalk
    (fn [x]
      (cond
        (and (seq? x) (= (first x) 'clojure.lang.Var/getThreadBindingFrame)) nil
        (and (seq? x) (= (first x) 'clojure.lang.Var/resetThreadBindingFrame)) nil
        (and (seq? x) (= (first x) 'java.util.concurrent.atomic.AtomicReferenceArray.)) (list 'clojure.core/make-array (second x))

        (and (seq? x)
             (symbol? (first x))
             (= (name (first x)) "instance?")
             (contains? catch-all-targets (second x)))
        (list 'clojure.core/some? (nth x 2))

        (or (= x 'java.lang.Throwable) (= x java.lang.Throwable)) ':default

        (and (seq? x) (symbol? (first x)) (= (name (first x)) "aget-object") (str/includes? (namespace (first x)) "ioc-macros"))
        (cons 'clojure.core/aget (rest x))

        (and (seq? x) (symbol? (first x)) (= (name (first x)) "aset-object") (str/includes? (namespace (first x)) "ioc-macros"))
        (cons 'clojure.core/aset (rest x))

        (= x 'clojure.core/identical?) 'cljs.core/keyword-identical?

        (symbol? x)
        (let [ns-str   (namespace x)
              name-str (name x)]
          (cond
            (and ns-str (str/includes? ns-str "ioc-macros"))
            (case (str/upper-case name-str)
              "FN-IDX"           0
              "STATE-IDX"        1
              "VALUE-IDX"        2
              "BINDINGS-IDX"     3
              "EXCEPTION-FRAMES" 4
              "USER-START-IDX"   5
              (symbol "cljs.core.async.impl.ioc-helpers" name-str))

            ns-str
            (let [cljs-ns (str/replace ns-str #"^clojure" "cljs")]
              (symbol cljs-ns name-str))

            :else x))
        :else x))
    form))

(defn custom-macro? [sym]
  (when (symbol? sym)
    (let [macro-var (ns-resolve 'paradise.shared.utils.macros (symbol (name sym)))]
      (and macro-var
           (:macro (meta macro-var))
           (= (ns-name (:ns (meta macro-var))) 'paradise.shared.utils.macros)))))


(defn expand-macros [form]
  (with-redefs [clojure.tools.analyzer.passes.jvm.validate/validate identity]
    (walk/prewalk
      (fn [x]
        (if (seq? x)
          (let [sym (first x)]
            (cond
              (#{'go 'cljs.core.async/go} sym)
              (let [unrolled-promises (walk/prewalk
                                        (fn [node]
                                          (if (and (seq? node) (= (first node) '<p!))
                                            (list '<! (list 'cljs.core.async.interop/p->c (second node)))
                                            node))
                                        (rest x))
                    qualified         (walk/prewalk
                                        #(case %
                                           <! 'clojure.core.async/<!
                                           cljs.core.async/<! 'clojure.core.async/<!
                                           put! 'clojure.core.async/put!
                                           cljs.core.async/put! 'clojure.core.async/put!
                                           timeout 'clojure.core.async/timeout
                                           cljs.core.async/timeout 'clojure.core.async/timeout
                                           alts! 'clojure.core.async/alts!
                                           cljs.core.async/alts! 'clojure.core.async/alts!
                                           %)
                                        unrolled-promises)
                    expanded          (macroexpand-1 `(clojure.core.async/go ~@qualified))]
                (scrub-jvm-artifacts expanded))

              (custom-macro? sym)
              (let [qualified-macro (symbol "paradise.shared.utils.macros" (name sym))]
                (macroexpand-1 (cons qualified-macro (rest x))))
              :else x))
          x))
      form)))

(defn read-cljs [s]
  (binding [*data-readers* tags/*cljs-data-readers*]
    (read-string s)))

(defn process-and-expand-file [file-path]
  (let [raw-code     (slurp file-path)
        wrapped-text (str "(do\n" raw-code "\n)")
        ast-form     (-> (read-cljs wrapped-text)
                         desugar-js-literals)
        ast-deferred (inject-defer-boundaries ast-form)
        expanded-ast (expand-macros ast-deferred)
        pure-ast     (sanitize-ast expanded-ast)]
    (pr-str pure-ast)))

(defn determine-target-from-ns [raw-code]
  (if-let [match (re-find #"\(ns\s+([^\s\)]+)" raw-code)]
    (let [ns-name (second match)]
      (cond
        (str/includes? ns-name ".ui") :ui
        (str/includes? ns-name ".engine") :engine
        :else :unknown))
    :unknown))

(defn process-src-directory [dir-path]
  (let [cljs-files (->> (io/file dir-path)
                        file-seq
                        (filter #(and (.isFile %) (str/ends-with? (.getName %) ".cljs"))))]
    (mapv (fn [file]
            (let [raw-code (slurp file)
                  target   (determine-target-from-ns raw-code)
                  ns-name  (second (re-find #"\(ns\s+([^\s\)]+)" raw-code))
                  expanded (process-and-expand-file (.getPath file))]
              {:ns ns-name
               :target target
               :code expanded}))
          cljs-files)))

(defn -main [& args]
  (let [raw-meta      (edn/read-string (slurp "manifest.edn"))
        manifest-data (assoc (dissoc raw-meta :modules)
                             :path (or (:path raw-meta) "plugin.edn"))
        modules       (process-src-directory "src")
        plugin-data   {:modules modules}
        out-dir       (io/file "dist")
        manifest-file (io/file out-dir "manifest.edn")
        plugin-file   (io/file out-dir "plugin.edn")]

    (when-not (.exists out-dir)
      (.mkdirs out-dir))

    (spit manifest-file (pr-str manifest-data))
    (spit plugin-file (pr-str plugin-data))
    (println "Plugin packed and AOT-expanded successfully.")
    (println "Wrote manifest to" (.getPath manifest-file))
    (println "Wrote" (count modules) "compiled modules to" (.getPath plugin-file))))
