(ns nido.coordinator.lane.conduct-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.agent.substrate :as substrate]
   [nido.coordinator.lane.conduct :as conduct]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.session-events :as events]
   [nido.coordinator.record.state :as cstate]
   [nido.session.state :as sstate]))

;; ── decide: the policy, pure ────────────────────────────────────────────────

(def driving
  {:session-id "s" :bg-id "s" :origin :person :driver :nido :pending nil :queued nil
   :own-work? false :policy {:auto-respawn? false} :driving-since "t0"
   :announced-blocked? false :recovered-boot nil})

(def done-row {:sessionId "s" :kind "background" :state "done" :status "idle" :pid 7 :cwd "/home/kid"})
(def ctx {:now "t1" :token "T" :baseline :shown :latest {:event :stop}})

(defn- kinds [d] (mapv first (:actions d)))

(deftest a-queued-instruction-goes-to-a-done-session-with-its-token-already-pending
  (let [d (conduct/decide (assoc driving :queued "do it") done-row [] ctx)]
    (is (= [[:send "do it\n\n[nido-send T]" "T" "do it" nil]] (:actions d)))
    (is (= {:token "T" :at "t1" :via :send :seen? false} (get-in d [:facet :pending]))
        "recorded in the facet the caller writes before it delivers")
    (is (nil? (get-in d [:facet :queued])))))

(deftest a-session-without-a-live-process-is-woken-instead
  (is (= [:wake] (kinds (conduct/decide (assoc driving :queued "x") (dissoc done-row :pid) [] ctx)))))

