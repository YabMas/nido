(ns nido.coordinator.session-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.session :as sess]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))
                       clock/now-iso    (constantly "2026-06-05T09:00:00Z")]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(def human-session
  {:name "explore-firefox"
   :workstream-id "ws-1"
   :project :brian
   :weight :light
   :substrate :live
   :substrate-history [{:at "2026-06-05T09:00:00Z" :substrate :live}]
   :autonomy nil
   :created-at "2026-06-05T09:00:00Z"})

(def autonomous-session
  (assoc human-session
         :name "run-triage-x"
         :weight :light
         :autonomy {:skill :triage-bug
                    :first-message "/triage-bug BR-1"
                    :agent :claude
                    :claude-session-id nil
                    :trigger :triage-bug
                    :limits {:budget "30m" :max-failures 3}
                    :priority 0
                    :uncapped? false
                    :on-promote nil
                    :phase :running
                    :phase-history [{:at "2026-06-05T09:00:00Z" :phase :queued}
                                    {:at "2026-06-05T09:01:00Z" :phase :running}]
                    :error nil}))

(deftest schema-accepts-human-and-autonomous
  (is (m/validate sess/Session human-session))
  (is (m/validate sess/Session autonomous-session)))

(deftest schema-rejects-bad-substrate
  (is (not (m/validate sess/Session (assoc human-session :substrate :nonsense)))))

(deftest schema-rejects-bad-weight
  (is (not (m/validate sess/Session (assoc human-session :weight :medium)))))

(deftest round-trip
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (sess/write! autonomous-session)
        (is (= autonomous-session
               (sess/read-session :brian "ws-1" "run-triage-x"))))
      (finally (fs/delete-tree tmp)))))

(deftest read-session-returns-nil-when-missing
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (is (nil? (sess/read-session :brian "ws-1" "no-such"))))
      (finally (fs/delete-tree tmp)))))

(deftest create-seeds-substrate-history
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        (let [s (sess/create! :brian "ws-1"
                              {:name "sx" :weight :heavy :autonomy nil})]
          (is (= :live (:substrate s)))
          (is (= [{:at "2026-06-05T09:00:00Z" :substrate :live}] (:substrate-history s)))
          (is (= s (sess/read-session :brian "ws-1" "sx")))))
      (finally (fs/delete-tree tmp)))))

(deftest list-sessions-returns-records-for-a-workstream
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        (sess/create! :brian "ws-1" {:name "a" :weight :light :autonomy nil})
        (sess/create! :brian "ws-1" {:name "b" :weight :heavy :autonomy nil})
        (is (= #{"a" "b"} (set (map :name (sess/list-sessions :brian "ws-1"))))))
      (finally (fs/delete-tree tmp)))))

(deftest archive-flips-substrate-and-appends-history
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T13:00:00Z")]
        (sess/write! human-session)
        (let [a (sess/archive! :brian "ws-1" "explore-firefox")]
          (is (= :archived (:substrate a)))
          (is (= {:at "2026-06-05T13:00:00Z" :substrate :archived}
                 (last (:substrate-history a))))))
      (finally (fs/delete-tree tmp)))))

(deftest set-phase-updates-autonomy-and-history
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T13:30:00Z")]
        (sess/write! autonomous-session)
        (let [p (sess/set-phase! :brian "ws-1" "run-triage-x" :parked)]
          (is (= :parked (get-in p [:autonomy :phase])))
          (is (= {:at "2026-06-05T13:30:00Z" :phase :parked}
                 (last (get-in p [:autonomy :phase-history]))))))
      (finally (fs/delete-tree tmp)))))

(deftest set-phase-throws-on-human-session
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (sess/write! human-session)
        (is (thrown? clojure.lang.ExceptionInfo
                     (sess/set-phase! :brian "ws-1" "explore-firefox" :parked))))
      (finally (fs/delete-tree tmp)))))

(deftest predicates
  (is (sess/live? human-session))
  (is (not (sess/live? (assoc human-session :substrate :archived))))
  (is (not (sess/parked? autonomous-session)))
  (is (sess/parked? (assoc-in autonomous-session [:autonomy :phase] :parked)))
  ;; parked requires BOTH live and phase :parked — an archived session with a
  ;; :parked phase is NOT parked.
  (is (not (sess/parked? (-> autonomous-session
                             (assoc :substrate :archived)
                             (assoc-in [:autonomy :phase] :parked)))))
  (is (sess/autonomous? autonomous-session))
  (is (not (sess/autonomous? human-session))))

