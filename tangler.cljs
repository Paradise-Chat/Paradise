(ns tangler
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as str]))

(defn tangle-file [f-path]
  (let [content (fs/readFileSync f-path "utf8")
        blocks (re-seq #"(?is)#\+begin_src\s+(?:clojurescript|css)\s+:tangle\s+([^\s\n]+).*?\n(.*?)\n#\+end_src" content)
        tangled-map (reduce (fn [acc [ _ rel-path code]]
                              (update acc rel-path (fnil conj []) code))
                            {}
                            blocks)]
    (doseq [[rel-path codes] tangled-map]
      (when (not= rel-path "no")
        (let [full-path (path/resolve (path/dirname f-path) rel-path)
              new-code (str/join "\n\n" codes)
              old-code (if (fs/existsSync full-path) (fs/readFileSync full-path "utf8") "")]
          (when (not= (str/trim old-code) (str/trim new-code))
            (fs/mkdirSync (path/dirname full-path) #js {:recursive true})
            (fs/writeFileSync full-path new-code)
            (println "  > Tangled (Updated):" full-path)))))))

(defn run-tangle-all []
  (let [docs-dir "docs"]
    (when (fs/existsSync docs-dir)
      (let [scan (fn scan [dir]
                   (doseq [f (fs/readdirSync dir)]
                     (when (not (str/starts-with? f "."))
                       (let [p (path/join dir f)
                             stat (fs/statSync p)]
                         (cond
                           (.isDirectory stat) (scan p)
                           (str/ends-with? f ".org") (tangle-file p))))))]
        (scan docs-dir)))))

(defn watch []
  (println "--- nbb Org-Tangle Watcher Active ---")
  (run-tangle-all)
  (js/setInterval run-tangle-all 1000))

(let [args (not-empty (js->clj (.slice js/process.argv 2)))]
  (if args
    (doseq [file args]
      (if (fs/existsSync file)
        (do
          (println "Tangling single file:" file)
          (tangle-file file))
        (println "File not found:" file)))
    (watch)))
