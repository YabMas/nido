(ns nido.coordinator.workstream-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]
   [nido.platform.core :as core]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.session :as sess]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.platform.io :as io]))

(def example-ws
  {:id            "ws-20260605-a1b2c3"
   :project       :brian
   :external-refs [{:adapter :notion :id "BR-4659"
                    :page-id "p" :url "u" :title "Firefox loading"}]
   :stage         :triaging
   :stage-history [{:at "2026-06-05T09:00:00Z" :stage :triaging}]
   :closed        nil
   :created-at    "2026-06-05T09:00:00Z"
   :entries       []})

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest schema-accepts-valid-workstream
  (is (m/validate ws/Workstream example-ws)))

(deftest schema-rejects-missing-id
  (is (not (m/validate ws/Workstream (dissoc example-ws :id)))))

(deftest schema-leaves-stage-open-so-a-foreign-record-stays-repairable
  ;; The vocabulary is closed at the SETTERS (create!/advance-stage!), not in the
  ;; schema — so a record that acquired a foreign stage some other way can still
  ;; be read, written and advanced back to a legal stage. Closing it here would
  ;; wedge every write! of such a record, including the one that repairs it.
  (is (m/validate ws/Workstream (assoc example-ws :stage :some-project-specific-stage))))

(deftest create-rejects-a-stage-outside-the-vocabulary
  (with-tmp
    (fn [_]
      ;; :implementing is a TICKET status, not a stage. It used to be written
      ;; happily and then ignored by every projection.
      (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workstream stage"
                                    (ws/create! :brian {:stage :implementing :external-refs []})))]
        (is (= :implementing (:stage (ex-data e))))
        (is (= :create! (:where (ex-data e))))))))

(deftest advance-stage-rejects-a-stage-outside-the-vocabulary
  (with-tmp
    (fn [_]
      (ws/write! example-ws)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workstream stage"
                            (ws/advance-stage! :brian (:id example-ws) :implementing)))
      (is (= :triaging (:stage (ws/read-ws :brian (:id example-ws))))
          "the refused stage is not written"))))

(deftest advance-stage-refuses-a-foreign-stage-even-when-unchanged
  ;; The guard runs ahead of the no-op check, so re-setting the stage a foreign
  ;; record already carries is refused rather than quietly accepted.
  (with-tmp
    (fn [_]
      (ws/write! (assoc example-ws :stage :implementing))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown workstream stage"
                            (ws/advance-stage! :brian (:id example-ws) :implementing))))))

(deftest a-foreign-record-can-still-be-advanced-back-to-a-legal-stage
  ;; The repair path the permissive schema exists for.
  (with-tmp
    (fn [_]
      (ws/write! (assoc example-ws :stage :implementing))
      (is (= :in-progress (:stage (ws/advance-stage! :brian (:id example-ws) :in-progress)))))))

(deftest every-storable-stage-is-accepted-by-both-setters
  (with-tmp
    (fn [_]
      (doseq [stage sess/storable-stages]
        (let [w (ws/create! :brian {:stage stage :external-refs []})]
          (is (= stage (:stage w)) (str "create! accepts " stage))
          (is (= :triage (:stage (ws/advance-stage! :brian (:id w) :triage)))
              (str "advance-stage! moves off " stage)))))))

(deftest mint-id-has-ws-prefix-and-is-unique
  (with-redefs [clock/now-iso (constantly "2026-06-05T09:00:00Z")]
    (let [a (ws/mint-id) b (ws/mint-id)]
      (is (str/starts-with? a "ws-20260605-"))
      (is (not= a b)))))

(deftest round-trip
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (ws/write! example-ws)
        (is (= example-ws (ws/read-ws :brian (:id example-ws)))))
      (finally (fs/delete-tree tmp)))))

(deftest read-ws-returns-nil-when-missing
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (is (nil? (ws/read-ws :brian "nope"))))
      (finally (fs/delete-tree tmp)))))

