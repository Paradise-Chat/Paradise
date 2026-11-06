(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [rewrite-clj.zip :as z]
            [cljfmt.core :as cljfmt]))

(def state-file ".shadow-cljs/engine-state.edn")

(def web-root "build")

(defn find-manifest [engine-path]
  (let [f1 (io/file engine-path "config.edn")
        f2 (io/file engine-path "dist" "manifest.edn")]
    (cond
      (.exists f1) f1
      (.exists f2) f2
      :else nil)))

(defn fetch-registry []
  (let [config (try (edn/read-string (slurp "config.edn")) (catch Exception _ {}))
        registry-url (:registry-url config "https://paradise-chat.github.io/community-repo/registry.edn")]
    (try
      (println "Downloading registry from:" registry-url)
      (let [data (slurp registry-url)]
        (spit "registry.edn" data)
        (println "Successfully downloaded registry.edn"))
      (catch Exception e
        (if (.exists (io/file "registry.edn"))
          (println "WARNING: Failed to fetch registry. Cache used.")
          (do
            (println "FATAL ERROR: Failed to fetch registry and no cached registry.edn exists!")
            (System/exit 1)))))))

(defn parse-node [s]
  (z/node (z/of-string s)))

(defn find-key [zloc k]
  (loop [loc (z/down zloc)]
    (when loc
      (if (= (z/sexpr loc) k)
        loc
        (when-let [vloc (z/right loc)]
          (recur (z/right vloc)))))))


(defn z-assoc [zloc k v]
  (if-let [kloc (find-key zloc k)]
    (let [vloc (z/right kloc)]
      (-> vloc (z/replace v) z/up))
    (if-let [last-loc (some-> zloc z/down z/rightmost)]
      (-> last-loc
          (z/insert-right v)
          (z/insert-space-right 1)
          (z/insert-right k)
          (z/insert-newline-right)
          z/up)
      (-> zloc
          (z/append-child k)
          (z/append-child v)))))

(defn z-dissoc [zloc k]
  (if-let [kloc (find-key zloc k)]
    (let [vloc (z/right kloc)]
      (if vloc
        (-> vloc z/remove z/remove z/up)
        (-> kloc z/remove z/up)))
    zloc))


(defn edit-edn [file edit-fn]
  (when (.exists (io/file file))
    (-> (z/of-file file)
        edit-fn
        z/root-string
        cljfmt/reformat-string
        (->> (spit file)))))

