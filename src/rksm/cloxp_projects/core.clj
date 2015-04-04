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

(defn lein-deps
  [project-clj-file]
  (let [proj (read-string (slurp project-clj-file))
        a 2
        deps (some->> proj
               (drop-while (partial not= :dependencies))
               second)
        dev-deps (some-> (drop-while (partial not= :dev-dependencies) proj)
                   second)
        dev-deps-2 (some-> (drop-while (partial not= :profiles) proj)
                     second :dev :dependencies)]
    (->> (concat deps dev-deps dev-deps-2)
      (into [])
      (filter boolean))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; dependencies

(defn install
  "dep like [group.id/artifact.id \"0.1.2\"]"
  [dep]
  (add-dependencies :coordinates [dep]
                    :repositories (merge cemerick.pomegranate.aether/maven-central
                                         {"clojars" "http://clojars.org/repo"})))

(defn project-deps
  [& [dir]]
  (let [dir (or dir (System/getProperty "user.dir"))
        make-file (fn [n] (io/file (str dir java.io.File/separator n)))
        project-clj (make-file "project.clj")
        pom (make-file "pom.xml")
        deps (cond
               (.exists project-clj) (lein-deps project-clj)
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

(defmulti ^{:private true} lein-project-conf-content
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
  (if-let [rdr (jar-url->reader
                 (str "jar:" (.toURI jar-file) "!/project.clj"))]
    (lein-project-conf-content (slurp rdr))))

(defmethod lein-project-conf-content :dir
  [dir]
  (if-not dir
    nil
    (let [project-clj (clojure.java.io/file (str dir "/project.clj"))]
      (if (.exists project-clj)
        (lein-project-conf-content (slurp project-clj))
        (lein-project-conf-content (.getParentFile dir))))))

(defn lein-project-conf-for-ns
  [ns]
  (lein-project-conf-content
   (sf/classpath-for-ns ns)))


(comment
 (lein-project-conf-for-ns *ns*)
 (lein-project-conf-for-ns 'rksm.cloxp-repl)
 (lein-project-conf-for-ns 'rksm.system-files)
 )
