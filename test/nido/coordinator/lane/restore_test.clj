(ns nido.coordinator.lane.restore-test
  "What a restore brings back, and what it records on its recovery run."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.lane.restore :as restore]
   [nido.coordinator.record.records :as records]
   [nido.coordinator.record.session-events :as session-events]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.triggers :as triggers]
   [nido.coordinator.source.queue :as queue]
   [nido.coordinator.source.start-failures :as start-failures]
   [nido.core.client :as client]
   [nido.platform.core :as core]
   [nido.session.failure :as failure]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as sstate]
   [nido.test.runs :as test-runs]))

(defn- with-tmp
  "Call `f` with the ledger, the runs and the kept failures under a temp dir, the
   recovery trigger declared, and envelopes captured instead of queued."
  [f]
  (let [tmp      (fs/create-temp-dir)
        enqueued (atom [])]
    (try
      (with-redefs [core/nido-root          (constantly (str tmp))
                    sstate/sessions-root    (constantly (str (fs/path tmp "sessions")))
                    failure/failures-dir    (constantly (str (fs/path tmp "failures")))
                    queue/enqueue!          (fn [env] (swap! enqueued conj env) "queued")
                    triggers/load-for-project
                    (fn [p] (when (= :nido p)
                              [{:name :session-recovery :source {:type :session-failure}
                                :skill :recover-session :payload "x" :limits {:budget "1h"}}]))]
        (cstate/ensure-dirs!)
        (f enqueued))
      (finally (fs/delete-tree tmp)))))

(defn- typed!
  "A person typed a prompt in brian's session `n`, in its home, now."
  [n]
  (let [home (str (fs/path (sstate/sessions-root) "brian" n))]
    (fs/create-dirs home)
    (spit (str (fs/path home "CLAUDE.md")) "briefing")
    (session-events/append! (str "conv-" n) {:event :prompt :cwd home :prompt "bring it up"})))

(defn- keep-failure! [attempt message]
  (binding [*err* (java.io.StringWriter.)]
    (failure/record! (merge {:verb :up :session "feat" :project "brian"
                             :instance-id "brian--feat" :opts {:project "brian"}
                             :origin {:kind :person}}
                            attempt)
                     (ex-info message {}))))

(defn- recovery-run!
  "A recovery run for `f`'s cause, running, as the source's fire would start it; diagnosed unless
   told otherwise. Answers its core key."
  [f & {:keys [diagnosed?] :or {diagnosed? true}}]
  (let [cause (failure/cause f)
        id    (test-runs/run! :nido :session-recovery
                              {:adapter :session-recovery :id (str cause "@" (:id f))
                               :cause cause :failures (:id f)}
                              :running)
        k     (client/run-key :nido id)]
    (when diagnosed?
      (records/append-to-run! k {:kind :session-diagnosis :failures [(:id f)] :verdict :one-off
                                 :cause "a stale pid" :evidence ["repl.log: pid 7 not running"]
                                 :remedy "cleared the pid file"}))
    k))

