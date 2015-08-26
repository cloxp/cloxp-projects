(ns rksm.cloxp-projects.dependencies-test
  (:require [rksm.cloxp-projects.dependencies :refer :all]
            [clojure.test :refer :all]))

(def p1 {:description "this is p1",
         :group "p1",
         :name "p1",
         :version "0.1.0",
         :dependencies '(),
         :dir "/foo/bar/p1"})

(def p2 {:description "this is p2",
         :group "bar",
         :name "p2",
         :version "0.1.0-SNAPSHOT",
         :dependencies '(),
         :dir "/foo/bar/p2"})

(def p3 {:description "this is p3",
         :group "foo",
         :name "p3",
         :version "0.1.0",
         :dependencies [['p1 "0.1.0"] ['bar/p2 "0.1.0"]],
         :dir "/foo/bar/p3"})

(def p4 {:description "this is p4",
         :group "bar",
         :name "p4",
         :version "0.1.0",
         :dependencies [['foo/p3 "0.1.0"]],
         :dir "/foo/bar/p4"})

(defn projects
  []
  (shuffle [p1 p2 p3 p4]))

(deftest sort-by-deps-test
  (let [sorted (sort-by-deps (projects))]
    (is (or (= [p1 p2 p3 p4] sorted)
            (= [p2 p1 p3 p4] sorted)))))

(deftest sort-by-deps-test-2
  (let [sorted (sort-by-deps [p1 p2 p4 p3])]
    (is (or (= [p1 p2 p3 p4] sorted)
            (= [p2 p1 p3 p4] sorted)))))

(deftest projects-with-dep-matching-test
  (is (= #{p3} (projects-with-dep-matching (projects) "p2")))
  (is (= #{p4} (projects-with-dep-matching (projects) "foo/")))
  (is (= #{p3} (projects-with-dep-matching (projects) 'bar/p2)))
  (is (= #{p3} (projects-with-dep-matching (projects) 'p1))))

(deftest projects-dependent-on-test
  (is (= #{p3 p4} (projects-dependent-on (projects) p2)))
  (is (= #{} (projects-dependent-on (projects) p4))))

(comment
 (binding [*test-out* *out*] (test-ns *ns*))
 )