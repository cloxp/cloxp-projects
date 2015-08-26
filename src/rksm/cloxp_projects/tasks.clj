(ns rksm.cloxp-projects.tasks
  (:require [clojure.java.shell :as shell]
            [leiningen.core.main :as lmain]
            [leiningen.core.project :as lproj]
            [clojure.java.io :as io]
            [clojure.string :refer [join split-lines]]))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; output

(defn- format-shell-result-for-print
  [{:keys [project result]}]
  (format "%s:\n%s"
          (:dir project)
          (->> result
            ((juxt :out :err))
            (join "\n")
            split-lines
            (map (partial str "  "))
            (join "\n"))))

(defn- do-for-all-and-print
  [func projects & cmd+args]
  (doall
    (sequence
     (comp (map #(apply func % cmd+args))
           (map format-shell-result-for-print)
           (map-indexed #(format "%s/%s: %s\n\n" (inc %) (count projects) %2))
           (map print))
     projects))
  nil)

(defn- maybe-in-background
  [opts func]
  (let [{:keys [run-in-background?]} (merge {:run-in-background? true} opts)]
    (if run-in-background?
      (future (try (func) (catch Exception e (println e))))
      (func))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; shell invocation

(defn shell
  [{:keys [dir] :as project} & cmd+args]
  (shell/with-sh-dir dir
    {:project project :result (apply shell/sh cmd+args)}))

(defn print-shell-for-all
  [projects & opts+cmd+args]
  (let [[opts & rest] opts+cmd+args
        cmd+args (if (map? opts) rest opts+cmd+args)
        opts (if (map? opts) opts {})]
    (maybe-in-background
     opts #(apply do-for-all-and-print shell projects cmd+args))))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; git

(defn git-status-for-all
  [projects & [opts]]
  (let [commands
        "[[ ! -d .git ]] && exit 0;
        branch=$(git branch | grep \"*\" | cut -d \" \" -f 2);
        local_hash=$(git show-ref --hash $branch | tail -n 1);
        remote_hash=$(git ls-remote origin $branch | cut -f 1);
        if [[ $local_hash = $remote_hash ]]; then echo \"No remote updates\"; else echo \"Remote updates\"; fi;
        git status --porcelain;"]
    (print-shell-for-all projects (or opts {}) "/bin/bash" "-c" commands)))

(defn git-short-status-for-all
  [projects & [opts]]
  (print-shell-for-all projects (or opts {}) "git" "status" "--porcelain"))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-
; leiningen

(defn lein-project-map
  [{:keys [dir] :as project}]
  (lproj/project-with-profiles
   (lproj/read
    (str (io/file dir "project.clj")))))

(defn install
  [project]
  (let [cmds (if (-> project lein-project-map :aliases (get "cleaninstall"))
               ["cleaninstall"] ["do" "clean," "install"])]
    (apply shell project "lein" cmds)))

(defn install-all
  [projects & [opts]]
  (maybe-in-background
   opts #(do-for-all-and-print install projects)))

(defn deploy-clojars
  [project]
  (let [cmds (if (-> project lein-project-map :aliases (get "cleandeploy"))
               ["cleandeploy"] ["do" "clean," "deploy" "clojars"])]
    (apply shell project "lein" cmds)))

(defn deploy-clojars-all
  [projects & [opts]]
  (maybe-in-background
   opts #(do-for-all-and-print deploy-clojars projects)))

(defn run-tests
  [project]
  (let [cmds (if (-> project lein-project-map :aliases (get "cleantest"))
               ["cleantest"] ["do" "clean," "test"])]
    (apply shell project "lein" cmds)))

(defn run-tests-all
  [projects & [opts]]
  (maybe-in-background
   opts #(do-for-all-and-print run-tests projects)))

(defn lein
  [project & task+args]
  (binding [leiningen.core.main/*exit-process?* false
            leiningen.core.main/*debug* false
            leiningen.core.main/*info* false
            ;  *err* *out*
            ;  *out* (java.io.StringWriter.)
            ]
    (lmain/resolve-and-apply (lein-project-map project) task+args)
    #_(str *out*)))

; -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

(comment
 (require '[rksm.cloxp-projects.core :as projs])
 (require '[rksm.cloxp-projects.dependencies :as proj-deps])
 (require '[rksm.cloxp-projects.tasks :as tasks])
 
 (def projects (proj-deps/sort-by-deps
                (rksm.cloxp-projects.core/project-infos
                 ["/Users/robert/clojure/clojure-system-files"
                  "/Users/robert/clojure/cloxp"
                  "/Users/robert/clojure/cloxp-4clojure"
                  "/Users/robert/clojure/cloxp-cljs"
                  "/Users/robert/clojure/cloxp-cljs-repl"
                  "/Users/robert/clojure/cloxp-com"
                  "/Users/robert/clojure/cloxp-lein"
                  "/Users/robert/clojure/cloxp-projects"
                  "/Users/robert/clojure/cloxp-repl"
                  "/Users/robert/clojure/cloxp-server"
                  "/Users/robert/clojure/cloxp-source-reader"
                  "/Users/robert/clojure/cloxp-trace"
                  "/Users/robert/clojure/subprocess"
                  "/Users/robert/clojure/system-navigator"
                  "/Users/robert/clojure/cljs-slimerjs-tester"
                  ; "/Users/robert/clojure/test-helpers"
                  ; "/Users/robert/clojure/cljs-eval-test"
                  ; "/Users/robert/clojure/cljs-bootstrap/cljs-bootstrap"
                  ; "/Users/robert/clojure/simple-cljs-project"
                  ]
                 {:include-plugins? true, :include-dev? true, :clean? true})))
 
 (def p (first (proj-deps/projects-matching projects "cloxp-projects")))
 (def p (first (proj-deps/projects-matching projects "cloxp-cljs")))
 
 (def cljs-projects (proj-deps/sort-by-deps
                     (proj-deps/projects-with-dep-matching
                      projects 'org.clojure/clojurescript)))
 
 (->> cljs-projects (map :name))
 (->> cljs-projects (map :group))
 
 (def f (future (loop [] (Thread/sleep 500) (println "alive!") (recur))))
 (future-cancel f)
 
 (->> cljs-projects (map :dir) (clojure.string/join "\n") print)
 
 (tasks/git-short-status-for-all projects)
 (tasks/git-short-status-for-all projects {:run-in-background? true})
 (tasks/git-short-status-for-all projects {:run-in-background? false})
 (tasks/git-status-for-all projects)
 
 (tasks/install-all projects)
 (tasks/install-all cljs-projects)
 (tasks/install-all [p])
 
 (tasks/deploy-clojars-all [p])
 (tasks/deploy-clojars-all projects)
 (tasks/deploy-clojars-all [p])
 
 (tasks/run-tests-all [p])
 (tasks/run-tests-all projects)
 
 (tasks/print-shell-for-all projects "ls")
 
 (tasks/print-shell-for-all projects {:run-in-background? false} "ls")
 
 (tasks/print-shell-for-all projects "ls")
 
 (tasks/print-shell-for-all projects "lein" "install")
 (tasks/print-shell-for-all projects "lein" "ancient")
 (tasks/print-shell-for-all projects "lein" "do" "ancient")
 )