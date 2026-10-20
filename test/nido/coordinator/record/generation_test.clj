(ns nido.coordinator.record.generation-test
  "A workstream's ledger read as generations: which entry belongs to which, what the current span
   holds, which design governs it and which ids it fixes — and the appends that hold to them."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.record.activity :as activity]
   [nido.coordinator.record.generation :as generation]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))] (f))
         (finally (fs/delete-tree tmp)))))

(def ^:private a-baseline
  {:format :baseline :strata [] :intent {:seq 1}
   :area "order totalling" :bounded-by "money on an order" :shape "one summing path"
   :model {:elements [{:id "agg" :sort :module :hides "the summing order" :interface "a total"}]
           :claims [{:id "c1" :about ["agg"] :statement "the aggregate is the only summing path"
                     :falsified-by "a second path that sums lines" :evidence {:by :round}
                     :read-at ["src/a.clj:1"]}]}
   :read ["src/a.clj"]})

(defn- a-design
  "A design over the baseline at `b` marking `marked` generation-defining, with `extra` merged in.
   Every marked id is an element of its model."
  [b marked & {:as extra}]
  (let [marked (set marked)
        ids    (into ["agg"] (sort (disj marked "agg")))]
    (merge {:format :design :strata [] :summary "s" :shape "sh"
            :model {:elements (mapv #(cond-> {:id % :sort :module}
                                       (not= "agg" %) (assoc :hides "its part" :interface "its part")
                                       (marked %) (assoc :defines-generation true))
                                    ids)
                    :claims [{:id "one-summing-path" :about ["agg"] :statement "one summing path"
                              :falsified-by "a second path" :evidence {:by :round}}]}
            :standing {:relation :conforms}
            :baseline {:seq b :relation :within}
            :intent {:seq 1} :effort :S}
           extra)))

(def ^:private seat {:as :seat :seat "agg" :session "ws--agg"})

