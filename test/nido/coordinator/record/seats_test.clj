(ns nido.coordinator.record.seats-test
  "A workstream's seats read off its records: the ids the generation fixes and nothing else, each
   with the interface its design gives it; tensions open until the addressee accords or the raiser
   withdraws; and what a generation inherited."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.record.seats :as seats]
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
  "A design over the baseline at 2 marking `marked`. \"agg\" is named bare, so its interface is
   the baseline's; every other id is new and states its own."
  [marked & {:as extra}]
  (let [marked (set marked)]
    (merge {:format :design :strata [] :summary "s" :shape "sh"
            :model {:elements (mapv #(cond-> {:id % :sort :module}
                                       (not= "agg" %) (assoc :hides "its part" :interface (str % "'s part"))
                                       (marked %) (assoc :defines-generation true))
                                    (into ["agg"] (sort (disj marked "agg"))))
                    :claims [{:id "one-summing-path" :about ["agg"] :statement "one summing path"
                              :falsified-by "a second path" :evidence {:by :round}}]}
            :standing {:relation :conforms}
            :baseline {:seq 2 :relation :within}
            :intent {:seq 1} :effort :S}
           extra)))

(defn- seat [id] {:as :seat :seat id :session (str "ws--" id)})
(def ^:private conductor {:as :conductor :session "ws--conductor"})

(defn- ledger
  "An intent (1), a baseline (2) and its review (3). Returns [id add stamped]."
  []
  (let [id      (:id (ws/create! :brian {:external-refs []}))
        seq-now #(count (:entries (ws/read-ws :brian id)))
        add     (fn [kind record] (ws/append-entry! :brian id {:kind kind} (pr-str record)) (seq-now))
        stamped (fn [record] (ws/append-stamped! :brian id record) (seq-now))]
    (add :intent {:format :intent :goal "g" :done-when ["d"]})
    (add :baseline a-baseline)
    (add :baseline-review {:format :baseline-review :verdict :sufficient :baseline-seq 2 :reason "ok"})
    [id add stamped]))

(deftest a-draft-designs-marks-seat-nobody
  (with-tmp
    (fn []
      (let [[id add] (ledger)
            d (add :design (a-design ["agg" "split"]))]
        (is (= [{:id 1 :whole true :interface "sh" :tensions []}] (seats/seats :brian id))
            "nothing is fixed until the design is approved, so the generation has its whole seat")
        (add :design-approved {:format :design-approved :design {:seq d} :at-seq d})
        (is (= [{:id "agg" :interface "a total" :tensions []}
                {:id "split" :interface "split's part" :tensions []}]
               (seats/seats :brian id))
            "a bare element reads with its baseline's interface")))))

(deftest a-tension-is-open-until-its-addressee-accords-or-its-raiser-withdraws
  (with-tmp
    (fn []
      (let [[id add stamped] (ledger)
            d  (add :design (a-design ["agg" "split"]))
            _  (add :design-approved {:format :design-approved :design {:seq d} :at-seq d})
            t1 (stamped {:format :tension :by (seat "agg") :to "split" :about ["agg"] :statement "s1"})
            t2 (stamped {:format :tension :by (seat "agg") :to "gone" :about ["agg"] :statement "s2"})
            t3 (stamped {:format :tension :by (seat "split") :to :conductor :about ["split"] :statement "s3"})
            state #(into {} (map (juxt :seq (juxt :to :state))) (seats/tensions :brian id))]
        (is (= {t1 ["split" :open] t2 ["gone" :open] t3 [:conductor :open]} (state))
            "a tension to a seat the generation lacks is nobody's, never the conductor's by default")
        (is (= [t1] (mapv :seq (:tensions (second (seats/seats :brian id))))))
        (testing "an accord from anyone but the addressee settles nothing"
          (stamped {:format :accord :by conductor :tension {:seq t1} :terms "t"})
          (stamped {:format :accord :by (seat "agg") :tension {:seq t1} :terms "t"})
          (is (= :open (get-in (state) [t1 1]))))
        (let [a (stamped {:format :accord :by (seat "split") :tension {:seq t1} :terms "t"})]
          (is (= :accorded (get-in (state) [t1 1])))
          (is (= a (:settled-by (first (seats/tensions :brian id)))))
          (is (= [] (:tensions (second (seats/seats :brian id))))))
        (testing "nobody can accord a tension addressed to no seat"
          (stamped {:format :accord :by conductor :tension {:seq t2} :terms "t"})
          (is (= :open (get-in (state) [t2 1]))))
        (testing "a withdrawal settles only from the one who raised it"
          (stamped {:format :withdrawal :by (seat "split") :tension {:seq t2}})
          (is (= :open (get-in (state) [t2 1])))
          (stamped {:format :withdrawal :by (seat "agg") :tension {:seq t2}})
          (is (= :withdrawn (get-in (state) [t2 1]))))
        (testing "the conductor settles what is addressed to it"
          (stamped {:format :accord :by conductor :tension {:seq t3} :terms "t"})
          (is (= :accorded (get-in (state) [t3 1]))))))))

(deftest a-new-generation-has-its-own-seats-and-what-it-inherited
  (with-tmp
    (fn []
      (let [[id add stamped] (ledger)
            d1 (add :design (a-design ["agg"]))
            _  (add :design-approved {:format :design-approved :design {:seq d1} :at-seq d1})
            t  (stamped {:format :tension :by (seat "agg") :to :conductor :about ["agg"]
                         :statement "two secrets"})
            r  (stamped {:format :recut :by {:as :person} :ids ["agg" "split"] :why "split it"
                         :tensions [{:seq t}]})]
        (is (nil? (seats/inherited :brian id)) "a first generation inherits nothing")
        (ws/open-generation! :brian id {:format :generation :recut {:seq r} :words "split it"
                                        :ids ["agg" "split"] :base-rev "abc"})
        (is (= [{:id "agg" :interface nil :tensions []} {:id "split" :interface nil :tensions []}]
               (seats/seats :brian id))
            "the generation's ids are its seats before its design states them")
        (is (= [] (seats/tensions :brian id)) "the first generation's tensions are not this one's")
        (is (= {:recut {:seq r} :words "split it" :ids ["agg" "split"] :base-rev "abc"
                :tensions [{:by (seat "agg") :to :conductor :about ["agg"] :statement "two secrets"}]}
               (select-keys (seats/inherited :brian id) [:recut :words :ids :base-rev :tensions])))
        (add :design (a-design ["agg" "split"] :supersedes {:seq d1 :why "re-cut"}))
        (is (= ["a total" "split's part"] (mapv :interface (seats/seats :brian id))))))))

(deftest a-generation-fixing-no-ids-has-one-whole-seat
  (with-tmp
    (fn []
      (let [[id add stamped] (ledger)]
        (is (= [{:id 1 :whole true :interface nil :tensions []}] (seats/seats :brian id))
            "a generation always has a seat, before any design too")
        (let [t (stamped {:format :tension :by conductor :to 1 :about ["agg"] :statement "s"})]
          (is (= [t] (mapv :seq (:tensions (first (seats/seats :brian id)))))
              "a tension to the whole seat is the whole seat's while it stands")
          (let [d (add :design (a-design ["agg"]))]
            (add :design-approved {:format :design-approved :design {:seq d} :at-seq d}))
          (is (= ["agg"] (mapv :id (seats/seats :brian id))) "fixing ids replaces the whole seat")
          (is (= :conductor (:to (first (seats/tensions :brian id))))
              "once the whole seat is gone, what was addressed to it is the conductor's"))))))
