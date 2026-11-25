(ns nido.coordinator.record.session-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.session :as sess]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]))


(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))
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
      (with-redefs [core/nido-root (constantly (str tmp))]
        (sess/write! autonomous-session)
        (is (= autonomous-session
               (sess/read-session :brian "ws-1" "run-triage-x"))))
      (finally (fs/delete-tree tmp)))))

(deftest read-session-returns-nil-when-missing
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (is (nil? (sess/read-session :brian "ws-1" "no-such"))))
      (finally (fs/delete-tree tmp)))))

(deftest create-seeds-substrate-history
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
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
      (with-redefs [core/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        (sess/create! :brian "ws-1" {:name "a" :weight :light :autonomy nil})
        (sess/create! :brian "ws-1" {:name "b" :weight :heavy :autonomy nil})
        (is (= #{"a" "b"} (set (map :name (sess/list-sessions :brian "ws-1"))))))
      (finally (fs/delete-tree tmp)))))

(deftest archive-flips-substrate-and-appends-history
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T13:00:00Z")]
        (sess/write! human-session)
        (let [a (sess/archive! :brian "ws-1" "explore-firefox")]
          (is (= :archived (:substrate a)))
          (is (= {:at "2026-06-05T13:00:00Z" :substrate :archived}
                 (last (:substrate-history a))))))
      (finally (fs/delete-tree tmp)))))

(deftest a-session-written-with-a-burst-phase-sheds-it
  ;; Where a session's agent is lives on its run; a record written before that keeps no copy
  ;; once anything rewrites it.
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (sess/write! (update autonomous-session :autonomy assoc
                             :phase :parked :phase-history [{:at "t" :phase :parked}]))
        (let [s (sess/read-session :brian "ws-1" "run-triage-x")]
          (is (not (contains? (:autonomy s) :phase)))
          (is (not (contains? (:autonomy s) :phase-history)))))
      (finally (fs/delete-tree tmp)))))

(deftest predicates
  (is (sess/live? human-session))
  (is (not (sess/live? (assoc human-session :substrate :archived))))
  (is (sess/autonomous? autonomous-session))
  (is (not (sess/autonomous? human-session))))

(deftest archive-is-idempotent
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T13:00:00Z")]
        (sess/write! human-session)
        (sess/archive! :brian "ws-1" "explore-firefox")
        (let [again (sess/archive! :brian "ws-1" "explore-firefox")]
          (is (= :archived (:substrate again)))
          ;; live + archived only — second archive adds no duplicate entry.
          (is (= 2 (count (:substrate-history again))))))
      (finally (fs/delete-tree tmp)))))

(deftest list-sessions-round-trips-a-slash-containing-name
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:external-refs []})]
        (sess/create! :brian (:id w)
                      {:name "feat/course-materials-tab" :weight :light :autonomy nil})
        (is (= ["feat/course-materials-tab"]
               (mapv :name (sess/list-sessions :brian (:id w))))
            "a '/'-containing session name round-trips through list-sessions")))))

(deftest list-sessions-still-handles-flat-names
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:external-refs []})]
        (sess/create! :brian (:id w) {:name "refshot" :weight :light :autonomy nil})
        (is (= ["refshot"] (mapv :name (sess/list-sessions :brian (:id w)))))))))

(deftest workstream-id-for-finds-the-owning-ws
  (with-tmp
    (fn [_]
      (let [ws1 (ws/create! :brian {:external-refs []})
            ws2 (ws/create! :brian {:external-refs []})]
        (sess/create! :brian (:id ws1) {:name "impl-br-1" :weight :heavy :autonomy nil})
        (sess/create! :brian (:id ws2) {:name "impl-br-2" :weight :heavy :autonomy nil})
        (is (= (:id ws2) (sess/workstream-id-for :brian "impl-br-2")))
        (is (nil? (sess/workstream-id-for :brian "nope")))))))

(defn- write-behind-the-rule!
  "A session record put on disk the way one could exist from before record-session kept a name to
   one workstream — straight to its path, past write!."
  [project ws-id session-name]
  (io/write-edn! (cstate/session-edn-path project ws-id session-name)
                 (assoc human-session :name session-name :workstream-id ws-id :project project)))

(deftest create-refuses-a-name-another-workstream-holds
  (with-tmp
    (fn [_]
      (let [ws1 (ws/create! :brian {:external-refs []})
            ws2 (ws/create! :brian {:external-refs []})]
        (sess/create! :brian (:id ws1) {:name "shared" :weight :light :autonomy nil})
        (let [e (try (sess/create! :brian (:id ws2) {:name "shared" :weight :light :autonomy nil})
                     nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= :name-held (:reason (ex-data e))))
          (is (= (:id ws1) (:holder (ex-data e)))))
        (is (nil? (sess/read-session :brian (:id ws2) "shared")) "nothing written on the refused workstream")
        (is (= (:id ws1) (sess/workstream-id-for :brian "shared")))))))

(deftest a-name-held-in-another-project-is-no-conflict
  (with-tmp
    (fn [_]
      (let [a (ws/create! :brian {:external-refs []})
            b (ws/create! :nido {:external-refs []})]
        (sess/create! :brian (:id a) {:name "same" :weight :light :autonomy nil})
        (is (some? (sess/create! :nido (:id b) {:name "same" :weight :light :autonomy nil})))))))

(deftest rewriting-an-existing-record-is-not-a-creation
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:external-refs []})
            s (sess/create! :brian (:id w) {:name "x" :weight :light :autonomy nil})]
        (sess/write! (assoc s :weight :heavy))
        (is (= :heavy (:weight (sess/read-session :brian (:id w) "x"))))
        (is (some? (sess/create! :brian (:id w) {:name "x" :weight :light :autonomy nil}))
            "re-creating on the workstream that holds it is not a second holder")))))

(deftest concurrent-creations-of-one-name-leave-one-holder
  (with-tmp
    (fn [_]
      (let [ids (vec (repeatedly 6 #(:id (ws/create! :brian {:external-refs []}))))
            results (->> ids
                         (mapv (fn [id] (future (try (sess/create! :brian id {:name "race" :weight :light :autonomy nil})
                                                     :created
                                                     (catch clojure.lang.ExceptionInfo _ :refused)))))
                         (mapv deref))]
        (is (= 1 (count (filter #{:created} results))))
        (is (string? (sess/workstream-id-for :brian "race")))))))

(deftest workstream-id-for-refuses-a-name-held-twice
  (with-tmp
    (fn [_]
      (let [ws1 (ws/create! :brian {:external-refs []})
            ws2 (ws/create! :brian {:external-refs []})]
        (write-behind-the-rule! :brian (:id ws1) "dup")
        (write-behind-the-rule! :brian (:id ws2) "dup")
        (let [e (try (sess/workstream-id-for :brian "dup") nil
                     (catch clojure.lang.ExceptionInfo e e))]
          (is (= :name-held-twice (:reason (ex-data e))))
          (is (= #{(:id ws1) (:id ws2)} (set (:holders (ex-data e))))))))))

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

(def a-facet
  {:session-id "c-1" :bg-id "c-1" :origin :person :driver :nobody :hold nil :pending nil
   :policy {:auto-respawn? false} :cursor 0 :driving-since nil :queued nil :own-work? false
   :announced-blocked? false :recovered-boot nil})
