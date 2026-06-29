(ns nido.work-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.config]
   [nido.coordinator.promote]
   [nido.coordinator.resume]
   [nido.coordinator.runs :as runs]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.workstream :as workstream]
   [nido.notion.views :as views]
   [nido.project]
   [nido.session.lifecycle]
   [nido.work :as work]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest stages-is-the-canonical-spine
  (is (= [:intake :triage :ready :in-progress :shipping :done] work/stages)))

(deftest classify-origin-delegates-to-source-classifier
  (is (= :scratch (work/classify-origin {:stage :scratch :external-refs []})))
  (is (= :notion  (work/classify-origin {:stage :triaging
                                         :external-refs [{:adapter :notion :id "BR-1"}]})))
  (is (= :github  (work/classify-origin {:stage :ready
                                         :external-refs [{:adapter :github-issue :id "o/r#1"}]})))
  (is (= :slack   (work/classify-origin {:stage :triaging
                                         :external-refs [{:adapter :slack-message :id "slack-C1-1.0"}]}))))

(deftest list-workstreams-folds-scratch-into-in-progress
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :scratch :external-refs []})]
        (session/create! :brian (:id w) {:name "poke" :weight :light :autonomy nil}))
      (let [row (first (work/list-workstreams :brian #{"poke"}))]
        (is (= :scratch (:origin row)) "origin preserved")
        (is (= :in-progress (:stage row)) "scratch enters the spine at in-progress")
        (is (nil? (:source row)) ":source is renamed to :origin")))))

(deftest list-workstreams-settled-scratch-reads-done
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :scratch :external-refs []})]
        (session/create! :brian (:id w) {:name "poke" :weight :light :autonomy nil})
        (workstream/close! :brian (:id w) :done))
      (is (= :done (:stage (first (work/list-workstreams :brian))))
          "a closed scratch workstream is :done, not :in-progress"))))

(deftest list-workstreams-preserves-ref-stage
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-9" :title "t"}]})]
        (tickets/open! :brian "BR-9" {:title "t"})
        (tickets/set-status! :brian "BR-9" :triaged)
        (session/create! :brian (:id w) {:name "s" :weight :light :autonomy nil}))
      (let [row (first (work/list-workstreams :brian))]
        (is (= :notion (:origin row)))
        (is (= :ready (:stage row)) "a triaged notion ticket projects to :ready, unchanged")))))

(deftest grouped-folds-scratch-into-in-progress-group
  (with-tmp
    (fn [_]
      ;; a scratch one-off
      (let [s (workstream/create! :brian {:stage :scratch :external-refs []})]
        (session/create! :brian (:id s) {:name "poke" :weight :light :autonomy nil}))
      ;; a triaged notion ticket → :ready
      (let [n (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-3" :title "t"}]})]
        (tickets/open! :brian "BR-3" {:title "t"})
        (tickets/set-status! :brian "BR-3" :triaged)
        (session/create! :brian (:id n) {:name "s" :weight :light :autonomy nil}))
      (let [g (work/grouped :brian #{"poke"})]
        (is (= 1 (count (:ready g))) "the triaged notion ticket is in :ready")
        (is (= 1 (count (:in-progress g))) "the scratch one-off folds into :in-progress")
        (is (= "BR-3 · t" (:label (first (:ready g)))))))))

(def ^:private autonomy-running
  {:skill :triage-bug :first-message "x" :agent :claude :claude-session-id nil
   :trigger :triage-bug :limits {:budget "30m"} :priority 4 :uncapped? false :on-promote nil
   :phase :running :phase-history [{:at "2026-06-01T00:00:00Z" :phase :running}]
   :error nil})

(deftest workstream-detail-presents-sessions-on-the-autonomy-axis
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-5" :title "t"}]})]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-running})
        (session/create! :brian (:id w) {:name "me"   :weight :light :autonomy nil})
        (let [d  (work/workstream :brian (:id w))
              by (into {} (map (juxt :name identity)) (:sessions d))]
          (is (= (:id w) (:ws-id d)) "detail uses the same :ws-id key as list-workstreams rows")
          (is (= :notion (:origin d)))
          (is (= :autonomous (:autonomy-level (by "auto"))))
          (is (= {:budget "30m"} (:brakes (by "auto"))) "brakes = the autonomy :limits")
          (is (= :running (:status (by "auto"))))
          (is (= :interactive (:autonomy-level (by "me"))))
          (is (nil? (:brakes (by "me"))) "interactive sessions carry no brakes")
          (is (= :up (:status (by "me"))) "a live human session reads :up"))))))

(deftest workstream-detail-flags-the-hitl-gate
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "gate" :weight :heavy
                                         :autonomy (assoc autonomy-running :phase :parked)})
        (is (true? (:parked? (first (:sessions (work/workstream :brian (:id w))))))
            "a parked autonomous session is at the HITL gate")))))

