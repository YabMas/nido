(ns nido.transcribe.core-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.transcribe.core :as t]
   [nido.transcribe.loom :as loom]
   [nido.transcribe.whisper :as whisper]))

(deftest loom-fast-path-writes-vtt-from-graphql
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "01.vtt"))]
    (with-redefs [loom/fetch-vtt (fn [_id]
                                   {:ok? true :vtt-text "WEBVTT\nhi\n"})]
      (let [r (t/video! {:url "https://www.loom.com/share/abc123def456abc123def456abc123de"
                         :out out :model :small :timeout-s 60})]
        (is (:ok? r))
        (is (= :loom-graphql (:transcript-source r)))
        (is (= out (:vtt-path r)))
        (is (= "WEBVTT\nhi\n" (slurp out)))))
    (fs/delete-tree tmp)))

(deftest loom-fallback-resolves-mp4-then-whisper
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "01.vtt"))
        downloaded (atom nil)]
    (with-redefs [loom/fetch-vtt        (fn [_id]
                                          {:ok? false
                                           :error {:reason :loom-transcript-unavailable}})
                  loom/video-source-url (fn [_id]
                                          {:ok? true :url "https://cdn.loom.com/x.mp4"})
                  t/download-to-temp!   (fn [url]
                                          (reset! downloaded url)
                                          {:ok? true :path (str (fs/path tmp "x.mp4"))})
                  whisper/run!          (fn [_opts]
                                          (spit out "WEBVTT\nfallback\n")
                                          {:ok? true :vtt-path out})]
      (let [r (t/video! {:url "https://www.loom.com/share/abc123def456abc123def456abc123de"
                         :out out :model :small :timeout-s 60})]
        (is (:ok? r))
        (is (= :whisper (:transcript-source r)))
        (is (= "https://cdn.loom.com/x.mp4" @downloaded))))
    (fs/delete-tree tmp)))

(deftest non-loom-url-goes-straight-to-whisper
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "01.vtt"))
        downloaded (atom nil)]
    (with-redefs [t/download-to-temp! (fn [url]
                                        (reset! downloaded url)
                                        {:ok? true :path (str (fs/path tmp "x.mp4"))})
                  whisper/run!        (fn [_opts]
                                        (spit out "WEBVTT\nok\n")
                                        {:ok? true :vtt-path out})]
      (let [r (t/video! {:url "https://prod-files-secure.s3/x.mp4"
                         :out out :model :small :timeout-s 60})]
        (is (:ok? r))
        (is (= :whisper (:transcript-source r)))
        (is (= "https://prod-files-secure.s3/x.mp4" @downloaded))))
    (fs/delete-tree tmp)))

(deftest whisper-failure-propagates
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "01.vtt"))]
    (with-redefs [t/download-to-temp! (fn [_url]
                                        {:ok? true :path (str (fs/path tmp "x.mp4"))})
                  whisper/run!        (fn [_opts]
                                        {:ok? false
                                         :error {:reason :whisper-crashed
                                                 :detail {:exit 1}}})]
      (let [r (t/video! {:url "https://example.com/x.mp4"
                         :out out :model :small :timeout-s 60})]
        (is (not (:ok? r)))
        (is (= :whisper-crashed (-> r :error :reason)))))
    (fs/delete-tree tmp)))

(deftest download-failure-propagates
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "01.vtt"))]
    (with-redefs [t/download-to-temp! (fn [_url]
                                        {:ok? false
                                         :error {:reason :download-failed
                                                 :detail {:status 404}}})]
      (let [r (t/video! {:url "https://example.com/x.mp4"
                         :out out :model :small :timeout-s 60})]
        (is (not (:ok? r)))
        (is (= :download-failed (-> r :error :reason)))))
    (fs/delete-tree tmp)))

(deftest both-loom-paths-fail-propagates-source-error
  ;; Loom transcript unavailable AND video-source-url also fails — error
  ;; from video-source-url is returned verbatim.
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "01.vtt"))]
    (with-redefs [loom/fetch-vtt        (fn [_id]
                                          {:ok? false
                                           :error {:reason :loom-transcript-unavailable}})
                  loom/video-source-url (fn [_id]
                                          {:ok? false
                                           :error {:reason :loom-server-error
                                                   :detail {:status 502}}})]
      (let [r (t/video! {:url "https://www.loom.com/share/abc123def456abc123def456abc123de"
                         :out out :model :small :timeout-s 60})]
        (is (not (:ok? r)))
        (is (= :loom-server-error (-> r :error :reason)))
        (is (= 502 (-> r :error :detail :status)))))
    (fs/delete-tree tmp)))

(deftest loom-happy-path-creates-parent-dir-when-missing
  ;; out's parent dir doesn't exist — write-vtt! creates it.
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "deeply" "nested" "01.vtt"))]
    (with-redefs [loom/fetch-vtt (fn [_id]
                                   {:ok? true :vtt-text "WEBVTT\nx\n"})]
      (let [r (t/video! {:url "https://www.loom.com/share/abc123def456abc123def456abc123de"
                         :out out :model :small :timeout-s 60})]
        (is (:ok? r))
        (is (= "WEBVTT\nx\n" (slurp out)))))
    (fs/delete-tree tmp)))