(deftest create-mints-id-and-seeds-stage-history
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        (let [w (ws/create! :brian {:stage :triaging
                                    :external-refs [{:adapter :notion :id "BR-1"}]})]
          (is (str/starts-with? (:id w) "ws-"))
          (is (= :triaging (:stage w)))
          (is (= [{:at "2026-06-05T09:00:00Z" :stage :triaging}] (:stage-history w)))
          (is (nil? (:closed w)))
          (is (= [] (:entries w)))
          (is (= w (ws/read-ws :brian (:id w))))))
      (finally (fs/delete-tree tmp)))))

(deftest advance-stage-appends-history-and-updates-stage
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T10:00:00Z")]
        (ws/write! example-ws)
        (let [updated (ws/advance-stage! :brian (:id example-ws) :ready)]
          (is (= :ready (:stage updated)))
          (is (= 2 (count (:stage-history updated))))
          (is (= {:at "2026-06-05T10:00:00Z" :stage :ready}
                 (last (:stage-history updated))))
          (is (= updated (ws/read-ws :brian (:id example-ws))))))
      (finally (fs/delete-tree tmp)))))

(deftest advance-stage-is-noop-when-stage-unchanged
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (ws/write! example-ws)
        (let [updated (ws/advance-stage! :brian (:id example-ws) :triaging)]
          (is (= 1 (count (:stage-history updated))))))
      (finally (fs/delete-tree tmp)))))

(deftest close-sets-closed-with-outcome
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T11:00:00Z")]
        (ws/write! example-ws)
        (let [closed (ws/close! :brian (:id example-ws) :dropped)]
          (is (= {:at "2026-06-05T11:00:00Z" :outcome :dropped} (:closed closed)))))
      (finally (fs/delete-tree tmp)))))

(deftest append-entry-writes-file-and-indexes-it
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T12:00:00Z")]
        (ws/write! example-ws)
        (let [path (ws/append-entry! :brian (:id example-ws)
                                     {:kind :note :session "sx"}
                                     "# Triage report\nbody")
              w    (ws/read-ws :brian (:id example-ws))
              e    (last (:entries w))]
          (is (str/ends-with? path "entries/0001-note.md"))
          (is (= "# Triage report\nbody" (slurp path)))
          (is (= 1 (:seq e)))
          (is (= :note (:kind e)))
          (is (= "sx" (:session e)))
          (is (= "entries/0001-note.md" (:file e)))
          (is (= "2026-06-05T12:00:00Z" (:at e)))))
      (finally (fs/delete-tree tmp)))))

(deftest append-entry-increments-seq
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T12:00:00Z")]
        (ws/write! example-ws)
        (ws/append-entry! :brian (:id example-ws) {:kind :note} "a")
        (let [p2 (ws/append-entry! :brian (:id example-ws) {:kind :plan} "b")]
          (is (str/ends-with? p2 "entries/0002-plan.md"))
          (is (= 2 (count (:entries (ws/read-ws :brian (:id example-ws))))))))
      (finally (fs/delete-tree tmp)))))

(deftest add-ref-appends-and-dedupes
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (let [w0 (ws/create! :brian {:stage :triaging :external-refs []})
              w1 (ws/add-ref! :brian (:id w0) {:adapter :notion :id "BR-9"})
              w2 (ws/add-ref! :brian (:id w0) {:adapter :notion :id "BR-9"})]
          (is (= 1 (count (:external-refs w1))))
          (is (= 1 (count (:external-refs w2))))))
      (finally (fs/delete-tree tmp)))))

(deftest find-by-ref-locates-the-workstream
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (let [w (ws/create! :brian {:stage :triaging
                                    :external-refs [{:adapter :notion :id "BR-42"}]})]
          (ws/create! :brian {:stage :triaging
                              :external-refs [{:adapter :notion :id "BR-99"}]})
          (is (= (:id w) (:id (ws/find-by-ref :brian :notion "BR-42"))))
          (is (nil? (ws/find-by-ref :brian :notion "BR-nope")))))
      (finally (fs/delete-tree tmp)))))