(deftest workstream-detail-nil-for-absent
  (with-tmp
    (fn [_]
      (is (nil? (work/workstream :brian "ws-does-not-exist"))))))

(deftest default-target-falls-back-to-in-progress
  (with-redefs [nido.config/read-projects (constantly {})]
    (is (= :in-progress (work/default-target :brian :promote)))
    (is (= :in-progress (work/default-target :brian :new)))))

(deftest default-target-honors-configured-stage
  (with-redefs [nido.config/read-projects
                (constantly {'brian {:workstream-defaults {:promote :ready}}})]
    (is (= :ready (work/default-target :brian :promote))
        "configured target wins, project key matched by name")
    (is (= :in-progress (work/default-target :brian :new))
        "unset action falls back to canonical")))

(deftest default-target-rejects-non-stage-config
  (with-redefs [nido.config/read-projects
                (constantly {'brian {:workstream-defaults {:promote :nonsense}}})]
    (is (= :in-progress (work/default-target :brian :promote))
        "a configured value that isn't a spine stage is ignored")))

(deftest set-stage-done-closes-the-workstream
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (is (= {:decision :done} (work/set-stage! :brian (:id w) :done)))
        (is (some? (:closed (workstream/read-ws :brian (:id w)))))))))

(deftest set-stage-advance-moves-stage-without-a-leg
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (is (= {:decision :advanced} (work/set-stage! :brian (:id w) :ready)))
        (is (= :ready (:stage (workstream/read-ws :brian (:id w)))))))))

(deftest set-stage-in-progress-runs-the-promote-gesture
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})
            calls (atom [])]
        (with-redefs [nido.coordinator.promote/promote-workstream!
                      (fn [p id] (swap! calls conj [p id]) {:decision :promote})]
          (is (= {:decision :promote} (work/set-stage! :brian (:id w) :in-progress)))
          (is (= [[:brian (:id w)]] @calls) "delegates to the shared promote gesture"))))))

(deftest new!-births-a-scratch-workstream-with-its-session
  (with-tmp
    (fn [_]
      (let [ups (atom [])]
        (with-redefs [nido.session.lifecycle/up! (fn [n opts] (swap! ups conj [n opts]) nil)]
          (let [ws-id (work/new! :brian "spike-thing")]
            (is (string? ws-id))
            (is (= [["spike-thing" {:project "brian"}]] @ups) "session brought up via lifecycle")
            (let [w (workstream/read-ws :brian ws-id)]
              (is (= :scratch (work/classify-origin w)) "ref-less scratch workstream")
              (is (= ["spike-thing"]
                     (map :name (session/list-sessions :brian ws-id)))))))))))

(deftest open-target-prefers-the-live-session
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "old"  :weight :light :autonomy nil})
        (session/archive! :brian (:id w) "old")
        (session/create! :brian (:id w) {:name "live" :weight :light :autonomy nil})
        (is (= {:project :brian :session "live"} (work/open-target :brian (:id w)))
            "open lands in the live session, not the archived one")))))

(deftest open-target-nil-when-no-sessions
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (is (nil? (work/open-target :brian (:id w))))))))

(def ^:private parked-autonomy
  {:skill :triage-bug :first-message "x" :agent :claude :claude-session-id "sid"
   :trigger :triage-bug :limits {} :priority 0 :uncapped? false :on-promote nil
   :phase :parked :phase-history [{:at "t" :phase :parked}] :error nil})

(defn- write-run! [id ws-id sname sid]
  (fs/create-dirs (cstate/run-dir id))
  (runs/write-run! {:id id :project :brian :trigger :triage-bug :source {:type :manual}
                    :event-payload {} :skill :triage-bug :first-message "/triage-bug"
                    :agent :claude :session-name sname :workstream-id ws-id
                    :claude-session-id sid :limits {:budget "30m"} :priority 0
                    :session-profile :lite :uncapped? false :state :awaiting-review
                    :state-history [{:at "2026-06-18T00:00:00Z" :state :queued}]
                    :artifacts [] :error nil}))

