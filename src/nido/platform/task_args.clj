(ns nido.platform.task-args
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

(defn- distance
  "Levenshtein distance between two strings."
  [^String a ^String b]
  (let [n (count b)]
    (peek (reduce (fn [prev [i ca]]
                    (reduce (fn [row [j cb]]
                              (conj row (min (inc (peek row))
                                             (inc (nth prev (inc j)))
                                             (+ (nth prev j) (if (= ca cb) 0 1)))))
                            [(inc i)]
                            (map-indexed vector b)))
                  (vec (range (inc n)))
                  (map-indexed vector a)))))

(defn ^{:malli/schema [:=> [:cat :map [:set :keyword]] [:maybe :string]]}
  unknown-opts-refusal
  "Why `opts` cannot be run by a task that reads exactly the keys in `known`, or nil when every
   key is one of them. Names each unknown key with the nearest known one, when one is within two
   edits — `:dry-run` for `:dry-run?` — and then every key the task takes.

   split-args keeps any key it is given, so without this a mistyped option is dropped by the
   destructuring that reads the map and the task runs as though it had never been typed."
  [opts known]
  (when-let [unknown (seq (sort-by str (remove (set known) (keys opts))))]
    (let [nearest (fn [k] (->> known
                               (map (fn [kn] [(distance (str k) (str kn)) kn]))
                               (filter #(<= (first %) 2))
                               sort first second))]
      (str "unknown option" (when (next unknown) "s") " "
           (apply str (interpose ", " (for [k unknown]
                                        (str k (when-let [n (nearest k)]
                                                 (str " (closest: " n ")"))))))
           " — this task takes " (apply str (interpose " " (sort-by str known)))))))