(deftest engagement-reads-sessions-off-disk
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        (let [w (ws/create! :brian {:stage :triaging})]
          (is (= :idle (ws/engagement :brian (:id w))))
          (sess/create! :brian (:id w) {:name "s1" :weight :light :autonomy nil})
          (is (= :active (ws/engagement :brian (:id w))))
          (ws/close! :brian (:id w) :dropped)
          (is (= :settled (ws/engagement :brian (:id w))))))
      (finally (fs/delete-tree tmp)))))

(deftest create-persists-intake
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian
                {:stage :incoming
                 :external-refs [{:adapter :slack-message :id "slack-C-1.0"}]
                 :intake {:trigger :triage-slack-bugs
                          :payload {:id "slack-C-1.0" :text "it broke"}}})]
        (is (= :incoming (:stage w)))
        (is (= :triage-slack-bugs (-> w :intake :trigger)))
        (is (= "it broke" (-> w :intake :payload :text)))
        ;; round-trips through validation on read
        (is (= w (ws/read-ws :brian (:id w))))))))

(deftest create-without-intake-is-valid
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :triaging})]
        (is (nil? (:intake w)))
        (is (= w (ws/read-ws :brian (:id w))))))))

(deftest schema-accepts-facets
  (is (m/validate ws/Workstream (assoc example-ws :facets {:app-domain ["Teacher"] :type "bug"}))))

(deftest schema-omits-facets-ok
  (is (m/validate ws/Workstream example-ws)))

(deftest create-threads-facets
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (let [w (ws/create! :brian {:stage :triaging :external-refs []
                                    :facets {:type "bug"}})]
          (is (= {:type "bug"} (:facets (ws/read-ws :brian (:id w)))))))
      (finally (fs/delete-tree tmp)))))

(deftest set-facets-updates-existing
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
          (ws/set-facets! :brian (:id w) {:app-domain ["Teacher"]})
          (is (= {:app-domain ["Teacher"]} (:facets (ws/read-ws :brian (:id w)))))))
      (finally (fs/delete-tree tmp)))))

(deftest set-facets-throws-on-absent
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (ws/set-facets! :brian "ws-nonexistent" {:type "bug"}))))
      (finally (fs/delete-tree tmp)))))

(deftest set-facets-empty-map-removes-key
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (let [w (ws/create! :brian {:stage :triaging :external-refs []
                                    :facets {:type "bug"}})]
          (ws/set-facets! :brian (:id w) {})
          (is (nil? (:facets (ws/read-ws :brian (:id w)))))))
      (finally (fs/delete-tree tmp)))))

(deftest append-entry-validates-and-stores-typed-event
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T12:00:00Z")]
        (ws/write! example-ws)
        (let [path (ws/append-entry! :brian (:id example-ws) {:kind :implementation-plan}
                                     (pr-str {:format :implementation-plan :summary "x"
                                              :direction "Round once" :effort :S}))]
          (is (str/ends-with? path "entries/0001-implementation-plan.edn"))
          (is (str/includes? (slurp path) ":implementation-plan"))
          (is (str/includes? (slurp path) "Round once"))))
      (finally (fs/delete-tree tmp)))))

(deftest append-entry-rejects-malformed-typed-event
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T12:00:00Z")]
        (ws/write! example-ws)
        (is (thrown? clojure.lang.ExceptionInfo
                     (ws/append-entry! :brian (:id example-ws) {:kind :blocker} "not a map"))))
      (finally (fs/delete-tree tmp)))))

(deftest append-to-ref-routes-to-existing-workstream
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :triaging
                                   :external-refs [{:adapter :notion :id "BR-1"}]})]
        (ws/append-to-ref! :brian "BR-1" {:kind :note} "hi")
        (let [w2 (ws/read-ws :brian (:id w))]
          (is (= (:id w) (:id (ws/find-by-ref-id :brian "BR-1"))) "found by ref id")
          (is (= "BR-1" (:id (first (:external-refs w2)))))
          (is (= 1 (count (:entries w2))) "entry appended to the workstream, not a ticket store")
          (is (= :note (:kind (last (:entries w2))))))))))

