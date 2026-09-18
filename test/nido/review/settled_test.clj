;; test/nido/review/settled_test.clj
(ns nido.review.settled-test
  "What settles a subject, and every doubt that must not."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.fork :as fork]
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
  [& {:keys [ws-id reviews decisions baselines designs retractions] :or {ws-id "ws-1"}}]
  {:ws-id ws-id :reviews (vec reviews) :decisions (vec decisions)
   :baselines (vec baselines) :designs (vec designs) :retractions (vec retractions)})

(def ^:private tree-a {:code-identity "tree-a"})
(defn- by [seq-n] {:ws-id "ws-1" :seq seq-n})

(deftest nothing-settles-without-an-identity-or-a-ledger
  (let [l (ledger :baselines [(baseline 1 c1)] :reviews [(review 2 1 :confirmed ["c1"])])]
    (is (= {} (settled/settled [l] (baseline 3 c1) {})))
    (is (= {} (settled/settled nil (baseline 3 c1) tree-a)))))

(deftest a-subject-confirmed-at-this-content-and-tree-is-settled-by-that-review
  (let [l (ledger :baselines [(baseline 1 c1 c2)] :reviews [(review 2 1 :confirmed ["c1"])])]
    (is (= {"c1" (by 2)} (settled/settled [l] (baseline 3 c1 c2) tree-a))
        "c2 was never confirmed, so it is still a check")))

(deftest a-changed-subject-is-a-check
  (let [l (ledger :baselines [(baseline 1 c1)] :reviews [(review 2 1 :confirmed ["c1"])])]
    (is (= {} (settled/settled [l] (baseline 3 (assoc c1 :property "reworded")) tree-a)))
    (testing "and every field of it counts, evidence included"
      (is (= {} (settled/settled [l] (baseline 3 (assoc c1 :evidence ["src/a.clj:1"])) tree-a))))))

(deftest a-confirmation-against-another-tree-settles-nothing
  (let [l (ledger :baselines [(baseline 1 c1)] :reviews [(review 2 1 :confirmed ["c1"])])]
    (is (= {} (settled/settled [l] (baseline 3 c1) {:code-identity "tree-b"})))))

(deftest a-review-that-read-no-single-tree-settles-nothing
  (let [l (ledger :baselines [(baseline 1 c1)]
                  :reviews [(dissoc (review 2 1 :confirmed ["c1"]) :code-identity)])]
    (is (= {} (settled/settled [l] (baseline 3 c1) tree-a)))))

(deftest a-holding-verdict-alone-settles-nothing
  (let [l (ledger :baselines [(baseline 1 c1)] :reviews [(review 2 1)])]
    (is (= {} (settled/settled [l] (baseline 3 c1) tree-a)))))

(deftest a-finding-at-the-key-stands-until-a-later-confirmation-answers-it
  ;; A finding was held for good, so a record amended elsewhere in answer to it left the claim a
  ;; check however often a later round confirmed it at the same content and key.
  (let [found (review 4 1 :verdict :falsified
                      :findings [{:claim-id "c1" :cites ["x"] :claim "wrong"}])]
    (testing "a finding after the confirmation unsettles"
      (is (= {} (settled/settled [(ledger :baselines [(baseline 1 c1)]
                                          :reviews [(review 2 1 :confirmed ["c1"]) found])]
                                 (baseline 5 c1) tree-a))))
    (testing "a later confirmation of the same content at the same key answers it"
      (is (= {"c1" (by 6)}
             (settled/settled [(ledger :baselines [(baseline 1 c1)]
                                       :reviews [found (review 6 1 :confirmed ["c1"])])]
                              (baseline 7 c1) tree-a))))
    (testing "and a judgement's :at orders them, whichever ledger each is on"
      (is (= {} (settled/settled [(ledger :baselines [(baseline 1 c1)]
                                          :reviews [(review 6 1 :confirmed ["c1"] :at "2026-09-01T10:00:00Z")])
                                  (ledger :ws-id "ws-2" :baselines [(baseline 1 c1)]
                                          :reviews [(assoc found :at "2026-09-02T10:00:00Z")])]
                                 (baseline 7 c1) tree-a))))
    (testing "but not a finding about the same id at other content"
      (is (= {"c1" (by 6)}
             (settled/settled [(ledger :baselines [(baseline 1 (assoc c1 :property "old"))
                                                   (baseline 5 c1)]
                                       :reviews [(review 4 1 :verdict :falsified
                                                         :findings [{:claim-id "c1"}])
                                                 (review 6 5 :confirmed ["c1"])])]
                              (baseline 7 c1) tree-a))))))

