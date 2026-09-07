;; test/nido/coordinator/lane/reentry_test.clj
(ns nido.coordinator.lane.reentry-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.lane.reentry :as reentry]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.workstream :as ws]))

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

(defn- a-design [b & [sup]]
  (cond-> {:format :design :summary "s" :shape "sh" :invariants ["one summing path"]
           :standing {:relation :conforms}
           :baseline {:seq b :relation :within}
           :intent {:seq 1} :effort :S}
    sup (assoc :supersedes {:seq sup :why "the cut moved"})))

(defn- ledger []
  (let [id (:id (ws/create! :brian {:stage :in-progress :external-refs []}))]
    (ws/append-entry! :brian id {:kind :intent}
                      (pr-str {:format :intent :goal "g" :done-when ["d"]}))
    [id (fn [kind record]
          (ws/append-entry! :brian id {:kind kind} (pr-str record))
          (count (:entries (ws/read-ws :brian id))))]))

(defn- approved!
  "A workstream standing on a verified baseline with an approved design.
   Returns [id add design-seq]."
  []
  (let [[id add] (ledger)
        b (add :baseline a-baseline)
        _ (add :baseline-review {:format :baseline-review :verdict :sufficient
                                 :baseline-seq b :reason "holds"})
        d (add :design (a-design b))
        _ (add :design-approved {:format :design-approved :design {:seq d} :at-seq d})]
    [id add d]))

;; ── generation, which is the whole of the trail question ────────────────────

