(ns rksm.cloxp-projects.lein
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.set :as set]
            [rksm.system-files :as sf]
            [rksm.system-files.jar-util :refer [jar-url->reader jar?]]
            [leiningen.core.project :as project]
            [leiningen.core.classpath :as classpath]
            [leiningen.new :as lnew]))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; reading project maps

(defn- read-project-clj-from-jar
  [^java.io.File jar-file]
  (if-let [rdr (jar-url->reader
                 (str "jar:" (.toURI jar-file) "!/project.clj"))]
    (slurp rdr)))

(defn- search-for-project-clj-dir-upward
  [dir]
  (if dir
    (let [project-clj (io/file dir "project.clj")]
      (if (.exists project-clj)
        dir
        (search-for-project-clj-dir-upward
         (.getParentFile dir))))))

(defn- search-for-project-clj-upward
  [dir]
  (some-> dir
    search-for-project-clj-dir-upward
    (io/file "project.clj")))

(defmulti lein-project-conf-content
  (fn [x] (cond
            (string? x) :string
            (jar? x) :jar
            :default :dir)))

(defmethod lein-project-conf-content :string
  [project-clj-string]
  ; rk 2015-03-22: this is copied from leiningen.core.project/read-raw as the
  ; leiningen methods do not provide a non-file interface to reading project maps
  ; and jars aren't supported either
  (locking leiningen.core.project/read-raw
    (binding [*ns* (find-ns 'leiningen.core.project)]
      (try (eval (read-string project-clj-string))
        (catch Exception e
          (throw (Exception. "Error loading" e)))))
    (let [project (resolve 'leiningen.core.project/project)]
      (when-not project
        (throw (Exception. "No project map!")))
      ;; return it to original state
      (ns-unmap 'leiningen.core.project 'project)
      @project)))

(defmethod lein-project-conf-content :jar
  [^java.io.File jar-file]
  (binding [*file* (str jar-file)] ; for leiningen's defproject
    (lein-project-conf-content
       (read-project-clj-from-jar jar-file))))

(defmethod lein-project-conf-content :dir
  [dir]
  (if-let [pclj (some-> dir search-for-project-clj-upward)]
    (binding [*file* (str pclj)] ; for leiningen
      (-> pclj
        slurp
        lein-project-conf-content))))

(defn lein-project-conf-for-ns
  [ns]
  (lein-project-conf-content
   (sf/classpath-for-ns ns)))

(defn lein-project-conf-string-for-ns
  [ns]
  (let [cp (sf/classpath-for-ns ns)]
    (cond
      (nil? cp) nil
      (jar? cp) (read-project-clj-from-jar cp)
      (.isDirectory cp) (some-> (some-> cp
                                  search-for-project-clj-upward
                                  slurp)))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; dependencies

(defn- lein-dep-hierarchy
  [proj path]
  get-in
  (let [key (last path)
        path (drop-last path)]
    (->> (get-in proj path)
      (classpath/dependency-hierarchy key)
      keys)))

(defn- lein-project-plugins
  [proj]
  (let [profiles (-> proj :profiles keys)]
    (distinct
     (concat
      (lein-dep-hierarchy proj [:plugins])
      (mapcat #(lein-dep-hierarchy proj [:profiles % :plugins]) profiles)))))

(defn lein-project-deps
  [proj {:keys [include-plugins? include-dev? clean?]
         :or {include-plugins? false, include-dev? true, clean? true}
         :as options}]
  (let [profiles (or (some-> proj :profiles keys) [])
        profiles (if include-dev? profiles (remove #{:dev} profiles))]
    (cond-> (lein-dep-hierarchy proj [:dependencies])
      include-dev?     (concat (lein-dep-hierarchy proj [:dev-dependencies]))
      include-dev?     (concat (mapcat #(lein-dep-hierarchy proj [:profiles % :dependencies]) profiles))
      include-plugins? (concat (lein-project-plugins proj))
      clean?           (->> (filter (comp (partial not= 'org.clojure/clojure) first)))
      true             distinct)))

(defn lein-deps
  [project-clj-file & [options]]
  (let [proj (-> (io/file project-clj-file)
               .getParentFile
               lein-project-conf-content)]
    (lein-project-deps proj (or options {}))))


; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; modifying project.clj maps

(defn project-clj-with-dep
  [project-clj-map group-id artifact-id version]
  (let [dep [(if group-id
               (symbol (str group-id) (str artifact-id))
               (symbol (str artifact-id)))
             version]
        deps-at (inc (.indexOf project-clj-map :dependencies))]
    (if (zero? deps-at)
      (concat project-clj-map [:dependencies [dep]])
      (let [updated-deps (-> (nth project-clj-map deps-at)
                           (conj dep)
                           distinct vec)
            updated (concat (take deps-at project-clj-map) [updated-deps]  (drop (inc deps-at) project-clj-map))]
        updated))))

(defn add-dep-to-project-clj!
  [project-clj group-id artifact-id version]
  (assert (-> (str project-clj) (.endsWith "project.clj")))
  (let [project-clj (io/file project-clj)]
    (assert (.exists project-clj))
    (let [conf (-> project-clj slurp read-string)]
      (->> (project-clj-with-dep conf group-id artifact-id version)
        (#(with-out-str (pp/pprint %)))
        (spit project-clj)))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn modify-project-clj!
  [file update-fn]
  (let [file (sf/file file)
        file (if (.isDirectory file)
               (sf/file (str file "/project.clj"))
               file)]
    (binding [*file* (str file)]
      (->> file slurp update-fn (spit file)))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn- extract-source-dirs
  [dir pclj-map]
  (->> pclj-map
    (juxt :source-paths
          :test-paths
          (fn [pmap] (some->> pmap
                       :cljsbuild
                       :builds
                       (#(cond (coll? %) % (map? %) (vals %) :default nil))
                       (mapcat :source-paths))))
    flatten (remove nil?)
    (map #(io/file (str dir "/" %)))
    (map #(.getCanonicalPath %))
    distinct))

(defn project-info
  "Read and parse the project.clj file, attach additional information about the
  project. Currently this is namespace information, consisting of a {:ns :type
  :file} seq and dependency information.
  If custom fields from project.clj are required use option :additional-keys."
  [project-dir & [{:keys [only additional-keys] :as opts}]]
  (if-let [conf (lein-project-conf-content (sf/file project-dir))]
    (let [default-keys [:description :group :name :version :dependencies :namespaces :dir]
          source-dirs (extract-source-dirs project-dir conf)
          keys (into (or only default-keys) additional-keys)
          file-re #"\.(clj(s|x|c)?)$"
          nss (if (some #{:namespaces} keys)
                (into []
                      (comp (mapcat #(sf/namespaces-in-dir-with-file % file-re))
                            (map (fn [{:keys [name file]}]
                                   (let [file (str file)
                                         [_ type _] (re-find file-re file)]
                                     {:ns name
                                      :type (keyword type)
                                      :file file}))))
                      source-dirs))
          deps (if (some #{:dependencies} keys) (lein-project-deps conf opts))]
      (select-keys
       (merge conf {:dir project-dir
                    :namespaces nss
                    :dependencies deps})
       keys))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn source-dirs-of-lein
  [project-clj-file]
  (let [dir (.getParentFile (io/file project-clj-file))]
    (extract-source-dirs
     dir (project-info dir {:only [:source-paths :test-paths :cljsbuild]}))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn new-project
  [name dir]
  (let [file (io/file dir)
        name (or name (.getName file))]
    (if-not (.exists file)
      (.mkdirs file))
    (if (empty? (.listFiles file))
      (lnew/new nil name "--to-dir" dir))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(comment

 (lein-deps "/Users/robert/clojure/websocket-test/project.clj" {:include-plugins? true})

 (def proj (lein-project-conf-content (clojure.java.io/file ".")))
 (lein-project-deps proj {:include-plugins? true, :clean? true})
 (lein-project-deps proj {:include-plugins? false, :include-dev? false, :clean? true})


 (s/replace "0.1.0-SNAPSHOT" #"([0-9]+\.[0-9]+\.)([0-9]+)(.*)" identity)
 (let [content (slurp (sf/file "/Users/robert/clojure/cloxp-projects/project.clj"))
       proj-map (lein-project-conf-content content)]
;   (clojure.string/replace-first
;     content
;     (proj-map :version re-pattern) )
   (proj-map :version re-pattern))"0.1.0-SNAPSHOT"
 (:version (project-info "/Users/robert/clojure/cloxp-projects"))
 (modify-project-clj! #())
 (lein-project-conf-content (sf/file "/Users/robert/clojure/cloxp-projects"))
 (lein-project-conf-content (slurp (sf/file "/Users/<robert/clojure/cloxp-projects/project.clj")))
 (def proj-map (-> (rksm.cloxp-source-reader.core/read-objs (slurp (sf/file "/Users/robert/clojure/cloxp-projects/project.clj")))
    first
    :form))
 (meta (first (drop 2 proj-map)))
 (meta (clojure.tools.reader/read-string (slurp (sf/file "/Users/robert/clojure/cloxp-projects/project.clj"))))

 (lein-project-conf-for-ns *ns*)
 (lein-project-conf-for-ns 'rksm.cloxp-repl)
 (lein-project-conf-for-ns 'rksm.system-files)
 )