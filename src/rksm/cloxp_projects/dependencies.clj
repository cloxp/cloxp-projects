(ns ^{:doc
      "dependency computation of projects, based on the 'project info'
      data structures"}
  rksm.cloxp-projects.dependencies
  (:require [clojure.set :refer [difference]]))

(defn- matches
  "string / regexp / equality"
  [matcher thing]
  (cond
    (string? matcher) (re-find (re-pattern (str "(?i)" matcher)) (str thing))
    (instance? java.util.regex.Pattern matcher) (re-find matcher (str thing))
    :default (= matcher thing)))

(defn- depends-on?
  "does proj depend on other-proj?"
  [{:keys [dependencies] :as proj} {:keys [group name] :as other-proj}]
  (let [pid (if (= group name) (symbol name) (symbol group name))]
    (some (fn [[id version]] (= id pid)) dependencies)))

(defn sort-by-deps
  "order projects so that for projects a and b, a is sorted before b iff (not
  (depends-on? a b))"
  [projects]
  (loop [projects (set projects) sorted []]
    (if (empty? projects)
      sorted
      (let [dep-less (filter (fn [a] (not-any? (fn [b] (depends-on? a b)) projects)) projects)]
        (when (and (empty? dep-less) (not-empty projects))
          (throw (Exception. (str "circular dependency in " projects))))
        (recur (difference projects dep-less) (concat sorted dep-less))))))

(defn projects-with-dep-matching
  [projects dep-spec]
  (->> projects
    (filter #(some (partial matches dep-spec) (->> % :dependencies (map first))))
    set))

(defn projects-matching
  "matches symbol group/name of project"
  [projects spec]
  (filter #(matches spec (symbol (:group %) (:name %))) projects))

(defn projects-directly-dependent-on
  [projects {:keys [group name] :as project}]
  (projects-with-dep-matching projects (symbol group name)))

(defn projects-dependent-on
  "recursivr version of projects-directly-dependent-on"
  [projects project]
  (loop [unresolved #{project}
         deps #{}]
    (let [direct-deps (->> unresolved
                        (mapcat #(projects-directly-dependent-on projects %))
                        set)]
      (if (empty? direct-deps) deps
        (recur
          (difference direct-deps deps unresolved)
          (set (concat deps direct-deps)))))))