(deftest reclaimed?-true-when-run-home-absent
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "run-x" :weight :light :autonomy parked-autonomy})
        (write-run! "r1" (:id w) "run-x" "sid")
        (with-redefs [runs/home-present? (constantly false)]
          (is (true? (work/reclaimed? :brian (:id w) "run-x"))
              "a run-owned session whose home was reclaimed is reclaimed?"))))))

(deftest reclaimed?-false-when-home-present
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "run-x" :weight :light :autonomy parked-autonomy})
        (write-run! "r1" (:id w) "run-x" "sid")
        (with-redefs [runs/home-present? (constantly true)]
          (is (false? (work/reclaimed? :brian (:id w) "run-x"))))))))

(deftest reclaimed?-false-when-no-run
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (session/create! :brian (:id w) {:name "manual" :weight :light :autonomy nil})
        (is (false? (work/reclaimed? :brian (:id w) "manual"))
            "a session with no owning run can't be re-hydrated → not reclaimed?")))))

(deftest ensure-open!-rehydrates-reclaimed-run-session
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})
            spawned (atom 0)]
        (session/create! :brian (:id w) {:name "run-x" :weight :light :autonomy parked-autonomy})
        (write-run! "r1" (:id w) "run-x" "sid")
        (with-redefs [runs/home-present? (constantly false)
                      runs/spawn-session-for-run! (fn [_] (swap! spawned inc))]
          (is (true? (work/ensure-open! :brian (:id w) "run-x"))
              "re-provisions and reports it re-hydrated"))
        (is (= 1 @spawned))))))

(deftest ensure-open!-noop-when-home-present
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})
            spawned (atom 0)]
        (session/create! :brian (:id w) {:name "run-x" :weight :light :autonomy parked-autonomy})
        (write-run! "r1" (:id w) "run-x" "sid")
        (with-redefs [runs/home-present? (constantly true)
                      runs/spawn-session-for-run! (fn [_] (swap! spawned inc))]
          (is (false? (work/ensure-open! :brian (:id w) "run-x"))))
        (is (zero? @spawned))))))

(deftest gate-actions-are-stage-derived
  (is (= [{:id :apply   :label "Apply"   :kind :resume :input "apply" :style :primary}
          {:id :dismiss :label "Dismiss" :kind :mutation              :style :danger}
          {:id :reply   :label "Reply"   :kind :resume                :style :default}]
         (work/gate-actions :triage true))
      "a parked triage offers one-click Apply, Dismiss, and free-text Reply")
  (is (= [{:id :dismiss :label "Dismiss" :kind :mutation :style :danger}]
         (work/gate-actions :triage false))
      "an unparked triage can still be dismissed off the radar")
  (is (= [{:id :promote :label "Promote" :kind :mutation :style :primary}
          {:id :drop    :label "Drop"    :kind :mutation :style :danger}]
         (work/gate-actions :ready false)))
  (is (= [{:id :reply :label "Reply" :kind :resume :style :default}
          {:id :done  :label "Done"  :kind :mutation :style :primary}]
         (work/gate-actions :in-progress true)))
  (is (= [] (work/gate-actions :intake true)))
  (is (= [] (work/gate-actions :done true))))

(deftest gates-hydrates-a-parked-triage-workstream
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-7" :title "t"}]})]
        (tickets/open! :brian "BR-7" {:title "t"})
        (tickets/set-status! :brian "BR-7" :investigating)
        (workstream/append-entry! :brian (:id w) {:kind :impl}
                                  "# Verdict\n\nbug — reproduced.")
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked)})
        (let [g (first (work/gates :brian))]
          (is (= (:id w) (:ws-id g)))
          (is (= :notion (:origin g)))
          (is (= :triage (:stage g)))
          (is (= "auto" (:session g)) "the parked session a :reply would resume")
          (is (= [:apply :dismiss :reply] (map :id (:actions g))))
          (is (= :markdown (-> g :report :format)))
          (is (= "Verdict" (-> g :report :title)))
          (is (= "# Verdict\n\nbug — reproduced." (-> g :report :markdown))))))))

(def ^:private slack-edn-report
  "Valid TriageReport EDN for slack triage ledger tests."
  (pr-str {:format :triage-report :ticket-key "slack-C1-1.0" :determination :bug
           :title "Verdict" :summary "bug — slack report."
           :confidence {:level :high :reason "r"}
           :directions [] :notion-writes nil :trail []}))

