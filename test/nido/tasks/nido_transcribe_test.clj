(ns nido.tasks.nido-transcribe-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.transcribe :as transcribe]
   [tasks.nido-transcribe :as task]))

(deftest parse-duration-accepts-m-and-s-and-ints
  (is (= 300 (task/parse-duration "5m")))
  (is (= 120 (task/parse-duration "120s")))
  (is (= 90  (task/parse-duration "90"))))

(deftest parse-duration-accepts-numeric-inputs
  ;; split-args EDN-parses bare integers into Long; parse-duration must handle that.
  (is (= 90 (task/parse-duration 90)))
  (is (= 300 (task/parse-duration 300))))

(deftest run-prints-vtt-path-on-success
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "out.vtt"))
        captured-opts (atom nil)]
    (with-redefs [transcribe/video! (fn [opts]
                                       (reset! captured-opts opts)
                                       {:ok? true :vtt-path out
                                        :transcript-source :loom-graphql})]
      (let [stdout (with-out-str
                     (binding [*err* (java.io.StringWriter.)]
                       (try
                         (task/run "https://www.loom.com/share/abc"
                                   ":out" out
                                   ":model" ":small"
                                   ":timeout" "5m")
                         (catch Exception _ nil))))]
        ;; Parse the EDN result map from stdout
        (let [result (clojure.edn/read-string (str/trim stdout))]
          (is (= out (:vtt-path result)))
          (is (= :loom-graphql (:transcript-source result))))
        (is (= "https://www.loom.com/share/abc" (:url @captured-opts)))
        (is (= out (:out @captured-opts)))
        (is (= :small (:model @captured-opts)))
        (is (= 300 (:timeout-s @captured-opts)))))
    (fs/delete-tree tmp)))

(deftest run-prints-edn-on-failure-and-exits-nonzero
  (let [exit-code (atom nil)]
    (with-redefs [transcribe/video! (fn [_opts]
                                      {:ok? false
                                       :error {:reason :loom-server-error
                                               :detail {:status 500}}})
                  task/exit!       (fn [code] (reset! exit-code code))]
      (let [stderr-w (java.io.StringWriter.)]
        (binding [*err* stderr-w]
          (task/run "https://www.loom.com/share/abc" ":out" "/tmp/x.vtt"))
        (is (= 1 @exit-code))
        (is (re-find #":loom-server-error" (str stderr-w)))))))

(deftest run-missing-args-exits-2-without-calling-video
  (let [exit-code (atom nil)
        video-calls (atom 0)]
    (with-redefs [task/exit!        (fn [c] (reset! exit-code c))
                  transcribe/video! (fn [_] (swap! video-calls inc)
                                            {:ok? true :vtt-path ""})]
      (let [stderr-w (java.io.StringWriter.)]
        (binding [*err* stderr-w]
          (task/run))           ;; no args at all
        (is (= 2 @exit-code))
        (is (= 0 @video-calls)
            "video! should not be called when usage check fails")
        (is (re-find #"Usage:" (str stderr-w)))))))
