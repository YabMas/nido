(ns nido.coordinator.record.activity-test
  "The claim's correctness is about what OTHER processes see, so the cases that
   matter cannot be tested in one JVM: a same-process caller is answered from the
   holder table and never reaches the lock at all. The cross-process cases shell
   out to babashka — which is also what the production callers are — so these
   exercise the same code path a second `bb nido:review:loop` takes."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [nido.coordinator.record.activity :as activity]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.core :as core]))

(def ^:dynamic *root* nil)

(use-fixtures :each
  (fn [f]
    (let [tmp (str (fs/create-temp-dir {:prefix "nido-activity-test"}))]
      (try
        (with-redefs [core/nido-root (constantly tmp)]
          (binding [*root* tmp] (f)))
        (finally (fs/delete-tree tmp))))))

(def base {:kind :diff-review :run-id "review-abc" :report-path "/tmp/report.json"})

(defn- holder-script
  "A separate process that takes the claim, signals that it has, and holds it
   until told to stop. `ready` and `stop` are files: the parent waits for the
   first to appear and creates the second, so no test sleeps on a guess."
  [root project ws-id ready stop]
  (str "(require '[nido.coordinator.record.activity :as a]"
       "         '[nido.platform.core :as core]"
       "         '[babashka.fs :as fs])"
       "(with-redefs [core/nido-root (constantly \"" root "\")]"
       "  (a/with-claim " project " \"" ws-id "\" "
       "    {:kind :diff-review :run-id \"held-by-child\" :report-path nil}"
       "    (fn [] (spit \"" ready "\" \"1\")"
       "           (loop [] (when-not (fs/exists? \"" stop "\") (Thread/sleep 25) (recur))))))"))

(defn- reader-script
  "A separate process holding a READER's shared lock on the claim byte for a
   moment, then letting go. Written out longhand rather than calling `read-live`
   because that is the point: this is any second process's probe seen from
   outside, and nothing is running against the workstream while it is held."
  [lock ready hold-ms]
  (str "(import '[java.nio.channels FileChannel]"
       "        '[java.nio.file Paths StandardOpenOption])"
       "(let [ch (FileChannel/open (Paths/get \"" lock "\" (into-array String []))"
       "           (into-array StandardOpenOption [StandardOpenOption/CREATE"
       "                                           StandardOpenOption/WRITE"
       "                                           StandardOpenOption/READ]))]"
       "  (.tryLock ch 0 1 true)"
       "  (spit \"" ready "\" \"1\")"
       "  (Thread/sleep " hold-ms ")"
       "  (.close ch))"))

(defn- bb!
  "Run a babashka form against this worktree's classpath, in the background."
  [form]
  (process/process {:dir (System/getProperty "user.dir")
                    :out :string :err :string}
                   "bb" "-e" form))

(defn- await-file! [p timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond (fs/exists? p) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 25) (recur))))))

(deftest paths-are-under-the-workstream
  (is (= (str (fs/path (cstate/workstream-dir :nido "ws-1") "activity.lock"))
         (cstate/activity-lock-path :nido "ws-1")))
  (is (= (str (fs/path (cstate/workstream-dir :nido "ws-1") "activity.edn"))
         (cstate/activity-path :nido "ws-1"))))

(deftest absent-when-nothing-holds-it
  (testing "no file at all reads as absent, and does not create one"
    (is (nil? (activity/read-live :nido "ws-1")))
    (is (false? (activity/held? :nido "ws-1")))
    (is (not (fs/exists? (cstate/activity-lock-path :nido "ws-1"))))))

(deftest payload-is-visible-to-the-holder-without-touching-the-lock
  (testing "a holder reads its own claim from the holder table"
    (activity/with-claim
      :nido "ws-1" base
      (fn []
        (let [live (activity/read-live :nido "ws-1")]
          (is (= :diff-review (:kind live)))
          (is (= "review-abc" (:run-id live)))
          (is (true? (activity/held? :nido "ws-1")))
          (is (= (.pid (java.lang.ProcessHandle/current)) (:pid live))))))))

(deftest released-on-every-exit-path
  (testing "a normal return releases"
    (activity/with-claim :nido "ws-1" base (fn [] :done))
    (is (nil? (activity/read-live :nido "ws-1"))))
  (testing "a throw releases too — the claim must not outlive a crashed round"
    (is (thrown? Exception
                 (activity/with-claim :nido "ws-1" base
                   (fn [] (throw (ex-info "boom" {}))))))
    (is (nil? (activity/read-live :nido "ws-1")))
    (is (false? (activity/held? :nido "ws-1")))))

