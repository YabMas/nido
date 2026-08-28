(ns tasks.nido-transcribe
  "bb task entry point for video transcription.

   Usage:
     bb nido:transcribe-video <url> :out <path> [:model :small] [:timeout 5m]

   Output:
     On success: prints `{:vtt-path \"...\" :transcript-source :loom-graphql|:whisper}` to stdout.
     On failure: prints `{:reason :kw :detail {...}}` to stderr, exits 1.
     On usage error: prints \"Usage:\" to stderr, exits 2."
  (:require
   [clojure.string :as str]
   [nido.platform.task-args :as task-args]
   [nido.transcribe :as transcribe]))

(defn exit! [code] (System/exit code))

(defn parse-duration
  "Parse a duration into integer seconds.
   Accepts:
     - Long/Integer → treated as seconds directly
     - \"NNN\" or \"NNNs\" → seconds
     - \"NNNm\" → minutes × 60"
  [s]
  (cond
    (nil? s)                       nil
    (integer? s)                   (int s)
    (and (string? s)
         (re-matches #"\d+s?" s))  (Integer/parseInt (str/replace s #"s$" ""))
    (and (string? s)
         (re-matches #"\d+m" s))   (* 60 (Integer/parseInt (str/replace s #"m$" "")))
    :else
    (throw (ex-info (str "Bad duration: " (pr-str s)) {:input s}))))

(defn run
  "bb nido:transcribe-video <url> :out <path> [:model :small] [:timeout 5m]"
  [& args]
  (let [[positionals opts] (task-args/split-args args)
        url   (first positionals)
        out   (:out opts)
        model (or (some-> (:model opts) (str/replace #"^:" "") keyword) :small)
        secs  (or (parse-duration (:timeout opts)) 300)]
    (if (or (str/blank? url) (str/blank? out))
      (do (binding [*out* *err*]
            (println "Usage: bb nido:transcribe-video <url> :out <path> [:model :small] [:timeout 5m]"))
          (exit! 2))
      (let [r (transcribe/video! {:url url :out out :model model :timeout-s secs})]
        (if (:ok? r)
          (println (pr-str (select-keys r [:vtt-path :transcript-source])))
          (do (binding [*out* *err*]
                (println (pr-str (:error r))))
              (exit! 1)))))))
