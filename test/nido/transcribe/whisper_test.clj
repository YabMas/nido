(ns nido.transcribe.whisper-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.transcribe.whisper :as whisper]))

(defn- stub-sh [{:keys [exit out err]}]
  (fn [_args _opts] {:exit exit :out (or out "") :err (or err "")}))

(deftest run-builds-correct-command
  (let [tmp   (fs/create-temp-dir)
        calls (atom [])
        out   (str (fs/path tmp "out"))]
    (fs/create-dirs out)
    (spit (str (fs/path tmp "in.mp4")) "fake")
    (spit (str (fs/path out "in.vtt")) "WEBVTT\n")
    (with-redefs [whisper/sh! (fn [args _opts]
                                (swap! calls conj args)
                                {:exit 0 :out "" :err ""})]
      (let [r (whisper/run! {:input    (str (fs/path tmp "in.mp4"))
                             :model    :small
                             :out-dir  out
                             :timeout-s 300})]
        (is (:ok? r))
        (is (= (str (fs/path out "in.vtt")) (:vtt-path r)))))
    (let [[cmd] @calls]
      (is (= "whisper" (first cmd)))
      (is (some #{"--model"} cmd))
      (is (some #{"small"} cmd))
      (is (some #{"--output_format"} cmd))
      (is (some #{"vtt"} cmd))
      (is (some #{"--language"} cmd))
      (is (some #{"--fp16"} cmd))
      (is (some #{"False"} cmd)))
    (fs/delete-tree tmp)))

(deftest run-returns-structured-error-on-non-zero-exit
  (let [tmp (fs/create-temp-dir)]
    (spit (str (fs/path tmp "in.mp4")) "fake")
    (with-redefs [whisper/sh! (stub-sh {:exit 1 :err "boom"})]
      (let [r (whisper/run! {:input    (str (fs/path tmp "in.mp4"))
                             :model    :small
                             :out-dir  (str tmp)
                             :timeout-s 300})]
        (is (not (:ok? r)))
        (is (= :whisper-crashed (-> r :error :reason)))
        (is (= 1 (-> r :error :detail :exit)))
        (is (= "boom" (-> r :error :detail :stderr)))))
    (fs/delete-tree tmp)))

(deftest run-returns-timeout-error-when-sh-times-out
  (let [tmp (fs/create-temp-dir)]
    (spit (str (fs/path tmp "in.mp4")) "fake")
    (with-redefs [whisper/sh! (fn [_args _opts]
                                (throw (java.util.concurrent.TimeoutException. "timeout")))]
      (let [r (whisper/run! {:input    (str (fs/path tmp "in.mp4"))
                             :model    :small
                             :out-dir  (str tmp)
                             :timeout-s 5})]
        (is (not (:ok? r)))
        (is (= :whisper-timeout (-> r :error :reason)))
        (is (= 5 (-> r :error :detail :limit-s)))))
    (fs/delete-tree tmp)))

(deftest run-rejects-missing-input
  (let [r (whisper/run! {:input    "/does/not/exist.mp4"
                         :model    :small
                         :out-dir  "/tmp"
                         :timeout-s 300})]
    (is (not (:ok? r)))
    (is (= :input-missing (-> r :error :reason)))))

(deftest run-returns-vtt-missing-when-whisper-exits-zero-but-no-output
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "out"))]
    (fs/create-dirs out)
    (spit (str (fs/path tmp "in.mp4")) "fake")
    ;; Do NOT pre-create the .vtt; whisper "succeeded" but wrote nothing.
    (with-redefs [whisper/sh! (fn [_args _opts] {:exit 0 :out "" :err ""})]
      (let [r (whisper/run! {:input    (str (fs/path tmp "in.mp4"))
                             :model    :small
                             :out-dir  out
                             :timeout-s 300})]
        (is (not (:ok? r)))
        (is (= :vtt-missing (-> r :error :reason)))))
    (fs/delete-tree tmp)))
