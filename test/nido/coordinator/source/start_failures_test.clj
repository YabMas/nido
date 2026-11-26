(ns nido.coordinator.source.start-failures-test
  "Which kept failures are owed a recovery, and when a cause may be recovered —
   decided without a coordinator, over failures and recoveries given as data."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.records :as records]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.source.start-failures :as sf]
   [nido.core.client :as client]
   [nido.platform.core :as core]
   [nido.session.failure :as failure]
   [nido.test.runs :as test-runs]))


(defn- kept [id & {:keys [session message origin project]
                   :or   {session "feat" message "Timed out waiting for .nrepl-port"
                          origin {:kind :person} project "brian"}}]
  {:id id :at "2026-09-17T08:00:00Z" :project project :session session
   :instance-id (str project "--" session) :origin origin
   :error [{:class "clojure.lang.ExceptionInfo" :message message}]})

(defn- recovery [cause & {:keys [closed named settled runs]}]
  {:ws-id (str "ws-" cause) :cause cause :closed closed
   :named (set named) :settled (set settled)
   :runs (vec runs)})

(defn- run [state ended-at]
  {:state state :state-history [{:at "2026-09-17T00:00:00Z" :state :queued}
                                {:at ended-at :state state}]})

(def ^:private pacing {:poll-ms 60000 :ceiling-ms (* 8 60000)})

(defn- at [iso] (java.time.Instant/parse iso))

;; ── owed ─────────────────────────────────────────────────────────────────────

(deftest a-failure-is-owed-until-a-recovery-settled-it
  (let [a (kept "F1") b (kept "F2") c (kept "F3")
        cause (failure/cause a)]
    (is (= ["F2" "F3"]
           (mapv :id (sf/owed [a b c] [(recovery cause :closed {:at "t" :outcome :done}
                                                 :named ["F1" "F2"] :settled ["F1"])])))
        "only what a recovery settled is settled")
    (is (= ["F1" "F2" "F3"]
           (mapv :id (sf/owed [a b c] [(recovery cause :named ["F1" "F2"])])))
        "naming a failure settles nothing: a recovery that dies leaves its failures owed")))

(deftest a-recoverys-own-failed-start-is-never-owed
  (let [own (kept "F9" :origin {:kind :run :run-id "R" :source-type :session-failure})]
    (is (empty? (sf/owed [own] []))
        "a recovery whose own session cannot start is nothing another recovery could fix")))

;; ── in flight ────────────────────────────────────────────────────────────────

(deftest a-cause-is-held-while-any-recovery-of-it-is-in-flight
  (testing "a recovery run starting or running"
    (doseq [state [:queued :running]]
      (is (sf/in-flight? [(recovery "c" :runs [(run state "t")])] "c"))))
  (testing "a run that ended holds nothing, parked for a person or not — it paces instead"
    (is (not (sf/in-flight? [(recovery "c" :runs [(run :awaiting-review "t")])] "c")))
    (is (not (sf/in-flight? [(recovery "c" :runs [(run :failed "t") (run :done "t")])] "c"))))
  (testing "other causes hold nothing"
    (is (not (sf/in-flight? [(recovery "other" :runs [(run :running "t")])] "c")))))

;; ── pacing ───────────────────────────────────────────────────────────────────

(deftest a-cause-waits-longer-after-each-failed-recovery
  (let [ended "2026-09-17T10:00:00Z"]
    (is (sf/due? [] (at ended) pacing) "never tried: due")
    (is (sf/due? [(run :failed ended) (run :done ended)] (at ended) pacing)
        "the latest recovery did not fail: due")
    (testing "one failure waits one poll interval"
      (is (not (sf/due? [(run :failed ended)] (at "2026-09-17T10:00:59Z") pacing)))
      (is (sf/due? [(run :failed ended)] (at "2026-09-17T10:01:00Z") pacing)))
    (testing "three in a row wait four intervals"
      (let [runs [(run :failed ended) (run :failed ended) (run :failed ended)]]
        (is (not (sf/due? runs (at "2026-09-17T10:03:59Z") pacing)))
        (is (sf/due? runs (at "2026-09-17T10:04:00Z") pacing))))
    (testing "the delay never exceeds the ceiling, however many failed"
      (let [runs (vec (repeat 30 (run :failed ended)))]
        (is (sf/due? runs (at "2026-09-17T10:08:00Z") pacing)
            "a cause no recovery repairs costs one agent per ceiling, and is never given up on")))))

