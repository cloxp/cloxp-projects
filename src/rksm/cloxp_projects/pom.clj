(ns rksm.cloxp-projects.pom
  (:require [clojure.data.xml :as xml]
            [clojure.zip :as z]
            [clojure.string :as s]
            [clojure.java.io :as io]
            [rksm.system-files.jar-util :refer [jar-entries-matching
                                                jar+entry->reader]]))
(defn- xml-tags-matching
  [xml tag-sym]
  (->> (xml-seq xml)
    (filter (fn [{tag :tag}] (= tag-sym tag)))))

(defn- xml-dep->info
  [xml-dep]
  (->> xml-dep
    :content
    (mapcat (juxt :tag (comp first :content)))
    (apply hash-map)))

(defn- make-dep-vec
  [{:keys [groupId artifactId version] :as dep-from-pom}]
  [(symbol groupId artifactId) version])

(defn pom-project-info-from-xml
  [xml]
  (->> (:content xml)
    (filter #(some #{(:tag %)} [:groupId :artifactId :version :name :description]))
    (mapcat (juxt :tag (comp first :content)))
    (apply hash-map)
    (#(clojure.set/rename-keys % {:groupId :group-id :artifactId :artifact-id}))))

(def pom-project-info-from-jar-file
  (memoize
   (fn
     [^java.io.File jar-file]
     (let [jar (java.util.jar.JarFile. jar-file)]
       (if-let [xml (some-> jar
                      (jar-entries-matching #"/pom.xml$")
                      first
                      (->> (jar+entry->reader jar))
                      slurp clojure.data.xml/parse-str)]
         (assoc (pom-project-info-from-xml xml) :jar jar-file))))))


(defn- pom-deps-from-xml
  [xml]
  (let [deps (-> (xml-tags-matching xml :dependencies) first :content)]
    (map (comp make-dep-vec xml-dep->info) deps)))

(defn pom-deps
  "dependencies declared in the pom.xml file"
  [pom-file]
  (-> pom-file slurp xml/parse-str pom-deps-from-xml))

(defn pom-project-info
  "returns a map of :description :artifact-id :group-id version"
  [pom-file]
  (-> pom-file slurp xml/parse-str
    pom-project-info-from-xml))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; source dirs

(defn source-dirs-of-pom
  "Returns contents of sourceDirectory, testSourceDirectory, and sources. Note:
  These are most likely relative paths."
  [pom]
  (as-> pom x
    (slurp x)
    (xml/parse-str x)
    (partial xml-tags-matching x)
    (mapcat x [:sourceDirectory :testSourceDirectory :source])
    (mapcat :content x)
    (distinct x)))

(defn- pom-with-dep
  [pom-string group-id artifact-id version]
  (let [xml (xml/parse-str pom-string)
        deps (->> (iterate z/next (z/xml-zip xml))
               (take-while (complement z/end?))
               (filter #(= :dependencies (some-> % z/node :tag)))
               first)]
    (if-not deps
      pom-string
      (let [el (xml/sexp-as-element
                [:dependency
                 [:groupId (str group-id)]
                 [:artifactId (str artifact-id)]
                 [:version version]])
            updated-deps (z/edit deps #(update-in % [:content] cons [el]))
            ; actually it should be enough to do
            ; xml-string (-> updated-deps z/root xml/indent-str)
            ; but due to http://dev.clojure.org/jira/browse/DXML-15 this
            ; doesn't work :(
            xml-string (-> updated-deps z/node xml/indent-str)
            xml-string (s/replace xml-string #"^.*>\\?\s*|[\n\s]$" "")
            xml-string (s/replace xml-string #"(?m)^" "  ")
            start (+ (.indexOf pom-string "<dependencies>") (count "<dependencies>"))
            end (+ (.indexOf pom-string "</dependencies>") (count "</dependencies>"))]
        (str (.substring pom-string 0 start)
             "\n  "
             xml-string
             (.substring pom-string end))))))

(defn add-dep-to-pom!
  [pom-file group-id artifact-id version]
  (assert (-> (str pom-file ) (.endsWith "pom.xml")))
  (let [pom-file  (io/file pom-file)]
    (assert (.exists pom-file))
    (let [conf (-> pom-file  slurp)]
      (-> pom-file
        (spit (pom-with-dep conf group-id artifact-id version))))))