(deftest gates-hydrates-a-slack-triage-report-from-the-ticket-ledger
  ;; A Slack workstream's triage report is written to the TICKET ledger keyed by
  ;; the slack-message id (not the workstream ledger). The gate must surface it.
  (with-tmp
    (fn [_]
      (let [slack-id "slack-C1-1.0"
            w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :slack-message
                                                           :id slack-id :title "msg"}]})]
        (tickets/open! :brian slack-id {:title "msg"})
        (tickets/set-status! :brian slack-id :awaiting-input)
        (tickets/append-entry! :brian slack-id {:kind :triage}
                               slack-edn-report)
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked)})
        (let [g (first (work/gates :brian))]
          (is (= (:id w) (:ws-id g)))
          (is (= :slack (:origin g)))
          (is (= :triage (:stage g)))
          (is (= :triage-report (-> g :report :format)))
          (is (= "Verdict" (-> g :report :title)))
          (is (= :bug (-> g :report :determination))))))))

(deftest gates-excludes-workstreams-that-do-not-need-you
  (with-tmp
    (fn [_]
      ;; triaged + a non-parked session → stage :ready, needs-you TRUE (ready always)
      (let [r (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-8" :title "t"}]})]
        (tickets/open! :brian "BR-8" {:title "t"})
        (tickets/set-status! :brian "BR-8" :triaged)
        (session/create! :brian (:id r) {:name "s" :weight :light :autonomy nil}))
      (let [g (first (work/gates :brian))]
        (is (= :ready (:stage g)))
        (is (= [:promote :drop] (map :id (:actions g))) "ready gate decides promote/drop")
        (is (nil? (:session g)) "no parked session → nothing to reply to")))))

(deftest gate-detail-nil-for-absent
  (with-tmp
    (fn [_]
      (is (nil? (work/gate :brian "ws-nope"))))))

(deftest all-gates-merges-across-projects
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (workstream/append-entry! :brian (:id w) {:kind :impl} "# X\n\nrep.")
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked)}))
      (with-redefs [nido.project/list-projects
                    (constantly {"brian" {:directory "/tmp/brian"}})]
        (let [gs (work/all-gates)]
          (is (= 1 (count gs)))
          (is (= "brian" (:project (first gs))) "project name threads through to each gate"))))))

(deftest resolve-gate-promote-runs-the-promote-gesture
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})
            calls (atom [])]
        (with-redefs [nido.coordinator.promote/promote-workstream!
                      (fn [p id] (swap! calls conj [p id]) {:decision :promote})]
          (is (= {:decision :promote} (work/resolve-gate! :brian (:id w) :promote)))
          (is (= [[:brian (:id w)]] @calls)))))))

(deftest resolve-gate-dismiss-settles-dropped-and-marks-ticket
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-42"}]})]
        (is (= {:decision :dismissed} (work/resolve-gate! :brian (:id w) :dismiss)))
        (is (= :dropped (:outcome (:closed (workstream/read-ws :brian (:id w))))))
        (is (= :dismissed (tickets/status :brian "BR-42"))
            "dismiss records the off-radar disposition so auto-re-triage skips it")))))

(deftest dismiss-creates-ticket-record-when-absent-and-closes-ws
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-99"}]})]
        ;; never-triaged ticket: no meta record yet
        (is (nil? (tickets/status :brian "BR-99")))
        (is (= {:decision :dismissed} (work/dismiss! :brian (:id w))))
        (is (= :dismissed (tickets/status :brian "BR-99")))
        (is (some? (:closed (workstream/read-ws :brian (:id w)))))))))

(deftest dismiss-marks-slack-ticket-via-ledger-key
  ;; A Slack workstream's ticket is keyed on its slack-message id, not a Notion
  ;; BR-####. dismiss! must stamp :dismissed there too (same ledger key the
  ;; triage report lives under) so the off-radar disposition isn't lost.
  (with-tmp
    (fn [_]
      (let [slack-id "slack-C1-2.0"
            w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :slack-message
                                                           :id slack-id :title "msg"}]})]
        (is (= {:decision :dismissed} (work/dismiss! :brian (:id w))))
        (is (= :dismissed (tickets/status :brian slack-id))
            "dismiss stamps the slack-keyed ticket, not just notion ones")
        (is (some? (:closed (workstream/read-ws :brian (:id w)))))))))

