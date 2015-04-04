(ns rksm.cloxp-projects.search
  (:require [clojure.data.json :as json]
            [rksm.cloxp-projects.pom :as pom]
            [rksm.system-files :as sf]
            [rksm.system-files.fs-util :as fs-util]
            [rksm.system-files.jar-util :refer [namespaces-in-jar]]))

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
    (keep pom/pom-project-info-from-jar-file)
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
 (search-for-namespaces-in-local-repo #"json")
 
 (def jar (sf/file "/Users/robert/.m2/repository/org/rksm/cloxp-cljs/0.1.1-SNAPSHOT/cloxp-cljs-0.1.1-SNAPSHOT.jar"))
 (project-infos-from-jar-files [jar])
 )
