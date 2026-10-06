(ns nido.session.lifecycle-lock-test
  "Lifecycle verbs on one session never overlap. Every start rolls back what it
   started when it fails, and two overlapping verbs share one PGDATA and one
   local.edn. So a failing verb would stop Postgres and delete local.edn under
   the verb it overlapped."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.lock :as lock]
   [nido.session.engine :as engine]
   [nido.session.failure :as failure]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.profiles :as profiles]
   [nido.session.state :as state]))

(def ^:private lock-name "session-brian--feat")

(defn- with-session
  "Call `f` with a worktree that exists, the locks under a temp dir, and every
   collaborator a verb reaches stubbed. `start` and `stop` stand in for the
   engine. Kept failures are appended to the atom passed to `f`."
  [{:keys [start stop] :or {stop (constantly nil)}} f]
  (let [tmp  (fs/create-temp-dir)
        wt   (str (fs/create-dirs (fs/path tmp "feat")))
        kept (atom [])]
    (try
      (with-redefs [lock/locks-dir                              (constantly (fs/path tmp "locks"))
                    lifecycle/resolve-project                   (constantly ["brian" {:directory (str tmp)}])
                    lifecycle/worktree-path                     (constantly wt)
                    nido.session.lifecycle/jj-worktree-poisoned? (constantly false)
                    nido.session.lifecycle/effective-pg-mode    (constantly :isolated)
                    engine/resolve-instance-id                  (constantly "brian--feat")
                    engine/stop-session!                        stop
                    engine/start-session!                       start
                    profiles/resolve-profile                    (constantly {:worktree {:strategy :jj}})
                    state/pg-data-dir                           (constantly (str (fs/path tmp "no-pgdata")))
                    failure/record!                             (fn [attempt _]
                                                                  (swap! kept conj attempt)
                                                                  {:id (str "F" (count @kept))})]
        (f kept))
      (finally (fs/delete-tree tmp)))))

(deftest overlapping-verbs-in-one-process-run-one-after-the-other
  (let [inside (atom 0)
        most   (atom 0)
        start  (fn [& _]
                 (swap! most max (swap! inside inc))
                 (Thread/sleep 150)
                 (swap! inside dec)
                 nil)]
    (with-session {:start start}
      (fn [_]
        (let [a (future (lifecycle/up! "feat" {:project "brian"}))
              b (future (lifecycle/reset! "feat" {:project "brian"}))]
          @a @b
          (is (= 1 @most)
              "the reset must not start services while the up is still starting them")
          (is (nil? (lock/holder lock-name)) "the lock is released after both"))))))

(deftest a-verb-another-process-holds-is-waited-for-then-refused
  (let [other (p/process ["sleep" "30"])]
    (try
      (with-session {:start (fn [& _] (throw (ex-info "must not start" {})))}
        (fn [kept]
          (fs/create-dirs (lock/locks-dir))
          (spit (str (fs/path (lock/locks-dir) (str lock-name ".lock")))
                (pr-str {:pid (.pid (:proc other)) :label "reset brian--feat"}))
          (with-redefs [nido.session.lifecycle/session-wait-ms 50]
            (let [e (try (lifecycle/up! "feat" {:project "brian"}) nil
                         (catch Exception e e))]
              (is (re-find #"Could not acquire lock" (str (ex-message e))))
              (is (empty? @kept)
                  "being refused the lock is the caller told no, not a failed start")))))
      (finally (p/destroy other)))))

(deftest a-nested-verb-keeps-the-outer-hold
  (testing "restart! composes down!; the file lock must still be held when the start runs"
    (let [held-at-start (atom nil)]
      (with-session {:start (fn [& _] (reset! held-at-start (lock/holder lock-name)) nil)}
        (fn [_]
          (lifecycle/restart! "feat" {:project "brian"})
          (is (some? @held-at-start))
          (is (nil? (lock/holder lock-name))))))))
