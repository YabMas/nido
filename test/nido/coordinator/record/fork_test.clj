(ns nido.coordinator.record.fork-test
  "A fork writes a child and nothing else: the parent reads exactly as it did, and the child starts
   from a baseline derived from the parent's, never surveyed."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.record.fork :as fork]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.report.model :as model]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(def ^:private a-baseline
  {:format :baseline :intent {:seq 1}
   :area "order totalling" :bounded-by "money on an order" :shape "one summing path"
   :model {:elements [{:id "agg" :sort :module :hides "the summing order" :interface "an order's total"}
                      {:id "total" :sort :operation}]
           :claims [{:id "c1" :about ["agg"] :statement "the aggregate is the only summing path"
                     :falsified-by "a second path that sums lines" :evidence {:by :round}}
                    {:id "c2" :about ["total"] :statement "a total is rounded once"
                     :falsified-by "a total rounded twice" :evidence {:by :round}}]}
   :health [{:id "h-fixed" :axis :design :observation "totals are stored twice" :evidence ["src/a.clj:1"]}
            {:id "h-kept" :axis :implementation :observation "a slow query" :evidence ["src/a.clj:9"]}]
   :read ["src/a.clj"]})

(defn- a-design [baseline-seq]
  {:format :design :summary "s" :shape "sh" :effort :S
   :standing {:relation :conforms}
   :baseline {:seq baseline-seq :relation :within}
   :intent {:seq 1}
   :model {:elements [{:id "agg" :sort :module}
                      {:id "writer" :sort :module :hides "where a total is stored" :interface "store a total"}]
           :claims [{:id "c1" :about ["agg"] :statement "the aggregate sums each line once"
                     :falsified-by "a line summed twice" :evidence {:by :round}}
                    {:id "c3" :about ["writer"] :statement "a total is stored once"
                     :falsified-by "two stored totals" :evidence {:by :round}}]
           :removed {:claims ["c2"]}}
   :routes [{:health-id "h-fixed" :to :fix-here}
            {:health-id "h-kept" :to :constrains :why "nothing here reads the query"}]})