(defn- ledger
  "A workstream holding an intent (1), a baseline (2) and its review (3). Returns
   [id add stamped w] — add and stamped append and return the new entry's seq."
  []
  (let [id      (:id (ws/create! :brian {:external-refs []}))
        seq-now #(count (:entries (ws/read-ws :brian id)))
        add     (fn [kind record] (ws/append-entry! :brian id {:kind kind} (pr-str record)) (seq-now))
        stamped (fn [record] (ws/append-stamped! :brian id record) (seq-now))]
    (add :intent {:format :intent :goal "g" :done-when ["d"]})
    (add :baseline a-baseline)
    (add :baseline-review {:format :baseline-review :verdict :sufficient :baseline-seq 2 :reason "ok"})
    [id add stamped #(ws/read-ws :brian id)]))

(defn- recut!
  "A tension raised by the seat, and a re-cut by a person raised from it. Returns the re-cut's seq."
  [stamped ids]
  (let [t (stamped {:format :tension :by seat :to :conductor :about ["agg"] :statement "two secrets"})]
    (stamped {:format :recut :by {:as :person} :ids ids :why "split the aggregate"
              :tensions [{:seq t}]})))

(deftest a-ledger-with-no-generation-is-wholly-its-first
  (with-tmp
    (fn []
      (let [[_ add _ w] (ledger)
            d (add :design (a-design 2 []))]
        (is (= 1 (generation/current (:entries (w)))))
        (is (= 1 (generation/generation-of (:entries (w)) d)))
        (is (nil? (generation/generation-of (:entries (w)) 1)) "an intent belongs to none")
        (is (nil? (generation/generation-of (:entries (w)) 2)) "nor a baseline")
        (is (nil? (generation/generation-of (:entries (w)) 3)) "nor its review")
        (is (= d (generation/current-design (w))))
        (is (nil? (generation/fixed-ids (w))) "nothing marked, nothing fixed")))))

(deftest a-first-generation-fixes-its-ids-when-a-marking-design-is-approved
  (with-tmp
    (fn []
      (let [[_ add _ w] (ledger)
            d (add :design (a-design 2 ["agg"]))]
        (is (nil? (generation/fixed-ids (w))) "a draft's marks bind nothing")
        (is (= #{"agg"} (generation/defining-ids (ws/entry-at-seq :brian (:id (w)) d))))
        (add :design-approved {:format :design-approved :design {:seq d} :at-seq d})
        (is (= #{"agg"} (generation/fixed-ids (w))))
        (testing "a later design of the generation is held to them"
          (is (thrown-with-msg? Exception #"fixed its generation-defining ids"
                                (add :design (a-design 2 ["agg" "split"] :supersedes {:seq d :why "re-marked"}))))
          (is (thrown-with-msg? Exception #"marks none"
                                (add :design (a-design 2 [] :supersedes {:seq d :why "re-marked"}))))
          (is (pos? (add :design (a-design 2 ["agg"] :supersedes {:seq d :why "re-marked"})))))))))

(deftest the-generic-appends-refuse-a-generation-and-the-stamped-kinds
  (with-tmp
    (fn []
      (let [[id add] (ledger)]
        (is (thrown-with-msg? Exception #"open-generation!"
                              (add :generation {:format :generation :recut {:seq 3} :words "w"
                                                :ids ["agg"] :base-rev "r"})))
        (doseq [r [{:format :tension :by seat :to :conductor :about ["agg"] :statement "s"}
                   {:format :recut :by {:as :person} :ids ["agg"] :why "w"}]]
          (is (thrown-with-msg? Exception #"append-stamped!" (add (:format r) r))))
        (is (thrown? Exception (ws/check-entry :brian id {:kind :tension}
                                               (pr-str {:format :tension :by seat :to :conductor
                                                        :about ["agg"] :statement "s"}))))))))

(deftest an-accord-names-a-tension
  (with-tmp
    (fn []
      (let [[_ _ stamped] (ledger)]
        (is (thrown-with-msg? Exception #"expected :tension"
                              (stamped {:format :accord :by seat :tension {:seq 2} :terms "t"})))
        (let [t (stamped {:format :tension :by seat :to :conductor :about ["agg"] :statement "s"})]
          (is (pos? (stamped {:format :accord :by {:as :conductor :session "c"}
                              :tension {:seq t} :terms "t"}))))))))

(deftest opening-a-generation-starts-a-new-span
  (with-tmp
    (fn []
      (let [[id add stamped w] (ledger)
            d1 (add :design (a-design 2 ["agg"]))
            _  (add :design-approved {:format :design-approved :design {:seq d1} :at-seq d1})
            r  (recut! stamped ["agg" "split"])
            _  (ws/open-generation! :brian id {:format :generation :recut {:seq r} :words "split it"
                                               :ids ["agg" "split"] :base-rev "abc"})
            g  (count (:entries (w)))
            entries (:entries (w))]
        (is (= 2 (generation/current entries)))
        (is (= 2 (generation/generation-of entries g)) "a :generation belongs to the one it opens")
        (is (= 1 (generation/generation-of entries r)))
        (is (= g (:seq (generation/opened-by entries))))
        (is (= [1 2 3 g] (mapv :seq (generation/current-span entries)))
            "the span holds what belongs to none, and nothing of the first generation")
        (is (nil? (generation/current-design (w))) "no design governs a span that holds none")
        (is (= d1 (generation/closed-design (w) g)))
        (is (= #{"agg" "split"} (generation/fixed-ids (w))))
        (testing "the tensions the re-cut names are carried by value"
          (is (= [{:by seat :to :conductor :about ["agg"] :statement "two secrets"}]
                 (:tensions (ws/entry-at-seq :brian id g)))))
        (testing "the first design supersedes the design that governed the generation before"
          (is (thrown-with-msg? Exception #"supersedes the design that governed"
                                (add :design (a-design 2 ["agg" "split"]))))
          (is (thrown-with-msg? Exception #"fixed its generation-defining ids"
                                (add :design (a-design 2 ["agg"] :supersedes {:seq d1 :why "re-cut"}))))
          (let [d2 (add :design (a-design 2 ["agg" "split"] :supersedes {:seq d1 :why "re-cut"}))]
            (is (= d2 (generation/current-design (w))))
            (testing "and nothing else of the first generation may be cited"
              (is (thrown-with-msg? Exception #"cites entry .*, of generation 1"
                                    (add :design-approved {:format :design-approved
                                                           :design {:seq d1} :at-seq d1}))))))))))

(deftest a-generation-opens-only-from-a-recut-of-the-one-it-closes
  (with-tmp
    (fn []
      (let [[id add stamped] (ledger)
            d (add :design (a-design 2 []))
            gen (fn [n] {:format :generation :recut {:seq n} :words "w" :ids ["agg"] :base-rev "r"})]
        (is (thrown-with-msg? Exception #"opens from a :recut" (ws/open-generation! :brian id (gen d))))
        (let [r (recut! stamped ["agg"])]
          (ws/open-generation! :brian id (gen r))
          (is (thrown-with-msg? Exception #"of generation 1, and the one open is 2"
                                (ws/open-generation! :brian id (gen r)))))))))

(deftest no-generation-opens-under-a-live-review-run
  ;; A review run reads the ledger as it stood when it began, so the opening waits for it.
  (with-tmp
    (fn []
      (let [[id add stamped w] (ledger)
            _ (add :design (a-design 2 []))
            r (recut! stamped ["agg"])
            gen {:format :generation :recut {:seq r} :words "w" :ids ["agg"] :base-rev "r"}]
        (activity/with-claim :brian id {:kind :diff-review :run-id "review-1" :report-path nil}
          (fn []
            (is (thrown-with-msg? Exception #"a review run of it is live — review-1"
                                  (ws/open-generation! :brian id gen)))))
        (is (= 1 (generation/current (:entries (w)))) "the refusal wrote nothing")
        (ws/open-generation! :brian id gen)
        (is (= 2 (generation/current (:entries (w)))) "once the run ends it opens")))))

(deftest a-retracted-baseline-belongs-to-every-generation
  (with-tmp
    (fn []
      (let [[id add stamped w] (ledger)
            _ (add :design (a-design 2 []))
            r (recut! stamped ["agg"])
            _ (ws/open-generation! :brian id {:format :generation :recut {:seq r} :words "w"
                                              :ids ["agg"] :base-rev "r"})
            x (add :retraction {:format :retraction :retracts {:seq 2} :because "wrong survey" :evidence ["src/a.clj:1"]})]
        (is (nil? (generation/generation-of (:entries (w)) x)))
        (is (some #(= x (:seq %)) (generation/current-span (:entries (w)))))))))

(deftest a-retraction-indexed-before-targets-were-mirrored-still-belongs-to-none
  ;; A row written before retractions mirrored their target holds none; the target is read from
  ;; its entry instead, so the retraction of a baseline is still read in every generation.
  (with-tmp
    (fn []
      (let [[id add stamped w] (ledger)
            _ (add :design (a-design 2 []))
            r (recut! stamped ["agg"])
            _ (ws/open-generation! :brian id {:format :generation :recut {:seq r} :words "w"
                                              :ids ["agg"] :base-rev "r"})
            x (add :retraction {:format :retraction :retracts {:seq 2} :because "wrong survey" :evidence ["src/a.clj:1"]})
            dir  (cstate/workstream-dir :brian id)
            side (str (fs/path dir (format "entries.meta/%04d.edn" x)))
            edn-path (cstate/workstream-edn-path :brian id)
            strip #(if (= x (:seq %)) (dissoc % :retracts) %)]
        (spit side (pr-str (strip (read-string (slurp side)))))
        (spit edn-path (pr-str (update (read-string (slurp edn-path)) :entries #(mapv strip %))))
        (is (= 2 (:retracts (some #(when (= x (:seq %)) %) (:entries (w))))))
        (is (nil? (generation/generation-of (:entries (w)) x)))))))

(deftest a-citation-naming-its-own-workstream-is-held-to-the-generation
  (with-tmp
    (fn []
      (let [[id add stamped w] (ledger)
            d1 (add :design (a-design 2 ["agg"]))
            r  (recut! stamped ["agg" "split"])
            _  (ws/open-generation! :brian id {:format :generation :recut {:seq r} :words "split it"
                                               :ids ["agg" "split"] :base-rev "abc"})
            check #(#'ws/check-generation-citations! (w) %1 (pr-str %2))]
        (is (thrown-with-msg? Exception #"cites entry .*, of generation 1"
                              (check :design-decision {:overturns [{:id "c1" :seq d1 :ws-id id}]}))
            "a reference carrying this workstream's own :ws-id is one of this ledger's")
        (is (nil? (check :design-decision {:overturns [{:id "c1" :seq d1 :ws-id "another-ws"}]}))
            "one carrying another workstream's names another ledger")
        (is (thrown-with-msg? Exception #"cites entry .*, of generation 1"
                              (check :findings {:format :findings :round 1 :between-phases d1 :items []}))
            "the design a findings round names under :between-phases is a citation")))))

(deftest opening-a-generation-carries-what-its-decisions-falsified
  (with-tmp
    (fn []
      (let [[id add stamped] (ledger)
            d1 (add :design (a-design 2 ["agg"]))
            _  (add :design-decision {:format :design-decision :recommend :recut :design-seq d1
                                      :reason "r" :asks "worth it?"
                                      :checks [{:check :relation-honest :status :held :note "n"}]
                                      :findings [{:cites ["one-summing-path"] :claim-id "one-summing-path"
                                                  :claim "a second path sums the lines"}
                                                 {:cites ["one-summing-path"] :claim-id "one-summing-path"
                                                  :claim "is a second path wanted?" :for-person true}
                                                 {:cites ["agg"] :claim "names no claim"}]})
            r  (recut! stamped ["agg"])
            g  (:entries (ws/open-generation! :brian id {:format :generation :recut {:seq r} :words "w"
                                                         :ids ["agg"] :base-rev "r"
                                                         :falsified [{:claim "retold" :by "the opener"}]}))]
        (is (= [{:claim "one-summing-path" :by "a second path sums the lines"}]
               (:falsified (ws/entry-at-seq :brian id (count g))))
            "the claims the closing span's decisions found falsified, from the ledger, not the opener")))))

(deftest a-retired-generations-decisions-do-not-hold-up-a-clearance
  (with-tmp
    (fn []
      (let [[id add stamped w] (ledger)
            decide (fn [d] (add :design-decision {:format :design-decision :recommend :proceed
                                                  :design-seq d :reason "r" :asks "worth it?"
                                                  :checks [{:check :relation-honest :status :held :note "n"}]}))
            d1 (add :design (a-design 2 ["agg"]))
            x1 (decide d1)
            r  (recut! stamped ["agg"])
            _  (ws/open-generation! :brian id {:format :generation :recut {:seq r} :words "w"
                                              :ids ["agg"] :base-rev "r"})
            d2 (add :design (a-design 2 ["agg"] :supersedes {:seq d1 :why "re-cut"}))
            _  (decide d2)
            row (some #(when (= x1 (:seq %)) %) (:entries (w)))]
        (spit (str (fs/path (cstate/workstream-dir :brian id) (:file row))) "{:not")
        (is (nil? (#'ws/check-clearance-owed! (w) :design-cleared
                                              (pr-str {:format :design-cleared :design {:seq d2}})))
            "an unreadable decision of the generation before is not one this clearance rests on")))))

(deftest opening-a-generation-carries-what-its-verdicts-found-broken
  (with-tmp
    (fn []
      (let [[id add stamped] (ledger)
            d1 (add :design (a-design 2 ["agg"]))
            _  (add :design-verdict {:format :design-verdict :verdict :invalidated :round 1
                                     :design-seq d1 :reason "a second path sums lines"
                                     :invariants-broken [{:invariant "one-summing-path"
                                                          :finding "the invoice renderer sums independently"}]
                                     :invariants-unmet [{:invariant "agg"
                                                         :finding "the code falls short of it"}]
                                     :needs "redesign the totalling seam"})
            r  (recut! stamped ["agg"])
            g  (:entries (ws/open-generation! :brian id {:format :generation :recut {:seq r} :words "w"
                                                         :ids ["agg"] :base-rev "r"}))]
        (is (= [{:claim "one-summing-path" :by "the invoice renderer sums independently"}]
               (:falsified (ws/entry-at-seq :brian id (count g))))
            "a verdict's broken invariant crosses by value; an unmet one falsifies no claim")))))
