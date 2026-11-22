(ns nido.core.crash-test
  "kill -9 drill: a daemon killed mid-commit loses nothing it acknowledged, keeps a store equal to
   the fold of its ledger, and on restart answers a resent key with its original outcome."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [nido.core.store :as store]
            [nido.core.writer :as writer])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- run-and-kill!
  "Run the drill child against `db` and SIGKILL it once it has acknowledged `kill-after`
   commands. Answers the keys it acknowledged."
  [db kill-after]
  (let [java (str (System/getProperty "java.home") "/bin/java")
        pb (doto (ProcessBuilder. ^java.util.List
                                  [java "--enable-native-access=ALL-UNNAMED"
                                   "-cp" (System/getProperty "java.class.path")
                                   "clojure.main" "-m" "nido.core.crash-drill" db "8" "500"])
             (.redirectErrorStream true))
        p (.start pb)
        acked (with-open [r (io/reader (.getInputStream p))]
                (loop [acc []]
                  (let [line (.readLine ^java.io.BufferedReader r)]
                    (cond
                      (nil? line) acc
                      (>= (count acc) kill-after) (do (.destroyForcibly p) acc)
                      (str/starts-with? line "ack ") (recur (conj acc (subs line 4)))
                      :else (recur acc)))))]
    (.waitFor p)
    acked))

(deftest a-killed-daemon-loses-nothing-it-acknowledged
  (let [db (str (Files/createTempDirectory "nido-crash-" (make-array FileAttribute 0)) "/core.db")]
    (doseq [round (range 3)]
      (let [acked (run-and-kill! db 300)
            s (store/open db)]
        (try
          (is (= 300 (count acked)) (str "round " round ": the child acknowledged before it was killed"))
          (is (every? #(store/command-outcome s %) acked) "every acknowledged command is committed")
          (is (empty? (store/verify s)) "the stored state is the fold of the ledger")
          (let [events-before (reduce + (map #(count (store/events s %)) (store/workstreams s)))
                w (writer/start s {})]
            (try
              (doseq [k (take 50 acked)]
                (is (= (store/command-outcome s k)
                       (deref (writer/submit! w {:key k :ws "ws0" :command {:command :open-unit :unit k :project "p" :goal "g"}}) 5000 nil))
                    "a resent key is answered with its original outcome"))
              (is (= events-before (reduce + (map #(count (store/events s %)) (store/workstreams s))))
                  "and decided nothing again")
              (finally (writer/stop! w))))
          (finally (store/close s)))))))