(deftest nothing-is-ever-deleted
  (testing "release leaves both files in place — deleting a lock is what makes
            the pid design unsound, and nothing here deletes one"
    (activity/with-claim :nido "ws-1" base (fn [] :done))
    (is (fs/exists? (cstate/activity-lock-path :nido "ws-1")))
    (is (fs/exists? (cstate/activity-path :nido "ws-1")))
    (is (str/includes? (slurp (cstate/activity-path :nido "ws-1")) "review-abc"))
    (testing "and the leftover payload does not read as a live claim"
      (is (nil? (activity/read-live :nido "ws-1"))))))

(deftest a-holder-answers-itself-without-opening-the-lock-file
  (testing "POSIX drops a process's locks on a file when it closes ANY descriptor
            to it, so a holder that probed its own claim through the filesystem
            would release it. The holder table is what stops that, and this is
            the test that would catch its removal: the claim must still be held
            by another process's reckoning after the holder has read it."
    (let [ready (str (fs/path *root* "ready4")) stop (str (fs/path *root* "stop4"))
          probe (str (fs/path *root* "probe4"))]
      (activity/with-claim
        :nido "ws-4" base
        (fn []
          ;; The holder reads its own claim repeatedly — each one would be a
          ;; released lock if it went through a channel.
          (dotimes [_ 5] (is (some? (activity/read-live :nido "ws-4"))))
          (spit ready "1")
          (let [child (bb! (str "(require '[nido.coordinator.record.activity :as a]"
                                "         '[nido.platform.core :as core])"
                                "(with-redefs [core/nido-root (constantly \"" *root* "\")]"
                                "  (spit \"" probe "\" (pr-str (a/held? :nido \"ws-4\"))))"))]
            @child
            (is (await-file! probe 30000))
            (is (= "true" (str/trim (slurp probe)))
                "another process must still see the claim as held"))
          (spit stop "1"))))))

(deftest a-second-taker-is-refused-and-told-who-holds-it
  (let [ready (str (fs/path *root* "ready")) stop (str (fs/path *root* "stop"))
        child (bb! (holder-script *root* :nido "ws-1" ready stop))]
    (try
      (is (await-file! ready 30000) "child process took the claim")
      (testing "a second taker does not run its body, and is handed the holder"
        (let [ran   (atom false)
              res   (activity/with-claim :nido "ws-1" base (fn [] (reset! ran true)))
              their (activity/refused res)]
          (is (false? @ran) "the loser's body must not run")
          (is (some? their) "the loser is told who holds it")
          (is (= "held-by-child" (:run-id their)))
          (is (= :diff-review (:kind their)))))
      (testing "and read-live agrees with what the refusal carried"
        (is (= "held-by-child" (:run-id (activity/read-live :nido "ws-1"))))
        (is (true? (activity/held? :nido "ws-1"))))
      (finally (spit stop "1") @child))))

(deftest a-refusal-is-told-apart-from-a-body-that-returned-nothing
  (testing "the payload cannot answer whether a take refused: a claim lost to a
            holder that exits before it is read carries nothing, and a caller
            reading THAT as a run would report a review nothing performed"
    (is (false? (activity/refused? (activity/with-claim :nido "ws-6" base (constantly nil))))
        "a body that ran and returned nil is not a refusal")
    (let [ready (str (fs/path *root* "ready6")) stop (str (fs/path *root* "stop6"))
          child (bb! (holder-script *root* :nido "ws-6" ready stop))]
      (try
        (is (await-file! ready 30000) "child process took the claim")
        (let [res (activity/with-claim :nido "ws-6" base (constantly :ran))]
          (is (true? (activity/refused? res)))
          (testing "and when the holder cannot be read, refused? still says so"
            (with-redefs [activity/read-live (constantly nil)]
              (let [res (activity/with-claim :nido "ws-6" base (constantly :ran))]
                (is (nil? (activity/refused res)))
                (is (true? (activity/refused? res)))))))
        (finally (spit stop "1") @child)))))

