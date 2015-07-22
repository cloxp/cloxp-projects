(defproject org.rksm/cloxp-projects "0.1.10-SNAPSHOT"
  :description "Dealing with clojure projects and project configurations."
  :license {:name "MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :url "http://github.com/cloxp/cloxp-projects"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/data.json "0.2.6"]
                 [com.cemerick/pomegranate "0.3.0"]
                 [org.rksm/system-files "0.1.7-SNAPSHOT"]
                 [classlojure/classlojure "0.6.6"]
                 [leiningen/leiningen "2.5.1" :exclusions [org.clojure/tools.reader]]]
  :profiles {:dev {:dependencies [[org.rksm/test-helpers "0.1.0"]]}}
  :scm {:url "git@github.com:cloxp/cloxp-projects.git"}
  :pom-addition [:developers [:developer
                              [:name "Robert Krahn"]
                              [:url "http://robert.kra.hn"]
                              [:email "robert.krahn@gmail.com"]
                              [:timezone "-9"]]])