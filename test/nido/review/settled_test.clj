;; test/nido/review/settled_test.clj
(ns nido.review.settled-test
  "What settles a subject, and every doubt that must not."
  (:require
   [babashka.fs :as fs]
   [babashka.process]
   [clojure.string :as str]
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

(defn- read-at
  "Where a judge says it read each of `ids` — the evidence a confirmation settles by."
  [ids]
  (zipmap ids (repeat ["src/a.clj:1"])))

(defn- review
  "A review; each id it confirms is confirmed with evidence unless `over` says where it was read."
  [seq-n baseline-seq & {:as over}]
  (merge {:format :baseline-review :seq seq-n :baseline-seq baseline-seq
          :verdict :sufficient :reason "ok" :code-identity "tree-a"}
         (when (:confirmed over) {:checked-at (read-at (:confirmed over))})
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

(deftest a-finding-quoting-a-sibling-unsettles-the-sibling-too
  (let [confirmed (review 2 1 :confirmed ["c1" "c2"])
        found     (review 4 1 :verdict :falsified
                          :findings [{:claim-id "c1" :claim "wrong"
                                      :cites ["[c1] only the aggregate sums lines"
                                              "[c2] totals are derived"]}])]
    (is (= {} (settled/settled [(ledger :baselines [(baseline 1 c1 c2)] :reviews [confirmed found])]
                               (baseline 5 c1 c2) tree-a))
        "a counterexample quoting c2's text puts c2 in doubt; filing it under c1 must not leave c2
         shielded from the next judge")
    (testing "an id that only appears unbracketed in the prose names nothing"
      (is (= {"c2" (by 2)}
             (settled/settled [(ledger :baselines [(baseline 1 c1 c2)]
                                       :reviews [confirmed
                                                 (assoc-in found [:findings 0 :cites] ["see c2's wording"])])]
                              (baseline 5 c1 c2) tree-a))))))

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

(deftest a-confirmation-that-cites-nothing-it-read-settles-nothing
  ;; A bare id is the judge's word. Settled on it, one skim of a subject shielded it from every later
  ;; round for as long as the tree held — and several such confirmations were false.
  (let [l (ledger :baselines [(baseline 1 c1)]
                  :reviews [(review 2 1 :confirmed ["c1"] :checked-at {})])]
    (is (= {} (settled/settled [l] (baseline 3 c1) tree-a))
        "a confirmation that names no file:line is re-checked, not banked")))

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
                                        :confirmed ["summers-once"] :checked-at (read-at ["summers-once"])
                                        :subject-identities ids}])]]
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
                                     :checked-at (read-at ["shape" "composition" "one-path"])
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
                          :model {:elements [agg] :claims [one-path]}})
        decision {:format :design-decision :seq 2 :design-seq 1 :recommend :proceed
                  :confirmed ["one-path"] :checked-at (read-at ["one-path"])
                  :subject-identities {"canvas.a/agg" "agg-1"}}
        l        [(ledger :designs [(design 1)] :decisions [decision])]
        reading  {:subject-identities {"canvas.a/agg" "agg-1"}}]
    (is (= {"one-path" (by 2)} (settled/settled l (design 3) reading)))
    (testing "and a claim a design had confirmed carries to a baseline stating the same claim"
      (is (= {"one-path" (by 2)} (settled/settled l (model-baseline 4 one-path) reading))))
    (testing "only while the baseline says the same of what it is about"
      (is (= {} (settled/settled l (assoc-in (model-baseline 4 one-path) [:model :elements 0 :hides] "more")
                                 reading))))))

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

;; ── What the record says around a subject ──────────────────────────────────

(def ^:private sibling {:id "sibling" :about ["canvas.a/agg"] :statement "the aggregate is the only writer"
                        :falsified-by "a second writer" :evidence {:by :round}})

(defn- confirmed-model [record ids]
  [(ledger :baselines [record]
           :reviews [(review 2 (:seq record) :confirmed ids :subject-identities {"canvas.a/agg" "agg-1"})])])

