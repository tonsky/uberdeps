(ns uberdeps.test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :as test :refer [deftest is are testing use-fixtures]]
   [uberdeps.api :as api])
  (:import
   [java.io File FileInputStream BufferedInputStream]
   [java.util.zip ZipInputStream]))


(def jar-path "target/test.jar")


(use-fixtures :each (fn [f]
                      (io/delete-file jar-path true)
                      (f)))


(defn read-jar [path]
  (with-open [in (ZipInputStream. (BufferedInputStream. (FileInputStream. (io/file path))))]
    (loop [entry (.getNextEntry in)
           acc   []]
      (cond
        (nil? entry)
        acc
        
        (.isDirectory entry)
        (recur (.getNextEntry in) acc)

        :else
        (let [entry {:name          (.getName entry)
                     :last-modified (.getLastModifiedTime entry)
                     :content       (@#'api/slurp-stream in)}]
          (recur (.getNextEntry in) (conj acc entry)))))))


(defn find-file [jar path]
  (let [files (filter #(= path (:name %)) jar)]
    (is (= 1 (count files)))
    (first files)))


(defn find-content [jar path]
  (:content (find-file jar path)))


(deftest test-mergers
  (api/package
    '{:deps 
      {u/project-a {:local/root "test_projects/mergers/project_a"}
       u/project-b {:local/root "test_projects/mergers/project_b"}
       u/project-c {:local/root "test_projects/mergers/project_c.jar"}}}
    jar-path
    {:mergers {#"README\.md" {:collect
                              (fn [acc content]
                                (if (some? acc)
                                  (str acc "\n" (str/upper-case content))
                                  (str/upper-case content)))
                              :combine
                              (fn [acc] acc)}}})

  (let [jar (read-jar jar-path)]
    (testing "data_readers"
      (is (= ["data_readers.clj"] (filter #(= "data_readers.clj" %) (map :name jar))))
      (is (= '{project-a a
               project-b b
               project-c c
               common    C}
             (-> (find-content jar "data_readers.clj") (edn/read-string)))))

    (testing "services"
      (is (= "common_a\nproject_a\ncommon_b\nproject_b\ncommon_c\nproject_c"
            (find-content jar "META-INF/services/common")))
      (is (= "project_a"
            (find-content jar "META-INF/services/project_a")))
      (is (= "project_b"
            (find-content jar "META-INF/services/project_b")))
      (is (= "project_c"
            (find-content jar "META-INF/services/project_c"))))

    (testing "components"
      (is (=
"<?xml version='1.0' encoding='UTF-8'?>
<component-set>
<components>
<component>
<role>
org.apache.maven.wagon.Wagon
</role>
<role-hint>
gs
</role-hint>
<description>

                &apos;Wagon&apos; or &quot;Wagon&quot; or &lt;Wagon&gt;?
            
</description>
</component>
<component>
<role>
org.apache.maven.lifecycle.mapping.LifecycleMapping
</role>
<role-hint>
zip
</role-hint>
<description>
Lifecycle &amp; Mapping
</description>
</component>
</components>
</component-set>
"
            (find-content jar "META-INF/plexus/components.xml"))))

    (testing "custom"
      (is (= "PROJECT A README\nPROJECT B README\nPROJECT C README"
            (find-content jar "README.md"))))))


; see https://github.com/tonsky/uberdeps/issues/3
(deftest test-overrides
  (api/package
    '{:deps
      {cheshire {:mvn/version "5.8.1"}
       cheshire/cheshire {:mvn/version "5.8.0"}}}
    jar-path)

  (let [jar (read-jar jar-path)]
    (let [files (filter #(= "cheshire/core.clj" (:name %)) jar)]
      (is (= 1 (count files))))))


; see https://github.com/tonsky/uberdeps/issues/22
(deftest test-multi-release
  (api/package
    '{:deps
      {org.apache.logging.log4j/log4j-api {:mvn/version "2.13.1"}
       org.apache.logging.log4j/log4j-core {:mvn/version "2.13.1"}}}
    jar-path
    {:multi-release? true})

  (let [jar (read-jar jar-path)]
    (is (re-find #"Multi-Release: true" (find-content jar "META-INF/MANIFEST.MF")))))


; see https://github.com/tonsky/uberdeps/pull/39
(deftest test-defaults
  (api/package
    '{:deps
      {tongue/tongue {:mvn/version "0.2.10"} ;; clojars
       com.cognitect/transit-clj {:mvn/version "1.0.324"}}} ;; maven central
    jar-path)

  (let [jar (read-jar jar-path)]
    (is (clojure.set/subset?
          #{"uberdeps/uberjar.clj"   ;; :paths ["src"]
            "tongue/core.cljc"       ;; clojars
            "cognitect/transit.clj"  ;; maven central
            "clojure/core.clj"}      ;; clojure
          (into #{} (map :name) jar)))))


(defn -main [& args]
  (test/run-all-tests #"uberdeps\.test")
  #_(test/test-ns 'uberdeps.test))
