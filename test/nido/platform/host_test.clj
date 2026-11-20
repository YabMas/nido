(ns nido.platform.host-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.host :as sut]))

(defn- status [{:keys [name uid ppid rss]}]
  (str "Name:\t" name "\nState:\tS (sleeping)\nPPid:\t" ppid "\nUid:\t" uid "\t" uid "\t" uid "\t" uid "\n"
       (when rss (str "VmRSS:\t" rss " kB\n"))))

(defn- fixture!
  "A /proc-shaped directory: the reader is uid 1000; pid 10 and 11 are its, 12 is root's."
  []
  (let [root (str (fs/create-temp-dir))
        put  (fn [rel s] (let [p (fs/path root rel)]
                           (fs/create-dirs (fs/parent p))
                           (spit (str p) s)))]
    (put "self/status" (status {:name "bb" :uid 1000 :ppid 1}))
    (put "loadavg" "2.10 1.50 0.90 3/400 12345\n")
    (put "meminfo" (str "MemTotal:       93000000 kB\nMemFree:  1000 kB\n"
                        "MemAvailable:   60000000 kB\nSwapTotal:  8000000 kB\nSwapFree:   7999000 kB\n"))
    (put "10/status" (status {:name "bb" :uid 1000 :ppid 1 :rss 200000}))
    (put "10/cmdline" "/usr/local/bin/bb\u0000nido:coordinator:run\u0000")
    ;; comm with a space and a paren, to prove fields are counted after the LAST `)`
    (put "10/stat" "10 (b b) x) S 1 10 10 0 -1 4194560 0 0 0 0 1200 300 0 0 20 0 1 0 1 0 0\n")
    (put "11/status" (status {:name "claude" :uid 1000 :ppid 10 :rss 1000}))
    (put "11/cmdline" "claude\u0000--resume\u0000")
    (put "12/status" (status {:name "sshd" :uid 0 :ppid 1 :rss 5000}))
    (put "12/cmdline" "sshd\u0000")
    (put "13/status" (status {:name "kworker" :uid 1000 :ppid 2}))
    (put "13/cmdline" "")
    root))

(deftest read-host-reads-proc-test
  (let [root (fixture!)
        r    (sut/read-host {:path (str root) :cpu-pids #{10} :proc-root root})]
    (testing "load and memory are parsed into numbers and bytes"
      (is (= {:one 2.1 :five 1.5 :fifteen 0.9} (:load r)))
      (is (= {:total-bytes (* 93000000 1024) :available-bytes (* 60000000 1024)
              :swap-total-bytes (* 8000000 1024) :swap-free-bytes (* 7999000 1024)}
             (:memory r))))
    (testing "only the reading user's processes, with argv split on NUL"
      (is (= [10 11 13] (map :pid (:processes r))))
      (is (= ["/usr/local/bin/bb" "nido:coordinator:run"] (:argv (first (:processes r)))))
      (is (= (* 200000 1024) (:rss-bytes (first (:processes r))))))
    (testing "CPU seconds only for the pids asked, counted after the last paren"
      (is (= 15.0 (:cpu-seconds (first (:processes r)))))
      (is (not (contains? (second (:processes r)) :cpu-seconds))))
    (testing "an empty cmdline falls back to the status Name, and a missing VmRSS is nil"
      (is (= ["kworker"] (:argv (nth (:processes r) 2))))
      (is (nil? (:rss-bytes (nth (:processes r) 2)))))
    (testing "cores and disk come from the JVM"
      (is (pos-int? (:cores r)))
      (is (pos? (get-in r [:disk :total-bytes]))))))

(deftest read-host-without-proc-test
  (let [r (sut/read-host {:path (str (fs/temp-dir)) :proc-root "/no/such/proc"})]
    (testing "a host without /proc reads load, memory and processes as nil — and still its cores and disk"
      (is (nil? (:load r)))
      (is (nil? (:memory r)))
      (is (nil? (:processes r)))
      (is (pos-int? (:cores r)))
      (is (some? (:disk r))))))

(deftest read-host-never-throws-test
  (testing "garbage in /proc and a missing volume read as nil, never an exception"
    (let [root (str (fs/create-temp-dir))]
      (spit (str (fs/path root "loadavg")) "nonsense")
      (fs/create-dirs (fs/path root "self"))
      (spit (str (fs/path root "self" "status")) "Uid:\t1000\n")
      (spit (str (fs/path root "meminfo")) "")
      (let [r (sut/read-host {:path "/no/such/volume" :proc-root root})]
        (is (nil? (:load r)))
        (is (nil? (:memory r)))
        (is (nil? (:disk r)))
        (is (= [] (:processes r)))))))

(deftest read-host-on-this-machine-test
  (when (fs/exists? "/proc/self/status")
    (let [r (sut/read-host {:path (str (fs/home)) :cpu-pids #{(.pid (java.lang.ProcessHandle/current))}})]
      (is (some? (:load r)))
      (is (some #(and (= (.pid (java.lang.ProcessHandle/current)) (:pid %)) (pos? (:cpu-seconds % 0)))
                (:processes r))))))
