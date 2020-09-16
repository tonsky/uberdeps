(ns uberdeps.api
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.tools.deps.alpha :as deps]
   [clojure.tools.deps.alpha.util.dir :as deps.dir]
   [clojure.xml :as xml]
   [clojure.zip :as zip])
  (:import
   [java.io File InputStream FileInputStream FileOutputStream BufferedInputStream BufferedOutputStream ByteArrayInputStream ByteArrayOutputStream]
   [java.nio.file.attribute FileTime]
   [java.time Instant]
   [java.util.jar JarEntry JarInputStream JarOutputStream]
   [java.util.regex Pattern]))


; (set! *warn-on-reflection* true)
; (set! *print-namespace-maps* false)


(def ^:private ^:dynamic *seen-files)
(def ^:private ^:dynamic *seen-libs)
(def ^:private ^:dynamic *mkdirs)
(def ^:private ^:dynamic *mergeables)
(def ^:private ^:dynamic mergers)
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
   #".*(\.pom|~)"
   #"module-info\.class"
   #"(?i)META-INF/.*\.(MF|SF|RSA|DSA)"
   #"(?i)META-INF/(INDEX\.LIST|DEPENDENCIES|NOTICE|LICENSE)(\.txt)?"])


(defn- escape-html [s]
  (-> s
    (str/replace "&" "&amp;")
    (str/replace "\"" "&quot;")
    (str/replace "'" "&apos;")
    (str/replace "<" "&lt;")
    (str/replace ">" "&gt;")))


(defn- tree-edit
  "Walk the componment xml dom looking for description tag"
  [zipper]
  (let [editor (fn [node]
                 (if-some [content (get (:content node) 0)]
                   (update node :content update 0 escape-html)
                   node))]
    (loop [loc zipper]
      (if (zip/end? loc)
        (zip/root loc)
        (if (= :description (:tag (zip/node loc)))
          (let [new-loc (zip/edit loc editor)]
            (recur (zip/next new-loc)))
          (recur (zip/next loc)))))))