(deftest a-failed-recovery-does-not-stop-the-cause-being-recovered
  (is (sf/due? (vec (repeat 5 (run :failed "2026-09-17T00:00:00Z")))
               (at "2026-09-18T00:00:00Z") pacing)))

;; ── events ───────────────────────────────────────────────────────────────────

(deftest one-event-per-cause-naming-every-owed-failure
  (let [a1 (kept "F1" :session "feat-a" :message "Timed out waiting for .nrepl-port on 6101")
        a2 (kept "F3" :session "feat-b" :message "Timed out waiting for .nrepl-port on 6102")
        b1 (kept "F2" :message "jj workspace add failed")
        events (sf/recovery-events [a1 a2 b1] [] (at "2026-09-17T10:00:00Z") pacing)
        by-cause (group-by :cause events)]
    (is (= 2 (count events)) "two causes, two recoveries — not three")
    (let [e (first (get by-cause (failure/cause a1)))]
      (is (= "F1,F3" (:failures e)))
      (is (= (str (failure/cause a1) "@F1") (:id e))
          "the ref is keyed by the cause and its earliest owed failure")
      (is (= :session-recovery (:adapter e)))
      (is (str/starts-with? (:title e) "recover brian/feat-a: ")))))

(deftest no-event-for-a-held-or-waiting-cause
  (let [f     (kept "F1")
        cause (failure/cause f)
        now   (at "2026-09-17T10:00:30Z")]
    (is (empty? (sf/recovery-events [f] [(recovery cause :runs [(run :running "t")])] now pacing))
        "held: a recovery of the cause is in flight, whatever ref it carries")
    (is (empty? (sf/recovery-events [f] [(recovery cause :runs [(run :failed "2026-09-17T10:00:00Z")])]
                                    now pacing))
        "waiting: its last recovery failed less than a poll interval ago")
    (is (= 1 (count (sf/recovery-events
                     [f] [(recovery cause :runs [(run :failed "2026-09-17T10:00:00Z")
                                                 (run :done "2026-09-17T10:00:00Z")])]
                     now pacing)))
        "a recovery that settled the cause ends the streak before it")))

(deftest durations-read-as-milliseconds
  (is (= 120000 (sf/duration-ms "2m")))
  (is (= 86400000 (sf/duration-ms "1d")))
  (is (nil? (sf/duration-ms "soon"))))

(deftest settled-failures-are-never-opened
  (let [opened (atom [])]
    (with-redefs [failure/ids     (constantly ["F1" "F2" "F3"])
                  failure/failure (fn [id] (swap! opened conj id) (kept id))]
      (is (= ["F3"] (mapv :id (sf/undischarged-failures
                               [(recovery "c" :closed {:at "t"} :named ["F1" "F2"] :settled ["F1" "F2"])]))))
      (is (= ["F3"] @opened)
          "the records a recovery settled are known settled from their ids"))))

;; ── recovery runs ────────────────────────────────────────────────────────────

