(ns uberdeps.uberjar
  (:require
   [clojure.edn :as edn]
   [uberdeps.api :as api]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.tools.deps.alpha.util.dir :as deps.dir]))


(defn -main [& {:as args}]
  (let [deps-file  (or (get args "--deps-file") "deps.edn")
        deps-dir   (-> (io/file deps-file) (.getCanonicalFile) (.getParentFile))
        target     (or (get args "--target")
                     (as-> (io/file ".") %
                       (.getCanonicalFile %)
                       (.getName %)
                       (str "target/" % ".jar")))
        aliases    (-> (or (get args "--aliases") "")
                     (str/split  #":")
                     (->> (remove str/blank?)
                       (map keyword)
                       (into #{})))
        main-attrs {:main-class    (get args "--main-class")
                    :multi-release (= (get args "--multi-release") "true")}
        level      (keyword (or (get args "--level") "debug"))]
    (binding [api/level level]
      (deps.dir/with-dir deps-dir 
        (api/package
          (edn/read-string (slurp deps-file))
          target
          (assoc main-attrs :aliases aliases))))
    (shutdown-agents)))
