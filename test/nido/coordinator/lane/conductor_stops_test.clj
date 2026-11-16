(ns nido.coordinator.lane.conductor-stops-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.agent.substrate :as substrate]
   [nido.coordinator.lane.conduct :as conduct]
   [nido.coordinator.lane.conductor :as c]
   [nido.coordinator.lane.drive :as drive]
   [nido.coordinator.lane.seats :as seats]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.session :as sess]
   [nido.coordinator.record.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(def t0 "2026-11-13T14:43:20Z")
(def t0-ms (.toEpochMilli (java.time.Instant/parse t0)))
(defn- at [minutes] (+ t0-ms (* minutes 60 1000)))

(defn- workstream!
  "An open workstream with a goal and one session nido drives, its facet merged with `facet`;
   activated when `activated?`. The session is \"worker\" unless `session` names another. Its
   ledger entries are stamped at t0, so a pass's grace counts from there."
  [facet activated? & [session]]
  (let [id (:id (ws/create! :brian {:external-refs []}))]
    (with-redefs [clock/now-iso (constantly t0)]
      (ws/append-entry! :brian id {:kind :intent} (pr-str {:format :intent :goal "g" :done-when ["d"]}))
      (when activated? (ws/activate! :brian id nil)))
    (sess/create! :brian id {:name (or session "worker") :weight :light :autonomy nil})
    (sess/update-claude! :brian id (or session "worker")
                         (constantly (merge {:session-id (str "s-" (or session "worker")) :bg-id (or session "worker") :origin :nido :driver :nido
                                             :pending nil :queued nil :own-work? false
                                             :policy {:auto-respawn? false} :driving-since t0
                                             :announced-blocked? false :recovered-boot nil}
                                            facet)))
    id))

(defn- pass! [minutes] (c/stops! :brian (at minutes)))

(defn- dialog-blockers [id] (filter :stop (ws/entries-of :brian id :blocker)))

(defn- stall-hand-backs [id]
  (filter #(str/starts-with? (str (:key %)) "stall:") (seats/hand-backs :brian id)))

(deftest a-dialog-nobody-answers-reaches-the-person-and-closes-with-it
  (with-tmp
    (fn [_]
      (with-redefs [conduct/live-states     (constantly {"s-worker" {:sessionId "s-worker" :pid 1 :cwd "/w"
                                                                     :state "blocked" :status "waiting"}})
                    substrate/pending-dialog (fn [sid cwd] (when (= ["s-worker" "/w"] [sid cwd])
                                                            {:tool "Bash" :input "npm install"}))]
        (let [id (workstream! {:waiting {:since t0 :for "permission prompt"}} true)]
          (testing "inside its grace nothing is asked"
            (is (empty? (pass! 1))))
          (testing "past it, one blocker saying what the dialog waits to run"
            (is (= [:blocker] (map :wrote (pass! 3))))
            (let [[b] (dialog-blockers id)]
              (is (= {:kind :dialog :session "worker" :since t0} (:stop b)))
              (is (= "Allow worker to use Bash: npm install?" (:needs b)))
              (is (str/includes? (:summary b) "permission prompt"))))
          (testing "asked once while the dialog stands"
            (is (empty? (pass! 4)))
            (is (= 1 (count (dialog-blockers id)))))
          (testing "no stall is handed back while it stands"
            (is (empty? (stall-hand-backs id))))
          (testing "once it closes, nido answers it"
            (sess/update-claude! :brian id "worker" #(assoc % :waiting nil))
            (is (= [:blocker-answered] (map :wrote (pass! 5))))
            (is (= [:nido] (map :by (ws/entries-of :brian id :blocker-answered))))
            (is (empty? (pass! 6)) "and answers it once")))))))

(deftest a-stall-is-handed-back-to-the-conductor-with-its-reason
  (with-tmp
    (fn [_]
      (with-redefs [conduct/live-states (constantly {})]
        (let [stop {:last-stop {:at t0 :message "Intent recorded." :nido? true}}
              id   (workstream! stop true)
              off  (workstream! stop false "plain")]
          (testing "inside its grace nothing is handed back"
            (is (empty? (pass! 5))))
          (testing "past it, one hand-back carrying who stopped, when, owing what and what it said"
            (is (= [:hand-back] (map :wrote (pass! 11))))
            (let [[m] (stall-hand-backs id)]
              (is (= (str "stall:worker:" t0) (:key m)))
              (is (= :conductor (:to m)))
              (is (str/includes? (:text m) "worker's turn ended at 2026-11-13T14:43:20Z owing write-baseline"))
              (is (str/includes? (:text m) "Intent recorded."))))
          (testing "once per turn end"
            (is (empty? (pass! 12)))
            (is (= 1 (count (stall-hand-backs id)))))
          (testing "a workstream not activated has no conductor, and is handed nothing"
            (is (empty? (stall-hand-backs off))))
          (testing "a later stall waits while the conductor still owes the first an answer"
            (sess/update-claude! :brian id "worker" #(assoc-in % [:last-stop :at] "2026-11-13T15:00:00Z"))
            (is (empty? (pass! 40)))
            (is (= 1 (count (stall-hand-backs id))))))))))

(defn- at-iso [minutes] (str (java.time.Instant/ofEpochMilli (at minutes))))

(deftest a-stall-waits-out-its-grace-from-the-ledger-s-newest-entry-too
  ;; brian ws-20261116-325d89: the seat's turn ended at 16:14 waiting on the person's approval,
  ;; the approval landed at 17:20:26, and the stall was handed back at 17:20:43 — before the
  ;; conductor could pass the approval on.
  (with-tmp
    (fn [_]
      (with-redefs [conduct/live-states (constantly {})]
        (let [id (workstream! {:last-stop {:at t0 :message "Waiting on approval." :nido? true}} true)]
          (with-redefs [clock/now-iso (constantly (at-iso 60))]
            (ws/append-entry! :brian id {:kind :scratch} (pr-str {:format :scratch :session "worker"})))
          (testing "an hour after the turn end, but a minute after the ledger moved, nothing is handed back"
            (is (empty? (pass! 61))))
          (testing "once the ledger has stood still for the grace too, the stall is handed back"
            (is (= [:hand-back] (map :wrote (pass! 71))))))))))

(defn- baseline [intent-seq]
  {:format :baseline :strata [] :intent {:seq intent-seq}
   :area "a" :bounded-by "b" :shape "s"
   :model {:elements [{:id "m" :sort :module :hides "h" :interface "i"}]
           :claims [{:id "c1" :about ["m"] :statement "p" :falsified-by "f" :evidence {:by :round}}]}
   :read ["src/a.clj"]})

(defn- answered-by-nido [id]
  (set (map :blocker-seq (filter (comp #{:nido} :by) (ws/entries-of :brian id :blocker-answered)))))

(deftest a-review-stage-s-machinery-halt-is-answered-once-a-newer-record-replaces-what-it-reviewed
  ;; nido ws-20261116-a5116e, entries 6-8: verify-baseline timed out, the corrected baseline was
  ;; appended next, and the timeout still asked the person how the stage should go on.
  (with-tmp
    (fn [_]
      (with-redefs [conduct/live-states (constantly {})]
        (let [id      (workstream! {} true)
              halt    (fn [stage outcome]
                        (:seq (drive/park! :brian id {:stage stage :outcome outcome
                                                      :tried [{:stage stage :outcome outcome}]})))
              _       (ws/append-entry! :brian id {:kind :baseline} (pr-str (baseline 1)))
              timeout (halt :verify-baseline :timed-out)
              judged  (halt :verify-baseline :disputed)]
          (testing "while the record it reviewed is the newest, it stands"
            (pass! 1)
            (is (empty? (answered-by-nido id))))
          (testing "a newer baseline answers the machinery halt, saying why"
            (ws/append-entry! :brian id {:kind :baseline} (pr-str (assoc (baseline 1) :supersedes {:seq 3 :why "corrected"})))
            (pass! 2)
            (is (contains? (answered-by-nido id) timeout))
            (is (str/includes? (:summary (first (ws/entries-of :brian id :blocker-answered))) "timed-out")))
          (testing "a halt at a round's judgement stays the person's"
            (is (not (contains? (answered-by-nido id) judged))))
          (testing "a design halt is not answered by a newer baseline"
            (let [d (halt :decide-design :usage-limited)]
              (ws/append-entry! :brian id {:kind :baseline} (pr-str (assoc (baseline 1) :supersedes {:seq 6 :why "again"})))
              (pass! 3)
              (is (not (contains? (answered-by-nido id) d))))))))))

(deftest catalog-chat-replay
  ;; catalog-chat as it stood at 15:00 on 2026-11-13: listed blocked, waiting, on an
  ;; AskUserQuestion its transcript (the fixture) shows unanswered.
  (with-tmp
    (fn [_]
      (let [fixture (str (fs/absolutize "test/fixtures/escalate/catalog-chat-1500.jsonl"))]
        (with-redefs [conduct/live-states (constantly {"s-catalog-chat" {:sessionId "s-catalog-chat" :pid 1 :cwd "/w"
                                                                          :state "blocked" :status "waiting"
                                                                          :waitingFor "input needed"}})
                      substrate/transcript-path (constantly fixture)]
          (let [id (workstream! {:waiting {:since "2026-11-13T14:43:19Z" :for "input needed"}} true "catalog-chat")]
            (testing "a session waiting on a dialog is not live"
              (is (false? (:live ((requiring-resolve 'nido.coordinator.lane.pipeline/status) :brian id)))))
            (testing "two minutes on, the person is asked, in the dialog's own words, and owes the move"
              (c/stops! :brian (.toEpochMilli (java.time.Instant/parse "2026-11-13T15:00:00Z")))
              (let [[b] (dialog-blockers id)]
                (is (str/includes? (:summary b) "It asks: “"))
                (is (str/ends-with? (:needs b) "?")))
              (is (= :person (:owed-by ((requiring-resolve 'nido.coordinator.lane.pipeline/status) :brian id)))))))))))
