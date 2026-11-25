(ns nido.coordinator.view.activity-test
  "Every facet of a workstream's activity is read off one observation, so no two can disagree;
   what runs is the core's to say."
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [nido.coordinator.view.activity :as act]))

(def ^:private quiet {:closed? false :owed-by :agent :activated? true})

(def ^:private claim {:kind :diff-review :run-id "r" :report-path "/r.json" :progress {:round 2}})

(deftest what-the-core-does-is-live-and-underway
  (doseq [k [:working :deciding :starting]]
    (let [a (act/of (assoc quiet :core {:kind k :unit "u"}))]
      (is (= {:source :core :activity k :unit "u"} (:doing a)) (str k))
      (is (:live a))
      (is (= :active (:engagement a)))
      (is (not (:stalled a))))))

(deftest a-claim-says-what-a-working-turn-is-doing-and-nothing-more
  (testing "beside a :working turn it describes the turn, with its round's progress"
    (let [a (act/of (assoc quiet :core {:kind :working :turn "t"} :claim claim))]
      (is (= {:source :claim :kind :diff-review :run-id "r" :report-path "/r.json" :progress {:round 2}}
             (:doing a)))
      (is (:live a))))
  (testing "with nothing the core does, a claim is a process holding a lock: nothing runs"
    (let [a (act/of (assoc quiet :claim claim))]
      (is (nil? (:doing a)))
      (is (not (:live a)))))
  (testing "an ask waits on the person whatever a claim says"
    (is (nil? (:doing (act/of (assoc quiet :core {:kind :asking} :claim claim)))))))

(deftest a-stall-is-the-cores-open-unit-left-unmoved
  (let [a (act/of (assoc quiet :movable? true))]
    (is (:stalled a))
    (is (= :stalled (:engagement a))))
  (testing "nothing stalls"
    (is (not (:stalled (act/of quiet))) "with no open unit nobody holds: nothing is the core's to move")
    (is (not (:stalled (act/of (assoc quiet :movable? true :paused? true)))) "while a pause stands")
    (is (not (:stalled (act/of (assoc quiet :movable? true :core {:kind :deciding})))) "while the core is owed a move")
    (is (not (:stalled (act/of (assoc quiet :movable? true :core {:kind :asking})))) "while it asks the person")
    (is (not (:stalled (act/of (assoc quiet :movable? true :closed? true)))) "once settled")
    (is (not (:stalled (act/of (assoc quiet :movable? true :owed-by :nobody))))
        "when nobody owes a move")
    (is (not (:stalled (act/of (assoc quiet :movable? true :owed-by :person :landing? true))))
        "while the move is the person's: a release candidate waits on their landing")
    (is (not (:stalled (act/of (assoc quiet :movable? true :failure :start-failed))))
        "when a session failed to start: that is a system error only the person clears")))

(deftest a-landing-needs-you-as-a-landing-and-asks-nothing
  (testing "brian's release candidate as the person found it: review converged, Land offered, the
            core idle with its unit open"
    (let [a (act/of (assoc quiet :owed-by :person :landing? true :movable? true :band :in-progress))]
      (is (= :person (:owed-by a)) "the landing is the person's turn")
      (is (:needs-you a) "and so it is on Needs you — no exception")
      (is (= :landing (:needs a)) "as a landing, not as a question")
      (is (not (:live a)) "nothing runs, so no working dot")
      (is (not (:stalled a)))
      (is (= :parked-at-gate (:engagement a)))))
  (testing "not theirs where the recipe does not run, or a pause holds it"
    (doseq [obs [(assoc quiet :owed-by :person :landing? true :activated? false)
                 (assoc quiet :owed-by :person :landing? true :paused? true)]]
      (let [a (act/of obs)]
        (is (= :nobody (:owed-by a)) (pr-str obs))
        (is (nil? (:needs a)) (pr-str obs))))))

(deftest a-failure-only-the-person-clears-needs-you-as-a-system-error
  (testing "a conductor the core stopped after it failed: its stop is an ask, said as a system error"
    (let [a (act/of (assoc quiet :core {:kind :asking} :failure :conductor-stopped))]
      (is (:needs-you a))
      (is (= :system-error (:needs a)))
      (is (= :person (:owed-by a)))))
  (testing "a session that failed to start, the core doing nothing on it"
    (let [a (act/of (assoc quiet :movable? true :failure :start-failed))]
      (is (:needs-you a))
      (is (= :system-error (:needs a)))
      (is (not (:stalled a)) "a system error, not a stall")
      (is (not (:live a)))))
  (testing "a failure outranks what the position owes"
    (is (= :system-error (:needs (act/of (assoc quiet :owed-by :person :landing? true
                                                :failure :start-failed))))))
  (testing "a failed start is not the person's while the conductor still decides on it, or a pause
            holds the workstream"
    (is (not (:needs-you (act/of (assoc quiet :failure :start-failed :core {:kind :deciding})))))
    (is (not (:needs-you (act/of (assoc quiet :failure :start-failed :paused? true)))))))

(deftest the-person-is-needed-by-an-ask-or-by-what-their-position-owes
  (testing "a core ask"
    (let [a (act/of (assoc quiet :core {:kind :asking}))]
      (is (:needs-you a))
      (is (= :ask (:needs a)))
      (is (= :person (:owed-by a)))
      (is (= :parked-at-gate (:engagement a)))
      (is (nil? (:doing a)) "an ask waits on the person: nothing is underway")
      (is (not (:stalled a)))))
  (testing "a move the ledger owes them, on an activated workstream — even with an open unit the
            core leaves unmoved, which waits on them rather than stalling"
    (let [a (act/of (assoc quiet :owed-by :person :movable? true))]
      (is (:needs-you a))
      (is (= :ask (:needs a)))
      (is (= :person (:owed-by a)))
      (is (= :parked-at-gate (:engagement a)))
      (is (not (:stalled a)))))
  (testing "but not on one nido's recipe does not run on, nor while a person's pause stands — and
            then nobody is said to owe it, so owed-by :person always means needs-you"
    (doseq [obs [(assoc quiet :owed-by :person :activated? false)
                 (assoc quiet :owed-by :person :paused? true)]]
      (let [a (act/of obs)]
        (is (not (:needs-you a)) (pr-str obs))
        (is (= :nobody (:owed-by a)) (pr-str obs))))))

