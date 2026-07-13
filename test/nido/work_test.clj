(ns nido.work-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.config]
   [nido.coordinator.facets]
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

(deftest workstream-detail-flags-the-current-ledger-entry
  (with-tmp
    (fn [_]
      (let [w  (workstream/create! :brian {:stage :triaging :external-refs []})
            id (:id w)]
        (workstream/append-entry! :brian id {:kind :note} "first")
        (workstream/append-entry! :brian id {:kind :note} "second")
        ;; default selection lands on the newest entry (seq 2) and is :on-latest?
        (let [d (work/workstream :brian id)]
          (is (= 2 (:selected-seq d)))
          (is (true? (:on-latest? d)) "no explicit selection → viewing the current entry"))
        ;; explicitly viewing the older entry (seq 1) is NOT the current entry
        (is (false? (:on-latest? (work/workstream :brian id 1)))
            "an older ledger entry is not the current entry")
        ;; selecting the newest entry explicitly is still :on-latest?
        (is (true? (:on-latest? (work/workstream :brian id 2))))))))

(deftest workstream-detail-with-no-entries-is-on-latest
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging :external-refs []})]
        (is (true? (:on-latest? (work/workstream :brian (:id w))))
            "an entryless workstream is trivially on its current (only) state")))))

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
  (is (= [{:id :apply   :label "Apply"   :kind :mutation              :style :primary}
          {:id :dismiss :label "Dismiss" :kind :mutation              :style :danger}
          {:id :reply   :label "Reply"   :kind :resume                :style :default}]
         (work/gate-actions :triage true))
      "a parked triage offers one-click Apply (nido mutation), Dismiss, and free-text Reply")
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
          (is (= [:apply :reply] (map :id (:actions g)))
              "Notion origin: Dismiss dropped from the parked triage action bar")
          (is (= :markdown (-> g :report :format)))
          (is (= "Verdict" (-> g :report :title)))
          (is (= "# Verdict\n\nbug — reproduced." (-> g :report :markdown))))))))

(deftest gate-not-working-when-session-failed-not-in-flight
  ;; Regression: a live autonomous session stuck at :failed (e.g. a plan-bug spawn
  ;; failure, whose teardown is a no-op so the session stays :live at :failed) must
  ;; NOT strand a permanent 'working…' on the gate — that hides its actions and the
  ;; ticket looks stuck. resuming? counts only actively-executing phases. Vehicle: a
  ;; parked triage gate (a :failed session alongside is still not in flight).
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-9" :title "t"}]})]
        (tickets/open! :brian "BR-9" {:title "t"})
        (tickets/set-status! :brian "BR-9" :investigating)   ; stays :triage ⇒ a real gate
        (session/create! :brian (:id w)
                         {:name "triage" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked)})
        (session/create! :brian (:id w)
                         {:name "impl" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :failed)})
        (let [g (work/gate :brian (:id w))]
          (is (= :triage (:stage g)))
          (is (false? (:working? g))
              "a live-but-:failed session is NOT in flight ⇒ no stranded 'working…'")
          (is (= [:apply :reply] (map :id (:actions g)))
              "gate actions stay actionable (Notion origin: Dismiss dropped)"))))))

(deftest gate-working-when-session-actively-running
  ;; The honest positive case: a parked triage gate whose agent you resumed is now
  ;; mid-turn (:running) ⇒ working… (gate visible, actions gated until it re-parks).
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-10" :title "t"}]})]
        (tickets/open! :brian "BR-10" {:title "t"})
        (tickets/set-status! :brian "BR-10" :investigating)
        (session/create! :brian (:id w)
                         {:name "triage" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked)})
        (session/create! :brian (:id w)
                         {:name "impl" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :running)})
        (is (true? (:working? (work/gate :brian (:id w))))
            "an actively-running session ⇒ working… (gate visible, actions gated)")))))

