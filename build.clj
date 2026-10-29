(ns build
  "Build & publish green.

    clojure -T:build jar       # build target/green-<version>.jar + pom
    clojure -T:build install   # install into the local ~/.m2
    clojure -T:build deploy    # deploy to Clojars (CLOJARS_USERNAME/CLOJARS_PASSWORD)

  Consumers can skip Clojars entirely and use green as a git dep:
  io.github.getcolors/green {:git/sha \"…\"} once the repo is pushed."
  (:require [clojure.tools.build.api :as b]))

(def lib 'io.github.getcolors/green)
(def version "0.1.0")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src"]
                :scm {:url "https://github.com/getcolors/green"
                      :connection "scm:git:git://github.com/getcolors/green.git"
                      :developerConnection "scm:git:ssh://git@github.com/getcolors/green.git"
                      :tag (str "v" version)}
                :pom-data [[:description
                            "Canonical Clojure/Babashka colour of the getcolors workflow SDKs."]
                           [:url "https://github.com/getcolors/green"]
                           [:licenses
                            [:license
                             [:name "MIT License"]
                             [:url "https://opensource.org/license/mit"]]]]})
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file}))

(defn install [_]
  (jar nil)
  (b/install {:basis @basis :lib lib :version version
              :jar-file jar-file :class-dir class-dir}))

(defn deploy [_]
  (jar nil)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer :remote
    :artifact (b/resolve-path jar-file)
    :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
