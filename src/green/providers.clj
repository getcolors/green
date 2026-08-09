(ns green.providers
  "Data-driven provider registry operations. Registries are caller-owned data."
  (:require [clojure.string :as str]))

(defn placeholder? [x]
  (or (nil? x)
      (and (string? x)
           (or (str/blank? x) (= "REPLACE_ME" (str/upper-case x))))))

(defn entry [registry opts slot]
  (get-in registry [slot (get opts slot)]))

(defn selected [registry opts slots]
  (into {} (map (fn [slot] [slot (entry registry opts slot)])) slots))

(defn slot-keys [registry opts slots field]
  (mapcat #(get (entry registry opts %) field []) slots))

(defn missing-keys [opts keys]
  (filterv #(placeholder? (get opts %)) keys))

(defn selection-errors [registry opts slots]
  (vec (keep (fn [slot]
           (let [name (get opts slot)]
             (when-not (get-in registry [slot name])
               (str (clojure.core/name slot) " has unsupported provider: " name))))
         slots)))

(defn required-errors [registry opts slots own-required]
  (mapv #(str (name %) " is required")
        (missing-keys opts (concat (slot-keys registry opts slots :required)
                                   own-required))))

(defn secret-errors [registry opts slots own-secrets par-name]
  (mapv #(str (par-name %) " is required")
        (missing-keys opts (concat (slot-keys registry opts slots :secrets)
                                   own-secrets))))

(defn tofu-env [registry opts slot]
  (:tofu-env (entry registry opts slot) {}))

(defn tool-env [registry opts slots]
  (not-empty
   (into {}
         (keep (fn [[key env-name]]
                 (let [value (get opts key)]
                   (when-not (placeholder? value) [env-name (str value)]))))
         (apply merge (map #(tofu-env registry opts %) slots)))))

(defn refuse-overlay [env key reason par-name]
  (when (get env (par-name key))
    [(str (par-name key) " is set; " reason)]))