(deftest a-retracted-baseline-settles-nothing
  (let [l (ledger :baselines [(baseline 1 c1)]
                  :reviews [(review 2 1 :confirmed ["c1"])]
                  :retractions [{:format :retraction :seq 3 :retracts {:seq 1}}])]
    (is (= {} (settled/settled [l] (baseline 4 c1) tree-a))))
  (testing "but a finding judged over a retracted baseline still stands at its key"
    (let [l (ledger :baselines [(baseline 1 c1) (baseline 3 c1)]
                    :reviews [(review 2 1 :confirmed ["c1"])
                              (review 4 3 :verdict :falsified :findings [{:claim-id "c1"}])]
                    :retractions [{:format :retraction :seq 5 :retracts {:seq 3}}])]
      (is (= {} (settled/settled [l] (baseline 6 c1) tree-a))))))

(deftest the-latest-confirming-review-is-named
  (let [l (ledger :baselines [(baseline 1 c1) (baseline 3 c1)]
                  :reviews [(review 2 1 :confirmed ["c1"]) (review 4 3 :confirmed ["c1"])])]
    (is (= {"c1" (by 4)} (settled/settled [l] (baseline 5 c1) tree-a)))))

(deftest whole-record-fields-settle-by-their-field-names
  (let [l (ledger :baselines [(baseline 1 c1)]
                  :reviews [(review 2 1 :confirmed ["shape" "composition" "m1"])])]
    (is (= {"shape" (by 2) "composition" (by 2) "m1" (by 2)}
           (settled/settled [l] (baseline 3 c1) tree-a)))
    (is (= {"composition" (by 2) "m1" (by 2)}
           (settled/settled [l] (assoc (baseline 3 c1) :shape "reshaped") tree-a)))))

(deftest two-subjects-sharing-an-id-settle-only-together
  (let [claim-m1 {:id "m1" :property "p" :falsified-by "f"}
        l (ledger :baselines [(baseline 1 claim-m1)] :reviews [(review 2 1 :confirmed ["m1"])])]
    (is (= {"m1" (by 2)} (settled/settled [l] (baseline 3 claim-m1) tree-a)))
    (is (= {} (settled/settled [l] (assoc-in (baseline 3 claim-m1) [:modules 0 :hides] "moved")
                               tree-a))
        "the module moved under the shared id, so the id names a subject nobody checked")))

;; ── What a claim of a model rests on ────────────────────────────────────────

(def ^:private agg {:id "canvas.a/agg" :sort :module :hides "summing order" :interface "a total"})
(def ^:private one-path {:id "one-path" :about ["canvas.a/agg"] :statement "one summing path"
                         :falsified-by "a second path" :evidence {:by :round}})

(defn- model-baseline [seq-n & claims]
  {:format :baseline :strata [] :seq seq-n :model {:elements [agg] :claims (vec claims)}})

