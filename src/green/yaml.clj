(ns green.yaml
  "A YAML emitter for the subset a generated Ansible file needs: maps,
  sequences, and scalars.

  Deliberately small. Everything is emitted in block style with quoted string
  scalars, which sidesteps the parts of YAML that bite — no anchors, no
  multi-line folding, no bare strings that a reader could mistake for a
  boolean or a number. Ordering is the map's own, so a sorted map in gives
  deterministic bytes out."
  (:require
   [clojure.string :as str]))

(defn- yaml-key
  [k]
  (if (keyword? k)
    (name k)
    (str k)))

(defn- yaml-scalar?
  [x]
  (or (nil? x)
      (string? x)
      (keyword? x)
      (boolean? x)
      (number? x)
      (and (map? x) (empty? x))
      (and (sequential? x) (empty? x))))

(defn- yaml-scalar
  [x]
  (cond
    (nil? x) "null"
    (string? x) (pr-str x)
    (keyword? x) (pr-str (name x))
    (true? x) "true"
    (false? x) "false"
    (number? x) (str x)
    (map? x) "{}"
    (sequential? x) "[]"
    :else (pr-str (str x))))

(declare yaml-lines)

(defn- map-lines
  [m indent]
  (mapcat (fn [[k v]]
            (let [prefix (str (apply str (repeat indent \space)) (yaml-key k) ":")]
              (if (yaml-scalar? v)
                [(str prefix " " (yaml-scalar v))]
                (cons prefix (yaml-lines v (+ indent 2))))))
          m))

(defn- sequence-item-lines
  [x indent]
  (let [prefix (str (apply str (repeat indent \space)) "-")]
    (if (yaml-scalar? x)
      [(str prefix " " (yaml-scalar x))]
      (let [child-indent (+ indent 2)
            child-prefix (apply str (repeat child-indent \space))
            lines (vec (yaml-lines x child-indent))]
        (if (empty? lines)
          [prefix]
          (into [(str prefix " " (subs (first lines) (count child-prefix)))]
                (subvec lines 1)))))))

(defn- yaml-lines
  [x indent]
  (cond
    (map? x) (map-lines x indent)
    (sequential? x) (mapcat #(sequence-item-lines % indent) x)
    :else [(str (apply str (repeat indent \space)) (yaml-scalar x))]))

(defn generate-string
  "Serialize maps, sequences, and scalars to the YAML subset described above,
  terminated by a newline."
  [data]
  (str (str/join "\n" (yaml-lines data 0)) "\n"))
