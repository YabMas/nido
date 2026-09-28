(ns nido.ui.server-review-queue-test
  "The review-queue grooming on the Operations surface: a plan run's plan shown
   ticket by ticket, decisions recorded per write, and apply firing exactly once
   with the decisions frozen."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.control :as control]
   [nido.coordinator.record.review-queue :as rq]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.triggers :as triggers]
   [nido.coordinator.source.queue :as queue]
   [nido.coordinator.view.review-queue :as rqv]
   [nido.coordinator.work :as work]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.platform.project :as project]
   [nido.ui.server :as server]))

(def ^:private real-fire
  "control/fire! as shipped, taken before any test stubs it."
  control/fire!)

(def ^:private plan-trigger
  {:name :review-queue :source {:type :manual} :skill :review-queue
   :payload "Groom ({{event/id}})." :payload-key :id :limits {:budget "1h"}})

(def ^:private plan
  {:tickets [{:br "BR-2" :title "Second" :url "https://n/2" :lifecycle "Prod" :rank 2
              :qa :keep :why "cosmetic"}
             {:br "BR-1" :title "First" :url "https://n/1" :lifecycle "Prod" :rank 1
              :qa :write :why "licences for every school"}]
   :items   [{:n 1 :br "BR-1" :kind :rank :summary "rank → 1"}
             {:n 2 :br "BR-1" :kind :brief :summary "prepend brief" :detail "What changed: …"}
             {:n 3 :br "BR-2" :kind :delete :summary "delete empty template"}
             {:n 4 :br nil :kind :schema :summary "add Review rank"}]
   :flags   ["BR-9 in Review with no Lifecycle"]})

(defn- with-plan
  "A throwaway root with one :review-queue plan run holding `plan`, brian declaring
   the trigger, and every fire recorded rather than queued."
  [f]
  (let [tmp   (fs/create-temp-dir)
        fired (atom [])]
    (try
      (with-redefs [core/nido-root              (constantly (str tmp))
                    server/read-rail-daemon     (constantly {:state :up})
                    project/list-projects       (constantly {"brian" {}})
                    triggers/load-for-project   (fn [p] (if (= :brian p) [plan-trigger] []))
                    control/fire!               (fn [p t payload & [k]] (swap! fired conj [p t payload k]))]
        (let [run (runs/create-run! {:project :brian :trigger plan-trigger :payload {:id "x"}
                                     :session-profile :lite}
                                    {:fired-at "t" :fired-by "test"})]
          (runs/transition! (:id run) :running)
          (runs/transition! (:id run) :done)
          (io/write-edn! (rq/plan-path (:id run)) plan)
          (f (:id run) fired)))
      (finally (fs/delete-tree tmp)))))

(defn- get-body [uri]
  (str (:body (server/handle-request {:request-method :get :uri uri}))))

(defn- post! [uri & [qs]]
  (server/handle-request {:request-method :post :uri uri :query-string qs}))

(deftest the-plan-is-shown-in-rank-order-with-its-writes
  (with-plan
    (fn [_ _]
      (let [body (get-body "/operations/review-queue")]
        (is (str/includes? body "_fragment/operations/review-queue") "the page polls its fragment")
        (is (< (str/index-of body "licences for every school") (str/index-of body "cosmetic"))
            "rank 1 before rank 2, whatever order the plan listed them in")
        (is (str/includes? body "prepend brief"))
        (is (str/includes? body "What changed: …") "the full brief is on the page, not only its summary")
        (is (str/includes? body "add Review rank") "a write about no ticket is shown too")
        (is (str/includes? body "BR-9 in Review with no Lifecycle") "and the flags")
        (is (str/includes? body "4 undecided"))
        (is (not (str/includes? body "Apply ")) "nothing approved, nothing to apply")))))

(deftest a-decision-is-recorded-and-an-unknown-verdict-is-not
  (with-plan
    (fn [run-id _]
      (post! (str "/operations/review-queue/brian/" run-id "/items/2") "verdict=approved")
      (post! (str "/operations/review-queue/brian/" run-id "/items/3") "verdict=maybe")
      (is (= {2 :approved} (:decisions (rq/read-decisions run-id))))
      (let [body (get-body "/_fragment/operations/review-queue")]
        (is (not (str/includes? body "Apply 1 approved")) "three items are still undecided")
        (is (str/includes? body "Approve or skip every item to apply"))))))

(deftest a-decision-about-an-item-the-plan-does-not-have-is-refused
  (with-plan
    (fn [run-id _]
      (is (= :no-item (:decision (rq/decide! run-id 99 :approved))))
      (is (= {} (:decisions (rq/read-decisions run-id)))))))

(deftest apply-freezes-the-decisions-and-fires-once
  (with-plan
    (fn [run-id fired]
      (post! (str "/operations/review-queue/brian/" run-id "/approve-all"))
      (post! (str "/operations/review-queue/brian/" run-id "/items/3") "verdict=skipped")
      (post! (str "/operations/review-queue/brian/" run-id "/apply"))
      (post! (str "/operations/review-queue/brian/" run-id "/apply"))
      (is (= 1 (count @fired)) "a second apply of one plan would write every change twice")
      (let [[p t payload k] (first @fired)]
        (is (= [:brian :review-queue-apply] [p t]))
        (is (= run-id (:plan-run payload)))
        (is (= (str "review-queue-apply-" run-id) k) "keyed by the plan"))
      (is (= :locked (:decision (rq/decide! run-id 1 :skipped)))
          "a decision changed after apply was asked for would not be the one carried out")
      (is (= {1 :approved 2 :approved 3 :skipped 4 :approved}
             (:decisions (rq/read-decisions run-id))))
      (is (str/includes? (get-body "/_fragment/operations/review-queue") "Applying")))))

