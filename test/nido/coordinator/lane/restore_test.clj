(ns nido.coordinator.lane.restore-test
  "What a restore brings back, what it hands the daemon, and what it records."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.lane.restore :as restore]
   [nido.coordinator.lane.spawn :as spawn]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.triggers :as triggers]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.source.queue :as queue]
   [nido.platform.core :as core]
   [nido.session.failure :as failure]
   [nido.session.lifecycle :as lifecycle]))

(defn- with-tmp
  "Call `f` with the ledger, the runs and the kept failures under a temp dir, the
   recovery trigger declared, and envelopes captured instead of queued."
  [f]
  (let [tmp      (fs/create-temp-dir)
        enqueued (atom [])]
    (try
      (with-redefs [core/nido-root          (constantly (str tmp))
                    failure/failures-dir    (constantly (str (fs/path tmp "failures")))
                    queue/enqueue!          (fn [env] (swap! enqueued conj env) "queued")
                    triggers/load-for-project
                    (fn [p] (when (= :nido p)
                              [{:name :session-recovery :source {:type :session-failure}
                                :skill :recover-session :payload "x" :limits {:budget "1h"}}]))]
        (cstate/ensure-dirs!)
        (f enqueued))
      (finally (fs/delete-tree tmp)))))

(defn- keep-failure! [attempt message]
  (binding [*err* (java.io.StringWriter.)]
    (failure/record! (merge {:verb :up :session "feat" :project "brian"
                             :instance-id "brian--feat" :opts {:project "brian"}
                             :origin {:kind :person}}
                            attempt)
                     (ex-info message {}))))

(defn- recovery-ws!
  "A recovery workstream for `f`'s cause, as the source's fire would make it."
  [f & {:keys [diagnosed?] :or {diagnosed? true}}]
  (let [w (ws/create! :nido {:stage :triaging
                             :external-refs [{:adapter :session-recovery
                                              :id (str (failure/cause f) "@" (:id f))}]})]
    (when diagnosed?
      (ws/append-entry! :nido (:id w) {:kind :session-diagnosis}
                        (pr-str {:format :session-diagnosis :failures [(:id f)] :verdict :one-off
                                 :cause "a stale pid" :evidence ["repl.log: pid 7 not running"]
                                 :remedy "cleared the pid file"})))
    (:id w)))

