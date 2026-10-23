(ns nido.coordinator.lane.conduct-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.agent.substrate :as substrate]
   [nido.coordinator.lane.conduct :as conduct]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.session-events :as events]
   [nido.coordinator.record.state :as cstate]))

;; ── decide: the policy, pure ────────────────────────────────────────────────

(def driving
  {:session-id "s" :bg-id "s" :origin :person :driver :nido :hold nil :pending nil :queued nil
   :own-work? false :policy {:auto-respawn? false} :cursor 0 :driving-since "t0"
   :announced-blocked? false :recovered-boot nil})

(def done-row {:sessionId "s" :kind "background" :state "done" :status "idle" :pid 7 :cwd "/home/kid"})
(def ctx {:now "t1" :token "T" :baseline :shown :latest {:event :stop}})

(defn- kinds [d] (mapv first (:actions d)))

(deftest a-queued-instruction-goes-to-a-done-session-with-its-token-already-pending
  (let [d (conduct/decide (assoc driving :queued "do it") done-row [] ctx)]
    (is (= [[:send "do it\n\n[nido-send T]" "T" "do it"]] (:actions d)))
    (is (= {:token "T" :at "t1" :via :send :seen? false} (get-in d [:facet :pending]))
        "recorded in the facet the caller writes before it delivers")
    (is (nil? (get-in d [:facet :queued])))))

(deftest a-session-without-a-live-process-is-woken-instead
  (is (= [:wake] (kinds (conduct/decide (assoc driving :queued "x") (dissoc done-row :pid) [] ctx)))))

