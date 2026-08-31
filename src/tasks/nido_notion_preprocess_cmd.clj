(ns tasks.nido-notion-preprocess-cmd
  "bb task entry point for Notion ticket preprocessing.

   Usage:
     bb nido:notion:preprocess-ticket :page <id> :out <dir> [:budget 10m]

   Output:
     On success: prints `<out>/manifest.edn` path and a summary line.
     On preprocessor failure: prints `{:reason :kw :detail {...}}` to stderr, exits 1.
     On missing args / missing token: prints \"Usage:\" or \"No Notion token\" to stderr, exits 2."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.notion.client :as notion]
   [nido.notion.preprocess :as pp]
   [nido.platform.task-args :as task-args]))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  exit! [code] (System/exit code))

(defn- parse-duration
  "Parse a duration into integer seconds. Accepts:
     - Long/Integer → seconds directly
     - \"NNN\" or \"NNNs\" → seconds
     - \"NNNm\" → minutes × 60
     - \"NNNh\" → hours × 3600"
  [s]
  (cond
    (nil? s)                            nil
    (integer? s)                        (int s)
    (and (string? s) (re-matches #"\d+s?" s)) (Integer/parseInt (str/replace s #"s$" ""))
    (and (string? s) (re-matches #"\d+m" s))  (* 60 (Integer/parseInt (str/replace s #"m$" "")))
    (and (string? s) (re-matches #"\d+h" s))  (* 3600 (Integer/parseInt (str/replace s #"h$" "")))
    :else
    (throw (ex-info (str "Bad duration: " (pr-str s)) {:input s}))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  run
  "bb nido:notion:preprocess-ticket :page <id> :out <dir> [:budget 10m]"
  [& args]
  (let [[_ opts] (task-args/split-args args)
        page-id  (:page opts)
        out-dir  (:out opts)
        budget-s (or (parse-duration (:budget opts)) 600)]
    (if (or (str/blank? page-id) (str/blank? out-dir))
      (do (binding [*out* *err*]
            (println "Usage: bb nido:notion:preprocess-ticket :page <id> :out <dir> [:budget 10m]"))
          (exit! 2))
      (let [token (notion/keychain-token)]
        (if (str/blank? token)
          (do (binding [*out* *err*]
                (println "No Notion token in keychain. Run `bb nido:notion:auth:set` first."))
              (exit! 2))
          (let [r (pp/preprocess-ticket!
                    {:page-id  page-id
                     :token    token
                     :out-dir  out-dir
                     :budget-s budget-s})]
            (if (:ok? r)
              (do (println (str (fs/path out-dir "manifest.edn")))
                  (println (format "%d videos processed."
                                   (count (-> r :manifest :videos)))))
              (do (binding [*out* *err*]
                    (println (pr-str (:error r))))
                  (exit! 1)))))))))
