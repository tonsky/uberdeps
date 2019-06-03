(ns uberdeps.aot
  (:require
    [clojure.edn :as edn]
    [uberdeps.api :as api]
    [clojure.string :as str]
    [clojure.java.io :as io]
    [clojure.tools.deps.alpha :as deps])
  (:import
    [java.nio.file Files]
    [java.nio.file.attribute FileAttribute]))


(defn rm-dir
  "Recursively delete a directory."
  [path]
  (let [f (clojure.java.io/file path)] 
    (when (.exists f)
      (let [items (.listFiles f)
            {dirs true files false} (group-by #(.isDirectory %) items)]
        (doseq [d dirs] (rm-dir d))
        (doseq [f' files] (.delete f'))
        (.delete f)))))

(defn create-temp-dir 
  ([]
   (str (Files/createTempDirectory "uberdeps" (into-array FileAttribute []))))
  ([root]
   (str (Files/createTempDirectory 
          (.toPath (clojure.java.io/file root))
          "uberdeps"
          (into-array FileAttribute [])))))

(defn transform-deps [deps {:keys [aliases]}]
  (let [deps-map (->> deps
                      (@#'clojure.tools.deps.alpha.reader/canonicalize-all-syms)
                      (merge-with merge
                                  {:mvn/repos
                                   {"central" {:url "https://repo1.maven.org/maven2/"}
                                    "clojars" {:url "https://repo.clojars.org/"}}}))]
    (-> deps-map
        (dissoc :aliases)
        (assoc :args-map (deps/combine-aliases deps-map aliases)))))

(defn -main [& {:as args}]
  (let [deps-file (or (get args "--deps-file") "deps.edn")
        target    (or (get args "--target")
                    (as-> (io/file ".") %
                      (.getCanonicalFile %)
                      (.getName %)
                      (str "target/" % ".jar")))
        aliases   (-> (or (get args "--aliases") "")
                    (str/split  #":")
                    (->> (remove str/blank?)
                      (map keyword)
                      (into #{})))
        level     (keyword (or (get args "--level") "debug"))
        namespaces (map symbol (clojure.string/split (get args "--ns") #","))
        _ (assert (not-empty namespaces) "Specify which namespace should be compiled")
        temp-dir (create-temp-dir "")
        exclusion-file (clojure.java.io/file (get args "--exclusion-file"))
        exclusion-patterns (map
                             re-pattern
                             (clojure.string/split (get args "--exclude" "clj$|cljs$|cljc$") #","))]
    (try
      (binding [api/level level
                api/exclude (fn [path] 
                              (when (or
                                      (api/exclude-default path)
                                      (some #(re-find % path) exclusion-patterns))
                                (when (#{:debug :info} level)
                                  (println "! Excluding path " path))
                                true))
                *compile-path* temp-dir]
        (rm-dir temp-dir)
        (clojure.java.io/make-parents temp-dir)
        (.mkdir (clojure.java.io/file temp-dir))
        (doseq [n namespaces]
          (when (#{:debug :info} level)
            (println "Excluding patterns: " exclusion-patterns))
          (compile n))
        (api/package
          (update
            (edn/read-string (slurp deps-file))
            :paths conj temp-dir)
          target
          {:aliases aliases}))
      (catch Exception e
        (.printStackTrace e)
        nil)
      (finally 
        (rm-dir temp-dir)
        (shutdown-agents)))))
