;; test/nido/pipeline_test.clj
(ns nido.pipeline-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]
   [nido.coordinator.report :as report]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.pipeline :as p]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(def ^:private a-baseline
  {:format :baseline :area "order totalling" :bounded-by "money on an order"
   :shape "one summing path"
   :modules [{:id "agg" :module "the aggregate" :hides "the summing order"
              :interface "an order's total"}]
   :composition "only the aggregate sees the lines"
   :load-bearing [{:id "c1" :property "the aggregate is the only summing path"
                   :falsified-by "a second path that sums lines"
                   :evidence ["src/a.clj:1"]}]
   :read ["src/a.clj"]})

(defn- a-design [baseline-seq]
  {:format :design :summary "s" :shape "sh" :invariants ["one summing path"]
   :standing {:relation :conforms}
   :baseline {:seq baseline-seq :relation :within}
   :intent {:seq 1} :effort :S})

(defn- a-decision
  "A decision record. :checks is non-empty and every non-:proceed recommendation
   carries findings, because the write schema requires both — a round that
   decided nothing and found nothing is not a record the ledger will take."
  [design-seq recommend]
  (cond-> {:format :design-decision :recommend recommend :design-seq design-seq
           :reason "r"
           :checks [{:check :relation-honest :status :held :note "n"}]
           :asks "is it worth it?"}
    (not= :proceed recommend)
    (assoc :findings [{:cites ["decomposable"] :claim "the cut does not hold"}])))

(def ^:private a-triage
  {:format :triage-report :ticket-key "BR-1" :determination :bug
   :title "t" :summary "s"
   :confidence {:level :high :reason "reproduced"}
   :directions [{:label "d" :shape "sh" :effort :S
                 :confidence {:level :high :reason "clear"}}]
   :notion-writes nil
   :trail []})

(defn- ledger
  "An OPEN workstream with nothing on it. Returns [ws-id add!], where add!
   appends one entry and hands back the :seq the ledger gave it — read back
   rather than counted, so a test cannot drift from the store."
  []
  (let [id (:id (ws/create! :brian {:stage :in-progress :external-refs []}))]
    [id (fn [kind record]
          (ws/append-entry! :brian id {:kind kind} (pr-str record))
          (count (:entries (ws/read-ws :brian id))))]))

(defn- intent! [add!] (add! :intent {:format :intent :goal "g" :done-when ["d"]}))

;; ── The arc ─────────────────────────────────────────────────────────────────

(deftest an-empty-ledger-is-intake-and-asks-for-an-intent
  (with-tmp
    (fn [_]
      (let [[id _] (ledger)
            r (p/of :brian id)]
        (is (= :intake (:at r)))
        (is (= :establish-intent (:stage (:next r))))
        (is (= :authoring (:mode (:next r))))))))

(deftest the-arc-advances-one-position-per-record
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)]
        (intent! add!)
        (is (= :intent-stated (:at (p/of :brian id))))
        (is (= :write-baseline (:stage (:next (p/of :brian id)))))

        (let [b (add! :baseline a-baseline)]
          (is (= :baselined (:at (p/of :brian id))))
          (is (= {:stage :verify-baseline :mode :mechanical} (:next (p/of :brian id))))

          (add! :baseline-review {:format :baseline-review :verdict :sufficient
                                  :baseline-seq b :reason "it holds"})
          (is (= :baseline-verified (:at (p/of :brian id))))
          (is (= :design (:stage (:next (p/of :brian id)))))

          (let [d (add! :design (a-design b))]
            (is (= :designed (:at (p/of :brian id))))
            (is (= {:stage :decide-design :mode :mechanical} (:next (p/of :brian id))))

            (add! :design-decision (a-decision d :proceed))
            (is (= :design-decided (:at (p/of :brian id))))
            (is (= :human (:mode (:next (p/of :brian id))))
                "a grant is nobody's but a human's until the auto-grant phase")

            (add! :design-approved {:format :design-approved :design {:seq d} :at-seq 5})
            (is (= :design-approved (:at (p/of :brian id))))
            (is (= {:stage :implement :mode :working-copy}
                   (:next (p/of :brian id))))))))))

;; ── Precedence ──────────────────────────────────────────────────────────────

