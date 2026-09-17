(ns nido.coordinator.source.start-failures-test
  "Which kept failures are owed a recovery, and when a cause may be recovered —
   decided without a coordinator, over failures and recoveries given as data."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.source.start-failures :as sf]
   [nido.session.failure :as failure]))


(defn- kept [id & {:keys [session message origin project]
                   :or   {session "feat" message "Timed out waiting for .nrepl-port"
                          origin {:kind :person} project "brian"}}]
  {:id id :at "2026-09-17T08:00:00Z" :project project :session session
   :instance-id (str project "--" session) :origin origin
   :error [{:class "clojure.lang.ExceptionInfo" :message message}]})

(defn- recovery [cause & {:keys [closed named settled runs sessions]}]
  {:ws-id (str "ws-" cause) :cause cause :closed closed
   :named (set named) :settled (set settled)
   :runs (vec runs) :sessions (vec sessions)})

(defn- session-in [phase] {:autonomy {:phase phase}})

(defn- run [state ended-at]
  {:state state :state-history [{:at "2026-09-17T00:00:00Z" :state :queued}
                                {:at ended-at :state state}]})

(def ^:private pacing {:poll-ms 60000 :ceiling-ms (* 8 60000)})

(defn- at [iso] (java.time.Instant/parse iso))

;; ── owed ─────────────────────────────────────────────────────────────────────

(deftest a-failure-is-owed-until-a-closed-recovery-named-it
  (let [a (kept "F1") b (kept "F2") c (kept "F3")
        cause (failure/cause a)]
    (is (= ["F2" "F3"]
           (mapv :id (sf/owed [a b c] [(recovery cause :closed {:at "t" :outcome :done} :named ["F1"])])))
        "only what the closed recovery named is settled")
    (is (= ["F1" "F2" "F3"]
           (mapv :id (sf/owed [a b c] [(recovery cause :named ["F1" "F2"])])))
        "an open recovery settles nothing: a recovery that dies leaves its failures owed")))

(deftest a-recoverys-own-failed-start-is-never-owed
  (let [own (kept "F9" :origin {:kind :run :run-id "R" :source-type :session-failure})]
    (is (empty? (sf/owed [own] []))
        "a recovery whose own session cannot start is nothing another recovery could fix")))

;; ── in flight ────────────────────────────────────────────────────────────────

(deftest a-cause-is-held-while-any-recovery-of-it-is-in-flight
  (testing "queued, preprocessing or running on any workstream of the cause, even a closed one"
    (doseq [phase [:queued :preprocessing :running]]
      (is (sf/in-flight? [(recovery "c" :closed {:at "t"} :sessions [(session-in phase)])] "c"))))
  (testing "parked holds only on an open workstream — a person's close stopped it"
    (is (sf/in-flight? [(recovery "c" :sessions [(session-in :parked)])] "c"))
    (is (not (sf/in-flight? [(recovery "c" :closed {:at "t"} :sessions [(session-in :parked)])] "c"))))
  (testing "finished sessions and other causes hold nothing"
    (is (not (sf/in-flight? [(recovery "c" :sessions [(session-in :failed) (session-in :done)])] "c")))
    (is (not (sf/in-flight? [(recovery "other" :sessions [(session-in :running)])] "c")))))

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
    (is (empty? (sf/recovery-events [f] [(recovery cause :sessions [(session-in :running)])] now pacing))
        "held: a recovery of the cause is in flight, whatever ref it carries")
    (is (empty? (sf/recovery-events [f] [(recovery cause :runs [(run :failed "2026-09-17T10:00:00Z")])]
                                    now pacing))
        "waiting: its last recovery failed less than a poll interval ago")
    (is (= 1 (count (sf/recovery-events
                     [f] [(recovery cause :closed {:at "t"} :runs [(run :failed "2026-09-17T10:00:00Z")])]
                     now pacing)))
        "a closed workstream's failed runs do not pace the next episode")))

(deftest durations-read-as-milliseconds
  (is (= 120000 (sf/duration-ms "2m")))
  (is (= 86400000 (sf/duration-ms "1d")))
  (is (nil? (sf/duration-ms "soon"))))

(deftest settled-failures-are-never-opened
  (let [opened (atom [])]
    (with-redefs [failure/ids     (constantly ["F1" "F2" "F3"])
                  failure/failure (fn [id] (swap! opened conj id) (kept id))]
      (is (= ["F3"] (mapv :id (sf/undischarged-failures
                               [(recovery "c" :closed {:at "t"} :named ["F1" "F2"])]))))
      (is (= ["F3"] @opened)
          "the records a closed recovery named are known settled from their ids"))))
