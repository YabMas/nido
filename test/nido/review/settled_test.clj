;; test/nido/review/settled_test.clj
(ns nido.review.settled-test
  "What settles a subject, and every doubt that must not."
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.workstream :as ws]
   [nido.review.settled :as settled]
   [nido.vsdd.jj :as jj]))

(def ^:private c1 {:id "c1" :property "only the aggregate sums lines"
                   :falsified-by "a second summing path"})
(def ^:private c2 {:id "c2" :property "totals are derived"
                   :falsified-by "a stored total edited on its own"})

(defn- baseline
  [seq-n & claims]
  {:format :baseline :seq seq-n :shape "the aggregate sums" :composition "only it sees lines"
   :modules [{:id "m1" :module "aggregate" :hides "summing order" :interface "a total"}]
   :load-bearing (vec claims)})

(defn- review
  [seq-n baseline-seq & {:as over}]
  (merge {:format :baseline-review :seq seq-n :baseline-seq baseline-seq
          :verdict :sufficient :reason "ok" :code-identity "tree-a"}
         over))

(defn- ledger
  [& {:keys [reviews baselines retractions]}]
  {:reviews (vec reviews) :baselines (vec baselines) :retractions (vec retractions)})

(deftest nothing-settles-without-a-code-identity-or-a-ledger
  (let [l (ledger :baselines [(baseline 1 c1)] :reviews [(review 2 1 :confirmed ["c1"])])]
    (is (= {} (settled/settled l (baseline 3 c1) nil)))
    (is (= {} (settled/settled nil (baseline 3 c1) "tree-a")))))

(deftest a-subject-confirmed-at-this-content-and-tree-is-settled-by-that-review
  (let [l (ledger :baselines [(baseline 1 c1 c2)] :reviews [(review 2 1 :confirmed ["c1"])])]
    (is (= {"c1" 2} (settled/settled l (baseline 3 c1 c2) "tree-a"))
        "c2 was never confirmed, so it is still a check")))

(deftest a-changed-subject-is-a-check
  (let [l (ledger :baselines [(baseline 1 c1)] :reviews [(review 2 1 :confirmed ["c1"])])]
    (is (= {} (settled/settled l (baseline 3 (assoc c1 :property "reworded")) "tree-a")))
    (testing "and every field of it counts, evidence included"
      (is (= {} (settled/settled l (baseline 3 (assoc c1 :evidence ["src/a.clj:1"])) "tree-a"))))))

(deftest a-confirmation-against-another-tree-settles-nothing
  (let [l (ledger :baselines [(baseline 1 c1)] :reviews [(review 2 1 :confirmed ["c1"])])]
    (is (= {} (settled/settled l (baseline 3 c1) "tree-b")))))

(deftest a-review-that-read-no-single-tree-settles-nothing
  (let [l (ledger :baselines [(baseline 1 c1)]
                  :reviews [(dissoc (review 2 1 :confirmed ["c1"]) :code-identity)])]
    (is (= {} (settled/settled l (baseline 3 c1) "tree-a")))))

(deftest a-holding-verdict-alone-settles-nothing
  (let [l (ledger :baselines [(baseline 1 c1)] :reviews [(review 2 1)])]
    (is (= {} (settled/settled l (baseline 3 c1) "tree-a")))))

(deftest a-finding-at-the-key-unsettles-for-good
  (let [found (review 4 1 :verdict :falsified
                      :findings [{:claim-id "c1" :cites ["x"] :claim "wrong"}])]
    (testing "after the confirmation"
      (is (= {} (settled/settled (ledger :baselines [(baseline 1 c1)]
                                         :reviews [(review 2 1 :confirmed ["c1"]) found])
                                 (baseline 5 c1) "tree-a"))))
    (testing "and before a later confirmation of the same content"
      (is (= {} (settled/settled (ledger :baselines [(baseline 1 c1)]
                                         :reviews [found (review 6 1 :confirmed ["c1"])])
                                 (baseline 7 c1) "tree-a"))))
    (testing "but not a finding about the same id at other content"
      (is (= {"c1" 6}
             (settled/settled (ledger :baselines [(baseline 1 (assoc c1 :property "old"))
                                                  (baseline 5 c1)]
                                      :reviews [(review 4 1 :verdict :falsified
                                                        :findings [{:claim-id "c1"}])
                                                (review 6 5 :confirmed ["c1"])])
                              (baseline 7 c1) "tree-a"))))))