(deftest resolve-gate-done-closes-done
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (work/resolve-gate! :brian (:id w) :done)
        (is (= :done (:outcome (:closed (workstream/read-ws :brian (:id w))))))))))

(deftest resolve-gate-reply-delegates-to-resume
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})
            calls (atom nil)]
        (with-redefs [nido.coordinator.resume/resume!
                      (fn [p id input] (reset! calls [p id input]) {:resumed "auto"})]
          (is (= {:resumed "auto"} (work/resolve-gate! :brian (:id w) :reply "do it")))
          (is (= [:brian (:id w) "do it"] @calls)))))))

(deftest resolve-gate-apply-resumes-with-the-apply-verb
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})
            calls (atom nil)]
        (with-redefs [nido.coordinator.resume/resume!
                      (fn [p id input] (reset! calls [p id input]) {:resumed "auto"})]
          (is (= {:resumed "auto"} (work/resolve-gate! :brian (:id w) :apply)))
          (is (= [:brian (:id w) "apply"] @calls)
              "the Apply button resumes the parked agent with the canned \"apply\" input"))))))

(def ^:private notion-edn-report
  "Valid TriageReport EDN for notion triage ledger tests."
  (pr-str {:format :triage-report :ticket-key "BR-9" :determination :bug
           :title "Verdict" :summary "ticket-ledger report."
           :confidence {:level :high :reason "r"}
           :directions [] :notion-writes nil :trail []}))

(deftest gates-report-falls-back-to-the-ticket-ledger
  (with-tmp
    (fn [_]
      ;; a parked Notion triage gate whose report lives ONLY in the ticket ledger
      ;; (the triage skill writes there, not the workstream ledger)
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-9" :title "t"}]})]
        (tickets/open! :brian "BR-9" {:title "t"})
        (tickets/set-status! :brian "BR-9" :investigating)
        (tickets/append-entry! :brian "BR-9" {:kind :triage} notion-edn-report)
        ;; deliberately NO workstream-level entry
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked)})
        (let [g (first (work/gates :brian))]
          (is (= :triage-report (-> g :report :format)))
          (is (= "Verdict" (-> g :report :title))))))))

(deftest gates-excludes-settled-workstreams
  (with-tmp
    (fn [_]
      ;; a CLOSED workstream with a stale :ready stage-override would otherwise
      ;; project needs-you=true (ready always needs you) and leak into the inbox
      (let [w (workstream/create! :brian {:stage :ready :external-refs []})]
        (workstream/close! :brian (:id w) :done))
      (is (empty? (work/gates :brian))
          "a settled (closed) workstream is never a gate, even with a needs-you stage-override"))))

(deftest gates-surface-the-parked-session-resume-error
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-2" :title "t"}]})]
        (tickets/open! :brian "BR-2" {:title "t"})
        (tickets/set-status! :brian "BR-2" :investigating)
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked
                                           :error {:reason :resume-failed :message "boom"})})
        (let [g (first (work/gates :brian))]
          (is (= :resume-failed (-> g :resume-error :reason)))
          (is (= "boom" (-> g :resume-error :message))))))))

(deftest gates-resume-error-nil-when-clean
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-3" :title "t"}]})]
        (tickets/open! :brian "BR-3" {:title "t"})
        (tickets/set-status! :brian "BR-3" :investigating)
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy :autonomy (assoc autonomy-running :phase :parked)})
        (is (nil? (:resume-error (first (work/gates :brian)))))))))

(deftest gate-actions-inbox
  (is (= [{:id :promote :label "Promote" :kind :mutation :style :primary}
          {:id :drop    :label "Dismiss" :kind :mutation :style :danger}]
         (work/gate-actions :inbox false)))
  (is (= (work/gate-actions :inbox false) (work/gate-actions :inbox true))
      "inbox actions don't depend on parked state"))

(deftest latest-report-falls-back-to-intake-text
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian
                {:stage :inbox
                 :external-refs [{:adapter :slack-message :id "slack-C-1.0"}]
                 :intake {:trigger :triage-slack-bugs
                          :payload {:id "slack-C-1.0" :text "the app crashed on save"}}})
            r (work/latest-report :brian (:id w))]
        (is (= :slack-report (:kind r)))
        (is (= "the app crashed on save" (:markdown r)))))))

