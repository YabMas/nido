;; test/nido/coordinator/record/standing_test.clj
(ns nido.coordinator.record.standing-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.io :as io]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(def ^:private a-baseline
  ;; :intent names entry 1, which `ledger` appends before anything else — the
  ;; survey says which goal it was scoped for, and the append boundary checks it.
  {:format :baseline :intent {:seq 1}
   :area "order totalling" :bounded-by "money on an order"
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

(defn- ledger
  "A workstream with an intent at seq 1, then whatever `entries` says.
   Returns [ws-id (fn add [kind record] -> seq)]."
  []
  (let [w  (ws/create! :brian {:stage :in-progress :external-refs []})
        id (:id w)]
    (ws/append-entry! :brian id {:kind :intent}
                      (pr-str {:format :intent :goal "g" :done-when ["d"]}))
    ;; The seq comes back from the ledger rather than a counter here, so a test
    ;; that also writes an entry another way cannot get out of step with it.
    [id (fn [kind record]
          (ws/append-entry! :brian id {:kind kind} (pr-str record))
          (count (:entries (ws/read-ws :brian id))))]))

(deftest a-design-on-a-verified-baseline-is-decidable-and-not-yet-decided
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b (add :baseline a-baseline)
            _ (add :baseline-review {:format :baseline-review :verdict :sufficient
                                     :baseline-seq b :reason "it holds"})
            d (add :design (a-design b))
            st (standing/of-design :brian id (ws/entry-at-seq :brian id d))]
        (is (true? (:decidable? st)))
        (is (false? (:decided? st)))
        (is (nil? (:blocked st)) "the missing approval is NOT a decidability blocker")
        (is (= :not-approved (:reason (standing/why-not-decided st))))))))

(deftest an-approval-decides-it
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b (add :baseline a-baseline)
            _ (add :baseline-review {:format :baseline-review :verdict :sufficient
                                     :baseline-seq b :reason "ok"})
            d (add :design (a-design b))
            a (add :design-approved {:format :design-approved :design {:seq d} :at-seq d})
            st (standing/of-design :brian id (ws/entry-at-seq :brian id d))]
        (is (true? (:decided? st)))
        (is (= a (:approved-by st)))
        (is (nil? (standing/why-not-decided st)))))))

(deftest retracting-the-premise-stops-a-design-that-was-already-decided
  ;; The case the whole change exists for.
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b (add :baseline a-baseline)
            _ (add :baseline-review {:format :baseline-review :verdict :sufficient
                                     :baseline-seq b :reason "ok"})
            d (add :design (a-design b))
            _ (add :design-approved {:format :design-approved :design {:seq d} :at-seq d})
            st0 (standing/of-design :brian id (ws/entry-at-seq :brian id d))
            r (add :retraction {:format :retraction :retracts {:seq b}
                                :because "the invoice renderer sums independently"
                                :evidence ["src/order/invoice.clj:88"]
                                :found-during :implementation})
            st (standing/of-design :brian id (ws/entry-at-seq :brian id d))]
        (is (true? (:decided? st0)) "decided before the retraction")
        (is (false? (:decidable? st)) "and undecidable after it")
        (is (false? (:decided? st)))
        (is (= :premise-retracted (:reason (:blocked st))))
        (is (= r (:seq (:blocked st))) "the refusal names the entry responsible")))))

(deftest a-corrected-baseline-does-not-block-and-is-reported-as-the-way-back
  ;; Supersession never blocks: a baseline corrected but not retracted still
  ;; stands. The correction only tells a stuck design what would re-establish it.
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b1 (add :baseline a-baseline)
            d  (add :design (a-design b1))
            b2 (add :baseline (assoc a-baseline :area "corrected"
                                     :supersedes {:seq b1 :why "refuted"}))
            _  (add :baseline-review {:format :baseline-review :verdict :sufficient
                                      :baseline-seq b2 :reason "ok"})
            st (standing/of-design :brian id (ws/entry-at-seq :brian id d))]
        (is (false? (:decidable? st))
            "the baseline it CITES was never found sufficient — the correction's
             verdict is about a different entry")
        (is (= :premise-unverified (:reason (:blocked st))))
        (is (= b2 (:replaced-by (:blocked st)))
            "and it says which record would re-establish the premise")
        (is (nil? (:retracted-by (:premise st))) "correction is not retraction")))))