(deftest apply-is-refused-while-an-item-is-undecided
  (with-plan
    (fn [run-id fired]
      (post! (str "/operations/review-queue/brian/" run-id "/items/2") "verdict=approved")
      (post! (str "/operations/review-queue/brian/" run-id "/apply"))
      (is (empty? @fired))
      (is (nil? (:applying (rq/read-decisions run-id))) "nothing frozen")
      (is (= {:decision :undecided :items [1 3 4]}
             (rq/apply! run-id (fn [] (throw (ex-info "must not fire" {})))))))))

(deftest a-failed-fire-leaves-the-plan-frozen-and-apply-fires-it-again
  (with-plan
    (fn [run-id fired]
      (post! (str "/operations/review-queue/brian/" run-id "/approve-all"))
      (with-redefs [control/fire! (fn [& _] (throw (ex-info "queue unwritable" {})))]
        (binding [*err* (java.io.StringWriter.)]
          (post! (str "/operations/review-queue/brian/" run-id "/apply"))))
      (is (empty? @fired))
      (is (get-in (rq/read-decisions run-id) [:applying :at]) "frozen")
      (is (str/includes? (get-body "/_fragment/operations/review-queue") "Apply again"))
      (is (= :locked (:decision (rq/decide! run-id 1 :skipped))) "and its decisions stay frozen")
      (post! (str "/operations/review-queue/brian/" run-id "/apply"))
      (is (= 1 (count @fired)) "the retry queues the apply")
      (post! (str "/operations/review-queue/brian/" run-id "/apply"))
      (is (= 1 (count @fired)) "and once recorded, Apply queues nothing more"))))

(deftest a-stop-between-queueing-and-recording-queues-no-second-envelope
  ;; The real control/fire! and queue, not the stub: this is the case only the key answers.
  (with-plan
    (fn [run-id _]
      (doseq [n [1 2 3 4]] (rq/decide! run-id n :approved))
      (with-redefs [control/fire! real-fire]
        (let [fire #(control/fire! :brian %1 %2 %3)]
          (work/begin-review-apply! run-id fire)
          ;; the process stopped after queueing, before the fire was recorded
          (io/update-edn! (rq/decisions-path run-id) update :applying dissoc :fired-at)
          (is (= :unfired (:stage (first (work/review-queues)))))
          (work/begin-review-apply! run-id fire)
          (is (= 1 (count (queue/drain!))) "one envelope, not two")
          (is (get-in (rq/read-decisions run-id) [:applying :fired-at]) "and the retry recorded the fire")
          (io/update-edn! (rq/decisions-path run-id) update :applying dissoc :fired-at)
          (work/begin-review-apply! run-id fire)
          (is (empty? (queue/drain!)) "nor after the daemon has drained the first"))))))

(deftest nothing-approved-fires-nothing
  (with-plan
    (fn [run-id fired]
      (doseq [n [1 2 3 4]] (rq/decide! run-id n :skipped))
      (post! (str "/operations/review-queue/brian/" run-id "/apply"))
      (is (empty? @fired))
      (is (nil? (:applying (rq/read-decisions run-id)))))))

(deftest running-a-grooming-fires-the-plan-trigger-once-per-click-window
  (with-plan
    (fn [_ fired]
      (post! "/operations/review-queue/brian/run")
      (post! "/operations/review-queue/brian/run")
      (is (= 1 (count @fired)) "the envelope waits on the queue; a second click must not add one")
      (is (= [:brian :review-queue] (take 2 (first @fired))))
      (is (str/includes? (get-body "/_fragment/operations/review-queue") "Requested")))))

(deftest the-home-card-counts-what-is-left-to-decide
  (with-plan
    (fn [run-id _]
      (with-redefs [work/improvement-holds (constantly [])]
        (rq/decide! run-id 1 :approved)
        (let [home (get-body "/operations")]
          (is (str/includes? home "href=\"/operations/review-queue\""))
          (is (str/includes? home "3 to decide")))))))

(deftest stage-follows-the-runs-and-the-decisions
  (is (= :none (rqv/stage {})))
  (is (= :planning (rqv/stage {:plan-run {:state :running}})))
  (is (= :failed (rqv/stage {:plan-run {:state :done}})) "a finished run with no plan")
  (is (= :deciding (rqv/stage {:plan-run {:state :done} :plan plan :decisions {:decisions {}}})))
  (is (= :applying (rqv/stage {:plan-run {:state :done} :plan plan
                               :decisions {:applying {:at "t" :fired-at "t"}}}))
      "queued, and the apply run not created yet")
  (is (= :unfired (rqv/stage {:plan-run {:state :done} :plan plan :decisions {:applying {:at "t"}}}))
      "frozen, and the fire never recorded")
  (is (= :applied (rqv/stage {:plan-run {:state :done} :plan plan :decisions {:applying {:at "t"}}
                              :apply-run {:state :done}}))))