(deftest a-retraction-outranks-the-record-trail-under-it
  ;; The whole point of ordering by precedence: this workstream also holds a
  ;; verified baseline and a design, and reporting :designed would send a driver
  ;; forward over a premise somebody found untrue.
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)]
        (intent! add!)
        (let [b (add! :baseline a-baseline)]
          (add! :baseline-review {:format :baseline-review :verdict :sufficient
                                  :baseline-seq b :reason "it holds"})
          (add! :design (a-design b))
          (is (= :designed (:at (p/of :brian id))))
          (add! :retraction {:format :retraction :retracts {:seq b}
                             :because "a second summing path exists"
                             :evidence ["src/b.clj:9"]})
          (is (= :premise-retracted (:at (p/of :brian id))))
          (is (= {:stage :rebaseline :mode :authoring} (:next (p/of :brian id)))))))))

(deftest a-superseding-baseline-repairs-the-retraction
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)]
        (intent! add!)
        (let [b (add! :baseline a-baseline)]
          (add! :retraction {:format :retraction :retracts {:seq b}
                             :because "wrong" :evidence ["src/b.clj:9"]})
          (is (= :premise-retracted (:at (p/of :brian id))))
          (add! :baseline (assoc a-baseline :supersedes {:seq b :why "corrected"}))
          (is (= :baselined (:at (p/of :brian id)))
              "the ledger moved past it, so it is no longer the fact that matters"))))))

(deftest an-unanswered-blocker-halts-and-an-answered-one-does-not
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)]
        (intent! add!)
        (let [blk (add! :blocker {:format :blocker :summary "which branch"
                                  :needs "a call"
                                  :options [{:label "A" :summary "a"}
                                            {:label "B" :summary "b"}]})]
          (is (= :blocked (:at (p/of :brian id))))
          (is (= :human (:mode (:next (p/of :brian id)))))
          (add! :blocker-answered {:format :blocker-answered :blocker-seq blk
                                   :letter "A" :label "do a"
                                   :summary "took branch A"})
          (is (= :intent-stated (:at (p/of :brian id)))
              "answered, so the arc resumes where the records left it"))))))

;; ── Keyed on seq, never on recency ──────────────────────────────────────────

(deftest a-review-of-an-older-baseline-does-not-verify-the-newer-one
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)]
        (intent! add!)
        (let [b1 (add! :baseline a-baseline)]
          (add! :baseline-review {:format :baseline-review :verdict :sufficient
                                  :baseline-seq b1 :reason "it holds"})
          (is (= :baseline-verified (:at (p/of :brian id))))
          (add! :baseline (assoc a-baseline :area "a different area"))
          (is (= :baselined (:at (p/of :brian id)))
              "the newest baseline is the one standing, and nothing has judged it"))))))

(deftest only-proceed-counts-as-a-decision
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)]
        (intent! add!)
        (let [b (add! :baseline a-baseline)
              _ (add! :baseline-review {:format :baseline-review :verdict :sufficient
                                        :baseline-seq b :reason "holds"})
              d (add! :design (a-design b))]
          (add! :design-decision (a-decision d :recut))
          (is (= :designed (:at (p/of :brian id)))
              "a round that sent the record back is not a decision to build it"))))))

;; ── Terminals and refusal ───────────────────────────────────────────────────

(deftest terminal-positions-name-no-next-action
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)]
        (intent! add!)
        (add! :pr-opened {:format :pr-opened :url "https://example.test/pr/1"
                          :title "t"})
        (is (= :published (:at (p/of :brian id))))
        (is (nil? (:next (p/of :brian id)))
            "the arc ends at the opened draft PR — landing it stays a human's gesture")))))

