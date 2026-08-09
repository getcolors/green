(ns green.scaffold
  "Flat file-spec scaffolding DSL. A spec is a seq of maps, one per file:

    {:template :zk/main.tf                    ; classpath resource zk/main.tf
     :target   \"{{workdir}}/n/{{node.id}}/main.tf\" ; Selmer-rendered vs :data
     :data     {...}}

  On :green/event :delete the same specs name the targets to remove."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [selmer.parser :as selmer]))

(defn template-path
  "Resource path for a qualified template keyword: namespace dots become
  directories, the name is used verbatim. :my.app/zoo.cfg -> my/app/zoo.cfg"
  [kw]
  (str (some-> (namespace kw) (str/replace "." "/") (str "/"))
       (name kw)))

(defn render-template
  "Render the classpath template named by qualified keyword `kw` with `data`.
  Optional `opts` are forwarded to selmer/render — use :tag-open, :tag-close,
  :filter-open, :filter-close to override delimiters (e.g. <{ }> and <% %>
  for Ansible files that reserve {{ }}/{% %} for Jinja2)."
  ([kw data] (render-template kw data nil))
  ([kw data opts]
   (let [path (template-path kw)
         res (io/resource path)]
     (when-not res
       (throw (ex-info (str "template not found on classpath: " path)
                       {:template kw :path path})))
     (selmer/render (slurp res) data opts))))

(defn- prune-empty-dir! [^java.io.File f]
  (let [p (.getParentFile f)]
    (when (and p (.isDirectory p) (empty? (.list p)))
      (.delete p))))

(def preserve-jinja-delimiters
  "Selmer delimiters that leave Ansible/Jinja delimiters untouched."
  {:tag-open \< :tag-close \> :filter-open \{ :filter-close \}})

(defn content-spec
  "A scaffold spec that writes `content` exactly, without template rendering."
  [target content]
  {:target target :content content :data {}})

(defn- target-path [{:keys [target data]}]
  (selmer/render target (or data {})))

(defn- target-paths [specs]
  (mapv target-path specs))

(defn- delete-target! [target]
  (let [f (io/file target)]
    (when (.exists f) (io/delete-file f))
    (prune-empty-dir! f)))

(defn- create-target! [{:keys [template content data opts] :as spec} target]
  (let [f (io/file target)]
    (io/make-parents f)
    (spit f (if (contains? spec :content)
              (str content)
              (render-template template data opts)))))

(defn- delete-targets! [targets]
  (doseq [target targets]
    (delete-target! target)))

(defn- create-targets! [specs targets]
  (doseq [[spec target] (map vector specs targets)]
    (create-target! spec target)))

(defn scaffold
  "Materialize `specs` (create) or remove their targets (delete), driven by
  :green/event in `opts`. Returns opts with :green/exit 0 and the affected
  paths under :green.scaffold/written or :green.scaffold/deleted."
  [opts specs]
  (let [specs (vec specs)
        targets (target-paths specs)]
    (if (= :delete (:green/event opts))
      (do (delete-targets! targets)
          (assoc opts :green/exit 0 :green.scaffold/deleted targets))
      (do (create-targets! specs targets)
          (assoc opts :green/exit 0 :green.scaffold/written targets)))))
