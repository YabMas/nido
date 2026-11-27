(ns nido.core.buffer-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nido.core.ask-tool :as ask-tool]
            [nido.core.buffer :as b]
            [nido.core.domain :as d]
            [nido.test.sessions :as sessions]))

(def spec {:executor :fake :instructions "i"})
(def opts {:spec-fn (fn [_ws turn _state items] {:executor :fake :instructions (str turn " hears " (count items))})
           :max-failures 3})

(defn- state-after-from [s commands]
  (reduce (fn [s c]
            (if (:session-cwd c)
              (sessions/settle s [c])
              (let [c (cond-> c (= :inbox (:hears c)) (assoc :hears (vec (keys (:inbox s)))))
                    [s2 _ r] (d/step s c)]
                (assert (nil? r) (pr-str r c))
                s2)))
          s commands))

(defn- state-after [commands] (state-after-from d/empty-state commands))

(defn- refusal* [s c] (:reason (nth (d/step s c) 2)))

(deftest an-outcome-wakes-the-conductor-to-hear-the-whole-inbox
  (let [s (state-after [{:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/w"}
                        {:command :start-turn :turn "t" :unit "u" :spec spec}
                        {:command :record-outcome :turn "t" :outcome :asked :call {:id "c" :question "Go?"}}])
        [wake & more] (b/decisions opts "ws" s 7)]
    (is (nil? more))
    (is (= {:key "buffer/wake/conductor-7" :ws "ws"
            :command {:command :start-turn :turn "conductor-7" :unit nil :hears (vec (keys (:inbox s)))
                      :spec {:executor :fake :instructions "conductor-7 hears 1"}}}
           wake))
    (testing "nothing more while a conductor turn runs"
      (is (empty? (b/decisions opts "ws" (first (d/step s (:command wake))) 8))))
    (testing "the same state seen twice issues the same keyed command"
      (is (= [wake] (b/decisions opts "ws" s 7))))))

(deftest a-conductor-s-ask-goes-to-the-person-and-its-answer-resumes-it
  (let [s (state-after [{:command :start-turn :turn "k" :unit nil :spec spec}
                        {:command :record-outcome :turn "k" :outcome :asked
                         :call {:id "c" :question "open unit b?" :explored "x" :options ["yes" "no"] :recommendation "yes"}}])
        [raise] (b/decisions opts "ws" s 3)]
    (is (= {:command :raise-operator-ask :ask "ask k c" :binds ["k"] :concerns nil
            :content {:question "open unit b?" :explored "x" :options ["yes" "no"] :recommendation "yes"}}
           (:command raise)))
    (let [s2 (state-after-from s [(:command raise) {:command :answer-operator-ask :ask "ask k c" :answer "yes"}])]
      (is (= [{:command :resume-turn :turn "k"}] (map :command (b/decisions opts "ws" s2 5)))))))

(deftest a-conductor-s-several-questions-reach-the-person-as-written
  (let [qs [{:question "a?" :options [{:option "x" :recommended true}]} {:question "b?" :options [{:option "y"}]}]
        s  (state-after [{:command :start-turn :turn "k" :unit nil :spec spec}
                         {:command :record-outcome :turn "k" :outcome :asked
                          :call {:id "c" :question "Two decisions?" :explored "x" :questions qs :recommendation "x"}}])
        [raise] (b/decisions opts "ws" s 3)]
    (is (= {:question "Two decisions?" :explored "x" :questions qs :recommendation "x"}
           (get-in raise [:command :content])))))

(deftest failing-conductor-turns-stop-the-waking
  (let [s (state-after (concat [{:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/w"}
                                {:command :start-turn :turn "t" :unit "u" :spec spec}
                                {:command :record-outcome :turn "t" :outcome :completed}]
                               (mapcat (fn [i] (let [k (str "k" i)]
                                                 [{:command :start-turn :turn k :unit nil :spec spec :hears :inbox}
                                                  {:command :record-outcome :turn k :outcome :failed}]))
                                       (range 3))))]
    (is (= 3 (:conductor-failures s)))
    (is (seq (:inbox s)) "what they heard is back in the inbox")
    (is (= [:raise-conductor-stop] (map (comp :command :command) (b/decisions opts "ws" s 9)))
        "nothing wakes the conductor again: the person is asked instead")))

(defn- exhausted-conductor-turns [n at]
  (state-after (concat [{:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/w"}
                        {:command :start-turn :turn "t" :unit "u" :spec spec}
                        {:command :record-outcome :turn "t" :outcome :completed}]
                       (mapcat (fn [i] (let [k (str "k" i)]
                                         [{:command :start-turn :turn k :unit nil :spec spec :hears :inbox}
                                          {:command :record-outcome :turn k :outcome :failed
                                           :detail {:reason :resource-exhausted :at at}}]))
                               (range n)))))

(deftest a-conductor-the-host-refuses-backs-off-and-is-not-stopped
  (let [at 1000000
        s  (exhausted-conductor-turns 3 at)]
    (is (= 0 (:conductor-failures s)) "a refused run is not the conductor failing")
    (is (= 3 (:conductor-exhaustions s)))
    (is (= (+ at (d/exhaustion-backoff-ms 3)) (:conductor-retry-at s)))
    (is (seq (:inbox s)) "what it heard is back in the inbox")
    (is (empty? (filter #(= :raise-conductor-stop (get-in % [:command :command]))
                        (b/decisions opts "ws" s 9)))
        "no stop ask: the person is not asked about the host's limits")
    (let [states {"ws" {:state s :pos 9}}]
      (testing "no wake before the retry time, a person's input included"
        (is (empty? (b/wakes (assoc opts :now (dec (:conductor-retry-at s))) states ["ws"])))
        (is (empty? (b/wakes (assoc opts :now (dec (:conductor-retry-at s)))
                             {"ws" {:state (state-after-from s [{:command :post-chat :message "m" :text "hi"}]) :pos 10}}
                             ["ws"]))))
      (testing "the wake once it has come"
        (is (= ["conductor-9"] (map (comp :turn :command) (b/wakes (assoc opts :now (:conductor-retry-at s)) states ["ws"])))))
      (testing "the rules' loop is told when to look again"
        (is (= 1001 (b/next-retry-in states (- (:conductor-retry-at s) 1000))))
        (is (nil? (b/next-retry-in states (:conductor-retry-at s))))))))

(deftest a-backoff-that-ends-while-the-wakes-are-decided-still-wakes
  ;; The retry time passes after `wakes` read the clock and before the loop computes its wait: the
  ;; loop must still look again, not block on an empty queue until an unrelated commit.
  (let [s (exhausted-conductor-turns 1 (+ (- (System/currentTimeMillis) (d/exhaustion-backoff-ms 1)) 50))
        submitted (atom [])
        orig b/wakes]
    (with-redefs [b/wakes (fn [& args] (let [r (doall (apply orig args))] (Thread/sleep 200) r))]
      (let [buf (b/start {:submit #(swap! submitted conj %) :spec-fn (:spec-fn opts)})]
        (try
          (b/changed! buf [{:ws "ws" :state s :pos 9}])
          (Thread/sleep 800)
          (is (some #(= :start-turn (get-in % [:command :command])) @submitted)
              "the conductor is woken once its retry time has come")
          (finally (b/stop! buf)))))))

(deftest the-backoff-doubles-to-a-ceiling
  (is (= [30000 60000 120000 240000 480000 900000 900000]
         (map d/exhaustion-backoff-ms [1 2 3 4 5 6 20]))))

(deftest a-conductor-turn-that-completes-clears-the-backoff
  (let [s (state-after-from (exhausted-conductor-turns 2 5)
                            [{:command :start-turn :turn "ok" :unit nil :spec spec :hears :inbox}
                             {:command :record-outcome :turn "ok" :outcome :completed}])]
    (is (= [0 0 nil] ((juxt :conductor-failures :conductor-exhaustions :conductor-retry-at) s)))))

(deftest a-unit-owes-its-session-before-its-first-turn
  (let [s (state-after [{:command :open-unit :unit "u" :project "p" :goal "g"}])
        [attempt] (b/decisions opts "ws" s 2)]
    (is (= {:command :record-provision-attempt :unit "u" :want :up :attempt "u up 2"} (:command attempt)))
    (is (= :session-not-ready (refusal* s {:command :start-turn :turn "t" :unit "u" :spec spec})))
    (let [s2 (state-after-from s [(:command attempt)
                                  {:command :record-provision-outcome :unit "u" :attempt "u up 2" :outcome :done :cwd "/wt"}])
          [s3 [e]] (d/step s2 {:command :start-turn :turn "t" :unit "u" :spec (assoc spec :cwd "/elsewhere")})]
      (is (= [{:command :start-turn :turn "conductor-3" :unit nil :hears ["#1 unit-ready"]}]
             (map #(dissoc (:command %) :spec) (b/decisions opts "ws" s2 3)))
          "only the conductor is owed, woken to direct a unit that has nothing to run")
      (is (= "/wt" (get-in e [:spec :cwd])) "a unit turn runs in its session, whatever it was told")
      (testing "a merge owes a stop, and a close a destroy"
        (let [s4 (state-after-from s3 [{:command :record-outcome :turn "t" :outcome :completed}
                                       {:command :record-merge :unit "u"}])]
          (is (= [{:unit "u" :want :stopped}] (d/sessions-owed s4)))
          (is (= [{:unit "u" :want :destroyed}]
                 (d/sessions-owed (state-after-from s4 [{:command :close-workstream}])))))))))

(deftest a-stopped-conductor-asks-the-person-and-their-answer-wakes-it
  (let [s (state-after (concat [{:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/w"}
                                {:command :start-turn :turn "t" :unit "u" :spec spec}
                                {:command :record-outcome :turn "t" :outcome :completed}]
                               (mapcat (fn [i] (let [k (str "k" i)]
                                                 [{:command :start-turn :turn k :unit nil :spec spec :hears :inbox}
                                                  {:command :record-outcome :turn k :outcome :failed}]))
                                       (range 3))))
        [stop] (b/decisions opts "ws" s 9)
        s2 (state-after-from s [(:command stop) {:command :post-chat :message "m" :text "hello?"}])]
    (is (= :raise-conductor-stop (get-in stop [:command :command])))
    (is (= 1 (count (d/awaiting-person s2))))
    (is (empty? (b/decisions opts "ws" s2 10)) "a person's input does not pass the stop")
    (let [s3 (state-after-from s2 [{:command :answer-operator-ask :ask (get-in stop [:command :ask]) :answer "wake" :by :person}])]
      (is (= [:start-turn] (map (comp :command :command) (b/decisions opts "ws" s3 11)))))))

(deftest background-wakes-are-capped-and-a-person-s-input-is-not
  (let [handed (state-after [{:command :hand-over :summary "x"}])
        chatted (state-after [{:command :post-chat :message "m" :text "hi"}])
        states {"a" {:state handed :pos 1} "b" {:state handed :pos 2} "c" {:state handed :pos 3}
                "p" {:state chatted :pos 4}}
        ws (map :ws (b/wakes (assoc opts :wake-cap 2) states ["a" "b" "c" "p"]))]
    (is (= ["p" "a" "b"] ws))
    (testing "running background turns count against the cap"
      (let [running (state-after-from handed [{:command :start-turn :turn "k" :unit nil :spec spec :hears :inbox}
                                              {:command :hand-over :summary "y"}])]
        (is (= ["p"] (map :ws (b/wakes (assoc opts :wake-cap 1) (assoc states "a" {:state running :pos 5}) ["a" "b" "c" "p"]))))))))

(deftest a-triggers-unit-starts-its-first-turn-once-its-session-is-up
  (let [first-turn {:spec {:executor :claude :instructions "/triage-bug BR-1"}}
        s (state-after [{:command :open-unit :unit "u" :project "p" :goal "g" :first-turn first-turn}])]
    (is (not-any? #(= :start-turn (get-in % [:command :command])) (b/decisions opts "ws" s 1))
        "no session yet: nothing starts")
    (let [s (state-after-from s [{:command :record-provision-attempt :unit "u" :want :up :attempt "a"}
                                 {:command :record-provision-outcome :unit "u" :attempt "a" :outcome :done :cwd "/w"}])
          starts (filter #(= :start-turn (get-in % [:command :command])) (b/decisions opts "ws" s 2))]
      (is (= [{:key "buffer/first/u" :ws "ws"
               :command {:command :start-turn :turn "u first" :unit "u"
                         :spec {:executor :claude :instructions "/triage-bug BR-1"}}}]
             starts))
      (let [s (state-after-from s [(:command (first starts))])]
        (is (= "/w" (get-in s [:turns "u first" :spec :cwd])) "it runs in the unit's session")
        (is (empty? (d/first-turns-owed s)) "and is owed once")))))

(deftest a-conductor-turn-is-given-the-images-the-messages-it-hears-carry
  (let [img (apply str (repeat 64 "b"))
        s   (state-after [{:command :post-chat :message "m1" :text "look" :images [img]}
                          {:command :post-chat :message "m2" :text "and this, again" :images [img]}])
        f   (b/conductor-spec-fn {:socket "/s" :bb-bin "bb" :src "/src" :ws-dir (constantly "/ws")})
        items (sort-by :id (vals (:inbox s)))
        spec (f "ws" "c1" s items)]
    (is (= [img] (:images spec)) "each image once, in order")
    (is (re-find #"with 1 image attached to this prompt, in order: b{64}" (:instructions spec))))
  (testing "a turn hearing no images is given none"
    (let [s (state-after [{:command :post-chat :message "m1" :text "hi"}])
          f (b/conductor-spec-fn {:socket "/s" :bb-bin "bb" :src "/src" :ws-dir (constantly "/ws")})]
      (is (not (contains? (f "ws" "c1" s (vals (:inbox s))) :images))))))

(deftest the-conductor-tools-run-from-the-source-the-daemon-loaded
  (let [s    (state-after [{:command :post-chat :message "m1" :text "hi"}])
        spec ((b/conductor-spec-fn {:socket "/s" :bb-bin "bb" :ws-dir (constantly "/ws")})
              "ws" "c1" s (vals (:inbox s)))
        root (-> (clojure.java.io/resource "nido/core/buffer.clj") clojure.java.io/file
                 .getParentFile .getParentFile .getParentFile str)]
    (is (= ["--classpath" root "-m" "nido.core.buffer" "tools" "/s" "ws" "c1"]
           (get-in spec [:mcp-servers "nido-conductor" :args])))))

(deftest the-conductor-is-told-where-the-recipe-stands
  (let [s     (state-after [{:command :create-workstream :identity {:project :brian :external-refs [] :created-at "2026-11-24T00:00:00Z"}}])
        add   (fn [s kind] (first (d/step s {:command :append-entry :after (d/latest-seq s)
                                              :row {:kind kind :at "t" :ext "edn"} :content "{}"})))
        brief #(b/brief "brian/ws" % [])]
    (is (re-find #"Recipe: not running" (brief s)))
    (let [held (reduce add s [:activated :intent])]
      (is (re-find #"design is NOT cleared \(recorded: intent\)" (brief held)))
      (is (re-find #"do not direct a unit to build" (brief held)))
      (is (re-find #"the design is cleared" (brief (reduce add held [:baseline :design :design-cleared])))))))

(deftest past-clearance-the-conductor-is-told-what-the-pipeline-says-is-owed
  (let [s       (state-after [{:command :create-workstream :identity {:project :brian :external-refs [] :created-at "2026-11-24T00:00:00Z"}}])
        add     (fn [s kind] (first (d/step s {:command :append-entry :after (d/latest-seq s)
                                                :row {:kind kind :at "t" :ext "edn"} :content "{}"})))
        held    (reduce add s [:activated :intent :baseline :design])
        cleared (add held :design-cleared)
        owed    (fn [at stage mode] {:at at :stage stage :mode mode :person nil :held-by nil
                                     :project :brian :ws-id "ws-1"})
        brief   #(b/brief "brian/ws-1" cleared [] %)]
    (testing "asked only where the recipe runs and the design is cleared"
      (is (not (#'b/owed-wanted? s)))
      (is (not (#'b/owed-wanted? held)))
      (is (#'b/owed-wanted? cleared)))
    (testing "implement owed: the unit records the layers and the implementation, citing the cleared design"
      (let [t (brief (owed :design-cleared :implement :working-copy))]
        (is (re-find #"Owed now: implement — at design-cleared" t))
        (is (re-find #":layer-completed" t))
        (is (re-find #":implementation-completed citing the design the :design-cleared names" t))
        (is (re-find #"nido:workstream:entry:add :project brian :ws-id ws-1" t))))
    (testing "implemented: the implementation review is owed, run by the unit"
      (let [t (brief (owed :implemented :review-implementation :mechanical))]
        (is (re-find #"Owed now: the implementation review — at implemented" t))
        (is (re-find #"nido:review:loop" t))
        (is (not (re-find #"Owed now: implement —" t)))))
    (testing "the review's direction calls no converged review a candidate by itself"
      (let [t (brief (owed :implemented :review-implementation :mechanical))]
        (is (re-find #"a release candidate if its QA passes or does not apply" t))
        (is (not (re-find #"converges the workstream is a release candidate\." t)))))
    (testing "QA owed or failed: the unit walks, plans or fixes, and nothing directs a landing"
      (let [walk (brief (owed :qa-owed :walk-qa :authoring))
            plan (brief (owed :qa-unplanned :plan-qa :authoring))
            fix  (brief (owed :qa-failed :address-qa :working-copy))]
        (is (re-find #"Owed now: the QA walk — at qa-owed" walk))
        (is (re-find #"agent-browser" walk))
        (is (re-find #"nido:workstream:screenshot:add" walk))
        (is (re-find #"Owed now: the QA plan — at qa-unplanned" plan))
        (is (re-find #":kind :qa-plan" plan))
        (is (re-find #"Owed now: what the QA walk found — at qa-failed" fix))
        (doseq [t [walk plan fix]
                :let [line (re-find #"Owed now: [^\n]*" t)]]
          (is (re-find #"no release candidate" line))
          (is (not (re-find #"is a release candidate|lands it with Land" line))))))
    (testing "the standing rule makes a candidate only once QA passes or does not apply"
      (is (re-find #"converges and its QA passes or does not apply, the workstream is a release candidate"
                   (brief nil))))
    (testing "a release candidate owes a unit nothing"
      (is (re-find #"Owed: nothing — at release-candidate" (brief (owed :release-candidate :land :release)))))
    (testing "a person's stage is not directed to a unit"
      (is (re-find #"it is the person's to do"
                   (brief (assoc (owed :blocked :answer-blocker :human) :person :answer-blocker)))))
    (testing "unread, the brief still names the order the recipe owes"
      (is (re-find #"could not be read now.*implement.*nido:review:loop" (brief nil))))))

(deftest the-spec-asks-what-is-owed-only-past-clearance
  (let [s       (state-after [{:command :create-workstream :identity {:project :brian :external-refs [] :created-at "2026-11-24T00:00:00Z"}}])
        add     (fn [s kind] (first (d/step s {:command :append-entry :after (d/latest-seq s)
                                                :row {:kind kind :at "t" :ext "edn"} :content "{}"})))
        asked   (atom [])
        f       (b/conductor-spec-fn {:socket "/s" :bb-bin "bb" :ws-dir (constantly "/ws")
                                      :owed (fn [ws] (swap! asked conj ws)
                                              {:at :implemented :stage :review-implementation :mode :mechanical
                                               :project :brian :ws-id "ws-1"})})]
    (f "brian/ws-1" "c1" (reduce add s [:activated :intent]) [])
    (is (= [] @asked) "not while the design is uncleared")
    (let [spec (f "brian/ws-1" "c2" (reduce add s [:activated :intent :baseline :design :design-cleared]) [])]
      (is (= ["brian/ws-1"] @asked))
      (is (re-find #"the implementation review" (:instructions spec))))))

(deftest the-conductor-hears-what-the-person-answered
  (let [s  (state-after [{:command :start-turn :turn "k" :unit nil :spec spec}
                         {:command :record-outcome :turn "k" :outcome :asked
                          :call {:id "c" :question "open unit b?" :explored "x" :options ["yes" "no"] :recommendation "yes"}}])
        [raise] (b/decisions opts "ws" s 3)
        answer "1. open unit b? — yes\n   The person's comment on question 1: only after a's tests pass"
        s2 (state-after-from s [(:command raise)
                                {:command :answer-operator-ask :ask "ask k c" :answer answer :by :person}])
        item (some #(when (= :ask-answered (:kind %)) %) (vals (:inbox s2)))]
    (testing "the inbox item carries the answer value as given"
      (is (= answer (:answer item))))
    (testing "the brief prints it beside the ask's id, every line under the item"
      (let [t (b/brief "ws" s2 [item])]
        (is (str/includes? t "the person answered operator ask ask k c, answering:\n    1. open unit b? — yes\n       The person's comment on question 1: only after a's tests pass"))))
    (testing "a withdrawal carries no answer"
      (let [s3 (state-after-from s [(:command raise)
                                    {:command :withdraw-operator-ask :ask "ask k c" :reason "no" :by :person}])
            w  (some #(when (= :ask-answered (:kind %)) %) (vals (:inbox s3)))]
        (is (= :withdrawn (:how w)))
        (is (not (contains? w :answer)))
        (is (re-find #"(?m)the person withdrawn operator ask ask k c$" (b/brief "ws" s3 [w])))))))

(deftest askers-are-told-a-comment-may-push-back
  (doseq [[where text] [["ask briefing" (ask-tool/briefing)]
                        ["conductor brief" (b/brief "ws" d/empty-state [])]]
          ;; Where the markdown wraps a line says nothing about what it says.
          :let [text (str/replace text #"\s+" " ")]]
    (testing where
      (is (str/includes? text "comment on a question") "an answer may carry a per-question comment")
      (is (str/includes? text "with or without an option chosen"))
      (is (str/includes? text "re-asking or reframing") "a disputing comment is answered by a re-ask or reframe")
      (is (str/includes? text "never by proceeding on an option nobody chose")))))

;; ── a drain's conductor ──────────────────────────────────────────────────────

(defn- drain-state
  "A drain's state: its ledger index holds a :drain birth entry, and its inbox a notice."
  []
  (-> (state-after [{:command :post-notice :notice "wake:charter:1" :text "Drain chartered: propose one."}])
      (assoc :ledger [{:seq 1 :kind :drain}])))

(def ^:private coord-brief "You coordinate drain ws-d of brian.\nYour levers, each run as printed:\n- bb --config ~/Code/nido/bb.edn nido:drain:propose")

(deftest a-drain-s-conductor-is-told-its-coordinator-brief-and-the-asking-rules
  (let [asked (atom [])
        f     (b/conductor-spec-fn {:socket "/s" :bb-bin "bb" :ws-dir (constantly "/ws")
                                    :drain-brief (fn [ws] (swap! asked conj ws) coord-brief)})
        s     (drain-state)
        i     (:instructions (f "brian/ws-d" "conductor-1" s (vals (:inbox s))))]
    (is (= ["brian/ws-d"] @asked))
    (is (str/starts-with? i coord-brief) "the coordinator brief, as the verb printed it, comes first")
    (is (str/includes? i "mcp__nido__ask as the ONLY tool call"))
    (is (str/includes? i (ask-tool/briefing)) "the ask briefing")
    (is (str/includes? i "Answer every chat message the person sent with reply_chat"))
    (is (str/includes? i "notice wake:charter:1") "what it has not yet heard")
    (is (not (str/includes? i "Act ONLY through the nido conductor tools")) "not the generic role")
    (is (not (str/includes? i "You are the conductor of nido workstream")))
    (doseq [unit-rule ["answer_call" "start_unit_turn" "settle_unknown" "Never ask the person whether to land"]]
      (is (not (str/includes? i unit-rule)) (str "no generic Rule for conducting units: " unit-rule)))))

(deftest any-other-conductor-is-briefed-as-before-and-the-reader-is-not-asked
  (let [asked (atom 0)
        f     (b/conductor-spec-fn {:socket "/s" :bb-bin "bb" :ws-dir (constantly "/ws")
                                    :drain-brief (fn [_] (swap! asked inc) coord-brief)})
        g     (b/conductor-spec-fn {:socket "/s" :bb-bin "bb" :ws-dir (constantly "/ws")})
        s     (state-after [{:command :hand-over :summary "x"}])
        items (vals (:inbox s))]
    (is (= (f "brian/ws" "c" s items) (g "brian/ws" "c" s items)))
    (is (= (b/brief "brian/ws" s items) (:instructions (f "brian/ws" "c" s items))))
    (is (zero? @asked))))

(deftest a-drain-wake-is-withheld-while-no-brief-is-given
  (let [f (b/conductor-spec-fn {:socket "/s" :bb-bin "bb" :ws-dir (constantly "/ws") :drain-brief (constantly nil)})
        o {:spec-fn f :max-failures 3}
        s (drain-state)]
    (is (nil? (f "brian/ws-d" "c" s (vals (:inbox s)))) "no spec without the brief")
    (is (empty? (b/decisions o "brian/ws-d" s 3)) "no command under the wake's key")
    (is (every? :withheld? (b/wakes o {"brian/ws-d" {:state s :pos 3}} ["brian/ws-d"])))
    (testing "the wake is decided again within the retry interval, and sent once a brief is given"
      (let [given     (atom false)
            submitted (atom [])
            buf (b/start {:submit #(swap! submitted conj %) :withheld-retry-ms 50
                          :spec-fn (b/conductor-spec-fn {:socket "/s" :bb-bin "bb" :ws-dir (constantly "/ws")
                                                         :drain-brief (fn [_] (when @given coord-brief))})})]
        (try
          (b/changed! buf [{:ws "brian/ws-d" :state s :pos 3}])
          (Thread/sleep 200)
          (is (empty? @submitted) "nothing sent while withheld")
          (reset! given true)
          (Thread/sleep 400)
          (is (= ["buffer/wake/conductor-3"] (distinct (map :key @submitted))))
          (finally (b/stop! buf)))))))

(deftest a-notice-wakes-under-the-background-cap
  (let [noticed (state-after [{:command :post-notice :notice "n" :text "census"}])]
    (is (not (b/person-input? noticed)))
    (is (= ["a"] (map :ws (b/wakes (assoc opts :wake-cap 1)
                                   {"a" {:state noticed :pos 1} "b" {:state noticed :pos 2}} ["a" "b"]))))))
