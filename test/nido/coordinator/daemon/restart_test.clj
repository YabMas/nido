(ns nido.coordinator.daemon.restart-test
  "The latest landing: one parsing boundary, a debt the daemon settles only if it is still the one
   it read, and one reading of what an owed restart waits for."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.daemon.launchctl :as lc]
   [nido.coordinator.daemon.restart :as restart]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.core :as core]
   [nido.platform.process :as process]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(deftest landing-keeps-none-apart-from-unreadable
  (with-tmp
    (fn []
      (is (= {:none true} (restart/landing)))
      (spit (cstate/restart-owed-path) "{:state :owed")
      (is (:unreadable (restart/landing)) "a file that does not parse is unreadable, never none")
      (spit (cstate/restart-owed-path) (pr-str {:state :owed :commit "c"}))
      (is (:unreadable (restart/landing)) "a map that is not a landing is unreadable"))))

(deftest owe!-replaces-whatever-was-recorded
  (with-tmp
    (fn []
      (let [{first-owed :landing} (restart/owe! "aaa" 11)]
        (is (= {:state :owed :commit "aaa" :by-pid 11} (dissoc first-owed :owed-at)))
        (restart/running! first-owed 99)
        (restart/owe! "bbb" 12)
        (is (= "bbb" (-> (restart/landing) :landing :commit)))
        (is (= :owed (-> (restart/landing) :landing :state)))))))

(deftest running!-settles-only-the-landing-it-was-given
  (with-tmp
    (fn []
      (let [{owed :landing} (restart/owe! "aaa" 11)]
        (testing "the same owed landing is recorded running, with the daemon's pid"
          (let [{:keys [landing]} (restart/running! owed 77)]
            (is (= :running (:state landing)))
            (is (= 77 (:pid landing)))
            (is (= landing (:landing (restart/landing))))))
        (testing "a landing owed meanwhile is never overwritten"
          (let [{newer :landing} (restart/owe! "bbb" 12)]
            (is (= {:moved true} (restart/running! owed 78)))
            (is (= newer (:landing (restart/landing))))))))))

(defn- waits-with [{:keys [busy owner-alive? holds? loaded? daemon-start]
                    :or {loaded? true holds? true}}]
  (with-redefs [runs/list-run-ids        (constantly (vec (keys busy)))
                runs/live?               (fn [id] (= :live (get busy id)))
                runs/read-run            (fn [id] {:state (if (= :record (get busy id)) :running :done)})
                process/process-alive?   (fn [pid] (if (= 4242 pid) (boolean owner-alive?) (some? daemon-start)))
                lc/installed?            (constantly true)
                lc/plist-path            (constantly "/nonexistent")
                lc/loaded?               (constantly loaded?)
                restart/checkout         (constantly "/root")
                restart/holds?           (fn [_ _] holds?)
                restart/daemon-pid       (constantly (when daemon-start 5151))
                restart/started-at       (fn [_] daemon-start)]
    (restart/waits {:state :owed :commit "abc" :owed-at "2026-11-17T12:00:00Z" :by-pid 4242})))

(deftest waits-names-everything-that-holds-the-restart
  (is (= [] (waits-with {})) "nothing holds it: the daemon restarts now")
  (is (str/includes? (first (waits-with {:busy {"r1" :live}})) "r1") "an agent alive under a run")
  (is (str/includes? (first (waits-with {:busy {"r2" :record}})) "r2") "a run whose record says running")
  (is (str/includes? (first (waits-with {:owner-alive? true})) "landing that owed it"))
  (is (str/includes? (first (waits-with {:holds? false})) "to hold abc"))
  (is (str/includes? (first (waits-with {:loaded? false})) "launchd"))
  (testing "a daemon that started after the debt settles it rather than pays it"
    (is (str/includes? (first (waits-with {:daemon-start (java.time.Instant/parse "2026-11-17T12:05:00Z")}))
                       "settles it"))
    (is (= [] (waits-with {:daemon-start (java.time.Instant/parse "2026-11-17T11:00:00Z")}))
        "one that started before it pays it")))
