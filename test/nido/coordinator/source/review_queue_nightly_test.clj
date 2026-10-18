(ns nido.coordinator.source.review-queue-nightly-test
  "The nightly grooming, exercised against the real queue and run records: one
   plan run per local day, its plan approved in full and applied once, and a
   grooming a person started left to them."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.control :as control]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.review-queue :as rq]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.triggers :as triggers]
   [nido.coordinator.source.queue :as queue]
   [nido.coordinator.source.review-queue-nightly :as nightly]
   [nido.coordinator.work :as work]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.platform.project :as project]
   [nido.ui.views :as views]))

(def ^:private config
  {:type :review-queue-nightly :project :brian :at "00:00" :zone "Europe/Amsterdam"})

(def ^:private plan-trigger
  {:name :review-queue :source config :skill :review-queue
   :payload "Groom ({{event/id}})." :payload-key :id :limits {:budget "1h"}})

(def ^:private plan
  {:tickets [{:br "BR-1" :title "First" :url "https://n/1" :rank 1}]
   :items   [{:n 1 :br "BR-1" :kind :rank :summary "rank → 1"}
             {:n 2 :br "BR-1" :kind :brief :summary "prepend brief"}
             {:n 3 :br nil :kind :schema :summary "add Review rank"}]})

;; 2026-10-18 local, Amsterdam (CEST, UTC+2)
(def ^:private before-midnight "2026-10-18T21:30:00Z")   ; 23:30 on the 18th
(def ^:private after-midnight  "2026-10-18T22:05:00Z")   ; 00:05 on the 19th
(def ^:private that-evening    "2026-10-19T19:00:00Z")   ; 21:00 on the 19th
(def ^:private next-night      "2026-10-19T22:01:00Z")   ; 00:01 on the 20th

