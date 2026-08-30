(ns nido.transcribe.whisper
  "Shell-out to the openai-whisper Python CLI. `sh!` is a redef seam so
   tests stub the subprocess entirely.

   Returns:
     {:ok? true  :vtt-path \"...\"}
     {:ok? false :error {:reason :whisper-crashed :detail {:exit n :stderr \"...\"}}}
     {:ok? false :error {:reason :whisper-timeout :detail {:limit-s n}}}
     {:ok? false :error {:reason :input-missing   :detail {:input p}}}
     {:ok? false :error {:reason :vtt-missing     :detail {:expected p}}}"
  (:refer-clojure :exclude [run!])
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p])
  (:import (java.util.concurrent TimeoutException)))

(defn ^{:malli/schema [:=> [:cat :any :map] :map]}
  sh!
  "Wrapped shell-out. Tests stub this. Returns {:exit :out :err}.
   When opts contains :timeout-s, the process is killed and
   TimeoutException is thrown after the limit. Without :timeout-s,
   blocks until the process exits."
  [args {:keys [timeout-s]}]
  (let [proc (p/process args {:out :string :err :string})]
    (if timeout-s
      ;; Double-wrap with `future` because `p/process`'s deref isn't time-bounded.
      (let [timeout-ms (* 1000 timeout-s)
            result     (deref (future @proc) timeout-ms ::timeout)]
        (if (= ::timeout result)
          (do (p/destroy-tree proc)
              (throw (TimeoutException.
                       (str "whisper timed out after " timeout-s "s"))))
          result))
      @proc)))

(defn- vtt-path-for [input out-dir]
  (let [stem (-> input fs/file-name fs/strip-ext)]
    (str (fs/path out-dir (str stem ".vtt")))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  run!
  "Transcribe a local audio/video file with whisper. See ns docstring."
  [{:keys [input model out-dir timeout-s]
    :or   {model :small timeout-s 300}}]
  (if-not (fs/exists? input)
    {:ok? false :error {:reason :input-missing :detail {:input input}}}
    (let [args ["whisper" input
                "--model"         (name model)
                "--output_format" "vtt"
                "--output_dir"    out-dir
                "--language"      "en"
                "--fp16"          "False"]]
      (fs/create-dirs out-dir)
      (try
        (let [{:keys [exit err]} (sh! args {:timeout-s timeout-s})]
          (if (zero? exit)
            (let [vtt (vtt-path-for input out-dir)]
              (if (fs/exists? vtt)
                {:ok? true :vtt-path vtt}
                {:ok? false :error {:reason :vtt-missing
                                    :detail {:expected vtt}}}))
            {:ok? false :error {:reason :whisper-crashed
                                :detail {:exit exit :stderr err}}}))
        (catch TimeoutException _
          {:ok? false :error {:reason :whisper-timeout
                              :detail {:limit-s timeout-s}}})))))
