(ns nido.ui.refused-sessions-test
  "A unit session whose destroy was refused is listed on the Operations page — closed and discarded
   workstreams included — with its workstream, unit and the path kept."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.core.client :as client]
   [nido.core.domain :as d]
   [nido.coordinator.work :as work]
   [nido.platform.core :as core]
   [nido.platform.project :as project]
   [nido.ui.views :as views]))

(def ^:private host {:cores 4 :load nil :memory nil :disk nil :coordinator nil :kinds nil :need nil})

(defn- commit! [ws-id cmds]
  (doseq [[i c] (map-indexed vector cmds)]
    (let [o (client/command! (client/ws-key :nido ws-id) (str ws-id "-" i) c)]
      (when (:refused o) (throw (ex-info "refused" {:command c :outcome o}))))))

(defn- refused-unit [unit cwd ends]
  (concat [{:command :create-workstream
            :identity {:project :nido :external-refs [] :created-at "2026-11-26T00:00:00Z"}}
           {:command :open-unit :unit unit :project "nido" :goal "g"}
           {:command :record-provision-attempt :unit unit :attempt "up" :want :up}
           {:command :record-provision-outcome :unit unit :attempt "up" :outcome :done :cwd cwd}]
          ends
          [{:command :record-provision-attempt :unit unit :attempt "d" :want :destroyed}
           {:command :record-provision-outcome :unit unit :attempt "d" :outcome :refused
            :detail (str "Refusing to destroy session " unit ": " cwd " is not its own to remove")}]))

(deftest a-summary-carries-refused-sessions-only-when-there-is-one
  (is (not (contains? (d/summary d/empty-state) :refused-sessions))
      "so a summary stored before the key existed still reads equal to its state's")
  (let [s (reduce (fn [s c] (first (d/step s c))) d/empty-state
                  (refused-unit "u" "/src/nido" [{:command :record-merge :unit "u"} {:command :close-workstream}]))]
    (is (= [{:unit "u" :cwd "/src/nido" :detail "Refusing to destroy session u: /src/nido is not its own to remove"}]
           (:refused-sessions (d/summary s))))))

(deftest refused-sessions-are-read-on-every-workstream-of-every-project
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root        (constantly (str tmp))
                    project/list-projects (fn [] {:nido {:directory (str tmp)}})]
        (commit! "ws-closed" (refused-unit "a" "/src/nido" [{:command :record-merge :unit "a"}
                                                            {:command :close-workstream}]))
        (commit! "ws-discarded" (refused-unit "b" "/home/me/Code/nido" [{:command :retract-unit-goal :unit "b"}
                                                                       {:command :discard-workstream}]))
        (commit! "ws-open" [{:command :create-workstream
                             :identity {:project :nido :external-refs [] :created-at "2026-11-26T00:00:00Z"}}
                            {:command :open-unit :unit "c" :project "nido" :goal "g"}])
        (let [rs (work/refused-sessions)]
          (is (= [["ws-closed" "a" "/src/nido" true false]
                  ["ws-discarded" "b" "/home/me/Code/nido" false true]]
                 (mapv (juxt :ws :unit :path :closed? :discarded?) rs)))
          (testing "and listed on the Operations page with workstream, unit and kept path"
            (let [html (str (views/operations-home-fragment
                             (views/operations-home-cards [] nil [] [] {:casualties [] :refused rs} host)))]
              (is (str/includes? html "Refused destroys"))
              (is (str/includes? html "2 sessions kept"))
              (doseq [s ["nido/ws-closed" "unit a" "/src/nido" "nido/ws-discarded" "(discarded)" "/home/me/Code/nido"]]
                (is (str/includes? html s) s))))))
      (finally (fs/delete-tree tmp)))))

(deftest the-notice-says-when-it-could-not-read-and-is-absent-when-nothing-was-refused
  (let [cards (fn [n] (str (views/operations-home-fragment (views/operations-home-cards [] nil [] [] n host))))]
    (is (not (str/includes? (cards {:casualties [] :refused []}) "Refused destroys")))
    (is (not (str/includes? (cards nil) "Refused destroys")))
    (is (str/includes? (cards {:casualties [] :refused nil}) "could not be read"))))

(deftest a-runs-refused-destroy-is-listed-too
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root        (constantly (str tmp))
                    project/list-projects (fn [] {:nido {:directory (str tmp)}})]
        (doseq [[i c] (map-indexed
                       vector
                       [{:command :start-run
                         :run {:id "r1" :project "nido" :trigger "t" :goal "g" :payload {}
                               :fired-at "2026-11-26T00:00:00Z"}
                         :spec {:executor :claude :ask? false :instructions "/x"}}
                        {:command :record-provision-attempt :unit "r1" :attempt "up" :want :up}
                        {:command :record-provision-outcome :unit "r1" :attempt "up" :outcome :done
                         :cwd "/w/r1"}
                        {:command :start-turn :turn "r1 first" :unit "r1"
                         :spec {:executor :claude :ask? false :instructions "/x"}}
                        {:command :record-outcome :turn "r1 first" :outcome :completed}])]
          (let [o (client/command! (client/run-key :nido "r1") (str "r1-" i) c)]
            (when (:refused o) (throw (ex-info "refused" {:command c :outcome o})))))
        (let [k (client/run-key :nido "r1")
              owed (fn [] (d/sessions-owed (client/value! (client/state k) :state)))]
          (is (= [{:unit "r1" :want :destroyed}] (owed)) "a run whose turn ended owes its destroy")
          (doseq [[i c] (map-indexed
                         vector
                         [{:command :record-provision-attempt :unit "r1" :attempt "d" :want :destroyed}
                          {:command :record-provision-outcome :unit "r1" :attempt "d" :outcome :refused
                           :detail "Refusing to destroy session r1"}])]
            (let [o (client/command! k (str "r1-d-" i) c)]
              (when (:refused o) (throw (ex-info "refused" {:command c :outcome o}))))))
        (is (= [["r1" true "r1" "/w/r1"]] (mapv (juxt :ws :run? :unit :path) (work/refused-sessions)))))
      (finally (fs/delete-tree tmp)))))

(deftest a-removed-projects-refusals-stay-listed
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root        (constantly (str tmp))
                    project/list-projects (fn [] {})]
        (commit! "ws-gone" (refused-unit "a" "/src/nido" [{:command :record-merge :unit "a"}
                                                         {:command :close-workstream}]))
        (is (= [["nido" "ws-gone" "a" "/src/nido"]]
               (mapv (juxt :project :ws :unit :path) (work/refused-sessions)))
            "read off the core's records, not the project registry"))
      (finally (fs/delete-tree tmp)))))