(deftest nothing-is-given-unless-every-gate-is-open
  (doseq [[why facet row] [["released"         (assoc driving :queued "x" :driver :nobody) done-row]
                           ["a person drives"  (assoc driving :queued "x" :driver :person) done-row]
                           ["one outstanding"  (assoc driving :queued "x" :pending {:token "P" :at "t" :via :send :seen? true}) done-row]
                           ["working"          (assoc driving :queued "x") (assoc done-row :state "working")]
                           ["blocked mid-turn" (assoc driving :queued "x") (assoc done-row :state "blocked")]
                           ["failed"           (assoc driving :queued "x") (assoc done-row :state "failed")]
                           ["own work running" (assoc driving :queued "x" :own-work? true) done-row]
                           ["not listed"       (assoc driving :queued "x") nil]
                           ["nothing queued"   driving done-row]]]
    (testing why
      (is (not-any? #{:send :wake} (kinds (conduct/decide facet row [] (assoc ctx :latest {:event :prompt}))))))))

(deftest a-session-blocked-after-its-stop-is-given-the-answer-queued-for-it
  (let [blocked (assoc done-row :state "blocked")]
    (is (= [:notify-blocked :send] (kinds (conduct/decide (assoc driving :queued "x") blocked [] ctx))))
    (is (= [:notify-blocked :wake] (kinds (conduct/decide (assoc driving :queued "x") (dissoc blocked :pid) [] ctx))))))

(deftest a-wake-needs-the-session-s-stop-as-its-latest-event
  (is (not-any? #{:wake} (kinds (conduct/decide (assoc driving :queued "x") (dissoc done-row :pid) []
                                                (assoc ctx :latest {:event :prompt})))))
  (is (= [:send] (kinds (conduct/decide (assoc driving :queued "x") done-row []
                                        (assoc ctx :latest {:event :prompt}))))
      "a live process the listing reports done is sent to"))

(deftest nothing-is-given-while-the-baseline-does-not-show
  (let [d (conduct/decide (assoc driving :queued "x") done-row [] (assoc ctx :baseline :not-auto))]
    (is (= [[:refused :not-auto]] (:actions d)))
    (is (= "x" (get-in d [:facet :queued])) "kept queued")
    (is (nil? (get-in d [:facet :pending])))))

(deftest a-hold-written-before-is-ignored
  (let [old (assoc driving :queued "x" :hold {:at "t" :because :foreign-prompt})
        d   (conduct/decide old done-row [] ctx)]
    (is (= [:send] (kinds d)) "nothing is kept back for it")
    (is (not (contains? (:facet d) :hold)) "and nothing writes it back"))
  (testing "a :person driver it left is nobody: nido gives it nothing until a start or an adoption"
    (let [d (conduct/decide (assoc driving :queued "x" :driver :person) done-row [] ctx)]
      (is (= [] (kinds d)))
      (is (= :nobody (get-in d [:facet :driver]))))))

(deftest usage-limit-holds-sends-back
  (is (not-any? #{:send} (kinds (conduct/decide (assoc driving :queued "x") done-row [] (assoc ctx :hold-sends? true))))))

(deftest nidos-own-prompt-is-recognised-by-its-token
  (let [f (assoc driving :pending {:token "T" :at "t" :via :send :seen? false})
        d (conduct/decide f done-row [{:event :prompt :prompt "do it\n\n[nido-send T]"}] ctx)]
    (is (true? (get-in d [:facet :pending :seen?])))))

(deftest a-prompt-nido-did-not-give-changes-nothing-of-who-drives
  (testing "typed, no token"
    (let [d (conduct/decide driving done-row [{:event :prompt :prompt "wait, stop" :at "t2"}] ctx)]
      (is (= :nido (get-in d [:facet :driver])))
      (is (not (contains? (:facet d) :hold)))
      (is (= [[:prompted {:event :prompt :prompt "wait, stop" :at "t2"}]] (:actions d))
          "it is reported as the person's prompt it is")))
  (testing "a paste of an old instruction: its token is no longer pending"
    (let [f (assoc driving :pending {:token "T" :at "t" :via :send :seen? true})
          d (conduct/decide f done-row [{:event :prompt :prompt "x [nido-send T]"}] ctx)]
      (is (= :nido (get-in d [:facet :driver])))
      (is (= {:token "T" :at "t" :via :send :seen? true} (get-in d [:facet :pending])))))
  (testing "a person's turn mid-way through nido's leaves nido's instruction to end at its Stop"
    (let [f (assoc driving :pending {:token "T" :at "t" :via :send :seen? false})
          d (conduct/decide f done-row [{:event :prompt :prompt "go\n\n[nido-send T]"}
                                        {:event :prompt :prompt "and also this"}
                                        {:event :stop :background-tasks 0 :crons 0}] ctx)]
      (is (= {:token "T" :at "t" :via :send :seen? true}
             (some (fn [[k _ i]] (when (= :turn-ended k) i)) (:actions d))))
      (is (= :nido (get-in d [:facet :driver]))))))

(deftest a-stop-ends-the-outstanding-instruction
  (let [f (assoc driving :pending {:token "T" :at "t" :via :send :seen? true})
        d (conduct/decide f done-row [{:event :stop :last-message "ok" :background-tasks 0 :crons 0}] ctx)]
    (is (nil? (get-in d [:facet :pending])))
    (is (= :turn-ended (ffirst (:actions d))))))

(deftest a-stop-names-the-instruction-it-ended-only-when-nido-gave-that-turn
  (let [p   {:token "T" :at "t" :via :send :seen? true}
        stop {:event :stop :background-tasks 0 :crons 0}
        ended #(some (fn [[k _ i]] (when (= :turn-ended k) [i])) (:actions %))]
    (is (= [p] (ended (conduct/decide (assoc driving :pending p) done-row [stop] ctx)))
        "the Stop after nido's own prompt ended nido's instruction")
    (let [d (conduct/decide (assoc driving :pending (assoc p :seen? false)) done-row
                            [{:event :prompt :prompt "mine, not nido's"} stop] ctx)]
      (is (= [nil] (ended d))
          "a person's turn, with nido's instruction outstanding but never prompted, ended none")
      (is (= (assoc p :seen? false) (get-in d [:facet :pending])) "so that instruction is still outstanding"))
    (is (= [nil] (ended (conduct/decide driving done-row [stop] ctx)))
        "nothing outstanding: none")))

(deftest a-turn-after-a-stop-with-work-running-is-the-session-s-own
  (let [f  (assoc driving :pending {:token "T" :at "t" :via :send :seen? true})
        d1 (conduct/decide f done-row [{:event :stop :background-tasks 1 :crons 0}] ctx)
        d2 (conduct/decide (:facet d1) done-row [{:event :stop :background-tasks 0 :crons 0}] ctx)]
    (is (true? (get-in d1 [:facet :own-work?])))
    (is (not-any? #{:send} (kinds (conduct/decide (assoc (:facet d1) :queued "x") done-row [] ctx)))
        "nothing is given while its own work runs")
    (is (false? (get-in d2 [:facet :own-work?])) "the background work finishing frees it")))

(deftest blocked-is-announced-once-per-entry
  (let [blocked (assoc done-row :state "blocked")
        d1 (conduct/decide driving blocked [] ctx)
        d2 (conduct/decide (:facet d1) blocked [] ctx)
        d3 (conduct/decide (:facet d2) done-row [] ctx)
        d4 (conduct/decide (:facet d3) blocked [] ctx)]
    (is (= [:notify-blocked] (kinds d1)))
    (is (= [] (kinds d2)) "still blocked: no second notification")
    (is (false? (get-in d3 [:facet :announced-blocked?])) "left blocked")
    (is (= [:notify-blocked] (kinds d4)) "a new entry is announced")))

(deftest blocked-after-a-turn-the-conductor-answers-for-is-marked-for-it
  (let [blocked (assoc done-row :state "blocked")
        given   (fn [pending] (assoc driving :pending (merge {:token "P" :at "t" :via :send :seen? true} pending)))
        stop    [{:event :stop :background-tasks 0 :crons 0}]
        unrun   (fn [d] (second (first (filter #(= :notify-blocked (first %)) (:actions d)))))]
    (is (true? (unrun (conduct/decide (given {}) blocked stop ctx)))
        "its turn on an instruction no Run gave ended: handed back to the conductor, not the person's")
    (is (false? (unrun (conduct/decide (given {:for "run-1"}) blocked stop ctx))) "a Run's turn is its Run's")
    (is (false? (unrun (conduct/decide (given {}) blocked [] (assoc ctx :latest {:event :prompt}))))
        "blocked mid-turn, nothing is handed back: only the person can answer it")
    (is (true? (unrun (conduct/decide driving blocked stop ctx)))
        "a person's turn ended no Run's instruction: it too went to whoever answers for the workstream")))

(deftest an-instruction-whose-prompt-was-never-recorded-lapses
  (let [p     {:token "P" :at "2026-10-16T08:00:00Z" :via :send :seen? false}
        later {:event :stop :at "2026-10-16T08:30:00Z"}
        f     (assoc driving :pending p :queued "next")]
    (is (= [:send] (kinds (conduct/decide f done-row [] (assoc ctx :latest later))))
        "a Stop after it was given is the latest event and the listing shows it done: it keeps nothing back")
    (testing "it lapses only on all three"
      (is (= [] (kinds (conduct/decide f (assoc done-row :state "working") [] (assoc ctx :latest later)))))
      (is (= [] (kinds (conduct/decide f done-row [] (assoc ctx :latest {:event :stop :at "2026-10-16T07:00:00Z"}))))
          "a Stop from before it was given")
      (is (= [] (kinds (conduct/decide f done-row [] (assoc ctx :latest {:event :prompt :at "2026-10-16T08:30:00Z"})))))
      (is (= [] (kinds (conduct/decide (assoc f :pending (assoc p :seen? true)) done-row [] (assoc ctx :latest later))))
          "a recorded prompt carried its token: it is under way, not lapsed"))
    (testing "a lapsed instruction is still ended by a Stop whose turn carried its token"
      (let [d1 (conduct/decide f done-row [] (assoc ctx :latest later))
            d2 (conduct/decide (:facet d1) done-row [{:event :prompt :prompt "late\n\n[nido-send P]"}
                                                     {:event :stop :background-tasks 0 :crons 0}] ctx)]
        (is (= [p] (get-in d1 [:facet :lapsed])) "kept when the next instruction replaced it")
        (is (= "T" (get-in d1 [:facet :pending :token])))
        (is (= (assoc p :seen? true) (some (fn [[k _ i]] (when (= :turn-ended k) i)) (:actions d2))))
        (is (= [] (get-in d2 [:facet :lapsed])))
        (is (= "T" (get-in d2 [:facet :pending :token])) "and the instruction given after it stays outstanding")))))

(deftest denials-surface-only-while-nido-drives
  (is (= [:denial] (kinds (conduct/decide driving done-row [{:event :permission-denied :tool-name "Bash"}] ctx))))
  (is (= [] (kinds (conduct/decide (assoc driving :driver :nobody) done-row
                                   [{:event :permission-denied :tool-name "Bash"}] ctx)))))

;; ── the verbs, against records on disk ──────────────────────────────────────

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root       (constantly (str tmp))
                       sstate/sessions-root (constantly (str (fs/path tmp "sessions")))
                       clock/now-iso        (constantly "2026-10-16T09:00:00Z")]
           (cstate/ensure-dirs!)
           (session/create! :nido "ws-1" {:name "kid" :weight :light :autonomy nil})
           (f))
         (finally (fs/delete-tree tmp)))))

(defn- facet [] (:claude (session/read-session :nido "ws-1" "kid")))

(deftest adopt-refuses-what-does-not-show-the-baseline
  (with-tmp
    (fn []
      (with-redefs [substrate/listing (constantly [{:sessionId "s" :kind "background" :id "s" :cwd "/home/kid"}])]
        (let [reason #(:reason (ex-data (try (conduct/adopt! {:project :nido :ws-id "ws-1" :session-name "kid" :session-id "s"})
                                             (catch Exception e e))))]
          (is (= :no-session-start (reason)))
          (events/append! "s" {:event :session-start})
          (events/append! "s" {:event :prompt :permission-mode "auto"})
          (is (= :started-elsewhere (reason)) "a start naming no directory shows none")
          (events/append! "s" {:event :session-start :cwd "/home/kid"})
          (events/append! "s" {:event :prompt :permission-mode "default"})
          (events/append! "s" {:event :notification})
          (is (= :not-auto (reason)) "a notification, which reports no mode, does not hide the one before it")
          (events/append! "s" {:event :prompt :permission-mode "auto"})
          (events/append! "s" {:event :stop})
          (is (= :not-auto (reason)) "the latest event itself must show auto")
          (is (nil? (facet)) "a refusal writes nothing")))
      (with-redefs [substrate/listing (constantly [{:sessionId "s" :kind "interactive"}])]
        (is (= :not-background
               (:reason (ex-data (try (conduct/adopt! {:project :nido :ws-id "ws-1" :session-name "kid" :session-id "s"})
                                      (catch Exception e e))))))))))

(deftest bypassed-permissions-show-the-baseline
  (let [shown {:started? true :start-cwd "/home/kid"}
        row   {:cwd "/home/kid"}]
    (is (nil? (conduct/baseline-refusal (assoc shown :mode "bypassPermissions") row)) "the launch baseline")
    (is (nil? (conduct/baseline-refusal (assoc shown :mode "auto") row)) "the baseline before it")
    (is (= :not-auto (first (conduct/baseline-refusal (assoc shown :mode "default") row))))))

(deftest adopt-refuses-a-session-started-elsewhere
  (with-tmp
    (fn []
      (events/append! "s" {:event :session-start :cwd "/home/kid"})
      (events/append! "s" {:event :prompt :permission-mode "auto"})
      (with-redefs [substrate/listing (constantly [{:sessionId "s" :kind "background" :id "s0" :cwd "/tmp/x"}])]
        (is (= :started-elsewhere
               (:reason (ex-data (try (conduct/adopt! {:project :nido :ws-id "ws-1" :session-name "kid" :session-id "s"})
                                      (catch Exception e e))))))))))

(deftest adopt-refuses-a-start-no-directory-shows
  (with-tmp
    (fn []
      (events/append! "s" {:event :session-start})
      (events/append! "s" {:event :prompt :permission-mode "auto"})
      (with-redefs [substrate/listing (constantly [{:sessionId "s" :kind "background" :id "s0"}])]
        (is (= :started-elsewhere
               (:reason (ex-data (try (conduct/adopt! {:project :nido :ws-id "ws-1" :session-name "kid" :session-id "s"})
                                      (catch Exception e e)))))
            "neither the start nor the listing naming a directory is no evidence of one")))))

(deftest a-hold-written-before-refuses-nothing
  (with-tmp
    (fn []
      (events/append! "s" {:event :session-start :cwd "/home/kid"})
      (events/append! "s" {:event :prompt :permission-mode "auto"})
      (session/update-claude! :nido "ws-1" "kid"
                              (constantly (assoc driving :driver :person :hold {:at "t" :because :foreign-prompt})))
      (with-redefs [substrate/listing (constantly [{:sessionId "s" :kind "background" :id "s0" :cwd "/home/kid"}])]
        (let [f (conduct/adopt! {:project :nido :ws-id "ws-1" :session-name "kid" :session-id "s"})]
          (is (= :nido (:driver f)) "adoption is refused nothing for it")
          (is (not (contains? f :hold)) "and drops it"))))))

(deftest releasing-a-session-leaves-nobody-driving-it
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid"
                              (constantly (assoc driving :driver :person :hold {:at "t" :because :foreign-prompt})))
      (conduct/release! :nido "ws-1" "kid")
      (is (= :nobody (:driver (facet))) "a person's old hold leaves no one driving it once released")
      (is (not (contains? (facet) :hold)))
      (session/update-claude! :nido "ws-1" "kid" #(assoc % :driver :nido))
      (conduct/release! :nido "ws-1" "kid")
      (is (= :nobody (:driver (facet)))))))

(deftest adopt-reads-from-now-on-not-from-what-came-before
  (with-tmp
    (fn []
      (events/append! "s" {:event :session-start :cwd "/home/kid" :at "2026-10-16T08:00:00Z"})
      (events/append! "s" {:event :prompt :prompt "a person typed this earlier" :permission-mode "auto"
                           :at "2026-10-16T08:00:01Z"})
      (events/append! "s" {:event :stop :permission-mode "auto" :background-tasks 0 :crons 0
                           :at "2026-10-16T08:00:02Z"})
      (with-redefs [substrate/listing (constantly [{:sessionId "s" :kind "background" :id "s0" :cwd "/home/kid"}])]
        (conduct/adopt! {:project :nido :ws-id "ws-1" :session-name "kid" :session-id "s"})
        (is (= {:driver :nido :origin :person :bg-id "s0"} (select-keys (facet) [:driver :origin :bg-id :cursor])))
        (is (= "2026-10-16T09:00:00Z" (:since (:reading (session/read-session :nido "ws-1" "kid"))))
            "conduct reads the record from its adoption")
        (let [r (conduct/conduct! {:projects [:nido]})]
          (is (= [] (:turn-ends r)) "the turn before it is not replayed")
          (is (= [] (:prompts r))))))))

(deftest start-records-the-token-before-dispatching
  (with-tmp
    (fn []
      (let [seen (atom nil)]
        (with-redefs [substrate/trusted?  (constantly true)
                      substrate/listing   (constantly [])
                      substrate/dispatch! (fn [{:keys [prompt]}]
                                            (reset! seen {:prompt prompt :facet (facet)
                                                          :note (:dispatching (session/read-session :nido "ws-1" "kid"))})
                                            {:bg-id "b" :session-id "b-1"})]
          (conduct/start! {:project :nido :ws-id "ws-1" :session-name "kid" :cwd "/wt" :prompt "go"}))
        (let [token (get-in @seen [:note :token])]
          (is (some? token) "the token is on the record's dispatching note when dispatch runs")
          (is (nil? (:facet @seen)) "and no facet exists before the session has an id")
          (is (= (conduct/tagged "go" token) (:prompt @seen)))
          (is (= {:session-id "b-1" :origin :nido :driver :nido :pending {:token token :at (get-in @seen [:note :at])
                                                                         :via :dispatch :seen? false}}
                 (select-keys (facet) [:session-id :origin :driver :pending])))
          (is (nil? (:dispatching (session/read-session :nido "ws-1" "kid"))) "the note is cleared"))))))

(deftest start-in-an-untrusted-directory-needs-a-trusted-source
  (with-tmp
    (fn []
      (let [accepted (atom [])]
        (with-redefs [substrate/trusted?        (fn [d] (= d "/src"))
                      substrate/listing         (constantly [])
                      substrate/ensure-trusted! (fn [d _] (swap! accepted conj d) true)
                      substrate/dispatch!       (constantly {:bg-id "b" :session-id "b-1"})]
          (is (= :workspace-untrusted
                 (:reason (ex-data (try (conduct/start! {:project :nido :ws-id "ws-1" :session-name "kid"
                                                         :cwd "/wt" :prompt "go" :source "/elsewhere"})
                                        (catch Exception e e))))))
          (is (= [] @accepted) "nothing untrusted is vouched for")
          (conduct/start! {:project :nido :ws-id "ws-1" :session-name "kid" :cwd "/wt" :prompt "go" :source "/src"})
          (is (= ["/wt"] @accepted)))))))

(deftest start-refuses-a-held-record-only-as-a-live-one
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly (assoc driving :hold {:at "t" :because :foreign-prompt})))
      (with-redefs [substrate/trusted?  (constantly true)
                    substrate/listing   (constantly [done-row])
                    substrate/dispatch! (fn [_] (throw (ex-info "must not" {})))]
        (is (= :live (:reason (ex-data (try (conduct/start! {:project :nido :ws-id "ws-1" :session-name "kid" :cwd "/wt" :prompt "go"})
                                            (catch Exception e e)))))
            "its session is alive; the hold is no reason of its own"))
      (with-redefs [substrate/trusted?  (constantly true)
                    substrate/listing   (constantly [])
                    substrate/dispatch! (constantly {:bg-id "b" :session-id "b-1"})]
        (conduct/start! {:project :nido :ws-id "ws-1" :session-name "kid" :cwd "/wt" :prompt "go"})
        (is (= "b-1" (:session-id (facet))) "stopped, it is started over whatever hold it carried")))))