(deftest a-ledger-of-unreadable-kinds-refuses-rather-than-defaulting-to-intake
  ;; The failure this guards: an empty ledger and a ledger full of pre-vocabulary
  ;; records both fall past every clause. Collapsing them tells a workstream that
  ;; has already been implemented to go and establish its intent.
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)]
        (add! :impl "legacy freeform markdown")
        (let [r (p/of :brian id)]
          (is (= :unplaceable (:at r)))
          (is (nil? (:next r)))
          (is (re-find #"does not read" (:why r))))))))

(deftest an-absent-workstream-refuses-by-name
  (with-tmp
    (fn [_]
      (let [r (p/of :brian "ws-does-not-exist")]
        (is (= :unplaceable (:at r)))
        (is (re-find #"no workstream" (:why r)))))))

;; ── How the work arrived ────────────────────────────────────────────────────

(deftest intake-kind-reads-how-the-work-arrived-not-where-it-resumed
  (with-tmp
    (fn [_]
      (testing "a bare pickup has nothing citable"
        (let [[id _] (ledger)]
          (is (= :pickup (:intake (p/of :brian id))))))

      (testing "a triage entry states the goal, so a design may cite it"
        (let [[id add!] (ledger)]
          (add! :triage a-triage)
          (is (= :triaged (:intake (p/of :brian id))))
          (is (= :intent-stated (:at (p/of :brian id)))
              "triage states the goal, so no separate intent is owed")))

      (testing "a Slack proposal is a proposal, never a decision"
        (let [[id add!] (ledger)]
          (add! :proposed-ticket
                {:format :proposed-ticket :title "t" :ticket-type "bug"
                 :source-url "https://slack.test/1" :problem "p"
                 :root-cause "rc" :fix "src/a.clj:1"})
          (is (= :proposal (:intake (p/of :brian id))))))

      (testing "the kind rides on the first stage, so one dispatch serves all four"
        (let [[id _] (ledger)
              n (:next (p/of :brian id))]
          (is (= :establish-intent (:stage n)))
          (is (= :pickup (:from n))))))))

;; ── What it must not do ─────────────────────────────────────────────────────

(deftest read-names-the-sources-the-answer-came-from
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)]
        (intent! add!)
        (let [r (p/of :brian id)]
          (is (= 1 (:ledger (:read r))))
          (is (= #{:intent} (:kinds (:read r))))
          (is (contains? (:read r) :board-stage))
          (is (contains? (:read r) :ticket-status))
          (is (contains? (:read r) :sessions))
          (is (contains? (:read r) :standing)))))))

(deftest approval-is-standings-answer-and-is-not-re-derived-here
  ;; A :design-approved entry naming a design whose premise was retracted since
  ;; must NOT read as approved: standing joins the grant with the retraction,
  ;; and a second implementation of that join is a second answer.
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)]
        (intent! add!)
        (let [b (add! :baseline a-baseline)
              _ (add! :baseline-review {:format :baseline-review :verdict :sufficient
                                        :baseline-seq b :reason "holds"})
              d (add! :design (a-design b))]
          (add! :design-decision (a-decision d :proceed))
          (add! :design-approved {:format :design-approved :design {:seq d} :at-seq 5})
          (is (= :design-approved (:at (p/of :brian id))))
          (add! :retraction {:format :retraction :retracts {:seq b}
                             :because "the baseline was wrong"
                             :evidence ["src/b.clj:9"]})
          (let [r (p/of :brian id)]
            (is (= :premise-retracted (:at r)))
            (is (false? (:decided? (:standing (:read r))))
                "standing withdrew the grant; the projection did not have to know how")))))))


(deftest stage-of-places-a-kind-or-refuses
  ;; Still load-bearing: deciding whether work moved on past a halt is asking
  ;; which entries are stages and which are halts.
  (is (= :baseline (p/stage-of :baseline)))
  (is (= :baseline (p/stage-of :baseline-review)))
  (is (= :halt (p/stage-of :blocker)))
  (is (nil? (p/stage-of :impl)) "a kind this vocabulary does not read"))

;; ── What a finished stage means ─────────────────────────────────────────────

(defn- ledger-review-statuses
  "The diff loop's terminals, read off the schema that stores them rather than
   copied into this test. A hand-kept list is the thing that drifts."
  []
  (->> (m/children report/ReviewReport)
       (some (fn [[k _ s]] (when (= :status k) (m/children s))))))

(deftest every-diff-loop-terminal-is-classified
  ;; Not via the fallback — the fallback exists for a status nobody has thought
  ;; about, and every status in the ledger's own enum has been thought about.
  (doseq [s (ledger-review-statuses)]
    (is (contains? p/dispositions (p/disposition s)) (str s " must classify"))
    (is (contains? @#'p/disposition-of-status s)
        (str s " is in the ledger enum and must be named in the table, not defaulted"))))

(deftest every-record-loop-terminal-is-classified
  ;; The record pipelines' statuses and the outcomes a round that could not run
  ;; reports in their place. Gathered from what the loops actually set.
  (doseq [s [:sufficient :proceed :disputed :underivable :retreated
             :amend-noop :amend-invalid :amend-touched-code :amend-unreadable
             :codex-failed :no-output :round-crashed :unusable-answer
             :nothing-to-check :no-record :no-workstream :not-worth-running
             :premise-unverified :premise-retracted :design-retracted
             :no-premise :unreadable-ledger :dry-run]]
    (is (contains? @#'p/disposition-of-status s)
        (str s " must be named in the table"))))

(deftest an-unknown-terminal-escalates-rather-than-advancing
  ;; The fail-safe direction. Advancing on an outcome nobody has understood is
  ;; how a pipeline steps forward over a thing that went wrong.
  (is (= :escalate (p/disposition :something-nobody-has-classified)))
  (is (= :escalate (p/disposition nil))))

(deftest the-four-kinds-of-silence-stay-apart
  ;; outcome-tagged, carried into the driver: a round that could not run must
  ;; never mean what a round that ran and found nothing means.
  (is (= :advance    (p/disposition :sufficient)))
  (is (= :retry      (p/disposition :codex-failed)))
  (is (= :route-back (p/disposition :premise-unverified)))
  (is (= :escalate   (p/disposition :disputed))))

(deftest a-finding-that-contradicts-an-invariant-routes-back-rather-than-escalating
  ;; The warden already makes this derivation every round — a finding that
  ;; contradicts a named invariant puts the DESIGN in question, not its
  ;; execution — and until now nothing consumed it.
  (is (= :route-back (p/disposition :escalated))))

;; ── A halt the work moved past ──────────────────────────────────────────────

(deftest work-appended-after-a-halt-answers-it
  ;; BR-5099's exact shape: halted on one day, then baselined, designed, PR'd and
  ;; reviewed clean five days later, with no :blocker-answered — because that
  ;; record is written when somebody clicks the gate button, and this question
  ;; was settled in the session chat. Requiring the record left the workstream
  ;; permanently "waiting on you" while three PRs sat open against it.
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)]
        (add! :blocker {:format :blocker :summary "a product decision"
                        :needs "which way to name it"})
        (is (= :blocked (:at (p/of :brian id))) "live while nothing has moved")
        (intent! add!)
        (is (not= :blocked (:at (p/of :brian id)))
            "an intent appended after it is the work going on anyway")
        (add! :pr-opened {:format :pr-opened :url "https://example.test/pr/1"
                          :title "t"})
        (is (= :published (:at (p/of :brian id))))))))