(deftest append-to-ref-mints-minimal-workstream-when-absent
  (with-tmp
    (fn [_]
      (ws/append-to-ref! :brian "BR-9" {:kind :note} "hi")
      (let [w (ws/find-by-ref-id :brian "BR-9")]
        (is (some? w) "a workstream was minted for the ref")
        (is (= "BR-9" (:id (first (:external-refs w)))))
        (is (= 1 (count (:entries w))))
        (is (= :triaging (:stage w)) "minted workstream starts at :triaging")
        (is (= :notion (:adapter (first (:external-refs w))))
            "non-slack id infers a :notion adapter")))))

(deftest append-to-ref-infers-slack-adapter
  (with-tmp
    (fn [_]
      (ws/append-to-ref! :brian "slack-C1-100.5" {:kind :note} "hi")
      (let [w (ws/find-by-ref-id :brian "slack-C1-100.5")]
        (is (some? w) "a workstream was minted for the ref")
        (is (= "slack-C1-100.5" (:id (first (:external-refs w)))))
        (is (= :slack-message (:adapter (first (:external-refs w))))
            "slack- prefixed id infers a :slack-message adapter")))))

(deftest read-ws-normalizes-legacy-inbox-stage
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T09:00:00Z")]
        ;; A record persisted before the rename carries the legacy :stage :inbox.
        ;; Written directly: create! refuses it now — :inbox is read-legacy only,
        ;; deliberately outside the storable vocabulary.
        (let [w (ws/write! (assoc example-ws :stage :inbox))]
          (is (= :incoming (:stage (ws/read-ws :brian (:id w))))
              "legacy :inbox is mapped to :incoming on read")))
      (finally (fs/delete-tree tmp)))))

;; ── A design's baseline ref has to resolve ─────────────────────────────────
;; The schema sees one record and cannot join it to the ledger, so this is the
;; one place the citation can be checked. A dangling :seq reads downstream
;; exactly like a real one — the design would look judged when it was not.

(def ^:private a-baseline
  {:format       :baseline
   :area         "order totalling"
   :bounded-by   "everything that reads or writes a money amount on an order"
   :shape        "The aggregate is the only thing that sums lines."
   :modules      [{:id "mod-the-order-aggregate" :module "the order aggregate"
                   :hides "the order in which lines are summed"
                   :interface "an order's total"}]
   :composition  "Only the aggregate can see the lines, so only it can sum them."
   :load-bearing [{:id "c1" :property "the aggregate is the only summing path"
                   :falsified-by "a caller outside the aggregate that reads lines and sums them"
                   :readings [{:lens :parnas/dependency :verdict :on-interface
                               :because "callers take the total, never the lines"}]
                   :evidence ["src/order/aggregate.clj:12"]}]
   :read         ["src/order/aggregate.clj"]})

(def ^:private a-triage
  {:format :triage-report :ticket-key "BR-7" :determination :bug
   :title "Checkout off by a cent" :summary "Rounding applied per line."
   :confidence {:level :high :reason "reproduced"}
   :directions [{:label "A" :shape "round once on the total" :effort :M
                 :confidence {:level :medium :reason "money math"}}]
   :notion-writes nil
   :trail [{:ref "src/order.clj:88" :note "per-line round here"}]})

(def ^:private an-intent
  {:format    :intent
   :goal      "Totals match the invoice."
   :done-when ["a multi-line order's total equals the sum of its invoice lines"]})

(defn- design-citing
  "A design citing baseline `n` and, unless overridden, the intent this suite
   seeds at seq 2."
  ([n] (design-citing n 2))
  ([n intent-seq]
   (cond-> {:format     :design
            :summary    "Round on the total."
            :shape      "One rounding boundary at the aggregate."
            :invariants ["a total is rounded exactly once"]
            :standing   {:relation :conforms}
            :baseline   {:seq n :relation :within}
            :effort     :M}
     intent-seq (assoc :intent {:seq intent-seq}))))

(defn- seed-intent!
  "Append the intent entry the default design-citing points at. Callers append a
   baseline first, so the intent lands at seq 2."
  [w]
  (ws/append-entry! :brian (:id w) {:kind :intent} (pr-str an-intent)))

