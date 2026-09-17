(ns nido.session.lifecycle-failure-test
  "A lifecycle start that throws is kept, and what the caller catches is still
   the failure it would have caught — plus the id of the record."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.session.engine :as engine]
   [nido.session.failure :as failure]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.profiles :as profiles]
   [nido.session.state :as state]))

(defn- caught [f]
  (try (f) nil (catch Exception e e)))

(defn- with-session
  "Call `f` with a worktree that exists and every collaborator a start reaches
   stubbed, `start` standing in for the engine and each kept attempt appended to
   the returned atom."
  [start f]
  (let [tmp  (fs/create-temp-dir)
        wt   (str (fs/create-dirs (fs/path tmp "feat")))
        kept (atom [])]
    (try
      (with-redefs [lifecycle/resolve-project                 (constantly ["brian" {:directory (str tmp)}])
                    lifecycle/worktree-path                   (constantly wt)
                    nido.session.lifecycle/jj-worktree-poisoned? (constantly false)
                    nido.session.lifecycle/effective-pg-mode  (constantly :isolated)
                    engine/resolve-instance-id                (constantly "brian--feat")
                    engine/stop-session!                      (constantly nil)
                    engine/start-session!                     start
                    profiles/resolve-profile                  (constantly {:worktree {:strategy :jj}})
                    state/write-pg-mode-override!             (constantly nil)
                    state/clear-pg-mode-override!             (constantly nil)
                    state/pg-data-dir                         (constantly (str (fs/path tmp "no-pgdata")))
                    failure/record!                           (fn [attempt t]
                                                                (swap! kept conj [attempt t])
                                                                {:id (str "F" (count @kept))})]
        (f kept))
      (finally (fs/delete-tree tmp)))))

(def ^:private timed-out
  (fn [& _] (throw (ex-info "Timed out waiting for .nrepl-port" {:pid 7 :outcome :timeout}))))

(deftest every-starting-verb-keeps-its-failure
  (doseq [[verb call] {:up      #(lifecycle/up! "feat" %)
                       :restart #(lifecycle/restart! "feat" %)
                       :reset   #(lifecycle/reset! "feat" %)
                       :isolate #(lifecycle/isolate! "feat" %)
                       :share   #(lifecycle/share! "feat" %)}]
    (testing (name verb)
      (with-session timed-out
        (fn [kept]
          (with-redefs [lifecycle/down! (constantly nil)]
            (let [e (caught #(call {:project "brian" :origin {:kind :run :run-id "R1"}}))]
              (is (= 1 (count @kept)) "exactly one record for one failed start")
              (is (= "Timed out waiting for .nrepl-port" (ex-message e))
                  "the caller still reads the message it always read")
              (is (= {:pid 7 :outcome :timeout :session-failure/id "F1"} (ex-data e))
                  "the original ex-data survives, with the record's id added")
              (let [[attempt] (first @kept)]
                (is (= verb (:verb attempt)))
                (is (= {:kind :run :run-id "R1"} (:origin attempt))
                    "the caller's origin is what a restore will read")
                (is (= "brian--feat" (:instance-id attempt)))))))))))

(deftest a-start-with-no-origin-is-a-persons
  (with-session timed-out
    (fn [kept]
      (caught #(lifecycle/up! "feat" {:project "brian"}))
      (is (= {:kind :person} (:origin (ffirst @kept)))))))

(deftest a-refusal-before-the-start-keeps-nothing
  (with-session timed-out
    (fn [kept]
      (with-redefs [nido.session.lifecycle/effective-pg-mode (constantly :shared)]
        (is (some? (caught #(lifecycle/reset! "feat" {:project "brian"}))))
        (is (empty? @kept)
            "a reset refused on the shared cluster is the caller told no, not a session that failed to come up")))))

(deftest a-failure-an-inner-start-kept-is-not-kept-twice
  (with-session (fn [& _] (throw (ex-info "inner" {:session-failure/id "F0"})))
    (fn [kept]
      (let [e (caught #(lifecycle/up! "feat" {:project "brian"}))]
        (is (empty? @kept))
        (is (= "F0" (:session-failure/id (ex-data e))))))))

(deftest an-unkept-failure-is-rethrown-as-it-was
  (with-session (fn [& _] (throw (java.io.IOException. "disk full")))
    (fn [_]
      (with-redefs [failure/record! (constantly nil)]
        (let [e (caught #(lifecycle/up! "feat" {:project "brian"}))]
          (is (instance? java.io.IOException e)
              "when the record could not be written the caller gets its own exception, not a wrapper"))))))

(deftest a-start-that-returns-keeps-nothing
  (with-session (constantly :started)
    (fn [kept]
      (is (= :started (lifecycle/up! "feat" {:project "brian"})))
      (is (empty? @kept)))))