(defn- run-recovery [cause state & {:keys [named settled ended] :or {ended "2026-09-17T10:00:00Z"}}]
  (assoc (recovery cause :closed (when-not (#{:queued :running} state) {:at ended :outcome :dropped})
                   :named named :settled settled :runs [(run state ended)])
         :run? true))

(deftest a-run-settles-what-it-recorded-and-nothing-else
  (let [[a b] [(kept "F1") (kept "F2")]
        cause (failure/cause a)]
    (is (= ["F2"] (mapv :id (sf/owed [a b] [(run-recovery cause :done :named ["F1" "F2"] :settled ["F1"])])))
        "over, the run discharges only what it recorded restored or withdrawn")
    (is (= ["F1" "F2"] (mapv :id (sf/owed [a b] [(run-recovery cause :failed :named ["F1" "F2"])])))
        "a run that died settles nothing, though it is over")))

(deftest a-run-that-ended-leaving-its-cause-owed-paces-it
  (let [f     (kept "F1")
        cause (failure/cause f)
        now   (at "2026-09-17T10:00:30Z")]
    (doseq [state [:failed :awaiting-review]]
      (is (empty? (sf/recovery-events [f] [(run-recovery cause state :named ["F1"])] now pacing))
          (str "a run ended " (name state) " less than a poll interval ago holds the cause")))
    (is (empty? (sf/recovery-events [f] [(run-recovery cause :running :named ["F1"])] now pacing))
        "a running one holds it in flight")
    (is (= [(str cause "@F1#1")]
           (map :id (sf/recovery-events [f] [(run-recovery cause :failed :named ["F1"])]
                                        (at "2026-09-17T10:01:00Z") pacing)))
        "its retry is a new run: the id counts the runs the cause has had")))

(deftest recoveries-reads-the-recovery-runs-in-the-core
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (let [cause (str "cause-" (random-uuid))
              start (fn [failures]
                      (let [id (test-runs/run! :nido :session-recovery
                                               {:adapter :session-recovery :id (str cause "@F1")
                                                :cause cause :failures failures} :running)]
                        [id (client/run-key :nido id)]))
              end!  (fn [id] (client/command! (client/run-key :nido id) (str "end/" id)
                                              {:command :record-outcome :turn (str id " first")
                                               :outcome :completed}))
              [done done-k]   (start "F1,F2")
              [parked park-k] (start "F3")]
          (records/append-to-run! done-k {:kind :session-restored
                                          :outcomes [{:failure "F1" :outcome :restored}
                                                     {:failure "F2" :outcome :withdrawn}]})
          (records/append-to-run! park-k {:kind :blocker :summary "s" :needs "may I reset the cluster?"
                                          :after "a"})
          (end! done) (end! parked)
          (let [by (into {} (map (juxt :ws-id identity)) (filter #(= cause (:cause %)) (sf/recoveries :nido)))]
            (is (= [:done :done #{"F1" "F2"}]
                   ((juxt (comp :outcome :closed) (comp :state first :runs) :settled) (by done))))
            (is (= :awaiting-review (-> (by parked) :runs first :state))
                "a run that recorded a blocker and settled nothing stopped for a person")
            (is (= 1 (count (get-in (by parked) [:entries :blocker])))))))
      (finally (fs/delete-tree tmp)))))

(deftest a-recovery-from-before-runs-settles-what-it-named-once-it-closed
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (let [from (fn [ws n ref] {:holder ws :seq n :ref ref})
              ref  (fn [id] {:adapter :session-recovery :id id :title "recover"})]
          (records/archive!
           :nido
           [{:kind :session-restored :at "2026-09-01T10:00:00Z"
             :outcomes [{:failure "F2" :outcome :not-restored}]
             :from (from "ws-a" 1 (ref "c@F1"))}
            {:kind :closed :outcome :dismissed :at "2026-09-01T11:00:00Z" :by :person
             :from (from "ws-a" 2 (ref "c@F1"))}
            {:kind :session-restored :at "2026-09-02T10:00:00Z"
             :outcomes [{:failure "F4" :outcome :restored} {:failure "F5" :outcome :not-restored}]
             :from (from "ws-b" 1 (ref "d@F4"))}
            {:kind :closed :outcome :done :at "2026-09-02T11:00:00Z" :by :person
             :from (from "ws-b" 2 (ref "d@F4"))}
            {:kind :reopened :at "2026-09-02T12:00:00Z" :by :person
             :from (from "ws-b" 3 (ref "d@F4"))}])
          (let [by (into {} (map (juxt :ws-id identity)) (sf/recoveries :nido))]
            (is (= [#{"F1" "F2"} {:at "2026-09-01T11:00:00Z" :outcome :dismissed} :done]
                   ((juxt :settled :closed (comp :state first :runs)) (by "ws-a")))
                "closed, it settled every failure its fire and its restores named, as it closed")
            (is (= #{"F4"} (:settled (by "ws-b")))
                "reopened since, it settles only what it restored")
            (is (= "c" (:cause (by "ws-a"))) "its cause read off its fire's id"))))
      (finally (fs/delete-tree tmp)))))