(deftest a-start-over-a-retired-held-conversation-inherits-nothing-of-it
  ;; Replacing an occupant a person had taken over: retire! stops its session and keeps the hold,
  ;; which was that conversation's. Once the listing shows it stopped, the new conversation starts
  ;; with nothing of the old facet.
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid"
                              (constantly (assoc driving :hold {:at "t" :because :foreign-prompt}
                                                 :queued "stale")))
      (with-redefs [substrate/trusted?  (constantly true)
                    substrate/listing   (constantly [])
                    substrate/dispatch! (constantly {:bg-id "b" :session-id "b-1"})]
        (conduct/start! {:project :nido :ws-id "ws-1" :session-name "kid" :cwd "/wt" :prompt "go"}))
      (is (= "b-1" (:session-id (facet))))
      (is (nil? (:hold (facet))))
      (is (nil? (:queued (facet)))))))
(deftest a-dispatch-that-left-a-session-running-keeps-its-note
  (with-tmp
    (fn []
      (with-redefs [substrate/trusted?  (constantly true)
                    substrate/listing   (constantly [])
                    substrate/dispatch! (fn [_] (throw (ex-info "unlisted" {:reason :session-unlisted
                                                                           :bg-id "b" :left-running "b"})))]
        (is (thrown? Exception (conduct/start! {:project :nido :ws-id "ws-1" :session-name "kid" :cwd "/wt" :prompt "go"})))
        (is (nil? (facet)) "no facet without the session's id")
        (is (= "b" (:bg-id (:dispatching (session/read-session :nido "ws-1" "kid"))))
            "the note keeps the process it launched, for a later listing to name")))))

