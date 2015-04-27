(ns rksm.cloxp-projects.core
  (:require [cemerick.pomegranate :refer (add-dependencies)]
            [clojure.java.io :as io]
            [rksm.cloxp-projects.pom :as pom]
            [rksm.cloxp-projects.lein :as lein]
            [rksm.cloxp-projects.search :as search]
            [rksm.system-files :as sf]
            [clojure.data.json :as json]))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; search
(def search-for-ns search/search-for-ns)
(def search-for-namespaces-in-local-repo search/search-for-namespaces-in-local-repo)
(def search-for-namespaces-in-local-repo->json search/search-for-namespaces-in-local-repo->json)

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; source dirs

(defn source-dirs-in-project-conf
  [project-dir]
  (let [pclj (io/file (str project-dir "/project.clj"))
        pom (io/file (str project-dir "/pom.xml"))]
    (map (partial str project-dir "/")
         (cond
           (.exists pclj) (lein/source-dirs-of-lein pclj)
           (.exists pom) (pom/source-dirs-of-pom pom)
           :default []))))

(comment
 (source-dirs-in-project-conf "/Users/robert/clojure/cloxp-cljs")
 (source-dirs-in-project-conf (io/file "/Users/robert/clojure/cloxp-cljs"))
 )

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; dependencies

(defn install
  "dep like [group.id/artifact.id \"0.1.2\"]"
  [dep]
  (add-dependencies :coordinates [dep]
                    :repositories (merge cemerick.pomegranate.aether/maven-central
                                         {"clojars" "http://clojars.org/repo"})))

(defn- project-deps
  [& [dir options]]
  (let [dir (or dir (System/getProperty "user.dir"))
        make-file (fn [n] (io/file (str dir java.io.File/separator n)))
        project-clj (make-file "project.clj")
        pom (make-file "pom.xml")
        deps (cond
               (.exists project-clj) (lein/lein-deps project-clj options)
               (.exists pom) (pom/pom-deps pom)
               :default nil)
        cleaned-deps (filter (comp (partial not= 'org.clojure/clojure) first) deps)]
    cleaned-deps))

(defn load-deps-from-project-clj-or-pom-in!
  [dir]
  (when-let [deps (project-deps dir)]
    (doall (map install deps))
    deps))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; modifying poms and project.cljss

(defn find-project-configuration-file
  [& [dir]]
  (let [dir (or dir (System/getProperty "user.dir"))
        sep java.io.File/separatorChar
        project-clj (io/file (str dir sep "project.clj"))
        pom (io/file (str dir sep "pom.xml"))]
    (cond
      (.exists project-clj) (.getCanonicalPath project-clj)
      (.exists pom) (.getCanonicalPath pom)
      :default nil)))

(defn add-dep-to-project-conf!
  [project-dir group-id artifact-id version]
  (let [conf-file (find-project-configuration-file project-dir)]
    (cond
      (.endsWith conf-file "pom.xml") (pom/add-dep-to-pom! conf-file group-id artifact-id version)
      (.endsWith conf-file "project.clj") (lein/add-dep-to-project-clj! conf-file group-id artifact-id version)
      :default (throw (Exception. (str "invalid conf file: " conf-file))))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn project-info
  "Returns a map with :dependencies :namespaces :dir :version :name :group
  :description"
  [project-dir & [opts]]
  (if-let [conf-file (find-project-configuration-file project-dir)]
    (cond
      (.endsWith conf-file "pom.xml") (let [info (pom/pom-project-info conf-file)
                                            deps (project-deps project-dir opts)]
                                        (assoc info :dependencies deps))
      (.endsWith conf-file "project.clj") (lein/project-info project-dir opts)
      :default nil)))

(defn project-infos
  [project-dirs & [opts]]
  (map #(project-info % opts) project-dirs))

(defn- jsonify
  [data]
  json/write
  (json/write-str
   data
;   (update-in data [:dependencies] (partial map (fn [[dep v]] [(str dep) v])))
   :value-fn (fn [k v]
               (case k
                 :dependencies (map (fn [[dep version]] [(str dep) version]) v)
                 v))))

(defn project-info->json
  [project-dir & [opts]]
  (jsonify (project-info project-dir opts)))

(defn project-infos->json
  [project-dirs & [opts]]
  (jsonify (project-infos project-dirs opts)))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(comment
 (require '[clojure.string :as s])
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
 (lein-project-conf-content (slurp (sf/file "/Users/robert/clojure/cloxp-projects/project.clj")))
 (def proj-map (-> (rksm.cloxp-source-reader.core/read-objs (slurp (sf/file "/Users/robert/clojure/cloxp-projects/project.clj")))
    first
    :form))
 (meta (first (drop 2 proj-map)))
 (meta (clojure.tools.reader/read-string (slurp (sf/file "/Users/robert/clojure/cloxp-projects/project.clj"))))
 
 (lein/lein-project-conf-for-ns *ns*)
 (sf/classpath-for-ns *ns*)
 (lein/lein-project-conf-for-ns 'rksm.cloxp-repl)
 (lein/lein-project-conf-for-ns 'rksm.system-files)
 )
