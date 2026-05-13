(ns tasks.nido-test
  "Run unit tests under test/. Optional :only <ns-prefix> filter."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :as t]
   [nido.task-args :as task-args]))

(defn- discover-test-namespaces
  "Find test namespaces under test/, optionally filtered by prefix."
  [prefix]
  (->> (fs/glob "test" "**/*_test.clj")
       (map str)
       (map #(-> %
                 (str/replace #"^test/" "")
                 (str/replace #"\.clj$" "")
                 (str/replace "_" "-")
                 (str/replace "/" ".")
                 symbol))
       (filter (fn [ns-sym]
                 (or (str/blank? prefix)
                     (str/starts-with? (str ns-sym) prefix))))
       sort))

(defn run [& args]
  (let [[_ opts] (task-args/split-args args)
        only     (some-> (:only opts) str)
        nses     (discover-test-namespaces only)]
    (when (empty? nses)
      (println "No test namespaces found." (when only (str "filter: " only)))
      (System/exit 0))
    (doseq [ns-sym nses]
      (require ns-sym))
    (let [{:keys [fail error]} (apply t/run-tests nses)]
      (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))))