(deftest start-refuses-a-record-whose-session-is-alive-and-driven
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly driving))
      (with-redefs [substrate/trusted?  (constantly true)
                    substrate/listing   (constantly [done-row])
                    substrate/dispatch! (fn [_] (throw (ex-info "must not" {})))]
        (is (= :live (:reason (ex-data (try (conduct/start! {:project :nido :ws-id "ws-1" :session-name "kid" :cwd "/wt" :prompt "go"})
                                            (catch Exception e e)))))))
      (is (= driving (facet)) "nothing is overwritten"))))

(deftest start-refuses-a-released-record-whose-session-is-still-alive
  ;; Whoever drives it: a start over a released record would leave the earlier session running
  ;; with nothing recording it.
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly (assoc driving :driver :nobody)))
      (with-redefs [substrate/trusted?  (constantly true)
                    substrate/listing   (constantly [done-row])
                    substrate/dispatch! (fn [_] (throw (ex-info "must not" {})))]
        (is (= :live (:reason (ex-data (try (conduct/start! {:project :nido :ws-id "ws-1" :session-name "kid" :cwd "/wt" :prompt "go"})
                                            (catch Exception e e))))))))))

(deftest seat-messages-are-given-in-the-order-owed-after-any-queued-instruction
  (let [owed [{:id "m1" :text "first"} {:id "m2" :text "second"}]
        f    (assoc driving :queued "do it")
        d1   (conduct/decide f done-row [] (assoc ctx :owed owed))]
    (is (= ["do it" nil] (subvec (first (:actions d1)) 3)) "the queued instruction goes first")
    (let [d2 (conduct/decide (assoc (:facet d1) :pending nil) done-row [] (assoc ctx :owed owed))]
      (is (= ["first" "m1"] (subvec (first (:actions d2)) 3)) "then the oldest message owed, by its id")
      (is (nil? (get-in d2 [:facet :queued]))))))
(deftest start-refuses-while-a-start-is-in-flight
  (with-tmp
    (fn []
      (session/update-substrate! :nido "ws-1" "kid" (constantly {:claude nil :dispatching {:token "T0" :at "t"}}))
      (with-redefs [substrate/trusted?  (constantly true)
                    substrate/listing   (constantly [])
                    substrate/dispatch! (fn [_] (throw (ex-info "must not" {})))]
        (is (= :dispatching (:reason (ex-data (try (conduct/start! {:project :nido :ws-id "ws-1" :session-name "kid" :cwd "/wt" :prompt "go"})
                                                   (catch Exception e e))))))))))

(deftest a-failed-dispatch-leaves-no-facet
  (with-tmp
    (fn []
      (with-redefs [substrate/trusted?  (constantly true)
                    substrate/listing   (constantly [])
                    substrate/dispatch! (fn [_] (throw (ex-info "no" {})))]
        (is (thrown? Exception (conduct/start! {:project :nido :ws-id "ws-1" :session-name "kid" :cwd "/wt" :prompt "go"})))
        (is (nil? (facet)))
        (is (nil? (:dispatching (session/read-session :nido "ws-1" "kid"))) "nothing left in flight")))))

(defn- auto-baseline! [& [cwd]]
  (events/append! "s" {:event :session-start :cwd (or cwd "/home/kid")})
  (events/append! "s" {:event :stop :permission-mode "auto" :background-tasks 0 :crons 0}))

(deftest a-launch-the-listing-names-later-is-driven-by-its-full-id
  (with-tmp
    (fn []
      (auto-baseline!)
      (session/update-substrate! :nido "ws-1" "kid"
                                 (constantly {:claude nil :dispatching {:token "T" :at "t" :bg-id "b"}}))
      (events/append! "s" {:event :prompt :prompt "go\n\n[nido-send T]" :permission-mode "auto"})
      (events/append! "s" {:event :stop :last-message "ok" :background-tasks 0 :crons 0 :permission-mode "auto"})
      (with-redefs [substrate/listing (constantly [(assoc done-row :id "b")])]
        (let [r (conduct/conduct! {:projects [:nido]})]
          (is (= "ok" (get-in (last (:turn-ends r)) [:event :last-message])))
          (is (= [{:project :nido :ws-id "ws-1" :session "kid"}] (:named r)) "named by its :bg-id")
          (is (= {:session-id "s" :pending nil} (select-keys (facet) [:session-id :pending]))
              "its first instruction settles")
          (is (nil? (:dispatching (session/read-session :nido "ws-1" "kid")))))))))