(deftest design-may-cite-a-real-baseline-on-the-same-workstream
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (ws/append-entry! :brian (:id w) {:kind :baseline} (pr-str a-baseline))
        (seed-intent! w)
        (ws/append-entry! :brian (:id w) {:kind :design} (pr-str (design-citing 1)))
        (is (= 1 (get-in (ws/latest-entry :brian (:id w) :design) [:baseline :seq])))
        (is (= 2 (get-in (ws/latest-entry :brian (:id w) :design) [:intent :seq])))))))

(deftest design-citing-a-baseline-that-does-not-exist-is-refused
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (is (thrown? clojure.lang.ExceptionInfo
                     (ws/append-entry! :brian (:id w) {:kind :design}
                                       (pr-str (design-citing 4)))))
        (is (empty? (:entries (ws/read-ws :brian (:id w))))
            "refused before anything is written — no orphan entry file")))))

;; ── Routing the cited baseline's health is total ───────────────────────────
;; The baseline observes and cannot route; the design routes and cannot observe.
;; Here is the only place both are in hand, so it is the only place "nothing is
;; lost and nothing is smuggled" can be made true rather than intended.

(def ^:private a-baseline-with-health
  (assoc a-baseline
         :health [{:id "invoice-resums" :axis :design
                   :observation "two summing paths where the design claims one"
                   :evidence ["src/order/invoice.clj:88"]}
                  {:id "half-migrated" :axis :implementation
                   :observation "two call sites migrated, eight not"
                   :evidence ["src/order/calc.clj:5"]
                   :invisibly-incomplete? true}]))

(defn- design-routing [n routes]
  (assoc (design-citing n) :routes routes))

(defn- with-health-baseline
  "Mint a workstream carrying the health baseline at seq 1, then run `f` on it."
  [f]
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (ws/append-entry! :brian (:id w) {:kind :baseline}
                          (pr-str a-baseline-with-health))
        (seed-intent! w)
        (f w)))))

(deftest design-routing-every-health-observation-is-accepted
  (with-health-baseline
    (fn [w]
      (ws/append-entry!
       :brian (:id w) {:kind :design}
       (pr-str (design-routing 1 [{:health-id "invoice-resums" :to :spin-out
                                   :why "revealed, not created" :ref "FU-88"}
                                  {:health-id "half-migrated" :to :fix-here}])))
      (is (= 2 (count (:routes (ws/latest-entry :brian (:id w) :design))))))))

(deftest design-leaving-a-health-observation-unrouted-is-refused
  (with-health-baseline
    (fn [w]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"unrouted|half-migrated"
           (ws/append-entry!
            :brian (:id w) {:kind :design}
            (pr-str (design-routing 1 [{:health-id "invoice-resums" :to :fix-here}])))))
      (is (= 2 (count (:entries (ws/read-ws :brian (:id w)))))
          "refused before anything is written — only the baseline and the intent
           it was seeded with are there"))))

(deftest design-routing-an-observation-the-baseline-never-made-is-refused
  (with-health-baseline
    (fn [w]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"does not record"
           (ws/append-entry!
            :brian (:id w) {:kind :design}
            (pr-str (design-routing 1 [{:health-id "invoice-resums" :to :fix-here}
                                       {:health-id "half-migrated" :to :fix-here}
                                       {:health-id "invented" :to :declined
                                        :why "nobody observed this"}]))))))))

(deftest design-routing-one-observation-twice-is-refused
  (with-health-baseline
    (fn [w]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"more than once"
           (ws/append-entry!
            :brian (:id w) {:kind :design}
            (pr-str (design-routing 1 [{:health-id "invoice-resums" :to :fix-here}
                                       {:health-id "invoice-resums" :to :declined
                                        :why "changed my mind halfway down"}
                                       {:health-id "half-migrated" :to :fix-here}]))))
          "exactly once — two destinations for one observation is not a routing"))))