(deftest archive-is-idempotent
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T13:00:00Z")]
        (sess/write! human-session)
        (sess/archive! :brian "ws-1" "explore-firefox")
        (let [again (sess/archive! :brian "ws-1" "explore-firefox")]
          (is (= :archived (:substrate again)))
          ;; live + archived only — second archive adds no duplicate entry.
          (is (= 2 (count (:substrate-history again))))))
      (finally (fs/delete-tree tmp)))))

(deftest engagement-projection
  ;; settled wins regardless of sessions
  (is (= :settled (sess/engagement-state {:at "t" :outcome :done} [autonomous-session])))
  ;; parked beats active (a parked session is also live)
  (let [parked (assoc-in autonomous-session [:autonomy :phase] :parked)]
    (is (= :parked-at-gate (sess/engagement-state nil [human-session parked]))))
  ;; any live session ⇒ active
  (is (= :active (sess/engagement-state nil [human-session])))
  ;; no live sessions ⇒ idle
  (is (= :idle (sess/engagement-state nil [(assoc human-session :substrate :archived)])))
  (is (= :idle (sess/engagement-state nil []))))

(deftest engagement-state-distinguishes-queued-from-active
  (let [auton (fn [phase] {:substrate :live :autonomy {:phase phase}})
        human {:substrate :live :autonomy nil}]
    (is (= :settled        (sess/engagement-state {:at "t" :outcome :done} [(auton :running)])))
    (is (= :parked-at-gate (sess/engagement-state nil [(auton :parked) (auton :queued)])))
    (is (= :active         (sess/engagement-state nil [(auton :running)])))
    (is (= :active         (sess/engagement-state nil [(auton :preprocessing)])))
    (is (= :active         (sess/engagement-state nil [human])))
    (is (= :queued         (sess/engagement-state nil [(auton :queued)])))
    (is (= :active         (sess/engagement-state nil [(auton :queued) (auton :running)])))
    (is (= :idle           (sess/engagement-state nil [{:substrate :archived :autonomy {:phase :done}}])))
    (is (= :idle           (sess/engagement-state nil [])))))

(deftest in-flight-by-trigger-counts-live-in-progress-autonomous-sessions
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        (let [w (ws/create! :brian {:stage :investigation})
              auto (fn [nm phase]
                     {:name nm :weight :light
                      :autonomy (assoc (:autonomy autonomous-session)
                                       :phase phase
                                       :trigger :triage-bug)})]
          (sess/create! :brian (:id w) (auto "r1" :running))
          (sess/create! :brian (:id w) (auto "r2" :running))
          (sess/create! :brian (:id w) (auto "p1" :parked))
          (sess/create! :brian (:id w) (auto "q1" :queued))
          (sess/create! :brian (:id w) {:name "human" :weight :light :autonomy nil})
          (sess/archive! :brian (:id w)
                         (:name (sess/create! :brian (:id w) (auto "gone" :running))))
          (is (= {:triage-bug 2} (sess/in-flight-by-trigger :brian)))))
      (finally (fs/delete-tree tmp)))))

(deftest gating-count-includes-parked
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        (let [w  (ws/create! :brian {:stage :investigation})
              mk (fn [nm phase]
                   (sess/create! :brian (:id w)
                                 {:name nm :weight :light
                                  :autonomy (assoc (:autonomy autonomous-session)
                                                   :phase phase
                                                   :trigger :triage-bug)}))]
          (mk "r-run" :running)
          (mk "r-park" :parked)
          (mk "r-queued" :queued)
          ;; gating counts running + parked (backpressure), NOT queued
          (is (= {:triage-bug 2} (sess/gating-count-by-trigger :brian)))
          ;; in-flight-by-trigger (active work) still excludes parked
          (is (= {:triage-bug 1} (sess/in-flight-by-trigger :brian)))))
      (finally (fs/delete-tree tmp)))))