(deftest a-model-claim-is-keyed-on-its-subjects-not-on-the-tree
  (let [l [(ledger :baselines [(model-baseline 1 one-path)]
                   :reviews [(review 2 1 :confirmed ["one-path"]
                                     :subject-identities {"canvas.a/agg" "agg-1"})])]]
    (testing "a tree that moved elsewhere leaves it settled"
      (is (= {"one-path" (by 2)}
             (settled/settled l (model-baseline 3 one-path)
                              {:code-identity "tree-b" :subject-identities {"canvas.a/agg" "agg-1"}}))))
    (testing "its subject's declaration or code moving makes it a check again"
      (is (= {} (settled/settled l (model-baseline 3 one-path)
                                 {:code-identity "tree-b" :subject-identities {"canvas.a/agg" "agg-2"}}))))
    (testing "a subject nothing identifies now is a doubt, and a doubt checks"
      (is (= {} (settled/settled l (model-baseline 3 one-path)
                                 {:code-identity "tree-b" :subject-identities {}}))))))

(deftest a-claim-about-a-role-rests-on-its-players-too
  (let [role   {:id "canvas.a/summers" :sort :role :plays ["canvas.a/agg"]}
        claim  {:id "summers-once" :about ["canvas.a/summers"] :statement "each sums once"
                :falsified-by "a double sum" :evidence {:by :round}}
        record (fn [n] {:format :baseline :strata [] :seq n :model {:elements [agg role] :claims [claim]}})
        ids    {"canvas.a/agg" "agg-1" "canvas.a/summers" "role-1"}
        l      [(ledger :baselines [(record 1)]
                        :reviews [(review 2 1 :confirmed ["summers-once"] :subject-identities ids)])]]
    (is (= {"summers-once" (by 2)} (settled/settled l (record 3) {:subject-identities ids})))
    (is (= {} (settled/settled l (record 3) {:subject-identities (assoc ids "canvas.a/agg" "agg-2")}))
        "a player's code moving unsettles a claim about the role")))

(deftest a-claim-about-a-role-kept-from-the-baseline-rests-on-its-players-too
  (let [role      {:id "canvas.a/summers" :sort :role :plays ["canvas.a/agg"]}
        claim     {:id "summers-once" :about ["canvas.a/summers"] :statement "each sums once"
                   :falsified-by "a double sum" :evidence {:by :round}}
        design    (fn [n] {:format :design :strata [] :seq n :model {:claims [claim]}})
        effective (fn [n] (assoc (design n) :model {:elements [agg role] :claims [claim]}))
        ids       {"canvas.a/agg" "agg-1" "canvas.a/summers" "role-1"}
        l         [(ledger :designs [(design 1)]
                           :decisions [{:format :design-decision :seq 2 :design-seq 1 :recommend :proceed
                                        :confirmed ["summers-once"] :subject-identities ids}])]]
    (is (= {"summers-once" (by 2)}
           (settled/settled l (design 3) {:subject-identities ids} (effective 3))))
    (is (= {} (settled/settled l (design 3) {:subject-identities (assoc ids "canvas.a/agg" "agg-2")}
                               (effective 3)))
        "a player's code moving unsettles it, though the design never restated the role")))

(deftest a-model-records-whole-record-fields-rest-on-the-whole-tree
  (let [design (fn [n] {:format :design :strata [] :seq n :shape "the aggregate sums" :composition "only it sees lines"
                        :model {:elements [agg] :claims [one-path]}})
        ids    {"canvas.a/agg" "agg-1"}
        l      [(ledger :designs [(design 1)]
                        :decisions [{:format :design-decision :seq 2 :design-seq 1 :recommend :proceed
                                     :confirmed ["shape" "composition" "one-path"]
                                     :code-identity "tree-a" :subject-identities ids}])]]
    (is (= {} (settled/settled [(ledger)] (design 3) {:code-identity "tree-a" :subject-identities ids}))
        "with nothing confirmed there is nothing settled — and a round is not stopped by asking")
    (is (= {"shape" (by 2) "composition" (by 2) "one-path" (by 2)}
           (settled/settled l (design 3) {:code-identity "tree-a" :subject-identities ids})))
    (is (= {"one-path" (by 2)}
           (settled/settled l (design 3) {:code-identity "tree-b" :subject-identities ids}))
        "a field of the whole record is keyed on the whole tree, a claim on its subjects")))