(deftest an-outstanding-instruction-survives-release-and-adoption
  (with-tmp
    (fn []
      (auto-baseline!)
      (let [p {:token "T" :at "t" :via :send :seen? true}]
        (session/update-claude! :nido "ws-1" "kid" (constantly (assoc driving :pending p :cursor 2)))
        (conduct/release! :nido "ws-1" "kid")
        (is (= p (:pending (facet))) "released, it is still outstanding")
        (events/append! "s" {:event :prompt :permission-mode "auto"})
        (with-redefs [substrate/listing (constantly [done-row])]
          (conduct/adopt! {:project :nido :ws-id "ws-1" :session-name "kid" :session-id "s"})
          (is (= p (:pending (facet))) "adopted again, it is still outstanding")
          (conduct/release! :nido "ws-1" "kid")
          (events/append! "s" {:event :stop :background-tasks 0 :crons 0 :permission-mode "auto"})
          (conduct/adopt! {:project :nido :ws-id "ws-1" :session-name "kid" :session-id "s"})
          (is (= p (:pending (facet))) "adoption moves no read position past its Stop")
          (let [r (conduct/conduct! {:projects [:nido]})]
            (is (= [p] (map :instruction (:turn-ends r))) "so the next pass reads the Stop, which ends it")
            (is (nil? (:pending (facet))))))))))

(deftest the-shared-baseline-check-is-public
  (is (nil? (conduct/baseline-refusal {:started? true :start-cwd "/home/kid" :mode "auto"} done-row)))
  (is (= :not-auto (first (conduct/baseline-refusal {:started? true :start-cwd "/home/kid" :mode "default"} done-row)))))

(deftest conduct-gives-a-queued-instruction-and-reports-the-turn
  (with-tmp
    (fn []
      (auto-baseline!)
      (session/update-claude! :nido "ws-1" "kid" (constantly (assoc driving :cursor 2)))
      (conduct/instruct! :nido "ws-1" "kid" "next")
      (let [sent (atom nil)]
        (with-redefs [substrate/listing (constantly [done-row])
                      substrate/send!   (fn [row text] (reset! sent [(:pid row) text]) {:delivered? true})]
          (let [r (conduct/conduct! {:projects [:nido]})]
            (is (= 1 (count (:delivered r))))
            (is (= 7 (first @sent)))
            (is (= (get-in (facet) [:pending :token])
                   (second (re-find #"\[nido-send (.+)\]$" (second @sent))))
                "the token delivered is the one recorded")))
        (events/append! "s" {:event :prompt :prompt (second @sent) :permission-mode "auto"})
        (events/append! "s" {:event :stop :last-message "done" :background-tasks 0 :crons 0 :permission-mode "auto"})
        (with-redefs [substrate/listing (constantly [done-row])]
          (let [r (conduct/conduct! {:projects [:nido]})]
            (is (= ["done"] (map #(get-in % [:event :last-message]) (:turn-ends r))))
            (is (= [nil] (map :run (:turn-ends r))) "an instruction given for no Run ends as no Run's")
            (is (nil? (:pending (facet))))))))))

(deftest a-turn-end-names-the-run-its-instruction-was-recorded-for
  (with-tmp
    (fn []
      (auto-baseline!)
      (session/update-claude! :nido "ws-1" "kid" (constantly (assoc driving :cursor 2)))
      (conduct/instruct! :nido "ws-1" "kid" "/drive-home" "run-7")
      (let [sent (atom nil)]
        (with-redefs [substrate/listing (constantly [done-row])
                      substrate/send!   (fn [_ text] (reset! sent text) {:delivered? true})]
          (conduct/conduct! {:projects [:nido]}))
        (is (= "run-7" (get-in (facet) [:pending :for])) "recorded with the instruction, before it is given")
        (events/append! "s" {:event :prompt :prompt @sent :permission-mode "auto"})
        (events/append! "s" {:event :stop :last-message "done" :background-tasks 0 :crons 0 :permission-mode "auto"})
        (with-redefs [substrate/listing (constantly [done-row])]
          (is (= ["run-7"] (map :run (:turn-ends (conduct/conduct! {:projects [:nido]}))))))))))

(deftest conduct-keeps-back-what-a-lost-baseline-would-deliver
  (with-tmp
    (fn []
      (auto-baseline!)
      (events/append! "s" {:event :stop :permission-mode "default" :background-tasks 0 :crons 0})
      (session/update-claude! :nido "ws-1" "kid" (constantly (assoc driving :queued "next" :cursor 3)))
      (with-redefs [substrate/listing (constantly [done-row])
                    substrate/send!   (fn [& _] (throw (ex-info "must not" {})))]
        (is (= [{:project :nido :ws-id "ws-1" :session "kid" :because :not-auto}]
               (:refused (conduct/conduct! {:projects [:nido]})))))
      (is (= "next" (:queued (facet)))))))

(deftest conduct-wakes-a-session-from-its-own-directory
  (with-tmp
    (fn []
      (auto-baseline! "/home/kid")
      (session/update-claude! :nido "ws-1" "kid" (constantly (assoc driving :queued "next" :cursor 2)))
      (let [woke (atom nil)]
        (with-redefs [substrate/listing (constantly [(assoc (dissoc done-row :pid) :cwd "/home/kid")])
                      substrate/wake!   (fn [sid _ opts] (reset! woke [sid (:cwd opts)]) {:woke? true})]
          (is (= 1 (count (:delivered (conduct/conduct! {:projects [:nido]}))))))
        (is (= ["s" "/home/kid"] @woke))))))

(deftest conduct-wakes-a-session-left-blocked-and-idle-after-its-stop
  (with-tmp
    (fn []
      (auto-baseline!)
      (events/append! "s" {:event :notification :notification-type "idle_prompt"})
      (session/update-claude! :nido "ws-1" "kid" (constantly (assoc driving :queued "reproduce it" :cursor 3)))
      (let [woke (atom nil)]
        (with-redefs [substrate/listing (constantly [(assoc (dissoc done-row :pid) :state "blocked")])
                      substrate/wake!   (fn [sid text _] (reset! woke [sid text]) {:woke? true})]
          (let [r (conduct/conduct! {:projects [:nido]})]
            (is (empty? (:refused r)) "the idle notification is not read as leaving the baseline")
            (is (= 1 (count (:delivered r))))))
        (is (= "s" (first @woke)))))))

(deftest a-wake-that-left-a-copy-running-is-reported-not-retried
  (with-tmp
    (fn []
      (auto-baseline!)
      (session/update-claude! :nido "ws-1" "kid" (constantly (assoc driving :queued "next" :cursor 2)))
      (with-redefs [substrate/listing (constantly [(dissoc done-row :pid)])
                    substrate/wake!   (fn [& _] (throw (ex-info "copy stuck" {:reason :copy-cleanup-failed
                                                                              :copy "c1" :left-running "c1"})))]
        (let [r (conduct/conduct! {:projects [:nido]})]
          (is (= [{:project :nido :ws-id "ws-1" :session "kid" :via :wake :running "c1"}] (:left-running r)))
          (is (empty? (:failed r))))
        (is (= {:queued nil :via :wake} {:queued (:queued (facet)) :via (get-in (facet) [:pending :via])})
            "the instruction stays outstanding")
        (is (empty? (:delivered (conduct/conduct! {:projects [:nido]}))) "and nothing is given again")))))

(deftest an-undelivered-instruction-goes-back-to-the-queue
  (with-tmp
    (fn []
      (auto-baseline!)
      (session/update-claude! :nido "ws-1" "kid" (constantly (assoc driving :queued "next" :cursor 2)))
      (with-redefs [substrate/listing (constantly [done-row])
                    substrate/send!   (constantly {:delivered? false :because :no-inbox})]
        (is (= 1 (count (:failed (conduct/conduct! {:projects [:nido]})))))
        (is (= {:pending nil :queued "next"} (select-keys (facet) [:pending :queued])))))))

(deftest an-unreadable-listing-decides-nothing
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly (assoc driving :queued "next")))
      (with-redefs [substrate/listing (fn [_] (throw (ex-info "boom" {})))]
        (is (= {:skipped :listing-failed} (conduct/conduct! {:projects [:nido]}))))
      (is (= "next" (:queued (facet)))))))

(def ^:private idle-row
  "done-row with no live process, in a directory that exists — what recover! may restart."
  (assoc (dissoc done-row :pid) :cwd (str (fs/temp-dir))))

(def ^:private mid-turn
  "A policy session the boot caught inside a turn nido gave it — what recover! may restart."
  (assoc driving :policy {:auto-respawn? true}
         :pending {:token "T" :at "t0" :via :send :seen? true}))

(deftest recover-restarts-a-policy-session-once-per-boot
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly mid-turn))
      (let [respawned (atom [])]
        (with-redefs [substrate/listing  (constantly [idle-row])
                      substrate/respawn! (fn [id _] (swap! respawned conj id) true)]
          (let [r1 (conduct/recover! {:projects [:nido] :boot "B1"})
                r2 (conduct/recover! {:projects [:nido] :boot "B1"})
                r3 (conduct/recover! {:projects [:nido] :boot "B2"})]
            (is (= [{:project :nido :ws-id "ws-1" :session "kid"}] (:restarted r1)))
            (is (= [] (:restarted r2)) "a second start in the same boot restarts nothing")
            (is (= [{:project :nido :ws-id "ws-1" :session "kid"}] (:resumable r2)))
            (is (= 1 (count (:restarted r3))) "a new boot restarts it again")
            (is (= ["s" "s"] @respawned))))))))

