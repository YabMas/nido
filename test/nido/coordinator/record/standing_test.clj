;; test/nido/coordinator/record/standing_test.clj
(ns nido.coordinator.record.standing-test
  (:require
   [babashka.fs :as fs]
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