(def ^:private at-agg-1 {:subject-identities {"canvas.a/agg" "agg-1"}})

(deftest amending-a-sibling-claim-unsettles-a-claim-about-the-same-element
  ;; A claim is judged beside the other claims about its elements. A sibling amended to say something
  ;; that makes it false left it settled, because neither its words nor the element's code moved.
  (let [l (confirmed-model (model-baseline 1 one-path sibling) ["one-path"])]
    (is (= {"one-path" (by 2)} (settled/settled l (model-baseline 3 one-path sibling) at-agg-1)))
    (is (= {} (settled/settled l (model-baseline 3 one-path (assoc sibling :statement "two writers now"))
                               at-agg-1))
        "the sibling moved, so what one-path was confirmed beside is gone")
    (is (= {} (settled/settled l (model-baseline 3 one-path sibling
                                                 {:id "added" :about ["canvas.a/agg"] :statement "new"
                                                  :falsified-by "x" :evidence {:by :round}})
                               at-agg-1))
        "a sibling added about the same element unsettles it too")
    (is (= {"one-path" (by 2)}
           (settled/settled l (model-baseline 3 one-path sibling
                                              {:id "elsewhere" :about ["canvas.b/other"] :statement "new"
                                               :falsified-by "x" :evidence {:by :round}})
                            at-agg-1))
        "a claim about another element is not its context")))

(deftest amending-the-records-own-element-entry-unsettles-the-claims-about-it
  ;; An amender rewriting an element's interface in the record moves no code and no declaration, so
  ;; neither identity saw it — and every claim about that element stayed settled.
  (let [l (confirmed-model (model-baseline 1 one-path) ["one-path"])
        amended (assoc-in (model-baseline 3 one-path) [:model :elements 0 :interface] "a total and a tax")]
    (is (= {} (settled/settled l amended at-agg-1)))))

(deftest a-changed-yardstick-unsettles-what-was-judged-against-the-old-one
  ;; A design resting on a re-surveyed baseline, or written for a goal that moved, is judged against
  ;; a different yardstick however identical its claims are.
  (let [design   (fn [n base intent] {:format :design :strata [] :seq n :baseline {:seq base}
                                      :intent {:seq intent} :model {:elements [agg] :claims [one-path]}})
        decision {:format :design-decision :seq 5 :design-seq 4 :recommend :proceed
                  :confirmed ["one-path"] :checked-at (read-at ["one-path"])
                  :subject-identities {"canvas.a/agg" "agg-1"}}
        l        [(ledger :designs [(design 4 2 1)] :decisions [decision])]]
    (is (= {"one-path" (by 5)} (settled/settled l (design 6 2 1) at-agg-1)))
    (is (= {} (settled/settled l (design 6 3 1) at-agg-1)) "the baseline was re-surveyed")
    (is (= {} (settled/settled l (design 6 2 7) at-agg-1)) "the intent was replaced")))

