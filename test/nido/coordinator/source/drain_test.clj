(ns nido.coordinator.source.drain-test
  "source-drain trusts a counter's output in one place, appends a census only when the reading
   moved, and fires at most one thing per drain per poll: a census failure, or the start of the
   oldest plan whose rested-on slices stand as it asks, within the in-flight cap."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.record.drain :as drain]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.source.drain :as sd]
   [nido.coordinator.source.registry :as sources]
   [nido.coordinator.source.state :as sst]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(def ^:private p :nido)

(def ^:private a-recipe
  {:steps ["s"] :ready-gates ["bb test"] :standing-orders [] :counter {:command "bb countdown"}})

(defn- counting [n] (fn [_dir _cmd] {:remaining n :groups []}))

(defn- with-recipe []
  (let [d (drain/start-drain! p {:established-by {:commit "abcdef1"} :exit "x"})]
    (drain/approve-recipe! p d (drain/propose-recipe! p d {:reason "r" :recipe a-recipe}))
    d))

(deftest a-counter-is-read-once-and-strictly
  (is (= {:remaining 3 :groups [{:group "quiz" :n 3}]}
         (sd/read-counter "{:remaining 3 :groups [{:group \"quiz\" :n 3}]}")))
  (is (= {:remaining 0 :groups []} (sd/read-counter "{:remaining 0}")))
  (is (= {:failed :unreadable} (sd/read-counter "{:remaining")))
  (is (= {:failed :invalid} (sd/read-counter "{:remaining -1}")))
  (is (= {:failed :invalid} (sd/read-counter "42"))))

(deftest a-counter-that-exits-non-zero-failed
  (is (= :counter-failed (:failed (sd/run-counter (str (fs/temp-dir)) "exit 3"))))
  (is (= {:remaining 2 :groups []}
         (sd/run-counter (str (fs/temp-dir)) "echo '{:remaining 2}'"))))

(deftest the-counter-run-is-the-current-recipes
  (with-tmp
    (fn [_]
      (let [d   (with-recipe)
            ran (atom nil)]
        (sd/take-census! p d (fn [_dir cmd] (reset! ran cmd) {:remaining 1 :groups []}))
        (is (= "bb countdown" @ran))))))

(deftest a-census-is-appended-only-when-it-moves
  (with-tmp
    (fn [_]
      (let [d (with-recipe)]
        (is (contains? (sd/take-census! p d (counting 4)) :census))
        (is (contains? (sd/take-census! p d (counting 4)) :unchanged))
        (is (contains? (sd/take-census! p d (counting 3)) :census))
        (is (= [4 3] (map :remaining (drain/censuses (ws/read-ws p d)))))
        (testing "a failed reading appends nothing"
          (is (= :invalid (:failed (sd/take-census! p d (fn [_ _] {:failed :invalid})))))
          (is (= 2 (count (drain/censuses (ws/read-ws p d))))))))))

(deftest a-poll-fires-one-start-within-the-cap
  (with-tmp
    (fn [_]
      (let [d  (with-recipe)
            _  (sd/take-census! p d (counting 4))
            c  (:seq (drain/newest-census (ws/read-ws p d)))
            a  (drain/plan-slice! p d {:sites ["a"] :rests-on [] :census c})
            b  (drain/plan-slice! p d {:sites ["b"] :rests-on [a] :start :after-landing :census c})
            fired (atom [])
            poll! #(sd/poll-once! {:type :drain :project p :max-in-flight 1} (partial swap! fired conj) (counting 4))]
        (poll!)
        (is (= [{:what :start-slice :plan a}] (map #(select-keys % [:what :plan]) @fired)))
        (let [s (drain/start-slice! p d a :nido)]
          (reset! fired [])
          (poll!)
          (is (empty? @fired) "b rests on a, which has not landed — and the cap is full")
          (ws/append-entry! p s {:kind :closed} (pr-str {:format :closed :outcome :done :note "landed"}))
          (poll!)
          (is (= [b] (map :plan @fired)) "a landed: b may start"))))))

(deftest a-failed-census-is-fired-not-recorded
  (with-tmp
    (fn [_]
      (let [d (with-recipe)
            fired (atom [])]
        (sd/poll-once! {:type :drain :project p} (partial swap! fired conj) (fn [_ _] {:failed :unreadable}))
        (is (= [{:what :census-failed :cause :unreadable :drain d}]
               (map #(select-keys % [:what :cause :drain]) @fired)))
        (is (empty? (drain/censuses (ws/read-ws p d))))))))

(deftest a-drain-without-a-recipe-fires-nothing
  (with-tmp
    (fn [_]
      (let [_ (drain/start-drain! p {:established-by {:commit "abcdef1"} :exit "x"})
            fired (atom [])]
        (sd/poll-once! {:type :drain :project p} (partial swap! fired conj) (counting 1))
        (is (empty? @fired))))))

(deftest a-poll-never-blocks-the-tick-on-a-counter
  (with-tmp
    (fn [_]
      (let [_       (with-recipe)
            gate    (promise)
            started (atom 0)
            run     (fn [_ _] (swap! started inc) @gate {:remaining 1 :groups []})
            inst    (#'sd/start-instance! {:type :drain :project p :poll "2m"} (fn [_]) run)
            t0      (System/currentTimeMillis)]
        (future (Thread/sleep 3000) (deliver gate :go))
        ((:poll! inst))
        (is (< (- (System/currentTimeMillis) t0) 2000) "poll! returned while the counter still ran")
        ((:poll! inst))
        (run! #(deref % 10000 nil) (vals @@#'sd/!running))
        (is (= 1 @started) "a poll still in progress starts no second one")
        (is (= [1] (map :remaining (drain/censuses (ws/read-ws p (first (sd/open-drains p))))))
            "the background poll appended its reading")))))

(deftest a-drain-source-keeps-its-state-apart-from-other-source-types
  (with-tmp
    (fn [_]
      (let [_    (with-recipe)
            cfg  {:type :drain :project p :poll "2m"}
            inst (#'sd/start-instance! cfg (fn [_]) (counting 1))]
        ((:poll! inst))
        (run! #(deref % 10000 nil) (vals @@#'sd/!running))
        (is (nil? (sst/read-state (sources/config-hash {:type :improvement :project p :poll "2m"})))
            "an improvement source of the same project and poll does not find the drain's state")
        (is (= :drain (:type (sst/read-state (#'sd/instance-key cfg)))))))))
