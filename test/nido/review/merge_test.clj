(ns nido.review.merge-test
  "A merge combines three effective models by id and names every conflict, and a parent is given a
   merged design only while none stands — whichever path appends it."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.record.fork :as fork]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.report.model :as model]
   [nido.design.check :as design]
   [nido.review.merge :as unit-merge]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    design/check   (fn [& _] {:status :satisfied})]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(defn- claim [id about statement]
  {:id id :about about :statement statement :falsified-by "its negation" :evidence {:by :round}})

(def ^:private agg   {:id "agg" :sort :module :hides "the summing order" :interface "an order's total"})
(def ^:private total {:id "total" :sort :operation})
(def ^:private c1    (claim "c1" ["agg"] "the aggregate is the only summing path"))

(def ^:private a-baseline
  {:format :baseline :strata [] :intent {:seq 1}
   :area "order totalling" :bounded-by "money on an order" :shape "one summing path"
   :model {:elements [agg total]
           :claims   [c1 (claim "c2" ["total"] "a total is rounded once")]}
   :health [{:id "h" :axis :implementation :observation "a slow query" :evidence ["src/a.clj:9"]}]
   :read ["src/a.clj"]})

(defn- a-design
  "A design stating `model` over the baseline at `bseq`."
  [bseq model & {:as more}]
  (merge {:format :design :strata [] :summary "s" :shape "sh" :effort :S
          :standing {:relation :conforms}
          :baseline {:seq bseq :relation :within}
          :intent {:seq 1}
          :routes [{:health-id "h" :to :constrains :why "nothing here reads the query"}]
          :model model}
         more))

(def ^:private unchanged {:elements [{:id "agg" :sort :module}] :claims [c1]})
(def ^:private c3-about-total {:elements [total] :claims [(claim "c3" ["total"] "a total is stored once")]})

(defn- add! [ws-id kind record]
  (ws/append-entry! :brian ws-id {:kind kind} (pr-str record))
  (:seq (ws/latest-entry :brian ws-id kind)))

(defn- parent-and-child
  "A parent whose design, entry 4, stands, and a child forked from it — whose baseline is entry 3."
  []
  (let [p (:id (ws/create! :brian {:stage :in-progress :external-refs []}))]
    (add! p :intent {:format :intent :goal "one summing path" :done-when ["totals agree"]})
    (add! p :baseline a-baseline)
    (add! p :baseline-review {:format :baseline-review :verdict :sufficient :baseline-seq 2 :reason "holds"})
    (add! p :design (a-design 2 unchanged))
    (add! p :design-approved {:format :design-approved :design {:seq 4} :at-seq 4})
    [p (:id (fork/fork! :brian p {:goal "store totals once" :done-when ["one stored total"]}))]))

(defn- parent-restates! [p model]
  (add! p :design (a-design 2 model :supersedes {:seq 4 :why "the parent moved on"})))

(defn- child-designs! [c model]
  (add! c :design (a-design 3 model)))

(def ^:private authored
  {:summary "the child's storage comes home" :shape "sh" :effort :S
   :standing {:relation :conforms} :baseline {:relation :within}
   :routes [{:health-id "h" :to :constrains :why "nothing here reads the query"}]})

(deftest a-change-only-the-child-made-merges-and-the-parent-carries-the-rest
  (with-tmp
    (fn [_]
      (let [[p c] (parent-and-child)
            _     (child-designs! c c3-about-total)
            prop  (unit-merge/proposal :brian c "/w")]
        (is (= [] (:conflicts prop)))
        (is (= ["c1" "c2" "c3"] (mapv :id (get-in prop [:model :claims])))
            "what neither side changed is carried")
        (let [{:keys [appended]} (unit-merge/merge! :brian c "/w" authored)
              merged             (ws/latest-entry :brian p :design)
              laid               (model/overlay (:model (ws/entry-at-seq :brian p 2)) (:model merged))]
          (is (some? appended))
          (is (= {:ws-id c :design {:seq 4}} (:merges merged)))
          (is (= 4 (get-in merged [:supersedes :seq])) "it supersedes the parent's current design")
          (is (= (set (get-in prop [:model :claims])) (set (:claims laid)))
              "and its effective model is the combination"))))))

