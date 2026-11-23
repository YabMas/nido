(ns nido.core.cutover-test
  "The cut-over commits the hand-over plan once, writing nothing under the legacy ledgers; the core
   will not start over a home the cut-over has not run on. The ledgers themselves are imported by
   the ledger import once the core serves."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [nido.core.cutover :as cutover]
            [nido.core.release :as release]
            [nido.core.store :as store])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tmp [] (str (Files/createTempDirectory "nido-cutover-" (make-array FileAttribute 0))))

(def ^:private plan
  [{:ws "brian/ws-1"
    :commands [{:command :open-unit :unit "ws-1" :project "brian" :goal "Fix it" :session-cwd "/wt/s1"}
               {:command :record-unknown :unit "ws-1" :key "cutover/s1"}
               {:command :hand-over :summary "Handed over: Fix it."}]}
   {:ws "brian/ws-2" :commands [{:command :hand-over :summary "Handed over: penned."}]}])

(deftest the-cut-over-hands-over-once
  (let [home (tmp) ps (release/paths home)
        entry (io/file home "projects/brian/workstreams/ws-1/entries/0001.edn")]
    (io/make-parents entry)
    (spit entry "{:kind :intent}")
    (testing "before the cut-over the core may not start"
      (is (= :not-cut-over (release/alongside ps))))
    (io/make-parents (io/file (:db ps)))
    (let [r (cutover/apply-plan! ps plan)]
      (is (= 2 (:handed r)))
      (is (= [] (:refused r))))
    (is (= "{:kind :intent}" (slurp entry)) "the legacy file is left as it was")
    (is (nil? (release/alongside ps)))
    (let [s (store/open-reader (:db ps))]
      (try
        (let [w1 (store/state s "brian/ws-1")]
          (is (= {:status :ready :cwd "/wt/s1"} (select-keys (get-in w1 [:units "ws-1" :session]) [:status :cwd])))
          (is (contains? (get-in w1 [:units "ws-1" :unknowns]) "cutover/s1"))
          (is (empty? (:turns w1)) "no turn is started"))
        (is (empty? (:units (store/state s "brian/ws-2"))) "one with no session opens no unit")
        (finally (store/close s))))
    (testing "a second run is answered, not repeated"
      (is (= [] (:refused (cutover/apply-plan! ps plan))))
      (let [s (store/open-reader (:db ps))]
        (try (is (= 1 (count (filter #(= :handed-over (:event %)) (store/events s "brian/ws-1")))))
             (finally (store/close s)))))))
