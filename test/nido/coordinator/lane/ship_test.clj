(ns nido.coordinator.lane.ship-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [nido.platform.core :as core]
   [nido.coordinator.lane.conduct :as conduct]
   [nido.coordinator.executor :as ex]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.lane.ship :as sut]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.status-file :as status-file]
   [nido.coordinator.record.workstream :as ws]
   [nido.session.state]))

;; Redirect ~/.nido to a temp dir for the duration of each test, and reset
;; the executor — same pattern as spawn_test.clj (with-tmp) +
;; executor_test.clj (reset-executor!).
(defn- each-fixture [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (ex/configure! {:global-cap 2})
      (ex/clear!)
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(use-fixtures :each each-fixture)

(deftest handle-ship-advances-stage-and-creates-one-merge-run
  (let [submitted (atom [])
        w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-42"}]})]
    (with-redefs [ex/submit! (fn [& a] (swap! submitted conj a))]
      (let [run (sut/handle-ship! {:type :ship :project :brian
                                   :session "impl-br-42" :ws-id (:id w)})]
        ;; stage flipped
        (is (= :shipping (:stage (ws/newest-choice (ws/read-ws :brian (:id w))))))
        ;; one merge run, correct shape
        (is (= :merge      (:trigger run)))
        (is (= :drive-home (:skill run)))
        (is (true?         (:uncapped? run)))
        (is (= "impl-br-42" (:session-name run)))
        (is (= "BR-42"     (-> run :event-payload :id)))
        (is (= "/drive-home" (:first-message run)))
        ;; submitted uncapped to the :merge lane at max-in-flight 1
        (is (= 1 (count @submitted)))
        (let [[_rid _prio uncapped? trigger mif] (first @submitted)]
          (is (true? uncapped?)) (is (= :merge trigger)) (is (= 1 mif)))))))

(deftest handle-ship-marks-the-shipment-on-the-ledger
  (let [w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-42"}]})]
    (with-redefs [ex/submit! (fn [& _] nil)]
      (let [run (sut/handle-ship! {:type :ship :project :brian
                                   :session "impl-br-42" :ws-id (:id w)})
            e   (last (:entries (ws/read-ws :brian (:id w))))]
        (is (= :ship-submitted (:kind e)))
        (is (= "impl-br-42" (:session e)))
        (is (= (:id run) (:run-id e)) "the entry names the merge Run it started")
        (is (= {:format :ship-submitted :session "impl-br-42"}
               (dissoc (ws/latest-entry :brian (:id w) :ship-submitted) :seq :at)))))))

(deftest ship-submitted-resets-a-stale-success-fingerprint
  ;; A halted ship leaves :implementation-completed as the ledger's last entry.
  ;; Re-shipping appends :ship-submitted, so a drive-home that then files nothing
  ;; cannot inherit the previous attempt's success — it falls back to run-status.
  (let [w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-42"}]})]
    (ws/append-entry! :brian (:id w) {:kind :implementation-completed}
                      (pr-str {:format :implementation-completed
                               :summary "landed" :artifacts []}))
    (is (= :awaiting-merge (sut/classify-outcome :brian "BR-42" "r1" {:exit-code 0 :num-turns 3}))
        "stale fingerprint reads as merged before the re-ship")
    (with-redefs [ex/submit! (fn [& _] nil)]
      (sut/handle-ship! {:type :ship :project :brian :session "impl-br-42" :ws-id (:id w)}))
    (with-redefs [status-file/read-status (fn [_] nil)]
      (is (= :blocked (sut/classify-outcome :brian "BR-42" "r1" {:exit-code 0 :num-turns 3}))
          "after the re-ship the fingerprint is neutral and fail-safe wins"))))

(deftest handle-ship-is-idempotent-while-in-flight
  (let [w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-7"}]})]
    (with-redefs [ex/submit! (fn [& _] nil)]
      (let [r1 (sut/handle-ship! {:type :ship :project :brian :session "impl-br-7" :ws-id (:id w)})]
        ;; r1 run is :queued → not in-progress yet; force it in-flight:
        (runs/transition! (:id r1) :running)
        (let [r2 (sut/handle-ship! {:type :ship :project :brian :session "impl-br-7" :ws-id (:id w)})]
          (is (nil? r2)))))))

(deftest handle-ship-allows-second-call-while-queued
  (let [w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-99"}]})]
    (with-redefs [ex/submit! (fn [& _] nil)]
      (sut/handle-ship! {:type :ship :project :brian :session "impl-br-99" :ws-id (:id w)})
      ;; r1 is still :queued (never transitioned in-progress) — second call must NOT return nil
      (let [r2 (sut/handle-ship! {:type :ship :project :brian :session "impl-br-99" :ws-id (:id w)})]
        (is (some? r2))))))

(deftest handle-ship-allows-re-ship-after-block
  (let [w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-88"}]})]
    (with-redefs [ex/submit! (fn [& _] nil)]
      (let [r1 (sut/handle-ship! {:project :brian :session "impl-br-88" :ws-id (:id w)})]
        ;; drive it to a parked/blocked terminal state
        (runs/transition! (:id r1) :running)
        (runs/transition! (:id r1) :awaiting-review)
        ;; the human fixed the blocker and re-ships — must NOT no-op
        (let [r2 (sut/handle-ship! {:project :brian :session "impl-br-88" :ws-id (:id w)})]
          (is (some? r2))
          (is (not= (:id r1) (:id r2))))))))

(deftest classify-reads-ledger-fingerprint
  (with-redefs [ws/find-by-ref-id (fn [_ _] {:entries [{:kind :implementation-completed}]})]
    (is (= :awaiting-merge (sut/classify-outcome :brian "BR-1" "r1" {:exit-code 0 :num-turns 5}))))
  (with-redefs [ws/find-by-ref-id (fn [_ _] {:entries [{:kind :blocker}]})]
    (is (= :blocked (sut/classify-outcome :brian "BR-1" "r1" {:exit-code 0 :num-turns 5})))))

(deftest classify-hard-failures-are-blocked
  (is (= :blocked (sut/classify-outcome :brian "BR-1" "r1" {:timed-out? true :exit-code 143})))
  (is (= :blocked (sut/classify-outcome :brian "BR-1" "r1" {:spawn-error true})))
  ;; exit 0 but zero turns = no-op (e.g. "Unknown command")
  (is (= :blocked (sut/classify-outcome :brian "BR-1" "r1" {:exit-code 0 :num-turns 0}))))

(deftest classify-falls-back-to-run-status-then-blocked
  ;; no BR / no ledger entry → consult run-status file
  (with-redefs [ws/find-by-ref-id   (fn [_ _] nil)
                status-file/read-status (fn [_] {:phase :complete})]
    (is (= :awaiting-merge (sut/classify-outcome :brian nil "r1" {:exit-code 0 :num-turns 3}))))
  (with-redefs [ws/find-by-ref-id   (fn [_ _] nil)
                status-file/read-status (fn [_] nil)]
    (is (= :blocked (sut/classify-outcome :brian nil "r1" {:exit-code 0 :num-turns 3})))))

(defn- impl-session! [ws-id session-name phase]
  (session/create! :brian ws-id
                   {:name session-name :weight :heavy
                    :autonomy {:skill :plan-bug :first-message "x" :agent :claude
                               :claude-session-id nil :trigger :plan-bug :limits {}
                               :priority 0 :uncapped? false :on-promote nil
                               :phase phase :phase-history [] :error nil}}))

(deftest settling-a-finished-drive-home-marks-done-no-teardown
  (let [torn (atom false)
        w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-9"}]})]
    (impl-session! (:id w) "impl-br-9" :parked)
    (let [run (sut/create-merge-run! :brian (:id w) "impl-br-9")]
      (runs/transition! (:id run) :running)
      (with-redefs [sut/classify-outcome           (fn [& _] :awaiting-merge)
                    runs/teardown-session-for-run! (fn [_] (reset! torn true))]
        (is (= :awaiting-merge (sut/settle-drive-home! (:id run) {})))
        (is (= :done (:state (runs/read-run (:id run)))))
        (is (false? @torn))))))

(deftest settling-a-blocked-drive-home-parks-with-error
  (let [w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-10"}]})]
    (impl-session! (:id w) "impl-br-10" :running)
    (let [run (sut/create-merge-run! :brian (:id w) "impl-br-10")]
      (runs/transition! (:id run) :running)
      (with-redefs [sut/classify-outcome (fn [& _] :blocked)]
        (sut/settle-drive-home! (:id run) {})
        (is (= :awaiting-review (:state (runs/read-run (:id run)))))
        (let [s (session/read-session :brian (:id w) "impl-br-10")]   ; mirrored from :awaiting-review
          (is (some?     (get-in s [:autonomy :error]))))))))

(def ^:private facet
  {:session-id "s" :bg-id "s" :origin :person :driver :nido :hold nil :pending nil :queued nil
   :own-work? false :policy {:auto-respawn? false} :cursor 0 :driving-since "t"
   :announced-blocked? false :recovered-boot nil})

(defn- drive-home-with [facet-or-nil]
  (let [w   (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-11"}]})
        nm  (str "impl-" (subs (str (random-uuid)) 0 8))
        _   (impl-session! (:id w) nm :parked)
        _   (cond
              (:dispatching facet-or-nil) (session/update-substrate! :brian (:id w) nm (constantly {:claude nil :dispatching (:dispatching facet-or-nil)}))
              facet-or-nil (session/update-claude! :brian (:id w) nm (constantly facet-or-nil)))
        run (sut/create-merge-run! :brian (:id w) nm)
        did (atom [])]
    (with-redefs [runs/launch-context (fn [_] {:mcp-config nil :add-dirs [] :briefing "" :run-paths ""})
                  nido.session.state/session-home-dir (fn [_ _] "/tmp")
                  conduct/start!      (fn [o] (swap! did conj [:start (:prompt o) (:cwd o)]) {})
                  conduct/adopt!      (fn [o] (swap! did conj [:adopt (:session-id o)]) {})
                  conduct/instruct!   (fn [_ _ _ t _] (swap! did conj [:instruct t]) {})]
      {:returned (sut/drive-home! (:id run))
       :did      @did
       :run      (runs/read-run (:id run))})))

(deftest drive-home-gives-the-instruction-and-returns
  (testing "no background session yet: one is started in the home"
    (let [{:keys [returned did run]} (drive-home-with nil)]
      (is (= :started returned))
      (is (= [[:start "/drive-home" "/tmp"]] did))
      (is (= :running (:state run)) "it settles when the turn's Stop arrives, not now")))
  (testing "one nido drives: the instruction is queued for it"
    (is (= [[:instruct "/drive-home"]] (:did (drive-home-with facet)))))
  (testing "a released one: the person's own ship adopts it first"
    (is (= [[:adopt "s"] [:instruct "/drive-home"]]
           (:did (drive-home-with (assoc facet :driver :nobody)))))))

(deftest drive-home-refuses-a-held-session
  (let [{:keys [did run]} (drive-home-with (assoc facet :hold {:at "t" :because :foreign-prompt}))]
    (is (= [] did))
    (is (= :awaiting-review (:state run)) "parked as blocked, with the reason on the session")))

(deftest drive-home-refuses-a-session-nido-started-but-has-no-id-for
  ;; A start still in flight has no facet to queue on until a listing names its session, so
  ;; ship says so and parks.
  (let [{:keys [did run]} (drive-home-with {:dispatching {:token "T" :at "t" :bg-id "bg-x"}})]
    (is (= [] did))
    (is (= :awaiting-review (:state run)))))

(deftest merge-lane-summary-counts-by-run-state
  (sut/create-merge-run! :brian "w1" "s1")                       ; :queued
  (let [r2 (sut/create-merge-run! :brian "w2" "s2")]
    (runs/transition! (:id r2) :running))                        ; :driving
  (let [r3 (sut/create-merge-run! :brian "w3" "s3")]
    (runs/transition! (:id r3) :running)
    (runs/transition! (:id r3) :awaiting-review))                ; :blocked
  (is (= {:driving 1 :queued 1 :blocked 1} (sut/merge-lane-summary))))
