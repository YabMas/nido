(ns nido.coordinator.work-core-test
  "What a surface shows as awaiting the person is exactly the open operator asks the core holds,
   and what a person does at a gate is their keyed command to the core."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.standing :as standing]
   [nido.core.domain :as d]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.record.workstream :as workstream]
   [nido.coordinator.view.activity :as act]
   [nido.coordinator.work :as work]
   [nido.coordinator.work.core :as wcore]
   [nido.ui.components.state :as ui-state]
   [nido.platform.core :as core]
   [nido.platform.project :as project]
   [nido.test.fake-core :as fake-core]))

(defn- with-ws
  "Call `(f core ws-id)` with a fresh nido home holding one brian workstream, and a fake core."
  [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))
                    project/list-projects (fn [] {:brian {:directory (str tmp)}})]
        (cstate/ensure-dirs!)
        (let [w (workstream/create! :brian {:external-refs []})]
          (workstream/append-entry! :brian (:id w) {:kind :scratch} (pr-str {:format :scratch}))
          (fake-core/with-fake-core #(f % (:id w)))))
      (finally (fs/delete-tree tmp)))))

(defn- core-state [c ws-id] (get @(:states c) (str "brian/" ws-id)))

(deftest an-open-ask-is-the-gate-and-nothing-else-is
  (with-ws
    (fn [c ws-id]
      (testing "a blocker on the ledger awaits no one: only the core decides what is asked"
        (workstream/append-entry! :brian ws-id {:kind :blocker}
                                  (pr-str {:format :blocker :summary "stuck" :needs "what now?" :after "the round resumes"}))
        (is (empty? (work/gates :brian)))
        (is (not (:needs-you (first (work/list-workstreams :brian))))))
      (fake-core/seed! c :brian ws-id fake-core/asking)
      (let [[g & more] (work/gates :brian)]
        (is (nil? more))
        (is (= ws-id (:ws-id g)))
        (is (= "a1" (:core-ask g)))
        (is (= "Ship it?" (get-in g [:ask :question])))
        (is (= [["A" "Yes" nil] ["B" "No" nil]]
               (map (juxt :letter :label :action) (get-in g [:ask :questions 0 :options])))
            "one question is chosen, then sent: no option answers by itself")
        (is (= :answer-questions (get-in g [:ask :submit])))
        (is (= [:answer-questions :reply :withdraw] (map :id (:actions g))))
        (is (:notes? (first (:actions g))) "Send answers records a comment per question"))
      (is (:needs-you (first (work/list-workstreams :brian)))))))

(deftest a-letter-answers-the-ask-with-its-option
  (with-ws
    (fn [c ws-id]
      (fake-core/seed! c :brian ws-id fake-core/asking)
      (is (= {:decision :answered} (work/resolve-gate! :brian ws-id :option-a nil)))
      (is (= {:answered "Yes"} (get-in (core-state c ws-id) [:turns "c1" :resolution])))
      (is (every? :input? @(:sent c)) "a person's act is input, taken ahead of background commands")
      (is (empty? (work/gates :brian)) "answered, it awaits no one")
      (is (= :core-refused (:decision (work/resolve-gate! :brian ws-id :option-b nil)))
          "a second answer finds nothing asked"))))

(def ^:private several
  "An ask putting two decisions to the person, each with its own options."
  [{:command :open-unit :unit "u" :project "brian" :goal "g" :session-cwd "/w"}
   {:command :start-turn :turn "c1" :unit nil :spec {:executor :fake :instructions "i"}}
   {:command :record-outcome :turn "c1" :run 1 :outcome :asked :call {:id "call-1" :question "q?"}}
   {:command :raise-operator-ask :ask "a1" :binds #{"c1"}
    :content {:question "Two decisions about pictures" :explored "Read the code."
              :questions [{:question "Drop the question or keep it without its picture?"
                           :context "Creation drops it."
                           :options [{:option "Drop it" :consequence "Fewer questions." :recommended true}
                                     {:option "Keep it" :consequence "A missing diagram."}]
                           :recommendation "Drop it."}
                          {:question "Tell the teacher?"
                           :options [{:option "Tell them"} {:option "Stay silent"}]}]
              :recommendation "Drop it; tell them." :after "The design is written next."}}])

