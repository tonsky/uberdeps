(ns uberdeps.uberjar
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.deps.util.dir :as deps.dir]
   [uberdeps.api :as api]))

(defn get-options [args]
  (->> args
       (partition 2)
       (group-by first)
       (map (fn [[option v]]
              [option (map second v)]))
       (into {})))

(defn -main [& args]
  (let [parsed-args (get-options args)
        deps-file (first (parsed-args "--deps-file" ["deps.edn"]))
        deps-dir  (-> (io/file deps-file)
                      (.getAbsoluteFile)
                      (.getParentFile))
        target    (or (first (parsed-args "--target"))
                      (as-> (io/file ".") %
                            (.getCanonicalFile %)
                            (.getName %)
                            (str "target/" % ".jar")))
        aliases   (-> (first (parsed-args "--aliases" [""]))
                      (str/split  #":")
                      (->> (remove str/blank?)
                         (map keyword)
                         (into #{})))
        exclusions (map re-pattern (parsed-args "--exclude"))
        opts {:main-class     (first (parsed-args "--main-class"))
              :multi-release? (contains? (set args) "--multi-release")
              :exclusions     exclusions
              :aliases        aliases}
        level     (or (some-> (first (parsed-args "--level")) keyword)
                      :debug)]
    (binding [api/level level]
      (deps.dir/with-dir deps-dir
        (api/package
          (edn/read-string (slurp deps-file))
          target
          opts)))
    (shutdown-agents)))