(deftest gating-count-excludes-closed-workstream-sessions
  ;; Regression: the TUI [d]one lever closes a workstream without resolving its
  ;; ticket, so sweep-resolved! can't reap the still-:parked session. That ghost
  ;; session must not keep pinning its trigger at :max-in-flight.
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        (let [open   (ws/create! :brian {:stage :investigation})
              closed (ws/create! :brian {:stage :investigation})
              mk (fn [wsid nm phase]
                   (sess/create! :brian wsid
                                 {:name nm :weight :light
                                  :autonomy (assoc (:autonomy autonomous-session)
                                                   :phase phase
                                                   :trigger :triage-bug)}))]
          (mk (:id open) "live-park" :parked)
          (mk (:id closed) "ghost-park" :parked)
          (ws/close! :brian (:id closed) :done)
          ;; only the open workstream's parked session counts toward the cap
          (is (= {:triage-bug 1} (sess/gating-count-by-trigger :brian)))))
      (finally (fs/delete-tree tmp)))))

(deftest stage-projection-derives-lifecycle
  (let [live   (assoc autonomous-session :substrate :live :autonomy {:phase :running})
        parked (assoc autonomous-session :substrate :live :autonomy {:phase :parked})
        dead   (assoc autonomous-session :substrate :archived :autonomy {:phase :done})]
    ;; closed workstream → :done
    (is (= :done  (:stage (sess/stage-projection {:at "t" :outcome :done} :triaged [dead] :triaging))))
    ;; ticket status drives the ladder
    (is (= :done        (:stage (sess/stage-projection nil :dismissed [dead] :triaging))))  ; off-radar
    (is (= :in-progress (:stage (sess/stage-projection nil :planning [live] :triaging))))
    (is (= :in-progress (:stage (sess/stage-projection nil :implementing [live] :triaging))))
    (is (= :ready       (:stage (sess/stage-projection nil :triaged [dead] :triaging))))
    (is (= :triage      (:stage (sess/stage-projection nil :investigating [live] :triaging))))
    (is (= :triage      (:stage (sess/stage-projection nil :awaiting-input [parked] :triaging))))
    ;; NO ledger entry (nil status) → :triage, regardless of run liveness. A failed
    ;; triage (archived/dead session, no entry) must STAY in the queue, not vanish.
    (is (= :triage      (:stage (sess/stage-projection nil nil [live] :triaging))))
    (is (= :triage      (:stage (sess/stage-projection nil nil [dead] :triaging))))
    (is (= :triage      (:stage (sess/stage-projection nil nil [] :triaging))))
    ;; manual override wins only when it names a lifecycle stage (:triaging default is ignored)
    (is (= :in-progress (:stage (sess/stage-projection nil :triaged [dead] :in-progress))))
    (is (= :ready       (:stage (sess/stage-projection nil :triaged [dead] :triaging))))))

(deftest stage-projection-needs-you
  (let [parked  (assoc autonomous-session :substrate :live :autonomy {:phase :parked})
        running (assoc autonomous-session :substrate :live :autonomy {:phase :running})
        dead    (assoc autonomous-session :substrate :archived :autonomy {:phase :done})]
    ;; ready is a PULL queue, not a gate — never needs-you (you pull it off the board)
    (is (false? (:needs-you (sess/stage-projection nil :triaged [dead] :triaging))))
    (is (= :ready (:stage (sess/stage-projection nil :triaged [dead] :triaging)))
        "…but it is still on the spine at :ready, visible on the board")
    ;; triage/in-progress need-you only when a session is parked
    (is (true?  (:needs-you (sess/stage-projection nil :awaiting-input [parked] :triaging))))
    (is (false? (:needs-you (sess/stage-projection nil :investigating [running] :triaging))))
    (is (true?  (:needs-you (sess/stage-projection nil :implementing [parked] :triaging))))
    (is (false? (:needs-you (sess/stage-projection nil :implementing [running] :triaging))))
    ;; done never needs you
    (is (false? (:needs-you (sess/stage-projection {:at "t" :outcome :done} :done [dead] :triaging))))
    ;; the incoming holding pen is passive — never a gate
    (is (false? (:needs-you (sess/stage-projection nil :investigating [running] :incoming))))
    (is (false? (:needs-you (sess/stage-projection nil nil [] :incoming))))))

(deftest list-sessions-round-trips-a-slash-containing-name
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :scratch :external-refs []})]
        (sess/create! :brian (:id w)
                      {:name "feat/course-materials-tab" :weight :light :autonomy nil})
        (is (= ["feat/course-materials-tab"]
               (mapv :name (sess/list-sessions :brian (:id w))))
            "a '/'-containing session name round-trips through list-sessions")))))

