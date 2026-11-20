(ns nido.coordinator.view.host-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.view.host :as sut]))

(def ^:private gib (* 1024 1024 1024))

(defn- reading
  "A healthy 32-core host: 89 GiB, 58 available, 3 TB volume 81 GB used, load 2.1."
  [& {:as over}]
  (merge {:at-ms     10000
          :cores     32
          :load      {:one 2.1 :five 2.0 :fifteen 1.9}
          :memory    {:total-bytes (* 89 gib) :available-bytes (* 58 gib)
                      :swap-total-bytes (* 8 gib) :swap-free-bytes (* 8 gib)}
          :disk      {:path "/home/u/.nido" :total-bytes (* 3000 gib) :free-bytes (* 2919 gib)}
          :processes []}
         over))

(defn- proc [pid ppid argv & [rss]] {:pid pid :ppid ppid :argv argv :rss-bytes rss})

(deftest kinds-test
  (let [ps [(proc 1 0 ["/sbin/init"])
            (proc 10 1 ["bb" "nido:coordinator:run"] 200)
            (proc 20 1 ["claude" "--dangerously-skip-permissions"] 500)
            (proc 21 20 ["npm exec @upstash/context7-mcp"] 50)
            (proc 22 21 ["sh" "-c" "context7-mcp"] 1)
            (proc 23 22 ["/usr/bin/node" "/x/context7-mcp"] 80)
            (proc 24 20 ["/usr/bin/zsh" "-c" "bb nido:review:design"] 2)
            (proc 30 1 ["/opt/java/bin/java" "-Xmx2g"] 2000)
            (proc 31 1 ["/usr/local/bin/bb" "--config" "x/nrepl.bb.edn" "nrepl"] 100)
            (proc 40 1 ["/usr/lib/postgresql/17/bin/postgres" "-D" "x"] 30)
            (proc 41 40 ["postgres: checkpointer"] 5)
            (proc 50 1 ["claude bg-pty-host"] 90)
            (proc 51 50 ["/home/u/.local/share/claude/versions/2.1.329" "bg-spare"] 90)
            (proc 60 1 ["bash" "-c" "while true; do sleep 1; done"] 3)
            (proc 61 10 ["bb" "nido:attach"] 70)]
        m  (sut/host-model (reading :processes ps) nil 10)
        by (into {} (map (juxt :kind identity)) (:kinds m))]
    (testing "each process except the coordinator in exactly one kind"
      (is (= (dec (count ps)) (reduce + (map :count (:kinds m)))))
      (is (= sut/kinds (mapv :kind (:kinds m)))))
    (testing "own kind by argv, else the nearest classified ancestor's"
      (is (= #{20 24 50 51} (set (map :pid (:processes (by :claude))))))
      (is (= #{21 22 23} (set (map :pid (:processes (by :mcp)))))
          "the sh -c an npm launcher wraps costs the MCP server")
      (is (= #{30 31} (set (map :pid (:processes (by :repl))))))
      (is (= #{40 41} (set (map :pid (:processes (by :postgres)))))))
    (testing "what no kind claims is other — a stray loop, init, a coordinator child"
      (is (= #{1 60 61} (set (map :pid (:processes (by :other)))))))
    (testing "memory per kind is the sum, processes heaviest first"
      (is (= 2100 (:rss-bytes (by :repl))))
      (is (= [30 31] (map :pid (:processes (by :repl))))))
    (testing "the coordinator is its own line"
      (is (= {:pid 10 :rss-bytes 200 :cpu-share nil} (:coordinator m))))))

(deftest process-kind-cycle-test
  (is (= :other (sut/process-kind {1 (proc 1 2 ["sh"]) 2 (proc 2 1 ["sh"])} 1))
      "a parent chain that loops ends at :other rather than hanging"))

(deftest coordinator-share-test
  (let [at (fn [ms cpu] (reading :at-ms ms :processes [(assoc (proc 10 1 ["bb"] 1) :cpu-seconds cpu)]))]
    (testing "CPU seconds between the readings over the wall time between them"
      (is (< (abs (- 0.83 (:cpu-share (:coordinator (sut/host-model (at 15000 104.15) (at 10000 100.0) 10))))) 1e-9)))
    (testing "absent on the first reading — being measured"
      (is (nil? (:cpu-share (:coordinator (sut/host-model (at 15000 104.15) nil 10))))))
    (testing "absent across a coordinator restart: the earlier reading has no such pid"
      (is (nil? (:cpu-share (:coordinator
                             (sut/host-model (at 15000 4.0)
                                             (reading :at-ms 10000 :processes [(assoc (proc 9 1 ["bb"]) :cpu-seconds 100.0)])
                                             10))))))
    (testing "no coordinator, no line"
      (is (nil? (:coordinator (sut/host-model (at 15000 1.0) nil nil)))))))

(deftest thresholds-test
  (let [need (fn [r] (:need (sut/host-model r nil nil)))]
    (testing "a host under every threshold needs nobody"
      (is (nil? (need (reading)))))
    (testing "memory available under 10% of total"
      (is (= :you (:tone (need (reading :memory {:total-bytes 1000 :available-bytes 99
                                                 :swap-total-bytes 0 :swap-free-bytes 0}))))))
    (testing "exactly 10% available is not under it"
      (is (nil? (need (reading :memory {:total-bytes 1000 :available-bytes 100
                                        :swap-total-bytes 0 :swap-free-bytes 0})))))
    (testing "disk free under 10% of the volume"
      (is (= ["disk free under 10%"]
             (:reasons (need (reading :disk {:path "/" :total-bytes 1000 :free-bytes 99}))))))
    (testing "exactly 10% free is not under it"
      (is (nil? (need (reading :disk {:path "/" :total-bytes 1000 :free-bytes 100})))))
    (testing "15-minute load above the cores"
      (is (= :you (:tone (need (reading :load {:one 33.0 :five 33.0 :fifteen 33.0}))))))
    (testing "15-minute load at the cores is not above them"
      (is (nil? (need (reading :load {:one 32.0 :five 32.0 :fifteen 32.0})))))
    (testing "a 1-minute spike with the 15-minute under the cores does not tone the card"
      (is (nil? (need (reading :load {:one 40.0 :five 20.0 :fifteen 10.0})))))
    (testing "every reason that holds is given"
      (is (= 2 (count (:reasons (need (reading :load {:one 40.0 :five 40.0 :fifteen 40.0}
                                               :disk {:path "/" :total-bytes 1000 :free-bytes 1})))))))
    (testing "a partial reading (no /proc) is judged on what it has"
      (is (nil? (need (reading :load nil :memory nil :processes nil))))
      (is (= :you (:tone (need (reading :load nil :memory nil :processes nil
                                        :disk {:path "/" :total-bytes 1000 :free-bytes 1}))))))
    (testing "an unreadable host carries no tone"
      (is (= {:unreadable :host} (sut/host-model nil nil 10))))))

(deftest figures-test
  (let [m (sut/host-model (reading :memory {:total-bytes 100 :available-bytes 60
                                            :swap-total-bytes 10 :swap-free-bytes 9}) nil nil)]
    (is (= {:total-bytes 100 :used-bytes 40 :available-bytes 60 :swap-used-bytes 1} (:memory m)))
    (is (= (* 81 gib) (get-in m [:disk :used-bytes])))))