(deftest nothing-is-given-unless-every-gate-is-open
  (doseq [[why facet row] [["held"             (assoc driving :queued "x" :hold {:at "t" :because :foreign-prompt}) done-row]
                           ["released"         (assoc driving :queued "x" :driver :nobody) done-row]
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

(deftest a-turn-only-the-listing-shows-holds-the-session
  (let [working (assoc done-row :state "working")
        d1 (conduct/decide driving done-row [{:event :stop :background-tasks 0 :crons 0}]
                           (assoc ctx :latest nil))
        d2 (conduct/decide (:facet d1) working [] ctx)]
    (is (= {:at "t1" :because :foreign-turn} (get-in d2 [:facet :hold])))
    (is (= [:held] (kinds d2))))
  (testing "working in the same pass its Stop is read is that turn's tail, not a new one"
    (let [f (assoc driving :pending {:token "T" :at "t" :via :send :seen? true} :idle-seen :listed)
          d (conduct/decide f (assoc done-row :state "working") [{:event :stop :background-tasks 0 :crons 0}] ctx)]
      (is (nil? (get-in d [:facet :hold])))))
  (testing "nido's own instruction outstanding is not a person's turn"
    (let [f (assoc driving :pending {:token "T" :at "t" :via :send :seen? false} :idle-seen :listed)]
      (is (nil? (get-in (conduct/decide f (assoc done-row :state "working") [] ctx) [:facet :hold]))))))

(deftest usage-limit-holds-sends-back
  (is (not-any? #{:send} (kinds (conduct/decide (assoc driving :queued "x") done-row [] (assoc ctx :hold-sends? true))))))

(deftest nidos-own-prompt-is-recognised-by-its-token
  (let [f (assoc driving :pending {:token "T" :at "t" :via :send :seen? false})
        d (conduct/decide f done-row [{:event :prompt :prompt "do it\n\n[nido-send T]"}] ctx)]
    (is (true? (get-in d [:facet :pending :seen?])))
    (is (nil? (get-in d [:facet :hold])))))

(deftest a-prompt-nido-did-not-give-holds-the-session
  (testing "typed, no token"
    (let [d (conduct/decide (assoc driving :queued "x") done-row [{:event :prompt :prompt "wait, stop" :at "t2"}] ctx)]
      (is (= {:at "t2" :because :foreign-prompt} (get-in d [:facet :hold])))
      (is (not-any? #{:send} (kinds d)) "decided in the same pass: nothing goes out after it")))
  (testing "a paste of an old instruction: its token is no longer pending"
    (let [f (assoc driving :pending {:token "T" :at "t" :via :send :seen? true})]
      (is (some? (get-in (conduct/decide f done-row [{:event :prompt :prompt "x [nido-send T]"}] ctx)
                         [:facet :hold])))))
  (testing "a released session is not held — nobody drives it"
    (is (nil? (get-in (conduct/decide (assoc driving :driver :nobody) done-row
                                      [{:event :prompt :prompt "hi"}] ctx)
                      [:facet :hold])))))

(deftest a-turn-nido-did-not-give-makes-a-person-the-driver
  (testing "a recorded prompt"
    (is (= :person (get-in (conduct/decide driving done-row [{:event :prompt :prompt "mine" :at "t2"}] ctx)
                           [:facet :driver]))))
  (testing "a turn only the listing shows"
    (is (= :person (get-in (conduct/decide (assoc driving :idle-seen :listed) (assoc done-row :state "working") [] ctx)
                           [:facet :driver])))))

(deftest a-stop-ends-the-outstanding-instruction
  (let [f (assoc driving :pending {:token "T" :at "t" :via :send :seen? true})
        d (conduct/decide f done-row [{:event :stop :last-message "ok" :background-tasks 0 :crons 0}] ctx)]
    (is (nil? (get-in d [:facet :pending])))
    (is (nil? (get-in d [:facet :hold])))
    (is (= :turn-ended (ffirst (:actions d))))))

(deftest a-stop-names-the-instruction-it-ended-only-when-nido-gave-that-turn
  (let [p   {:token "T" :at "t" :via :send :seen? true}
        stop {:event :stop :background-tasks 0 :crons 0}
        ended #(some (fn [[k _ i]] (when (= :turn-ended k) [i])) (:actions %))]
    (is (= [p] (ended (conduct/decide (assoc driving :pending p) done-row [stop] ctx)))
        "the Stop after nido's own prompt ended nido's instruction")
    (is (= [nil] (ended (conduct/decide (assoc driving :pending (assoc p :seen? false)) done-row
                                        [{:event :prompt :prompt "mine, not nido's"} stop] ctx)))
        "a person's turn, with nido's instruction outstanding but never prompted, ended none")
    (is (= [nil] (ended (conduct/decide driving done-row [stop] ctx)))
        "nothing outstanding: none")))

(deftest a-turn-after-a-quiet-stop-is-a-person-s
  (doseq [seen [:stopped :listed]]
    (let [d (conduct/decide (assoc driving :idle-seen seen) done-row
                            [{:event :stop :background-tasks 0 :crons 0 :at "t3"}] ctx)]
      (is (= :foreign-turn (get-in d [:facet :hold :because]))))))

(deftest a-turn-underway-when-nido-began-driving-never-holds-it
  (let [d1 (conduct/decide driving (assoc done-row :state "working")
                           [{:event :stop :background-tasks 0 :crons 0 :at "t3"}] ctx)
        d2 (conduct/decide (:facet d1) done-row
                           [{:event :stop :background-tasks 0 :crons 0 :at "t4"}] ctx)]
    (is (nil? (get-in d1 [:facet :hold])) "its Stop ends a turn begun before nido drove it")
    (is (= :foreign-turn (get-in d2 [:facet :hold :because])) "the turn after it is a person's")))

(deftest a-turn-after-a-stop-with-work-running-is-the-session-s-own
  (let [f  (assoc driving :pending {:token "T" :at "t" :via :send :seen? true})
        d1 (conduct/decide f done-row [{:event :stop :background-tasks 1 :crons 0}] ctx)
        d2 (conduct/decide (:facet d1) done-row [{:event :stop :background-tasks 0 :crons 0}] ctx)]
    (is (true? (get-in d1 [:facet :own-work?])))
    (is (nil? (get-in d2 [:facet :hold])) "the background work finishing is not a takeover")))

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

(deftest denials-surface-only-while-nido-drives
  (is (= [:denial] (kinds (conduct/decide driving done-row [{:event :permission-denied :tool-name "Bash"}] ctx))))
  (is (= [] (kinds (conduct/decide (assoc driving :driver :nobody) done-row
                                   [{:event :permission-denied :tool-name "Bash"}] ctx)))))

;; ── the verbs, against records on disk ──────────────────────────────────────

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))
                       clock/now-iso  (constantly "2026-10-16T09:00:00Z")]
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

(deftest a-hold-survives-everything-but-hand-back
  (with-tmp
    (fn []
      (events/append! "s" {:event :session-start :cwd "/home/kid"})
      (events/append! "s" {:event :prompt :permission-mode "auto"})
      (let [hold {:at "t" :because :foreign-prompt}
            adopt #(try (conduct/adopt! {:project :nido :ws-id "ws-1" :session-name "kid" :session-id "s"})
                        (catch Exception e e))]
        (session/update-claude! :nido "ws-1" "kid" (constantly (assoc driving :hold hold)))
        (with-redefs [substrate/listing (constantly [{:sessionId "s" :kind "background" :id "s0" :cwd "/home/kid"}])]
          (is (= {:reason :held :hold hold} (select-keys (ex-data (adopt)) [:reason :hold])))
          (is (= hold (:hold (facet))) "a refusal writes nothing")
          (conduct/release! :nido "ws-1" "kid")
          (is (= hold (:hold (facet))) "releasing does not clear it")
          (is (= :held (:reason (ex-data (adopt)))))
          (conduct/hand-back! :nido "ws-1" "kid")
          (is (nil? (:hold (facet))))
          (is (= :nido (:driver (adopt)))))))))

(deftest releasing-a-held-session-leaves-the-person-driving-it
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid"
                              (constantly (assoc driving :driver :person :hold {:at "t" :because :foreign-prompt})))
      (conduct/release! :nido "ws-1" "kid")
      (is (= :person (:driver (facet))))
      (session/update-claude! :nido "ws-1" "kid" #(assoc % :hold nil :driver :nido))
      (conduct/release! :nido "ws-1" "kid")
      (is (= :nobody (:driver (facet))) "without a hold, nobody drives a released session"))))

(deftest hand-back-ends-an-instruction-whose-stop-it-skips
  ;; A person's prompt held the session while nido's instruction was outstanding; the session's
  ;; Stop was recorded before any pass read it. Handing back skips it, and with it the instruction.
  (with-tmp
    (fn []
      (events/append! "s" {:event :prompt :prompt "x\n\n[nido-send T]"})
      (events/append! "s" {:event :stop :background-tasks 0 :crons 0})
      (session/update-claude! :nido "ws-1" "kid"
                              (constantly (assoc driving :driver :person :cursor 0
                                                 :hold {:at "t" :because :foreign-prompt}
                                                 :pending {:token "T" :at "t" :via :send :seen? true})))
      (conduct/hand-back! :nido "ws-1" "kid")
      (is (nil? (:pending (facet))) "nothing stays outstanding past its Stop")
      (is (= (:cursor (events/events-since "s" nil)) (:cursor (facet))) "turns until now never hold it"))))

(deftest adopt-drives-from-now-on-not-from-what-came-before
  (with-tmp
    (fn []
      (events/append! "s" {:event :session-start :cwd "/home/kid"})
      (events/append! "s" {:event :prompt :prompt "a person typed this earlier" :permission-mode "auto"})
      (with-redefs [substrate/listing (constantly [{:sessionId "s" :kind "background" :id "s0" :cwd "/home/kid"}])]
        (conduct/adopt! {:project :nido :ws-id "ws-1" :session-name "kid" :session-id "s"}))
      (is (= {:driver :nido :origin :person :cursor 2 :bg-id "s0"}
             (select-keys (facet) [:driver :origin :cursor :bg-id]))
          "the earlier prompt is behind the cursor, so it never holds the adopted session"))))

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

(deftest start-refuses-a-record-whose-session-is-held
  (with-tmp
    (fn []
      (let [hold {:at "t" :because :foreign-prompt}]
        (session/update-claude! :nido "ws-1" "kid" (constantly (assoc driving :hold hold)))
        (with-redefs [substrate/trusted?  (constantly true)
                      substrate/listing   (constantly [])
                      substrate/dispatch! (fn [_] (throw (ex-info "must not" {})))]
          (is (= :held (:reason (ex-data (try (conduct/start! {:project :nido :ws-id "ws-1" :session-name "kid" :cwd "/wt" :prompt "go"})
                                              (catch Exception e e)))))))
        (is (= hold (:hold (facet))))))))

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
          (is (nil? (:pending (facet))) "its Stop, recorded and not yet read, ends it"))))))

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
            (is (empty? (:held r)) "its own instruction did not hold it")
            (is (nil? (:pending (facet))))))))))

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

