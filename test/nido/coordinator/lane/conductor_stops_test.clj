(ns nido.coordinator.lane.conductor-stops-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.platform.process :as process]
   [nido.coordinator.agent.substrate :as substrate]
   [nido.coordinator.lane.conduct :as conduct]
   [nido.coordinator.lane.conductor :as c]
   [nido.coordinator.lane.drive :as drive]
   [nido.coordinator.lane.pipeline :as pipeline]
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

(defn- dialog-blockers [id] (filter #(= :dialog (get-in % [:stop :kind])) (ws/entries-of :brian id :blocker)))

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

(defn- design [baseline-seq & [sup]]
  (cond-> {:format :design :strata [] :summary "s" :shape "sh"
           :model {:elements [{:id "m" :sort :module}]
                   :claims [{:id "d1" :about ["m"] :statement "p" :falsified-by "f" :evidence {:by :round}}]}
           :standing {:relation :conforms}
           :baseline {:seq baseline-seq :relation :within}
           :intent {:seq 1} :effort :S}
    sup (assoc :supersedes {:seq sup :why "repaired"})))

(defn- add! [id kind record & [amended-by]]
  (if (= :design-decision kind) (ws/append-decision! :brian id (pr-str record)) (ws/append-entry! :brian id (cond-> {:kind kind} amended-by (assoc :amended-by amended-by)) (pr-str record)))
  (count (:entries (ws/read-ws :brian id))))

(defn- halt! [id stage outcome]
  (:seq (drive/park! :brian id {:stage stage :outcome outcome :tried [{:stage stage :outcome outcome}]})))

(defn- seat-repairs-a-design-the-round-asked-about
  "nido ws-20261117-df1d96 entries 41-47, replayed."
  []
  (with-tmp
    (fn [_]
      (with-redefs [conduct/live-states (constantly {})]
        (let [id      (workstream! {} true)
              b       (add! id :baseline (baseline 1))
              _       (add! id :baseline-review {:format :baseline-review :verdict :sufficient :baseline-seq b :reason "it holds"})
              d       (add! id :design (design b))
              _       (add! id :design-decision {:format :design-decision :recommend :amend :design-seq d :reason "r"
                                                 :checks [{:check :relation-honest :status :held :note "n"}]
                                                 :findings [{:cites ["goal-served"] :claim "the bound does not hold"}]
                                                 :asks "Should we build it at medium effort?"})
              amended (add! id :design (design b d) "design-loop-x")
              blocker (halt! id :decide-design :asked)
              owed    #(select-keys (:next (pipeline/of :brian id)) [:stage :mode])]
          (is (< amended blocker) "the round's amendment precedes its halt")
          (is (= {:stage :answer-blocker :mode :human} (owed)))
          (pass! 1)
          (is (empty? (answered-by-nido id)) "with nothing after it, the person owes the answer")
          (let [repaired (add! id :design (design b amended))]
            (pass! 2)
            (is (= #{blocker} (answered-by-nido id)) "the seat's repaired design answers it")
            (is (str/includes? (:summary (first (ws/entries-of :brian id :blocker-answered))) (str "design at entry " repaired)))
            (is (= {:stage :decide-design :mode :mechanical} (owed)) "and the round runs on it, no person's click")
            (is (empty? (ws/entries-of :brian id :design-approved)) "granting nothing")
            (pass! 3)
            (is (= 1 (count (ws/entries-of :brian id :blocker-answered))) "once")))))))

(deftest a-review-stage-s-halt-is-answered-once-a-reply-replaces-what-it-reviewed
  ;; nido ws-20261116-a5116e, entries 6-8: verify-baseline timed out, the corrected baseline was
  ;; appended next, and the timeout still asked the person how the stage should go on.
  ;; nido ws-20261117-df1d96, entries 41-47: decide-design stopped at asked, its amendment (42) and
  ;; blocker (43) were appended, the seat appended the repaired design (44) — and a person was
  ;; parked half an hour on a card that asked nothing.
  (with-tmp
    (fn [_]
      (with-redefs [conduct/live-states (constantly {})]
        (let [id (workstream! {} true)
              b  (add! id :baseline (baseline 1))]
          (testing "a machinery halt is answered by a newer baseline, saying why"
            (let [timeout (halt! id :verify-baseline :timed-out)]
              (pass! 1)
              (is (not (contains? (answered-by-nido id) timeout)) "while what it reviewed is the newest, it stands")
              (add! id :baseline (assoc (baseline 1) :supersedes {:seq b :why "corrected"}))
              (pass! 2)
              (is (contains? (answered-by-nido id) timeout))
              (is (str/includes? (:summary (first (ws/entries-of :brian id :blocker-answered))) "timed-out"))))
          (testing "a judgement halt: the round's own amendment is no reply, a session's newer baseline is"
            (let [prev    (:seq (last (ws/entries-of :brian id :baseline)))
                  judged  (halt! id :verify-baseline :disputed)
                  _       (add! id :baseline (assoc (baseline 1) :supersedes {:seq prev :why "loop"}) "baseline-loop-x")
                  _       (pass! 3)
                  _       (is (not (contains? (answered-by-nido id) judged)) "an amender-stamped baseline answers nothing")
                  reply   (add! id :baseline (assoc (baseline 1) :supersedes {:seq prev :why "the seat's"}))]
              (pass! 4)
              (is (contains? (answered-by-nido id) judged))
              (is (str/includes? (:summary (some #(when (= judged (:blocker-seq %)) %) (ws/entries-of :brian id :blocker-answered)))
                                 (str "entry " reply)))))
          (testing "a design halt is not answered by a newer baseline"
            (let [last-b (:seq (last (ws/entries-of :brian id :baseline)))
                  d      (halt! id :decide-design :usage-limited)]
              (add! id :baseline (assoc (baseline 1) :supersedes {:seq last-b :why "again"}))
              (pass! 5)
              (is (not (contains? (answered-by-nido id) d)))))))))
  (testing "a design round stopped at asked runs on the seat's repaired design, granting nothing"
    (seat-repairs-a-design-the-round-asked-about)))

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

;; ── a turn whose process is gone ────────────────────────────────────────────

(def ^:private began "2026-11-13T14:40:00Z")
(def ^:private started "2026-11-13T14:39:00Z")

(defn- open-turn
  "One of conduct's open turns on workstream `id`: session worker's conversation c-1, begun at
   `began`, its latest start having recorded pid 4242 at `started`; `over` replaces what it says."
  [id & [over]]
  (merge {:project :brian :ws-id id :session-name "worker" :conversation "c-1"
          :since began :pid "4242" :cwd "/w/worker" :started-at started}
         over))

(defn- dead-turn-blockers [id] (filter #(= :dead-turn (get-in % [:stop :kind])) (ws/entries-of :brian id :blocker)))

(defn- with-witnesses
  "Run `f` with conduct holding `turns` open, the listing read for the check answering `listing`
   (a function of its opts) and the process under any pid having started at `process`
   (epoch ms, :gone or :unknown). The display cache answers a live process for every
   conversation, so a check that read it would find no turn dead."
  [{:keys [turns listing process]} f]
  (with-redefs [conduct/open-turns     (fn [_] turns)
                conduct/live-states    (constantly {"c-1" {:sessionId "c-1" :pid 1 :cwd "/w/worker"}})
                substrate/listing      (or listing (constantly []))
                substrate/dispatch!    (fn [& _] (throw (ex-info "a dead turn starts nothing" {})))
                substrate/send!        (fn [& _] (throw (ex-info "a dead turn messages nothing" {})))
                substrate/wake!        (fn [& _] (throw (ex-info "a dead turn wakes nothing" {})))
                substrate/respawn!     (fn [& _] (throw (ex-info "a dead turn restarts nothing" {})))
                process/process-started (constantly (or process :gone))]
    (f)))

(deftest a-turn-whose-process-is-gone-is-put-to-the-person-once-with-what-resumes-it
  ;; nido ws-20261116-a5116e: cross-repo-workstreams' process exited inside a turn at 09:39 on
  ;; 2026-11-17 and the workstream read healthy for 6h24m.
  (with-tmp
    (fn [_]
      (let [id (workstream! {:driver :nobody} true)]
        (with-witnesses {:turns [(open-turn id)]}
          (fn []
            (testing "the first pass that finds it dead asks"
              (is (= [:blocker] (map :wrote (pass! 1))))
              (let [[b] (dead-turn-blockers id)]
                (is (= {:kind :dead-turn :session "worker" :since began} (:stop b)))
                (is (str/includes? (:summary b) "c-1") "it names the conversation")
                (is (str/includes? (:summary b) began) "and when the turn began")
                (is (str/includes? (:summary b) "No end of the session was recorded"))
                (is (str/includes? (:summary b) "cd '/w/worker' && claude --resume 'c-1' — with no other flag")
                    "and the command that reopens that conversation, flagless, in the directory it ran in")
                (is (str/ends-with? (:needs b) "?"))))
            (testing "the workstream is a person's move"
              (is (= :person (:owed-by ((requiring-resolve 'nido.coordinator.lane.pipeline/status) :brian id)))))
            (testing "a later pass asks nothing more"
              (is (= [] (pass! 2)))
              (is (= 1 (count (dead-turn-blockers id)))))
            (testing "nothing is handed to a conductor for it"
              (is (empty? (seats/hand-backs :brian id))))))))))

(deftest a-recorded-end-is-named-with-its-reason
  (with-tmp
    (fn [_]
      (let [id (workstream! {:driver :nobody} true)]
        ((requiring-resolve 'nido.coordinator.record.session-events/append!)
         "c-1" {:event :session-end :reason "other" :at "2026-11-13T14:41:00Z"})
        (with-witnesses {:turns [(open-turn id)]}
          (fn []
            (pass! 1)
            (is (str/includes? (:summary (first (dead-turn-blockers id)))
                               "ending at 2026-11-13T14:41:00Z with reason `other`"))))))))

(deftest a-turn-is-dead-only-when-every-witness-says-its-process-is-gone
  (let [recorded (.toEpochMilli (java.time.Instant/parse started))]
    (doseq [[why witnesses dead?]
            [["no row, the recorded pid gone" {} true]
             ["no row, no pid recorded: the listing is the only witness" {:over {:pid nil :started-at nil}} true]
             ["no row, the pid given to a process started after the recorded start" {:process (+ recorded 60000)} true]
             ["a row with a process for the conversation"
              {:listing (constantly [{:sessionId "c-1" :pid 9 :cwd "/w/worker"}])} false]
             ["a row for the conversation with no process, the pid gone"
              {:listing (constantly [{:sessionId "c-1" :kind "background" :cwd "/w/worker"}])} true]
             ["the recorded pid running as a process started before the recorded start" {:process (- recorded 1000)} false]
             ["the recorded pid running, its start unreadable" {:process :unknown} false]
             ["the recorded pid running, the start's recording instant unreadable"
              {:process recorded :over {:started-at "not a time"}} false]
             ["a recorded pid that is no pid" {:over {:pid "abc"}} false]
             ["a listing that cannot be read" {:listing (fn [_] (throw (ex-info "boom" {})))} false]]]
      (testing why
        (with-tmp
          (fn [_]
            (let [id (workstream! {:driver :nobody} true)]
              (with-witnesses (assoc witnesses :turns [(open-turn id (:over witnesses))])
                (fn []
                  (pass! 1)
                  (is (= (if dead? 1 0) (count (dead-turn-blockers id)))))))))))))

(deftest only-a-person-s-turn-on-a-workstream-being-worked-is-asked-about
  (let [plain  #(workstream! {:driver :nobody} true)
        beside (fn [n opts] (fn [] (let [id (plain)]
                                     (sess/create! :brian id (merge {:name n :weight :light :autonomy nil} opts))
                                     id)))]
    (doseq [[why make session asked?]
            [["an activated, open workstream's seat" plain "worker" true]
             ["a workstream not activated" #(workstream! {:driver :nobody} false) "worker" false]
             ["a paused workstream" #(let [id (plain)] (ws/pause! :brian id nil) id) "worker" false]
             ["a closed workstream" #(let [id (plain)] (ws/close! :brian id :done) id) "worker" false]
             ["the conductor's session" (beside "conductor" {:conductor true}) "conductor" false]
             ["the chat's session" (beside "chat" {:chat true}) "chat" false]
             ["a conversation holding a Run's instruction"
              #(workstream! {:session-id "c-1" :pending {:token "T" :at t0 :via :send :seen? true :for "run-1"}} true)
              "worker" false]
             ["a conversation whose Run's instruction lapsed, a late prompt delivering it"
              #(workstream! {:session-id "c-1" :pending {:token "U" :at t0 :via :send :seen? false}
                             :lapsed [{:token "T" :at t0 :via :send :seen? true :for "run-1"}]} true)
              "worker" false]
             ["a driven conversation holding an instruction given for no Run"
              #(workstream! {:session-id "c-1" :pending {:token "T" :at t0 :via :send :seen? true}} true)
              "worker" true]]]
      (testing why
        (with-tmp
          (fn [_]
            (let [id (make)]
              (with-witnesses {:turns [(open-turn id {:session-name session})]}
                (fn []
                  (pass! 1)
                  (is (= (if asked? 1 0) (count (dead-turn-blockers id)))))))))))))

(deftest a-dead-turn-on-a-paused-workstream-is-found-once-the-pause-is-lifted
  (with-tmp
    (fn [_]
      (let [id (workstream! {:driver :nobody} true)]
        (ws/pause! :brian id nil)
        (with-witnesses {:turns [(open-turn id)]}
          (fn []
            (pass! 1)
            (is (empty? (dead-turn-blockers id)))
            (ws/resume! :brian id)
            (pass! 2)
            (is (= 1 (count (dead-turn-blockers id))))))))))

(deftest nido-answers-a-dead-turn-s-blocker-once-it-no-longer-stands
  (doseq [[why after] [["conduct no longer holds the turn open" {:turns []}]
                       ["a witness shows its process"
                        {:listing (constantly [{:sessionId "c-1" :pid 9 :cwd "/w/worker"}])}]]]
    (testing why
      (with-tmp
        (fn [_]
          (let [id (workstream! {:driver :nobody} true)]
            (with-witnesses {:turns [(open-turn id)]} #(pass! 1))
            (testing "while it stands, nido answers nothing"
              (with-witnesses {:turns [(open-turn id)]} #(pass! 2))
              (is (empty? (answered-by-nido id))))
            (with-witnesses (merge {:turns [(open-turn id)]} after)
              (fn []
                (is (= [:blocker-answered] (map :wrote (pass! 3))))
                (is (= (set (map :seq (dead-turn-blockers id))) (answered-by-nido id)))
                (testing "and it is answered once"
                  (is (= [] (pass! 4))))))))))))

(deftest an-undecided-witness-neither-asks-nor-answers
  (with-tmp
    (fn [_]
      (let [id (workstream! {:driver :nobody} true)]
        (with-witnesses {:turns [(open-turn id)]} #(pass! 1))
        (with-witnesses {:turns [(open-turn id)] :listing (fn [_] (throw (ex-info "boom" {})))}
          (fn []
            (is (= [] (pass! 2)))
            (is (empty? (answered-by-nido id)) "the blocker written stands")))))))

(deftest a-dead-turn-s-closing-is-answered-on-a-workstream-no-reading-names-any-more
  ;; A plain session has no facet, and once its turn closes it is among no open turns: only the
  ;; ask itself brings a pass back to the workstream.
  (with-tmp
    (fn [_]
      (let [id (:id (ws/create! :brian {:external-refs []}))]
        (with-redefs [clock/now-iso (constantly t0)]
          (ws/append-entry! :brian id {:kind :intent} (pr-str {:format :intent :goal "g" :done-when ["d"]}))
          (ws/activate! :brian id nil))
        (sess/create! :brian id {:name "worker" :weight :light :autonomy nil})
        (with-witnesses {:turns [(open-turn id)]} #(pass! 1))
        (is (= 1 (count (dead-turn-blockers id))) "a session with no facet is asked about")
        (with-witnesses {:turns []}
          (fn []
            (is (= [:blocker-answered] (map :wrote (pass! 2))))
            (is (= [] (pass! 3)))))))))

(deftest a-dialog-s-closing-answers-no-dead-turn
  (with-tmp
    (fn [_]
      (let [id (workstream! {} true)]
        (with-witnesses {:turns [(open-turn id)]}
          (fn []
            (pass! 1)
            (pass! 2)
            (is (empty? (answered-by-nido id))
                "the dialog reading, which finds this session waiting on none, leaves it alone")))))))

(deftest a-pass-that-comes-late-says-how-late
  ;; The gap is measured when stops! passes, not at an instant the tick took before it: every
  ;; pass here is handed the same old instant, as a tick whose conduct read ran long would be.
  (with-tmp
    (fn [_]
      (let [said    #(let [w (java.io.StringWriter.)]
                       (reset! @#'c/!last-stops-pass (if % {:brian (- (System/currentTimeMillis) %)} {}))
                       (binding [*err* w] (pass! 0))
                       (str w))
            minutes #(* % 60 1000)]
        (is (= "" (said nil)) "a daemon's first pass has nothing to be late after")
        (is (= "" (said (minutes 1))) "a minute apart is inside the bound")
        (is (str/includes? (said (minutes 3)) "came 180s after the previous pass")
            "three minutes after the previous pass, whatever instant the tick began at")))))

(deftest the-resume-command-quotes-what-it-was-recorded-with
  (with-tmp
    (fn [_]
      (let [id (workstream! {:driver :nobody} true)]
        (with-witnesses {:turns [(open-turn id {:cwd "/Users/pat/Pat's project"})]}
          (fn []
            (pass! 1)
            (is (str/includes? (:summary (first (dead-turn-blockers id)))
                               "cd '/Users/pat/Pat'\"'\"'s project' && claude --resume 'c-1'")
                "an apostrophe in the directory leaves the command one a shell runs")))))))
