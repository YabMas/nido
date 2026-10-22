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
   [nido.session.resume-shim]
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

(deftest a-sync-and-a-departure-run-one-after-the-other
  ;; A sync reads the runtime's members and refreshes its checkout; a departure between the two
  ;; would have the sync put the departed member's files back.
  (let [inside (atom 0)
        most   (atom 0)
        busy   (fn [& _]
                 (swap! most max (swap! inside inc))
                 (Thread/sleep 150)
                 (swap! inside dec)
                 nil)]
    (with-session {:start (constantly nil) :stop busy}
      (fn [_]
        (with-redefs [engine/sync-runtime! busy]
          (let [a (future (lifecycle/sync! (str (lifecycle/worktree-path "brian" "." "feat"))))
                b (future (lifecycle/down! "feat" {:project "brian"}))]
            @a @b
            (is (= 1 @most) "the sync must not refresh while the departure is under way")
            (is (nil? (lock/holder lock-name)) "the lock is released after both")))))))

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

(deftest a-restart-keeps-what-the-stop-removes
  (testing "the run link and profile are read before the stop and handed to the start"
    (let [stopped? (atom false)
          started  (atom nil)
          lite     {:services [:process] :worktree {:strategy :jj}}]
      (with-session {:stop  (fn [& _] (reset! stopped? true))
                     :start (fn [_ opts] (reset! started opts) nil)}
        (fn [_]
          (with-redefs [nido.session.resume-shim/run-dir (fn [_] (when-not @stopped? "/runs/r1"))
                        engine/read-profile-for-session  (fn [_ _] (when-not @stopped? lite))]
            (lifecycle/restart! "feat" {:project "brian" :runtime-key "ws-1"}))
          (is (= "/runs/r1" (:run-dir @started)) "the resume shim's run is written back")
          (is (= lite (:profile @started)) "a lite session does not come back with every service"))))))

(deftest two-starts-of-one-stopped-session-under-different-keys-run-one-at-a-time
  (let [inside (atom 0)
        most   (atom 0)
        start  (fn [& _]
                 (swap! most max (swap! inside inc))
                 (Thread/sleep 150)
                 (swap! inside dec)
                 nil)]
    (with-session {:start start}
      (fn [_]
        (with-redefs [engine/resolve-instance-id (constantly nil)]
          (let [a (future (lifecycle/up! "feat" {:project "brian" :runtime-key "ws-1"}))
                b (future (lifecycle/up! "feat" {:project "brian" :runtime-key "ws-2"}))]
            @a @b
            (is (= 1 @most) "the session's own lock holds whatever runtime each start names")))))))

(deftest opposite-moves-between-two-runtimes-do-not-deadlock
  (let [done (atom [])]
    (with-session {:start (fn [& _] (Thread/sleep 150) nil)}
      (fn [_]
        (let [tmp (str (fs/create-temp-dir))]
          (with-redefs [lifecycle/worktree-path      (fn [_ _ n] (str (fs/create-dirs (fs/path tmp n))))
                        engine/resolve-instance-id   (fn [wt] (if (= "a" (str (fs/file-name wt)))
                                                                "brian--r1" "brian--r2"))
                        nido.session.lifecycle/session-wait-ms 3000]
            (let [a (future (try (lifecycle/restart! "a" {:project "brian" :runtime-key "r2"})
                                 (swap! done conj :a) (catch Exception e (swap! done conj e))))
                  b (future (try (lifecycle/restart! "b" {:project "brian" :runtime-key "r1"})
                                 (swap! done conj :b) (catch Exception e (swap! done conj e))))]
              @a @b
              (is (= #{:a :b} (set @done)) "both moves finish; neither waits out the other"))))))))

(deftest runtimes-whose-ids-fold-alike-keep-distinct-locks
  ;; A lock's file name folds `~` to `_`: the runtime keyed `a/b` and the one keyed `a_b` must
  ;; not share one, or a verb holding both would drop the outer hold when the inner releases.
  (let [lock-name @#'lifecycle/lock-name
        lock-file (comp str @#'lock/lock-path lock-name)]
    (is (not= (lock-file "p--a~b") (lock-file "p--a_b")))
    (let [digest (->> (.digest (java.security.MessageDigest/getInstance "SHA-256")
                               (.getBytes "p--a~b" "UTF-8"))
                      (map #(format "%02x" (bit-and % 0xff)))
                      (apply str))
          ids    ["p--a~b" "p--a_b" "p--a_7Eb" (str "p--a_b-" (subs digest 0 12))]]
      (is (= (count ids) (count (set (map lock-file ids))))
          "no two ids share a lock file, an id shaped like another's escape included"))
    (is (= "session-brian--feat" (lock-name "brian--feat")) "a plain id is its own name")))
