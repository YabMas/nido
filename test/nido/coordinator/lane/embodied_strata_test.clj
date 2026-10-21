(ns nido.coordinator.lane.embodied-strata-test
  "The strata embodiment carries only a workstream it can give every seat a session of its own,
   whether it carries all its seats or occupies or replaces one."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.lane.conduct :as conduct]
   [nido.coordinator.lane.embodied-strata :as embodied]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.core :as core]
   [nido.platform.project :as project]
   [nido.session.launcher :as launcher]
   [nido.session.lifecycle :as lifecycle]))

(def ^:private a-baseline
  {:format :baseline :strata [] :intent {:seq 1}
   :area "order totalling" :bounded-by "money on an order" :shape "one summing path"
   :model {:elements [{:id "agg" :sort :module :hides "the summing order" :interface "a total"}]
           :claims [{:id "c1" :about ["agg"] :statement "the aggregate is the only summing path"
                     :falsified-by "a second path that sums lines" :evidence {:by :round}
                     :read-at ["src/a.clj:1"]}]}
   :read ["src/a.clj"]})

(defn- a-design [strata marked]
  (let [marked (set marked)]
    {:format :design :strata strata :summary "s" :shape "sh"
     :model {:elements (into [(cond-> {:id "agg" :sort :module} (marked "agg") (assoc :defines-generation true))]
                             (map #(cond-> {:id % :sort :stratum :interface "words"
                                            :readings [{:lens :stratified/level :verdict :sound :because "b"}]}
                                     (marked %) (assoc :defines-generation true)))
                             strata)
             :claims [{:id "one-summing-path" :about ["agg"] :statement "one summing path"
                       :falsified-by "a second path" :evidence {:by :round}}]}
     :standing {:relation :conforms}
     :baseline {:seq 2 :relation :within}
     :intent {:seq 1} :effort :S}))

(defn- ledger [strata marked]
  (let [id  (:id (ws/create! :brian {:external-refs []}))
        add (fn [kind record] (ws/append-entry! :brian id {:kind kind} (pr-str record))
              (count (:entries (ws/read-ws :brian id))))]
    (add :intent {:format :intent :goal "g" :done-when ["d"]})
    (add :baseline a-baseline)
    (add :baseline-review {:format :baseline-review :verdict :sufficient :baseline-seq 2 :reason "ok"})
    (let [d (add :design (a-design strata marked))]
      (add :design-approved {:format :design-approved :design {:seq d} :at-seq d}))
    id))

(defn- run [f]
  (let [tmp      (fs/create-temp-dir)
        launched (atom [])]
    (try
      (with-redefs [core/nido-root             (constantly (str tmp))
                    lifecycle/up!              (fn [_ _] nil)
                    lifecycle/runtime-of       (fn [_ _] {:instance-id "brian--ws" :key "ws"})
                    launcher/session-briefing  (fn [& _] "briefing")
                    launcher/nido-add-dirs     (fn [] [])
                    project/list-projects      (fn [] {"brian" {:directory "/src/brian"}})
                    conduct/start!             (fn [o]
                                                 (swap! launched conj (:session-name o))
                                                 (session/update-substrate! (:project o) (:ws-id o) (:session-name o)
                                                                            #(assoc % :dispatching {:token "t" :at "now"}))
                                                 {})
                    conduct/retire!            (fn [_ _ _ _] nil)]
        (f launched))
      (finally (fs/delete-tree tmp)))))

(defn- refusal [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest two-strata-sharing-a-last-segment-are-two-sessions
  ;; A seat's session is named by its stratum's whole id, so strata that differ only before their
  ;; last segment are carried each by a session of its own.
  (run
    (fn [launched]
      (let [strata ["canvas.orders/store" "canvas.payments/store"]
            id     (ledger strata strata)]
        (is (= [{:seat "canvas.orders/store" :session (str id "--canvas_dorders_sstore-g1")}
                {:seat "canvas.payments/store" :session (str id "--canvas_dpayments_sstore-g1")}]
               (:occupied (embodied/carry! :brian id))))
        (is (= 2 (count (distinct @launched))))))))

(deftest a-seats-session-name-is-told-apart-from-every-other-ids
  (let [slug #'embodied/slug]
    (is (= "canvas_dstrata_srecord-status" (slug "canvas.strata/record-status")))
    (is (not= (slug "a.b/c") (slug "a/b.c")))
    (is (not= (slug "a_s") (slug "a/")))
    (is (not= (slug "A") (slug "a")))))

(deftest one-seat-is-occupied-only-where-the-strata-carry-the-workstream
  (run
    (fn [launched]
      (testing "a non-stratum marked"
        (let [strata ["canvas.strata/ledger" "canvas.strata/seats"]
              id     (ledger strata (conj strata "agg"))]
          (is (= ["agg"] (:foreign (refusal #(embodied/occupy! :brian id "agg")))))
          (is (= ["agg"] (:foreign (refusal #(embodied/replace! :brian id "canvas.strata/ledger")))))))
      (testing "a stratum left unmarked"
        (let [strata ["canvas.strata/ledger" "canvas.strata/seats"]
              id     (ledger strata ["canvas.strata/ledger"])]
          (is (= ["canvas.strata/seats"] (:unmarked (refusal #(embodied/occupy! :brian id "canvas.strata/ledger")))))))
      (is (= [] @launched))
      (testing "and occupied where they do"
        (let [strata ["canvas.strata/ledger" "canvas.strata/seats"]
              id     (ledger strata strata)]
          (is (= {:seat "canvas.strata/ledger" :session (str id "--canvas_dstrata_sledger-g1")}
                 (embodied/occupy! :brian id "canvas.strata/ledger"))))))))

(deftest carrying-starts-again-a-seat-whose-launch-failed
  (run
    (fn [launched]
      (let [strata ["canvas.strata/ledger" "canvas.strata/seats"]
            id     (ledger strata strata)]
        (with-redefs [lifecycle/up! (fn [_ _] (throw (ex-info "no runtime" {})))]
          (is (thrown-with-msg? Exception #"no runtime" (embodied/carry! :brian id))))
        (is (= [(str id "--canvas_dstrata_sledger-g1") (str id "--canvas_dstrata_sseats-g1")]
               (mapv :session (:occupied (embodied/carry! :brian id)))))
        (is (str/ends-with? (last @launched) "--canvas_dstrata_sseats-g1"))))))