(deftest triaged-failed-spawn-is-not-a-gate-but-stays-on-board
  ;; The scenario the two tests above used to cover on a :ready gate: a triaged
  ;; ticket whose impl spawn failed. :ready is no longer a gate, so it drops off the
  ;; Needs-you inbox entirely — no stranded 'working…' possible — and is pulled from
  ;; the board instead, where its Promote/Drop pane actions render unconditionally.
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-11" :title "t"}]})]
        (tickets/open! :brian "BR-11" {:title "t"})
        (tickets/complete! :brian "BR-11" :triaged :applied)   ; ticket :triaged ⇒ :ready
        (session/create! :brian (:id w)
                         {:name "impl" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :failed)})
        (is (nil? (work/gate :brian (:id w))) "a :ready workstream is not a gate")
        (is (= [:promote :drop] (map :id (work/gate-actions :ready false)))
            "board pane still offers promote/drop")))))

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

(deftest gates-excludes-a-ready-workstream
  (with-tmp
    (fn [_]
      ;; triaged + a non-parked session → stage :ready. Ready is a PULL queue: you
      ;; pull it off the board to promote, so it must NOT appear in the needs-you gates.
      (let [r (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-8" :title "t"}]})]
        (tickets/open! :brian "BR-8" {:title "t"})
        (tickets/set-status! :brian "BR-8" :triaged)
        (session/create! :brian (:id r) {:name "s" :weight :light :autonomy nil}))
      (is (empty? (work/gates :brian)) "a :ready workstream is not a gate")
      ;; …but it is still on the spine at :ready with its board actions available.
      (let [row (first (filter #(= :ready (:stage %)) (work/list-workstreams :brian)))]
        (is (some? row) "ready workstream is still on the board")
        (is (= [:promote :drop] (map :id (work/gate-actions :ready false)))
            "and its board pane still offers promote/drop")))))

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

(deftest resolve-gate-apply-finalizes-the-ticket-nido-side
  ;; Apply is a direct nido-side mutation (ticket:complete → :triaged/:applied), NOT a
  ;; claude resume — so it works for legacy pre-notion-cli reviews whose apply
  ;; conversation calls the removed Notion MCP tools (which stranded them in triage).
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-A9" :title "t"}]})
            resumed (atom false)]
        (tickets/open! :brian "BR-A9" {:title "t"})
        (tickets/set-status! :brian "BR-A9" :awaiting-input)
        (with-redefs [nido.coordinator.resume/resume! (fn [& _] (reset! resumed true) {:resumed "x"})
                      nido.coordinator.facets/refresh-for-ticket! (fn [& _] nil)]
          (is (= {:decision :applied} (work/resolve-gate! :brian (:id w) :apply)))
          (is (false? @resumed) "Apply does NOT resume a conversation")
          (is (= :triaged (tickets/status :brian "BR-A9"))
              "Apply finalizes the ticket :triaged nido-side ⇒ it leaves :triage")
          (is (= :applied (:disposition (tickets/read-meta :brian "BR-A9")))))))))

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
      ;; a CLOSED workstream with a stale :ready stage-override must still fold to
      ;; :done and stay out of the inbox — closed wins over any stage-override
      (let [w (workstream/create! :brian {:stage :ready :external-refs []})]
        (workstream/close! :brian (:id w) :done))
      (is (empty? (work/gates :brian))
          "a settled (closed) workstream is never a gate, even with a :ready stage-override"))))

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

(deftest gate-actions-incoming
  (is (= [{:id :promote :label "Promote" :kind :mutation :style :primary}
          {:id :drop    :label "Dismiss" :kind :mutation :style :danger}]
         (work/gate-actions :incoming false)))
  (is (= (work/gate-actions :incoming false) (work/gate-actions :incoming true))
      "incoming actions don't depend on parked state"))

