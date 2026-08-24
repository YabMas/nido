(ns tasks.nido-review-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]
   [nido.coordinator.session :as csession]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.review.loop :as rloop]
   [nido.session.lifecycle :as lifecycle]
   [tasks.nido-review :as t]))

(defn- with-tmp-nido-root
  "Every `loop-cmd` test drives the REAL command, so every side effect it has
   lands on the real filesystem unless the root is moved. That is not a
   hypothetical: `loop-cmd*` ends by enqueuing the run for analysis, and without
   this the suite wrote live envelopes into ~/.nido/coordinator/queue/ that the
   running daemon drained and spawned agent sessions for — one per test, every
   time anyone ran `bb nido:test`.

   Redirecting the root is the fix rather than stubbing the one function that
   bit, because the hazard is structural: a command test that writes wherever
   the command writes will bite again the next time `loop-cmd*` grows a side
   effect."
  [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(use-fixtures :each with-tmp-nido-root)

(defn- queued-envelopes
  "Envelopes sitting in the (temp) queue dir."
  []
  (->> (fs/list-dir (cstate/queue-dir))
       (filter #(str/ends-with? (str %) ".edn"))
       (mapv #(clojure.edn/read-string (slurp (str %))))))

(deftest loop-cmd-passes-config-and-defaults
  (let [seen (atom nil)]
    (with-redefs [rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :converged :history []})]
      (t/loop-cmd ":base" "develop" ":max-iters" "3" ":cwd" "/w")
      (is (= "develop" (:base @seen)))
      (is (= 3 (:max-iters @seen)))
      (is (= "/w" (:cwd @seen)))
      (is (string? (:run-id @seen)))
      (is (fn? (:emit @seen)) "engine is given an emit fn")
      (is (fn? (:clock @seen)) "engine is given a clock"))))

(defn- run-loop-writing-a-report
  "A stubbed engine that leaves a report where the real one would, so the
   enqueue gate — which refuses a run with no report to read — sees a run that
   produced something."
  [status]
  (fn [cfg]
    (let [rp (fs/path (cstate/run-dir (:run-id cfg)) "report.json")]
      (fs/create-dirs (fs/parent rp))
      (spit (str rp) "{}"))
    {:status status :history []}))

(deftest loop-cmd-queues-exactly-one-analysis-for-the-finished-run
  ;; Fires once per RUN, never per round, and only after the loop returns.
  (with-redefs [rloop/run-loop (run-loop-writing-a-report :converged)]
    (t/loop-cmd ":cwd" "/w")
    (let [envs (queued-envelopes)]
      (is (= 1 (count envs)))
      (is (= {:project :nido :trigger :review-analysis} (:target (first envs)))
          "aimed nido-side, never at the reviewed project")
      (is (= "converged" (get-in (first envs) [:payload :status]))))))

(deftest loop-cmd-queues-no-analysis-for-a-dry-run
  (with-redefs [rloop/run-loop (run-loop-writing-a-report :converged)]
    (t/loop-cmd ":cwd" "/w" ":dry-run?" "true")
    (is (empty? (queued-envelopes)))))

(deftest loop-cmd-queues-no-analysis-when-the-run-left-no-report
  ;; The shape that spawned phantom sessions: the command driven with the engine
  ;; stubbed out, so nothing was written and there is nothing to analyse.
  (with-redefs [rloop/run-loop (fn [_] {:status :converged :history []})]
    (t/loop-cmd ":cwd" "/w")
    (is (empty? (queued-envelopes))
        "no report on disk means no session is provisioned to go and read one")))

(deftest loop-cmd-defaults-base-to-main
  (let [seen (atom nil)]
    (with-redefs [rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :clean :history []})]
      (t/loop-cmd ":cwd" "/w")
      (is (= "main" (:base @seen)))
      (is (nil? (:max-iters @seen)) "uncapped by default — runs as long as it takes"))))

(deftest loop-cmd-resolves-worktree-when-cwd-absent
  (let [seen (atom nil)]
    (with-redefs [lifecycle/worktree-from-cwd (fn [] "/resolved/wt")
                  rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :clean :history []})]
      (t/loop-cmd)
      (is (= "/resolved/wt" (:cwd @seen))))))

