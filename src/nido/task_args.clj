(ns nido.task-args
  "Shared CLI argument parser for `bb nido:*` tasks. The bb task layer hands
   us a flat seq of strings; we split it into [positionals opts-map]."
  (:require
   [clojure.edn :as edn]))

(defn- parse-token
  "Parse a CLI token as EDN, with one carve-out: top-level symbols stay as
   their original string. `bb nido:session:up :base origin/main` would
   otherwise produce the symbol `origin/main`, which crashes anything that
   runs a regex or string operation on it. Other shapes (keywords, numbers,
   booleans, vectors, maps) parse as usual — including vectors of symbols
   like `[dev cider/nrepl]`, where downstream code expects them."
  [tok]
  (let [parsed (try (edn/read-string tok) (catch Exception _ tok))]
    (if (symbol? parsed) tok parsed)))

(defn- keyword-token? [tok]
  (and (string? tok) (.startsWith ^String tok ":")))

(defn split-args
  "Split CLI args into [positionals opts-map]. A token starting with ':' is
   a kwarg key and consumes the next token as its value; every other token
   is a positional. Preserves positional order.

   `raw-string-keys` (optional set) names kwarg keys whose values must be
   passed through verbatim — EDN-parsing is lossy for values like URLs
   (`/123` reads as int) or multi-word titles (read-string consumes only
   the first form)."
  ([args] (split-args args #{}))
  ([args raw-string-keys]
   (loop [xs args, pos [], opts {}]
     (if (empty? xs)
       [pos opts]
       (let [x (first xs)]
         (if (keyword-token? x)
           (let [k (parse-token x)
                 v (second xs)]
             (when-not (some? v)
               (throw (ex-info (str "Missing value for " x) {:args args})))
             (recur (drop 2 xs) pos
                    (assoc opts k (if (contains? raw-string-keys k)
                                    (str v)
                                    (parse-token v)))))
           (recur (rest xs) (conj pos x) opts)))))))