(deftest a-replacement-is-followed-through-the-chain-and-never-inferred
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b1 (add :baseline a-baseline)
            d  (add :design (a-design b1))
            b2 (add :baseline (assoc a-baseline :area "second"
                                     :supersedes {:seq b1 :why "r"}))
            b3 (add :baseline (assoc a-baseline :area "third"
                                     :supersedes {:seq b2 :why "r"}))
            _  (add :baseline (assoc a-baseline :area "an unrelated later baseline"))
            st (standing/of-design :brian id (ws/entry-at-seq :brian id d))]
        (is (= b3 (:replaced-by (:premise st)))
            "the chain is followed to its end")
        (is (not= b3 (inc b3)))
        (testing "and a baseline citing nothing yields no replacement, however new"
          (let [st2 (standing/of-design
                     :brian id (assoc (a-design 999) :seq 999))]
            (is (nil? (:replaced-by (:premise st2))))))))))

(deftest standing-fails-closed-when-a-record-it-depends-on-will-not-read
  ;; Alone among this ledger's readers. Everything else degrades to nil on an
  ;; entry it cannot parse; an unreadable retraction that silently does not
  ;; retract turns a safety check into a formality.
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b (add :baseline a-baseline)
            _ (add :baseline-review {:format :baseline-review :verdict :sufficient
                                     :baseline-seq b :reason "ok"})
            d (add :design (a-design b))
            r (add :retraction {:format :retraction :retracts {:seq b}
                                :because "x" :evidence ["src/a.clj:1"]})]
        (io/write-text! (str (fs/path (cstate/workstream-dir :brian id)
                                      (format "entries/%04d-retraction.edn" r)))
                        "{:format :retraction :this-will-not")
        (let [st (standing/of-design :brian id (ws/entry-at-seq :brian id d))]
          (is (true? (:indeterminate? st)))
          (is (= :unreadable-ledger (:reason (:blocked st))))
          (is (not (:decidable? st)) "and an indeterminate standing blocks"))))))

(deftest a-review-of-a-different-baseline-does-not-verify-this-one
  ;; A workstream holds several baselines and several reviews. The one that counts
  ;; names the baseline the design stands on — reading "the latest review" would
  ;; let a baseline of another area vouch for this one.
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b1 (add :baseline a-baseline)
            b2 (add :baseline (assoc a-baseline :area "a different area"))
            d  (add :design (a-design b1))
            _  (add :baseline-review {:format :baseline-review :verdict :sufficient
                                      :baseline-seq b2 :reason "the other one holds"})
            _  (add :baseline-review {:format :baseline-review :verdict :falsified
                                      :baseline-seq b1 :reason "this one does not"
                                      :findings [{:cites ["c"] :claim "x"}]})
            st (standing/of-design :brian id (ws/entry-at-seq :brian id d))]
        (is (false? (:decidable? st)))
        (is (= :premise-unverified (:reason (:blocked st)))
            "checked and found wrong is not checked and found sound")))))

(defn- add-from-an-older-era!
  "Write an entry the write contract no longer accepts, the way a ledger from
   that era actually holds it — file plus index row. `append-entry!` cannot: it
   validates against what is writable NOW, which is the point of the read eras."
  [ws-id kind record]
  (let [w (ws/read-ws :brian ws-id)
        n (inc (count (:entries w)))
        f (format "entries/%04d-%s.edn" n (name kind))]
    (io/write-text! (str (fs/path (cstate/workstream-dir :brian ws-id) f)) (pr-str record))
    (ws/write! (update w :entries conj {:kind kind :seq n :at "2026-01-01T00:00:00Z" :file f}))
    n))

