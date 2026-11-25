(ns nido.boot.intake-test
  "An arrival becomes core commands: a spawning fire opens its trigger's unit, with the skill as
   its first turn; a re-emit while that unit works opens nothing; a queue-mode fire pens the
   workstream and opens nothing in the core."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.boot.intake :as intake]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.work :as work]
   [nido.platform.core :as core]
   [nido.platform.project :as project]
   [nido.test.fake-core :as fake-core]))

(defn- with-home [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    project/list-projects (fn [] {"brian" {:directory (str tmp)}})]
        (cstate/ensure-dirs!)
        (fake-core/with-fake-core f))
      (finally (fs/delete-tree tmp)))))

(def ^:private triage
  {:name :triage :skill :triage-bug :payload "{{event/id}} {{event/title}}"
   :source {:type :manual}})

(defn- fire [trigger payload] {:project :brian :trigger trigger :payload payload})

(defn- the-ws [] (ws/find-by-ref :brian :notion "BR-9"))

(deftest a-fire-opens-its-triggers-unit-with-the-skill-as-first-turn
  (with-home
    (fn [c]
      (is (= :opened (work/open-fire! (fire (assoc triage :skill :fix) {:id "BR-9" :title "Crash"}))))
      (let [w (the-ws)
            st (get @(:states c) (str "brian/" (:id w)))
            [u & more] (vals (:units st))]
        (is (nil? more))
        (is (= "triage" (:trigger u)))
        (is (= {:spec {:executor :claude :instructions "/fix BR-9 Crash"}} (:first-turn u)))
        (is (= {:status :none} (:session u)) "the core provisions its session before the first turn")
        (testing "a re-emit while the unit is open opens nothing"
          (is (= :running (work/open-fire! (fire (assoc triage :skill :fix) {:id "BR-9" :title "Crash"}))))
          (is (= 1 (count (:units (get @(:states c) (str "brian/" (:id w)))))))))
      (testing "a source that works a ref once opens nothing on a ref it has a workstream for"
        (is (= :settled (work/open-fire! (fire (assoc triage :name :other)
                                                 {:id "BR-9" :once-per-ref? true}))))))))

(deftest a-queue-mode-fire-pens-and-opens-nothing
  (with-home
    (fn [c]
      (#'intake/route-fire! (fire (assoc triage :intake :queue :skill :fix) {:id "BR-9" :title "Crash"}))
      (is (some? (the-ws)) "penned for the person")
      (is (empty? @(:states c)) "the core is told nothing until the person promotes it"))))

(deftest a-fire-of-a-run-trigger-starts-a-run-and-no-workstream
  (with-home
    (fn [c]
      (let [groom {:name :review-queue :skill :review-queue :payload "Groom ({{event/id}})."
                   :source {:type :manual} :run? true :session-profile :lite
                   :session-name-prefix "review-queue-"}]
        (#'intake/route-fire! (fire groom {:id "nightly-2026-11-25" :nightly "true"}))
        (let [[[k st] & more] (seq @(:states c))
              id (get-in st [:run :id])
              u  (get-in st [:units id])]
          (is (nil? more) "one stream, and it is the run's")
          (is (= (str "run:brian/" id) k))
          (is (re-matches #"review-queue-[0-9a-f]{8}" id))
          (is (= {:trigger "review-queue" :profile :lite :payload {:id "nightly-2026-11-25" :nightly "true"}}
                 (select-keys (:run st) [:trigger :profile :payload])))
          (is (= :lite (:profile u)) "its session is made with the trigger's profile")
          (is (false? (get-in u [:first-turn :spec :ask?])) "it has nobody to ask")
          (is (.startsWith ^String (get-in u [:first-turn :spec :instructions]) "/review-queue Groom (nightly-2026-11-25)."))
          (is (.contains ^String (get-in u [:first-turn :spec :instructions])
                         (cstate/run-artifacts-dir id))
              "its turn is told where its artifacts go")
          (is (fs/directory? (cstate/run-artifacts-dir id)))
          (is (nil? (ws/find-by-ref :brian :notion "nightly-2026-11-25")) "no workstream is made for it"))
        (testing "the same fire again starts no second run"
          (#'intake/route-fire! (fire groom {:id "nightly-2026-11-25" :nightly "true"}))
          (is (= 1 (count @(:states c)))))))))
