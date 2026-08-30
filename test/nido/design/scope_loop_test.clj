(ns nido.design.scope-loop-test
  "The scope loop, end to end: a baseline decides a scope, and a session on that workstream is
   briefed with it.

   Every hop of this chain is unit-tested where it lives — the Baseline schema in
   coordinator.report, the Run stamp in coordinator.record.runs, the flag in design.check. None
   of that proves the chain HOLDS. It crosses three bands and four records, each hop written
   against what the last one was supposed to produce, which is exactly the shape of thing that
   passes everywhere and works nowhere."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.design.check :as design]
   [nido.platform.core :as core]
   [nido.platform.project :as project]
   [nido.session.launcher :as launcher]))

(def ^:private the-scope '[(Band ?n) (named ?n "Lane")])

(defn- a-baseline
  "The smallest record the ledger will accept as a baseline — `latest-entry` reads entries back
   through `report/parse-event`, so an invalid one is not a bad baseline, it is no baseline."
  [scope]
  (cond-> {:format       :baseline
           :area         "the work plane's lanes"
           :bounded-by   "everything that advances a workstream from one stage to the next"
           :shape        "one namespace per verb, over the record layer"
           :modules      [{:id "lanes" :module "the lanes" :hides "the order of the steps"
                           :interface "one fn per verb, taking a workstream id"}]
           :composition  "each lane reads the record, acts, and appends"
           :load-bearing [{:id "c1" :property "a lane never writes another lane's record"
                           :falsified-by "two lanes writing the same entries/ file"}]
           :read         ["src/nido/coordinator/lane/"]}
    scope (assoc :scope scope)))

(defn- with-nido-root [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))] (f tmp))
         (finally (fs/delete-tree tmp)))))

(defn- baselined-workstream!
  "A workstream carrying one baseline entry. Returns its id."
  [project scope]
  (let [w (ws/create! project {:stage :triaging})]
    (ws/append-entry! project (:id w) {:kind :baseline} (pr-str (a-baseline scope)))
    (:id w)))

(defn- a-run!
  "Mint a Run against `ws-id`, the way spawn does."
  [project ws-id]
  (runs/create-run! {:project project
                     :trigger {:name :t :skill :s :payload "" :source {:type :manual}}
                     :payload {}
                     :workstream-id ws-id}
                    {:fired-at "2026-08-30T00:00:00Z" :fired-by "scope-loop-test"}))

;; ── the chain ────────────────────────────────────────────────────────────────

(deftest a-baselined-workstream-stamps-its-scope-on-every-run-it-mints
  (with-nido-root
    (fn [_]
      (let [ws-id (baselined-workstream! :p the-scope)
            run   (a-run! :p ws-id)]
        (is (= the-scope (:design-scope run))
            "read off the workstream's latest baseline when the Run was minted")))))

(deftest the-launcher-reads-the-scope-back-off-run-edn
  (testing "coordination hands the value down on run.edn rather than the launcher reaching into
            a ledger — the same way it hands down the run directory"
    (with-nido-root
      (fn [_]
        (let [ws-id   (baselined-workstream! :p the-scope)
              run     (a-run! :p ws-id)
              run-dir (cstate/run-dir (:id run))
              ctx     (#'launcher/run-workstream-context :run-dir run-dir)]
          (is (= ws-id (:workstream-id ctx)))
          (is (= the-scope (:design-scope ctx))
              "the briefing's own context carries it"))))))

(deftest the-briefing-asks-fukan-for-that-part-of-the-design
  (testing "the far end: what the session is actually briefed with"
    (with-nido-root
      (fn [_]
        (let [wt (fs/create-temp-dir)]
          (try
            (fs/create-dirs (fs/path wt "canvas"))
            (spit (str (fs/path wt "canvas" "bands.clj")) "(ns canvas.bands)")
            (with-redefs [project/get-project
                          (constantly {:design {:cmd ["sh" "-c" "echo \"$0 $*\"; exit 0"]}})]
              (is (str/includes? (design/describe "p" wt the-scope)
                                 "--select [(Band ?n) (named ?n \"Lane\")]")
                  "the scope the baseline settled on, asked of fukan verbatim"))
            (finally (fs/delete-tree wt))))))))

;; ── and the case that has to keep working ────────────────────────────────────

(deftest a-workstream-with-no-baseline-carries-no-scope-through-any-hop
  (testing "an unmodelled project, and the baseline round's OWN session: nothing anywhere in the
            chain may invent a scope, and every hop must stay valid without one"
    (with-nido-root
      (fn [_]
        (let [ws-id   (:id (ws/create! :p {:stage :triaging}))
              run     (a-run! :p ws-id)
              run-dir (cstate/run-dir (:id run))
              ctx     (#'launcher/run-workstream-context :run-dir run-dir)]
          (is (nil? (:design-scope run)))
          (is (nil? (:design-scope ctx)))
          (is (= ws-id (:workstream-id ctx)) "and the rest of the context is unaffected"))))))

(deftest a-baseline-that-declared-no-scope-is-not-a-scope-of-nil
  (testing "a modelled project may still baseline without a scope — the field is optional, and
            the absence must survive the round trip rather than becoming an empty selection"
    (with-nido-root
      (fn [_]
        (let [ws-id (baselined-workstream! :p nil)
              run   (a-run! :p ws-id)]
          (is (nil? (:design-scope run))))))))