(deftest a-halt-with-nothing-after-it-is-still-a-halt
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)]
        (intent! add!)
        (add! :blocker {:format :blocker :summary "stuck" :needs "a key"})
        (is (= :blocked (:at (p/of :brian id))))))))

(deftest another-halt-is-not-an-answer-to-the-first
  ;; A later blocker is a NEW question, not a resolution of the old one — and it
  ;; is the one that should be reported.
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)]
        (intent! add!)
        (add! :blocker {:format :blocker :summary "first" :needs "a"})
        (let [second-seq (add! :blocker {:format :blocker :summary "second" :needs "b"})]
          (is (= :blocked (:at (p/of :brian id))))
          (is (= "second" (:summary (ws/latest-entry :brian id :blocker)))
              "and it is the later question that stands")
          (is (some? second-seq)))))))

(deftest an-explicit-answer-still-answers
  (with-tmp
    (fn [_]
      (let [[id add!] (ledger)]
        (intent! add!)
        (let [blk (add! :blocker {:format :blocker :summary "which branch"
                                  :needs "a call"
                                  :options [{:label "A" :summary "a"}
                                            {:label "B" :summary "b"}]})]
          (is (= :blocked (:at (p/of :brian id))))
          (add! :blocker-answered {:format :blocker-answered :blocker-seq blk
                                   :letter "A" :label "do a" :summary "took A"})
          (is (= :intent-stated (:at (p/of :brian id)))
              "answered explicitly, with no other work since"))))))