(deftest a-baseline-checked-under-the-older-question-was-still-checked
  ;; :accurate is what :sufficient replaced, and it is read-only now — a ledger
  ;; carrying one was verified, and re-asking it under the newer question is the
  ;; baseline loop's business, not a reason to refuse to decide against it.
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b (add :baseline a-baseline)
            _ (add-from-an-older-era! id :baseline-review
                                      {:format :baseline-review :verdict :accurate
                                       :baseline-seq b :reason "ok"})
            d (add :design (a-design b))
            st (standing/of-design :brian id (ws/entry-at-seq :brian id d))]
        (is (true? (:decidable? st)))))))

;; ── An invalidating verdict, and a premise re-surveyed under a live design ────

(defn- a-verdict
  "A verdict of `v` against `design-seq`. :invariants-broken and :needs are what
   the write schema demands of a decision, which is the bar this reads as
   evidence somebody could check."
  [design-seq v]
  {:format :design-verdict :verdict v :round 1 :design-seq design-seq
   :reason "a second path sums lines"
   :invariants-broken [{:invariant "one summing path"
                        :finding "the invoice renderer sums independently"}]
   :needs "redesign the totalling seam"})

(deftest an-invalidating-verdict-unseats-a-design-that-was-already-approved
  ;; The case this change exists for: the round that reviewed the implementation
  ;; judged the DESIGN wrong, and until now nothing read that back.
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b (add :baseline a-baseline)
            _ (add :baseline-review {:format :baseline-review :verdict :sufficient
                                     :baseline-seq b :reason "ok"})
            d (add :design (a-design b))
            _ (add :design-approved {:format :design-approved :design {:seq d} :at-seq d})
            st0 (standing/of-design :brian id (ws/entry-at-seq :brian id d))
            v (add :design-verdict (a-verdict d :invalidated))
            st (standing/of-design :brian id (ws/entry-at-seq :brian id d))]
        (is (true? (:decided? st0)) "decided before the round ran")
        (is (false? (:decidable? st)) "and undecidable after it")
        (is (= :design-invalidated (:reason (:blocked st))))
        (is (= v (:seq (:blocked st))) "the refusal names the round responsible")))))

(deftest a-standing-challenged-verdict-counts-and-a-strained-one-does-not
  ;; :strained exists so the gap between fine and wrong is not rounded to fine.
  ;; It is a reading, not a decision, and rounding it UP would stop a branch on
  ;; the expected outcome of a healthy round — ten of the eleven verdicts ever
  ;; written are :strained.
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b (add :baseline a-baseline)
            _ (add :baseline-review {:format :baseline-review :verdict :sufficient
                                     :baseline-seq b :reason "ok"})
            d (add :design (a-design b))
            _ (add :design-verdict (dissoc (a-verdict d :strained) :needs))
            st1 (standing/of-design :brian id (ws/entry-at-seq :brian id d))
            _ (add :design-verdict (a-verdict d :standing-challenged))
            st2 (standing/of-design :brian id (ws/entry-at-seq :brian id d))]
        (is (true? (:decidable? st1)) ":strained is a reading, not a decision")
        (is (false? (:decidable? st2)) ":standing-challenged is a decision")
        (is (= :design-invalidated (:reason (:blocked st2))))))))

(deftest an-approval-after-the-verdict-answers-it-and-one-before-does-not
  ;; The Accept half of the gate, as the ledger sees it. Ordering is the whole
  ;; content: a grant made before the round ran was made against a reading the
  ;; round has since contradicted, so presence alone cannot be the test.
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b (add :baseline a-baseline)
            _ (add :baseline-review {:format :baseline-review :verdict :sufficient
                                     :baseline-seq b :reason "ok"})
            d (add :design (a-design b))
            _ (add :design-approved {:format :design-approved :design {:seq d} :at-seq d})
            v (add :design-verdict (a-verdict d :invalidated))
            st0 (standing/of-design :brian id (ws/entry-at-seq :brian id d))
            a2 (add :design-approved {:format :design-approved :design {:seq d}
                                      :at-seq v :note "the round misread the renderer"})
            st (standing/of-design :brian id (ws/entry-at-seq :brian id d))]
        (is (false? (:decidable? st0)) "the earlier grant does not answer it")
        (is (true? (:decidable? st)) "a grant made after it does")
        (is (true? (:decided? st)))
        (is (= a2 (:approved-by st)) "and it is the later grant that decides it")))))