(deftest a-retracted-baseline-settles-nothing
  (let [l (ledger :baselines [(baseline 1 c1)]
                  :reviews [(review 2 1 :confirmed ["c1"])]
                  :retractions [{:format :retraction :seq 3 :retracts {:seq 1}}])]
    (is (= {} (settled/settled l (baseline 4 c1) "tree-a"))))
  (testing "but a finding judged over a retracted baseline still stands at its key"
    (let [l (ledger :baselines [(baseline 1 c1) (baseline 3 c1)]
                    :reviews [(review 2 1 :confirmed ["c1"])
                              (review 4 3 :verdict :falsified :findings [{:claim-id "c1"}])]
                    :retractions [{:format :retraction :seq 5 :retracts {:seq 3}}])]
      (is (= {} (settled/settled l (baseline 6 c1) "tree-a"))))))

(deftest the-latest-confirming-review-is-named
  (let [l (ledger :baselines [(baseline 1 c1) (baseline 3 c1)]
                  :reviews [(review 2 1 :confirmed ["c1"]) (review 4 3 :confirmed ["c1"])])]
    (is (= {"c1" 4} (settled/settled l (baseline 5 c1) "tree-a")))))

(deftest whole-record-fields-settle-by-their-field-names
  (let [l (ledger :baselines [(baseline 1 c1)]
                  :reviews [(review 2 1 :confirmed ["shape" "composition" "m1"])])]
    (is (= {"shape" 2 "composition" 2 "m1" 2} (settled/settled l (baseline 3 c1) "tree-a")))
    (is (= {"composition" 2 "m1" 2}
           (settled/settled l (assoc (baseline 3 c1) :shape "reshaped") "tree-a")))))

(deftest two-subjects-sharing-an-id-settle-only-together
  (let [claim-m1 {:id "m1" :property "p" :falsified-by "f"}
        l (ledger :baselines [(baseline 1 claim-m1)] :reviews [(review 2 1 :confirmed ["m1"])])]
    (is (= {"m1" 2} (settled/settled l (baseline 3 claim-m1) "tree-a")))
    (is (= {} (settled/settled l (assoc-in (baseline 3 claim-m1) [:modules 0 :hides] "moved")
                               "tree-a"))
        "the module moved under the shared id, so the id names a subject nobody checked")))

;; ── The code identity ───────────────────────────────────────────────────────

(def ^:private listing
  (str "a.clj: Ok(Resolved(Some(File { id: FileId(\"78981922613b\"), executable: false, copy_id: CopyId(\"\") })))\n"
       "link: Ok(Resolved(Some(Symlink(SymlinkId(\"c71e2c967ad3\")))))"))

(deftest the-code-identity-is-a-hash-of-the-listing
  (with-redefs [jj/jj! (fn [_ & _] {:exit 0 :out listing :err ""})]
    (is (string? (settled/code-identity "/w")))
    (is (= (settled/code-identity "/w") (settled/code-identity "/w")))))

(deftest every-doubt-about-the-listing-is-no-identity
  (doseq [[why answer] [["jj refused"         {:exit 1 :out "" :err "no repo"}]
                        ["an empty tree"      {:exit 0 :out "" :err ""}]
                        ["a line with no id"  {:exit 0 :out (str listing "\nb.clj: Ok(Resolved(None))") :err ""}]]]
    (with-redefs [jj/jj! (fn [_ & _] answer)]
      (is (nil? (settled/code-identity "/w")) why)))
  (with-redefs [jj/jj! (fn [_ & _] (throw (ex-info "boom" {})))]
    (is (nil? (settled/code-identity "/w")) "a throw")))

;; ── Reading the ledger ──────────────────────────────────────────────────────

(deftest an-entry-that-will-not-parse-settles-nothing
  (let [index {:entries [{:kind :baseline :seq 1} {:kind :baseline-review :seq 2}
                         {:kind :retraction :seq 3}]}
        parsed {:baseline [(baseline 1 c1)] :baseline-review [(review 2 1)]
                :retraction [{:format :retraction :seq 3 :retracts {:seq 1}}]}]
    (with-redefs [ws/read-ws (fn [_ _] index)
                  ws/entries-of (fn [_ _ k] (parsed k))]
      (is (= 1 (count (:retractions (settled/ledger :nido "ws-1"))))))
    (doseq [k [:baseline :baseline-review :retraction]]
      (with-redefs [ws/read-ws (fn [_ _] index)
                    ws/entries-of (fn [_ _ kind] (if (= k kind) [] (parsed kind)))]
        (is (nil? (settled/ledger :nido "ws-1"))
            (str "a " k " the index holds and nothing could read"))))))