(defn- restored-on [k]
  (some #(when (= :session-restored (:kind %)) %) (records/of k)))

(deftest a-restore-refuses-an-undiagnosed-failure
  (with-tmp
    (fn [_]
      (let [f (keep-failure! {} "Timed out waiting for .nrepl-port")
            w (recovery-run! f :diagnosed? false)]
        (is (= :undiagnosed
               (try (restore/restore! :nido w) nil
                    (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))))))

(deftest a-persons-session-comes-back-and-the-run-records-it
  (with-tmp
    (fn [enqueued]
      (typed! "feat")
      (let [f      (keep-failure! {} "Timed out waiting for .nrepl-port")
            w      (recovery-run! f)
            ups    (atom [])]
        (with-redefs [lifecycle/up! (fn [name opts] (swap! ups conj [name opts]) :up)]
          (let [{:keys [outcomes settled?]} (restore/restore! :nido w)]
            (is (= [{:failure (:id f) :outcome :restored :continuation {:kind :none}}] outcomes))
            (is settled? "nothing of the cause is owed any more")))
        (is (= "feat" (ffirst @ups)))
        (is (= {:kind :person :restoring (:id f)} (:origin (second (first @ups))))
            "a restore's own start says what it is restoring, so its failure is traceable")
        (is (empty? @enqueued) "a person's start continues nothing")
        (is (= :restored (-> (restored-on w) :outcomes first :outcome))
            "what became of it is recorded on the run")))))

(deftest a-start-nobody-typed-into-is-withdrawn-not-restored
  ;; 2026-11-04: a shared-PG outage failed the starts conductors' agents had made — kept with a
  ;; person's origin — and the recovery brought all nine back up, on workstreams nobody was in.
  (with-tmp
    (fn [enqueued]
      (typed! "other")
      (let [f   (keep-failure! {} "reading shared flyway history failed: FATAL: the database system is shutting down")
            w   (recovery-run! f)
            ups (atom 0)]
        (with-redefs [lifecycle/up! (fn [& _] (swap! ups inc))]
          (let [{:keys [outcomes settled?]} (restore/restore! :nido w)]
            (is (= [{:failure (:id f) :outcome :withdrawn :why :no-person}] outcomes)
                "a prompt in another session is no one at work in this one")
            (is settled? "nothing is left owed")))
        (is (zero? @ups) "nothing is brought up")
        (is (empty? @enqueued))))))

(deftest a-start-whose-prompt-log-cannot-be-read-stays-owed
  ;; An unreadable log says nothing about who typed: withdrawing on it would close a person's
  ;; recovery on a disk error, and throwing would stop every other failure's restore.
  (with-tmp
    (fn [enqueued]
      (let [f   (keep-failure! {} "Timed out waiting for .nrepl-port")
            w   (recovery-run! f)
            ups (atom 0)]
        (with-redefs [lifecycle/up!                (fn [& _] (swap! ups inc))
                      session-events/prompts-since (fn [_] (throw (ex-info "disk gone" {})))]
          (let [{:keys [outcomes settled?]} (restore/restore! :nido w)]
            (is (= [:not-restored] (map :outcome outcomes)))
            (is (nil? (:why (first outcomes))) "not withdrawn as nobody's")
            (is (not settled?) "the failure stays owed")))
        (is (zero? @ups))
        (is (empty? @enqueued))))))

(deftest a-start-that-fails-again-is-not-restored-and-stays-owed
  (with-tmp
    (fn [_]
      (typed! "feat")
      (let [f (keep-failure! {} "Timed out waiting for .nrepl-port")
            w (recovery-run! f)]
        (with-redefs [lifecycle/up! (fn [& _] (throw (ex-info "Timed out waiting for .nrepl-port"
                                                              {:session-failure/id "F-again"})))]
          (let [{:keys [outcomes settled?]} (restore/restore! :nido w)]
            (is (= :not-restored (:outcome (first outcomes))))
            (is (= "F-again" (:failure-left (first outcomes)))
                "the outcome names the failure its own attempt kept")
            (is (not settled?) "a failure nobody brought back is still owed")))))))

(deftest a-restore-refuses-a-run-that-is-not-a-recovery
  (with-tmp
    (fn [_]
      (let [k (client/run-key :nido (test-runs/run! :nido :review-analysis {:id "rr"} :running))]
        (is (= :not-a-recovery
               (try (restore/restore! :nido k) nil
                    (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))))))

(deftest a-start-made-for-a-run-or-a-reply-is-withdrawn
  ;; The core runs every turn: a turn whose session did not come up ended failed there, and intake
  ;; drops an envelope that would continue it, so there is nothing a restore could hand on.
  (with-tmp
    (fn [enqueued]
      (doseq [origin [{:kind :run :run-id "R"} {:kind :resume :project :brian :ws-id "w" :input "x"}]]
        (is (= {:kind :withdrawn} (restore/continuation {:origin origin} {:typed? true}))))
      (let [f (keep-failure! {:origin {:kind :run :run-id "R"}} "Timed out waiting for .nrepl-port")
            w (recovery-run! f)]
        (with-redefs [lifecycle/up! (fn [& _] (throw (Exception. "must not start")))]
          (let [{:keys [outcomes settled?]} (restore/restore! :nido w)]
            (is (= [:withdrawn] (map :outcome outcomes)))
            (is settled?)
            (is (empty? @enqueued) "nothing is handed to the daemon")))))))

(deftest a-start-under-a-name-up-refuses-is-withdrawn-not-retried
  (with-tmp
    (fn [enqueued]
      (let [f   (keep-failure! {:session "teacher assistant contextual awerness"}
                               "Error: Failed to parse name pattern: Syntax error")
            w   (recovery-run! f)
            ups (atom 0)]
        (with-redefs [lifecycle/up! (fn [& _] (swap! ups inc))]
          (let [{:keys [outcomes settled?]} (restore/restore! :nido w)]
            (is (= [{:failure (:id f) :outcome :withdrawn}] outcomes))
            (is settled? "nothing is left owed")))
        (is (zero? @ups) "restoring it could only be refused again")
        (is (empty? @enqueued))))))

(deftest a-persons-session-comes-back-on-the-workstream-holding-it
  (with-tmp
    (fn [_]
      (typed! "feat")
      (let [f   (keep-failure! {} "Timed out waiting for .nrepl-port")
            w   (recovery-run! f)
            ups (atom [])]
        (with-redefs [lifecycle/up! (fn [name opts] (swap! ups conj [name opts]) :up)
                      nido.coordinator.record.session/workstream-id-for
                      (fn [p s] (when (= [:brian "feat"] [p s]) "ws-9"))]
          (restore/restore! :nido w))
        (is (= "ws-9" (:runtime-key (second (first @ups))))
            "a failure kept with no key comes back on its workstream's runtime, not one of its own")))))

;; ── dismissing a recovery that left its cause owed ──────────────────────────

(deftest a-person-dismisses-a-parked-recovery-and-what-it-named-is-settled
  (with-tmp
    (fn [_]
      (let [cause  (str "cause-" (random-uuid))
            start  (fn [failures status]
                     (test-runs/run! :nido :session-recovery
                                     {:adapter :session-recovery :id (str cause "@F1")
                                      :cause cause :failures failures} status))
            parked (start "F1,F2" :running)
            live   (start "F3" :running)
            by-id  (fn [] (into {} (map (juxt :ws-id identity)) (start-failures/recoveries :nido)))
            reason (fn [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))]
        (records/append-to-run! (client/run-key :nido parked)
                                {:kind :blocker :summary "s" :needs "may I reset the cluster?" :after "a"})
        (client/command! (client/run-key :nido parked) (str "end/" parked)
                         {:command :record-outcome :turn (str parked " first") :outcome :completed})
        (is (= :awaiting-review (-> ((by-id) parked) :runs peek :state)) "parked, it stopped for a person")
        (is (= :in-flight (reason #(restore/dismiss! :nido live nil))) "a running recovery is not dismissed")
        (is (= :no-such-recovery (reason #(restore/dismiss! :nido "no-such-run" nil))))
        (is (= {:recovery parked :settles ["F1" "F2"]} (restore/dismiss! :nido parked "handled by hand")))
        (let [r ((by-id) parked)]
          (is (= #{"F1" "F2"} (:settled r)) "dismissed, it settles everything it named")
          (is (= :dismissed (-> r :closed :outcome)))
          (is (= :done (-> r :runs peek :state)) "and no longer paces its cause"))
        (is (= :settled (reason #(restore/dismiss! :nido parked nil))) "a second dismissal records nothing")
        (is (= [{:kind :recovery-dismissed :recovery parked :by :person :note "handled by hand"}]
               (mapv #(select-keys % [:kind :recovery :by :note])
                     (records/of (records/journal :nido start-failures/journal-name)))))))))