(deftest a-blocker-at-an-idle-workstream-reads-one-way-everywhere
  (testing "brian ws-20261124-35a103 as the person found it: the ledger's newest open item is a
            blocker owed to the person, the core does nothing on it and its unit is open"
    (let [a (act/of {:closed? false :band :in-progress :owed-by :person :activated? true
                     :paused? false :core nil :movable? true :claim nil})]
      (is (not (:live a)) "nothing runs, so no working dot")
      (is (nil? (:doing a)))
      (is (= :person (:owed-by a)))
      (is (:needs-you a) "it is on Needs you")
      (is (not (:stalled a)))
      (is (= :parked-at-gate (:engagement a))))))

(deftest a-merge-waiting-its-turn-is-underway-but-nothing-runs
  (let [a (act/of (assoc quiet :band :shipping :owed-by :machine))]
    (is (= {:source :merge :phase :queued} (:doing a)))
    (is (not (:live a)))
    (is (= :queued (:engagement a))))
  (testing "once the core takes it, what the core does is underway, and live"
    (let [a (act/of (assoc quiet :band :shipping :core {:kind :working :turn "t"}))]
      (is (= :core (:source (:doing a))))
      (is (:live a)))))

(deftest a-pause-runs-nothing
  (let [a (act/of (assoc quiet :paused? true :core {:kind :working :turn "t"}))]
    (is (some? (:doing a)) "the turn is still underway")
    (is (not (:live a)) "but no working dot while the person's pause stands")
    (is (:paused a))))

(deftest the-machine-owing-a-move-is-queued
  (is (= :queued (:engagement (act/of (assoc quiet :owed-by :machine))))))

(deftest settled-is-settled
  (let [a (act/of (assoc quiet :closed? true :core {:kind :asking} :claim claim))]
    (is (= :settled (:engagement a)))
    (is (nil? (:doing a)))
    (is (not (:live a)))
    (is (not (:needs-you a)))
    (is (not (:stalled a)))))

;; ── The model's invariants, over every combination of what it reads ─────────

(def ^:private observations
  (for [closed?    [false true]
        activated? [false true]
        landing?   [false true]
        failure    [nil :conductor-stopped :start-failed]
        paused?    [false true]
        owed-by    [:person :agent :machine :nobody nil]
        core       [nil {:kind :asking} {:kind :working :turn "t"} {:kind :deciding}]
        band       [:in-progress :shipping]
        movable?   [false true]
        claim      [nil claim]]
    {:closed? closed? :activated? activated? :landing? landing? :failure failure :paused? paused?
     :owed-by owed-by :core core
     :band band :movable? movable? :claim claim}))

(deftest the-facets-cannot-disagree
  (doseq [obs observations
          :let [a (act/of obs)]]
    (is (m/validate act/Activity a)
        (str "the answer is an Activity: its facets fit its engagement's variant " (pr-str obs) " → " (pr-str a)))
    (is (m/validate act/Observation obs) (str "the observation is an Observation " (pr-str obs)))
    (is (= (boolean (:live a))
           (boolean (and (:doing a) (not= :merge (:source (:doing a))) (not (:paused a)))))
        (str "underway ⇔ live, except a merge waiting its turn or a pause " (pr-str obs)))
    (is (or (not= :merge (:source (:doing a))) (nil? (:core obs)))
        (str "a merge is underway only while the core does nothing " (pr-str obs)))
    (is (= (= :person (:owed-by a)) (boolean (:needs-you a)) (some? (:needs a)))
        (str "owed-by :person ⇔ needs-you ⇔ a category, with no exception " (pr-str obs)))
    (is (contains? (set (cons nil act/needs-categories)) (:needs a))
        (str "the category is one of the named ones " (pr-str obs)))
    (is (or (not= :landing (:needs a)) (:landing? obs))
        (str "only a landing the position owes is said as a landing " (pr-str obs)))
    (is (or (not= :system-error (:needs a)) (some? (:failure obs)))
        (str "only a failure is said as a system error " (pr-str obs)))
    (is (or (not (:needs-you a)) (some? (:core obs)) (not (:live a)))
        (str "waiting on the person with the core idle lights no working dot " (pr-str obs)))
    (is (or (not (:stalled a))
            (and (not (:live a)) (not (:paused a)) (not (:needs-you a)) (#{:agent :machine} (:owed-by a))))
        (str "a stall runs nothing, is unpaused and is an agent's or the machine's move " (pr-str obs)))
    (is (or (:activated? obs) (not (:needs-you a)) (= :asking (:kind (:core obs)))
            (= :start-failed (:failure obs)))
        (str "nothing not activated needs the person but the core: an ask or a failed start " (pr-str obs)))
    (is (or (not (:closed? obs)) (and (not (:needs-you a)) (not (:stalled a)) (nil? (:doing a))
                                      (not (:live a)) (= :settled (:engagement a))))
        (str "settled is settled " (pr-str obs)))
    (is (= (:engagement a)
           (cond (:closed? obs) :settled (:needs-you a) :parked-at-gate (:live a) :active
                 (:stalled a) :stalled (= :machine (:owed-by a)) :queued :else :idle))
        (str "engagement is read off the other facets " (pr-str obs)))))