(defn- parent
  "A parent whose design stands: intent 1, baseline 2 found sufficient at 3, design 4 granted at 5.
   Returns its id; `approve?` false leaves the design ungranted."
  ([] (parent true))
  ([approve?]
   (let [id  (:id (ws/create! :brian {:stage :in-progress :external-refs []}))
         add #(ws/append-entry! :brian id {:kind %1} (pr-str %2))]
     (add :intent {:format :intent :goal "one summing path" :done-when ["totals agree"]})
     (add :baseline a-baseline)
     (add :baseline-review {:format :baseline-review :verdict :sufficient :baseline-seq 2 :reason "holds"})
     (add :design (a-design 2))
     (when approve? (add :design-approved {:format :design-approved :design {:seq 4} :at-seq 4}))
     id)))

(defn- parent-on-disk
  "Everything a reader of `id` could see: the record, and every entry file's content by name."
  [id]
  (let [dir (fs/path (cstate/workstream-dir :brian id) "entries")]
    {:record  (ws/read-ws :brian id)
     :entries (into (sorted-map) (map (fn [f] [(str (fs/file-name f)) (slurp (str f))]))
                    (fs/list-dir dir))}))

(def ^:private goal {:goal "store totals once" :done-when ["one stored total per order"]})

(deftest a-fork-writes-its-child-and-leaves-the-parent-as-it-was
  (with-tmp
    (fn [_]
      (let [p      (parent)
            before (parent-on-disk p)
            child  (fork/fork! :brian p goal)
            ids    (:id child)]
        (testing "the parent's ledger and record are identical before and after"
          (is (= before (parent-on-disk p))))
        (testing "the child is another workstream, holding its goal, its lineage and a derived baseline"
          (is (not= p ids))
          (is (= [:intent :fork :baseline] (mapv :kind (:entries child))))
          (is (= {:parent p :baseline-seq 2 :design-seq 4} (fork/lineage child))))
        (let [derived (ws/latest-entry :brian ids :baseline)
              {pb :baseline pd :design} (fork/parent-records :brian (fork/lineage child))]
          (testing "its model is the parent design laid over the parent baseline"
            (is (= (model/overlay (:model pb) (:model pd)) (:model derived)))
            (is (= ["c1" "c3"] (mapv :id (get-in derived [:model :claims])))))
          (testing "it cites the :fork entry and its own goal, never the parent's"
            (is (= {:seq 2} (:fork derived)))
            (is (= {:seq 1} (:intent derived))))
          (testing "what the parent design committed to fixing is not the child's to inherit"
            (is (= ["h-kept"] (mapv :id (:health derived))))))))))

(deftest a-design-that-does-not-stand-is-not-forked
  (with-tmp
    (fn [_]
      (let [p        (parent false)
            existing (count (ws/list-ids :brian))
            e        (try (fork/fork! :brian p goal) nil
                          (catch clojure.lang.ExceptionInfo e e))]
        (is (= :fork (:refused (ex-data e))))
        (is (str/includes? (ex-message e) "does not stand"))
        (is (= existing (count (ws/list-ids :brian))) "and a refused fork leaves no workstream behind")))))

(deftest a-design-from-before-the-shared-model-is-refused-by-entry
  (with-tmp
    (fn [_]
      (let [p (parent)]
        (with-redefs [ws/latest-entry     (fn [_ _ _] {:seq 9 :format :design :baseline {:seq 2}
                                                       :invariants ["a total is rounded once"]})
                      standing/of-design (fn [& _] {:cleared? true})]
          (let [e (try (fork/fork! :brian p goal) nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (= :fork (:refused (ex-data e))))
            (is (str/includes? (ex-message e) "entry 9"))
            (is (str/includes? (ex-message e) "before the shared model"))))))))

(deftest a-record-from-before-role-membership-is-refused-by-entry
  (with-tmp
    (fn [_]
      (let [p        (parent)
            unplayed {:id "payer" :sort :role}
            refusal  (fn [] (try (fork/fork! :brian p goal) nil
                                 (catch clojure.lang.ExceptionInfo e e)))]
        (testing "a baseline role with no players, even where the design supplies them"
          (with-redefs [ws/entry-at-seq    (fn [_ _ _] (update-in a-baseline [:model :elements] conj unplayed))
                        ws/latest-entry    (fn [_ _ _] (-> (a-design 2)
                                                           (assoc :seq 4)
                                                           (update-in [:model :elements] conj
                                                                      (assoc unplayed :plays ["agg"]))))
                        standing/of-design (fn [& _] {:cleared? true})]
            (let [e (refusal)]
              (is (= :fork (:refused (ex-data e))))
              (is (= 2 (:seq (ex-data e))))
              (is (str/includes? (ex-message e) "baseline at entry 2"))
              (is (str/includes? (ex-message e) "before a role named its players")))))
        (testing "a design role with no players"
          (with-redefs [ws/latest-entry    (fn [_ _ _] (-> (a-design 2)
                                                           (assoc :seq 9)
                                                           (update-in [:model :elements] conj unplayed)))
                        standing/of-design (fn [& _] {:cleared? true})]
            (let [e (refusal)]
              (is (= :fork (:refused (ex-data e))))
              (is (str/includes? (ex-message e) "design at entry 9"))
              (is (str/includes? (ex-message e) "before a role named its players")))))))))

(deftest a-design-adding-a-module-it-does-not-describe-is-refused-by-entry
  ;; A design may name a module bare only where its baseline describes it. Watched on nido's own
  ;; ledger: a design naming five new modules bare left every fork of it refused anonymously.
  (with-tmp
    (fn [_]
      (let [p (parent)]
        (with-redefs [ws/latest-entry    (fn [_ _ _] (-> (a-design 2)
                                                         (assoc :seq 9)
                                                         (update-in [:model :elements] conj
                                                                    {:id "ledger" :sort :module})))
                      standing/of-design (fn [& _] {:cleared? true})]
          (let [e (try (fork/fork! :brian p goal) nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (= :fork (:refused (ex-data e))))
            (is (= ["ledger"] (:modules (ex-data e))))
            (is (str/includes? (ex-message e) "entry 9"))
            (is (str/includes? (ex-message e) "adds ledger without saying what each hides"))))))))

(deftest a-child-record-the-ledger-would-refuse-says-why
  (with-tmp
    (fn [_]
      (let [p (parent)]
        (with-redefs [ws/entry-at-seq    (fn [_ _ _] (dissoc a-baseline :read))
                      standing/of-design (fn [& _] {:cleared? true})]
          (let [e (try (fork/fork! :brian p goal) nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (= :fork (:refused (ex-data e))))
            (is (str/includes? (ex-message e) "would not be accepted"))
            (is (str/includes? (ex-message e) ":read")
                "the refusal names what the ledger would refuse, not only that it would")))))))

(deftest the-ledger-holds-a-fork-to-one-origin-on-another-workstream
  (with-tmp
    (fn [_]
      (let [p     (parent)
            child (:id (fork/fork! :brian p goal))
            fork  (fn [ws-id parent-ws b d]
                    (try (ws/append-entry! :brian ws-id {:kind :fork}
                                           (pr-str {:format :fork
                                                    :parent {:ws-id parent-ws :baseline {:seq b}
                                                             :design {:seq d}}}))
                         nil
                         (catch clojure.lang.ExceptionInfo e (ex-message e))))
            fresh (:id (ws/create! :brian {:stage :in-progress :external-refs []}))]
        (is (str/includes? (fork child p 2 4) "already a fork") "a unit has one origin")
        (is (str/includes? (fork fresh fresh 2 4) "is this one") "a fork's parent is another workstream")
        (is (str/includes? (fork fresh p 3 4) "expected a :baseline") "the cited baseline is one")
        (is (str/includes? (fork fresh "ws-nowhere" 2 4) "does not exist"))
        (with-redefs [model/predates #(when (= :design (:format %)) :role-players)]
          (let [refusal (fork fresh p 2 4)]
            (is (str/includes? refusal "design at entry 4"))
            (is (str/includes? refusal "before a role named its players")
                "a cited record no child's model can be derived from is refused by entry")))
        (is (nil? (fork fresh p 2 4)) "and a fork naming a real baseline and design is accepted")))))
