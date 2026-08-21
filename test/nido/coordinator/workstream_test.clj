(ns nido.coordinator.workstream-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [malli.core :as m]
   [nido.coordinator.clock :as clock]
   [nido.coordinator.session :as sess]
   [nido.coordinator.state :as cstate]
   [nido.coordinator.workstream :as ws]
   [nido.io :as io]))

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
      (with-redefs [cstate/nido-root (constantly (str tmp))]
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
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (ws/write! example-ws)
        (is (= example-ws (ws/read-ws :brian (:id example-ws)))))
      (finally (fs/delete-tree tmp)))))

(deftest read-ws-returns-nil-when-missing
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (is (nil? (ws/read-ws :brian "nope"))))
      (finally (fs/delete-tree tmp)))))

(deftest create-mints-id-and-seeds-stage-history
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
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
      (with-redefs [cstate/nido-root (constantly (str tmp))
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
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (ws/write! example-ws)
        (let [updated (ws/advance-stage! :brian (:id example-ws) :triaging)]
          (is (= 1 (count (:stage-history updated))))))
      (finally (fs/delete-tree tmp)))))

(deftest close-sets-closed-with-outcome
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
                    clock/now-iso (constantly "2026-06-05T11:00:00Z")]
        (ws/write! example-ws)
        (let [closed (ws/close! :brian (:id example-ws) :dropped)]
          (is (= {:at "2026-06-05T11:00:00Z" :outcome :dropped} (:closed closed)))))
      (finally (fs/delete-tree tmp)))))

(deftest append-entry-writes-file-and-indexes-it
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
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
      (with-redefs [cstate/nido-root (constantly (str tmp))
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
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (let [w0 (ws/create! :brian {:stage :triaging :external-refs []})
              w1 (ws/add-ref! :brian (:id w0) {:adapter :notion :id "BR-9"})
              w2 (ws/add-ref! :brian (:id w0) {:adapter :notion :id "BR-9"})]
          (is (= 1 (count (:external-refs w1))))
          (is (= 1 (count (:external-refs w2))))))
      (finally (fs/delete-tree tmp)))))

(deftest find-by-ref-locates-the-workstream
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
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
      (with-redefs [cstate/nido-root (constantly (str tmp))
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
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (let [w (ws/create! :brian {:stage :triaging :external-refs []
                                    :facets {:type "bug"}})]
          (is (= {:type "bug"} (:facets (ws/read-ws :brian (:id w)))))))
      (finally (fs/delete-tree tmp)))))

(deftest set-facets-updates-existing
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (let [w (ws/create! :brian {:stage :triaging :external-refs []})]
          (ws/set-facets! :brian (:id w) {:app-domain ["Teacher"]})
          (is (= {:app-domain ["Teacher"]} (:facets (ws/read-ws :brian (:id w)))))))
      (finally (fs/delete-tree tmp)))))

(deftest set-facets-throws-on-absent
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (is (thrown? clojure.lang.ExceptionInfo
                     (ws/set-facets! :brian "ws-nonexistent" {:type "bug"}))))
      (finally (fs/delete-tree tmp)))))

(deftest set-facets-empty-map-removes-key
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))]
        (let [w (ws/create! :brian {:stage :triaging :external-refs []
                                    :facets {:type "bug"}})]
          (ws/set-facets! :brian (:id w) {})
          (is (nil? (:facets (ws/read-ws :brian (:id w)))))))
      (finally (fs/delete-tree tmp)))))

(deftest append-entry-validates-and-stores-typed-event
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [cstate/nido-root (constantly (str tmp))
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
      (with-redefs [cstate/nido-root (constantly (str tmp))
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
      (with-redefs [cstate/nido-root (constantly (str tmp))
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
   :load-bearing [{:property "the aggregate is the only summing path"
                   :evidence ["src/order/aggregate.clj:12"]}]
   :read         ["src/order/aggregate.clj"]})

(defn- design-citing [n]
  {:format     :design
   :summary    "Round on the total."
   :shape      "One rounding boundary at the aggregate."
   :invariants ["a total is rounded exactly once"]
   :standing   {:relation :conforms}
   :baseline   {:seq n :relation :within}
   :effort     :M})

(deftest design-may-cite-a-real-baseline-on-the-same-workstream
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (ws/append-entry! :brian (:id w) {:kind :baseline} (pr-str a-baseline))
        (ws/append-entry! :brian (:id w) {:kind :design} (pr-str (design-citing 1)))
        (is (= 1 (get-in (ws/latest-entry :brian (:id w) :design) [:baseline :seq])))))))

(deftest design-citing-a-baseline-that-does-not-exist-is-refused
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (is (thrown? clojure.lang.ExceptionInfo
                     (ws/append-entry! :brian (:id w) {:kind :design}
                                       (pr-str (design-citing 4)))))
        (is (empty? (:entries (ws/read-ws :brian (:id w))))
            "refused before anything is written — no orphan entry file")))))

(deftest design-citing-an-entry-that-is-not-a-baseline-is-refused
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (ws/append-entry! :brian (:id w) {:kind :note} "just a note")
        (is (thrown? clojure.lang.ExceptionInfo
                     (ws/append-entry! :brian (:id w) {:kind :design}
                                       (pr-str (design-citing 1))))
            "seq 1 exists, but it is a note — the ref must name a baseline")))))

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
  ;; A workstream may survey twice. The design was judged against ONE of them,
  ;; and handing a later baseline to the review would check the change against a
  ;; yardstick its author never saw.
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:stage :in-progress :external-refs []})]
        (ws/append-entry! :brian (:id w) {:kind :baseline} (pr-str a-baseline))
        (ws/append-entry! :brian (:id w) {:kind :baseline}
                          (pr-str (assoc a-baseline :area "a second, wider survey")))
        (is (= "order totalling" (:area (ws/entry-at-seq :brian (:id w) 1))))
        (is (= "a second, wider survey" (:area (ws/entry-at-seq :brian (:id w) 2))))
        (is (= "a second, wider survey" (:area (ws/latest-entry :brian (:id w) :baseline)))
            "latest and cited genuinely differ here — which is why the reader
             following a :seq cannot be latest-entry")
        (is (nil? (ws/entry-at-seq :brian (:id w) 9))
            "a reader following a citation must find nothing without crashing")))))