(deftest spinning-out-invisible-incompleteness-is-vetoed
  (with-health-baseline
    (fn [w]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"invisibly incomplete"
           (ws/append-entry!
            :brian (:id w) {:kind :design}
            (pr-str (design-routing 1 [{:health-id "invoice-resums" :to :fix-here}
                                       {:health-id "half-migrated" :to :spin-out
                                        :why "feels separate" :ref "FU-99"}]))))
          "the one rule in /spin-out that is not a judgment call, enforced where
           it cannot depend on remembering to be principled"))))

(deftest invisible-incompleteness-may-still-constrain-or-be-fixed
  (with-health-baseline
    (fn [w]
      (ws/append-entry!
       :brian (:id w) {:kind :design}
       (pr-str (design-routing 1 [{:health-id "invoice-resums" :to :declined
                                   :why "cold corner"}
                                  {:health-id "half-migrated" :to :constrains
                                   :why "this change may not add a ninth unmigrated site"}])))
      (is (ws/latest-entry :brian (:id w) :design)
          "the veto is on deferring it, not on the observation existing"))))

(deftest a-baseline-with-no-health-routes-vacuously
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (ws/append-entry! :brian (:id w) {:kind :baseline} (pr-str a-baseline))
        (seed-intent! w)
        (ws/append-entry! :brian (:id w) {:kind :design} (pr-str (design-citing 1)))
        (is (ws/latest-entry :brian (:id w) :design)
            "every design record written before health existed still appends")))))

(deftest a-design-may-cite-a-triage-entry-as-its-intent
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (ws/append-entry! :brian (:id w) {:kind :baseline} (pr-str a-baseline))
        (ws/append-entry! :brian (:id w) {:kind :triage} (pr-str a-triage))
        (ws/append-entry! :brian (:id w) {:kind :design} (pr-str (design-citing 1 2)))
        (is (= 2 (get-in (ws/latest-entry :brian (:id w) :design) [:intent :seq]))
            "a workstream whose intent was written down at triage does not
             restate it")))))

(deftest a-design-citing-an-entry-that-states-no-intent-is-refused
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (ws/append-entry! :brian (:id w) {:kind :baseline} (pr-str a-baseline))
        (ws/append-entry! :brian (:id w) {:kind :blocker}
                          (pr-str {:format :blocker :summary "s" :needs "n"}))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"neither an :intent nor a :triage"
             (ws/append-entry! :brian (:id w) {:kind :design}
                               (pr-str (design-citing 1 2)))))
        (is (= 2 (count (:entries (ws/read-ws :brian (:id w)))))
            "refused before anything is written")))))

(deftest a-design-citing-an-intent-that-does-not-exist-is-refused
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (ws/append-entry! :brian (:id w) {:kind :baseline} (pr-str a-baseline))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"neither an :intent nor a :triage"
             (ws/append-entry! :brian (:id w) {:kind :design}
                               (pr-str (design-citing 1 9)))))))))

(deftest design-citing-an-entry-that-is-not-a-baseline-is-refused
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (ws/append-entry! :brian (:id w) {:kind :note} "just a note")
        (is (thrown? clojure.lang.ExceptionInfo
                     (ws/append-entry! :brian (:id w) {:kind :design}
                                       (pr-str (design-citing 1))))
            "seq 1 exists, but it is a note — the ref must name a baseline")))))

(defn- phased-design-citing
  "A two-phase design on baseline `n`, with a seam that `phase` closes. Same
   shape as design-citing, one axis over: the ledger has to check a claim inside
   the record against another field of it, which Malli cannot express."
  [n phase]
  {:format     :design
   :summary    "The address moves to its own column."
   :shape      "Two writers during the migration; one reader throughout."
   :invariants [{:invariant "no request reads a column no writer maintains" :holds :always}]
   :standing   {:relation :conforms}
   :intent     {:seq 2}
   :baseline   {:seq n :relation :within}
   :phases     [{:claim     "both writers maintain the new column"
                 :habitable "nothing reads the new column yet"
                 :exit      {:kind :observation :criterion "discrepancy counter flat at zero for 7 days"}
                 :undo      {:how :revert :by "stop dual-writing"}}
                {:claim     "the old column is dropped"
                 :habitable "one writer, one reader — the end state"
                 :exit      {:kind :completion :criterion "nothing follows"}
                 :undo      {:how :none :why "the data is gone"}}]
   :seams      [{:what "the old column is still written through phase 1"
                 :visible-how "both writers sit side by side in one namespace"
                 :closed-by :phase :phase phase}]
   :effort     :L})

