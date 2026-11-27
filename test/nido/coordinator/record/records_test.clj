(ns nido.coordinator.record.records-test
  "What the history read asks of the core: the archive once per process and core, between
   writes; the runs once per request read, and never across two."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.proposal :as proposal]
   [nido.coordinator.record.records :as records]
   [nido.coordinator.source.start-failures :as start-failures]
   [nido.coordinator.work :as work]
   [nido.core.client :as client]
   [nido.platform.core :as core]
   [nido.test.runs :as test-runs]))

(defn- with-home
  "Call `f` with the nido home pointed at a fresh directory, so the in-process core is its own."
  [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))] (f))
      (finally (fs/delete-tree tmp)))))

(defn- counting
  "Call `f` with every core request counted, answering `{:archive n :runs n}`: the archive reads
   and the runs reads made while it ran."
  [f]
  (let [seen (atom {:archive 0 :runs 0})
        real client/request!]
    (with-redefs [client/request!
                  (fn [req]
                    (cond
                      (and (= :states (:op req)) (str/includes? (str (:prefix req)) "/archive"))
                      (swap! seen update :archive inc)
                      (and (= :summaries (:op req)) (str/starts-with? (str (:project req)) "run:"))
                      (swap! seen update :runs inc))
                    (real req))]
      (f))
    @seen))

(defn- archived [holder n]
  {:kind :scratch :at (str "2026-09-0" n "T10:00:00Z")
   :from {:holder holder :seq n :ref {:adapter :review-run :id holder :title holder}}})

(deftest the-archive-is-asked-once-between-writes
  (with-home
    (fn []
      (records/archive! :nido [(archived "ws-old" 1)])
      (let [ids (atom [])]
        (is (= {:archive 1 :runs 3}
               (counting #(dotimes [_ 3] (swap! ids conj (mapv :id (filter :archived? (records/holders :nido))))))))
        (is (= [["ws-old"] ["ws-old"] ["ws-old"]] @ids) "each read answers the archive held"))
      (testing "a write drops what is held, so the next read asks again and sees it"
        (records/archive! :nido [(archived "ws-older" 2)])
        (let [hs (atom nil)]
          (is (= 1 (:archive (counting #(reset! hs (records/holders :nido))))))
          (is (= #{"ws-old" "ws-older"} (set (map :id (filter :archived? @hs))))))))))

(deftest the-archive-held-is-one-cores-own
  ;; Another home is another core: what one holds never answers a read against the other.
  (with-home #(records/archive! :nido [(archived "ws-mine" 1)]))
  (with-home
    (fn []
      (records/archive! :nido [(archived "ws-theirs" 1)])
      (is (= ["ws-theirs"] (mapv :id (filter :archived? (records/holders :nido))))))))

(deftest one-request-read-reads-the-runs-once
  (with-home
    (fn []
      (test-runs/run! :nido :improvement-claim {:adapter :improvement :id "p/1#0"} :done)
      (testing "every history derivation inside one request read shares one runs read"
        (is (= 1 (:runs (counting
                         #(work/with-request-read
                            (fn []
                              (work/proposals :nido)
                              (proposal/attempts :nido)
                              (proposal/plans-of :nido)
                              (start-failures/recoveries :nido)
                              (records/holders :nido))))))))
      (testing "a nested request read shares the outer one"
        (is (= 1 (:runs (counting
                         #(work/with-request-read
                            (fn [] (records/runs :nido)
                              (work/with-request-read (fn [] (records/runs :nido))))))))))
      (testing "nothing read is kept between requests: the next one reads again, and sees what changed"
        (let [before (work/with-request-read #(count (records/runs :nido)))]
          (test-runs/run! :nido :improvement-claim {:adapter :improvement :id "p/1#1"} :done)
          (is (= 1 (:runs (counting #(work/with-request-read (fn [] (records/runs :nido)))))))
          (is (= (inc before) (work/with-request-read #(count (records/runs :nido)))))))
      (testing "outside a request read every runs read asks"
        (is (= 2 (:runs (counting #(do (records/runs :nido) (records/runs :nido))))))))))

(deftest a-runs-read-that-fails-is-not-kept
  (with-home
    (fn []
      (let [calls (atom 0)
            real  client/request!]
        (with-redefs [client/request! (fn [req]
                                        (if (and (= :summaries (:op req)) (= 1 (swap! calls inc)))
                                          (client/unavailable "nowhere")
                                          (real req)))]
          (work/with-request-read
            (fn []
              (is (thrown? clojure.lang.ExceptionInfo (records/runs :nido)))
              (is (vector? (records/runs :nido)) "the next ask reads again"))))))))
