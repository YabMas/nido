(ns nido.work-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.config]
   [nido.coordinator.promote]
   [nido.coordinator.resume]
   [nido.coordinator.session :as session]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.workstream :as workstream]
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
  (is (= [:intake :triage :ready :in-progress :done] work/stages)))

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

(deftest gate-actions-are-stage-derived
  (is (= [{:id :skip  :label "Skip"  :kind :mutation}
          {:id :reply :label "Reply" :kind :reply}]
         (work/gate-actions :triage true)))
  (is (= [] (work/gate-actions :triage false)) "an unparked triage offers nothing")
  (is (= [{:id :promote :label "Promote" :kind :mutation}
          {:id :drop    :label "Drop"    :kind :mutation}]
         (work/gate-actions :ready false)) "ready always decides, parked or not")
  (is (= [{:id :reply :label "Reply" :kind :reply}
          {:id :done  :label "Done"  :kind :mutation}]
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
        (workstream/append-entry! :brian (:id w) {:kind :triage}
                                  "# Verdict\n\nbug — reproduced.")
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked)})
        (let [g (first (work/gates :brian))]
          (is (= (:id w) (:ws-id g)))
          (is (= :notion (:origin g)))
          (is (= :triage (:stage g)))
          (is (= "auto" (:session g)) "the parked session a :reply would resume")
          (is (= [:skip :reply] (map :id (:actions g))))
          (is (= :triage (-> g :report :kind)))
          (is (= "Verdict" (-> g :report :title)))
          (is (= "# Verdict\n\nbug — reproduced." (-> g :report :markdown))))))))

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
        (workstream/append-entry! :brian (:id w) {:kind :triage} "# X\n\nrep.")
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

(deftest resolve-gate-skip-and-drop-settle-dropped
  (with-tmp
    (fn [_]
      (let [a (workstream/create! :brian {:stage :triaging :external-refs []})
            b (workstream/create! :brian {:stage :triaging :external-refs []})]
        (work/resolve-gate! :brian (:id a) :skip)
        (work/resolve-gate! :brian (:id b) :drop)
        (is (= :dropped (:outcome (:closed (workstream/read-ws :brian (:id a))))))
        (is (= :dropped (:outcome (:closed (workstream/read-ws :brian (:id b))))))))))

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

(deftest gates-report-falls-back-to-the-ticket-ledger
  (with-tmp
    (fn [_]
      ;; a parked Notion triage gate whose report lives ONLY in the ticket ledger
      ;; (the triage skill writes there, not the workstream ledger)
      (let [w (workstream/create! :brian {:stage :triaging
                                          :external-refs [{:adapter :notion :id "BR-9" :title "t"}]})]
        (tickets/open! :brian "BR-9" {:title "t"})
        (tickets/set-status! :brian "BR-9" :investigating)
        (tickets/append-entry! :brian "BR-9" {:kind :triage} "# Verdict\n\nticket-ledger report.")
        ;; deliberately NO workstream-level entry
        (session/create! :brian (:id w)
                         {:name "auto" :weight :heavy
                          :autonomy (assoc autonomy-running :phase :parked)})
        (let [g (first (work/gates :brian))]
          (is (= :triage (-> g :report :kind)))
          (is (= "Verdict" (-> g :report :title)))
          (is (= "# Verdict\n\nticket-ledger report." (-> g :report :markdown))
              "report read from the ticket ledger when the workstream ledger is empty"))))))
