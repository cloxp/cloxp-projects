(ns rksm.cloxp-projects.versions-test
  (:require [rksm.cloxp-projects.versions :refer :all]
            [clojure.test :refer :all]))

(deftest increase-versions
  (is (= "0.1.3-SNAPSHOT" (next-snapshot-version "0.1.2-SNAPSHOT")))
  (is (= "0.1.3-SNAPSHOT" (next-snapshot-version "0.1.2")))
  (is (= "0.1.2"          (next-release-version "0.1.2-SNAPSHOT")))
  (is (= "0.1.3"          (next-release-version "0.1.2")))
  (is (= "0.1.3"          (next-patch-version "0.1.2-SNAPSHOT")))
  (is (= "0.1.3"          (next-patch-version "0.1.2")))
  (is (= "0.2.0"          (next-minor-version "0.1.2-SNAPSHOT")))
  (is (= "0.2.0"          (next-minor-version "0.1.2")))
  (is (= "1.0.0"          (next-major-version "0.1.2-SNAPSHOT")))
  (is (= "1.0.0"          (next-major-version "0.1.2"))))

(deftest project-clj-update
  (let [sample-project-map-string
        "(defproject foo/bar \"0.1.0-SNAPSHOT\"\n  :description \"baz\"\n  :dependencies [[org.clojure/clojure \"1.6.0\"]])"]
    (is (= "(defproject foo/bar \"0.1.0\"\n  :description \"baz\"\n  :dependencies [[org.clojure/clojure \"1.6.0\"]])"
          (update-project-version next-release-version sample-project-map-string)))))

(deftest project-clj-dep-update
  (let [sample-project-map-string
        "(defproject foo/bar \"0.1.0-SNAPSHOT\"\n  :description \"baz\"\n  :dependencies [[org.clojure/clojure \"1.6.0\"]\n                 [foo.bar/baz \"1.3.0\"]]\n  :profile {:dev {:plugins [[foo.bar/baz \"1.3.0\"]]}})"
        expected
        "(defproject foo/bar \"0.1.0-SNAPSHOT\"\n  :description \"baz\"\n  :dependencies [[org.clojure/clojure \"1.6.0\"]\n                 [foo.bar/baz \"1.3.1\"]]\n  :profile {:dev {:plugins [[foo.bar/baz \"1.3.1\"]]}})"]
    (is (= expected
           (update-project-dep
            {:group-id 'foo.bar :artifact-id 'baz :version "1.3.1"}
            sample-project-map-string)))
    (is (= expected
           (update-project-dep
            [foo.bar/baz "1.3.1"]
            sample-project-map-string)))))

(comment
 (test-ns *ns*)
 )