(deftest a-seam-may-name-a-phase-that-is-in-the-plan
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (ws/append-entry! :brian (:id w) {:kind :baseline} (pr-str a-baseline))
        (seed-intent! w)
        (ws/append-entry! :brian (:id w) {:kind :design}
                          (pr-str (phased-design-citing 1 "the old column is dropped")))
        (is (= 2 (count (:phases (ws/latest-entry :brian (:id w) :design)))))))))

(deftest a-seam-naming-a-phase-that-is-not-in-the-plan-is-refused
  ;; A seam pointing at a phantom phase reads downstream exactly like one
  ;; pointing at a real phase — the seam looks scheduled for closure when
  ;; nothing schedules it. Same failure as a dangling :baseline :seq.
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (ws/append-entry! :brian (:id w) {:kind :baseline} (pr-str a-baseline))
        (is (thrown? clojure.lang.ExceptionInfo
                     (ws/append-entry! :brian (:id w) {:kind :design}
                                       (pr-str (phased-design-citing 1 "some later phase")))))
        (is (= 1 (count (:entries (ws/read-ws :brian (:id w)))))
            "refused before anything is written — only the baseline is on the ledger")))))

(deftest a-seam-cannot-promise-a-phase-on-a-record-with-no-phase-plan
  ;; The rule the schema deliberately does not encode: with no plan there is
  ;; nothing for the claim to match, so the promise is refused rather than left
  ;; standing as a closure nobody scheduled.
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (ws/append-entry! :brian (:id w) {:kind :baseline} (pr-str a-baseline))
        (is (thrown? clojure.lang.ExceptionInfo
                     (ws/append-entry! :brian (:id w) {:kind :design}
                                       (pr-str (-> (phased-design-citing 1 "the old column is dropped")
                                                   (dissoc :phases)
                                                   (assoc :invariants ["no request reads a column no writer maintains"]))))))))))

(deftest a-legacy-design-entry-on-disk-still-reads-back
  ;; The migration case, at the level that matters: a record written before
  ;; :baseline existed is still there, and latest-entry must not quietly stop
  ;; seeing it. It validates on read and swallows the failure, so the symptom of
  ;; getting this wrong is not an error — it is the design silently going absent.
  (with-tmp
    (fn [_]
      (let [w   (ws/create! :brian {:stage :in-progress :external-refs []})
            old {:format     :design
                 :summary    "Round on the total."
                 :shape      "One rounding boundary at the aggregate."
                 :invariants ["a total is rounded exactly once"]
                 :standing   {:relation :conforms}
                 :assumes    [{:about "totals are per-item" :read ["src/order/calc.clj"]}]
                 :effort     :M}
            dir (cstate/workstream-dir :brian (:id w))]
        ;; written by hand: append-entry! would refuse it now, which is the point
        (io/write-text! (str (fs/path dir "entries/0001-design.edn")) (pr-str old))
        (ws/write! (assoc w :entries [{:kind :design :seq 1 :at "2026-01-01T00:00:00Z"
                                       :file "entries/0001-design.edn"}]))
        (is (= old (dissoc (ws/latest-entry :brian (:id w) :design) :seq :at))
            "the pre-baseline record is still readable, :assumes and all")))))

;; ── Following a citation ───────────────────────────────────────────────────

(deftest entry-at-seq-follows-the-citation-not-the-latest
  ;; A workstream may baseline twice. The design was judged against ONE of them,
  ;; and handing a later baseline to the review would check the change against a
  ;; yardstick its author never saw.
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (ws/append-entry! :brian (:id w) {:kind :baseline} (pr-str a-baseline))
        (ws/append-entry! :brian (:id w) {:kind :baseline}
                          (pr-str (assoc a-baseline :area "a second, wider baseline")))
        (is (= "order totalling" (:area (ws/entry-at-seq :brian (:id w) 1))))
        (is (= "a second, wider baseline" (:area (ws/entry-at-seq :brian (:id w) 2))))
        (is (= "a second, wider baseline" (:area (ws/latest-entry :brian (:id w) :baseline)))
            "latest and cited genuinely differ here — which is why the reader
             following a :seq cannot be latest-entry")
        (is (nil? (ws/entry-at-seq :brian (:id w) 9))
            "a reader following a citation must find nothing without crashing")))))

