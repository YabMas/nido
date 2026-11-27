(ns tasks.nido-workstream-send-back-test
  "ws-20261120-d22b85's shape: a release candidate with an operator ask about landing open on it.
   The person's word sending it back for rework reaches the conductor through tell's path, the
   conductor's send_back tool takes it up, and the workstream leaves Needs you as work underway."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.lane.landing :as landing]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.record.sent-back-test :as fixture]
   [nido.coordinator.record.standing :as standing]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.work :as work]
   [nido.coordinator.work.core :as wcore]
   [nido.core.buffer :as buffer]
   [nido.core.domain :as d]
   [nido.platform.core :as core]
   [nido.platform.project :as project]
   [nido.platform.task-args :as task-args]
   [tasks.nido-workstream :as task]))

(def ^:private landing-positions #{:release-candidate :published :land-granted})

(defn- ask! [id turn ask question]
  (wcore/act! :brian id {:command :start-turn :turn turn :unit nil :spec {:executor :fake :instructions "i"}})
  (wcore/act! :brian id {:command :record-outcome :turn turn :run 1 :outcome :asked
                         :call {:id (str "call-" turn) :question question}})
  (wcore/act! :brian id {:command :raise-operator-ask :ask ask :binds [turn]
                         :content {:question question :explored "The review converged."
                                   :options [{:option "Land it" :consequence "It lands."} "Not yet"]
                                   :recommendation "Land it"}}))

(defn- run-verb-in-process
  "What `run-argv` answers for send_back's argv, with nido's verb run in this process — the suite's
   core is in-process, so a child bb would speak to none."
  [argv _]
  (is (= "nido:workstream:send-back" (nth argv 3)))
  (let [[_ opts] (task-args/split-args (drop 4 argv) #{:words})
        r        (task/send-back* opts)]
    {:exit (if (:sent-back r) 0 1) :out (pr-str r)}))

(defn- row [id] (first (filter #(= id (:ws-id %)) (work/list-workstreams :brian))))

(deftest a-word-sending-a-candidate-back-takes-back-its-landing
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root              (constantly (str tmp))
                    project/list-projects       (fn [] {:brian {:directory (str tmp)}})
                    landing/names-pr?           (constantly false)
                    standing/activated?         (constantly true)
                    buffer/run-argv             run-verb-in-process]
        (cstate/ensure-dirs!)
        (let [[id] (fixture/candidate!)]
          (ask! id "k1" "a1" "Land the totals change?")
          (ask! id "k2" "a2" "Land it now, or after the next release?")
          (testing "before: a release candidate, awaiting the person"
            (is (= :release-candidate (:at (pipeline/of :brian id))))
            (is (:needs-you (row id))))
          (testing "the person's word goes through tell's path, and answers the oldest ask"
            (is (= "a1" (:answered (work/person-says! :brian id "No — rework the totals first." [])))))
          (testing "the conductor's send_back takes the word up, naming the landing ask still open"
            (let [out (buffer/call-tool nil (str "brian/" id) "c3" "send_back"
                                        {:words "No — rework the totals first." :asks ["a2"]}
                                        {:nido-dir "/nido" :bb-bin "bb"})]
              (is (str/starts-with? out "ok: ") out)))
          (testing "after: nothing awaits the person, the position is off the landing rungs, and the
                    workstream is off Needs you"
            (let [st (wcore/state :brian id)]
              (is (empty? (d/awaiting-person st)))
              (is (not (contains? (:grants st) :merge))))
            (let [pos (pipeline/of :brian id)]
              (is (not (landing-positions (:at pos))))
              (is (= :implement (get-in pos [:next :stage]))))
            (let [r (row id)]
              (is (not (:needs-you r)))
              (is (nil? (:needs r)))
              (is (not (landing-positions (get-in r [:position :at]))))))
          (testing "a second send-back is refused at its position, sending the core nothing"
            (let [before (count (:ledger (wcore/state :brian id)))
                  r      (task/send-back* {:project "brian" :ws-id id :words "again"})]
              (is (= :not-at-a-landing (:refused r)))
              (is (= before (count (:ledger (wcore/state :brian id)))))))))
      (finally (fs/delete-tree tmp)))))

(deftest send-back-runs-nido-s-verb-in-the-checkout
  (is (= ["bb" "--config" "/nido/bb.edn" "nido:workstream:send-back" ":project" "brian"
          ":ws-id" "ws-1" ":words" "rework it" ":asks" "[\"ask 1\"]"]
         (buffer/send-back-argv {:nido-dir "/nido"} "brian/ws-1" {:words "rework it" :asks ["ask 1"]})))
  (is (re-find #"unavailable"
               (buffer/call-tool nil "brian/ws-1" "c1" "send_back" {:words "w"} {}))
      "a conductor given no checkout says so rather than running anything"))

(deftest every-brief-tells-the-conductor-to-record-a-send-back-first
  (let [brief (buffer/brief "brian/ws-1" d/empty-state [])]
    (is (re-find #"record that with send_back BEFORE you direct any unit to the rework" brief))
    (is (re-find #"A word that only asks a question, or says to land, sends nothing back" brief))
    (is (re-find #"Never ask the person whether to land" brief)))
  (is (some #{"send_back"}
            (map :name (get-in (buffer/respond {:socket "/s" :ws "brian/ws-1" :turn "c1"}
                                               {:id 1 :method "tools/list"})
                               [:result :tools])))))