(deftest each-kind-of-conflict-is-named-and-nothing-is-appended
  (testing "a claim both sides changed differently"
    (with-tmp
      (fn [_]
        (let [[p c] (parent-and-child)]
          (parent-restates! p {:elements [{:id "agg" :sort :module}]
                               :claims   [(claim "c1" ["agg"] "one summing path, as the parent says")]})
          (child-designs! c {:elements [{:id "agg" :sort :module}]
                             :claims   [(claim "c1" ["agg"] "one summing path, as the child says")]})
          (let [{:keys [proposal appended]} (unit-merge/merge! :brian c "/w" authored)]
            (is (= [{:kind :claim-diverged :names ["c1"]}] (:conflicts proposal)))
            (is (nil? appended))
            (is (= 6 (:seq (ws/latest-entry :brian p :design))) "the parent's own design is still its newest"))))))
  (testing "a claim kept about what the other side removed"
    (with-tmp
      (fn [_]
        (let [[p c] (parent-and-child)]
          (parent-restates! p {:elements [{:id "agg" :sort :module}] :claims [c1]
                               :removed  {:elements ["total"] :claims ["c2"]}})
          (child-designs! c c3-about-total)
          (is (= [{:kind :subject-removed :names ["c3" "total"]}]
                 (:conflicts (unit-merge/proposal :brian c "/w"))))))))
  (testing "a claim stated against a subject only the other side restated"
    (with-tmp
      (fn [_]
        (let [[p c] (parent-and-child)]
          (parent-restates! p {:elements [{:id "agg" :sort :module} (assoc total :interface "a rounded total")]
                               :claims   [c1]})
          (child-designs! c c3-about-total)
          (is (= [{:kind :subject-restated :names ["c3" "total"]}]
                 (:conflicts (unit-merge/proposal :brian c "/w")))
              "and c2, which both carry unchanged, relies on nothing the parent restated")))))
  (testing "a law the declaration breaks"
    (with-tmp
      (fn [_]
        (let [[_ c] (parent-and-child)]
          (child-designs! c c3-about-total)
          (with-redefs [design/check (fn [& _] {:status :violated
                                                :violations [{:law "every modelled Module is realized by a namespace"
                                                              :vars ["?m"] :offenders [["record-fork"]]}]})]
            (let [[conflict] (:conflicts (unit-merge/proposal :brian c "/w"))]
              (is (= :law-violated (:kind conflict)))
              (is (= "every modelled Module is realized by a namespace" (first (:names conflict)))))))))))

(deftest a-record-from-before-role-membership-is-refused-by-entry
  (with-tmp
    (fn [_]
      (let [[_ c]  (parent-and-child)
            _      (child-designs! c c3-about-total)
            latest ws/latest-entry]
        (with-redefs [ws/latest-entry (fn [project ws-id kind]
                                        (cond-> (latest project ws-id kind)
                                          (= c ws-id) (update-in [:model :elements] conj {:id "payer" :sort :role})))]
          (let [e (try (unit-merge/proposal :brian c "/w") nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (= :merge (:refused (ex-data e))))
            (is (= 4 (:seq (ex-data e))) "named by the child design's entry")
            (is (str/includes? (str (ex-message e)) "before a role named its players"))))))))

(deftest a-design-adding-a-module-it-does-not-describe-is-refused-by-entry
  (with-tmp
    (fn [_]
      (let [[_ c]  (parent-and-child)
            _      (child-designs! c c3-about-total)
            latest ws/latest-entry]
        (with-redefs [ws/latest-entry (fn [project ws-id kind]
                                        (cond-> (latest project ws-id kind)
                                          (= c ws-id) (update-in [:model :elements] conj
                                                                 {:id "ledger" :sort :module})))]
          (let [e (try (unit-merge/proposal :brian c "/w") nil
                       (catch clojure.lang.ExceptionInfo e e))]
            (is (= :merge (:refused (ex-data e))))
            (is (= 4 (:seq (ex-data e))) "named by the child design's entry")
            (is (= ["ledger"] (:modules (ex-data e))))))))))

(deftest the-ledger-appends-a-merged-design-only-as-the-combination
  (with-tmp
    (fn [_]
      (let [[p c]  (parent-and-child)
            _      (child-designs! c c3-about-total)
            append #(try (ws/append-entry! :brian p {:kind :design} (pr-str %)) nil
                         (catch clojure.lang.ExceptionInfo e (ex-message e)))
            merged (fn [model merges]
                     (a-design 2 model :supersedes {:seq 4 :why "merge"} :merges merges))]
        (is (str/includes? (str (append (merged unchanged {:ws-id c :design {:seq 4}})))
                           "not the combination")
            "a design claiming to merge the child while leaving its claim out")
        (is (str/includes? (str (append (merged c3-about-total {:ws-id p :design {:seq 4}})))
                           "no fork of")
            "a merge names a child of this workstream")
        (is (nil? (append (merged c3-about-total {:ws-id c :design {:seq 4}})))
            "and a design whose effective model is the combination is accepted — restating nothing it keeps")))))
