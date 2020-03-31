(ns uberdeps.uberjar
  (:require
   [clojure.edn :as edn]
   [uberdeps.api :as api]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.tools.deps.alpha.util.dir :as deps.dir]))


(defn get-option-value [args option]
  (->> args (drop-while #(not= % option)) second))


(defn get-option [args option]
  (some #(= option %) args))


(defn -main [& args]
  (let [deps-file (or (get-option-value args "--deps-file") "deps.edn")
        deps-dir  (-> (io/file deps-file) (.getCanonicalFile) (.getParentFile))
        target    (or (get-option-value args "--target")
                    (as-> (io/file ".") %
                      (.getCanonicalFile %)
                      (.getName %)
                      (str "target/" % ".jar")))
        aliases   (-> (or (get-option-value args "--aliases") "")
                    (str/split  #":")
                    (->> (remove str/blank?)
                      (map keyword)
                      (into #{})))
        opts      {:main-class     (get-option-value args "--main-class")
                   :multi-release? (get-option args "--multi-release")
                   :aliases        aliases}
        level     (or (some-> (get-option-value args "--level") keyword)
                    :debug)]
    (binding [api/level level]
      (deps.dir/with-dir deps-dir 
        (api/package
          (edn/read-string (slurp deps-file))
          target
          opts)))
    (shutdown-agents)))