(deftest a-trail-record-belongs-to-the-newest-design-before-it
  (let [w {:entries [{:kind :design :seq 5}
                     {:kind :implementation-completed :seq 7}
                     {:kind :design :seq 9}
                     {:kind :review :seq 11}]}]
    (let [{:keys [current stale]} (reentry/trail-standing w 9)]
      (is (= [{:kind :implementation-completed :seq 7 :under 5}] stale)
          "the implementation predates design 9; the review does not")
      (is (= #{:review} current)))
    (is (= [] (:stale (reentry/trail-standing w 5)))
        "and against design 5 nothing is BEHIND — the review at 11 sits under a
         later design, which callers never ask about but which must not read as
         rotten if they do")))

(deftest a-stage-is-current-when-ANY-of-its-records-is
  ;; The rule that lets a workstream escape. Asking that EVERY record be current
  ;; makes the first stale one permanent: a workstream that redesigned,
  ;; re-approved and re-implemented would still be held at the implementation by
  ;; the entry it had just superseded, and could never reach the review again.
  (let [w {:entries [{:kind :design :seq 1}
                     {:kind :implementation-completed :seq 2}
                     {:kind :design :seq 3}
                     {:kind :implementation-completed :seq 4}]}
        {:keys [current stale]} (reentry/trail-standing w 3)]
    (is (= #{:implementation-completed} current)
        "the redone work counts, whatever the superseded entry beside it says")
    (is (= [{:kind :implementation-completed :seq 2 :under 1}] stale)
        "and the old record is still reported — history, not a debt")))

(deftest a-trail-record-with-no-design-before-it-is-not-attributed
  ;; The refusal to guess. Work done before any design exists cannot be blamed on
  ;; one, and inventing a generation for it would report every pre-design branch
  ;; as stale forever.
  (let [w {:entries [{:kind :pr-opened :seq 2}
                     {:kind :design :seq 4}]}]
    (is (= [] (:stale (reentry/trail-standing w 4))))
    (is (= #{} (:current (reentry/trail-standing w 4))))))

(deftest every-trail-kind-is-checked
  (let [w {:entries (into [{:kind :design :seq 1}]
                          (map-indexed (fn [i k] {:kind k :seq (+ 2 i)})
                                       [:implementation-completed :review
                                        :pr-opened :merged]))}]
    ;; design 1 is current, so nothing is stale and all four stand…
    (is (= [] (:stale (reentry/trail-standing w 1))))
    (is (= 4 (count (:current (reentry/trail-standing w 1)))))
    ;; …and against a design appended after all of them, all four are behind.
    (let [w2 (update w :entries conj {:kind :design :seq 9})]
      (is (= 4 (count (:stale (reentry/trail-standing w2 9))))
          "all four kinds place a stage by presence, so all four need a generation")
      (is (= #{} (:current (reentry/trail-standing w2 9)))))))

;; ── the composed answer ─────────────────────────────────────────────────────

(deftest an-approved-design-with-a-current-trail-needs-no-re-entry
  (with-tmp
    (fn [_]
      (let [[id _] (approved!)]
        (is (nil? (reentry/of :brian id)))
        (testing "and still none once work is done UNDER that design"
          (ws/append-entry! :brian id {:kind :implementation-completed}
                            (pr-str {:format :implementation-completed
                                     :summary "done" :artifacts []}))
          (is (nil? (reentry/of :brian id))))))))

(deftest a-workstream-with-no-design-has-nothing-to-come-back-to
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)]
        (add :baseline a-baseline)
        (is (nil? (reentry/of :brian id))
            "placed by its record trail exactly as before")))))

(deftest an-unapproved-design-sends-it-back-to-the-approval
  (with-tmp
    (fn [_]
      (let [[id add] (ledger)
            b (add :baseline a-baseline)
            _ (add :baseline-review {:format :baseline-review :verdict :sufficient
                                     :baseline-seq b :reason "holds"})
            d (add :design (a-design b))
            r (reentry/of :brian id)]
        (is (= :approval (:stage r)))
        (is (= :not-approved (:reason (:because r))))
        (is (= d (:seq (:because r))))))))

(deftest an-unseated-design-sends-it-back-to-the-design
  (with-tmp
    (fn [_]
      (let [[id add d] (approved!)]
        (is (nil? (reentry/of :brian id)))
        (add :design-verdict {:format :design-verdict :verdict :invalidated
                              :round 1 :design-seq d :reason "a second path sums"
                              :invariants-broken [{:invariant "one summing path"
                                                   :finding "the renderer sums"}]
                              :needs "redesign the seam"})
        (let [r (reentry/of :brian id)]
          (is (= :design (:stage r)))
          (is (= :design-invalidated (:reason (:because r)))
              "and it carries standing's own reason rather than a translation"))))))

(deftest implementation-done-under-a-superseded-design-sends-it-back-to-implement
  ;; The live case. fukan/ws-20260831-1ba009 holds exactly this shape: approved at
  ;; 83, implemented at 84, then six more designs — and it reported :implemented.
  (with-tmp
    (fn [_]
      (let [[id add d1] (approved!)]
        (add :implementation-completed {:format :implementation-completed
                                        :summary "done" :artifacts []})
        (is (nil? (reentry/of :brian id)) "current while the design is")
        (let [b (get-in (ws/latest-entry :brian id :design) [:baseline :seq])
              d2 (add :design (a-design b d1))
              _ (add :design-approved {:format :design-approved :design {:seq d2}
                                       :at-seq d2})
              r (reentry/of :brian id)]
          (is (= :implementation (:stage r))
              "the new design is approved, so the design and approval are fine —
               what is stale is the code written against the old one")
          (is (= #{} (:trail r)) "and no trail stage may be reported")
          (is (= :trail-superseded (:reason (:because r))))
          (is (= [{:kind :implementation-completed :seq 6 :under d1}]
                 (:records (:because r)))
              "and it names the record and the design it was attributed to"))))))

(deftest the-design-question-outranks-the-trail-question
  ;; Precedence is a claim: a workstream whose design does not stand AND whose
  ;; trail is stale has one answer, and it is the innermost one. Reporting
  ;; :implementation would send somebody to rewrite code against a design nobody
  ;; has re-established.
  (with-tmp
    (fn [_]
      (let [[id add d1] (approved!)]
        (add :implementation-completed {:format :implementation-completed
                                        :summary "done" :artifacts []})
        (let [b  (get-in (ws/latest-entry :brian id :design) [:baseline :seq])
              d2 (add :design (a-design b d1))]
          (is (= :approval (:stage (reentry/of :brian id)))
              "the trail is stale too, and the approval is the nearer answer")
          (add :design-verdict {:format :design-verdict :verdict :invalidated
                                :round 1 :design-seq d2 :reason "no"
                                :invariants-broken [{:invariant "i" :finding "f"}]
                                :needs "redesign"})
          (is (= :design (:stage (reentry/of :brian id)))
              "and the design is nearer still"))))))

(deftest of*-is-pure-and-answers-the-same-as-of
  ;; The arity the render path uses. It must not be able to disagree with the one
  ;; that reads for itself — that would be the two-answers defect, one level down.
  (with-tmp
    (fn [_]
      (let [[id add d1] (approved!)]
        (add :implementation-completed {:format :implementation-completed
                                        :summary "done" :artifacts []})
        (let [b  (get-in (ws/latest-entry :brian id :design) [:baseline :seq])
              d2 (add :design (a-design b d1))
              _  (add :design-approved {:format :design-approved :design {:seq d2}
                                        :at-seq d2})
              w  (ws/read-ws :brian id)
              d  (ws/latest-entry :brian id :design)
              st (standing/of-design :brian id d)]
          (is (= (reentry/of :brian id) (reentry/of* w d st))))))))

(deftest an-indeterminate-standing-sends-it-back-to-the-design
  ;; Fails closed, like the standing under it: a ledger nobody can read is not a
  ;; workstream anybody may advance.
  (is (= :design (:stage (reentry/of* {:entries []} {:seq 3}
                                      {:indeterminate? true
                                       :blocked {:reason :unreadable-ledger}})))))

(deftest redoing-the-work-clears-the-stage-and-leaves-the-ones-above-it-owed
  ;; The escape. Found by walking the whole arc rather than by a unit test: an
  ;; earlier rule asked that EVERY trail record be current, so a workstream that
  ;; had redesigned, re-approved and re-implemented was still held at the
  ;; implementation by the entry it had just superseded.
  (with-tmp
    (fn [_]
      (let [[id add d1] (approved!)]
        (add :implementation-completed {:format :implementation-completed
                                        :summary "first" :artifacts []})
        (add :pr-opened {:format :pr-opened :url "u" :title "t"})
        (let [b  (get-in (ws/latest-entry :brian id :design) [:baseline :seq])
              d2 (add :design (a-design b d1))]
          (add :design-approved {:format :design-approved :design {:seq d2} :at-seq d2})
          (is (= :implementation (:stage (reentry/of :brian id)))
              "both the implementation and the PR are behind")
          (add :implementation-completed {:format :implementation-completed
                                          :summary "redone" :artifacts []})
          (let [r (reentry/of :brian id)]
            (is (= :publication (:stage r))
                "the implementation is current again, so the PR is what is owed")
            (is (= #{:implementation-completed} (:trail r))
                "and the fold may report the implementation, but nothing above it")))))))
