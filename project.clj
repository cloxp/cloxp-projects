(defproject org.rksm/cloxp-projects "0.1.0-SNAPSHOT"
  :description "Dealing with clojure projects and project configurations."
  :license "MIT"
  :url "http://github.com/cloxp/cloxp-projects"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/data.xml "0.0.8"]
                 [com.cemerick/pomegranate "0.3.0"]]
  :scm {:url "git@github.com:cloxp/cloxp-projects.git"}
  :pom-addition [:developers [:developer
                              [:name "Robert Krahn"]
                              [:url "http://robert.kra.hn"]
                              [:email "robert.krahn@gmail.com"]
                              [:timezone "-9"]]])