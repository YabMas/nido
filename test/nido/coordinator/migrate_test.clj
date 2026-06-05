(ns nido.coordinator.migrate-test
  (:require
   [clojure.test :refer [deftest is]]
   [malli.core :as m]
   [nido.coordinator.migrate :as migrate]
   [nido.coordinator.session :as sess]
   [nido.coordinator.workstream :as ws]))

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
