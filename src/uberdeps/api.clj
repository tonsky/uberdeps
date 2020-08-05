(ns uberdeps.api
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.tools.deps.alpha :as deps]
   [clojure.tools.deps.alpha.util.dir :as deps.dir])
  (:import
   [java.io File InputStream FileInputStream FileOutputStream BufferedInputStream BufferedOutputStream ByteArrayInputStream ByteArrayOutputStream]
   [java.nio.file.attribute FileTime]
   [java.time Instant]
   [java.util.jar JarEntry JarInputStream JarOutputStream]))


(set! *warn-on-reflection* true)


(def ^:private ^:dynamic *seen-files)
(def ^:private ^:dynamic *seen-libs)
(def ^:private ^:dynamic *services)
(def ^:private ^:dynamic context)
(def ^:private ^:dynamic indent "")


(def ^:dynamic level :debug) ; :debug :info :warn :error


; from https://github.com/seancorfield/depstar/blob/06eb9dea599840b38ec493669c89de5aa1ff2eba/src/hf/depstar/uberjar.clj
(def ^:dynamic exclusions
  "Filename patterns to exclude. These are checked with re-matches and
  should therefore be complete filename matches including any path."
  [#"project.clj"
   #"LICENSE"
   #"COPYRIGHT"
   #".*\.pom"
   #"module-info\.class"
   #"(?i)META-INF/.*\.(MF|SF|RSA|DSA)"
   #"(?i)META-INF/(INDEX\.LIST|DEPENDENCIES|NOTICE|LICENSE)(\.txt)?"])


(defn- package-services [^JarOutputStream out]
  (doseq [[path contents] @*services
          :let [entry (JarEntry. (str/replace path (File/separator) "/"))]]
    (.putNextEntry out entry)
    (io/copy (first contents) out)
    (doseq [content (next contents)]
      (.write out \newline)
      (io/copy content out))
    (.closeEntry out)))


(defn- copy-stream [^InputStream in ^String path last-modified ^JarOutputStream out]
  (if (re-matches #"META-INF/services/.*" path)
    (let [baos    (ByteArrayOutputStream.)
          _       (io/copy in baos)
          content (.toString baos "UTF-8")]
      (swap! *services update path (fnil conj []) content))
    (if-some [context' (get @*seen-files path)]
      (when (#{:debug :info :warn} level)
        (println (str "! Duplicate entry \"" path "\" from \"" context "\" already seen in \"" context' "\"")))
      (let [entry (doto
                    (JarEntry. (str/replace path (File/separator) "/"))
                    (.setLastModifiedTime last-modified))]
        (.putNextEntry out entry)
        (io/copy in out)
        (.closeEntry out)
        (swap! *seen-files assoc path context)))))


(defn- copy-stream-filtered [^InputStream in ^String path last-modified ^JarOutputStream out]
  (when-not (some #(re-matches % path) exclusions)
    (copy-stream in path last-modified out)))


(defn copy-directory [^File dir out]
  (let [dir-path  (.getPath dir)
        dir-path' (if (str/ends-with? dir-path "/") dir-path (str dir-path "/"))]
    (doseq [^File file (file-seq (io/file dir-path'))
            :when (.isFile file)]
      (with-open [in (io/input-stream file)]
        (let [rel-path (-> (.getPath file) (subs (count dir-path')))
              modified (FileTime/fromMillis (.lastModified file))]
          (copy-stream-filtered in rel-path modified out))))))


(defn copy-jar [^File file out]
  (with-open [in (JarInputStream. (BufferedInputStream. (FileInputStream. file)))]
    (loop [entry (.getNextEntry in)]
      (when (some? entry)
        (when-not (.isDirectory entry)
          (copy-stream-filtered in (.getName entry) (.getLastModifiedTime entry) out))
        (recur (.getNextEntry in))))))


(defn package* [path out]
  (let [file (io/file path)
        file (if (.isAbsolute file) file (io/file deps.dir/*the-dir* file))]
    (cond
      (not (.exists file))
      :skip

      (.isDirectory file)
      (copy-directory file out)

      (str/ends-with? path ".jar")
      (copy-jar file out)

      :else
      (throw (ex-info (str ":( Unknown entity at classpath: " path) {:path path})))))


(defn package-lib [lib coord lib-map out]
  (if (contains? @*seen-libs lib)
    (when (#{:debug :info :warn} level)
      (println (str "- " indent "duplicate" lib)))
    (binding [context (str lib " " (or (:mvn/version coord) (:sha coord)))]
      ; pack library itself
      (when (#{:debug} level)
        (println (str (if (= "" indent) "+ " ". ") indent context)))
      (doseq [path (:paths coord)]
        (package* path out))
      (swap! *seen-libs conj lib)
      ; pack dependants
      (binding [indent (str indent "  ")]
        (doseq [[lib' coord'] lib-map
                :when (some #(= lib %) (:dependents coord'))]
          (package-lib lib' coord' lib-map out))))))


(defn package-manifest
  [opts out]
  (let [manifest (str "Manifest-Version: 1.0\n"
                   "Created-By: " (System/getProperty "java.version") " (" (System/getProperty "java.vm.vendor") ")\n"
                   (when-some [main-class (:main-class opts)]
                     (format "Main-Class: %s\n" main-class))
                   (when (:multi-release? opts)
                     (format "Multi-Release: true\n")))
        in       (io/input-stream (.getBytes manifest))]
    (copy-stream in "META-INF/MANIFEST.MF" (FileTime/from (Instant/now)) out)))


(defn package-libs [deps-map out]
  (let [lib-map (->>
                  (deps/resolve-deps deps-map (:args-map deps-map))
                  (remove (fn [[_ deps-map]] (contains? deps-map :extension))) ;; remove non-jar deps (https://github.com/tonsky/uberdeps/issues/14 https://github.com/tonsky/uberdeps/pull/15)
                  (into (sorted-map)))]
    (doseq [[lib coord] lib-map
            :when (nil? (:dependents coord))] ; roots
      (package-lib lib coord lib-map out))))


(defn package-paths [deps-map out]
  (let [paths (concat
                (:paths deps-map)
                (:extra-paths (:args-map deps-map)))]
    (doseq [path (sort paths)]
      (binding [context (str path "/**")]
        (when (#{:debug} level)
          (println (str "+ " context)))
        (package* path out)))))


(defn- deps-map [deps {:keys [aliases]}]
  (let [deps-map (->> deps
                   (@#'clojure.tools.deps.alpha.reader/canonicalize-all-syms)
                   (merge-with merge
                     {:mvn/repos
                      {"central" {:url "https://repo1.maven.org/maven2/"}
                       "clojars" {:url "https://repo.clojars.org/"}}}))]
    (-> deps-map
      (dissoc :aliases)
      (assoc :args-map (deps/combine-aliases deps-map aliases)))))


(defn package
  ([deps target] (package deps target {}))
  ([deps ^String target opts]
   (let [deps-map (deps-map deps opts)
         t0       (System/currentTimeMillis)]
     (when (#{:debug :info} level)
       (println (str "[uberdeps] Packaging " target "...")))
     (binding [*seen-files (atom {})
               *seen-libs  (atom #{})
               *services   (atom {})]
       (when-let [p (.getParentFile (io/file target))]
         (.mkdirs p))
       (with-open [out (JarOutputStream. (BufferedOutputStream. (FileOutputStream. target)))]
         (package-manifest opts out)
         (package-paths deps-map out)
         (package-libs deps-map out)
         (package-services out)))
     (when (#{:debug :info} level)
       (println (str "[uberdeps] Packaged " target " in " (- (System/currentTimeMillis) t0) " ms"))))))
