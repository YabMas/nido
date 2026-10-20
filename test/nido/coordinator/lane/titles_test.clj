(ns nido.coordinator.lane.titles-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.lane.titles :as titles]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.core :as core]
   [nido.session.state :as session-state]))

(defn- notion-ws! [title]
  (ws/create! :brian {:stage :triaging
                      :external-refs [{:adapter :notion :id "BR-1" :title title}]}))

(deftest refresh-titles-a-workstream-from-its-basis
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (let [w (notion-ws! "fix the drift in session index")]
          (is (= 1 (titles/refresh-project! :brian (fn [b] (str "Title of " b)))))
          (is (= {:text  "Title of fix the drift in session index"
                  :basis "fix the drift in session index"}
                 (:title (ws/read-ws :brian (:id w)))))))
      (finally (fs/delete-tree tmp)))))

(deftest refresh-skips-a-title-whose-basis-is-unchanged
  (let [tmp   (fs/create-temp-dir)
        calls (atom 0)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (notion-ws! "fix the drift")
        (let [gen (fn [b] (swap! calls inc) b)]
          (titles/refresh-project! :brian gen)
          (is (= 0 (titles/refresh-project! :brian gen)))
          (is (= 1 @calls) "an unchanged basis costs no second call")))
      (finally (fs/delete-tree tmp)))))

(deftest refresh-leaves-the-record-alone-when-generation-fails
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (let [w (notion-ws! "fix the drift")]
          (is (= 0 (titles/refresh-project! :brian (constantly nil))))
          (is (nil? (:title (ws/read-ws :brian (:id w)))))))
      (finally (fs/delete-tree tmp)))))

(deftest refresh-clears-a-title-whose-workstream-lost-its-basis
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
          (ws/set-title! :brian (:id w) "Invented title" "review-analysis-x-4caea003-9378-4ffa")
          (is (= 1 (titles/refresh-project! :brian (constantly "unused"))))
          (is (nil? (:title (ws/read-ws :brian (:id w)))))))
      (finally (fs/delete-tree tmp)))))

(deftest clean-rejects-what-is-not-a-title
  (is (= "Drift in the session index" (#'titles/clean "\"Drift in the session index.\"\n")))
  (is (nil? (#'titles/clean "")))
  (is (nil? (#'titles/clean (apply str (repeat 100 "x"))))))

(deftest tab-titles-names-each-session-home-and-worktree-by-the-board-label
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root              (constantly (str tmp))
                    core/nido-home              (constantly (str tmp))
                    session/list-sessions       (fn [_ _] [{:name "rag-raptor-index"}])
                    session-state/read-registry (constantly {"/wt/rag-raptor-index"
                                                             {:instance-id "brian--rag-raptor-index"}})]
        (let [w (notion-ws! "fix the drift")]
          (ws/set-title! :brian (:id w) "Drift in\tthe index" "fix the drift")
          (is (= [[(str tmp "/sessions/brian/rag-raptor-index") "Drift in the index"]
                  ["/wt/rag-raptor-index" "Drift in the index"]]
                 (titles/tab-titles :brian)))
          (is (true? (titles/write-tab-titles! [:brian])))
          (is (false? (titles/write-tab-titles! [:brian])) "unchanged content is not rewritten")
          (is (= (str tmp "/sessions/brian/rag-raptor-index\tDrift in the index\n"
                      "/wt/rag-raptor-index\tDrift in the index\n")
                 (slurp (titles/tab-titles-path))))))
      (finally (fs/delete-tree tmp)))))

(deftest generation-is-a-one-shot-call-that-loads-no-local-settings
  (let [argv (atom nil)]
    (with-redefs [babashka.process/process (fn [_opts & args] (reset! argv (vec args)) (doto (promise) (deliver {:exit 0 :out "A title"})))]
      (is (= "A title" (titles/generate "basis"))))
    (is (= "user,project" (second (drop-while #(not= "--setting-sources" %) @argv))))))
