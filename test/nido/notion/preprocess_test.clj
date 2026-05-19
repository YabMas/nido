(ns nido.notion.preprocess-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is]]
   [nido.notion.client :as notion]
   [nido.notion.preprocess :as pp]))

(deftest classify-loom-video-external
  (let [b {:id "b1" :type "video"
           :video {:type "external"
                   :external {:url "https://www.loom.com/share/abc123def456abc123def456abc123de"}}}]
    (is (= {:url "https://www.loom.com/share/abc123def456abc123def456abc123de"
            :kind :loom
            :block-id "b1"}
           (pp/classify b)))))

(deftest classify-notion-uploaded-video
  (let [b {:id "b2" :type "video"
           :video {:type "file"
                   :file {:url "https://prod-files-secure.s3/x.mp4?sig=z"}}}]
    (is (= {:url "https://prod-files-secure.s3/x.mp4?sig=z"
            :kind :notion-upload
            :block-id "b2"}
           (pp/classify b)))))

(deftest classify-loom-via-embed-block
  (let [b {:id "b3" :type "embed"
           :embed {:url "https://www.loom.com/embed/abc123def456abc123def456abc123de"}}]
    (is (= :loom (:kind (pp/classify b))))))

(deftest classify-loom-via-bookmark
  (let [b {:id "b4" :type "bookmark"
           :bookmark {:url "https://www.loom.com/share/abc123def456abc123def456abc123de"}}]
    (is (= :loom (:kind (pp/classify b))))))

(deftest classify-mp4-external-as-other
  (let [b {:id "b5" :type "video"
           :video {:type "external"
                   :external {:url "https://cdn.example.com/clip.mp4"}}}]
    (is (= :other (:kind (pp/classify b))))))

(deftest classify-ignores-paragraph
  (is (nil? (pp/classify {:id "b6" :type "paragraph"
                          :paragraph {:rich_text [{:plain_text "hi"}]}}))))

(deftest classify-ignores-embed-with-non-video
  (is (nil? (pp/classify {:id "b7" :type "embed"
                          :embed {:url "https://www.figma.com/file/X"}}))))

(deftest preprocess-ticket-happy-path-loom
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "out"))]
    (fs/create-dirs out)
    (with-redefs [pp/fetch-page-meta!  (fn [_pid _tok]
                                         {:last_edited_time "2026-05-19T10:00:00Z"})
                  notion/walk-blocks    (fn [_pid _tok _opts]
                                          [{:block {:id "b1" :type "video"
                                                    :video {:type "external"
                                                            :external {:url
                                                              "https://www.loom.com/share/abc123def456abc123def456abc123de"}}}
                                            :depth 0}])
                  pp/shell-bb-task     (fn [_args]
                                         {:exit 0
                                          :out "{:vtt-path \"01-loom-abc123de.vtt\" :transcript-source :loom-graphql}\n"
                                          :err ""})]
      ;; Pre-create the .vtt the (stubbed) shell-out would have produced
      (spit (str (fs/path out "01-loom-abc123de.vtt")) "WEBVTT\nok\n")
      (let [r (pp/preprocess-ticket!
                {:page-id "p1" :token "tok"
                 :out-dir out :budget-s 600 :max-videos 50})]
        (is (:ok? r))
        (let [m (edn/read-string (slurp (str (fs/path out "manifest.edn"))))]
          (is (= "p1" (:page-id m)))
          (is (= "2026-05-19T10:00:00Z" (:page-last-edited-time m)))
          (is (= 1 (count (:videos m))))
          (is (= :ok (-> m :videos first :status)))
          (is (= :loom-graphql (-> m :videos first :transcript-source))
              "transcript-source comes from parsed stdout, not inferred"))
        (is (fs/exists? (fs/path out "transcripts.md")))))
    (fs/delete-tree tmp)))

(deftest preprocess-ticket-partial-failure-still-writes-manifest
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "out"))]
    (fs/create-dirs out)
    (with-redefs [pp/fetch-page-meta!  (fn [_pid _tok] {:last_edited_time "x"})
                  notion/walk-blocks    (fn [_pid _tok _opts]
                                          [{:block {:id "b1" :type "video"
                                                    :video {:type "file"
                                                            :file {:url "https://s3/x.mp4"}}}
                                            :depth 0}])
                  pp/shell-bb-task     (fn [_args]
                                         {:exit 1
                                          :out ""
                                          :err "[nido :init] shell altered: foo\n{some debug noise}\n{:reason :whisper-crashed :detail {:exit 1}}\n"})]
      (let [r (pp/preprocess-ticket!
                {:page-id "p1" :token "tok"
                 :out-dir out :budget-s 600 :max-videos 50})]
        (is (:ok? r) "per-video failure is not a preprocessor failure")
        (let [m (edn/read-string (slurp (str (fs/path out "manifest.edn"))))]
          (is (= :failed (-> m :videos first :status)))
          (is (= :none (-> m :videos first :transcript-source))
              ":transcript-source must be :none on failed entries")
          ;; Parser takes the LAST {-prefixed line — must be {:reason :whisper-crashed}, not {some debug noise}
          (is (= :whisper-crashed (-> m :videos first :error :reason))))))
    (fs/delete-tree tmp)))

