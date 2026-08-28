(ns nido.coordinator.migrate-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]
   [nido.coordinator.migrate :as migrate]
   [nido.coordinator.session :as sess]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.tickets :as tickets]
   [nido.coordinator.workstream :as ws]
   [nido.platform.io :as io]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [cstate/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(def old-ticket
  {:br-id "BR-4659"
   :status :triaged
   :notion-page-id "p" :url "u" :title "Firefox loading"
   :disposition :applied
   :triaged-at "2026-06-04T10:00:00Z"
   :entries [{:seq 1 :kind :triage :session "run-x" :at "2026-06-04T09:00:00Z"
              :file "entries/0001-triage.md"}]})

(def old-run
  {:id "2026-06-04-brian-triage-bug-aaa111"
   :project :brian
   :trigger :triage-bug
   :source {:type :notion-view}
   :event-payload {:id "BR-4659" :url "u"}
   :skill :triage-bug
   :first-message "/triage-bug BR-4659"
   :agent :claude
   :session-name "run-2026-06-04-triage-bug-aaa111"
   :claude-session-id "uuid-1"
   :limits {:budget "30m" :max-failures 3}
   :priority 0
   :session-profile :lite
   :on-promote nil
   :uncapped? false
   :state :done
   :state-history [{:at "2026-06-04T09:00:00Z" :state :queued}
                   {:at "2026-06-04T09:01:00Z" :state :running}
                   {:at "2026-06-04T09:10:00Z" :state :done}]
   :artifacts []
   :error nil})

(deftest ticket->workstream-maps-status-to-stage-and-ref
  (let [w (migrate/ticket->workstream :brian old-ticket "ws-fixed-1")]
    (is (m/validate ws/Workstream w))
    (is (= "ws-fixed-1" (:id w)))
    (is (= :triaged (:stage w)))
    (is (= [{:adapter :notion :id "BR-4659"
             :page-id "p" :url "u" :title "Firefox loading"}]
           (:external-refs w)))
    (is (= (:entries old-ticket) (:entries w)))
    (is (nil? (:closed w)))))

(deftest ticket->workstream-closes-skipped
  (let [w (migrate/ticket->workstream :brian (assoc old-ticket :status :skipped) "ws-2")]
    (is (= :dropped (get-in w [:closed :outcome])))))

(deftest run->session-maps-profile-to-weight-and-state-to-phase
  (let [{:keys [ws-id session]} (migrate/run->session old-run)]
    (is (= "BR-4659" ws-id))
    (is (m/validate sess/Session session))
    (is (= "run-2026-06-04-triage-bug-aaa111" (:name session)))
    (is (= :light (:weight session)))
    (is (= :archived (:substrate session)))
    (is (= :done (get-in session [:autonomy :phase])))
    (is (= :triage-bug (get-in session [:autonomy :trigger])))
    (is (= "uuid-1" (get-in session [:autonomy :claude-session-id])))))

(deftest run->session-parks-awaiting-review
  (let [{:keys [session]} (migrate/run->session (assoc old-run :state :awaiting-review))]
    (is (= :live (:substrate session)))
    (is (= :parked (get-in session [:autonomy :phase])))))

(deftest run->session-without-br-gets-synthetic-ws-id
  (let [{:keys [ws-id]} (migrate/run->session
                          (assoc old-run :event-payload {:url "u"}))]
    (is (= (str "ws-from-run-" (:id old-run)) ws-id))))

(deftest ticket->workstream-defaults-missing-status-to-investigating
  ;; A ticket with no :status maps to the canonical :investigating stage (the
  ;; value open! would have written), not an invented :investigation.
  (let [w (migrate/ticket->workstream :brian (dissoc old-ticket :status) "ws-3")]
    (is (m/validate ws/Workstream w))
    (is (= :investigating (:stage w)))
    (is (= :investigating (:stage (last (:stage-history w)))))))

(deftest run->session-terminal-substrate-history-is-two-entries
  ;; A terminal run was live during its burst then archived — its
  ;; substrate-history reflects both, rather than a fabricated archived-at-birth.
  (let [{:keys [session]} (migrate/run->session old-run)
        sh (:substrate-history session)]
    (is (m/validate sess/Session session))
    (is (= [{:at "2026-06-04T09:00:00Z" :substrate :live}
            {:at "2026-06-04T09:10:00Z" :substrate :archived}]
           sh))))

(deftest run->session-awaiting-review-substrate-history-is-one-live-entry
  (let [{:keys [session]} (migrate/run->session (assoc old-run :state :awaiting-review))]
    (is (= [{:at "2026-06-04T09:00:00Z" :substrate :live}]
           (:substrate-history session)))))

(deftest run->session-handles-empty-state-history
  ;; A legacy run with no state-history must still produce a schema-valid session.
  (let [{:keys [session]} (migrate/run->session
                            (assoc old-run :state-history []))]
    (is (m/validate sess/Session session))
    (is (= [] (get-in session [:autonomy :phase-history])))))

(defn- seed-legacy! [tmp]
  (io/write-edn! (str (fs/path tmp "projects" "brian" "tickets" "BR-4659" "meta.edn"))
                 old-ticket)
  (io/write-edn! (str (fs/path tmp "runs" (:id old-run) "run.edn"))
                 old-run))

(deftest run-once-migrates-ticket-and-run-then-archives
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (seed-legacy! tmp)
        (let [report (migrate/run-once! :brian)]
          (is (= 1 (:workstreams report)))
          (is (= 1 (:sessions report)))
          (let [w (ws/find-by-ref :brian :notion "BR-4659")]
            (is (some? w))
            (is (= :triaged (:stage w)))
            (let [ss (sess/list-sessions :brian (:id w))]
              (is (= 1 (count ss)))
              (is (= (:id w) (:workstream-id (first ss))))
              (is (= :done (get-in (first ss) [:autonomy :phase])))))
          (is (fs/exists? (str (fs/path (cstate/pre-unification-dir :brian) "tickets"))))
          ;; the converted run dir is moved out of the global runs/ and archived
          ;; under _pre-unification/runs/<run-id> (the global runs/ dir itself stays).
          (is (not (fs/exists? (cstate/run-dir (:id old-run)))))
          (is (fs/exists? (str (fs/path (cstate/pre-unification-dir :brian)
                                        "runs" (:id old-run)))))))
      (finally (fs/delete-tree tmp)))))

