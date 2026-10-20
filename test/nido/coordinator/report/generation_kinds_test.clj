(ns nido.coordinator.report.generation-kinds-test
  "The ledger kinds a generation is opened and argued with: each validates as written, titles and
   renders; the writer is stamped in one of the shapes the kind admits; and the mark on an element
   is the only thing the ledger is told about what makes a generation."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [malli.core :as m]
            [nido.coordinator.report :as report]))

(def ^:private seat      {:as :seat :seat "canvas.strata/ledger" :session "ws-1--ledger"})
(def ^:private conductor {:as :conductor :session "ws-1--conductor"})

(def ^:private samples
  {:recut      {:format :recut :by {:as :person}
                :ids ["canvas.strata/ledger" "canvas.strata/seats"]
                :why "the ledger and status levels argue every round; they are one level"
                :tensions [{:seq 12}]}
   :tension    {:format :tension :by seat :to "canvas.strata/record-status"
                :about ["governing-design-one-reading"]
                :statement "status reads the newest design, not the span's"}
   :accord     {:format :accord :by conductor :tension {:seq 12}
                :terms "status takes the governing design from the span"}
   :withdrawal {:format :withdrawal :by seat :tension {:seq 12}}
   :generation {:format :generation :recut {:seq 14}
                :words "Merge ledger and status; they are one level."
                :ids ["canvas.strata/ledger" "canvas.strata/seats"]
                :base-rev "abc123"
                :falsified [{:claim "seats-derived" :by "entry 13's verdict"}]
                :tensions [{:by seat :to :conductor :about ["seats-derived"]
                            :statement "two seats own one secret"}]}})

(deftest every-generation-kind-validates-titles-and-renders
  (doseq [[kind r] samples]
    (testing (name kind)
      (is (= r (report/validate-event kind r)))
      (is (not (str/blank? (report/report-title r))))
      (is (not (str/blank? (report/report->markdown r)))))))

(deftest a-recut-may-come-from-anyone-a-tension-only-from-the-ensemble
  (testing "a re-cut by any session or a person"
    (doseq [by [seat conductor {:as :session :session "other"} {:as :person}]]
      (is (report/validate-event :recut (assoc (:recut samples) :by by)))))
  (testing "a tension, an accord and a withdrawal only by a seat or the conductor"
    (doseq [kind [:tension :accord :withdrawal]
            by   [{:as :session :session "other"} {:as :person}]]
      (is (thrown? Exception (report/validate-event kind (assoc (kind samples) :by by)))
          (str (name kind) " by " (name (:as by))))))
  (testing "a seat writer names its seat and session"
    (is (thrown? Exception (report/validate-event :tension
                                                  (assoc (:tension samples) :by (dissoc seat :seat)))))))

(deftest a-tension-is-addressed-to-a-seat-or-the-conductor
  (is (report/validate-event :tension (assoc (:tension samples) :to :conductor)))
  (is (thrown? Exception (report/validate-event :tension (assoc (:tension samples) :to :someone))))
  (is (thrown? Exception (report/validate-event :tension (assoc (:tension samples) :about [])))))

(deftest a-generation-carries-the-persons-words-ids-and-base
  (doseq [k [:words :ids :base-rev :recut]]
    (is (thrown? Exception (report/validate-event :generation (dissoc (:generation samples) k)))
        (str "without " (name k))))
  (is (thrown? Exception (report/validate-event :generation (assoc (:generation samples) :ids [])))))

(deftest a-recut-names-the-ids-it-proposes
  (is (thrown? Exception (report/validate-event :recut (assoc (:recut samples) :ids [])))))

(deftest an-element-is-marked-or-says-nothing
  (let [element {:id "canvas.strata/ledger" :sort :stratum}
        design  (fn [e] {:elements [e]
                         :claims [{:id "c" :about ["canvas.strata/ledger"] :statement "s"
                                   :falsified-by "f" :evidence {:by :round}}]})]
    (is (m/validate report/Model (design (assoc element :defines-generation true))))
    (is (not (m/validate report/Model (design (assoc element :defines-generation false))))
        "there is no false mark: an unmarked element carries none")))

(deftest a-seat-layer-is-counted-within-its-seat
  (let [r {:format :layer-completed :design {:seq 9} :layer 1 :of 2 :seat "canvas.strata/ledger"}]
    (is (= r (report/validate-event :layer-completed r)))
    (is (str/includes? (report/report-title r) "canvas.strata/ledger"))
    (is (thrown? Exception (report/validate-event :layer-completed (assoc r :seat ""))))))
