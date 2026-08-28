(ns nido.ui.views-fleet-test
  "The fleet card in the ops panel.

   What is pinned here is mostly what the card must NOT do: not read as an
   empty machine when its probe failed, and not grow a per-session memory
   column that `work/all-machine-rows` already owns on two other surfaces."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [hiccup2.core :as h]
   [nido.ui.views :as views]))

(def ^:private gb (* 1024 1024 1024))

(defn- render [fleet] (str (h/html (views/fleet-card fleet))))

(defn- panel [fleet]
  (views/ops-panel-fragment {:daemon {:state :up} :halt nil :breakers [] :triggers {}
                             :fleet fleet}))

(def ^:private roomy
  {:sessions 10 :in-use (* 25 gb) :machine (* 48 gb) :over? false :candidates []})

(def ^:private tight
  {:sessions 14 :in-use (* 36 gb) :machine (* 48 gb) :over? true
   :candidates [{:project "brian" :session "impl-fu-15" :bytes (long (* 2.6 gb))
                 :idle-ms (* 71 3600000)}
                {:project "brian" :session "impl-br-5559" :bytes (long (* 1.9 gb))
                 :idle-ms (* 47 3600000)}]})

(deftest shows-the-aggregate-against-the-machine
  (let [out (render roomy)]
    (is (str/includes? out "25.0 GB / 48.0 GB") "what the machine holds, against what it has")
    (is (str/includes? out "52%"))
    (is (str/includes? out "10 live sessions"))))

(deftest over-budget-is-visible-without-reading-the-numbers
  (is (str/includes? (render tight) "fleet-over")
      "the card carries the class the amber bar and percentage hang off")
  (is (not (str/includes? (render roomy) "fleet-over"))
      "and a machine with room does not"))

(deftest candidates-are-named-with-what-they-hold
  (let [out (render tight)]
    (is (str/includes? out "brian/impl-fu-15"))
    (is (str/includes? out "2.6 GB"))
    (is (str/includes? out "2d") "71h reads as days")
    (is (str/includes? out "47h") "under two days it stays in hours")))

(deftest a-quiet-fleet-says-so
  (let [out (render roomy)]
    (is (str/includes? out "Nothing idle"))
    (is (not (str/includes? out "Nothing driving these")))))

(deftest a-long-candidate-list-is-capped
  (let [many (assoc tight :candidates
                    (for [i (range 7)]
                      {:project "brian" :session (str "s" i) :bytes gb :idle-ms (* 50 3600000)}))
        out  (render many)]
    (is (str/includes? out "+4 more") "three shown, the rest counted")
    (is (= 3 (count (re-seq #"fleet-cand" out))) "the panel is 340px — it does not scroll a fleet")))

(deftest a-failed-probe-does-not-read-as-an-empty-machine
  ;; The dangerous rendering: nil must not come out as "0 sessions, 0 bytes",
  ;; which is indistinguishable from a machine with nothing running on it.
  (let [out (render nil)]
    (is (str/includes? out "unavailable"))
    (is (not (str/includes? out "0.0 B")))
    (is (not (str/includes? out "live session")))))

(deftest the-card-carries-no-per-session-memory-column
  ;; `work/all-machine-rows` owns per-session RSS and already renders it on the
  ;; workstream pane and the winding-down band. A third copy here would drift.
  ;; Only sessions nothing is DRIVING carry a size, and that size is the reason
  ;; they are listed at all.
  (let [out (render (assoc tight :candidates []))]
    (is (not (str/includes? out "GB ·"))
        "no size appears once there is nothing to reclaim")))

(deftest a-broken-probe-never-takes-the-emergency-levers-with-it
  ;; The card shares a panel with halt/resume and breaker-clear — the controls
  ;; you reach for when something is already wrong.
  (let [out (panel nil)]
    (is (str/includes? out "unavailable"))
    (is (str/includes? out "Halt") "halt survives a fleet probe that failed")
    (is (str/includes? out "Breakers"))))

(deftest a-blind-probe-does-not-claim-the-fleet-is-busy
  ;; No candidates has two causes — nothing is idle, or nothing could be
  ;; measured — and only one of them is a fact about the fleet.
  (let [out (render (assoc roomy :candidates [] :signals-ok? false))]
    (is (str/includes? out "Idle check unavailable"))
    (is (not (str/includes? out "Nothing idle")))
    (is (str/includes? out "25.0 GB / 48.0 GB")
        "the aggregate still stands — it does not depend on the activity probes"))
  (let [out (render (assoc roomy :candidates [] :signals-ok? true))]
    (is (str/includes? out "Nothing idle"))))
