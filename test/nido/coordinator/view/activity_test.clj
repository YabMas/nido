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

(deftest a-stall-needs-you-as-a-system-error
  (testing "an open unit nobody holds, owed to an agent or the machine, the core idle"
    (doseq [owed [:agent :machine]]
      (let [a (act/of (assoc quiet :movable? true :held? true :owed-by owed))]
        (is (:stalled a) (str owed))
        (is (:needs-you a) "a stall needs the person")
        (is (= :system-error (:needs a)) "as a system error")
        (is (= :person (:owed-by a)) "their Wake is the next move")
        (is (= :parked-at-gate (:engagement a)))
        (is (not (:live a))))))
  (testing "an activated workstream the core holds with no open unit at all — done-when 2: it
            used to read :queued and show nowhere"
    (let [a (act/of {:closed? false :band :in-progress :owed-by :machine :activated? true
                     :paused? false :held? true :core nil :movable? false :claim nil})]
      (is (:stalled a))
      (is (= :system-error (:needs a)))
      (is (= :parked-at-gate (:engagement a)))))
  (testing "nothing stalls"
    (is (not (:stalled (act/of quiet))) "with the core holding nothing of it: nothing is the core's to move")
    (is (not (:stalled (act/of (assoc quiet :held? true :activated? false))))
        "held, but neither activated nor with an open unit nobody holds")
    (is (not (:stalled (act/of (assoc quiet :held? true :band :shipping :owed-by :machine))))
        "while a merge waits its turn: a move is underway")
    (is (not (:stalled (act/of (assoc quiet :movable? true :paused? true)))) "while a pause stands")
    (is (not (:stalled (act/of (assoc quiet :movable? true :core {:kind :deciding})))) "while the core is owed a move")
    (is (not (:stalled (act/of (assoc quiet :movable? true :core {:kind :asking})))) "while it asks the person")
    (is (not (:stalled (act/of (assoc quiet :movable? true :closed? true)))) "once settled")
    (is (not (:stalled (act/of (assoc quiet :movable? true :owed-by :nobody))))
        "when nobody owes a move")
    (is (not (:stalled (act/of (assoc quiet :movable? true :owed-by :person :landing? true))))
        "while the move is the person's: a release candidate waits on their landing")
    (is (not (:stalled (act/of (assoc quiet :movable? true :failure :start-failed))))
        "when a session failed to start: that is a system error only the person clears")
    (is (not (:stalled (act/of (assoc quiet :held? true :activated? true :band :incoming
                                      :waits-on :person-act))))
        "a queued arrival on an intake band: it waits on the person's Promote, deliberately")
    (is (not (:needs-you (act/of (assoc quiet :held? true :activated? true :band :incoming
                                        :waits-on :person-act))))
        "and is no system error")
    (is (not (:stalled (act/of (assoc quiet :movable? true :band :drained :waits-on :open-slice))))
        "a drain while a slice of it is open: the slice's own row is read for a stall")))

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