(deftest run-once-archives-only-the-given-projects-runs
  ;; ~/.nido/runs/ is global; migrating :brian must NOT sweep away another
  ;; project's runs (regression guard for the per-run-dir archive).
  (let [tmp      (fs/create-temp-dir)
        acme-run (assoc old-run :id "acme-run-1" :project :acme :event-payload {:url "u"})]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (seed-legacy! tmp)
        (io/write-edn! (str (fs/path tmp "runs" (:id acme-run) "run.edn")) acme-run)
        (migrate/run-once! :brian)
        ;; acme's run is untouched — still in the global runs dir, not in brian's archive
        (is (fs/exists? (cstate/run-dir (:id acme-run))))
        (is (not (fs/exists? (str (fs/path (cstate/pre-unification-dir :brian)
                                           "runs" (:id acme-run))))))
        ;; brian's run WAS archived
        (is (not (fs/exists? (cstate/run-dir (:id old-run))))))
      (finally (fs/delete-tree tmp)))))

(deftest run-once-does-not-duplicate-an-existing-workstream-for-a-ticket
  ;; A pre-existing workstream carrying the ticket's BR ref (e.g. from a prior
  ;; partial run) is reused via find-by-ref, not re-minted — no duplicate.
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (seed-legacy! tmp)
        (ws/write! (migrate/ticket->workstream :brian old-ticket "ws-pre-existing"))
        (migrate/run-once! :brian)
        (let [matches (->> (ws/list-ids :brian)
                           (keep #(ws/read-ws :brian %))
                           (filter (fn [w] (some #(= "BR-4659" (:id %)) (:external-refs w)))))]
          (is (= 1 (count matches)))
          (is (= "ws-pre-existing" (:id (first matches))))))
      (finally (fs/delete-tree tmp)))))

(deftest run-once-is-idempotent
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (seed-legacy! tmp)
        (migrate/run-once! :brian)
        (let [again (migrate/run-once! :brian)]
          (is (= 0 (:workstreams again)))
          (is (= 0 (:sessions again)))))
      (finally (fs/delete-tree tmp)))))

(defn- auton [skill phase]
  {:skill skill :first-message "m" :agent :claude :claude-session-id nil
   :trigger :t :limits {} :priority 0 :uncapped? false :on-promote nil
   :phase phase :phase-history [] :error nil})

(deftest archive-orphaned-live-archives-terminal-triage-but-not-plan-bug
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (let [w (ws/create! :brian {:stage :triaging})]
          (sess/create! :brian (:id w) {:name "done-triage" :weight :light
                                        :autonomy (auton :triage-bug :done)})
          (sess/create! :brian (:id w) {:name "running-triage" :weight :light
                                        :autonomy (auton :triage-bug :running)})
          (sess/create! :brian (:id w) {:name "impl-handed" :weight :light
                                        :autonomy (auton :plan-bug :done)})
          (let [n (migrate/archive-orphaned-live! :brian)]
            (is (= 1 n) "only the terminal triage session is archived")
            (is (= :archived (:substrate (sess/read-session :brian (:id w) "done-triage"))))
            (is (= :live (:substrate (sess/read-session :brian (:id w) "running-triage"))))
            (is (= :live (:substrate (sess/read-session :brian (:id w) "impl-handed")))
                "plan-bug session is the human's workspace — never archived"))))
      (finally (fs/delete-tree tmp)))))

;; --- ledger->workstreams! -----------------------------------------------

