(ns uberdeps.uberjar
  (:require
   [clojure.edn :as edn]
   [uberdeps.api :as api]
   [clojure.string :as str]
   [clojure.java.io :as io]))


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
        level     (keyword (or (get args "--level") "debug"))]
    (binding [api/level level]
      (api/package
        (transform-deps (edn/read-string (slurp deps-file)))
        target
        {:aliases aliases}))
    (shutdown-agents)))
