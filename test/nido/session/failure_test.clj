(ns nido.session.failure-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.session.failure :as failure]
   [nido.session.state :as state]))

(defn- with-homes
  "Call `f` with failures kept under one temp dir and instance state under another."
  [f]
  (let [failures-dir (str (fs/create-temp-dir))
        state-dir    (str (fs/create-temp-dir))]
    (try
      (with-redefs [failure/failures-dir (constantly failures-dir)
                    state/state-dir      (constantly state-dir)]
        (f failures-dir state-dir))
      (finally (fs/delete-tree failures-dir)
               (fs/delete-tree state-dir)))))

(def ^:private attempt
  {:verb :up :session "feat-x" :project "brian" :instance-id "brian--feat-x"
   :opts {:project "brian" :session-profile :full}
   :worktree-existed? true
   :origin {:kind :run :run-id "2026-09-17-brian-triage-new-abc12345"}})

(deftest a-kept-failure-carries-the-evidence-that-teardown-would-delete
  (with-homes
   (fn [_ state-dir]
    (let [log (str (fs/path state-dir "brian--feat-x" "logs" "repl.log"))]
      (fs/create-dirs (fs/parent log))
      (spit log "starting\nCaused by: java.net.BindException: Address already in use\n")
      (let [t    (ex-info "Timed out waiting for .nrepl-port"
                          {:timeout-ms 30000 :outcome :process-died :pid 42}
                          (java.io.IOException. "port file never appeared"))
            kept (failure/record! attempt t)]
        (fs/delete-tree (str (fs/path state-dir "brian--feat-x")))
        (let [read-back (failure/failure (:id kept))]
          (is (= kept read-back)
              "the record reads back whole after the instance state dir is gone — that is the point of keeping it")
          (is (= ["Timed out waiting for .nrepl-port" "port file never appeared"]
                 (mapv :message (:error read-back)))
              "the whole cause chain is kept: a wrapper's message says where, its cause says what")
          (is (= {:timeout-ms 30000 :outcome :process-died :pid 42} (-> read-back :error first :data)))
          (is (str/includes? (get (:logs read-back) log) "Address already in use")
              "the service log's tail is snapshotted at the moment of failure")
          (is (= (:origin attempt) (:origin read-back))
              "the origin is kept verbatim for the restore to read")))))))

(deftest keeping-a-failure-never-replaces-it
  (with-redefs [failure/failures-dir (constantly "/dev/null/cannot-exist")]
    (is (nil? (binding [*err* (java.io.StringWriter.)]
                (failure/record! attempt (ex-info "boom" {}))))
        "a record that cannot be written answers nil instead of throwing, so the caller rethrows its own failure")))

(deftest unprintable-exception-data-is-kept-as-text
  (with-homes
   (fn [_ _]
    (let [kept (failure/record! attempt (ex-info "boom" {:path (fs/path "/tmp/x")
                                                         :proc (Object.)
                                                         :output (apply str (repeat 10000 "x"))}))
          data (-> (failure/failure (:id kept)) :error first :data)]
      (is (= "/tmp/x" (:path data)) "a Path is kept as its string, so the record still reads as EDN")
      (is (string? (:proc data)))
      (is (< (count (:output data)) 5000) "a program's whole output is capped")))))

(deftest failures-list-oldest-first-and-skip-a-torn-record
  (with-homes
   (fn [failures-dir _]
    (let [a (failure/record! attempt (ex-info "one" {}))
          b (do (Thread/sleep 5) (failure/record! attempt (ex-info "two" {})))]
      (spit (str (fs/path failures-dir "20260101T000000-deadbeef.edn")) "{:id \"torn")
      (is (= [(:id a) (:id b)] (mapv :id (failure/failures)))
          "one unreadable record must not blind recovery to every other failure")))))

(deftest cause-ignores-what-names-one-session
  (let [f (fn [session message]
            {:project "brian" :session session :instance-id (str "brian--" session)
             :error [{:class "clojure.lang.ExceptionInfo" :message message}]})]
    (testing "the same failure in two sessions is one cause"
      (is (= (failure/cause (f "feat-a" "Timed out waiting for PostgreSQL on port 6145 in /Users/me/.nido/state/brian--feat-a"))
             (failure/cause (f "feat-b" "Timed out waiting for PostgreSQL on port 6201 in /Users/me/.nido/state/brian--feat-b")))))
    (testing "a migration checksum mismatch is one cause whatever version it names"
      (is (= (failure/cause (f "a" "Flyway checksum mismatch on migration version 264"))
             (failure/cause (f "b" "Flyway checksum mismatch on migration version 265")))))
    (testing "different errors are different causes"
      (is (not= (failure/cause (f "a" "jj workspace add failed"))
                (failure/cause (f "a" "Could not find free port")))))
    (testing "the same error in another project is another cause"
      (is (not= (failure/cause (f "a" "boom"))
                (failure/cause (assoc (f "a" "boom") :project "nido")))))))

(deftest a-failures-id-carries-its-time
  (with-homes
   (fn [_ _]
    (let [kept (failure/record! attempt (ex-info "boom" {}))]
      (is (= (.toEpochMilli (java.time.Instant/parse (:at kept))) (failure/id-ms (:id kept)))
          "a reader can order and page failures by id without opening them")
      (is (= [(:id kept)] (failure/ids)))
      (is (nil? (failure/id-ms "not-an-id")))))))

(deftest ids-are-listed-without-reading-records
  (with-homes
   (fn [failures-dir _]
    (spit (str (fs/path failures-dir "20260917T100000000-aaaaaaaa.edn")) "{:id \"torn")
    (is (= ["20260917T100000000-aaaaaaaa"] (failure/ids))
        "an unreadable record is still an id; reading it is the caller's choice"))))