(deftest recover-restarts-a-policy-session-once-per-boot
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly (assoc driving :policy {:auto-respawn? true})))
      (let [respawned (atom [])]
        (with-redefs [substrate/listing  (constantly [(dissoc done-row :pid)])
                      substrate/respawn! (fn [id _] (swap! respawned conj id) true)]
          (let [r1 (conduct/recover! {:projects [:nido] :boot "B1"})
                r2 (conduct/recover! {:projects [:nido] :boot "B1"})
                r3 (conduct/recover! {:projects [:nido] :boot "B2"})]
            (is (= [{:project :nido :ws-id "ws-1" :session "kid"}] (:restarted r1)))
            (is (= [] (:restarted r2)) "a second start in the same boot restarts nothing")
            (is (= [{:project :nido :ws-id "ws-1" :session "kid"}] (:resumable r2)))
            (is (= 1 (count (:restarted r3))) "a new boot restarts it again")
            (is (= ["s" "s"] @respawned))))))))

(deftest recover-records-only-a-restart-claude-code-accepted
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly (assoc driving :policy {:auto-respawn? true})))
      (let [refuse? (atom true)]
        (with-redefs [substrate/listing  (constantly [(dissoc done-row :pid)])
                      substrate/respawn! (fn [_ _] (if @refuse? (throw (ex-info "refused" {:reason :respawn-refused})) true))]
          (let [r1 (conduct/recover! {:projects [:nido] :boot "B1"})]
            (is (= [] (:restarted r1)))
            (is (= 1 (count (:failed r1))))
            (is (nil? (:recovered-boot (facet))) "not marked restarted"))
          (reset! refuse? false)
          (is (= 1 (count (:restarted (conduct/recover! {:projects [:nido] :boot "B1"}))))
              "a later start in the same boot tries it again")
          (is (= "B1" (:recovered-boot (facet)))))))))

(deftest recover-leaves-a-session-without-the-policy-resumable
  (with-tmp
    (fn []
      (session/update-claude! :nido "ws-1" "kid" (constantly driving))
      (with-redefs [substrate/listing  (constantly [(dissoc done-row :pid)])
                    substrate/respawn! (fn [& _] (throw (ex-info "must not" {})))]
        (let [r (conduct/recover! {:projects [:nido] :boot "B1"})]
          (is (= [] (:restarted r)))
          (is (= 1 (count (:resumable r)))))))))

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
        (is (= :nobody (:driver (facet)))))
      (is (nil? (with-redefs [session/read-session (constantly {:claude nil})]
                  (conduct/retire! :nido "ws-1" "kid" {})))
          "a session without a facet has nothing to retire"))))
