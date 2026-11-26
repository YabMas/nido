(ns nido.core.commit-test
  "The commit stratum: writer, feed and api, against a real store."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nido.core.api :as api]
            [nido.core.domain :as d]
            [nido.core.feed :as feed]
            [nido.core.store :as store]
            [nido.core.writer :as writer])
  (:import [java.lang.management ManagementFactory]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-dir [] (str (Files/createTempDirectory "nido-commit-" (make-array FileAttribute 0))))

(defn- open-unit [ws key unit & {:as more}]
  (merge {:key key :ws ws :command {:command :open-unit :unit unit :project "p" :goal "g" :session-cwd "/tmp"}} more))

(defmacro with-writer [[w s db opts] & body]
  `(let [dir# (temp-dir)
         ~db (str dir# "/core.db")
         ~s (store/open ~db)
         ~w (writer/start ~s ~opts)]
     (try ~@body
          (finally (writer/stop! ~w) (store/close ~s)))))

(defn- answer [p] (deref p 10000 ::no-answer))

(defn- within
  "Whether `pred` holds within ten seconds, polled: for what a test sees only some time after the
   call that caused it."
  [pred]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 10) (recur))))))

(defn- next-within
  "The next message on `conn`, or ::no-message when none comes within ten seconds — so a stream
   that ends short fails its test instead of hanging the suite."
  [conn]
  (deref (future (api/next-message conn)) 10000 ::no-message))

(deftest a-command-is-answered-with-what-committed-and-a-repeat-with-the-same
  (with-writer [w s db {}]
    (let [first-answer (answer (writer/submit! w (open-unit "ws" "k1" "u")))]
      (is (= [{:event :unit-opened :unit "u" :project "p" :goal "g" :session-cwd "/tmp"}] (:events first-answer)))
      (testing "a resent key is answered from the store, not decided again"
        (is (= first-answer (answer (writer/submit! w (open-unit "ws" "k1" "u")))))
        (is (= 1 (count (store/events s "ws")))))
      (testing "a refusal is an outcome committed under its key"
        (let [r (answer (writer/submit! w (open-unit "ws" "k2" "u")))]
          (is (:refused r))
          (is (= r (store/command-outcome s "k2")))))
      (testing "a malformed envelope is answered at once and never queued"
        (is (= {:error :key-missing} (answer (writer/submit! w {:ws "ws" :command {:command :open-unit}})))))
      (is (empty? (store/verify s))))))

(deftest nothing-leaves-before-its-commit-is-durable
  ;; The gate is observed from a second connection: when a caller is answered, and when a change
  ;; is published, another reader already sees the command committed.
  (let [seen (atom [])
        db-holder (atom nil)
        publish (fn [changes]
                  (let [r (store/open-reader @db-holder)]
                    (try (swap! seen conj [:published (mapv :ws changes)
                                           (mapv #(= (:state %) (store/state r (:ws %))) changes)])
                         (finally (store/close r)))))]
    (let [dir (temp-dir) db (str dir "/core.db") _ (reset! db-holder db)
          s (store/open db) w (writer/start s {:publish publish})]
      (try
        (let [answers (doall (for [i (range 20)] (writer/submit! w (open-unit (str "ws" (mod i 3)) (str "k" i) (str "u" i)))))
              r (store/open-reader db)]
          (doseq [[i p] (map-indexed vector answers)]
            (answer p)
            (is (some? (store/command-outcome r (str "k" i))) "a caller is answered only once its outcome is durable"))
          (store/close r)
          ;; A batch is published after its callers are answered, so the last publish may still
          ;; be on its way when the last answer is.
          (is (within #(seq @seen)) "each commit is published")
          (is (every? #(every? true? (nth % 2)) @seen) "a published change is the committed state"))
        (finally (writer/stop! w) (store/close s))))))

(deftest input-is-taken-ahead-of-the-background-backlog
  ;; The writer is held on a first command whose decision blocks; a backlog queues behind it,
  ;; then one person's input.
  (let [order (atom [])
        gate (promise)]
    (with-writer [w s db {:max-batch 8
                          :decide (fn [st c]
                                    (when (= "hold" (:unit c)) @gate)
                                    (swap! order conj (:unit c))
                                    (d/step st c))}]
      (let [held (writer/submit! w (open-unit "ws" "hold" "hold"))
            _    (Thread/sleep 100)
            bg   (doall (for [i (range 100)] (writer/submit! w (open-unit "ws" (str "bg" i) (str "bg" i)))))
            in   (writer/submit! w (open-unit "ws" "input" "input" :input? true))]
        (deliver gate true)
        (answer in) (run! answer bg) (answer held)
        (is (= ["hold" "input"] (take 2 @order))
            "the input waits on the batch in progress, and on nothing queued behind it")
        (is (= 102 (count @order)))))))

(deftest one-bad-command-does-not-sink-its-batch
  (let [dir (temp-dir) s (store/open (str dir "/core.db"))
        ;; A decision that yields a state the store's constraints refuse: two running turns on one unit.
        bad-decide (fn [st c]
                     (if (= "bad" (:unit c))
                       [(-> st (assoc-in [:units "bad"] {:id "bad" :status :open :held? false :unknowns #{}})
                            (assoc-in [:turns "t1"] {:id "t1" :unit "bad" :status :running})
                            (assoc-in [:turns "t2"] {:id "t2" :unit "bad" :status :running}))
                        [{:event :unit-opened :unit "bad" :project "p" :goal "g"}] nil]
                       (d/step st c)))
        gate (promise)
        w (writer/start s {:decide (fn [st c] (when (= "first" (:unit c)) @gate) (bad-decide st c))})]
    (try
      (let [p0 (writer/submit! w (open-unit "w0" "first" "first"))
            _  (Thread/sleep 100)
            ps (doall (for [u ["a" "bad" "b"]] (writer/submit! w (open-unit (str "ws-" u) u u))))]
        (deliver gate true)
        (answer p0)
        (let [[a bad b] (map answer ps)]
          (is (:events a)) (is (:events b))
          (is (:error bad) "the command whose commit is refused is answered an error")
          (is (nil? (store/command-outcome s "bad")) "and nothing is recorded under its key, so it may be resent")
          (is (empty? (store/verify s)))))
      (finally (writer/stop! w) (store/close s)))))

(deftest an-idle-writer-executes-nothing
  (with-writer [w s db {}]
    (answer (writer/submit! w (open-unit "ws" "k" "u")))
    (Thread/sleep 200)
    (let [mx (ManagementFactory/getThreadMXBean)
          id (.getId ^Thread (:thread w))
          before (.getThreadCpuTime mx id)]
      (Thread/sleep 1000)
      (is (< (- (.getThreadCpuTime mx id) before) 2000000)
          "under 2ms of CPU in a second of idling: it blocks on its queue"))))

(deftest a-subscriber-catches-up-then-follows-and-resumes-after-a-cut-off
  (let [f (feed/create)]
    (with-writer [w s db {:publish #(feed/publish! f %)}]
      (answer (writer/submit! w (open-unit "w1" "k1" "u1")))
      (answer (writer/submit! w (open-unit "w2" "k2" "u2")))
      (let [r (store/open-reader db)
            sub (feed/subscribe! f r 1 {:buffer 2})]
        (testing "catch-up: what changed after the position it resumes from, then where it ends"
          (is (= [["w2" 2]] [((juxt :ws :pos) (feed/take! sub 100))]))
          (is (= {:caught-up true :pos 2} (feed/take! sub 50)))
          (is (nil? (feed/take! sub 50)) "the end of the catch-up is said once"))
        (testing "live, in commit order"
          (answer (writer/submit! w (open-unit "w1" "k3" "u3")))
          (is (= ["w1" 3] ((juxt :ws :pos) (feed/take! sub 1000)))))
        (testing "a subscriber that falls behind is cut off, then resumes losing nothing"
          (doseq [i (range 5)] (answer (writer/submit! w (open-unit "w3" (str "c" i) (str "c" i)))))
          (let [taken (loop [acc []] (let [m (feed/take! sub 100)] (if (or (nil? m) (:cut-off m)) (conj acc m) (recur (conj acc m)))))
                cut (last taken)]
            (is (:cut-off cut))
            (let [sub2 (feed/subscribe! f r (:resume-from cut) {})
                  m (feed/take! sub2 100)]
              (is (= ["w3" 8] ((juxt :ws :pos) m)) "the catch-up carries the latest state of what it missed")
              (is (= 5 (count (get-in m [:state :units])))))))
        (store/close r)))))

(deftest a-catch-up-ends-before-anything-live
  (let [f (feed/create)]
    (with-writer [w s db {:publish #(feed/publish! f %)}]
      (answer (writer/submit! w (open-unit "w1" "k1" "u1")))
      (answer (writer/submit! w (open-unit "w2" "k2" "u2")))
      (let [r (store/open-reader db)
            sub (feed/subscribe! f r 0 {})]
        ;; Committed after the subscription registered and before anything was taken: live, so it
        ;; comes after the marker, which a reader takes to mean it holds every workstream.
        (answer (writer/submit! w (open-unit "w3" "k3" "u3")))
        (let [ms (vec (take-while some? (repeatedly #(feed/take! sub 200))))]
          (is (= [:w1 :w2 :caught-up :w3]
                 (mapv #(if (:caught-up %) :caught-up (keyword (:ws %))) ms))))
        (store/close r)))))

(deftest the-feed-shows-as-awaiting-exactly-the-open-operator-asks
  (let [f (feed/create)
        ready {:question "Go?" :explored "x" :options ["a" "b"] :recommendation "a"}]
    (with-writer [w s db {:publish #(feed/publish! f %)}]
      (let [r (store/open-reader db)
            sub (feed/subscribe! f r 0 {})]
        (is (:caught-up (feed/take! sub 100)) "nothing committed yet: caught up at once")
        (doseq [[k c] [["1" {:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/tmp"}]
                       ["2" {:command :start-turn :turn "t" :unit "u" :spec {:executor :fake :instructions "i"}}]
                       ["3" {:command :record-outcome :turn "t" :outcome :asked :call {:id "c" :question "Go?"}}]]]
          (answer (writer/submit! w {:key k :ws "ws" :command c})))
        (let [ms (repeatedly 3 #(feed/take! sub 1000))]
          (is (every? (comp empty? :awaiting) ms) "a unit's call is not shown as awaiting the person"))
        (answer (writer/submit! w {:key "4" :ws "ws" :command {:command :raise-operator-ask :ask "a" :binds ["t"] :concerns "u" :content ready}}))
        (is (= ["a"] (map :id (:awaiting (feed/take! sub 1000)))))
        (store/close r)))))

(deftest the-socket-answers-commands-reads-and-subscriptions
  (let [f (feed/create)
        sock (str "/tmp/nido-core-" (System/nanoTime) ".sock")]
    (with-writer [w s db {:publish #(feed/publish! f %)}]
      (let [srv (api/serve! {:socket sock :submit #(writer/submit! w %) :open-session #(feed/session f db)})]
        (try
          (let [c (api/connect sock) sub (api/connect sock)]
            (api/subscribe sub 0)
            ;; Nothing is committed yet, so the catch-up is empty and its marker comes at once —
            ;; which is also what says the subscription is registered before anything commits.
            (is (:caught-up (next-within sub)) "the subscription says where its catch-up ends")
            (testing "commands, through the writer"
              (is (:events (api/request c {:op :command :key "k1" :ws "ws" :command {:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/tmp"}})))
              (is (:events (api/request c {:op :command :key "k2" :ws "ws" :command {:command :open-unit :unit "v" :project "p" :goal "g" :session-cwd "/tmp"} :input? true}))))
            (testing "reads, from committed state"
              (is (= :open (get-in (api/request c {:op :state :ws "ws"}) [:state :units "u" :status])))
              (is (= {:workstreams ["ws"]} (api/request c {:op :workstreams}))))
            (testing "what is not a request is refused, and the connection goes on"
              (is (= {:error :unknown-op :op :drop-tables} (api/request c {:op :drop-tables})))
              (.write ^java.io.BufferedWriter (:writer c) "not edn {\n")
              (.flush ^java.io.BufferedWriter (:writer c))
              (is (= {:error :malformed} (api/next-message c)))
              (is (= {:workstreams ["ws"]} (api/request c {:op :workstreams}))))
            (testing "the subscription streams each commit after its catch-up"
              (let [ms (vec (repeatedly 2 #(next-within sub)))]
                (is (not-any? #(and (map? %) (:caught-up %)) ms) "the catch-up ends once")
                (is (= [#{"u"} #{"u" "v"}]
                       (for [m ms] (when (map? m) (set (keys (:units (:state m))))))))))
            (api/disconnect c) (api/disconnect sub))
          (finally (api/close! srv)))))))