(deftest a-stuck-turn-is-read-apart-from-every-other-facet
  ;; Stuck is a fact about a process, not about who owes the move: it is never a stall, and it
  ;; leaves closure, the ask and the pause to read as they would without it.
  (let [a (act/of (assoc quiet :movable? true :core {:kind :stuck :turn "t" :unit "u"}))]
    (is (:stuck a))
    (is (not (:live a)) "nothing it does is underway")
    (is (nil? (:doing a)))
    (is (not (:stalled a)) "the core reads it, so it is not a unit left unmoved")
    (is (not= :active (:engagement a))))
  (is (not (:stuck (act/of (assoc quiet :core {:kind :working :turn "t"})))))
  (testing "a stuck run on a closed workstream still reads stuck, and the workstream settled"
    (let [a (act/of (assoc quiet :closed? true :core {:kind :stuck :turn "t"}))]
      (is (:stuck a))
      (is (= :settled (:engagement a)))))
  (testing "a stuck run under an open ask reads stuck beside the ask, never as a stall"
    (let [a (act/of (assoc quiet :movable? true :core {:kind :asking :stuck {:turn "t" :unit "u"}}))]
      (is (:stuck a))
      (is (:needs-you a) "the ask is still the person's")
      (is (= :ask (:needs a)))
      (is (not (:stalled a)) "a stall never needs you")
      (is (not (:live a)))))
  (testing "beside a machine failure as well: both are carried, independently"
    (let [a (act/of (assoc quiet :failure :conductor-stopped
                           :core {:kind :asking :failed? true :failure-only? true
                                  :stuck {:turn "t" :unit "u"}}))]
      (is (:stuck a))
      (is (= :system-error (:needs a)))))
  (testing "a held unit's stuck run reads stuck and paused both"
    (let [a (act/of (assoc quiet :paused? true :core {:kind :stuck :turn "t" :unit "u"}))]
      (is (:stuck a))
      (is (:paused a)))))

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
        core       [nil {:kind :asking} {:kind :working :turn "t"} {:kind :deciding}
                    {:kind :stuck :turn "t"} {:kind :asking :stuck {:turn "t"}}]
        band       [:in-progress :shipping]
        held?      [false true]
        movable?   (if held? [false true] [false])
        claim      [nil claim]
        waits-on   [nil :person-act :open-slice]]
    {:closed? closed? :activated? activated? :landing? landing? :failure failure :paused? paused?
     :owed-by owed-by :core core :held? held?
     :band band :movable? movable? :claim claim :waits-on waits-on}))

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
    (is (or (not= :system-error (:needs a)) (some? (:failure obs)) (:stalled a))
        (str "only a failure or a stall is said as a system error " (pr-str obs)))
    (is (or (not (:stalled a)) (= :system-error (:needs a)))
        (str "a stall needs the person as a system error " (pr-str obs)))
    (is (or (not (:needs-you a)) (some? (:core obs)) (not (:live a)))
        (str "waiting on the person with the core idle lights no working dot " (pr-str obs)))
    (is (or (not (:stalled a))
            (and (not (:live a)) (nil? (:doing a)) (not (:paused a)) (nil? (:core obs))
                 (#{:agent :machine} (:owed-by obs)) (nil? (:waits-on obs))
                 (or (:movable? obs) (and (:held? obs) (:activated? obs)))))
        (str "a stall runs nothing, is unpaused, rests on no deliberate wait, the core's to move and an agent's or the machine's move " (pr-str obs)))
    (is (or (:stalled a) (not (and (not (:closed? obs)) (nil? (:core obs)) (not (:paused? obs))
                                   (nil? (:failure obs)) (not= :shipping (:band obs))
                                   (nil? (:waits-on obs))
                                   (#{:agent :machine} (:owed-by obs))
                                   (or (:movable? obs) (and (:held? obs) (:activated? obs))))))
        (str "the core's to move, owed to an agent or the machine, and nothing underway is a stall " (pr-str obs)))
    (is (or (:activated? obs) (not (:needs-you a)) (= :asking (:kind (:core obs)))
            (= :start-failed (:failure obs)) (:stalled a))
        (str "nothing not activated needs the person but the core: an ask, a failed start or a stall " (pr-str obs)))
    (is (or (not (:closed? obs)) (and (not (:needs-you a)) (not (:stalled a)) (nil? (:doing a))
                                      (not (:live a)) (= :settled (:engagement a))))
        (str "settled is settled " (pr-str obs)))
    (is (= (:stuck a) (boolean (or (= :stuck (:kind (:core obs))) (:stuck (:core obs)))))
        (str "stuck is the core's reading alone, whatever else holds " (pr-str obs)))
    (is (or (not (:stuck a)) (and (nil? (:doing a)) (not (:live a))))
        (str "a stuck turn has nothing underway " (pr-str obs)))
    (is (= (:engagement a)
           (cond (:closed? obs) :settled (:needs-you a) :parked-at-gate (:live a) :active
                 (= :machine (:owed-by a)) :queued :else :idle))
        (str "engagement is read off the other facets " (pr-str obs)))))
