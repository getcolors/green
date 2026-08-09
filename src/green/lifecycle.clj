(ns green.lifecycle
  "Composable package lifecycle preflight."
  (:require [clojure.string :as str]))

(defn real-run?
  "True unless the SDK dry-run flag is set."
  [opts]
  (not (:green/dry-run opts)))

(defn preflight
  "Apply defaults/overlay, aggregate validators in order, and optionally run a
  successful callback. Validators receive `(opts env context)` and return
  error strings. Returns exit 2 for validation errors."
  ([opts config] (preflight opts config (System/getenv)))
  ([opts {:keys [defaults overlay validators after-validate]
          :or {defaults {} overlay (fn [x _] x) validators []}} env]
   (let [opts (overlay (merge defaults opts) env)
         context {:event (:green/event opts) :real? (real-run? opts)}
         errors (vec (mapcat #(or (% opts env context) []) validators))]
     (if (seq errors)
       (assoc opts :green/exit 2 :green/err (str/join "\n" errors))
       (if after-validate
         (after-validate opts env context)
         (assoc opts :green/exit 0))))))