(deftest several-decisions-are-asked-one-question-each-and-sent-together
  (with-ws
    (fn [c ws-id]
      (fake-core/seed! c :brian ws-id several)
      (let [[g] (work/gates :brian)
            ask (:ask g)]
        (is (= "Two decisions about pictures" (:question ask)))
        (is (= ["Drop the question or keep it without its picture?" "Tell the teacher?"]
               (map :question (:questions ask))) "each decision is a question of its own")
        (is (= [["Read the code." "Creation drops it."] []] (map :context (:questions ask)))
            "what was explored leads the first question")
        (is (= [[["A" "Drop it" true] ["B" "Keep it" nil]] [["A" "Tell them" nil] ["B" "Stay silent" nil]]]
               (for [q (:questions ask)] (map (juxt :letter :label :recommended?) (:options q))))
            "the recommended branch is the one its writer marked, never the first by position")
        (is (every? nil? (mapcat #(map :action (:options %)) (:questions ask)))
            "no option answers the whole ask by itself")
        (is (= :answer-questions (:submit ask)))
        (is (= "The design is written next." (:after (last (:questions ask)))))
        (is (= [:answer-questions :reply :withdraw] (map :id (:actions g)))))
      (is (= :core-refused (:decision (work/resolve-gate! :brian ws-id :answer-questions {:answers [{} {}]})))
          "nothing chosen answers nothing")
      (is (= {:decision :answered}
             (work/resolve-gate! :brian ws-id :answer-questions {:answers [{:letter "A"} {:letter "B"}]})))
      (is (= {:answered (str "1. Drop the question or keep it without its picture? — Drop it\n"
                             "2. Tell the teacher? — Stay silent")}
             (get-in (core-state c ws-id) [:turns "c1" :resolution]))
          "with no note, the answer reads as the same choices always did"))))

(defn- answered-with
  "The answer text the core records when the ask `commands` raise is sent `picks` by Send answers,
   or the refusal's decision."
  [commands picks]
  (let [out (atom nil)]
    (with-ws
      (fn [c ws-id]
        (fake-core/seed! c :brian ws-id commands)
        (let [r (work/resolve-gate! :brian ws-id :answer-questions {:answers picks})]
          (reset! out (if (= :answered (:decision r))
                        (get-in (core-state c ws-id) [:turns "c1" :resolution :answered])
                        r)))))
    @out))

(deftest a-comment-is-said-under-its-own-question
  (testing "a note beside a choice, and a note with no choice, each under its own question"
    (is (= (str "1. Drop the question or keep it without its picture? — Drop it\n"
                "   The person's comment on question 1:\n"
                "     only for this release\n"
                "     and say so in the notes\n"
                "2. Tell the teacher? — no option chosen\n"
                "   The person's comment on question 2:\n"
                "     neither — ask the school first")
           (answered-with several [{:letter "A" :note "only for this release\nand say so in the notes"}
                                   {:note "neither — ask the school first"}]))))
  (testing "a note on one question leaves another's open line as it was"
    (is (= (str "1. Drop the question or keep it without its picture? — (left open)\n"
                "2. Tell the teacher? — Tell them\n"
                "   The person's comment on question 2:\n"
                "     by mail")
           (answered-with several [{} {:letter "A" :note "by mail"}]))))
  (testing "a note alone is an answer: the wait ends on it"
    (is (string? (answered-with several [{:note "this misses the point"} {}]))))
  (testing "blank words are no note, and nothing at all answers nothing"
    (is (= :core-refused (:decision (answered-with several [{:note "   "} {}]))))))

(deftest a-one-question-ask-is-answered-by-send-too
  (testing "with no note, the chosen option's label alone, as its option action answered"
    (is (= "Yes" (answered-with fake-core/asking [{:letter "A"}]))))
  (testing "with a note, the question, its choice and the comment"
    (is (= (str "1. Ship it? — no option chosen\n"
                "   The person's comment on question 1:\n"
                "     not before the demo")
           (answered-with fake-core/asking [{:note "not before the demo"}]))))
  (testing "nothing chosen and nothing said is refused"
    (is (= :core-refused (:decision (answered-with fake-core/asking [{}]))))))

(deftest a-single-decision-recommends-only-what-its-writer-marked
  (with-ws
    (fn [c ws-id]
      (fake-core/seed! c :brian ws-id
                       (conj (pop fake-core/asking)
                             {:command :raise-operator-ask :ask "a1" :binds #{"c1"}
                              :content {:question "Ship it?" :explored "x" :recommendation "No"
                                        :options ["Yes" {:option "No" :recommended true}]}}))
      (let [[g] (work/gates :brian)]
        (is (= [nil true] (map :recommended? (get-in g [:ask :questions 0 :options]))))
        (is (= [:primary :default] (map :style (take 2 (:actions g))))
            "Send answers is the one primary; the recommendation is marked on its option")))))

(deftest a-reply-answers-in-words-and-a-withdrawal-takes-the-ask-back
  (with-ws
    (fn [c ws-id]
      (fake-core/seed! c :brian ws-id fake-core/asking)
      (is (= {:decision :withdrawn} (work/resolve-gate! :brian ws-id :withdraw nil)))
      (is (= :withdrawn (get-in (core-state c ws-id) [:operator-asks "a1" :status])))))
  (with-ws
    (fn [c ws-id]
      (fake-core/seed! c :brian ws-id fake-core/asking)
      (is (= {:decision :answered} (work/resolve-gate! :brian ws-id :reply "  Only after the demo ")))
      (is (= {:answered "Only after the demo"} (get-in (core-state c ws-id) [:turns "c1" :resolution]))))))

(deftest land-grants-promote-opens-done-closes-pause-holds
  (with-ws
    (fn [c ws-id]
      (testing "promote is the person's start: the workstream's unit opened"
        (is (= {:decision :promoted} (work/set-stage! :brian ws-id :in-progress)))
        (is (= :open (get-in (core-state c ws-id) [:units ws-id :status]))))
      (testing "land grants nothing where nothing is landable as it stands"
        (is (= {:decision :nothing-to-land} (work/resolve-gate! :brian ws-id :land nil)))
        (is (not (contains? (:grants (core-state c ws-id)) :merge))))
      (testing "land grants the landing of a release candidate"
        (with-redefs [pipeline/of (constantly {:at :release-candidate})]
          (is (= {:decision :granted} (work/resolve-gate! :brian ws-id :land nil))))
        (is (contains? (:grants (core-state c ws-id)) :merge)))
      (testing "pause holds each open unit, and resume releases it"
        (work/pause! :brian ws-id "later")
        (is (get-in (core-state c ws-id) [:units ws-id :held?]))
        (is (get-in (first (work/list-workstreams :brian)) [:status :paused]))
        (work/resume! :brian ws-id)
        (is (not (get-in (core-state c ws-id) [:units ws-id :held?]))))
      (testing "done retracts the open unit and closes the workstream, in the core only"
        (is (= {:decision :done} (work/set-stage! :brian ws-id :done)))
        (is (:closed? (core-state c ws-id)))
        (is (nil? (workstream/closure (workstream/read-ws :brian ws-id))))
        (is (= :settled (:engagement (first (work/list-workstreams :brian)))))))))

(defn- reads
  "What each surface reads of the person's pause on `ws-id`: the pane, the board, and the core's
   hold on the open unit the conductor's turns start under."
  [c ws-id]
  {:pane  (some? (:pause (work/workstream :brian ws-id)))
   :board (boolean (get-in (first (work/list-workstreams :brian)) [:status :paused]))
   :held  (boolean (get-in (core-state c ws-id) [:units ws-id :held?]))})

(deftest pause-and-resume-move-every-surface-together
  (with-ws
    (fn [c ws-id]
      (work/set-stage! :brian ws-id :in-progress)
      (is (= {:pane false :board false :held false} (reads c ws-id)))
      (testing "the pane's Pause is read by the pane, the board and the core"
        (work/pause! :brian ws-id "after the demo")
        (is (= {:pane true :board true :held true} (reads c ws-id)))
        (is (= "after the demo" (:note (:pause (work/workstream :brian ws-id)))) "the note is kept"))
      (testing "the pane's Resume lifts it everywhere"
        (work/resume! :brian ws-id)
        (is (= {:pane false :board false :held false} (reads c ws-id))))
      (testing "a hold the core carries without a ledger pause still offers Resume on the pane"
        (work/deactivate! :brian ws-id nil)
        (is (= {:pane true :board true :held true} (reads c ws-id)))
        (work/resume! :brian ws-id)
        (is (= {:pane false :board false :held false} (reads c ws-id)))))))

(deftest resume-lifts-a-ledger-only-pause
  (with-ws
    (fn [c ws-id]
      (work/set-stage! :brian ws-id :in-progress)
      ;; The older path: a :paused on the ledger, and no unit held in the core.
      (workstream/pause! :brian ws-id nil)
      (is (= {:pane true :board true :held false} (reads c ws-id)))
      (work/resume! :brian ws-id)
      (is (= {:pane false :board false :held false} (reads c ws-id)))
      (is (nil? (workstream/pause (workstream/read-ws :brian ws-id)))))))

(deftest a-persons-word-answers-what-is-asked-or-reaches-the-conductor
  (with-ws
    (fn [c ws-id]
      (is (= {:answered nil :posted true :granted [] :refused []}
             (work/person-says! :brian ws-id "how is it going?" [])))
      (is (= ["how is it going?"] (map :text (:chat (core-state c ws-id)))))
      (fake-core/seed! c :brian ws-id (rest fake-core/asking))
      (is (= {:answered "a1" :posted false :granted [:merge] :refused []}
             (work/person-says! :brian ws-id "yes" [:merge]))))))

(deftest activation-opens-a-unit-whose-session-the-core-provisions
  (with-ws
    (fn [c ws-id]
      (let [r (work/activate! :brian ws-id)]
        (is (false? (:already? r)))
        (is (= {:status :none} (get-in (core-state c ws-id) [:units ws-id :session]))
            "no session already up is adopted: the core owes the unit one"))
      (is (:already? (work/activate! :brian ws-id)))
      (is (:was? (work/deactivate! :brian ws-id nil)))
      (is (get-in (core-state c ws-id) [:units ws-id :held?])))))

(deftest activation-given-a-brief-makes-it-the-first-turn
  (with-ws
    (fn [c ws-id]
      (work/activate! :brian ws-id {:brief "do the thing"})
      (is (= {:spec {:executor :claude :instructions "do the thing"}}
             (get-in (core-state c ws-id) [:units ws-id :first-turn]))))))

(deftest a-goal-start-opens-a-unit-named-for-its-session-with-the-brief-first
  (with-ws
    (fn [c _]
      (let [id (work/start-goal! :brian "widget" "Build the widget.")
            u  (get-in (core-state c id) [:units "widget"])]
        (is (not= id nil))
        (is (= {:status :none} (:session u)) "its session is the core's to provision")
        (is (= "Build the widget." (get-in u [:first-turn :spec :instructions])))
        (is (= [:activated] (map :kind (:entries (workstream/read-ws :brian id))))
            "minted with only the person's grant: the recipe runs on it, and no :scratch mark, so no reaper takes it for a one-off")))))

(deftest activation-records-the-grant-on-the-ledger
  (with-ws
    (fn [_c ws-id]
      (let [w          #(workstream/read-ws :brian ws-id)
            activated? #(standing/activated? (w))
            ;; what the core's code hold reads: the ledger's index rows
            held       #(d/code-held {:ledger (:entries (w))})
            at-open    (atom nil)
            act!       wcore/act!]
        (is (not (activated?)) "a one-off starts plain")
        (with-redefs [wcore/act! (fn [p id cmd & more]
                                   (when (= :open-unit (:command cmd)) (reset! at-open (activated?)))
                                   (apply act! p id cmd more))]
          (work/activate! :brian ws-id {:brief "Build it."}))
        (testing "activating writes the person's grant every recipe gate reads"
          (is (activated?))
          (is (= :design-not-cleared (held)) "so the unit's turns hold the code until a design is cleared"))
        (is (true? @at-open) "the grant is on the ledger before the unit — and its first turn — exists")
        (testing "re-activating records nothing twice"
          (let [n #(count (filter (comp #{:activated} :kind) (:entries (w))))
                before (n)]
            (work/activate! :brian ws-id)
            (is (= before (n)))))
        (testing "deactivating takes the grant back off the ledger"
          (work/deactivate! :brian ws-id nil)
          (is (standing/deactivated? (w)))
          (is (nil? (held))))))))

(deftest a-unit-opened-before-the-grant-was-recorded-gets-it-on-reactivation
  ;; Every activation between the core cut-over and this fix opened a unit and wrote no grant.
  (with-ws
    (fn [_c ws-id]
      (wcore/act! :brian ws-id {:command :open-unit :unit ws-id :project "brian" :goal "g"})
      (is (:already? (work/activate! :brian ws-id)))
      (is (standing/activated? (workstream/read-ws :brian ws-id))))))

(deftest a-workstream-the-core-works-unrecorded-reads-as-its-core-state
  (with-ws
    (fn [c ws-id]
      (let [row #(first (work/list-workstreams :brian))]
        (is (= :intake (get-in (row) [:position :at])) "nothing done anywhere: not started")
        (fake-core/seed! c :brian ws-id fake-core/asking)
        (is (= {:at :core-asking :next {:stage :answer-ask :mode :human}} (:position (row)))
            "the ledger records none of it, and the core asks the person")
        (work/resolve-gate! :brian ws-id :option-a nil)
        (fake-core/seed! c :brian ws-id
                         [{:command :start-turn :turn "u1" :unit "u" :spec {:executor :fake :instructions "i"}}])
        (is (= :core-under-way (get-in (row) [:position :at])) "a unit's turn ran: under way")))))

(deftest what-runs-is-read-from-the-core
  (with-ws
    (fn [c ws-id]
      (let [row    #(first (work/list-workstreams :brian))
            status #(select-keys (:status (row)) [:owed-by :live :stalled :paused])]
        (is (= {:live false :stalled false :paused false} (dissoc (status) :owed-by))
            "the core holds no unit: nothing of it is the core's to move, so nothing is stalled")
        (fake-core/seed! c :brian ws-id
                         [{:command :open-unit :unit "u" :project "brian" :goal "g" :session-cwd "/w"}])
        (testing "an open unit the core does nothing on and is owed nothing for is stalled"
          (is (= {:live false :stalled true :paused false} (dissoc (status) :owed-by)))
          (is (= :stalled (:engagement (row)))))
        (fake-core/seed! c :brian ws-id
                         [{:command :start-turn :turn "u1" :unit "u" :spec {:executor :fake :instructions "i"}}])
        (testing "a turn the core runs is live, and is what is underway"
          (is (= {:live true :stalled false :paused false} (dissoc (status) :owed-by)))
          (is (= {:source :core :activity :working :turn "u1" :unit "u"} (:doing (row))))
          (is (= :active (:engagement (row)))))
        (testing "a review's claim beside the running turn says what it is doing"
          (let [claim {:kind :design-round :run-id "r1"}]
            (with-redefs [act/observe (fn [& _] claim)]
              (is (= (assoc claim :source :claim) (:doing (row))))
              (is (= (assoc claim :source :claim) (:doing (work/workstream :brian ws-id)))
                  "and the pane says what the row says"))))
        (fake-core/seed! c :brian ws-id
                         [{:command :record-outcome :turn "u1" :run 1 :outcome :completed}
                          {:command :start-turn :turn "c1" :unit nil :spec {:executor :fake :instructions "i"}}
                          {:command :record-outcome :turn "c1" :run 1 :outcome :asked
                           :call {:id "call-1" :question "Ship it?" :explored "x" :options ["Yes" "No"]
                                  :recommendation "Yes"}}
                          {:command :raise-operator-ask :ask "a1" :binds #{"c1"}
                           :content {:question "Ship it?" :explored "x" :options ["Yes" "No"]
                                     :recommendation "Yes"}}])
        (testing "an ask the core holds owes the person; it is neither live nor stalled"
          (is (= {:owed-by :person :live false :stalled false :paused false} (status)))
          (is (:needs-you (row))))))))

(deftest a-held-unit-is-paused-not-stalled
  (with-ws
    (fn [c ws-id]
      (fake-core/seed! c :brian ws-id
                       [{:command :open-unit :unit "u" :project "brian" :goal "g" :session-cwd "/w"}
                        {:command :hold-unit :unit "u"}])
      (is (= {:live false :stalled false :paused true}
             (select-keys (:status (first (work/list-workstreams :brian))) [:live :stalled :paused]))))))

(deftest a-release-candidate-needs-you-as-a-landing
  (with-ws
    (fn [c ws-id]
      (fake-core/seed! c :brian ws-id
                       [{:command :open-unit :unit "u" :project "brian" :goal "g" :session-cwd "/w"}])
      (with-redefs [pipeline/of (constantly {:at :release-candidate :next {:stage :land :mode :release}})
                    nido.coordinator.record.standing/activated? (constantly true)]
        (let [row  (first (work/list-workstreams :brian))
              g    (first (work/gates :brian))
              chip (str (ui-state/position-chip (:position row) nil (get-in row [:status :needs])))
              mark (str (ui-state/state-mark (:status row) (:doing row)))]
          (testing "only the person's Land moves it: the person owes it, so it needs them — as a
                    landing — and nothing has stalled"
            (is (= {:owed-by :person :live false :stalled false :paused false :needs-you true
                    :needs :landing}
                   (:status row)))
            (is (= :parked-at-gate (:engagement row))))
          (testing "carded on Needs you as a landing that offers Land and asks nothing"
            (is (= [ws-id] (map :ws-id (work/gates :brian))))
            (is (= :landing (:needs g)))
            (is (= :land (:owes g)))
            (is (nil? (:ask g)) "no question is invented")
            (is (nil? (:core-ask g)) "and no gate is raised in the core")
            (is (= [:land] (map :id (:actions g)))))
          (testing "the board lights its chip as the person's, marks it needing them as a landing,
                    and draws neither ⚠ nor a working dot"
            (is (re-find #"pos-you" chip))
            (is (re-find #"needs you · landing" mark))
            (is (not (re-find #"⚠" mark)))
            (is (not (re-find #"st-mark live" mark)))))))))

(deftest a-land-the-core-already-holds-is-no-landing
  ;; Placed :land-granted, the seat lands it by its project's procedure: an agent's move, not the
  ;; person's, so it is no landing and nobody's Needs you card.
  (with-ws
    (fn [c ws-id]
      (fake-core/seed! c :brian ws-id
                       [{:command :open-unit :unit "u" :project "brian" :goal "g" :session-cwd "/w"}])
      (with-redefs [pipeline/of (constantly {:at :land-granted :next {:stage :land :mode :working-copy}})
                    nido.coordinator.record.standing/activated? (constantly true)]
        (let [row (first (work/list-workstreams :brian))]
          (is (= :agent (get-in row [:status :owed-by])))
          (is (not (get-in row [:status :needs-you])))
          (is (nil? (get-in row [:status :needs])))
          (is (empty? (work/gates :brian))))))))

(deftest a-candidate-whose-land-the-core-holds-is-the-seat-s-move
  ;; The grant placed the candidate :land-granted; who owes it and whether it is landing are read
  ;; off that placed position, never the release candidate status read before placing it.
  (with-ws
    (fn [c ws-id]
      (fake-core/seed! c :brian ws-id
                       [{:command :open-unit :unit "u" :project "brian" :goal "g" :session-cwd "/w"}
                        {:command :give-grant :grant :merge :by "person"}])
      (with-redefs [pipeline/of (constantly {:at :release-candidate :next {:stage :land :mode :release}})
                    nido.coordinator.record.standing/activated? (constantly true)]
        (let [row (first (work/list-workstreams :brian))]
          (is (= :land-granted (get-in row [:position :at])))
          (is (= :agent (get-in row [:status :owed-by])))
          (is (not (:needs-you row)))
          (is (nil? (get-in row [:status :needs])))
          (is (empty? (work/gates :brian))))))))

(deftest a-ledger-owed-move-is-carded-with-its-ask-and-actions
  (with-ws
    (fn [c ws-id]
      (fake-core/seed! c :brian ws-id
                       [{:command :open-unit :unit "u" :project "brian" :goal "g" :session-cwd "/w"}])
      (with-redefs [pipeline/of (constantly {:at :blocked :next {:stage :answer-blocker :mode :human}})
                    nido.coordinator.record.standing/activated? (constantly true)]
        (let [g (first (work/gates :brian))]
          (is (= :ask (:needs g)))
          (is (= :answer-blocker (:owes g)))
          (is (string? (get-in g [:ask :question])) "it carries the Ask built for the owed move")
          (is (seq (:actions g)) "and the actions that answer it"))))))

(deftest a-slice-keeps-a-card-of-its-own
  (is (= [{:ws-id "s"}]
         (#'work/inbox-gates :brian [[{:ws-id "s" :origin :slice} {:ws-id "s"}] [{:ws-id "d" :origin :drain} nil]]))
      "a slice's card is its own, never folded into its drain's"))

(deftest the-pane-s-gate-is-the-held-card-too
  (with-ws
    (fn [_ ws-id]
      (with-redefs [work/held-ledger-gates (fn [_ asked] (when-not (asked ws-id) [{:ws-id ws-id :needs :landing}]))]
        (is (= {:ws-id ws-id :needs :landing} (work/gate :brian ws-id))
            "the pane answers from the selection the list holds")))))

(deftest a-ledger-blocker-s-answer-is-recorded-on-the-ledger
  (with-ws
    (fn [_ ws-id]
      (workstream/append-entry! :brian ws-id {:kind :blocker}
                                (pr-str {:format :blocker :summary "stuck" :needs "Which way?" :after "it resumes"
                                         :options [{:label "Left" :summary "go left"} {:label "Right" :summary "go right"}]}))
      (let [n (pipeline/owed-blocker :brian ws-id)]
        (is (= {:decision :option-stale} (work/resolve-gate! :brian ws-id :option-b (inc n)))
            "a letter naming another record answers nothing")
        (is (= {:decision :answered-unresumed} (work/resolve-gate! :brian ws-id :option-b n)))
        (is (= [{:blocker-seq n :summary "Right"}]
               (map #(select-keys % [:blocker-seq :summary]) (workstream/entries-of :brian ws-id :blocker-answered))))
        (is (nil? (pipeline/owed-blocker :brian ws-id)) "and the blocker is owed no longer")))))

(deftest a-ledger-card-s-click-answers-its-blocker-whatever-the-core-asks-since
  (with-ws
    (fn [c ws-id]
      (workstream/append-entry! :brian ws-id {:kind :blocker}
                                (pr-str {:format :blocker :summary "stuck" :needs "Which way?" :after "it resumes"
                                         :options [{:label "Left" :summary "go left"} {:label "Right" :summary "go right"}]}))
      (let [n (pipeline/owed-blocker :brian ws-id)]
        ;; The core opens a question after the ledger card was drawn.
        (fake-core/seed! c :brian ws-id fake-core/asking)
        (is (= {:decision :answered-unresumed} (work/resolve-gate! :brian ws-id :option-a n)))
        (is (= :open (get-in (core-state c ws-id) [:operator-asks "a1" :status]))
            "the core's question, which the card never showed, is left unanswered")
        (is (nil? (pipeline/owed-blocker :brian ws-id)) "and the blocker the card showed is answered")))))

(deftest a-core-card-s-click-acts-only-on-the-ask-it-names
  (with-ws
    (fn [c ws-id]
      (fake-core/seed! c :brian ws-id fake-core/asking)
      (is (= :answered (:decision (work/resolve-gate! :brian ws-id :option-a {:ask "a1"}))))
      (is (= {:decision :option-stale} (work/resolve-gate! :brian ws-id :option-a {:ask "a1"}))
          "a second click on an ask already answered is refused, never redirected")
      (is (= {:decision :option-stale} (work/resolve-gate! :brian ws-id :reply {:ask "gone" :text "hi"}))))))

(deftest a-several-question-answer-hands-on-the-grants-it-chose
  (with-ws
    (fn [c ws-id]
      (workstream/append-entry! :brian ws-id {:kind :blocker}
                                (pr-str {:format :blocker :summary "stuck" :after "it resumes"
                                         :questions [{:id "q1" :question "Land it?"
                                                      :options [{:label "Land" :summary "now" :grants [:merge]}
                                                                {:label "Hold" :summary "later"}]}
                                                     {:id "q2" :question "Anything else?"}]}))
      (let [n (pipeline/owed-blocker :brian ws-id)]
        (is (= {:decision :answered-unresumed}
               (work/resolve-gate! :brian ws-id :answer-questions
                                   {:seq n :answers [{:letter (first nido.coordinator.report/option-letters)} {}]})))
        (is (contains? (:grants (core-state c ws-id)) :merge) "the option chosen on the card grants what it offered")))))

(deftest a-drain-s-owed-moves-are-carded-with-their-acts
  (with-ws
    (fn [c ws-id]
      (fake-core/seed! c :brian ws-id
                       [{:command :open-unit :unit "u" :project "brian" :goal "g" :session-cwd "/w"}])
      (doseq [[at owed act] [[:recipe-proposed :approve-recipe :approve-recipe]
                             [:drain-exhausted :close-drain :close-drain]
                             [:slice-cleared :land :land-slice]]]
        (with-redefs [pipeline/of (constantly {:at at :next {:stage owed :mode :human}})
                      nido.coordinator.record.standing/activated? (constantly true)]
          (let [row (first (work/list-workstreams :brian))
                g   (first (work/gates :brian))]
            (is (not= :landing (get-in row [:status :needs])) (str at " is no landing"))
            (is (= :ask (:needs g)))
            (is (= [act] (map :id (:actions g))) (str at " offers the act that makes its move"))))))))

(deftest retry-hands-back-the-failed-start-the-card-describes
  (with-ws
    (fn [c ws-id]
      (fake-core/seed! c :brian ws-id
                       (vec (concat
                             (for [u ["a" "b"]
                                   c [{:command :open-unit :unit u :project "brian" :goal "g"}
                                      {:command :record-provision-attempt :unit u :attempt (str "p-" u) :want :up}
                                      {:command :record-provision-outcome :unit u :attempt (str "p-" u)
                                       :outcome :failed :detail {:message (str "unit " u " would not start")}}]]
                               c)
                             [{:command :hold-unit :unit "a"}])))
      (is (= :retried (:decision (work/core-resolve! :brian ws-id :retry-start nil))))
      (is (= :failed (get-in (core-state c ws-id) [:units "a" :session :status])) "the held unit is left as it is")
      (is (= :none (get-in (core-state c ws-id) [:units "b" :session :status])) "the unheld one is retried"))))

(deftest a-session-that-failed-to-start-needs-you-as-a-system-error
  (with-ws
    (fn [c ws-id]
      (fake-core/seed! c :brian ws-id
                       [{:command :open-unit :unit "u" :project "brian" :goal "g"}
                        {:command :record-provision-attempt :unit "u" :attempt "p1" :want :up}
                        {:command :record-provision-outcome :unit "u" :attempt "p1" :outcome :failed
                         :detail {:message "pg would not start"}}])
      (testing "while the conductor is owed its failure, the core decides: nobody needs the person"
        (is (not (:needs-you (first (work/list-workstreams :brian))))))
      (let [heard (vec (keys (:inbox (core-state c ws-id))))]
        (fake-core/seed! c :brian ws-id
                         [{:command :start-turn :turn "c1" :unit nil :hears heard
                           :spec {:executor :fake :instructions "i"}}
                          {:command :record-outcome :turn "c1" :run 1 :outcome :completed}]))
      (let [row (first (work/list-workstreams :brian))
            g   (first (work/gates :brian))]
        (testing "the conductor left it: a failure only the person clears, not a stall"
          (is (= :system-error (get-in row [:status :needs])))
          (is (= :person (get-in row [:status :owed-by])))
          (is (not (get-in row [:status :stalled]))))
        (testing "carded as a system error offering Retry, which hands the start back to the core"
          (is (= :system-error (:needs g)))
          (is (= [:retry-start] (map :id (:actions g))))
          (is (re-find #"pg would not start" (:note g)))
          (is (= :retried (:decision (work/core-resolve! :brian ws-id :retry-start nil))))
          (is (= :none (get-in (core-state c ws-id) [:units "u" :session :status]))))))))

(deftest a-claim-with-the-core-idle-runs-nothing
  (with-ws
    (fn [c ws-id]
      (fake-core/seed! c :brian ws-id
                       [{:command :open-unit :unit "u" :project "brian" :goal "g" :session-cwd "/w"}])
      (with-redefs [nido.coordinator.record.activity/read-live (fn [& _] {:kind :design-round :run-id "r1"})]
        (let [row (first (work/list-workstreams :brian))]
          (is (nil? (:doing row)))
          (is (false? (get-in row [:status :live])))
          (is (true? (get-in row [:status :stalled]))
              "a review the core did not start is no turn: the unit is left unmoved"))))))

(deftest a-ledger-owing-the-person-parks-nothing-the-core-does-not-ask
  (with-ws
    (fn [_ _]
      (let [status pipeline/status]
        (with-redefs [pipeline/status (fn [& args] (assoc (apply status args) :owed-by :person))]
          (let [row (first (work/list-workstreams :brian))]
            (is (false? (:needs-you row)))
            (is (not= :parked-at-gate (:engagement row)))))))))

(deftest a-catch-up-is-applied-whole-before-anything-refreshes
  (with-redefs [wcore/mirror (atom {:pos 0 :ws {} :following? true :caught-up (promise)})]
    (let [changed (atom []) caught (atom 0)
          opts    {:on-change #(swap! changed conj %) :on-caught-up #(swap! caught inc)}
          msg     (fn [ws pos] {:ws ws :pos pos :state {:units {}} :awaiting []})]
      (is (false? (wcore/receive! (msg "p/a" 1) opts false)))
      (is (false? (wcore/receive! (msg "p/b" 2) opts false)))
      (is (= [] @changed) "nothing refreshes a workstream at a time during the catch-up")
      (is (not (realized? (:caught-up @wcore/mirror))))
      (is (true? (wcore/receive! {:caught-up true :pos 2} opts false)))
      (is (= 1 @caught) "the end of the catch-up refreshes once")
      (is (realized? (:caught-up @wcore/mirror)) "and releases a read waiting for it")
      (is (= #{"p/a" "p/b"} (set (keys (:ws @wcore/mirror)))))
      (is (true? (wcore/receive! (msg "p/a" 3) opts true)))
      (is (= ["p/a"] @changed) "a live message refreshes the workstream it changed"))))

(deftest the-mirror-holds-no-run
  (with-redefs [wcore/mirror (atom {:pos 0 :ws {} :following? true :caught-up (promise)})]
    (let [changed (atom [])
          opts    {:on-change #(swap! changed conj %)}
          msg     (fn [ws pos] {:ws ws :pos pos :state {:units {}} :awaiting []})]
      (wcore/receive! (msg "run:p/review-queue-1" 1) opts false)
      (wcore/receive! {:caught-up true :pos 1} opts false)
      (wcore/receive! (msg "run:p/review-queue-1" 2) opts true)
      (is (= {} (:ws @wcore/mirror)) "a run is no workstream, caught up or live")
      (is (= 2 (:pos @wcore/mirror)) "and the mirror moves past it all the same")
      (is (= [] @changed)))))

(deftest the-core-working-a-stage-runs-on-its-stage-row
  (with-ws
    (fn [c ws-id]
      (workstream/append-entry! :brian ws-id {:kind :intent}
                                (pr-str {:format :intent :goal "g" :done-when ["d"]}))
      (let [pane  #(work/workstream :brian ws-id)
            stage (fn [p s] (first (filter #(= s (:stage %)) (get-in p [:arc :stages]))))]
        (fake-core/seed! c :brian ws-id
                         [{:command :open-unit :unit "u" :project "brian" :goal "g"}])
        (let [p (pane)]
          (is (= :baseline (get-in p [:position :stage])) "an intent stated owes the baseline")
          (is (= {:source :core :activity :starting :unit "u"} (:doing p)))
          (is (= :running (:now (stage p :baseline))) "a session starting for it: the baseline is under way")
          (is (= "Status — Intent stated" (ui-state/status-heading (:position p) (:doing p)))
              "the heading keeps the position; the running stage row says what runs"))
        (fake-core/seed! c :brian ws-id
                         [{:command :record-provision-attempt :unit "u" :attempt "p1" :want :up}
                          {:command :record-provision-outcome :unit "u" :attempt "p1" :outcome :done :cwd "/w"}])
        (fake-core/seed! c :brian ws-id
                         [{:command :start-turn :turn "c" :unit nil :spec {:executor :fake :instructions "i"}}
                          {:command :record-outcome :turn "c" :run 1 :outcome :asked
                           :call {:id "k" :question "q?"}}
                          {:command :raise-operator-ask :ask "a" :binds #{"c"}
                           :content {:question "q?" :explored "x" :options ["y" "n"] :recommendation "y"}}])
        (let [p (pane)]
          (is (= :awaiting (:now (stage p :baseline))) "an ask the core holds: the stage waits on you")
          (is (nil? (:doing p))))))))

(deftest a-list-refresh-keeps-its-rows-while-the-core-is-down
  (with-ws
    (fn [_c ws-id]
      (work/hold! {:sweep? false})
      (try
        (work/refresh-list! :brian)
        (let [before (work/list-workstreams :brian)]
          ;; what nido.core.client throws when the socket is gone, from the read that reaches the
          ;; core — tests run an in-process core, which never touches the socket
          (with-redefs-fn {#'work/held-project
                           (fn [_] (throw (ex-info "the core daemon is not running"
                                                   {:core :unreachable :socket "/x"})))}
            #(is (nil? (work/refresh-list! :brian)) "a core restarting is not a failure of the refresh"))
          (is (= (map :ws-id before) (map :ws-id (work/list-workstreams :brian)))
              "the rows held before the core went away are still held")
          (is (some #{ws-id} (map :ws-id before)))
          (with-redefs-fn {#'work/held-project (fn [_] (throw (ex-info "other" {:core :refused})))}
            #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"other" (work/refresh-list! :brian))
                 "anything else still throws")))
        (finally (work/release!))))))