(deftest a-verdict-against-a-superseded-design-says-nothing-about-this-one
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b  (add :baseline a-baseline)
            _  (add :baseline-review {:format :baseline-review :verdict :sufficient
                                      :baseline-seq b :reason "ok"})
            d1 (add :design (a-design b))
            _  (add :design-verdict (a-verdict d1 :invalidated))
            d2 (add :design (assoc (a-design b) :supersedes {:seq d1 :why "recut"}))
            st (standing/of-design :brian id (ws/entry-at-seq :brian id d2))]
        (is (true? (:decidable? st))
            "the redesign is the answer to the verdict, not another thing it blocks")))))

(deftest a-premise-re-surveyed-after-the-design-unseats-it
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b1 (add :baseline a-baseline)
            _  (add :baseline-review {:format :baseline-review :verdict :sufficient
                                      :baseline-seq b1 :reason "ok"})
            d  (add :design (a-design b1))
            _  (add :design-approved {:format :design-approved :design {:seq d} :at-seq d})
            st0 (standing/of-design :brian id (ws/entry-at-seq :brian id d))
            b2 (add :baseline (assoc a-baseline :area "re-surveyed"
                                     :supersedes {:seq b1 :why "the seam moved"}))
            st (standing/of-design :brian id (ws/entry-at-seq :brian id d))]
        (is (true? (:decided? st0)))
        (is (false? (:decidable? st)))
        (is (= :premise-superseded (:reason (:blocked st))))
        (is (= b2 (:replaced-by (:blocked st))) "and it names what to cite instead")))))

(deftest a-premise-superseded-BEFORE-the-design-is-the-ordinary-authoring-round
  ;; The guard that makes the rule survivable. A design round appends three to six
  ;; superseding baselines in a normal run and the design is written last, so
  ;; every one of them predates it. Firing on those would switch the rule off.
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b1 (add :baseline a-baseline)
            b2 (add :baseline (assoc a-baseline :area "round 2"
                                     :supersedes {:seq b1 :why "falsified"}))
            b3 (add :baseline (assoc a-baseline :area "round 3"
                                     :supersedes {:seq b2 :why "falsified"}))
            _  (add :baseline-review {:format :baseline-review :verdict :sufficient
                                      :baseline-seq b3 :reason "holds"})
            d  (add :design (a-design b3))
            _  (add :design-approved {:format :design-approved :design {:seq d} :at-seq d})
            st (standing/of-design :brian id (ws/entry-at-seq :brian id d))]
        (is (true? (:decided? st))
            "three supersessions, all before the design — nothing is unseated")
        (is (nil? (:superseded-after (:premise st))))))))

(deftest an-unrelated-later-baseline-supersedes-nothing-and-unseats-nothing
  ;; Measured on a live ledger: a second, narrower survey of a different area
  ;; written beside the first carries no :supersedes at all. Recency is exactly
  ;; what the citations exist to refuse.
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b1 (add :baseline a-baseline)
            _  (add :baseline-review {:format :baseline-review :verdict :sufficient
                                      :baseline-seq b1 :reason "ok"})
            d  (add :design (a-design b1))
            _  (add :design-approved {:format :design-approved :design {:seq d} :at-seq d})
            _  (add :baseline (assoc a-baseline :area "a different area entirely"))
            st (standing/of-design :brian id (ws/entry-at-seq :brian id d))]
        (is (true? (:decided? st)))
        (is (nil? (:superseded-after (:premise st))))))))

(deftest an-unreadable-verdict-makes-standing-indeterminate
  ;; :design-verdict joins the kinds standing depends on, so it joins the ones it
  ;; fails closed on. A verdict that silently does not invalidate is the same
  ;; formality an unreadable retraction would be.
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b (add :baseline a-baseline)
            _ (add :baseline-review {:format :baseline-review :verdict :sufficient
                                     :baseline-seq b :reason "ok"})
            d (add :design (a-design b))
            v (add :design-verdict (a-verdict d :invalidated))]
        (io/write-text! (str (fs/path (cstate/workstream-dir :brian id)
                                      (format "entries/%04d-design-verdict.edn" v)))
                        "{:format :design-verdict :truncated")
        (let [st (standing/of-design :brian id (ws/entry-at-seq :brian id d))]
          (is (true? (:indeterminate? st)))
          (is (= :unreadable-ledger (:reason (:blocked st)))))))))

