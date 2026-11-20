(ns nido.ui.host-views-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.view.host :as host-view]
   [nido.ui.views :as views]))

(def ^:private gib (* 1024 1024 1024))

(def ^:private reading
  {:at-ms 10000 :cores 32
   :load {:one 2.1 :five 2.0 :fifteen 1.9}
   :memory {:total-bytes (* 89 gib) :available-bytes (* 58 gib)
            :swap-total-bytes (* 8 gib) :swap-free-bytes (* 8 gib)}
   :disk {:path "/home/u/.nido" :total-bytes (* 3000 gib) :free-bytes (* 2919 gib)}
   :processes [{:pid 10 :ppid 1 :argv ["bb" "nido:coordinator:run"] :rss-bytes 100 :cpu-seconds 100.0}
               {:pid 20 :ppid 1 :argv ["claude"] :rss-bytes (* 2 gib)}
               {:pid 21 :ppid 20 :argv ["npm exec chiasmus"] :rss-bytes gib}]})

(defn- home
  "The Host card alone, out of the home's grid — the other cards carry tones of their own."
  [host]
  (let [grid (str (views/operations-home-fragment (views/operations-home-cards [] nil [] [] {} host)))]
    (re-find #"<a class=\"ops-card\" href=\"/operations/host\">.*?</a>" grid)))

(deftest host-card-shows-the-figures
  (let [m    (host-view/host-model (assoc reading :at-ms 15000
                                          :processes (assoc-in (:processes reading) [0 :cpu-seconds] 104.15))
                                   reading 10)
        card (home m)]
    (is (str/includes? card "href=\"/operations/host\"") "the card opens its page")
    (is (str/includes? card "load 2.1 / 32 cores"))
    (is (str/includes? card "58.0 GB available"))
    (is (not (str/includes? card "swap")) "no swap line when none is in use")
    (is (str/includes? card "disk 81.0 GB / 2.9 TB"))
    (is (str/includes? card "coordinator 83% of a core"))
    (is (str/includes? card "claude 1 (2.0 GB)"))
    (is (str/includes? card "mcp 1 (1.0 GB)"))
    (is (not (str/includes? card "ops-you")) "a healthy host asks nothing")))

(deftest host-card-tones-past-a-threshold
  (let [card (home (host-view/host-model (assoc reading :load {:one 40.0 :five 40.0 :fifteen 40.0}) nil nil))]
    (is (str/includes? card "ops-you"))
    (is (str/includes? card "15-minute load 40.0 above 32 cores"))))

(deftest host-card-degrades
  (testing "unreadable: says so, toned nothing"
    (let [card (home {:unreadable :host})]
      (is (str/includes? card "Host"))
      (is (str/includes? card "could not be read"))))
  (testing "no /proc: cores and disk, and why the rest is missing"
    (let [card (home (host-view/host-model (assoc reading :load nil :memory nil :processes nil) nil 10))]
      (is (str/includes? card "cannot be read on this host"))
      (is (str/includes? card "disk 81.0 GB"))
      (is (str/includes? card "coordinator CPU: measuring…")))))

(deftest host-page-lists-each-kinds-processes
  (let [page (views/host-fragment (host-view/host-model reading nil 10))]
    (is (str/includes? page "Claude sessions — 1"))
    (is (str/includes? page "MCP servers (node/npm) — 1"))
    (is (str/includes? page "npm exec chiasmus"))
    (is (str/includes? page "coordinator pid 10"))
    (is (< (str/index-of page "Claude sessions") (str/index-of page "MCP servers")))))
