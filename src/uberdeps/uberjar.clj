(ns uberdeps.uberjar
  (:require
    [clojure.edn :as edn]
    [uberdeps.api :as api]
    [clojure.string :as str]
    [clojure.java.io :as io]))


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
        main-class (get args "--main-class")
        level     (keyword (or (get args "--level") "debug"))]
    (binding [api/level level]
      (api/package
        (edn/read-string (slurp deps-file))
        target
        {:aliases aliases :main-class main-class}))
    (shutdown-agents)))
