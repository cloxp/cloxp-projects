(ns rksm.cloxp-projects.lein
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [rksm.system-files :as sf]
            [rksm.system-files.jar-util :refer [jar-url->reader jar?]]
            [leiningen.core.project :as project]
            [leiningen.core.classpath :as classpath]
            [leiningen.new :as lnew]))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; reading project maps


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
    (if-let [rdr (jar-url->reader
                   (str "jar:" (.toURI jar-file) "!/project.clj"))]
      (lein-project-conf-content (slurp rdr)))))

(defmethod lein-project-conf-content :dir
  [dir]
  (if-not dir
    nil
    (binding [*file* (str dir "/project.clj")] ; for leiningen
      (let [project-clj (io/file *file*)]
        (lein-project-conf-content
         (if (.exists project-clj)
           (slurp project-clj)
           (.getParentFile dir)))))))

(defn lein-project-conf-for-ns
  [ns]
  (lein-project-conf-content
   (sf/classpath-for-ns ns)))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn source-dirs-of-lein
  [project-clj-file]
  (let [proj (read-string (slurp project-clj-file))
        source (some->> proj
                 (drop-while (partial not= :source-paths))
                 second)
        test-source (some-> (drop-while (partial not= :test-paths) proj)
                      second)]
    (into [] (apply merge source test-source))))

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

(defn project-info
  [project-dir & [opts]]
  project-dir
  (if-let [conf (lein-project-conf-content (sf/file project-dir))]
    (let [file-re #"\.(clj(s|x)?)$"
          nss (->> (sf/discover-ns-in-project-dir project-dir file-re)
                (map (fn [ns] (let [file (str (sf/file-for-ns ns nil file-re))
                                    [_ type _] (re-find file-re file)]
                                {:ns ns
                                 :type (keyword type)
                                 :file file}))))
          deps (lein-project-deps conf opts)]
      (merge
       (select-keys conf [:description :group :name :version])
       {:dir project-dir
        :namespaces nss
        :dependencies deps}))))

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