(deftest a-judgement-keeps-only-the-identities-its-record-rests-on
  (let [role   {:id "canvas.a/summers" :sort :role :plays ["canvas.a/agg"]}
        claim  {:id "summers-once" :about ["canvas.a/summers"] :statement "s" :falsified-by "f"
                :evidence {:by :round}}
        record {:format :baseline :strata [] :model {:elements [role] :claims [claim]}
                :health [{:id "h1" :axis :design :observation "o"}]}]
    (is (= #{"canvas.a/summers" "canvas.a/agg"}
           (settled/rested-on record (assoc-in record [:model :elements] [agg role]))))
    (is (= #{} (settled/rested-on (baseline 1 c1) (baseline 1 c1))) "a record with no model rests on none")))

(deftest a-subject-stating-only-its-id-and-sort-has-nothing-to-check
  (is (settled/nothing-to-check? [{:id "k" :sort :kind}]))
  (is (settled/nothing-to-check? [{:id "k" :sort :operation :readings []}]))
  (is (not (settled/nothing-to-check? [agg])) "a module states what it hides")
  (is (not (settled/nothing-to-check? [one-path])) "a claim is always checkable")
  (is (not (settled/nothing-to-check? ["the aggregate sums"])) "a whole-record field is too"))

(deftest a-strata-identity-moves-with-the-code-of-its-modules
  ;; A stratum's reading is about how the code of its modules is written, so a `sound` read at one
  ;; tree is not settled at a tree where those modules moved.
  (let [dir (fs/create-temp-dir)
        f   (str (fs/path dir "src" "a.clj"))]
    (try
      (fs/create-dirs (fs/parent f))
      (spit f "(ns a)")
      (let [module  {:id "canvas.a/agg" :sort :fukan.common.vocab.code.module/Module
                     :declaration "d1" :file "src/a.clj"}
            stratum {:id "canvas.strata/core" :sort :canvas.vocab.strata/Stratum :declaration "s1"
                     :refs {:provided-by ["canvas.a/agg"]}}
            ids     #(get (settled/subject-identities {:status :listed :elements %} (str dir))
                          "canvas.strata/core")
            before  (ids [module stratum])]
        (is (string? before))
        (spit f "(ns a) (def x 1)")
        (is (not= before (ids [module stratum])) "a providing module's code moved")
        (is (nil? (ids [(dissoc module :file) stratum]))
            "a provider paired with no file is a doubt, and a doubt identifies nothing"))
      (finally (fs/delete-tree dir)))))

(deftest a-plain-git-checkout-has-a-code-identity
  ;; A project that is a git repository and no jj one had no identity at all, so nothing it confirmed
  ;; could ever settle — and nothing said so.
  (let [dir (str (fs/create-temp-dir))
        git (fn [& args] (apply babashka.process/shell {:dir dir :out :string :err :string} "git" args))]
    (try
      (git "init" "-q")
      (spit (str (fs/path dir "a.clj")) "(ns a)")
      (spit (str (fs/path dir ".gitignore")) "target/\n")
      (let [before (settled/code-identity dir)]
        (is (string? before))
        (is (= before (settled/code-identity dir)) "the same tree read twice is one identity")
        (fs/create-dirs (fs/path dir "target"))
        (spit (str (fs/path dir "target" "out.txt")) "build output")
        (is (= before (settled/code-identity dir)) "an ignored file is not the code")
        (spit (str (fs/path dir "a.clj")) "(ns a) (def x 1)")
        (is (not= before (settled/code-identity dir)) "an uncommitted edit moves it")
        (is (empty? (str/trim (:out (git "status" "--porcelain" "--untracked-files=no"))))
            "reading the identity stages nothing into the repository's own index"))
      (finally (fs/delete-tree dir)))))

(deftest a-directory-in-no-repository-has-no-identity
  (let [dir (str (fs/create-temp-dir))]
    (try (is (nil? (settled/code-identity dir)))
         (finally (fs/delete-tree dir)))))

;; ── Two readings, and what a reversal overturns ─────────────────────────────

(deftest a-subject-settles-on-its-second-consecutive-confirmation
  ;; One judge held and broke the same claim a round apart on text nobody touched, and a record
  ;; loop ended on the one clean reading between. A single confirmation is a sample.
  (let [once  (ledger :baselines [(baseline 1 c1)] :reviews [(review 2 1 :confirmed ["c1"])])
        twice (ledger :baselines [(baseline 1 c1)]
                      :reviews [(review 2 1 :confirmed ["c1"]) (review 4 1 :confirmed ["c1"])])]
    (is (= #{"c1"} (settled/single-readings [once] (baseline 5 c1) tree-a))
        "one reading is still put to the judge")
    (is (= #{} (settled/single-readings [twice] (baseline 5 c1) tree-a))
        "the second consecutive reading at the same key settles it")
    (testing "a finding between two confirmations ends the pair"
      (let [l (ledger :baselines [(baseline 1 c1)]
                      :reviews [(review 2 1 :confirmed ["c1"])
                                (review 3 1 :verdict :falsified :findings [{:claim-id "c1"}])
                                (review 4 1 :confirmed ["c1"])])]
        (is (= #{"c1"} (settled/single-readings [l] (baseline 5 c1) tree-a)))))
    (testing "an unchecked reading between two confirmations ends the pair"
      ;; Watched: a subject the judge said three times it could not check settled through the rounds
      ;; that happened to confirm it.
      (let [l (ledger :baselines [(baseline 1 c1)]
                      :reviews [(review 2 1 :confirmed ["c1"])
                                (review 3 1 :unchecked [{:id "c1" :reason "outside the repo"}])
                                (review 4 1 :confirmed ["c1"])])]
        (is (= #{"c1"} (settled/single-readings [l] (baseline 5 c1) tree-a))))
      (let [l (ledger :baselines [(baseline 1 c1)]
                      :reviews [(review 2 1 :confirmed ["c1"])
                                (review 3 1 :unchecked [{:id "c1" :reason "outside the repo"}])])]
        (is (= {} (settled/settled [l] (baseline 5 c1) tree-a))
            "nor does a confirmation stand once the reading after it could not check it")))
    (testing "a confirmation at another tree is no first reading here"
      (let [l (ledger :baselines [(baseline 1 c1)]
                      :reviews [(review 2 1 :confirmed ["c1"] :code-identity "tree-b")
                                (review 4 1 :confirmed ["c1"])])]
        (is (= #{"c1"} (settled/single-readings [l] (baseline 5 c1) tree-a)))))))

(deftest only-a-confirmation-that-says-where-it-read-counts
  (is (= #{"c1"} (settled/checked-confirmations
                  {:confirmed ["c1" "c2"] :checked-at {"c1" ["src/a.clj:1"]}}))))

(deftest an-earlier-runs-finding-is-what-a-confirmation-now-overturns
  (let [found (fn [n run] (review n 1 :verdict :falsified :run-id run
                                  :findings [{:claim-id "c1" :cites ["x"] :claim "a second path"}]))]
    (testing "the newest finding by another run, with whether the subject moved since"
      (let [l (ledger :baselines [(baseline 1 c1)] :reviews [(found 2 "old") (found 4 "older")])]
        (is (= {"c1" {:ws-id "ws-1" :seq 4 :restated? false
                      :finding {:claim-id "c1" :cites ["x"] :claim "a second path"}}}
               (settled/prior-findings [l] :baseline (baseline 5 c1) "now")))
        (is (true? (get-in (settled/prior-findings [l] :baseline (baseline 5 (assoc c1 :property "reworded")) "now")
                           ["c1" :restated?]))
            "restated, so the judge knows a confirmation is not a pure reversal")))
    (testing "this run's own findings reached its amender already, and are left out"
      (let [l (ledger :baselines [(baseline 1 c1)] :reviews [(found 2 "now")])]
        (is (= {} (settled/prior-findings [l] :baseline (baseline 5 c1) "now")))))))

(defn- design
  [seq-n breaks & claims]
  {:format :design :seq seq-n :shape "the design's own shape"
   :baseline {:seq 1 :relation :revisit :breaks breaks}
   :model {:claims (vec claims) :elements [{:id "m1" :sort :module :hides "summing order"}]}})

(defn- decision
  [seq-n design-seq & {:as over}]
  (merge {:format :design-decision :seq seq-n :design-seq design-seq :recommend :proceed
          :reason "ok" :code-identity "tree-a"}
         (when (:confirmed over) {:checked-at (read-at (:confirmed over))})
         over))

(deftest a-baseline-finding-is-no-prior-finding-against-its-designs-subject-of-the-same-id
  ;; A design's `shape` inherited a refutation of its baseline's `shape`, and confirming the
  ;; design's own recorded a false :overturns of a finding about a different record.
  (let [l (ledger :baselines [(baseline 1 c1)]
                  :reviews [(review 2 1 :verdict :falsified :run-id "old"
                                    :findings [{:claim-id "shape" :cites ["x"] :claim "wrong order"}])]
                  :designs [(design 3 [])])]
    (is (= {} (settled/prior-findings [l] :design (design 4 []) "now"))
        "a baseline review's finding is about the baseline, whatever id it shares")
    (is (contains? (settled/prior-findings [l] :baseline (baseline 5 c1) "now") "shape")
        "and it is still the last word against the baseline's own")))

(deftest a-finding-a-later-confirmation-answered-is-no-longer-prior
  ;; An answered finding shown again is a reversal the judge is asked to justify twice, and a
  ;; confirmation of it records an :overturns of something already overturned.
  (let [found (review 2 1 :verdict :falsified :run-id "old"
                      :findings [{:claim-id "c1" :cites ["x"] :claim "a second path"}])]
    (is (= {} (settled/prior-findings
               [(ledger :baselines [(baseline 1 c1)]
                        :reviews [found (review 3 1 :confirmed ["c1"] :run-id "later")])]
               :baseline (baseline 5 c1) "now")))
    (testing "a confirmation from a retracted record answers nothing"
      (is (contains? (settled/prior-findings
                      [(ledger :baselines [(baseline 1 c1) (baseline 4 c1)]
                               :reviews [found (review 6 4 :confirmed ["c1"] :run-id "later")]
                               :retractions [{:seq 7 :retracts {:seq 4}}])]
                      :baseline (baseline 8 c1) "now")
                     "c1")))
    (testing "a confirmation that says nowhere it read answers nothing"
      (is (contains? (settled/prior-findings
                      [(ledger :baselines [(baseline 1 c1)]
                               :reviews [found (review 3 1 :confirmed ["c1"] :checked-at {})])]
                      :baseline (baseline 5 c1) "now")
                     "c1")))
    (testing "a finding after the confirmation is prior again"
      (is (= 4 (get-in (settled/prior-findings
                        [(ledger :baselines [(baseline 1 c1)]
                                 :reviews [found (review 3 1 :confirmed ["c1"])
                                           (review 4 1 :verdict :falsified :run-id "old"
                                                   :findings [{:claim-id "c1" :cites ["y"] :claim "again"}])])]
                        :baseline (baseline 5 c1) "now")
                       ["c1" :seq]))))))

(deftest a-relation-honest-finding-is-restated-when-the-ids-place-under-breaks-moves
  ;; A :breaks-membership finding fixed by an amendment was shown as `reads the same now`, because
  ;; only the element was compared, and the judge re-reported it as instructed.
  (let [m1        {:id "m1" :sort :module :hides "summing order"}
        overclaim {:claim-id "m1" :check :relation-honest :cites ["Declared :breaks includes m1"]
                   :claim "m1 stands, so listing it under :breaks claims too much"}
        found     (decision 4 3 :recommend :amend :run-id "old" :findings [overclaim])
        l         (fn [& ds] (ledger :designs [(design 3 ["m1"])] :decisions (into [found] ds)))]
    (is (true? (get-in (settled/prior-findings [(l)] :design (design 5 []) "now") ["m1" :restated?]))
        "the element reads the same, but :breaks no longer lists the id the finding was about")
    (is (false? (get-in (settled/prior-findings [(l)] :design (design 5 ["m1"]) "now") ["m1" :restated?])))
    (is (true? (get-in (settled/prior-findings [(l)] :design (assoc-in (design 5 ["m1"]) [:baseline :relation] :extends) "now")
                       ["m1" :restated?]))
        "the relation the finding read beside the id is part of what it cited")
    (testing "a later ruling the record's :breaks agrees with answers it"
      (is (= {} (settled/prior-findings
                 [(ledger :designs [(design 3 ["m1"]) (design 5 [])]
                          :decisions [found (decision 6 5 :relation-rulings [{:id "m1" :ruling :stands}])])]
                 :design (design 7 []) "now"))))
    (testing "a ruling :breaks contradicts, or a confirmation of the element, does not"
      (is (contains? (settled/prior-findings
                      [(l (decision 6 3 :relation-rulings [{:id "m1" :ruling :stands}]
                                    :confirmed ["m1"]))]
                      :design (design 7 ["m1"]) "now")
                     "m1")))))