(deftest workstream-includes-its-report
  (with-redefs [nido.coordinator.workstream/read-ws (fn [_ _] {:id "x"})
                work/latest-report (fn [_ _] {:kind :triage :at "t" :title "V" :markdown "# V"})
                ;; minimal stubs so workstream's other projections don't throw:
                nido.coordinator.session/list-sessions (fn [_ _] [])
                nido.coordinator.workstreams-view/workstream-row (fn [_ w] {:stage :triage :label "BR-7" :source :notion})]
    (is (= "# V" (-> (work/workstream "brian" "ws-1") :report :markdown)))))

(deftest list-workstreams-rows-carry-facets
  (with-tmp
    (fn [_]
      (workstream/create! :brian {:stage :triaging
                                  :external-refs [{:adapter :notion :id "BR-1"}]
                                  :facets {:app-domain ["Teacher"] :type "bug"}})
      (let [row (first (work/list-workstreams :brian))]
        (is (= {:app-domain ["Teacher"] :type "bug"} (:facets row)))))))

(deftest facet-dimensions-from-config
  (with-tmp
    (fn [_]
      (with-redefs [views/facet-properties (constantly ["App Domain" "Type"])]
        (is (= [:app-domain :type] (work/facet-dimensions :brian)))))))

(deftest facet-values-distinct-plus-unclassified
  (with-tmp
    (fn [_]
      (workstream/create! :brian {:stage :triaging :external-refs [{:adapter :notion :id "BR-1"}]
                                  :facets {:app-domain ["Teacher"]}})
      (workstream/create! :brian {:stage :triaging :external-refs [{:adapter :notion :id "BR-2"}]
                                  :facets {:app-domain ["Student" "Teacher"]}})
      (workstream/create! :brian {:stage :triaging :external-refs [{:adapter :notion :id "BR-3"}]})
      (let [vals (work/facet-values :brian :app-domain)]
        (is (= #{"Teacher" "Student"} (set (remove #{:unclassified} vals)))
            "both present domain values, order-independent")
        (is (= :unclassified (last vals)) ":unclassified is appended last")))))

(deftest facet-match-composes-and-handles-vectors
  (let [row {:facets {:app-domain ["Teacher"] :type "bug"}}]
    (is (work/facet-match? {:app-domain :all :type :all} row) "all passes")
    (is (work/facet-match? {:app-domain "Teacher" :type "bug"} row))
    (is (not (work/facet-match? {:app-domain "Student"} row)))
    (is (work/facet-match? {:app-domain "Teacher"} row) "vector membership"))
  (is (work/facet-match? {:app-domain :unclassified} {:facets {}}) "unclassified matches missing")
  (is (not (work/facet-match? {:app-domain :unclassified} {:facets {:app-domain ["Teacher"]}}))))

(deftest facet-dimensions-is-source-aware
  (with-redefs [views/facet-properties (constantly ["App Domain" "Type"])]
    (is (= [:app-domain :type] (work/facet-dimensions :brian :notion)))
    (is (= [:app-domain :type] (work/facet-dimensions :brian :all)))
    (is (= [] (work/facet-dimensions :brian :slack)))
    (is (= [] (work/facet-dimensions :brian :github)))
    (is (= [:app-domain :type] (work/facet-dimensions :brian)) "1-arity = project-wide (:all)")))

(deftest source-match-honours-all-and-origin
  (is (work/source-match? :all {:origin :slack}))
  (is (work/source-match? :notion {:origin :notion}))
  (is (not (work/source-match? :notion {:origin :slack}))))

(deftest grouped-rows-flattens-all-bands
  (let [g {:inbox [{:id 1}] :triage {:in-flight [{:id 2}] :queued [{:id 3}]}
           :ready [{:id 4}] :in-progress [{:id 5}]}]
    (is (= #{1 2 3 4 5} (set (map :id (work/grouped-rows g)))))))

(deftest filter-grouped-keeps-shape-drops-nonmatching
  (let [g {:inbox [{:origin :notion} {:origin :slack}]
           :triage {:in-flight [{:origin :notion}] :queued [{:origin :slack}]}
           :ready [{:origin :slack}] :in-progress [{:origin :notion}]}
        f (work/filter-grouped g #(= :notion (:origin %)))]
    (is (= [{:origin :notion}] (:inbox f)))
    (is (= [{:origin :notion}] (get-in f [:triage :in-flight])))
    (is (= [] (get-in f [:triage :queued])))
    (is (= [] (:ready f)))
    (is (= [{:origin :notion}] (:in-progress f)))))

(deftest latest-report-prefers-workstream-entries
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :scratch :external-refs []})]
        (workstream/append-entry! :brian (:id w) {:kind :impl} "# First\n\none")
        (workstream/append-entry! :brian (:id w) {:kind :impl} "# Second\n\ntwo")
        (let [r (work/latest-report :brian (:id w))]
          (is (= :markdown (:format r)))
          (is (str/includes? (:markdown r) "Second") "returns the LATEST workstream entry"))))))

(deftest latest-report-falls-back-to-ticket-ledger
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-9"}]})]
        (tickets/open! :brian "BR-9" {:title "t"})
        (tickets/append-entry! :brian "BR-9" {:kind :impl} "# Impl\n\ndid it")
        (let [r (work/latest-report :brian (:id w))]
          (is (str/includes? (:markdown r) "did it")
              "no workstream entries → reads the ticket ledger"))))))