(defn get-unique-deps [engine-path]
  (let [script (str "const fs = require('fs');\n"
                    "const engine = JSON.parse(fs.readFileSync('" engine-path "/package.json'));\n"
                    "const client = JSON.parse(fs.readFileSync('package.json'));\n"
                    "const toAdd = Object.entries(engine.dependencies || {})\n"
                    "  .filter(([k]) => !client.dependencies[k])\n"
                    "  .map(([k, v]) => k + '@' + v);\n"
                    "if (toAdd.length > 0) { console.log(toAdd.join(':::')); }")
        result (b/process {:command-args ["node" "-e" script]
                           :out :capture})]
    (when (and (zero? (:exit result)) (string? (:out result)))
      (let [out (str/trim (:out result))]
        (when (not-empty out)
          (str/split out #":::"))))))

(defn copy-manifest-files! [engine-path copy-files]
  (doseq [{:keys [from to]} copy-files]
    (let [engine-src (io/file engine-path from)
          client-src (io/file from)
          src        (if (.exists engine-src) engine-src client-src)
          clean-to   (str/replace to #"^/" "")
          dest       (io/file web-root clean-to)]
      (if (.exists src)
        (b/copy-file {:src (.getPath src) :target (.getPath dest)})
        (println "WARNING: Could not find file to copy:" from)))))

(defn delete-manifest-files! [copy-files]
  (doseq [{:keys [to]} copy-files]
    (let [clean-to (str/replace to #"^/" "")
          dest     (io/file web-root clean-to)]
      (b/delete {:path (.getPath dest)}))))

(defn read-state []
  (let [f (io/file state-file)]
    (if (.exists f)
      (try (edn/read-string (slurp f)) (catch Exception _ {}))
      {})))

(defn prep [_]
  (fetch-registry)
  (b/process {:command-args ["npx" "shadow-cljs" "release" "app"]}))

(defn prep-engine! [engine-path copy-files force-prep?]
  (let [build-file (io/file engine-path "build.clj")
        missing?   (some #(not (.exists (io/file engine-path (:from %)))) copy-files)]
    (when (and (.exists build-file) (or force-prep? missing?))
      (println "Running prep in engine:" engine-path)
      (b/process {:dir          engine-path
                  :command-args ["clojure" "-T:build" "prep"]}))))

(defn mount [{:keys [engine-path prep]}]
  (when-not engine-path
    (println "FATAL ERROR: :engine-path is required!")
    (System/exit 1))
  (let [engine-path (str engine-path)
        manifest-file (find-manifest engine-path)]
    (when-not manifest-file
      (println "FATAL ERROR: Could not find manifest.edn in" engine-path)
      (System/exit 1))
    (let [manifest (edn/read-string (slurp manifest-file))
          {:keys [deps-sym module-id config-key path shadow-module copy-files]} manifest
          config-kw (keyword config-key)
          dev-path (str "/" (last (str/split path #"/")))
          deps (get-unique-deps engine-path)
          current-state (read-state)
          new-state (assoc current-state module-id {:manifest manifest :deps deps})
          dev-manifest (assoc manifest :path dev-path)]
      (edit-edn "deps.edn"
                (fn [zloc]
                  (-> zloc
                      (z/find-value z/next :deps)
                      z/right
                      (z-assoc deps-sym {:local/root engine-path}))))
      (edit-edn "shadow-cljs.edn"
                (fn [zloc]
                  (-> zloc
                      (z/find-value z/next :app)
                      (z/find-value z/next :modules)
                      z/right
                      (z-assoc module-id shadow-module))))
      (edit-edn "config.edn"
                (fn [zloc]
                  (if-let [engines-loc (z/find-value zloc z/next :engines)]
                    (-> engines-loc z/right (z-assoc config-kw dev-manifest))
                    (z-assoc zloc :engines {config-kw dev-manifest}))))
      (io/make-parents state-file)
      (spit state-file (pr-str new-state))
      (when (seq deps)
        (b/process {:command-args (into ["pnpm" "add" "--ignore-scripts" "--prefer-offline"] deps)}))
      (when (seq copy-files)
        (prep-engine! engine-path copy-files prep)
        (copy-manifest-files! engine-path copy-files))
      (println "Successfully mounted:" module-id))))

(defn dismount [{:keys [module-ids]}]
  (let [state-f (io/file state-file)]
    (if (.exists state-f)
      (let [current-state (read-state)
            targets (if (seq module-ids) (map keyword module-ids) (keys current-state))]
        (if (empty? targets)
          (println "No engines to dismount.")
          (let [remaining-state
                (reduce (fn [state target-id]
                          (if-let [engine-state (get state target-id)]
                            (let [{:keys [deps-sym module-id config-key copy-files]} (:manifest engine-state)
                                  deps (:deps engine-state)
                                  config-kw (keyword config-key)]
                              (edit-edn "deps.edn"
                                        (fn [zloc]
                                          (-> zloc
                                              (z/find-value z/next :deps)
                                              z/right
                                              (z-dissoc deps-sym))))
                              (edit-edn "shadow-cljs.edn"
                                        (fn [zloc]
                                          (-> zloc
                                              (z/find-value z/next :app)
                                              (z/find-value z/next :modules)
                                              z/right
                                              (z-dissoc module-id))))
                              (edit-edn "config.edn"
                                        (fn [zloc]
                                          (if-let [engines-loc (z/find-value zloc z/next :engines)]
                                            (-> engines-loc z/right (z-dissoc config-kw))
                                            zloc)))
                              (when (seq copy-files)
                                (delete-manifest-files! copy-files))
                              (when (seq deps)
                                (let [pkg-names (map #(str/replace % #"@[^@]+$" "") deps)]
                                  (b/process {:command-args (into ["pnpm" "remove"] pkg-names)})))
                              (println "Successfully dismounted engine:" target-id)
                              (dissoc state target-id))
                            state))
                        current-state
                        targets)]
            (if (empty? remaining-state)
              (do (.delete state-f) (println "All engines dismounted."))
              (spit state-file (pr-str remaining-state))))))
      (println "No active mount state found. Skipping dismount."))))