(deftest loop-cmd-explicit-cwd-overrides-resolution
  (let [seen (atom nil)]
    (with-redefs [lifecycle/worktree-from-cwd (fn [] "/resolved/wt")
                  rloop/run-loop (fn [cfg] (reset! seen cfg) {:status :clean :history []})]
      (t/loop-cmd ":cwd" "/explicit")
      (is (= "/explicit" (:cwd @seen))))))

(deftest loop-cmd-exit-maps-status
  (with-redefs [rloop/run-loop (fn [_] {:status :clean :history []})]
    (is (zero? (t/exit-code :clean))))
  (is (zero? (t/exit-code :converged)))
  (is (zero? (t/exit-code :escalated)))
  (is (= 1 (t/exit-code :review-failed))))

(deftest review-event-derives-verdict-and-counts
  (let [final  {:status :escalated :findings [{:file "a" :line-start 1 :title "x"}
                                              {:file "b" :line-start 2 :title "y"}]}
        report {:summary {:rounds 3 :findings-fixed 4}
                :target  {:base "main" :base-rev "deadbee"}}
        ev     (t/review-event final report "/runs/r/report.json")]
    (is (= :review-report (:format ev)))
    (is (= :escalated (:status ev)))
    (is (= "main" (:base ev)))
    (is (= "deadbee" (:base-rev ev)))
    (is (= 3 (:rounds ev)))
    (is (= 4 (:findings-fixed ev)))
    (is (= 2 (:findings-remaining ev)))
    (is (= "/runs/r/report.json" (:report-path ev)))))

(deftest review-event-defaults-missing-counts-to-zero
  (let [ev (t/review-event {:status :review-failed}
                           {:target {:base "main" :base-rev nil}}
                           nil)]
    (is (= 0 (:rounds ev)))
    (is (= 0 (:findings-fixed ev)))
    (is (= 0 (:findings-remaining ev)))
    (is (nil? (:base-rev ev)))))

(deftest append-review-entry-writes-when-workstream-resolves
  (let [appended (atom nil)]
    (with-redefs [lifecycle/session-from-cwd (fn [_] {:project "brian" :session "s1"})
                  csession/workstream-id-for (fn [_ _] "ws-1")
                  ws/append-entry! (fn [p id entry content]
                                     (reset! appended {:p p :id id :entry entry :content content})
                                     "/path")]
      (let [ret (t/append-review-entry! "/w"
                                        {:status :converged :findings []}
                                        {:summary {:rounds 1 :findings-fixed 0}
                                         :target {:base "main" :base-rev "abc"}}
                                        "/runs/r/report.json")]
        (is (= "ws-1" ret))
        (is (= :brian (:p @appended)))
        (is (= :review (:kind (:entry @appended))))
        (is (str/includes? (:content @appended) ":review-report"))))))

(deftest append-review-entry-noops-without-workstream
  (let [called (atom false)]
    (with-redefs [lifecycle/session-from-cwd (fn [_] nil)
                  ws/append-entry! (fn [& _] (reset! called true) "/path")]
      (is (nil? (t/append-review-entry! "/w" {:status :clean :findings []}
                                        {:target {:base "main"}} nil)))
      (is (false? @called) "no append when cwd resolves to no session"))))

(deftest append-review-entry-swallows-append-failure
  (with-redefs [lifecycle/session-from-cwd (fn [_] {:project "brian" :session "s1"})
                csession/workstream-id-for (fn [_ _] "ws-1")
                ws/append-entry! (fn [& _] (throw (ex-info "disk boom" {})))]
    (is (nil? (t/append-review-entry! "/w" {:status :converged :findings []}
                                      {:summary {:rounds 1 :findings-fixed 0}
                                       :target {:base "main" :base-rev "abc"}}
                                      "/runs/r/report.json"))
        "a ledger-write failure is swallowed — returns nil, does not throw")))