(deftest active-ledger-empty-when-no-entries
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :scratch :external-refs []})]
        (is (= [] (:entries (#'work/active-ledger :brian (:id w))))
            "no entries anywhere → empty vector")))))

(deftest workstream-browses-entries-newest-first
  (with-tmp
    (fn [_]
      (let [id (:id (workstream/create! :brian {:stage :scratch :external-refs []}))]
        (workstream/append-entry! :brian id {:kind :impl} "# One\n\nfirst")
        (workstream/append-entry! :brian id {:kind :impl} "# Two\n\nsecond")
        (workstream/append-entry! :brian id {:kind :impl} "# Three\n\nthird")
        (let [d (work/workstream :brian id)]
          (is (= [3 2 1] (mapv :seq (:entries d))) "index is newest-first")
          (is (= ["Three" "Two" "One"] (mapv :title (:entries d))) "titles from headings")
          (is (= [:impl :impl :impl] (mapv :kind (:entries d))) "kinds carried")
          (is (= 3 (:selected-seq d)) "defaults to the latest entry")
          (is (str/includes? (:markdown (:report d)) "third") "report is the selected entry"))))))

(deftest workstream-selects-requested-entry
  (with-tmp
    (fn [_]
      (let [id (:id (workstream/create! :brian {:stage :scratch :external-refs []}))]
        (workstream/append-entry! :brian id {:kind :impl} "# One\n\nfirst")
        (workstream/append-entry! :brian id {:kind :impl} "# Two\n\nsecond")
        (let [picked (work/workstream :brian id 1)]
          (is (= 1 (:selected-seq picked)))
          (is (str/includes? (:markdown (:report picked)) "first")))
        (let [oob (work/workstream :brian id 99)]
          (is (= 2 (:selected-seq oob)) "out-of-range seq → latest")
          (is (str/includes? (:markdown (:report oob)) "second")))))))

(deftest workstream-single-entry-has-no-index
  (with-tmp
    (fn [_]
      (let [id (:id (workstream/create! :brian {:stage :scratch :external-refs []}))]
        (workstream/append-entry! :brian id {:kind :impl} "# Solo\n\nonly")
        (let [d (work/workstream :brian id)]
          (is (nil? (seq (:entries d))) "a single-entry ledger has no index list")
          (is (str/includes? (:markdown (:report d)) "only")))))))

(deftest workstream-index-title-falls-back-to-first-line
  (with-tmp
    (fn [_]
      (let [id (:id (workstream/create! :brian {:stage :scratch :external-refs []}))]
        (workstream/append-entry! :brian id {:kind :impl} "just prose no heading")
        (workstream/append-entry! :brian id {:kind :impl} "# Has heading\n\nx")
        (is (= ["Has heading" "just prose no heading"]
               (mapv :title (:entries (work/workstream :brian id))))
            "no markdown heading → first non-blank line")))))

(deftest workstream-index-title-uses-event-fields
  (with-tmp
    (fn [_]
      (let [id (:id (workstream/create! :brian {:stage :scratch :external-refs []}))]
        (workstream/append-entry! :brian id {:kind :implementation-plan}
          (pr-str {:format :implementation-plan :summary "x" :direction "First direction" :effort :S}))
        (workstream/append-entry! :brian id {:kind :blocker}
          (pr-str {:format :blocker :summary "y" :needs "Second needs"}))
        (let [d (work/workstream :brian id)]
          (is (= ["Second needs" "First direction"] (mapv :title (:entries d)))
              "index titles come from each event's fields via report/report-title"))))))
