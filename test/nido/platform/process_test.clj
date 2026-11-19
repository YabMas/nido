(ns nido.platform.process-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.process :as sut]))

;; ---------------------------------------------------------------------------
;; find-available-port — browser-blocked ports
;;
;; Regression: session brian/vp-srf-ch was allocated app port 4045 (lockd).
;; The app bound it, listened on it, and answered curl with a 200, so
;; `session:status` reported "app alive / listening" and every nido surface
;; called the session healthy — while Chrome refused it outright with
;; ERR_UNSAFE_PORT. It presents as a dead session that is provably not dead.
;; ---------------------------------------------------------------------------

(defn- all-free
  "A port-free? that says yes to everything, so these tests exercise only the
   blocked-port rule and never depend on what is really listening."
  [_port]
  true)

(deftest find-available-port-skips-browser-blocked-ports-test
  (testing "walks past a blocked port even when it is free"
    (with-redefs [sut/port-free? all-free]
      (is (= 4046 (sut/find-available-port 4045 50))
          "4045 is lockd — free, bindable, and unopenable by a browser")))

  (testing "walks past a run of consecutive blocked ports"
    (with-redefs [sut/port-free? all-free]
      (is (= 5062 (sut/find-available-port 5060 50))
          "5060 sip and 5061 sips are both blocked")))

  (testing "returns an unblocked free port unchanged"
    (with-redefs [sut/port-free? all-free]
      (is (= 4100 (sut/find-available-port 4100 50)))))

  (testing "blocked and occupied compose"
    ;; 4045 blocked, 4046 occupied -> 4047.
    (with-redefs [sut/port-free? (fn [p] (not= p 4046))]
      (is (= 4047 (sut/find-available-port 4045 50)))))

  (testing "still throws when nothing is available within max-attempts"
    (with-redefs [sut/port-free? (fn [_] false)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (sut/find-available-port 4100 3))))))

(deftest browser-blocked-ports-covers-nido-ranges-test
  (testing "every browser-blocked port inside the app range is listed"
    ;; App ports are drawn from [3100 5100) (see engine/pre-allocate-ports).
    ;; These are the members of Chromium's kRestrictedPorts that fall in it;
    ;; dropping any one of them reopens the trap above.
    (is (= [3659 4045 4190 4444 5060 5061]
           (sort (filter #(and (>= % 3100) (< % 5100))
                         sut/browser-blocked-ports)))))

  (testing "the X11 and irc ports inside the pg range are listed"
    ;; Per-session pg ports come from [5500 7500). Nothing points a browser at
    ;; postgres, but the allocator is shared and walks upward across ranges.
    (is (every? sut/browser-blocked-ports [6000 6566 6665 6697]))))

(deftest host-boot-keeps-its-identity-when-the-clock-moves-the-boot-time
  ;; macOS moves kern.boottime when it corrects the clock; on 2026-10-27 one boot read as
  ;; ...351 then ...352, and a daemon restart took it for a new boot.
  (let [sec (atom 1792861351)
        out (fn [_ & args]
              {:out (case (last args)
                      "kern.boottime"        (str "{ sec = " @sec ", usec = 971631 } Sat Oct 24 19:02:31 2026")
                      "kern.bootsessionuuid" "7C20755B-EE3A-4A46-B5BB-FA3BD0F0FFA1\n")})]
    (with-redefs [babashka.process/shell out
                  fs/exists?             (constantly false)] ; a host without /proc
      (let [a (sut/host-boot)]
        (swap! sec inc)
        (let [b (sut/host-boot)]
          (is (= "7C20755B-EE3A-4A46-B5BB-FA3BD0F0FFA1" (:id a) (:id b)))
          (is (= "2026-10-24T17:02:31Z" (:began-at a))))))
    (testing "an unreadable boot session is an unknown boot, not one named by its time"
      (with-redefs [fs/exists?             (constantly false)
                    babashka.process/shell (fn [_ & args]
                                             {:out (if (= "kern.boottime" (last args))
                                                     "{ sec = 1792861351, usec = 0 }" "")})]
        (is (nil? (sut/host-boot)))))))

(deftest on-linux-the-boot-and-load-come-from-proc
  (let [files {"/proc/sys/kernel/random/boot_id" "a8233b50-fb6b-48ed-8221-ab61a7310362\n"
               "/proc/stat"                      "cpu  1 2 3\nbtime 1795023206\nprocesses 9\n"
               "/proc/loadavg"                   "0.52 0.58 0.59 1/563 98271\n"}]
    (with-redefs [fs/exists?         (fn [p] (contains? files (str p)))
                  fs/read-all-bytes  (fn [p] (.getBytes ^String (get files (str p)) "UTF-8"))
                  babashka.process/shell (fn [& _] (throw (ex-info "no sysctl on Linux" {})))]
      (is (= {:id "a8233b50-fb6b-48ed-8221-ab61a7310362" :began-at "2026-11-18T17:33:26Z"}
             (sut/host-boot)))
      (is (= 0.52 (sut/load-average))))))

;; ---------------------------------------------------------------------------
;; wait-within! — a wall clock on a launched process

(deftest a-process-past-its-budget-is-killed-and-says-so
  (let [proc (.start (ProcessBuilder. ["sleep" "30"]))
        t0   (System/currentTimeMillis)
        out  (sut/wait-within! proc 200)]
    (is (:timed-out? out))
    (is (not (.isAlive proc)))
    (is (< (- (System/currentTimeMillis) t0) 15000) "killed on SIGTERM, not waited out")))

(deftest a-process-inside-its-budget-answers-its-own-exit
  (is (= {:exit 3 :timed-out? false}
         (sut/wait-within! (.start (ProcessBuilder. ["sh" "-c" "exit 3"])) 10000)))
  (is (= {:exit 0 :timed-out? false}
         (sut/wait-within! (.start (ProcessBuilder. ["true"])) nil))
      "nil is no limit"))

(deftest a-pid-s-start-tells-its-process-from-a-later-one
  (let [me (.pid (java.lang.ProcessHandle/current))]
    (testing "a running process answers when it started"
      (is (<= (sut/process-started me) (System/currentTimeMillis))))
    (testing "a pid nothing runs under is gone, not unknown"
      (let [p   (.start (ProcessBuilder. ["true"]))
            pid (.pid p)]
        (.waitFor p)
        (is (= :gone (sut/process-started pid)))))))