(deftest list-sessions-still-handles-flat-names
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :scratch :external-refs []})]
        (sess/create! :brian (:id w) {:name "refshot" :weight :light :autonomy nil})
        (is (= ["refshot"] (mapv :name (sess/list-sessions :brian (:id w)))))))))

(deftest set-error-sets-and-clears-autonomy-error
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
        (sess/create! :brian (:id w)
                      {:name "auto" :weight :heavy
                       :autonomy {:skill :triage-bug :first-message "x" :agent :claude
                                  :claude-session-id nil :trigger :triage-bug :limits {}
                                  :priority 0 :uncapped? false :on-promote nil :phase :parked
                                  :phase-history [] :error nil}})
        (sess/set-error! :brian (:id w) "auto" {:reason :resume-failed :message "boom"})
        (is (= :resume-failed (-> (first (sess/list-sessions :brian (:id w))) :autonomy :error :reason)))
        (sess/set-error! :brian (:id w) "auto" nil)
        (is (nil? (-> (first (sess/list-sessions :brian (:id w))) :autonomy :error)))))))

(deftest set-error-throws-on-human-session
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
        (sess/create! :brian (:id w) {:name "me" :weight :light :autonomy nil})
        (is (thrown? clojure.lang.ExceptionInfo
                     (sess/set-error! :brian (:id w) "me" {:reason :x})))))))

(deftest incoming-stage-projection-open
  ;; A stored :incoming stage is honored as an override but is passive — never needs-you.
  (is (= {:stage :incoming :needs-you false}
         (sess/stage-projection nil nil [] :incoming))))

(deftest incoming-stage-projection-closed
  ;; A CLOSED incoming workstream falls through to :done (no longer needs-you),
  ;; even though :incoming is a lifecycle stage — the override is only honored
  ;; while the workstream is open.
  (is (= {:stage :done :needs-you false}
         (sess/stage-projection {:at "2026-06-01T00:00:00Z" :outcome :dropped}
                                nil [] :incoming))))

(deftest set-claude-session-id-writes-onto-autonomy
  (with-tmp
    (fn [_]
      (sess/write! (assoc autonomous-session :workstream-id "ws-1"))
      (sess/set-claude-session-id! :brian "ws-1" "run-triage-x" "sid-42")
      (is (= "sid-42"
             (get-in (sess/read-session :brian "ws-1" "run-triage-x")
                     [:autonomy :claude-session-id]))))))

(deftest set-claude-session-id-throws-on-human-session
  (with-tmp
    (fn [_]
      (sess/write! human-session)
      (is (thrown? Exception
            (sess/set-claude-session-id! :brian "ws-1" "explore-firefox" "x"))))))

(deftest shipping-stage-is-honored-as-override
  ;; An OPEN workstream whose stored :stage is :shipping projects :shipping
  ;; (not the derived :in-progress), because :shipping is in lifecycle-stages.
  (is (= :shipping
         (:stage (sess/stage-projection nil :implementing [] :shipping))))
  ;; closed always wins → :done
  (is (= :done
         (:stage (sess/stage-projection {:outcome :done} :implementing [] :shipping)))))

(deftest shipping-needs-you-only-when-parked
  (let [parked  {:substrate :live :autonomy {:phase :parked}}
        running {:substrate :live :autonomy {:phase :running}}]
    (is (true?  (:needs-you (sess/stage-projection nil nil [parked] :shipping))))
    (is (false? (:needs-you (sess/stage-projection nil nil [running] :shipping))))))

(deftest workstream-id-for-finds-the-owning-ws
  (with-tmp
    (fn [_]
      (let [ws1 (ws/create! :brian {:stage :triaging :external-refs []})
            ws2 (ws/create! :brian {:stage :triaging :external-refs []})]
        (sess/create! :brian (:id ws1) {:name "impl-br-1" :weight :heavy :autonomy nil})
        (sess/create! :brian (:id ws2) {:name "impl-br-2" :weight :heavy :autonomy nil})
        (is (= (:id ws2) (sess/workstream-id-for :brian "impl-br-2")))
        (is (nil? (sess/workstream-id-for :brian "nope")))))))

(deftest ship-substate-reads-the-live-autonomous-session
  ;; archived triage session (phase :done) + live impl session (phase :running):
  ;; substate must reflect the LIVE one (driving), not the archived one (awaiting-merge)
  (let [archived {:substrate :archived :autonomy {:phase :done}}
        live     {:substrate :live     :autonomy {:phase :running}}]
    (is (= :driving (sess/ship-substate [archived live])))
    (is (= :driving (sess/ship-substate [live archived])))))