(deftest a-refused-restart-is-reported-and-not-retried
  ;; Retrying a refusal at the next start kept a boot unrecovered for as long as one refusal never
  ;; cleared, and every later start then ran as the first. A refused session stays resumable.
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly mid-turn))
      (let [refuse? (atom true)]
        (with-redefs [substrate/listing  (constantly [idle-row])
                      substrate/respawn! (fn [_ _] (if @refuse? (throw (ex-info "refused" {:reason :respawn-refused})) true))]
          (let [r1 (conduct/recover! {:projects [:nido] :boot "B1"})]
            (is (= [] (:restarted r1)))
            (is (= 1 (count (:failed r1))) "the refusal is answered for the caller to report")
            (is (nil? (:recovered-boot (facet))) "not marked restarted"))
          (reset! refuse? false)
          (let [r2 (conduct/recover! {:projects [:nido] :boot "B1"})]
            (is (= [] (:restarted r2)) "a later start in the same boot restarts nothing")
            (is (false? (:first-start? r2)))))))))

(deftest recover-attempts-no-session-the-listing-does-not-name
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly mid-turn))
      (with-redefs [substrate/listing  (constantly [])
                    substrate/respawn! (fn [& _] (throw (ex-info "must not" {})))]
        (let [r (conduct/recover! {:projects [:nido] :boot "B1"})]
          (is (= [[] [] [] []] ((juxt :restarted :failed :gone :resumable) r))
              "nothing can be said of it: neither gone nor resumable"))))))

(deftest a-notification-after-the-boot-shows-the-session-outlived-it
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly mid-turn))
      (events/append! "s" {:event :notification})
      (with-redefs [substrate/listing  (constantly [idle-row])
                    substrate/respawn! (fn [& _] (throw (ex-info "must not" {})))]
        (is (= [] (:restarted (conduct/recover! {:projects [:nido] :boot "B1"
                                                 :boot-at "2026-10-16T08:00:00Z"}))))))))

(deftest recover-leaves-a-session-without-the-policy-resumable
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly driving))
      (with-redefs [substrate/listing  (constantly [idle-row])
                    substrate/respawn! (fn [& _] (throw (ex-info "must not" {})))]
        (let [r (conduct/recover! {:projects [:nido] :boot "B1"})]
          (is (= [] (:restarted r)))
          (is (= 1 (count (:resumable r)))))))))

(deftest recover-answers-a-session-whose-directory-is-gone-and-still-recovers-the-boot
  ;; A refused restart is retried at the next start, and the boot stays unrecovered until then.
  ;; A session whose worktree was deleted is refused at every start, so counted as a failure it
  ;; held the boot unrecovered for good — and every later start in that boot ran as the first.
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly mid-turn))
      (with-redefs [substrate/listing  (constantly [(assoc idle-row :cwd "/nonexistent/worktree")])
                    substrate/respawn! (fn [& _] (throw (ex-info "must not" {})))]
        (let [r1 (conduct/recover! {:projects [:nido] :boot "B1"})
              r2 (conduct/recover! {:projects [:nido] :boot "B1"})]
          (is (= [{:project :nido :ws-id "ws-1" :session "kid"}] (:gone r1)))
          (is (= [] (:failed r1)) "a session nothing can restart is not a failed restart")
          (is (false? (:first-start? r2)) "so the boot is recovered, and a later start is not a first"))))))

(deftest recover-leaves-a-session-that-ended-after-the-boot
  ;; Only the boot's casualties are restarted. One that recorded anything after the boot began
  ;; ended in this boot — finished, stopped — and restarting it brings back a finished session.
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly mid-turn))
      (events/append! "s" {:event :stop})          ; at the test clock, 2026-10-16T09:00:00Z
      (with-redefs [substrate/listing  (constantly [idle-row])
                    substrate/respawn! (fn [& _] (throw (ex-info "must not" {})))]
        (let [after  (conduct/recover! {:projects [:nido] :boot "B1" :boot-at "2026-10-16T08:00:00Z"})]
          (is (= [] (:restarted after)))
          (is (= [{:project :nido :ws-id "ws-1" :session "kid"}] (:resumable after))
              "it stays resumable from its saved conversation"))))))

(deftest recover-restarts-a-session-that-fell-silent-before-the-boot
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly mid-turn))
      (events/append! "s" {:event :notification})
      (with-redefs [substrate/listing  (constantly [idle-row])
                    substrate/respawn! (constantly true)]
        (is (= 1 (count (:restarted (conduct/recover! {:projects [:nido] :boot "B1"
                                                       :boot-at "2026-10-16T10:00:00Z"})))))))))