(deftest ledger-migration-copies-ticket-entries-into-workstreams
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :triaging :external-refs [{:adapter :notion :id "BR-1"}]})]
        ;; legacy: an entry in the ticket store, none on the workstream
        (tickets/write-meta! :brian "BR-1"
          {:br-id "BR-1" :status :triaged :entries [{:kind :note :seq 1 :at "t" :file "entries/0001-note.md"}]})
        (io/write-text! (str (fs/path (tickets/ticket-dir :brian "BR-1") "entries" "0001-note.md"))
                        "legacy body")
        ;; an orphan: entries, no workstream
        (tickets/write-meta! :brian "BR-orphan"
          {:br-id "BR-orphan" :status :triaged :entries [{:kind :note :seq 1 :at "t" :file "entries/0001-note.md"}]})
        (let [res (migrate/ledger->workstreams! :brian)]
          (is (= 1 (:migrated res)))
          (is (= 1 (:orphans res)))
          (is (= 0 (:failed-entries res)))
          (let [w2 (ws/read-ws :brian (:id w))]
            (is (= 1 (count (:entries w2)))
                "the ticket entry now lives on the workstream")
            (is (= :note (-> w2 :entries first :kind)))))))))

(deftest ledger-migration-skips-a-ticket-whose-workstream-already-has-entries
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :triaging :external-refs [{:adapter :notion :id "BR-2"}]})]
        (tickets/write-meta! :brian "BR-2"
          {:br-id "BR-2" :status :triaged :entries [{:kind :note :seq 1 :at "t" :file "entries/0001-note.md"}]})
        (io/write-text! (str (fs/path (tickets/ticket-dir :brian "BR-2") "entries" "0001-note.md"))
                        "legacy body")
        ;; first run migrates it onto the workstream
        (let [first-res (migrate/ledger->workstreams! :brian)]
          (is (= 1 (:migrated first-res)))
          (is (= 0 (:orphans first-res)))
          (is (= 0 (:failed-entries first-res))))
        ;; a second run must not double-append — the workstream already carries entries
        (let [second-res (migrate/ledger->workstreams! :brian)]
          (is (= 0 (:migrated second-res)))
          (is (= 0 (:orphans second-res)))
          (is (= 0 (:failed-entries second-res))))
        (is (= 1 (count (:entries (ws/read-ws :brian (:id w)))))
            "re-running the migration never duplicates the entry")))))

(deftest ledger-migration-catches-a-legacy-entry-that-no-longer-validates
  ;; report/entry-payload re-validates a typed kind (e.g. :triage) on append; a
  ;; legacy body that fails that validation must abandon just THAT ENTRY, not
  ;; throw and abort the whole run. Per-entry model: the ticket DID have a
  ;; workstream and WAS processed, so it counts as :migrated (not :orphans)
  ;; even though its only entry landed 0 entries — :failed-entries is where
  ;; the failure is visible. (Contrast with the true-orphan case above, where
  ;; there is no workstream at all.)
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :triaging :external-refs [{:adapter :notion :id "BR-bad"}]})]
        (tickets/write-meta! :brian "BR-bad"
          {:br-id "BR-bad" :status :triaged
           :entries [{:kind :triage :seq 1 :at "t" :file "entries/0001-triage.md"}]})
        (io/write-text! (str (fs/path (tickets/ticket-dir :brian "BR-bad") "entries" "0001-triage.md"))
                        "not a valid triage report")
        (let [res (migrate/ledger->workstreams! :brian)]
          (is (= 1 (:migrated res)))
          (is (= 0 (:orphans res)))
          (is (= 1 (:failed-entries res)))
          (is (= 0 (count (:entries (ws/read-ws :brian (:id w)))))
              "the failed entry's workstream gains no entry"))))))

(deftest ledger-migration-lands-earlier-entries-when-a-later-entry-fails
  ;; A multi-entry ticket where entry 2 fails re-validation must NOT strand
  ;; entry 1 (already appended non-transactionally by ws/append-entry!) nor
  ;; mislabel the ticket as an orphan — this is the Important-fix regression
  ;; guard: the old whole-ticket try/catch would have counted this ticket as
  ;; an :orphan (wrong — it HAS a workstream) while leaving entry 1 already
  ;; written, and the idempotency guard would then have permanently skipped
  ;; ever copying entry 2's replacement/successor on any re-run.
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :triaging :external-refs [{:adapter :notion :id "BR-multi"}]})]
        (tickets/write-meta! :brian "BR-multi"
          {:br-id "BR-multi" :status :triaged
           :entries [{:kind :note :seq 1 :at "t1" :file "entries/0001-note.md"}
                     {:kind :triage :seq 2 :at "t2" :file "entries/0002-triage.md"}]})
        (io/write-text! (str (fs/path (tickets/ticket-dir :brian "BR-multi") "entries" "0001-note.md"))
                        "legacy body one")
        (io/write-text! (str (fs/path (tickets/ticket-dir :brian "BR-multi") "entries" "0002-triage.md"))
                        "not a valid triage report")
        (let [res (migrate/ledger->workstreams! :brian)]
          (is (= 1 (:migrated res)) "the ticket is migrated, not orphaned")
          (is (= 0 (:orphans res)))
          (is (= 1 (:failed-entries res)))
          (let [w2 (ws/read-ws :brian (:id w))]
            (is (= 1 (count (:entries w2)))
                "the first entry landed even though the second failed")
            (is (= :note (-> w2 :entries first :kind)))))))))