(deftest a-design-decision-confirms-as-a-review-does
  (let [design   (fn [n] {:format :design :strata [] :seq n
                          :model {:elements [{:id "canvas.a/agg" :sort :module}] :claims [one-path]}})
        decision {:format :design-decision :seq 2 :design-seq 1 :recommend :proceed
                  :confirmed ["one-path"] :subject-identities {"canvas.a/agg" "agg-1"}}
        l        [(ledger :designs [(design 1)] :decisions [decision])]
        reading  {:subject-identities {"canvas.a/agg" "agg-1"}}]
    (is (= {"one-path" (by 2)} (settled/settled l (design 3) reading)))
    (testing "and a claim a design had confirmed carries to a baseline stating the same claim"
      (is (= {"one-path" (by 2)} (settled/settled l (model-baseline 4 one-path) reading))))))

(deftest a-confirmation-on-another-ledger-names-that-ledger
  (let [parent (ledger :ws-id "ws-parent"
                       :baselines [(model-baseline 1 one-path)]
                       :reviews [(review 2 1 :confirmed ["one-path"]
                                         :subject-identities {"canvas.a/agg" "agg-1"})])]
    (is (= {"one-path" {:ws-id "ws-parent" :seq 2}}
           (settled/settled [(ledger :ws-id "ws-child") parent] (model-baseline 3 one-path)
                            {:subject-identities {"canvas.a/agg" "agg-1"}})))))

(deftest the-ledgers-a-unit-reaches-are-its-own-its-parents-and-a-merged-childs
  (with-redefs [ws/read-ws     (fn [_ id] {:id id :entries []})
                fork/lineage   (fn [w] (when (= "ws-child" (:id w)) {:parent "ws-parent"}))
                settled/ledger (fn [_ id] {:ws-id id})]
    (is (= ["ws-child" "ws-parent"] (mapv :ws-id (settled/ledgers :nido "ws-child" {}))))
    (is (= ["ws-parent" "ws-child"]
           (mapv :ws-id (settled/ledgers :nido "ws-parent" {:merges {:ws-id "ws-child"}}))))
    (with-redefs [settled/ledger (fn [_ id] (when-not (= "ws-parent" id) {:ws-id id}))]
      (is (nil? (settled/ledgers :nido "ws-child" {}))
          "a ledger nobody could read settles nothing"))))

(deftest an-elements-identity-moves-with-its-declaration-or-its-code
  (let [dir (fs/create-temp-dir)
        f   (str (fs/path dir "src" "a.clj"))]
    (try
      (fs/create-dirs (fs/parent f))
      (spit f "(ns a)")
      (let [row    {:id "canvas.a/agg" :sort :fukan.common.vocab.code.module/Module
                    :declaration "d1" :file "src/a.clj"}
            ids    #(settled/subject-identities {:status :listed :elements [%]} (str dir))
            before (ids row)]
        (is (= before (ids row)))
        (is (not= before (ids (assoc row :declaration "d2"))) "its declaration moved")
        (spit f "(ns a) (def x 1)")
        (is (not= before (ids row)) "its code moved")
        (is (nil? (settled/subject-identities {:status :unmodelled} (str dir)))
            "a project that declares no design identifies nothing"))
      (finally (fs/delete-tree dir)))))

(deftest code-listed-without-its-file-is-a-doubt-and-a-role-is-its-declaration
  (let [ids (settled/subject-identities
             {:status :listed
              :elements [{:id "canvas.a/agg" :sort :fukan.common.vocab.code.module/Module :declaration "d1"}
                         {:id "canvas.a/sum" :sort :fukan.common.vocab.code.operation/Operation :declaration "d2"}
                         {:id "canvas.a/summers" :sort :canvas.vocab.claim/Role :declaration "d3"}]}
             "/nowhere")]
    (is (not (contains? ids "canvas.a/agg"))
        "a module paired with nothing may be one whose code fukan could not read")
    (is (not (contains? ids "canvas.a/sum")))
    (is (string? (get ids "canvas.a/summers")) "a role has no code of its own to be missing")))

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