(deftest recover-leaves-a-session-between-turns-resumable
  ;; The policy outlives every turn, so a Run that finished long before the boot still carries it.
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly (assoc mid-turn :pending nil)))
      (with-redefs [substrate/listing  (constantly [idle-row])
                    substrate/respawn! (fn [& _] (throw (ex-info "must not" {})))]
        (let [r (conduct/recover! {:projects [:nido] :boot "B1"})]
          (is (= [] (:restarted r)))
          (is (= [{:project :nido :ws-id "ws-1" :session "kid"}] (:resumable r))))))))

(deftest recover-restarts-a-session-running-its-own-work
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly (assoc mid-turn :pending nil :own-work? true)))
      (with-redefs [substrate/listing  (constantly [idle-row])
                    substrate/respawn! (constantly true)]
        (is (= 1 (count (:restarted (conduct/recover! {:projects [:nido] :boot "B1"})))))))))

(deftest recover-leaves-a-session-whose-stop-was-never-consumed
  ;; The daemon was down when the turn ended: the facet still holds the token, the events do not.
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly mid-turn))
      (events/append! "s" {:event :stop})
      (with-redefs [substrate/listing  (constantly [idle-row])
                    substrate/respawn! (fn [& _] (throw (ex-info "must not" {})))]
        (is (= [] (:restarted (conduct/recover! {:projects [:nido] :boot "B1"
                                                 :boot-at "2026-10-16T10:00:00Z"}))))))))

(deftest recover-leaves-a-session-whose-stop-a-notification-followed
  ;; Claude Code notifies an idle session after its Stop; the notification starts no turn.
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly mid-turn))
      (events/append! "s" {:event :stop})
      (events/append! "s" {:event :notification})
      (with-redefs [substrate/listing  (constantly [idle-row])
                    substrate/respawn! (fn [& _] (throw (ex-info "must not" {})))]
        (is (= [] (:restarted (conduct/recover! {:projects [:nido] :boot "B1"
                                                 :boot-at "2026-10-16T10:00:00Z"}))))))))

(deftest a-pass-reports-the-sessions-nido-drives-that-are-working
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly driving))
      (with-redefs [substrate/listing (constantly [(assoc done-row :state "working")])]
        (is (= [{:project :nido :ws-id "ws-1" :session "kid"}]
               (:working (conduct/conduct! {:projects [:nido]}))))))))

(deftest retire-releases-then-stops-the-process
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly driving))
      (let [stopped (atom [])]
        (with-redefs [substrate/stop! (fn [id _] (swap! stopped conj id) true)]
          (conduct/retire! :nido "ws-1" "kid" {}))
        (is (= ["s"] @stopped))
        (is (= :retired (:driver (facet))) "its conversation is over until one is driven on the record again")
        (conduct/release! :nido "ws-1" "kid")
        (is (= :retired (:driver (facet))) "releasing it drives nothing"))
      (is (nil? (with-redefs [session/read-session (constantly {:claude nil})]
                  (conduct/retire! :nido "ws-1" "kid" {})))
          "a session without a facet has nothing to retire"))))

(deftest retire-stops-a-process-a-failed-start-left-running
  (with-tmp
    (fn []
      (session/update-substrate! :nido "ws-1" "kid" (constantly {:dispatching {:token "t" :at "now" :bg-id "bg-9"}}))
      (let [stopped (atom [])]
        (with-redefs [substrate/stop! (fn [id _] (swap! stopped conj id) true)]
          (conduct/retire! :nido "ws-1" "kid" {}))
        (is (= ["bg-9"] @stopped))
        (is (nil? (:dispatching (session/read-session :nido "ws-1" "kid"))) "its note cleared, so nothing names it after")))))

(deftest a-seat-message-is-marked-delivered-only-once-the-substrate-took-it
  ;; What a session is owed is kept where the mail is, not on its facet: a message whose delivery
  ;; failed is simply still owed, and one delivered is marked so.
  (with-tmp
    (fn []
      (auto-baseline!)
      (session/update-claude! :nido "ws-1" "kid" (constantly (assoc driving :cursor 2)))
      (let [delivered (atom [])
            mail {:owed (fn [_ _ n] (if (= "kid" n) (vec (remove (comp (set @delivered) :id)
                                                                 [{:id "m1" :text "first"} {:id "m2" :text "second"}]))
                                       []))
                  :delivered! (fn [_ _ id token]
                                (is (= token (get-in (facet) [:pending :token])) "delivered under the token given")
                                (swap! delivered conj id))}]
        (with-redefs [substrate/listing (constantly [done-row])
                      substrate/send!   (fn [_ _] {:delivered? false})]
          (is (= 1 (count (:failed (conduct/conduct! {:projects [:nido] :mail mail}))))))
        (is (= [] @delivered) "a failed delivery leaves it owed")
        (is (nil? (:pending (facet))))
        (is (nil? (:queued (facet))) "and puts nothing on the facet")
        (with-redefs [substrate/listing (constantly [done-row])
                      substrate/send!   (fn [_ text] (is (str/starts-with? text "first")) {:delivered? true})]
          (is (= 1 (count (:delivered (conduct/conduct! {:projects [:nido] :mail mail}))))))
        (is (= ["m1"] @delivered))))))

(deftest outstanding-is-what-the-next-fold-would-leave
  (with-tmp
    (fn []
      (let [pending {:token "T" :at "2026-10-16T08:00:00Z" :via :send :seen? true :for "r1"}
            f       {:session-id "o-1" :driver :nido :pending pending}
            s       {:claude f :reading {:since "2026-10-16T07:00:00Z" :positions {"o-1" 1}}}]
        (events/append! "o-1" {:event :prompt :prompt "go\n\n[nido-send T]"})
        (is (= pending (conduct/outstanding s)) "nothing past the position ends it")
        (events/append! "o-1" {:event :notification :notification-type "idle_prompt"})
        (is (= pending (conduct/outstanding s)) "a notification is not a turn end")
        (events/append! "o-1" {:event :stop :last-message "done" :background-tasks 0 :crons 0})
        (is (nil? (conduct/outstanding s)) "a Stop no pass has folded yet ends it")
        (is (nil? (conduct/outstanding (assoc-in s [:claude :pending] nil))) "nothing given, nothing outstanding")
        (is (nil? (conduct/outstanding {:claude (assoc f :cursor 1)}))
            "a facet written before positions were kept reads from its cursor")))))

;; ── reading every conversation of every session ─────────────────────────────