;; Adapted from https://github.com/technomancy/leiningen/blob/ebebd0eb9b17933d78142dcffcab180715d56d80/src/leiningen/uberjar.clj#L24-L55
(def components-merger
  {:collect
   (fn [acc ^String content]
     (let [zipper (with-open [is (ByteArrayInputStream. (.getBytes content "UTF-8"))]
                    (->> is xml/parse zip/xml-zip))
           comp   (->> zipper
                    tree-edit
                    zip/xml-zip 
                    zip/children
                    (filter #(= (:tag %) :components))
                    first
                    :content)]
       (into (or acc []) comp)))
   :combine
   (fn [acc]
     (with-out-str
       (xml/emit {:tag :component-set
                  :content
                  [{:tag :components
                    :content acc}]})
       (.flush *out*)))})


(def services-merger
  {:collect
   (fn [acc content] (if (some? acc) (str acc "\n" content) content))
   :combine
   (fn [acc] acc)})


(def clojure-maps-merger
  {:collect (fn [acc content] (merge acc (edn/read-string content)))
   :combine (fn [acc] (pr-str acc))})


(def default-mergers
  {"META-INF/plexus/components.xml" components-merger
   #"META-INF/services/.*"          services-merger   
   #"data_readers.clj[cs]?"         clojure-maps-merger})


(defn- deps-map [deps {:keys [aliases]}]
  (let [deps-map (->> deps
                   (@#'deps/canonicalize-all-syms)
                   (merge-with merge
                     {:mvn/repos
                      {"central" {:url "https://repo1.maven.org/maven2/"}
                       "clojars" {:url "https://repo.clojars.org/"}}}))]
    (-> deps-map
      (dissoc :aliases)
      (assoc :args-map (deps/combine-aliases deps-map aliases)))))


(defn- merger [path]
  (reduce-kv
    (fn [_ pattern merger]
      (when (condp instance? pattern
              String  (= pattern path)
              Pattern (re-matches pattern path)
              false)
        (when (some? merger)
          (reduced merger))))
    nil
    mergers))


(defn- slurp-stream [^InputStream in]
  (let [baos (ByteArrayOutputStream.)]
    (io/copy in baos)
    (.toString baos "UTF-8")))


(defn- mkdirs
  ([path out]
   (mkdirs path (FileTime/from (Instant/now)) out))
  ([path last-modified ^JarOutputStream out]
   (let [segments (str/split path #"/")]
     (doseq [i (range 1 (count segments))
             :let [dir (str (str/join "/" (subvec segments 0 i)) "/")]]
       (when-not (@*mkdirs dir)
         (let [entry (JarEntry. dir)]
           (.setLastModifiedTime entry last-modified)
           (.putNextEntry out entry)
           (.closeEntry out)
           (swap! *mkdirs conj dir)))))))


(defn- copy-stream [^InputStream in ^String path last-modified ^JarOutputStream out]
  (if-some [merger (merger path)]
    (swap! *mergeables update path (:collect merger) (slurp-stream in))
    (if-some [context' (get @*seen-files path)]
      (when (#{:debug :info :warn} level)
        (println (str "! Duplicate entry \"" path "\" from \"" context "\" already seen in \"" context' "\"")))
      (let [_     (mkdirs path last-modified out)
            entry (JarEntry. (str/replace path (File/separator) "/"))]
        (.setLastModifiedTime entry last-modified)
        (.putNextEntry out entry)
        (io/copy in out)
        (.closeEntry out)
        (swap! *seen-files assoc path context)))))


(defn- copy-stream-filtered [^InputStream in ^String path last-modified ^JarOutputStream out]
  (when-not (some #(re-matches % path) exclusions)
    (copy-stream in path last-modified out)))


(defn- normalize-path [path]
  (str/replace path "\\" "/"))

(defn copy-directory [^File dir out]
  (let [dir-path  (.getPath dir)
        dir-path' (if (str/ends-with? dir-path "/") dir-path (str dir-path "/"))]
    (doseq [^File file (file-seq (io/file dir-path'))
            :when (.isFile file)]
      (with-open [in (io/input-stream file)]
        (let [rel-path (-> (.getPath file) (subs (count dir-path')) normalize-path)
              modified (FileTime/fromMillis (.lastModified file))]
          (copy-stream-filtered in rel-path modified out))))))


(defn copy-jar [^File file out]
  (with-open [in (JarInputStream. (BufferedInputStream. (FileInputStream. file)))]
    (loop [entry (.getNextEntry in)]
      (when (some? entry)
        (when-not (.isDirectory entry)
          (copy-stream-filtered in (normalize-path (.getName entry)) (.getLastModifiedTime entry) out))
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


(defn package-manifest [opts out]
  (let [manifest (str "Manifest-Version: 1.0\n"
                   "Created-By: " (System/getProperty "java.version") " (" (System/getProperty "java.vm.vendor") ")\n"
                   (when-some [main-class (:main-class opts)]
                     (format "Main-Class: %s\n" main-class))
                   (when (:multi-release? opts)
                     (format "Multi-Release: true\n")))
        in       (io/input-stream (.getBytes manifest "UTF-8"))]
    (copy-stream in "META-INF/MANIFEST.MF" (FileTime/from (Instant/now)) out)))


(defn package-paths [deps-map out]
  (let [paths (concat
                (:paths deps-map)
                (:extra-paths (:args-map deps-map)))]
    (doseq [path (sort paths)]
      (binding [context (str path "/**")]
        (when (#{:debug} level)
          (println (str "+ " context)))
        (package* path out)))))


(defn- lib-coord [coord]
  (select-keys coord [:mvn/version :local/root :sha]))


(defn package-lib [lib coord lib-map out]
  (if (contains? @*seen-libs lib)
    (when (#{:debug :info :warn} level)
      (println (str "- " indent "skipping duplicate lib") lib (lib-coord coord)))
    (binding [context (str lib " " (lib-coord coord))]
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


(defn package-libs [deps-map out]
  (let [lib-map (->>
                  (deps/resolve-deps deps-map (:args-map deps-map))
                  (remove (fn [[_ deps-map]] (contains? deps-map :extension))) ;; remove non-jar deps (https://github.com/tonsky/uberdeps/issues/14 https://github.com/tonsky/uberdeps/pull/15)
                  (into (sorted-map)))]
    (doseq [[lib coord] lib-map
            :when (nil? (:dependents coord))] ; roots
      (package-lib lib coord lib-map out))))


(defn package-mergeables [^JarOutputStream out]
  (doseq [[^String path acc] @*mergeables
          :let [merger (merger path)]]
    (mkdirs path out)
    (let [entry   (JarEntry. path)
          content ((:combine merger) acc)]
      (.putNextEntry out entry)
      (io/copy content out)
      (.closeEntry out))))


(defn package
  ([deps target] (package deps target {}))
  ([deps ^String target opts]
   (let [deps-map (deps-map deps opts)
         t0       (System/currentTimeMillis)]
     (when (#{:debug :info} level)
       (println (str "[uberdeps] Packaging " target "...")))
     (binding [*seen-files (atom {})
               *seen-libs  (atom #{})
               *mkdirs     (atom #{})
               *mergeables (atom {})
               mergers     (merge default-mergers (:mergers opts))]
       (when-let [p (.getParentFile (io/file target))]
         (.mkdirs p))
       (with-open [out (JarOutputStream. (BufferedOutputStream. (FileOutputStream. target)))]
         (package-manifest opts out)
         (package-paths deps-map out)
         (package-libs deps-map out)
         (package-mergeables out)))
     (when (#{:debug :info} level)
       (println (str "[uberdeps] Packaged " target " in " (- (System/currentTimeMillis) t0) " ms"))))))
