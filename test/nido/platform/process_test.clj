(ns nido.platform.process-test
  (:require
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
