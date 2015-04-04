(ns rksm.cloxp-projects.core
  (:require [clojure.data.json :as json]
            [clojure.string :as s]
            [cemerick.pomegranate :refer (add-dependencies)]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [rksm.cloxp-projects.pom :as pom]
            [rksm.system-files :as sf]
            [rksm.system-files.fs-util :as fs-util]
            [rksm.system-files.jar-util :refer [namespaces-in-jar
                                                jar-url->reader
                                                jar?]]
            [leiningen.core.project :as project]
            [leiningen.core.classpath :as classpath]))


; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; DEPRECATED pom
(def ^:private pom-deps pom/pom-deps)
(def pom-project-info-from-xml pom/pom-project-info-from-xml)
(def pom-project-info-from-jar-file pom/pom-project-info-from-jar-file)
(def pom-project-info pom/pom-project-info)
(def ^:private source-dirs-of-pom pom/source-dirs-of-pom)
(def ^:private add-dep-to-pom! pom/add-dep-to-pom!)

(defn- merge-project-infos
  [infos]
  (let [merged (apply merge (map #(dissoc % :version :jar) infos))
        versions (apply sorted-map
                   (mapcat (fn [{:keys [version jar]}]
                             [version {:jar jar
                                       :namespaces (namespaces-in-jar jar #"clj(x|s)?")}])
                           infos))]
    (assoc merged :versions versions)))

(defn project-infos-from-jar-files
  [jar-files]
  (->> jar-files
    (keep pom-project-info-from-jar-file)
    (group-by (juxt :artifact-id :group-id))
    vals (map merge-project-infos)))

(defn search-for-ns
  [project-infos ns-match & [{:keys [newest] :as opts}]]
  (keep
   (fn [{:keys [versions] :as info}]
     (let [found-versions (keep
                           (fn [[version {nss :namespaces}]]
                             (if (some #(re-find ns-match (str %)) nss) version))
                           versions)
           found-versions (if newest (take-last 1 found-versions) found-versions)]
       (if (empty? found-versions)
         nil (assoc info :versions (select-keys versions found-versions)))))
   project-infos))

(defn search-for-namespaces-in-local-repo
  "Takes a regexp and finds project infos {:description :artifact-id :group-id
  :versions {version-string {:jar :namespaces}} whose namespaces match."
  [ns-match & [opts]]
  (let [repo-dir (clojure.java.io/file
                  (-> (System/getenv) (get "HOME")) ".m2/repository")
        infos (->> (fs-util/walk-dirs repo-dir #"\.jar$")
                project-infos-from-jar-files)]
    (search-for-ns infos ns-match opts)))

(defn search-for-namespaces-in-local-repo->json
  [ns-match & [options]]
  (json/write-str
   (search-for-namespaces-in-local-repo ns-match options)
   :value-fn (fn [k v]
               (if (= :jar k) (str v) v))))

(comment
 (search-for-namespaces-in-local-repo #"json"))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; source dirs

(defn- source-dirs-of-lein
  [project-clj-file]
  (let [proj (read-string (slurp project-clj-file))
        source (some->> proj
                 (drop-while (partial not= :source-paths))
                 second)
        test-source (some-> (drop-while (partial not= :test-paths) proj)
                      second)]
    (into [] (apply merge source test-source))))

(defn source-dirs-in-project-conf
  [project-dir]
  (let [pclj (io/file (str project-dir "/project.clj"))
        pom (io/file (str project-dir "/pom.xml"))]
    (map (partial str project-dir "/")
         (cond
           (.exists pclj) (source-dirs-of-lein pclj)
           (.exists pom) (source-dirs-of-pom pom)
           :default []))))

(comment
 (source-dirs-of-pom "/Users/robert/clojure/cloxp-cljs/pom.xml")
 (source-dirs-of-pom "/Users/robert/clojure/system-navigator/pom.xml")
 (source-dirs-of-lein "/Users/robert/clojure/cloxp-cljs/project.clj")
 (source-dirs-in-project-conf "/Users/robert/clojure/cloxp-cljs")
 (source-dirs-in-project-conf (io/file "/Users/robert/clojure/cloxp-cljs"))
 )

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; lein

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
        (if (.exists project-clj)
          (lein-project-conf-content (slurp project-clj))
          (lein-project-conf-content (.getParentFile dir)))))))

(defn lein-project-conf-for-ns
  [ns]
  (lein-project-conf-content
   (sf/classpath-for-ns ns)))

(defn- lein-dep-hierarchy
  [proj path]
  get-in
  (let [key (last path)
        path (drop-last path)]
    (->> (get-in proj path)
      (classpath/dependency-hierarchy key)
      keys)))

(defn lein-project-plugins
  [proj]
  (let [profiles (-> proj :profiles keys)]
    (distinct
     (concat
      (lein-dep-hierarchy proj [:plugins])
      (mapcat #(lein-dep-hierarchy proj [:profiles % :plugins]) profiles)))))

(defn lein-project-deps
  [proj {:keys [include-plugins? include-dev?] :or [include-plugins? false, include-dev? true] :as options}]
  (let [profiles (or (some-> proj :profiles keys) [])
        profiles (if include-dev? profiles (remove #{:dev} profiles))]
    (distinct
     (concat
      (lein-dep-hierarchy proj [:dependencies])
      (if include-dev? (lein-dep-hierarchy proj [:dev-dependencies]) [])
      (mapcat #(lein-dep-hierarchy proj [:profiles % :dependencies]) profiles)
      (if include-plugins? (lein-project-plugins proj) [])))))

(comment
 (lein-deps "/Users/robert/clojure/websocket-test/project.clj" {:include-plugins? true})
 (def proj (lein-project-conf-content (clojure.java.io/file ".")))
 (lein-project-deps proj {:include-plugins? true})
 (lein-project-deps proj {:include-plugins? false :include-dev? false}))

(defn lein-deps
  [project-clj-file & [options]]
  (let [proj (-> (io/file project-clj-file)
               .getParentFile
               lein-project-conf-content)]
    (lein-project-deps proj (or options {}))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; dependencies

(defn install
  "dep like [group.id/artifact.id \"0.1.2\"]"
  [dep]
  (add-dependencies :coordinates [dep]
                    :repositories (merge cemerick.pomegranate.aether/maven-central
                                         {"clojars" "http://clojars.org/repo"})))

(defn project-deps
  [& [dir options]]
  (let [dir (or dir (System/getProperty "user.dir"))
        make-file (fn [n] (io/file (str dir java.io.File/separator n)))
        project-clj (make-file "project.clj")
        pom (make-file "pom.xml")
        deps (cond
               (.exists project-clj) (lein-deps project-clj options)
               (.exists pom) (pom-deps pom)
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

(defn- project-clj-with-dep
  [project-clj-map group-id artifact-id version]
  (let [dep [(if group-id (symbol (str group-id) (str artifact-id)) (symbol (str artifact-id))) version]
        deps-at (inc (.indexOf project-clj-map :dependencies))]
    (if (zero? deps-at)
      (concat project-clj-map [:dependencies [dep]])
      (let [updated-deps (-> (nth project-clj-map deps-at)
                           (conj dep)
                           distinct vec)
            updated (concat (take deps-at project-clj-map) [updated-deps]  (drop (inc deps-at) project-clj-map))]
        updated))))

(defn- add-dep-to-project-clj!
  [project-clj group-id artifact-id version]
  (assert (-> (str project-clj) (.endsWith "project.clj")))
  (let [project-clj (io/file project-clj)]
    (assert (.exists project-clj))
    (let [conf (-> project-clj slurp read-string)]
      (->> (project-clj-with-dep conf group-id artifact-id version)
        (#(with-out-str (pp/pprint %)))
        (spit project-clj)))))

(defn add-dep-to-project-conf!
  [project-dir group-id artifact-id version]
  (let [conf-file (find-project-configuration-file project-dir)]
    (cond
      (.endsWith conf-file "pom.xml") (add-dep-to-pom! conf-file group-id artifact-id version)
      (.endsWith conf-file "project.clj") (add-dep-to-project-clj! conf-file group-id artifact-id version)
      :default (throw (Exception. (str "invalid conf file: " conf-file))))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn project-info
  [project-dir]
  (if-let [conf (lein-project-conf-content (sf/file project-dir))]
    (let [nss (->> (sf/discover-ns-in-project-dir project-dir #"\.clj(s|x)?$")
                (map (fn [ns] (sf/file-for-ns ns))))]
      (merge
       (select-keys conf [:description :group :name :version])
       {:dir project-dir
        :namespaces nss
        :dependencies (project-deps project-dir)}))))

(defn modify-project-clj!
  [file f]
  (let [file (sf/file file)]
    (spit file (f (slurp file)))))

(comment
 
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
 (def jar (sf/file "/Users/robert/.m2/repository/org/rksm/cloxp-cljs/0.1.1-SNAPSHOT/cloxp-cljs-0.1.1-SNAPSHOT.jar"))
 (project-infos-from-jar-files [jar])
 
 (lein-project-conf-for-ns *ns*)
 (lein-project-conf-for-ns 'rksm.cloxp-repl)
 (lein-project-conf-for-ns 'rksm.system-files)
 )