(deftest ship-substate-from-session-phase
  (let [phase #(do {:substrate :live :autonomy {:phase %}})]
    (is (= :queued         (sess/ship-substate [(phase :queued)])))
    (is (= :driving        (sess/ship-substate [(phase :running)])))
    (is (= :driving        (sess/ship-substate [(phase :preprocessing)])))
    (is (= :blocked        (sess/ship-substate [(phase :parked)])))
    (is (= :awaiting-merge (sess/ship-substate [(phase :done)])))
    (is (nil?              (sess/ship-substate [{:substrate :live :autonomy nil}])))))

(defn- auton-for [trigger phase]
  {:skill :triage-bug :first-message "m" :agent :claude :claude-session-id nil
   :trigger trigger :limits {} :priority 0 :uncapped? false :on-promote nil
   :phase phase :phase-history [{:at "2026-06-05T09:00:00Z" :phase phase}] :error nil})

(deftest pending-session-for-trigger-true-for-in-flight-phases
  ;; A session in any non-terminal phase — INCLUDING :queued (which gating-phases
  ;; excludes) — counts as an in-flight run the reconcile dedup gate must see.
  (with-tmp
    (fn [_]
      (doseq [phase [:queued :preprocessing :running :parked]]
        (let [ws-id (str "ws-" (name phase))]
          (sess/create! :brian ws-id {:name (str "run-" (name phase)) :weight :light
                                      :autonomy (auton-for :triage-teacher-bugs phase)})
          (is (true? (sess/pending-session-for-trigger? :brian ws-id :triage-teacher-bugs))
              (str "phase " phase " should count as pending")))))))

(deftest pending-session-for-trigger-false-for-terminal-mismatch-or-empty
  (with-tmp
    (fn [_]
      ;; terminal phases must not count — a settled ticket stays re-triable
      (doseq [phase [:done :failed :halted]]
        (let [ws-id (str "ws-t-" (name phase))]
          (sess/create! :brian ws-id {:name (str "run-" (name phase)) :weight :light
                                      :autonomy (auton-for :triage-teacher-bugs phase)})
          (is (false? (sess/pending-session-for-trigger? :brian ws-id :triage-teacher-bugs))
              (str "terminal phase " phase " must not count"))))
      ;; a pending session for a DIFFERENT trigger doesn't count
      (sess/create! :brian "ws-other" {:name "run-other" :weight :light
                                       :autonomy (auton-for :triage-new :queued)})
      (is (false? (sess/pending-session-for-trigger? :brian "ws-other" :triage-teacher-bugs)))
      ;; a human (non-autonomous) session doesn't count
      (sess/create! :brian "ws-human" {:name "explore" :weight :light :autonomy nil})
      (is (false? (sess/pending-session-for-trigger? :brian "ws-human" :triage-teacher-bugs)))
      ;; empty / unknown workstream
      (is (false? (sess/pending-session-for-trigger? :brian "ws-empty" :triage-teacher-bugs))))))

(deftest notion-stage-maps-status-to-band
  (is (= :done        (sess/notion-stage "Done" false)))
  (is (= :done        (sess/notion-stage "Not Done" true)))
  (is (= :done        (sess/notion-stage "Review" true)) "Review is done from nido's side")
  (is (= :in-progress (sess/notion-stage "In progress" false)))
  (is (= :in-progress (sess/notion-stage "Code Review" false)))
  (is (= :ready       (sess/notion-stage "Not started" true)) "pre-impl + triaged → ready")
  (is (= :triage      (sess/notion-stage "Not started" false)) "pre-impl + untriaged → triage")
  (is (= :triage      (sess/notion-stage nil false)) "no status + untriaged → triage")
  (is (= :ready       (sess/notion-stage "On Hold" true))))

(deftest notion-stage-projection-shipping-overlay-and-needs-you
  (is (= :shipping (:stage (sess/notion-stage-projection
                            {:shipping? true :notion-status "In progress"
                             :has-triage-report? true :sessions []})))
      "merge-lane overlay wins over Notion status")
  (is (= {:stage :triage :needs-you false}
         (sess/notion-stage-projection
          {:shipping? false :notion-status "Not started"
           :has-triage-report? false :sessions []}))
      "triage at rest is not needs-you"))