(deftest a-restore-refuses-an-undiagnosed-failure
  (with-tmp
    (fn [_]
      (let [f (keep-failure! {} "Timed out waiting for .nrepl-port")
            w (recovery-ws! f :diagnosed? false)]
        (is (= :undiagnosed
               (try (restore/restore! :nido w) nil
                    (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))))))

(deftest a-persons-session-comes-back-and-the-recovery-closes
  (with-tmp
    (fn [enqueued]
      (let [f      (keep-failure! {} "Timed out waiting for .nrepl-port")
            w      (recovery-ws! f)
            ups    (atom [])]
        (with-redefs [lifecycle/up! (fn [name opts] (swap! ups conj [name opts]) :up)]
          (let [{:keys [outcomes closed?]} (restore/restore! :nido w)]
            (is (= [{:failure (:id f) :outcome :restored :continuation {:kind :none}}] outcomes))
            (is closed? "every failure named on it came back")))
        (is (= "feat" (ffirst @ups)))
        (is (= {:kind :person :restoring (:id f)} (:origin (second (first @ups))))
            "a restore's own start says what it is restoring, so its failure is traceable")
        (is (empty? @enqueued) "a person's start continues nothing")
        (is (some? (:closed (ws/read-ws :nido w))))
        (is (= :restored (-> (ws/latest-entry :nido w :session-restored) :outcomes first :outcome)))))))

(deftest a-start-that-fails-again-is-not-restored-and-the-recovery-stays-open
  (with-tmp
    (fn [_]
      (let [f (keep-failure! {} "Timed out waiting for .nrepl-port")
            w (recovery-ws! f)]
        (with-redefs [lifecycle/up! (fn [& _] (throw (ex-info "Timed out waiting for .nrepl-port"
                                                              {:session-failure/id "F-again"})))]
          (let [{:keys [outcomes closed?]} (restore/restore! :nido w)]
            (is (= :not-restored (:outcome (first outcomes))))
            (is (= "F-again" (:failure-left (first outcomes)))
                "the outcome names the failure its own attempt kept")
            (is (not closed?))
            (is (nil? (:closed (ws/read-ws :nido w)))
                "closing now would settle a failure nobody brought back")))))))

(deftest a-failed-run-continues-as-its-successor-with-its-session-already-up
  (with-tmp
    (fn [enqueued]
      (let [origin-ws (ws/create! :brian {:stage :triaging :external-refs []})
            trigger   {:name :triage-new :source {:type :notion-view} :skill :triage-bug
                       :payload "{{event/id}}" :limits {:budget "1h"}}
            failed    (runs/create-run! {:project :brian :trigger trigger :payload {:id "BR-1"}
                                         :priority 3 :session-profile :full
                                         :workstream-id (:id origin-ws)}
                                        {:fired-at "t" :fired-by "test"})
            _         (runs/transition! (:id failed) :failed)
            f         (keep-failure! {:origin (runs/run-origin (runs/read-run (:id failed)))}
                                     "Advancing shared cluster failed")
            w         (recovery-ws! f)
            spawned   (atom [])]
        (with-redefs [triggers/load-for-project (fn [p] (if (= :brian p) [trigger] []))
                      spawn/create-session-for-run! (fn [& _] nil)
                      runs/spawn-session-for-run! (fn [run origin] (swap! spawned conj [(:id run) origin]))]
          (let [{:keys [outcomes]} (restore/restore! :nido w)
                {:keys [continuation]} (first outcomes)
                next-id (:run-id continuation)]
            (is (= :successor (:kind continuation)))
            (is (= (:id failed) (:of continuation)))
            (is (= :failed (:state (runs/read-run (:id failed)))) ":failed stays failed")
            (is (= (:id origin-ws) (:workstream-id (runs/read-run next-id)))
                "the successor carries on the failed Run's workstream")
            (is (= {:id "BR-1"} (:event-payload (runs/read-run next-id))))
            (is (= [next-id] (mapv first @spawned))
                "its session is started here, before the daemon hears of it")
            (is (= [{:type :execute-run :run-id next-id}] @enqueued))))))))

(deftest a-failure-whose-work-was-closed-is-withdrawn
  (with-tmp
    (fn [enqueued]
      (let [origin-ws (ws/create! :brian {:stage :triaging :external-refs []})
            f         (keep-failure! {:origin {:kind :resume :project :brian :ws-id (:id origin-ws)
                                               :session "impl" :input "apply it"}}
                                     "Re-hydration failed")
            _         (ws/close! :brian (:id origin-ws) :dismissed)
            w         (recovery-ws! f)]
        (with-redefs [lifecycle/up! (fn [& _] (throw (Exception. "must not start")))]
          (let [{:keys [outcomes closed?]} (restore/restore! :nido w)]
            (is (= :withdrawn (:outcome (first outcomes))))
            (is closed?)
            (is (empty? @enqueued))))))))

(deftest continuation-reads-the-origin
  (let [f {:at "2026-09-17T10:00:00Z"}]
    (testing "a Run that parked on its failed start is executed again as itself"
      (is (= {:kind :execute :run-id "R"}
             (restore/continuation (assoc f :origin {:kind :run :run-id "R"})
                                   {:run {:id "R" :state :awaiting-review} :ws {}}))))
    (testing "a session that is merely gone is brought back, not withdrawn"
      (is (= :successor
             (:kind (restore/continuation (assoc f :origin {:kind :run :run-id "R"})
                                          {:run {:id "R" :state :failed} :ws {:closed nil}})))))
    (testing "a workstream closed BEFORE the failure withdraws nothing"
      (is (= :resume-turn
             (:kind (restore/continuation (assoc f :origin {:kind :resume :ws-id "w" :input "x"})
                                          {:ws {:closed {:at "2026-09-17T09:00:00Z"}}})))))))