;; ── The edges standing walks ───────────────────────────────────────────────

(deftest every-edge-standing-walks-must-resolve
  ;; A :seq pointing at nothing reads downstream exactly like one pointing at a
  ;; real record, so a retracted premise reached through a dangling edge would
  ;; answer "no retraction found" rather than "this edge is broken".
  (with-tmp
    (fn [_]
      (let [w   (ws/create! :brian {:stage :in-progress :external-refs []})
            id  (:id w)
            add #(ws/append-entry! :brian id {:kind %1} (pr-str %2))]
        (add :baseline a-baseline)                                    ; seq 1
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Retraction cites entry 99"
             (add :retraction {:format :retraction :retracts {:seq 99}
                               :because "b" :evidence ["src/a.clj:1"]}))
            "a retraction naming no entry")
        (is (some? (add :retraction {:format :retraction :retracts {:seq 1}
                                     :because "b" :evidence ["src/a.clj:1"]}))
            "and one naming a real baseline is fine")
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Approval cites entry 1, which is a :baseline"
             (add :design-approved {:format :design-approved :design {:seq 1} :at-seq 2}))
            "an approval must name a design, and the refusal says what it found")
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"Baseline :supersedes cites entry 2"
             (add :baseline (assoc a-baseline :supersedes {:seq 2 :why "corrected"})))
            "a baseline correcting a retraction is not a correction")
        (is (some? (add :baseline (assoc a-baseline :supersedes {:seq 1 :why "corrected"})))
            "a baseline correcting a baseline is")))))

(deftest a-citation-standing-never-reads-is-left-alone
  ;; Validating every number in the ledger is a different change, and one the
  ;; design turned down: :blocker-seq is not an edge standing follows.
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (is (some? (ws/append-entry!
                    :brian (:id w) {:kind :blocker-answered}
                    (pr-str {:format :blocker-answered :blocker-seq 99
                             :letter "a" :label "go ahead"
                             :summary "the option the human picked"}))))))))

;; ── Concurrent appends ──────────────────────────────────────────────────────

(deftest concurrent-appends-each-take-their-own-sequence-number
  ;; The failure this guards is a LOST UPDATE, not a torn write. Two writers read
  ;; the same index, derive the same :seq, write the same filename — the second
  ;; over the first — and each write an index claiming that count. One append
  ;; vanishes and the ledger looks consistent afterwards, which is what makes it
  ;; worth a test: nothing downstream can notice.
  ;;
  ;; Threads rather than processes, deliberately. A file lock is held PER JVM, so
  ;; threads are the half it does not cover on its own — without the interned
  ;; monitor beside it the second thread raises OverlappingFileLockException
  ;; instead of queueing.
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (let [id  (:id (ws/create! :brian {:stage :in-progress :external-refs []}))
              n   12
              res (->> (range n)
                       (mapv (fn [i]
                               (future
                                 (try (ws/append-entry! :brian id
                                                        {:kind (keyword (str "note" i))}
                                                        (str "body " i))
                                      (catch Throwable t (str "THREW " (ex-message t)))))))
                       (mapv deref))
              w   (ws/read-ws :brian id)]
          (is (empty? (filter string? (filter #(str/starts-with? (str %) "THREW") res)))
              "no writer was refused")
          (is (= n (count (:entries w))))
          (is (= n (count (distinct (map :seq (:entries w)))))
              "every append took its own sequence number")
          (is (= (set (range 1 (inc n))) (set (map :seq (:entries w))))
              "the sequence is dense — no number skipped, none reused")
          (is (= n (count (fs/list-dir (fs/path (cstate/workstream-dir :brian id) "entries"))))
              "one file per append, none written over")
          (is (= (set (map #(keyword (str "note" %)) (range n)))
                 (set (map :kind (:entries w))))
              "every writer's own payload survived")))
      (finally (fs/delete-tree tmp)))))