;; ── A goal that moved ──────────────────────────────────────────────────────
;; The half `standing` owns: records that ALREADY EXIST when the goal moves.
;; The other half — a record written afterwards that stands on the replaced
;; goal, however many citations away — is refused at the append boundary, and
;; is tested there.

(deftest a-design-serving-a-goal-superseded-after-it-does-not-stand
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b (add :baseline a-baseline)
            _ (add :baseline-review {:format :baseline-review :verdict :sufficient
                                     :baseline-seq b :reason "it holds"})
            d (add :design (a-design b))
            i2 (add :intent {:format :intent :goal "a wider goal" :done-when ["d"]
                             :supersedes {:seq 1 :why "implementation found the scope wrong"}})
            st (standing/of-design :brian id (ws/entry-at-seq :brian id d))]
        (is (false? (:decidable? st)))
        (is (= :goal-superseded (:reason (:blocked st))))
        (is (= 1 (:seq (:blocked st))) "it names the goal that moved")
        (is (str/includes? (:detail (:blocked st)) "a goal nobody holds"))
        (is (= i2 (:goal-replaced-by (:premise st)))
            "the survey was scoped for the same goal, so it is owed too — which
             :blocked, naming the design's own goal first, cannot say")))))

(deftest a-goal-superseded-before-the-design-leaves-it-standing
  ;; The sequence guard, on the goal edge. A design written after the amendment
  ;; cites the live goal; the replaced one is history, not a debt.
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            i2 (add :intent {:format :intent :goal "a wider goal" :done-when ["d"]
                             :supersedes {:seq 1 :why "the scope moved"}})
            b  (add :baseline (assoc a-baseline :intent {:seq i2}))
            _  (add :baseline-review {:format :baseline-review :verdict :sufficient
                                      :baseline-seq b :reason "it holds"})
            d  (add :design (assoc (a-design b) :intent {:seq i2}))
            st (standing/of-design :brian id (ws/entry-at-seq :brian id d))]
        (is (true? (:decidable? st)) "nothing supersedes the goal it serves")))))

(deftest one-goal-edge-answers-for-the-design-and-its-baseline
  ;; An earlier cut asked two questions — had the design's goal moved, and had
  ;; the goal its BASELINE was scoped for moved — because a design could cite a
  ;; :triage while its baseline cited an :intent, and then lose one while keeping
  ;; the other. With :intent the only goal kind, root agreement refuses a design
  ;; whose baseline roots elsewhere, so the two edges always name one chain.
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b (add :baseline a-baseline)             ; scoped for goal 1
            _ (add :baseline-review {:format :baseline-review :verdict :sufficient
                                     :baseline-seq b :reason "it holds"})
            d (add :design (a-design b))             ; serves goal 1
            _ (add :intent {:format :intent :goal "a wider goal" :done-when ["d"]
                            :supersedes {:seq 1 :why "the scope moved"}})
            st (standing/of-design :brian id (ws/entry-at-seq :brian id d))]
        (is (false? (:decidable? st)))
        (is (= :goal-superseded (:reason (:blocked st)))
            "one reason, naming the goal — the survey beneath it was scoped for
             the same goal, so there is no second thing to report")
        (is (= 1 (:seq (:blocked st))))
        (is (some? (:goal-replaced-by (:premise st)))
            "and the premise still says the survey is owed too")))))

(deftest a-design-may-not-root-elsewhere-than-its-baseline
  ;; What makes the single edge sound. Without this the two could come apart and
  ;; one question could not answer for both.
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            i2 (add :intent {:format :intent :goal "a second goal" :done-when ["d"]})
            b  (add :baseline a-baseline)]          ; scoped for goal 1
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"reaches 2 goals"
             (add :design (assoc (a-design b) :intent {:seq i2}))))))))
