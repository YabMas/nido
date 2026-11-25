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

(deftest a-fire-of-a-skill-with-its-own-recipe-is-a-background-run
  (with-home
    (fn [c]
      (is (= :opened (work/open-fire! (fire (assoc triage :own-recipe? true) {:id "BR-9" :title "Crash"}))))
      (let [w  (the-ws)
            st #(get @(:states c) (str "brian/" (:id w)))
            [u] (vals (:units (st)))]
        (is (:background? u) "a skill carrying its own procedure, on a workstream off the recipe")
        (is (not-any? #(= (:id w) (:ws-id %))
                      (mapcat val (dissoc (work/grouped :brian) :winding-down)))
            "the board does not list it")
        (fake-core/seed! c :brian (:id w)
                         [{:command :record-provision-attempt :unit (:id u) :attempt "p" :want :up}
                          {:command :record-provision-outcome :unit (:id u) :attempt "p" :outcome :done :cwd "/w"}
                          {:command :start-turn :turn "t" :unit (:id u) :spec {:executor :fake :instructions "i"}}
                          {:command :record-outcome :turn "t" :outcome :completed}])
        (is (:closed? (st)) "its turn completing ends it")
        (testing "a later fire on the same ref reopens it and runs again"
          (is (= :opened (work/open-fire! (fire (assoc triage :own-recipe? true :name :again)
                                                {:id "BR-9" :title "Crash"}))))
          (is (not (:closed? (st))))
          (is (= 2 (count (:units (st))))))))))

(deftest a-fire-without-its-own-recipe-is-not-background
  (with-home
    (fn [c]
      (work/open-fire! (fire triage {:id "BR-9" :title "Crash"}))
      (let [[u] (vals (:units (get @(:states c) (str "brian/" (:id (the-ws))))))]
        (is (not (:background? u)) "it runs nido's recipe, which a person follows")))))
