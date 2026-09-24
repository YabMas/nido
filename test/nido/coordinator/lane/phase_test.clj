(ns nido.coordinator.lane.phase-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.lane.phase :as lphase]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f))
         (finally (fs/delete-tree tmp)))))

(def ^:private a-baseline
  {:format :baseline :strata [] :intent {:seq 1}
   :area "a" :bounded-by "b" :shape "s"
   :model {:elements [{:id "m" :sort :module :hides "h" :interface "i"}]
           :claims [{:id "c1" :about ["m"] :statement "st" :falsified-by "f" :evidence {:by :round}}]}
   :read ["src/a.clj"]})

(def ^:private a-phased-design
  {:format :design :strata [] :summary "s" :shape "sh"
   :model {:elements [{:id "m" :sort :module}]
           :claims [{:id "c" :about ["m"] :statement "st" :falsified-by "f" :evidence {:by :round}}]}
   :holds {"c" :on-completion}
   :standing {:relation :conforms} :intent {:seq 1}
   :baseline {:seq 2 :relation :within} :effort :S
   :phases [{:claim "both paths run" :habitable "h" :exit {:kind :soak :criterion "a week"}
             :undo {:how :revert :by "r"}}
            {:claim "the old path is removed" :habitable "h" :exit {:kind :completion :criterion "done"}
             :undo {:how :none :why "gone"}}]})

(defn- between-phases []
  (let [id   (:id (ws/create! :brian {:stage :in-progress :external-refs []}))
        add! #(ws/append-entry! :brian id {:kind %1} (pr-str %2))]
    (add! :intent {:format :intent :goal "g" :done-when ["d"]})
    (add! :baseline a-baseline)
    (add! :design a-phased-design)
    (add! :merged {:format :merged :pr "o/r#1" :url "u" :title "t" :design {:seq 3}})
    (ws/close! :brian id :between-phases 3)
    id))

(deftest advance-opens-the-next-phase-of-the-plan
  (with-tmp
    (fn []
      (let [id (between-phases)]
        (is (= {:opened "the old path is removed" :phase 2 :of 2}
               (lphase/advance! :brian id {:evidence "error rate flat for a week"})))
        (let [w (ws/read-ws :brian id)]
          (is (nil? (:closed w)))
          (is (= {:format :phase-gate :design {:seq 3} :opens "the old path is removed"
                  :evidence "error rate flat for a week"}
                 (dissoc (ws/latest-entry :brian id :phase-gate) :seq :at))))))))

(deftest advance-refuses-blank-evidence-and-a-workstream-not-between-phases
  (with-tmp
    (fn []
      (let [id (between-phases)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"with its evidence"
                              (lphase/advance! :brian id {:evidence " "})))
        (ws/reopen! :brian id :in-progress)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not waiting on a gate — it is open"
                              (lphase/advance! :brian id {:evidence "x"})))
        (is (nil? (ws/latest-entry :brian id :phase-gate)))))))

(deftest advance-opens-the-next-phase-of-the-plan-its-close-names
  ;; The plan waiting on the gate is the one the close named; no design can be
  ;; written over it while it waits, so the gate shown is the gate that opens.
  (with-tmp
    (fn []
      (let [id (between-phases)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"waiting between phases"
                              (ws/append-entry! :brian id {:kind :design}
                                                (pr-str (dissoc a-phased-design :phases :holds)))))
        (is (= {:opened "the old path is removed" :phase 2 :of 2}
               (lphase/advance! :brian id {:evidence "error rate flat for a week"})))
        (is (= {:seq 3} (:design (ws/latest-entry :brian id :phase-gate))))))))

(deftest advance-opens-a-phase-whose-merged-was-never-written
  (with-tmp
    (fn []
      (let [id   (:id (ws/create! :brian {:stage :in-progress :external-refs []}))
            add! #(ws/append-entry! :brian id {:kind %1} (pr-str %2))]
        (add! :intent {:format :intent :goal "g" :done-when ["d"]})
        (add! :baseline a-baseline)
        (add! :design a-phased-design)
        (ws/close! :brian id :between-phases 3)
        (is (= 2 (:phase (lphase/advance! :brian id {:evidence "a quiet week"}))))))))