(defn- with-root [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root            (constantly (str tmp))
                    project/list-projects     (constantly {"brian" {}})
                    triggers/load-for-project (fn [p] (if (= :brian p) [plan-trigger] []))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(defn- poll-at [now]
  (with-redefs [clock/now-iso (constantly now)]
    (nightly/poll-once! config)))

(defn- plan-run!
  "A plan run fired with `payload`, in `state`, holding `plan` when given."
  [payload state & [p]]
  (let [run (runs/create-run! {:project :brian :trigger plan-trigger :payload payload
                               :session-profile :lite}
                              {:fired-at "t" :fired-by "test"})]
    (runs/transition! (:id run) :running)
    (when (not= :running state) (runs/transition! (:id run) state))
    (when p (io/write-edn! (rq/plan-path (:id run)) p))
    (:id run)))

(defn- drained-triggers [] (mapv #(get-in % [:target :trigger]) (queue/drain!)))

;; ── when it is due ──────────────────────────────────────────────────────────

(deftest the-day-is-the-local-day-and-at-is-inclusive
  (is (= "2026-10-18" (nightly/due-day config before-midnight)))
  (is (= "2026-10-19" (nightly/due-day config "2026-10-18T22:00:00Z")) "00:00 itself is due")
  (is (nil? (nightly/due-day (assoc config :at "23:45") before-midnight)) "nothing before :at")
  (is (= "2026-10-18" (nightly/due-day (assoc config :zone "UTC") after-midnight))
      "the same instant is still the 18th in UTC")
  (is (nil? (nightly/due-day (assoc config :at "06:00") after-midnight))))

(deftest one-plan-run-per-local-day
  (with-root
    (fn []
      (with-redefs [clock/now-iso (constantly before-midnight)]
        (nightly/poll-once! (assoc config :at "23:45")))
      (is (empty? (queue/drain!)) "nothing before :at")
      (poll-at after-midnight)
      (poll-at after-midnight)
      (let [[env & more] (queue/drain!)]
        (is (empty? more) "two polls, one plan run")
        (is (= {:project :brian :trigger :review-queue} (:target env)))
        (is (= {:id "nightly-2026-10-19" :nightly "true"} (:payload env))))
      (poll-at that-evening)
      (is (empty? (queue/drain!)) "a daemon restarted later that day finds the day's key taken")
      (poll-at next-night)
      (is (= [:review-queue] (drained-triggers)) "and the next night grooms again"))))

(deftest a-daemon-down-at-midnight-grooms-when-it-comes-up
  (with-root
    (fn []
      (poll-at that-evening)
      (is (= [:review-queue] (drained-triggers))))))

;; ── deciding and applying ───────────────────────────────────────────────────

(deftest a-nightly-plan-is-approved-in-full-and-applied-once
  (with-root
    (fn []
      (poll-at after-midnight)
      (queue/drain!)
      (let [id (plan-run! {:id "nightly-2026-10-19" :nightly "true"} :done plan)]
        (rq/decide! id 3 :skipped)                       ; a person got there first
        (is (= {id :begun} (:applied (poll-at after-midnight))))
        (is (= {1 :approved 2 :approved 3 :skipped} (:decisions (rq/read-decisions id)))
            "every undecided item approved; the person's skip stands")
        (is (= :nightly (get-in (rq/read-decisions id) [:applying :by])))
        (poll-at after-midnight)
        (work/begin-review-apply! id #(control/fire! :brian %1 %2 %3))
        (let [envs (queue/drain!)]
          (is (= 1 (count envs)) "the source, a second poll and the page's Apply queue one apply")
          (is (= {:project :brian :trigger :review-queue-apply} (:target (first envs))))
          (is (= id (get-in (first envs) [:payload :plan-run]))))))))

(deftest a-nightly-plan-with-nothing-to-approve-is-still-applied
  ;; So a nightly grooming always ends applied, and the log never shows one waiting on nobody.
  (with-root
    (fn []
      (poll-at after-midnight)
      (queue/drain!)
      (let [empty-id (plan-run! {:id "nightly-2026-10-19" :nightly "true"} :done
                                {:tickets [] :items []})]
        (is (= {empty-id :begun} (:applied (poll-at after-midnight))))
        (is (= [:review-queue-apply] (drained-triggers)))
        (is (= :nightly (get-in (rq/read-decisions empty-id) [:applying :by])))
        (is (empty? (:applied (poll-at after-midnight))) "and once fired, never again")))))

(deftest a-written-plan-is-decided-as-soon-as-it-is-there
  (with-root
    (fn []
      (poll-at after-midnight)
      (queue/drain!)
      (let [id (plan-run! {:id "nightly-2026-10-19" :nightly "true"} :running)]
        (poll-at after-midnight)
        (is (= {:decisions {}} (rq/read-decisions id)) "no plan yet, nothing decided")
        (io/write-edn! (rq/plan-path id) plan)
        (poll-at after-midnight)
        (is (= #{:approved} (set (vals (:decisions (rq/read-decisions id))))))
        (is (= [:review-queue-apply] (drained-triggers)))))))

(deftest a-grooming-a-person-started-is-left-to-them
  (with-root
    (fn []
      (let [id (plan-run! {:id "20261019-091258"} :done plan)]
        (poll-at that-evening)
        (is (= [:review-queue] (drained-triggers)) "only today's plan is queued")
        (poll-at that-evening)
        (is (= {:decisions {}} (rq/read-decisions id)))
        (is (empty? (queue/drain!)))))))

(deftest an-unapplied-earlier-plan-is-applied-but-not-by-the-poll-that-queues-today
  (with-root
    (fn []
      (let [old (plan-run! {:id "nightly-2026-10-18" :nightly "true"} :done plan)]
        (is (nil? (:applied (poll-at after-midnight))) "the poll that queued today's plan applies nothing")
        (is (= [:review-queue] (drained-triggers)))
        (is (= {old :begun} (:applied (poll-at after-midnight))) "the next poll carries it out")
        (is (= [:review-queue-apply] (drained-triggers)))))))

;; ── the log ─────────────────────────────────────────────────────────────────

(deftest the-log-says-who-decided-each-grooming
  (with-root
    (fn []
      (let [mine (plan-run! {:id "20261018-120000"} :done plan)]
        (doseq [n [1 2 3]] (rq/decide! mine n :approved))
        (rq/apply! mine (constantly nil))
        (Thread/sleep 5)                                 ; the nightly run starts later
        (poll-at after-midnight)
        (queue/drain!)
        (let [night (plan-run! {:id "nightly-2026-10-19" :nightly "true"} :done plan)]
          (poll-at after-midnight)
          (let [[q] (work/review-queues)
                html (views/review-queue-fragment [q] #{})]
            (is (= night (:run-id q)) "the newest grooming is the one shown")
            (is (= [[night true :nightly] [mine false :person]]
                   (mapv (juxt :run-id :nightly? :decided-by) (:log q))))
            (is (str/includes? html "Log"))
            (is (str/includes? html "nightly · decided by nido"))
            (is (str/includes? html "started from this page · decided by a person"))))))))