(deftest latest-report-falls-back-to-intake-text
  (with-tmp
    (fn [_]
      (let [w (workstream/create! :brian
                {:stage :incoming
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

(deftest shipping-gate-actions
  (is (= #{:reply :drop} (set (map :id (work/gate-actions :shipping true)))))
  (is (= [] (work/gate-actions :shipping false))))

(deftest source-match-honours-origin
  ;; No cross-source :all anymore — a row matches iff its origin is the source.
  (is (work/source-match? :notion {:origin :notion}))
  (is (not (work/source-match? :notion {:origin :slack})))
  (is (work/source-match? :slack {:origin :slack})))

(deftest grouped-rows-flattens-all-bands
  (let [g {:incoming [{:id 1}] :triage {:in-flight [{:id 2}] :queued [{:id 3}]}
           :ready [{:id 4}] :in-progress [{:id 5}]}]
    (is (= #{1 2 3 4 5} (set (map :id (work/grouped-rows g)))))))

(deftest filter-grouped-keeps-shape-drops-nonmatching
  (let [g {:incoming [{:origin :notion} {:origin :slack}]
           :triage {:in-flight [{:origin :notion}] :queued [{:origin :slack}]}
           :ready [{:origin :slack}] :in-progress [{:origin :notion}]}
        f (work/filter-grouped g #(= :notion (:origin %)))]
    (is (= [{:origin :notion}] (:incoming f)))
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

(deftest screen-overview-and-detail-groups-are-identical
  ;; The same view-state must produce the same filtered :groups regardless of
  ;; whether a selection is present (the overview vs detail bug).
  (let [grouped {:incoming [] :triage {:in-flight [] :queued []}
                 :ready [{:ws-id "r1" :origin :notion :facets {} :stage :ready}]
                 :in-progress [{:ws-id "p1" :origin :github :facets {} :stage :in-progress}]
                 :shipping []}
        groups [{:project "brian" :grouped grouped}]
        vs-over {:surface :workstreams :scope "all" :source :notion :facets {} :selection nil}
        vs-det  (assoc vs-over :selection {:project "brian" :ws-id "r1"})
        g1 (:groups (work/screen vs-over {:groups groups :gates [] :pending #{}}))
        g2 (:groups (work/screen vs-det  {:groups groups :gates [] :pending #{}}))]
    (is (= g1 g2) "selection must not change the filtered list")
    (is (= ["r1"] (map :ws-id (get-in (first g1) [:grouped :ready]))) "notion row kept")
    (is (empty? (get-in (first g1) [:grouped :in-progress])) "github row filtered out")))

(deftest screen-source-counts-include-incoming-under-its-source
  ;; No cross-source All view anymore: a source's chip counts ALL its rows,
  ;; including its :incoming holding-pen (which is only ever shown under that
  ;; source), and that count equals the rows actually visible under it.
  (let [grouped {:incoming [{:ws-id "i1" :origin :notion :facets {} :stage :incoming}]
                 :triage {:in-flight [] :queued []}
                 :ready [{:ws-id "r1" :origin :notion :facets {} :stage :ready}]
                 :in-progress [] :shipping []}
        groups [{:project "brian" :grouped grouped}]
        vs {:surface :workstreams :scope "all" :source :notion :facets {} :selection nil}
        s (work/screen vs {:groups groups :gates [] :pending #{}})
        visible-notion (->> (:groups s) (mapcat #(work/grouped-rows (:grouped %)))
                            (filter #(= :notion (:origin %))) count)]
    (is (= 2 (get-in s [:source-counts :notion])) "notion chip counts ready + incoming")
    (is (= 2 visible-notion) "and that equals the notion rows actually visible")))

(deftest screen-passes-injected-facet-dims-through
  (let [s (work/screen {:surface :workstreams :scope "all" :source :notion :facets {}}
                       {:groups [] :gates [] :pending #{} :facet-dims [:app-domain :type]})]
    (is (= [:app-domain :type] (:facet-dims s)) "facet-dims come from injected data, not disk")))

(deftest screen-source-counts-under-selected-source
  ;; With a specific source selected, the SELECTED chip's count matches the rows
  ;; visible for that source (incoming included, since a non-:all source shows its
  ;; own holding-pen). Other-origin chips show switch-potential.
  (let [grouped {:incoming [{:ws-id "ni" :origin :notion :facets {} :stage :incoming}
                            {:ws-id "gi" :origin :github :facets {} :stage :incoming}]
                 :triage {:in-flight [] :queued []}
                 :ready [{:ws-id "nr" :origin :notion :facets {} :stage :ready}]
                 :in-progress [] :shipping []}
        groups [{:project "brian" :grouped grouped}]
        s (work/screen {:surface :workstreams :scope "all" :source :notion :facets {} :selection nil}
                       {:groups groups :gates [] :pending #{}})
        visible-notion (->> (:groups s) (mapcat #(work/grouped-rows (:grouped %)))
                            (filter #(= :notion (:origin %))) count)]
    (is (= 2 (get-in s [:source-counts :notion])) "selected notion chip counts both notion rows (incl incoming)")
    (is (= 2 visible-notion) "and that equals the notion rows actually visible under source=notion")
    (is (= 1 (get-in s [:source-counts :github])) "github chip shows its switch-potential")))

(deftest screen-needs-count-equals-gate-count
  (let [gates [{:ws-id "a" :project "brian"} {:ws-id "b" :project "brian"}]
        s (work/screen {:surface :needs :scope "all" :source :notion :facets {}}
                       {:groups [] :gates gates :pending #{}})]
    (is (= 2 (:needs-count s)))
    (is (= 2 (count (:gates s))))))

(deftest screen-marks-pending-gates-working
  (let [gates [{:ws-id "a" :project "brian" :working? false}
               {:ws-id "b" :project "brian" :working? true}]
        s (work/screen {:surface :needs :scope "all" :source :notion :facets {}}
                       {:groups [] :gates gates :pending #{"brian/a"}})]
    (is (true? (:pending? (first (filter #(= "a" (:ws-id %)) (:gates s)))))
        "optimistic bridge key marks pending")
    (is (true? (:pending? (first (filter #(= "b" (:ws-id %)) (:gates s)))))
        "a running (resumed) session marks pending")))

(deftest screen-scope-filters-both-groups-and-gates
  (let [groups [{:project "brian" :grouped {:incoming [] :triage {:in-flight [] :queued []}
                                            :ready [{:ws-id "r1" :origin :notion :facets {} :stage :ready}]
                                            :in-progress [] :shipping []}}
                {:project "foo" :grouped {:incoming [] :triage {:in-flight [] :queued []}
                                          :ready [{:ws-id "r2" :origin :notion :facets {} :stage :ready}]
                                          :in-progress [] :shipping []}}]
        gates [{:ws-id "r1" :project "brian"} {:ws-id "r2" :project "foo"}]
        s (work/screen {:surface :workstreams :scope "brian" :source :notion :facets {} :selection nil}
                       {:groups groups :gates gates :pending #{}})]
    (is (= ["brian"] (map :project (:groups s))))
    (is (= ["r1"] (map :ws-id (:gates s))))))

(deftest triage-dismiss-dropped-for-notion-kept-for-slack
  (is (not (some #{:dismiss} (map :id (work/gate-actions :triage true :notion))))
      "Notion parked triage: no Dismiss")
  (is (some #{:dismiss} (map :id (work/gate-actions :triage true :slack)))
      "Slack parked triage: Dismiss kept")
  (is (= [] (work/gate-actions :triage false :notion))
      "Notion unparked triage: no actions")
  (is (= [:dismiss] (map :id (work/gate-actions :triage false :slack)))
      "Slack unparked triage: Dismiss")
  (is (some #{:dismiss} (map :id (work/gate-actions :triage true)))
      "2-arity (origin nil) unchanged: Dismiss present"))