(deftest a-dead-holder-reads-as-absent-with-no-cleanup
  (let [ready (str (fs/path *root* "ready2")) stop (str (fs/path *root* "stop2"))
        child (bb! (holder-script *root* :nido "ws-2" ready stop))]
    (is (await-file! ready 30000))
    (is (true? (activity/held? :nido "ws-2")))
    (testing "killing the holder frees the claim without anything cleaning up"
      (process/destroy-tree child)
      @child
      (let [deadline (+ (System/currentTimeMillis) 10000)]
        (loop [] (when (and (activity/held? :nido "ws-2")
                            (< (System/currentTimeMillis) deadline))
                   (Thread/sleep 25) (recur))))
      (is (false? (activity/held? :nido "ws-2"))
          "the OS dropped the lock when the process died")
      (is (nil? (activity/read-live :nido "ws-2"))
          "and its payload is debris, not a live claim")
      (is (fs/exists? (cstate/activity-path :nido "ws-2"))
          "no cleanup step ran — the file is still there"))
    (testing "a fresh taker succeeds against a dead holder's debris"
      (let [ran (atom false)]
        (activity/with-claim :nido "ws-2" base (fn [] (reset! ran true)))
        (is (true? @ran))))))

(deftest concurrent-readers-do-not-read-each-other-as-the-holder
  (testing "the probe is SHARED: two readers at the same instant both see absent.
            An exclusive probe would make each read the other as a holder."
    (let [results (->> (range 8)
                       (mapv (fn [_] (future (activity/read-live :nido "ws-3"))))
                       (mapv deref))]
      (is (every? nil? results)))
    (is (every? false? (mapv deref (mapv (fn [_] (future (activity/held? :nido "ws-3")))
                                         (range 8)))))))

(deftest a-readers-probe-is-not-a-holder
  (testing "a claim's lock is exclusive, so it collides with a READER's shared
            probe as surely as with another claim. Refusing there would report a
            workstream busy that nothing is running against — and worse, the
            reader is gone by the time the refusal is inspected, so it carries no
            claim and reads exactly like a run."
    (let [lock  (cstate/activity-lock-path :nido "ws-5")
          ready (str (fs/path *root* "ready5"))]
      (fs/create-dirs (fs/parent lock))
      (let [child (bb! (reader-script lock ready 60))
            _     (is (await-file! ready 30000) "reader process took a shared lock")
            ran   (atom false)
            res   (activity/with-claim :nido "ws-5" base (fn [] (reset! ran true)))]
        (is (true? @ran) "the claim must be taken, not refused to a reader")
        (is (nil? (activity/refused res)))
        @child))))

(deftest same-jvm-takers-do-not-drop-each-others-lock
  (testing "a winner is not in the holder table until its payload is published,
            and in that window a second taker here sees no local holder and opens
            its OWN descriptor to the lock file — which under POSIX releases the
            winner's claim the moment the loser closes it. The monitor covering
            acquisition and publication is what stops that, and this is the test
            that would catch its removal: while the winner runs, another PROCESS
            must still see the claim as held."
    (let [ready (str (fs/path *root* "ready6")) stop (str (fs/path *root* "stop6"))
          ran   (atom 0)
          takes (mapv (fn [_]
                        (future
                          (activity/with-claim
                            :nido "ws-6" base
                            (fn []
                              (swap! ran inc)
                              (spit ready "1")
                              (loop [] (when-not (fs/exists? stop)
                                         (Thread/sleep 10) (recur)))))))
                      (range 8))]
      (try
        (is (await-file! ready 30000) "one thread took the claim")
        (Thread/sleep 500)                              ; every loser has refused by now
        (let [probe (str (fs/path *root* "probe6"))
              child (bb! (str "(require '[nido.coordinator.record.activity :as a]"
                              "         '[nido.platform.core :as core])"
                              "(with-redefs [core/nido-root (constantly \"" *root* "\")]"
                              "  (spit \"" probe "\" (pr-str (a/held? :nido \"ws-6\"))))"))]
          @child
          (is (await-file! probe 30000))
          (is (= "true" (str/trim (slurp probe)))
              "the losers' channels must not have released the winner's lock"))
        (finally (spit stop "1")))
      (let [results (mapv deref takes)]
        (is (= 1 @ran) "exactly one body runs")
        (is (= 7 (count (keep activity/refused results)))
            "and every loser is handed the holder's claim rather than nil")))))

(deftest rejects-a-payload-outside-the-vocabulary
  (testing "the kind vocabulary is closed at the setter, so a claim nothing can
            render is refused rather than written"
    (is (thrown? clojure.lang.ExceptionInfo
                 (activity/with-claim :nido "ws-1" (assoc base :kind :not-a-kind)
                   (fn [] :never))))
    (is (nil? (activity/read-live :nido "ws-1")))))