(deftest a-persons-prompt-into-a-driven-session-holds-nothing
  (with-tmp
    (fn []
      (auto-baseline!)
      (session/update-claude! :nido "ws-1" "kid" (constantly (assoc driving :cursor 2 :queued "next")))
      (events/append! "s" {:event :prompt :prompt "let me steer this" :permission-mode "auto"})
      (events/append! "s" {:event :stop :last-message "steered" :background-tasks 0 :crons 0 :permission-mode "auto"})
      (let [sent (atom nil)]
        (with-redefs [substrate/listing (constantly [done-row])
                      substrate/send!   (fn [_ text] (reset! sent text) {:delivered? true})]
          (let [r (conduct/conduct! {:projects [:nido]})]
            (is (= [{:session "kid" :instruction nil :conversation "s" :said "steered"}]
                   (map #(-> (select-keys % [:session :instruction :conversation]) (assoc :said (get-in % [:event :last-message])))
                        (:turn-ends r)))
                "the person's turn end is reported, ending no instruction")
            (is (= ["let me steer this"] (map (comp :prompt :event) (:prompts r))))
            (is (= :nido (:driver (facet))) "nido still drives it")
            (is (not (contains? (facet) :hold)))
            (is (= 1 (count (:delivered r))) "and gives it what was queued once its turn is done")
            (is (str/starts-with? @sent "next"))))))))

(defn- home!
  "Make brian-less session `n` of project nido a session home under the test's sessions root, and
   answer its path."
  [n]
  (let [home (str (fs/path (sstate/sessions-root) "nido" n))]
    (fs/create-dirs home)
    (spit (str (fs/path home "CLAUDE.md")) "briefing")
    home))

(deftest every-conversation-of-a-plain-session-is-read-once-from-its-own-position
  (with-tmp
    (fn []
      (session/create! :nido "ws-1" {:name "plain" :weight :light :autonomy nil})
      (let [home (home! "plain")
            ev   (fn [conv m] (events/append! conv (assoc m :cwd home)))]
        ;; Recorded before conduct began reading the session: history, never replayed.
        (ev "c-old" {:event :prompt :prompt "earlier" :at "2026-10-16T08:00:00Z"})
        (ev "c-old" {:event :stop :last-message "earlier turn" :at "2026-10-16T08:00:01Z"})
        (with-redefs [substrate/listing (constantly [])]
          (let [r1 (conduct/conduct! {:projects [:nido]})]
            (is (= [] (:turn-ends r1)) "a Stop from before conduct read the session reaches no one")
            (is (= "2026-10-16T09:00:00Z" (get-in (session/read-session :nido "ws-1" "plain") [:reading :since]))))
          (ev "c-old" {:event :prompt :prompt "again"})
          (ev "c-old" {:event :stop :last-message "old conversation, new turn"})
          (ev "c-new" {:event :prompt :prompt "hello"})
          (ev "c-new" {:event :stop :last-message "new conversation"})
          (let [r2 (conduct/conduct! {:projects [:nido]})]
            (is (= #{["c-old" "old conversation, new turn"] ["c-new" "new conversation"]}
                   (set (map (juxt :conversation (comp :last-message :event)) (:turn-ends r2))))
                "each conversation's new Stop, though the session has no facet")
            (is (every? #(and (= "plain" (:session %)) (nil? (:instruction %)) (false? (:activated? %))) (:turn-ends r2)))
            (is (= #{"again" "hello"} (set (map (comp :prompt :event) (:prompts r2))))))
          (is (= {"c-old" 4 "c-new" 2} (get-in (session/read-session :nido "ws-1" "plain") [:reading :positions]))
              "a position per conversation")
          (is (= [] (:turn-ends (conduct/conduct! {:projects [:nido]}))) "each is read once"))))))

(deftest a-facets-cursor-becomes-its-conversations-position
  (with-tmp
    (fn []
      (auto-baseline!)
      (events/append! "s" {:event :prompt :prompt "x\n\n[nido-send R]" :at "2026-10-16T08:00:00Z"})
      (events/append! "s" {:event :stop :last-message "run done" :at "2026-10-16T08:00:01Z"})
      (session/update-claude! :nido "ws-1" "kid"
                              (constantly (assoc driving :cursor 2 :pending {:token "R" :at "t" :via :send :seen? false :for "run-1"})))
      (with-redefs [substrate/listing (constantly [done-row])]
        (let [r (conduct/conduct! {:projects [:nido]})]
          (is (= ["run-1"] (map :run (:turn-ends r)))
              "what the facet had not read is read, a Run's turn end whenever it was recorded")
          (is (= {"s" 4} (get-in (session/read-session :nido "ws-1" "kid") [:reading :positions])))
          (is (not (contains? (facet) :cursor)) "the facet names only which conversation it drives"))))))

(deftest a-start-changes-only-which-conversation-the-facet-names
  (with-tmp
    (fn []
      (let [home (home! "kid")]
        (session/update-claude! :nido "ws-1" "kid" (constantly (assoc driving :driver :retired)))
        (events/append! "s" {:event :session-start :cwd home})
        (with-redefs [substrate/listing (constantly [])]
          (conduct/conduct! {:projects [:nido]}))
        ;; The old conversation's last turn ends after the pass that read it, before the start.
        (events/append! "s" {:event :stop :last-message "old one's last" :cwd home})
        (with-redefs [substrate/trusted?  (constantly true)
                      substrate/listing   (constantly [])
                      substrate/dispatch! (fn [{:keys [prompt]}]
                                            (events/append! "n" {:event :prompt :prompt prompt :cwd home})
                                            {:bg-id "nb" :session-id "n"})]
          (conduct/start! {:project :nido :ws-id "ws-1" :session-name "kid" :cwd home :prompt "go"}))
        (events/append! "n" {:event :stop :last-message "new one's first" :cwd home})
        (with-redefs [substrate/listing (constantly [])]
          (let [r (conduct/conduct! {:projects [:nido]})]
            (is (= #{["s" false "old one's last"] ["n" true "new one's first"]}
                   (set (map (juxt :conversation (comp some? :instruction) (comp :last-message :event)) (:turn-ends r))))
                "the old conversation's unread Stop is still read, and the new one's first instruction ends")))))))

(deftest a-stop-is-kept-with-its-words-and-whose-turn-it-ended
  (let [stop {:event :stop :at "t9" :last-message "Intent recorded." :background-tasks 0 :crons 0}]
    (is (= {:at "t9" :message "Intent recorded." :nido? true}
           (get-in (conduct/decide (assoc driving :pending {:token "T" :at "t" :via :send :seen? true})
                                   done-row [stop] ctx)
                   [:facet :last-stop]))
        "a turn that ended nido's instruction")
    (is (= {:at "t9" :message "Intent recorded." :nido? false}
           (get-in (conduct/decide driving done-row [stop] ctx) [:facet :last-stop]))
        "a turn only a person prompted")))

(deftest a-dialog-is-timed-from-the-first-pass-that-sees-it
  (let [dialog (assoc done-row :state "blocked" :status "waiting" :waitingFor "input needed")
        d1     (conduct/decide driving dialog [] ctx)]
    (is (= {:since "t1" :for "input needed"} (get-in d1 [:facet :waiting])))
    (is (= {:since "t1" :for "input needed"}
           (get-in (conduct/decide (:facet d1) dialog [] (assoc ctx :now "t2")) [:facet :waiting]))
        "kept while it stands")
    (is (nil? (get-in (conduct/decide (:facet d1) (assoc dialog :status "idle") [] ctx) [:facet :waiting]))
        "blocked after its turn ended is not a dialog")
    (is (nil? (get-in (conduct/decide (:facet d1) done-row [] ctx) [:facet :waiting])))))
