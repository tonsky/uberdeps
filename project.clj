(defproject uberdeps "0.0.0"
  :description "Uberjar builder for deps.edn"
  :license     {:name "MIT" :url "https://github.com/tonsky/uberdeps/blob/master/LICENSE"}
  :url         "https://github.com/tonsky/uberdeps"
  :dependencies
  [[org.clojure/clojure "1.11.1"]
   [org.clojure/tools.deps "0.18.1335"]]
  :deploy-repositories
  {"clojars"
   {:url "https://clojars.org/repo"
    :username "tonsky"
    :password :env/clojars_token
    :sign-releases false}})
