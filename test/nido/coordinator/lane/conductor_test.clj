(ns nido.coordinator.lane.conductor-test
  "The conductor: a start carried into the whole seat, every turn nido gave for no Run handed back
   once, one decision per hand-back from the conductor alone, an unanswered hand-back escalated,
   a person's reply to an escalation recorded as its answer, a person's act refused for the
   conductor and the seats on its instruction, and one conductor for every worked workstream."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.lane.conduct :as conduct]
   [nido.coordinator.lane.conductor :as conductor]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.record.generation :as generation]
   [nido.coordinator.record.seats :as rseats]
   [nido.coordinator.lane.seats :as seats]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.session-events :as session-events]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.platform.project :as project]
   [nido.coordinator.record.standing :as standing]
   [nido.session.commit-gate :as commit-gate]
   [nido.session.launcher :as launcher]
   [nido.session.lifecycle :as lifecycle]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))] (f))
         (finally (fs/delete-tree tmp)))))

(defmacro ^:private launching
  "Run `body` with every side effect of starting a session stubbed, `launched` an atom collecting
   [session-name prompt] per start."
  [[launched] & body]
  `(let [~launched (atom [])]
     (with-redefs [lifecycle/up!             (fn [~'_ ~'_] nil)
                   lifecycle/runtime-of      (fn [~'_ ~'_] {:instance-id "brian--ws" :key "ws"})
                   lifecycle/session-coords  (fn [n# ~'_] {:wt-path (str "/wt/" n#) :instance-id "brian--ws"})
                   launcher/session-briefing (fn [~'& ~'_] "briefing")
                   launcher/nido-add-dirs    (fn [] [])
                   project/list-projects     (fn [] {"brian" {:directory "/src/brian"}})
                   conduct/start!            (fn [o#]
                                               (swap! ~launched conj [(:session-name o#) (:prompt o#)])
                                               (session/update-substrate! (:project o#) (:ws-id o#) (:session-name o#)
                                                                          (fn [m#] (assoc m# :dispatching {:token "t" :at "now"})))
                                               {})
                   conduct/retire!           (fn [~'& ~'_] nil)
                   conduct/live-states       (fn [~'_] {})]
       ~@body)))

(defn- in-session
  "Run `f` as if from inside the nido session `n`, or outside every session for nil."
  [n f]
  (with-redefs [lifecycle/session-from-cwd (fn [& _] (when n {:project "brian" :session n}))
                lifecycle/session-home-coords-from-cwd (fn [& _] nil)]
    (f)))

(defn- workstream
  "A workstream with an intent and nothing designed, so its seat is the whole one. Returns [id add]."
  []
  (let [id  (:id (ws/create! :brian {:external-refs []}))
        add (fn [kind record] (ws/append-entry! :brian id {:kind kind} (pr-str record))
              (count (:entries (ws/read-ws :brian id))))]
    (add :intent {:format :intent :goal "g" :done-when ["d"]})
    [id add]))

(defn- driving
  "Make `n`'s record a session nido drives in conversation `conv`, in a turn on `pending`."
  [id n conv pending]
  (session/update-substrate! :brian id n (constantly {:claude {:session-id conv :bg-id "b" :origin :nido
                                                               :driver :nido :hold nil :pending pending
                                                               :queued nil :own-work? false
                                                               :policy {:auto-respawn? false} :cursor 0
                                                               :driving-since nil :announced-blocked? false
                                                               :recovered-boot nil}
                                                      :dispatching nil})))

(deftest a-start-is-carried-into-a-free-whole-seat
  (with-tmp
    (fn []
      (let [[id] (workstream)]
        (session/create! :brian id {:name "fix-it" :weight :heavy :autonomy nil})
        (session/create! :brian id {:name "other" :weight :heavy :autonomy nil})
        (launching [launched]
          (is (= {:carried {:seat 1 :session "fix-it"}} (conductor/carry! :brian id "fix-it" "do the thing")))
          (is (str/includes? (second (first @launched)) "do the thing") "its brief is its first instruction")
          (is (= {:carried nil :why :occupied} (conductor/carry! :brian id "other"))
              "a second start displaces no one"))))))

(deftest a-carried-seat-whose-launch-failed-is-started-again-on-the-next-pass
  (with-tmp
    (fn []
      (let [[id] (workstream)]
        (session/create! :brian id {:name "fix-it" :weight :heavy :autonomy nil})
        (launching [launched]
          (with-redefs [conduct/start! (fn [_] (throw (ex-info "launch failed" {:reason :boom})))]
            (is (thrown? Exception (conductor/carry! :brian id "fix-it" "do the thing"))))
          (is (= 1 (:seat (first (seats/occupants :brian id)))) "the record names the seat")
          (with-redefs [conduct/start! (fn [_] (throw (ex-info "launch failed again" {:reason :boom})))]
            (is (= [1] (:escalated (conductor/reseat! :brian id))))
            (is (= ["seat 1 could not be started"] (mapv :needs (ws/entries-of :brian id :blocker)))
                "a pass whose start failed names the seat in an escalation")
            (conductor/reseat! :brian id)
            (is (= 1 (count (ws/entries-of :brian id :blocker))) "once"))
          (conductor/reseat! :brian id)
          (is (= ["fix-it"] (mapv first @launched)) "the seat is started again, not counted as held")
          (is (= [{:by :nido :blocker-seq (:seq (first (ws/entries-of :brian id :blocker)))}]
                 (mapv #(select-keys % [:by :blocker-seq]) (ws/entries-of :brian id :blocker-answered)))
              "and the escalation naming it is answered by nido, owing the person nothing")
          (is (str/includes? (second (first @launched)) "do the thing")
              "on the first instruction it was carried with"))))))

(deftest a-closed-workstream-is-not-reseated
  (with-tmp
    (fn []
      (let [[id] (workstream)]
        (session/create! :brian id {:name "fix-it" :weight :heavy :autonomy nil})
        (launching [launched]
          (with-redefs [conduct/start! (fn [_] (throw (ex-info "launch failed" {:reason :boom})))]
            (is (thrown? Exception (conductor/carry! :brian id "fix-it" "do the thing"))))
          (ws/close! :brian id :done)
          (is (= {} (conductor/reseat! :brian id)))
          (is (= [] @launched) "nothing is started on a settled workstream")
          (let [[other] (workstream)]
            (session/create! :brian other {:name "late" :weight :heavy :autonomy nil})
            (ws/close! :brian other :done)
            (is (= {:carried nil :why :closed} (conductor/carry! :brian other "late")) "nor carried onto it")
            (is (nil? (:seat (session/read-session :brian other "late"))) "and its record names no seat")))))))

(deftest only-a-turn-nido-gave-for-no-run-is-handed-back-once
  (with-tmp
    (fn []
      (let [[id] (workstream)]
        (session/create! :brian id {:name "fix-it" :weight :heavy :autonomy nil})
        (let [turn   (fn [instruction run] {:turn-ends [{:project :brian :ws-id id :session "fix-it"
                                                         :event {:last-message "done"}
                                                         :instruction instruction :run run}]})
              posted (conductor/hand-backs! (turn {:token "tok-1"} nil))]
          (is (= 1 (count posted)))
          (is (= :conductor (:to (first posted))))
          (is (str/includes? (:text (first posted)) "done") "it carries the turn's last message")
          (with-redefs [standing/governing-design (fn [_ _] {:seq 42 :summary "the design now"})]
            (let [t (:text (first (conductor/hand-backs! (turn {:token "tok-3"} nil))))]
              (is (str/includes? t "Governing design: entry 42 — the design now")
                  "and the governing design as the records give it at that hand-back")
              (is (str/includes? t "Seats: the whole seat (1)") "and the seats")))
          (is (= (mapv :id posted) (mapv :id (conductor/hand-backs! (turn {:token "tok-1"} nil))))
              "the same turn end is logged once")
          (is (= [] (conductor/hand-backs! (turn {:token "tok-2"} "run-1"))) "a Run's turn is the Run's")
          (is (= [] (conductor/hand-backs! (turn nil nil))) "a person's turn is handed back to no one"))))))

(deftest a-hand-back-takes-one-decision-from-the-conductor
  (with-tmp
    (fn []
      (let [[id] (workstream)
            c    (str id "--conductor")]
        (session/create! :brian id {:name "fix-it" :weight :heavy :autonomy nil})
        (launching [_]
          (seats/conductor! :brian id "conduct it")
          (conductor/carry! :brian id "fix-it"))
        (let [hb  (:id (first (conductor/hand-backs! {:turn-ends [{:project :brian :ws-id id :session "fix-it"
                                                                   :event {} :instruction {:token "t1"} :run nil}]})))
              go  {:hand-back hb :decision [:instruct [{:seat 1 :text "go on"}]]}]
          (is (= [:refused :not-the-conductor] (in-session "fix-it" #(conductor/decide! :brian id go))))
          (is (= [:refused :not-a-hand-back] (in-session c #(conductor/decide! :brian id (assoc go :hand-back "nope")))))
          (is (= [:refused :not-a-seat]
                 (in-session c #(conductor/decide! :brian id (assoc go :decision [:instruct [{:seat "agg" :text "x"}]]))))
              "an instruction goes only to a current seat")
          (let [[verdict entry] (in-session c #(conductor/decide! :brian id go))]
            (is (= :accepted verdict))
            (is (= :conductor (:by entry)))
            (is (some? (:position entry)) "decided at the position the pipeline derives"))
          (is (= ["go on"] (mapv :text (filter #(= :instruction (:kind %)) (seats/traffic :brian id 1))))
              "the instruction is posted to the seat it names")
          (is (= [:refused :already-answered] (in-session c #(conductor/decide! :brian id go)))))))))

(deftest a-seat-gone-before-the-decision-lands-refuses-it-whole
  (with-tmp
    (fn []
      (let [[id] (workstream)
            c    (str id "--conductor")]
        (session/create! :brian id {:name "fix-it" :weight :heavy :autonomy nil})
        (launching [_]
          (seats/conductor! :brian id "conduct it")
          (conductor/carry! :brian id "fix-it"))
        (let [hb    (:id (first (conductor/hand-backs! {:turn-ends [{:project :brian :ws-id id :session "fix-it"
                                                                     :event {} :instruction {:token "t1"} :run nil}]})))
              gone  (atom false)
              seats* rseats/seats]
          ;; The seat goes while the decision is being made — as an opening or a design approval
          ;; would remove it — after the conductor named it and before the decision is recorded.
          (with-redefs [pipeline/of  (fn [& _] (reset! gone true) {:at :implementing :next nil})
                        rseats/seats (fn [& args] (if @gone [] (apply seats* args)))]
            (is (= [:refused :not-a-seat]
                   (in-session c #(conductor/decide! :brian id {:hand-back hb :decision [:instruct [{:seat 1 :text "go on"}]]})))))
          (is (= [] (ws/entries-of :brian id :conduct-decision)) "nothing is recorded")
          (is (= [] (filter #(= :instruction (:kind %)) (seats/traffic :brian id 1))) "nothing is posted"))))))

(deftest a-decision-the-ledger-refuses-leaves-nothing-behind
  (with-tmp
    (fn []
      (let [[id] (workstream)
            c    (str id "--conductor")]
        (session/create! :brian id {:name "fix-it" :weight :heavy :autonomy nil})
        (launching [_]
          (seats/conductor! :brian id "conduct it")
          (conductor/carry! :brian id "fix-it"))
        (let [hb (:id (first (conductor/hand-backs! {:turn-ends [{:project :brian :ws-id id :session "fix-it"
                                                                  :event {} :instruction {:token "t1"} :run nil}]})))]
          (is (thrown? Exception (in-session c #(conductor/decide! :brian id {:hand-back hb
                                                                               :decision [:instruct [{:seat 1 :text "go on" :why "x"}]]}))))
          (is (= [] (filter #(= :instruction (:kind %)) (seats/traffic :brian id 1)))
              "an instruction the decision's schema refuses is posted to no one")
          (is (thrown? Exception (in-session c #(conductor/decide! :brian id {:hand-back hb
                                                                               :decision [:escalate {:needs "x" :why "y"}]}))))
          (is (= [] (ws/entries-of :brian id :blocker)) "an escalation the ledger refuses parks nothing")
          (is (= [] (ws/entries-of :brian id :conduct-decision)))
          (testing "and the hand-back stays owed, its corrected answer posting what it records"
            (is (= :accepted (first (in-session c #(conductor/decide! :brian id {:hand-back hb
                                                                                   :decision [:instruct [{:seat 1 :text "go on, corrected"}]]})))))
            (is (= ["go on, corrected"] (mapv :text (filter #(= :instruction (:kind %)) (seats/traffic :brian id 1)))))))))))

(deftest a-decision-is-written-only-by-decide
  (with-tmp
    (fn []
      (let [[id add] (workstream)
            c        (str id "--conductor")]
        (launching [_] (seats/conductor! :brian id "conduct it"))
        (let [hb (:id (conductor/hand-back! :brian id c))]
          (is (thrown? Exception (add :conduct-decision {:format :conduct-decision :hand-back hb :position :intake
                                                         :by :conductor :decision [:escalate {:needs "x"}]}))
              "a generic append of a decision is refused")
          (is (= :accepted (first (in-session c #(conductor/decide! :brian id {:hand-back hb
                                                                                 :decision [:escalate {:needs "x"}]}))))
              "so the conductor's own answer is not locked out"))))))

(deftest two-decisions-on-one-hand-back-land-once
  (with-tmp
    (fn []
      (let [[id] (workstream)
            c    (str id "--conductor")]
        (launching [_] (seats/conductor! :brian id "conduct it"))
        (let [hb (:id (conductor/hand-back! :brian id c))
              go {:hand-back hb :decision [:escalate {:needs "x"}]}]
          ;; The position is read between the uniqueness check and the write; slowed, both
          ;; decisions are inside that window at once unless the two are one step.
          (with-redefs [pipeline/of (fn [& _] (Thread/sleep 300) {:at :implementing :next nil})]
            (in-session c
                        #(let [a (future (conductor/decide! :brian id go))
                               b (future (conductor/decide! :brian id go))]
                           (is (= #{:accepted :refused} (set [(first @a) (first @b)]))))))
          (is (= 1 (count (ws/entries-of :brian id :conduct-decision)))))))))

(deftest an-earlier-generations-escalation-is-not-answered-in-this-one
  (with-tmp
    (fn []
      (let [[id] (workstream)
            c    (str id "--conductor")]
        (launching [_] (seats/conductor! :brian id "conduct it"))
        (let [hb (:id (conductor/hand-back! :brian id c))]
          (in-session c #(conductor/decide! :brian id {:hand-back hb :decision [:escalate {:needs "x"}]}))
          (is (conductor/awaiting-answer? :brian id))
          (let [cut (count (:entries (ws/read-ws :brian id)))]
            (with-redefs [generation/current-span (fn [es] (filterv #(> (:seq %) cut) es))]
              (is (not (conductor/awaiting-answer? :brian id)) "a new generation owes its predecessor's blocker no reply")
              (is (nil? (conductor/answer! :brian id "yes"))))))))))

(deftest an-escalation-parks-and-a-reply-answers-it
  (with-tmp
    (fn []
      (let [[id] (workstream)
            c    (str id "--conductor")]
        (launching [_] (seats/conductor! :brian id "conduct it"))
        (is (nil? (conductor/answer! :brian id "yes")) "no conductor's blocker is open")
        (let [hb (:id (conductor/hand-back! :brian id c))
              [v entry] (in-session c #(conductor/decide! :brian id {:hand-back hb
                                                                      :decision [:escalate {:needs "approve the design"}]}))]
          (is (= :accepted v))
          (is (= "approve the design" (:needs (last (ws/entries-of :brian id :blocker)))))
          (is (= (:seq (last (ws/entries-of :brian id :blocker))) (get-in entry [:parked :seq]))
              "the decision cites the blocker it parked")
          (let [a (conductor/answer! :brian id "approved — go")]
            (is (= (get-in entry [:parked :seq]) (:blocker-seq a)))
            (is (= ["approved — go"] (mapv :summary (ws/entries-of :brian id :blocker-answered)))))
          (is (nil? (conductor/answer! :brian id "again")) "answered, it is no longer open"))))))

(deftest only-the-act-the-escalated-step-takes-answers-its-blocker
  (with-tmp
    (fn []
      (let [[id add] (workstream)
            c        (str id "--conductor")]
        (launching [_] (seats/conductor! :brian id "conduct it"))
        (with-redefs [pipeline/of (constantly {:at :triage-awaiting :next {:stage :accept-triage :mode :human}})]
          (let [hb (:id (conductor/hand-back! :brian id c))]
            (in-session c #(conductor/decide! :brian id {:hand-back hb :decision [:escalate {:needs "accept the triage"}]})))
          (Thread/sleep 5)
          (let [amended (add :intent {:format :intent :goal "g2" :done-when ["d"] :supersedes {:seq 1 :why "amend"}})]
            (conductor/answers! :brian)
            (is (empty? (ws/entries-of :brian id :blocker-answered)) "an intent amendment accepts no triage")
            (is (some #(str/includes? (str (:text %)) (str "entry " amended))
                      (filter #(= :hand-back (:kind %)) (seats/traffic :brian id 1)))
                "it is handed back as an act of its own"))
          (let [accepted (add :triage-accepted {:format :triage-accepted})]
            (conductor/answers! :brian)
            (is (= [accepted] (mapv (comp :seq :act) (ws/entries-of :brian id :blocker-answered)))
                "the acceptance answers the blocker escalated at accepting the triage")))))))

(deftest a-conductor-turn-that-leaves-its-hand-back-unanswered-escalates-it
  (with-tmp
    (fn []
      (let [[id] (workstream)
            c    (str id "--conductor")]
        (launching [_] (seats/conductor! :brian id "conduct it"))
        (driving id c "conv-1" {:token "tok" :at "t" :via :send :seen? false})
        (let [hb (conductor/hand-back! :brian id c)]
          (is (= [] (conductor/close-unanswered! :brian id c {:token "tok"})) "a hand-back not yet delivered stays owed")
          (seats/delivered! :brian id (:id hb) "tok")
          (let [[e] (conductor/close-unanswered! :brian id c {:token "tok"})]
            (is (= :nido (:by e)))
            (is (= (:id hb) (:hand-back e)))
            (is (= :escalate (first (:decision e)))))
          (is (= [] (conductor/close-unanswered! :brian id c {:token "tok"})) "answered once, by nido"))))))

(deftest a-conductor-turn-end-escalates-only-the-hand-back-of-the-turn-that-ended
  (with-tmp
    (fn []
      (let [[id] (workstream)
            c    (str id "--conductor")]
        (launching [_] (seats/conductor! :brian id "conduct it"))
        (driving id c "conv-1" {:token "tok-a" :at "t" :via :send :seen? false})
        (let [a (conductor/hand-back! :brian id c)]
          (seats/delivered! :brian id (:id a) "tok-a")
          (session/update-substrate! :brian id c #(assoc-in % [:claude :driving-since] "later"))
          (let [b (conductor/hand-back! :brian id c)]
            (session/update-substrate! :brian id c #(assoc-in % [:claude :pending :token] "tok-b"))
            (seats/delivered! :brian id (:id b) "tok-b")
            (is (= [(:id a)] (mapv :hand-back (conductor/close-unanswered! :brian id c {:token "tok-a"})))
                "the ended turn's hand-back, not the newest delivery, is escalated")
            (is (not-any? #(= (:id b) (:hand-back %)) (ws/entries-of :brian id :conduct-decision))
                "the hand-back whose turn has just begun stays owed")))))))

(deftest every-worked-open-workstream-has-a-conductor
  (with-tmp
    (fn []
      (let [[worked]   (workstream)
            [unworked] (workstream)
            [before]   (workstream)]
        (session/create! :brian before {:name "long-ago" :weight :heavy :autonomy nil})
        (session/write! (assoc (session/read-session :brian before "long-ago") :created-at "2020-01-01T00:00:00Z"))
        (io/write-edn! (doto (str (fs/path (core/nido-root) "coordinator" "conductors-since.edn"))
                         (-> fs/parent fs/create-dirs))
                       {:since "2025-01-01T00:00:00Z"})
        (session/create! :brian worked {:name "fix-it" :weight :heavy :autonomy nil})
        (launching [launched]
          (is (= [{:ws-id worked :act :started :session (str worked "--conductor")}]
                 (conductor/conductors! :brian)))
          (is (str/includes? (second (first @launched)) "bb nido:seats:decide") "briefed with how to decide")
          (is (not-any? #(#{unworked before} (:ws-id %)) (conductor/conductors! :brian))
              "a workstream never worked, or worked only before conductors began, gets none")
          (is (= "2025-01-01T00:00:00Z"
                 (:since (io/read-edn (str (fs/path (core/nido-root) "coordinator" "conductors-since.edn")))))
              "the instant conductors began never moves"))))))

(deftest a-ticket-settled-in-notion-retires-its-conductor
  (with-tmp
    (fn []
      (let [[id add] (workstream)]
        (io/write-edn! (doto (str (fs/path (core/nido-root) "coordinator" "conductors-since.edn"))
                         (-> fs/parent fs/create-dirs))
                       {:since "2025-01-01T00:00:00Z"})
        (session/create! :brian id {:name "fix-it" :weight :heavy :autonomy nil})
        (launching [_]
          (with-redefs [seats/retire-conductor! (fn [_ _] {:retired true})]
            (is (= [:started] (mapv :act (conductor/conductors! :brian))))
            (add :notion-status {:format :notion-status :page-id "p" :status "Review" :by :poller})
            (is (= [:retired] (mapv :act (conductor/conductors! :brian))) "settled in Notion is closed")
            (add :stage-set {:format :stage-set :stage :in-progress :by :person})
            (is (= [] (conductor/conductors! :brian))
                "a stage chosen since takes it back out, so its running conductor stays")))))))

(deftest the-first-pass-records-when-conductors-began
  (with-tmp
    (fn []
      (let [[id] (workstream)]
        (session/create! :brian id {:name "fix-it" :weight :heavy :autonomy nil})
        (launching [_]
          (is (= [] (conductor/conductors! :brian)) "a session recorded before the first pass is not work since")
          (session/create! :brian id {:name "fix-it-2" :weight :heavy :autonomy nil})
          (is (= [id] (mapv :ws-id (conductor/conductors! :brian))) "one recorded after it is"))))))

(deftest a-persons-act-is-refused-for-the-conductor-and-its-instructions
  (with-tmp
    (fn []
      (let [[id] (workstream)
            c    (str id "--conductor")
            in   (fn [n command]
                   (in-session n #(conductor/refusal {:tool_name "Bash" :cwd "/wt" :tool_input {:command command}})))]
        (session/create! :brian id {:name "fix-it" :weight :heavy :autonomy nil})
        (launching [_] (seats/conductor! :brian id "conduct it"))
        (with-redefs [commit-gate/acts (fn [command _]
                                         (cond
                                           (str/includes? command "design:approve") [{:kind :nido-task :task "nido:design:approve" :args []}]
                                           (str/starts-with? command "bb nido:")    [{:kind :nido-task :task (second (str/split command #" "))
                                                                                       :args (vec (drop 2 (str/split command #" ")))}]
                                           (str/includes? command "gh api")         [{:kind :gh-pr :verb "api"}]
                                           (str/includes? command "$V")             [{:kind :gh-pr :verb :unread}]
                                           (str/starts-with? command "gh pr ")      [{:kind :gh-pr :verb (nth (str/split command #" ") 2)}]
                                           :else []))]
          (is (str/includes? (in c "bb nido:design:approve :design-seq 4") "conductor"))
          (doseq [verb ["nido:improvement:discharge" "nido:ticket:dismiss" "nido:workstream:fork"
                        "nido:recovery:restore" "nido:improvement:reserve" "nido:workstream:stage:advance"
                        "nido:findings:resolve" "nido:status:backfill"]]
            (is (some? (in c (str "bb " verb))) (str verb " closes or states an intent")))
          (is (some? (in c "bb nido:ticket:append :kind :triage-accepted")) "a generic append of a person's kind")
          (is (nil? (in c "bb nido:ticket:append :kind :note")) "a generic append of another kind")
          (doseq [kind ["\"intent\"" "intent" ":intent"]]
            (is (some? (in c (str "bb nido:workstream:entry:add :kind " kind)))
                (str "a kind the task reads as a person's, written " kind)))
          (is (nil? (in c "bb nido:seats:send :to 1")) "a verb that is no person's act")
          (is (nil? (in c "bb nido:review:design")) "a design round, whose carried grant grants nothing new")
          (doseq [cmd ["jj git push -b main" "git push origin main" "gh pr create" "gh pr create --draft" "gh pr ready 3"]]
            (is (nil? (in c cmd)) (str "pushing and opening a PR are allowed: " cmd)))
          (is (some? (in c "gh pr merge 3")) "a merge is the person's")
          (is (some? (in c "gh api -X PUT repos/o/r/pulls/1/merge")) "a merge through gh api")
          (is (some? (in c "gh pr \"$V\" 3")) "a gh verb the gate cannot read may be a merge")
          (testing "a seat"
            (is (nil? (in "fix-it" "gh pr merge 3")) "on a person's own turn")
            (driving id "fix-it" "conv" {:token "t" :at "t" :via :send :seen? false})
            (is (some? (in "fix-it" "gh pr merge 3")) "on the conductor's instruction")
            (is (nil? (in-session "fix-it" #(conductor/refusal {:tool_name "Bash" :cwd "/wt" :session_id "another"
                                                                 :tool_input {:command "gh pr merge 3"}})))
                "not in a person's other conversation in the same folder")
            (is (some? (in-session "fix-it" #(conductor/refusal {:tool_name "Bash" :cwd "/wt" :session_id "conv"
                                                                  :tool_input {:command "gh pr merge 3"}})))
                "but in the driven one")
            (driving id "fix-it" "conv" {:token "t" :at "t" :via :send :seen? false :for "run-1"})
            (is (nil? (in "fix-it" "gh pr merge 3")) "on a Run's instruction, the Run's own gates hold")
            (driving id "fix-it" "conv" {:token "t" :at "t" :via :dispatch :seen? true :given-by :start})
            (is (nil? (in "fix-it" "bb nido:workstream:entry:add :kind intent"))
                "on the first instruction its start gave — a spawn's brief — it writes the intent the brief states")))))))

(deftest a-start-gives-its-first-instruction-unless-the-conductor-asked-for-it
  (with-tmp
    (fn []
      (let [[id] (workstream)
            given (atom [])]
        (session/create! :brian id {:name "fix-it" :weight :heavy :autonomy nil})
        (session/create! :brian id {:name "parent" :weight :heavy :autonomy nil})
        (launching [_]
          (with-redefs [conduct/start! (fn [o] (swap! given conj (:given-by o))
                                         (session/update-substrate! (:project o) (:ws-id o) (:session-name o)
                                                                    (fn [m] (assoc m :dispatching {:token "t" :at "now"})))
                                         {})]
            (conductor/carry! :brian id "fix-it" "the brief" nil :start)
            (is (= [:start] @given) "the start's word reaches the conversation's first instruction")
            (is (= :start (:given-by (:launched (session/read-session :brian id "fix-it"))))
                "and is kept for a retried launch")))
        (is (= :start (in-session nil #(conductor/starter "/elsewhere"))) "a start from outside every session")
        (is (= :start (in-session "parent" #(conductor/starter "/wt"))) "a start from a session nido does not drive")
        (driving id "parent" "conv" {:token "t" :at "t" :via :dispatch :seen? true :given-by :start})
        (is (= :start (in-session "parent" #(conductor/starter "/wt"))) "a start from a turn its own start gave")
        (driving id "parent" "conv" {:token "t" :at "t" :via :send :seen? true})
        (is (nil? (in-session "parent" #(conductor/starter "/wt")))
            "a start made on the conductor's instruction hands its child nothing the gate refuses the conductor")))))

(deftest a-hand-back-is-work-and-a-conductor-on-no-work-is-retired
  (with-tmp
    (fn []
      (let [[old]  (workstream)
            [idle] (workstream)
            since  (str (fs/path (core/nido-root) "coordinator" "conductors-since.edn"))]
        (session/create! :brian old {:name "long-ago" :weight :heavy :autonomy nil})
        (session/write! (assoc (session/read-session :brian old "long-ago") :created-at "2020-01-01T00:00:00Z"))
        (io/write-edn! (doto since (-> fs/parent fs/create-dirs)) {:since "2025-01-01T00:00:00Z"})
        (launching [_]
          (is (= [] (conductor/conductors! :brian)) "a session from before is no work since")
          (conductor/hand-back! :brian old "long-ago")
          (is (= [{:ws-id old :act :started :session (str old "--conductor")}] (conductor/conductors! :brian))
              "an instruction nido gave there, handed back, is")
          (testing "a conductor running where no work is"
            (session/create! :brian idle {:name (str idle "--conductor") :weight :heavy :autonomy nil :conductor true})
            (session/write! (assoc (session/read-session :brian idle (str idle "--conductor")) :dispatching {:token "t" :at "now"}))
            (let [retired (atom [])]
              (with-redefs [seats/retire-conductor! (fn [_ id] (swap! retired conj id) {:session (str id "--conductor")})]
                (conductor/conductors! :brian))
              (is (= [idle] @retired)))))))))

(deftest a-handed-back-hold-carries-its-last-message
  (with-tmp
    (fn []
      (let [[id] (workstream)]
        (launching [_] (seats/conductor! :brian id "conduct it"))
        (session/create! :brian id {:name "fix-it" :weight :heavy :autonomy nil})
        (driving id "fix-it" "conv-1" nil)
        (session-events/append! "conv-1" {:event :stop :last-message "the person's work is done"})
        (conductor/hand-back! :brian id "fix-it")
        (is (some #(str/includes? (str (:text %)) "the person's work is done")
                  (filter #(= :hand-back (:kind %)) (seats/traffic :brian id 1))))))))

(def ^:private a-baseline
  {:format :baseline :strata [] :intent {:seq 1}
   :area "order totalling" :bounded-by "money on an order" :shape "one summing path"
   :model {:elements [{:id "agg" :sort :module :hides "the summing order" :interface "a total"}]
           :claims [{:id "c1" :about ["agg"] :statement "the aggregate is the only summing path"
                     :falsified-by "a second path that sums lines" :evidence {:by :round}
                     :read-at ["src/a.clj:1"]}]}
   :read ["src/a.clj"]})

(deftest a-carried-grant-is-no-act-of-the-persons
  (with-tmp
    (fn []
      (let [[id add] (workstream)
            c        (str id "--conductor")
            _        (add :baseline a-baseline)
            _        (add :baseline-review {:format :baseline-review :verdict :sufficient :baseline-seq 2 :reason "ok"})
            d        (add :design {:format :design :strata [] :summary "s" :shape "sh"
                                   :model {:elements [{:id "agg" :sort :module}]
                                           :claims [{:id "one" :about ["agg"] :statement "one path"
                                                     :falsified-by "a second" :evidence {:by :round}}]}
                                   :standing {:relation :conforms} :baseline {:seq 2 :relation :within}
                                   :intent {:seq 1} :effort :S})]
        (launching [_] (seats/conductor! :brian id "conduct it"))
        (with-redefs [pipeline/of (constantly {:at :designed :next {:stage :approve-design :mode :human}})]
          (let [hb (:id (conductor/hand-back! :brian id c))]
            (in-session c #(conductor/decide! :brian id {:hand-back hb :decision [:escalate {:needs "approve it"}]})))
          (Thread/sleep 5)
          (let [granted (add :design-approved {:format :design-approved :design {:seq d} :at-seq d :carried true})]
            (conductor/answers! :brian)
            (is (empty? (ws/entries-of :brian id :blocker-answered)) "a carried grant answers no blocker")
            (is (not-any? #(str/includes? (str (:text %)) (str "entry " granted))
                          (filter #(= :hand-back (:kind %)) (seats/traffic :brian id 1)))
                "nor is it handed back as the person's act"))
          (let [granted (add :design-approved {:format :design-approved :design {:seq d} :at-seq d})]
            (conductor/answers! :brian)
            (is (= [granted] (keep (comp :seq :act) (ws/entries-of :brian id :blocker-answered)))
                "the person's grant of the design the escalation found answers it")))))))

(deftest answering-the-blocker-found-open-answers-the-escalation-at-it
  (with-tmp
    (fn []
      (let [[id add] (workstream)
            c        (str id "--conductor")
            b        (add :blocker {:format :blocker :summary "stopped" :needs "a decision about x"})]
        (launching [_] (seats/conductor! :brian id "conduct it"))
        (with-redefs [pipeline/of (constantly {:at :blocked :next {:stage :answer-blocker :mode :human}})]
          (let [hb (:id (conductor/hand-back! :brian id c))]
            (in-session c #(conductor/decide! :brian id {:hand-back hb :decision [:escalate {:needs "answer it"}]})))
          (let [escalated (:seq (last (ws/entries-of :brian id :blocker)))
                answer    (add :blocker-answered {:format :blocker-answered :blocker-seq b :summary "do y" :resumed nil})]
            (conductor/answers! :brian)
            (is (= [[escalated answer]]
                   (keep #(when (:act %) [(:blocker-seq %) (get-in % [:act :seq])])
                         (ws/entries-of :brian id :blocker-answered)))
                "the person's answer to the blocker the escalation found answers the escalation's")))))))

(deftest only-an-answer-to-the-blocker-the-escalation-found-answers-it
  (with-tmp
    (fn []
      (let [[id add] (workstream)
            c        (str id "--conductor")
            b1       (add :blocker {:format :blocker :summary "first" :needs "a decision about x"})
            b2       (add :blocker {:format :blocker :summary "second" :needs "a decision about y"})]
        (launching [_] (seats/conductor! :brian id "conduct it"))
        (with-redefs [pipeline/of (constantly {:at :blocked :next {:stage :answer-blocker :mode :human}})]
          (let [hb (:id (conductor/hand-back! :brian id c))]
            (in-session c #(conductor/decide! :brian id {:hand-back hb :decision [:escalate {:needs "answer it"}]})))
          (let [escalated (:seq (last (ws/entries-of :brian id :blocker)))
                acts      #(keep (fn [a] (when (:act a) [(:blocker-seq a) (get-in a [:act :seq])]))
                                 (ws/entries-of :brian id :blocker-answered))]
            (add :blocker-answered {:format :blocker-answered :blocker-seq b1 :summary "x" :resumed nil})
            (conductor/answers! :brian)
            (is (empty? (acts)) "answering an older blocker answers none the escalation found")
            (let [answer (add :blocker-answered {:format :blocker-answered :blocker-seq b2 :summary "y" :resumed nil})]
              (conductor/answers! :brian)
              (is (= [[escalated answer]] (acts)) "answering the one it found does"))))))))

(deftest an-act-that-closed-its-workstream-still-answers-and-is-handed-back
  (with-tmp
    (fn []
      (let [[id add] (workstream)
            c        (str id "--conductor")]
        (launching [_] (seats/conductor! :brian id "conduct it"))
        (with-redefs [pipeline/of (constantly {:at :published :next {:stage :land :mode :human}})]
          (let [hb (:id (conductor/hand-back! :brian id c))]
            (in-session c #(conductor/decide! :brian id {:hand-back hb :decision [:escalate {:needs "land it"}]}))))
        (Thread/sleep 5)
        (let [merged (add :merged {:format :merged :commit "abc" :url "u" :title "t"})]
          (ws/close! :brian id :done)
          (conductor/answers! :brian)
          (is (= [merged] (keep (comp :seq :act) (ws/entries-of :brian id :blocker-answered)))
              "the landing recorded before the close answers the escalation at :land")
          (is (some #(str/includes? (str (:text %)) (str "entry " merged))
                    (filter #(= :hand-back (:kind %)) (seats/traffic :brian id 1)))
              "and is handed back to the conductor"))))))

(deftest only-the-acceptance-of-the-verdict-found-answers-it
  (with-tmp
    (fn []
      (let [[id add] (workstream)
            c        (str id "--conductor")
            report   {:format :triage-report :ticket-key "BR-1" :determination :bug
                      :title "Verdict" :summary "bug — reproduced." :confidence {:level :high :reason "r"}
                      :routing nil :directions [] :notion-writes nil :trail []}
            r1       (add :triage report)
            r2       (add :triage report)]
        (launching [_] (seats/conductor! :brian id "conduct it"))
        (with-redefs [pipeline/of (constantly {:at :triage-awaiting :next {:stage :accept-triage :mode :human}})]
          (let [hb (:id (conductor/hand-back! :brian id c))]
            (in-session c #(conductor/decide! :brian id {:hand-back hb :decision [:escalate {:needs "accept it"}]}))))
        (add :triage-accepted {:format :triage-accepted :triage-seq r1})
        (conductor/answers! :brian)
        (is (empty? (ws/entries-of :brian id :blocker-answered)) "accepting an older verdict answers nothing")
        (let [accepted (add :triage-accepted {:format :triage-accepted :triage-seq r2})]
          (conductor/answers! :brian)
          (is (= [accepted] (keep (comp :seq :act) (ws/entries-of :brian id :blocker-answered)))))))))

(deftest a-gate-answers-under-the-paused-plan-that-governs
  ;; A design appended before the phase closed is not the plan that close paused: the gate a person
  ;; asserts cites the paused plan, and that is the design the escalation at :assert-gate found.
  (with-tmp
    (fn []
      (let [[id add] (workstream)
            design   {:format :design :strata [] :summary "s" :shape "sh"
                      :model {:elements [{:id "agg" :sort :module}]
                              :claims [{:id "one" :about ["agg"] :statement "one path"
                                        :falsified-by "a second" :evidence {:by :round}}]}
                      :standing {:relation :conforms} :baseline {:seq 2 :relation :within}
                      :intent {:seq 1} :effort :S}
            _        (add :baseline a-baseline)
            d1       (add :design (assoc design
                                         :holds {"one" :on-completion}
                                         :phases [{:claim "both" :habitable "h" :exit {:kind :soak :criterion "a week"}
                                                   :undo {:how :revert :by "r"}}
                                                  {:claim "old gone" :habitable "h" :exit {:kind :completion :criterion "done"}
                                                   :undo {:how :none :why "gone"}}]))
            _        (add :design-approved {:format :design-approved :design {:seq d1} :at-seq d1})
            d2       (add :design (assoc design :summary "a later design"))
            _        (ws/close! :brian id :between-phases d1)
            blocker  (add :blocker {:format :blocker :summary "s" :needs "assert the gate"})
            takes?   #(#'conductor/takes-step? :brian id {:seq blocker} :assert-gate
                       {:format :phase-gate :design {:seq %}})]
        (is (takes? d1) "the gate under the paused plan takes the step")
        (is (not (takes? d2)) "one under the newest design does not")))))

(deftest an-older-escalation-is-answered-by-its-own-step-while-a-newer-one-is-open
  (with-tmp
    (fn []
      (let [[id add] (workstream)
            c        (str id "--conductor")
            escalate (fn [at next]
                       (with-redefs [pipeline/of (constantly {:at at :next next})]
                         (let [hb (:id (first (conductor/hand-backs!
                                                {:turn-ends [{:project :brian :ws-id id :session "fix-it"
                                                              :event {:last-message "done"}
                                                              :instruction {:token (name at)} :run nil}]})))]
                           (in-session c #(conductor/decide! :brian id {:hand-back hb :decision [:escalate {:needs "x"}]})))
                         (Thread/sleep 5)
                         (:seq (last (ws/entries-of :brian id :blocker)))))]
        (launching [_] (seats/conductor! :brian id "conduct it"))
        (session/create! :brian id {:name "fix-it" :weight :heavy :autonomy nil})
        (let [c1 (escalate :triage-awaiting {:stage :accept-triage :mode :human})
              c2 (escalate :blocked {:stage :answer-blocker :mode :human})
              _  (is (< c1 c2) "two escalations stand open")
              accepted (add :triage-accepted {:format :triage-accepted})]
          (conductor/answers! :brian)
          (is (= [[c1 accepted]] (keep #(when (:act %) [(:blocker-seq %) (get-in % [:act :seq])])
                                       (ws/entries-of :brian id :blocker-answered)))
              "the acceptance answers the escalation made at accepting the triage, though a newer one is open"))))))

(deftest a-reply-is-offered-only-for-the-escalation-it-answers
  (with-tmp
    (fn []
      (let [[id add] (workstream)
            c        (str id "--conductor")]
        (launching [_] (seats/conductor! :brian id "conduct it"))
        (let [hb (:id (conductor/hand-back! :brian id c))]
          (in-session c #(conductor/decide! :brian id {:hand-back hb :decision [:escalate {:needs "x"}]})))
        (is (conductor/awaiting-answer? :brian id) "a reply answers the escalation placing the pipeline")
        (add :blocker {:format :blocker :summary "reseat failed" :needs "a session"})
        (is (not (conductor/awaiting-answer? :brian id))
            "under a newer blocker no conductor's, a reply has no escalation to answer")
        (is (nil? (conductor/answer! :brian id "go")) "as answer! records none")))))
