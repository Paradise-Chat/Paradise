(ns build-hooks
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn copy-runtime-assets
  {:shadow.build/stage :flush}
  [build-state & _args]

  (let [assets
        [["node_modules/ffi-bindings/src/generated-compat/wasm-bindgen/index_bg.wasm"
          "build/ffi-bindings/wasm-bindgen/index_bg.wasm"]

         ["node_modules/simple-rnnoise-wasm/dist/rnnoise.mjs"
          "build/rnnoise/rnnoise.mjs"]

         ["node_modules/simple-rnnoise-wasm/dist/rnnoise.mjs.map"
          "build/rnnoise/rnnoise.mjs.map"]

         ["node_modules/simple-rnnoise-wasm/dist/rnnoise.worklet.js"
          "build/rnnoise/rnnoise.worklet.js"]

         ["node_modules/simple-rnnoise-wasm/dist/rnnoise.worklet.js.map"
          "build/rnnoise/rnnoise.worklet.js.map"]

         ["node_modules/simple-rnnoise-wasm/dist/rnnoise.wasm"
          "build/rnnoise/rnnoise.wasm"]]]

    (doseq [[source-path target-path] assets]
      (let [source (io/file source-path)
            target (io/file target-path)]

        (if (.exists source)
          (do
            (io/make-parents target)
            (io/copy source target)
            (println
             "--- Copied runtime asset:"
             source-path
             "->"
             target-path))

          (when-not (.endsWith source-path ".map")
            (println
             "--- WARNING: runtime asset not found:"
             source-path))))))

  build-state)

(defn include-themes
  {:shadow.build/stage :flush}
  [build-state & args]
  (let [source-dir (io/file "themes")
        target-root (io/file "build/themes")
        source-path (.getAbsolutePath source-dir)]
    (when (.exists source-dir)
      (doseq [source-file (file-seq source-dir)
              :when (.isFile source-file)]
        (let [file-path (.getAbsolutePath source-file)
              rel-path (subs file-path (count source-path))
              target-file (io/file target-root (if (.startsWith rel-path "/")
                                                (subs rel-path 1)
                                                rel-path))]
          (io/make-parents target-file)
          (io/copy source-file target-file)))
      (println "--- All themes recursively copied to resources/public/themes ---"))
    build-state))

(defn include-css
  {:shadow.build/stage :flush}
  [build-state & args]
  (let [source-dir (io/file "css")
        target-root (io/file "build/css")
        source-path (.getAbsolutePath source-dir)]
    (when (.exists source-dir)
      (doseq [source-file (file-seq source-dir)
              :when (.isFile source-file)]
        (let [file-path (.getAbsolutePath source-file)
              rel-path (subs file-path (count source-path))
              target-file (io/file target-root (if (.startsWith rel-path "/")
                                                 (subs rel-path 1)
                                                 rel-path))]
          (io/make-parents target-file)
          (io/copy source-file target-file)))
      (println "--- All themes recursively copied to resources/public/themes ---"))
    build-state))

(defn include-root-files
  {:shadow.build/stage :compile-prepare}
  [build-state & args]
  (let [resource-dir "build/"
        root-files   ["index.html" "config.edn" "i18n.edn"]]
    (doseq [f root-files
            :let [source (io/file f)
                  target (io/file resource-dir f)]]
      (when (.exists source)
        (io/make-parents target)
        (io/copy source target))))
  build-state)

(defn copy-element-call
  {:shadow.build/stage :flush}
  [build-state & args]
  (let [source (clojure.java.io/file "node_modules/@element-hq/element-call-embedded/dist")
        target (clojure.java.io/file "build/element-call")]
    (if (.exists source)
      (do
        (when (.exists target)
          (run! #(clojure.java.io/delete-file % true) (reverse (file-seq target))))
        (doseq [f (file-seq source)]
          (let [relative-path (.substring (.getPath f) (count (.getPath source)))
                dest-file (clojure.java.io/file target (if (.startsWith relative-path "/")
                                                         (.substring relative-path 1)
                                                         relative-path))]
            (if (.isDirectory f)
              (.mkdirs dest-file)
              (do
                (clojure.java.io/make-parents dest-file)
                (clojure.java.io/copy f dest-file)))))
        (println "--- Element Call Bridge (Full Dist) copied to build/element-call ---"))
      (println "!!! Warning: Element Call source not found in node_modules !!!"))
    build-state))

(defn stamp-version
  {:shadow.build/stage :configure}
  [build-state & _]
  (let [pkg-json    (slurp "package.json")
        version     (second (re-find #"\"version\"\s*:\s*\"([^\"]+)\"" pkg-json))
        config-path "config.edn"
        config      (try (edn/read-string (slurp config-path))
                         (catch Exception _ {}))
        updated     (assoc config :version version)]
    (spit config-path (pr-str updated))
    (println (str "Stamped config.edn with version " version))
    build-state))
