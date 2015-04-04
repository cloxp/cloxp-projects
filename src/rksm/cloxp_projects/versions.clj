(ns rksm.cloxp-projects.versions
  (:refer-clojure :exclude [replace])
  (:require [clojure.string :refer [replace replace-first]]
            [rksm.cloxp-projects.lein :as lein]))


(defn next-patch-version
  [version]
  (replace version #"([0-9]+\.[0-9]+\.)([0-9]+).*"
             (fn [[_ pre patch]] (str pre (inc (read-string patch))))))

(defn next-minor-version
  [version]
  (replace version #"([0-9]+\.)([0-9]+)(\.[0-9]+)(.*)"
             (fn [[_ pre minor patch rest]](str pre (inc (read-string minor)) ".0"))))

(defn next-major-version
  [version]
  (replace version #"([0-9]+)(.*)"
             (fn [[_ major rest]](str (inc (read-string major)) ".0.0"))))

(defn as-snapshot-version
  [version]
  (replace version #"([0-9]+\.[0-9]+\.[0-9]+).*" "$1-SNAPSHOT"))

(defn no-snapshot-version
  [version]
  (replace version #"([0-9]+\.[0-9]+\.[0-9]+).*" "$1"))

(defn next-snapshot-version
  [version]
  (-> version next-patch-version as-snapshot-version))

(defn next-release-version
  [version]
  (if (re-find #"-SNAPSHOT$" version)
    (no-snapshot-version version)
    (next-patch-version version)))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn update-project-version
  [version-update-fn project-map-string]
  (let [{:keys [version]} (lein/lein-project-conf-content project-map-string)
        next-version (version-update-fn version)]
    (replace-first project-map-string version next-version)))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn update-project-dep*
  [{:keys [group-id artifact-id version]} project-clj-string]
  (let [match (re-pattern (str "\\[\\s*" group-id "/" artifact-id "\\s+[^\\]]*\\]"))
        replacement (str "[" group-id "/" artifact-id " \"" version "\"]")]
    (replace project-clj-string match replacement)))

(defmacro update-project-dep
  [map-or-vec project-clj-string]
  (if (map? map-or-vec)
    `(update-project-dep* ~map-or-vec ~project-clj-string)
    (let [[n v] map-or-vec
          g-id (symbol (namespace n))
          a-id (symbol (name n))]
      `(update-project-dep* {:group-id '~g-id, :artifact-id '~a-id, :version ~v}
                                ~project-clj-string))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(defn update-project-version-in-file!
  [version-update-fn file]
  (lein/modify-project-clj! file version-update-fn))

(defmacro update-project-dep-in-file!
  [new-dep-vec file]
  `(lein/modify-project-clj!
    ~file (fn [c#] (update-project-dep ~new-dep-vec c#))))

(comment
 (macroexpand-1 '(update-project-dep-in-file! [foo/bar "123"] "xxx"))
 
 (next-snapshot-version "0.1.2-SNAPSHOT")
 (next-snapshot-version "0.1.2")
 (next-release-version "0.1.2-SNAPSHOT")
 (next-release-version "0.1.2")
 (next-patch-version "0.1.2-SNAPSHOT")
 (next-minor-version "0.1.2-SNAPSHOT")
 (next-major-version "0.1.2-SNAPSHOT"))