(deftest preprocess-ticket-page-walk-failure-aborts
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "out"))]
    (fs/create-dirs out)
    (with-redefs [pp/fetch-page-meta! (fn [_pid _tok] {:last_edited_time "x"})
                  notion/walk-blocks   (fn [_pid _tok _opts]
                                          (throw (ex-info "auth"
                                                          {:error {:error :auth}})))]
      (let [r (pp/preprocess-ticket!
                {:page-id "p1" :token "tok"
                 :out-dir out :budget-s 600 :max-videos 50})]
        (is (not (:ok? r)))
        (is (= :notion-auth (-> r :error :reason)))
        (is (not (fs/exists? (fs/path out "manifest.edn")))
            "no partial manifest on page-walk failure")))
    (fs/delete-tree tmp)))

(deftest preprocess-ticket-zero-videos-writes-empty-manifest
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "out"))]
    (fs/create-dirs out)
    (with-redefs [pp/fetch-page-meta!  (fn [_ _] {:last_edited_time "x"})
                  notion/walk-blocks    (fn [_ _ _]
                                          [{:block {:id "b1" :type "paragraph"}
                                            :depth 0}])
                  pp/shell-bb-task     (fn [_]
                                         (throw (ex-info "should not be called" {})))]
      (let [r (pp/preprocess-ticket!
                {:page-id "p1" :token "tok"
                 :out-dir out :budget-s 600 :max-videos 50})]
        (is (:ok? r))
        (let [m (edn/read-string (slurp (str (fs/path out "manifest.edn"))))]
          (is (= [] (:videos m))))
        (is (fs/exists? (fs/path out "transcripts.md")))))
    (fs/delete-tree tmp)))

(deftest preprocess-ticket-records-whisper-fallback-honestly
  ;; A Loom URL whose transcript was disabled — the bb task fell back to
  ;; whisper internally. The manifest must record :transcript-source :whisper,
  ;; NOT :loom-graphql inferred from the kind.
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "out"))]
    (fs/create-dirs out)
    (with-redefs [pp/fetch-page-meta!  (fn [_ _] {:last_edited_time "x"})
                  notion/walk-blocks    (fn [_ _ _]
                                          [{:block {:id "b1" :type "video"
                                                    :video {:type "external"
                                                            :external {:url
                                                              "https://www.loom.com/share/abc123def456abc123def456abc123de"}}}
                                            :depth 0}])
                  pp/shell-bb-task     (fn [_]
                                         {:exit 0
                                          :out "{:vtt-path \"01-loom-abc123de.vtt\" :transcript-source :whisper}\n"
                                          :err ""})]
      (spit (str (fs/path out "01-loom-abc123de.vtt")) "WEBVTT\nfallback\n")
      (let [r (pp/preprocess-ticket!
                {:page-id "p1" :token "tok" :out-dir out
                 :budget-s 600 :max-videos 50})]
        (is (:ok? r))
        (let [m (edn/read-string (slurp (str (fs/path out "manifest.edn"))))]
          (is (= :whisper (-> m :videos first :transcript-source))
              "Even though kind is :loom, the source must be :whisper because the bb task fell back"))))
    (fs/delete-tree tmp)))

(deftest preprocess-ticket-maps-http-error-to-notion-http-error
  (let [tmp (fs/create-temp-dir)
        out (str (fs/path tmp "out"))]
    (fs/create-dirs out)
    (with-redefs [pp/fetch-page-meta! (fn [_ _] {:last_edited_time "x"})
                  notion/walk-blocks   (fn [_ _ _]
                                          (throw (ex-info "http"
                                                          {:error {:error :http :status 404}})))]
      (let [r (pp/preprocess-ticket!
                {:page-id "p1" :token "tok" :out-dir out
                 :budget-s 600 :max-videos 50})]
        (is (not (:ok? r)))
        (is (= :notion-http-error (-> r :error :reason)))))
    (fs/delete-tree tmp)))
