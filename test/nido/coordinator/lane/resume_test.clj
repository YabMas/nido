(ns nido.coordinator.lane.resume-test
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [nido.platform.core :as core]
            [nido.coordinator.executor :as ex]
            [nido.coordinator.lane.conduct :as conduct]
            [nido.coordinator.lane.resume :as resume]
            [nido.coordinator.record.runs :as runs]
            [nido.coordinator.record.session :as session]
            [nido.coordinator.record.state :as cstate]
            [nido.coordinator.record.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (cstate/ensure-dirs!)
        (f))
      (finally (fs/delete-tree tmp)))))

(def ^:private autonomy-parked
  {:skill :triage-bug :first-message "x" :agent :claude :claude-session-id nil
   :trigger :triage-bug :limits {:budget "30m"} :priority 4 :uncapped? false
   :on-promote nil :phase :parked
   :phase-history [{:at "2026-06-18T00:00:00Z" :phase :parked}] :error nil})

(defn- write-run!
  "The run owning session `sname`, parked for review — what makes a session wait on a reply —
   registered with its workstream as create-run! does."
  [id ws-id sname sid]
  (fs/create-dirs (cstate/run-dir id))
  (runs/write-run! {:id id :project :brian :trigger :triage-bug
                    :source {:type :manual} :event-payload {} :skill :triage-bug
                    :first-message "/triage-bug" :agent :claude :session-name sname
                    :workstream-id ws-id :claude-session-id sid
                    :limits {:budget "30m"} :priority 0 :session-profile :full
                    :uncapped? false :state :awaiting-review
                    :state-history [{:at "2026-06-18T00:00:00Z" :state :queued}]
                    :artifacts [] :error nil})
  (runs/register! id))

(defn- background!
  "Give session `sname` the :claude facet of a background session nido drives."
  [ws-id sname]
  (session/update-claude! :brian ws-id sname
                          (constantly {:session-id "bg-1" :bg-id "bg-1" :origin :nido :driver :nido
                                       :hold nil :pending nil :queued nil :own-work? false
                                       :policy {:auto-respawn? true} :cursor 0 :driving-since "t"
                                       :announced-blocked? false :recovered-boot nil})))

(deftest resume!-hands-the-reply-to-the-executor
  ;; Admitted under the global cap like any turn, rather than given beside the executor.
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:external-refs []})
            given (atom nil)]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (background! (:id w) "auto")
        (write-run! "r1" (:id w) "auto" "bg-1")
        (ex/clear!)
        (ex/configure! {:global-cap 2})
        (ex/tick! (fn [_]) {})                    ; this process now drives one
        (with-redefs [conduct/instruct! (fn [& args] (reset! given (vec args)))]
          (let [r (resume/resume! :brian (:id w) "go")]
            (is (= "auto" (:resumed r)))
            (is (true? (:queued? r)) "queued rather than given beside the executor"))
          (is (nil? @given) "and it has not been given yet — it is waiting for its slot")
          (ex/tick! (fn [_]) {})
          (ex/await-idle! 2000)
          (is (= [:brian (:id w) "auto" "go"] @given) "the executor gave it once a slot was free"))))))

(deftest resume!-runs-inline-and-says-so-when-nothing-ticks-this-process
  ;; A standalone `bb nido:ui` starts the gate surface without the daemon. Queued there, the turn
  ;; would park forever in an atom nobody promotes from and say nothing. Running it is right;
  ;; letting the caller believe it was slot-accounted is not.
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:external-refs []})
            given (atom nil)]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (background! (:id w) "auto")
        (write-run! "r1" (:id w) "auto" "bg-1")
        (ex/clear!)                                ; nothing has ticked
        (is (false? (ex/driven?)))
        (with-redefs [conduct/instruct! (fn [& args] (reset! given (vec args)))]
          (let [r (resume/resume! :brian (:id w) "go")]
            (is (false? (:queued? r)))
            (is (true? (:unaccounted? r)) "and it says the slot was not taken"))
          (is (some? @given) "it was given rather than parked in a queue nobody drains"))))))

(deftest resume!-throws-when-not-parked
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:external-refs []})]
        (is (thrown? clojure.lang.ExceptionInfo
                     (resume/resume! :brian (:id w) "go"))
            "no parked session → no resume target")
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (is (= :not-parked
               (try (resume/resume! :brian (:id w) "go") nil
                    (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))
            "nor is a session whose run nobody parked for review — no stored phase says it waits")))))

(deftest a-queued-reply-is-not-queued-twice
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:external-refs []})]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (write-run! "r1" (:id w) "auto" "sid-1")
        (is (= "auto" (:name (resume/waiting-session :brian (:id w)))))
        (with-redefs [ex/turn-pending? #(= "r1" %)]
          (is (nil? (resume/waiting-session :brian (:id w)))
              "a reply already waiting for a slot leaves no session waiting on another"))))))

(deftest a-reply-to-a-conversation-from-before-background-sessions-is-refused
  ;; No --print turn continues a conversation any more, so one whose record carries no :claude
  ;; facet is refused, saying how a person continues it.
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:external-refs []})]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (write-run! "r1" (:id w) "auto" "sid-9")
        (ex/clear!)
        (with-redefs [conduct/instruct! (fn [& _] (throw (ex-info "given" {})))]
          (is (= :no-background-session
                 (try (resume/resume! :brian (:id w) "go") nil
                      (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))
        (let [err (:error (:autonomy (session/read-session :brian (:id w) "auto")))]
          (is (= :no-background-session (:reason err)) "recorded where the gate shows it")
          (is (re-find #"claude --resume sid-9" (:message err))
              "naming the conversation a person can continue"))))))

(deftest a-reply-to-a-session-nido-has-no-id-for-is-refused
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:external-refs []})]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (write-run! "r1" (:id w) "auto" nil)
        (session/update-substrate! :brian (:id w) "auto"
                                   (constantly {:claude nil :dispatching {:token "T" :at "t" :bg-id "bg-1"}}))
        (is (= :session-unnamed
               (try (resume/resume! :brian (:id w) "go") nil
                    (catch clojure.lang.ExceptionInfo e (:reason (ex-data e))))))
        (let [s (session/read-session :brian (:id w) "auto")]
          (is (nil? (get-in s [:claude :queued])) "nothing queued where it cannot be delivered")
          (is (= :session-unnamed (get-in s [:autonomy :error :reason]))))))))

(deftest a-reply-to-a-background-session-is-its-next-instruction
  ;; Conduct gives the reply when the session is ready, and the session's own Stop re-parks it.
  (with-tmp
    (fn []
      (let [w (ws/create! :brian {:external-refs []})]
        (session/create! :brian (:id w) {:name "auto" :weight :heavy :autonomy autonomy-parked})
        (background! (:id w) "auto")
        (write-run! "r1" (:id w) "auto" "bg-1")
        (ex/clear!)
        (ex/configure! {:global-cap 1})
        (ex/tick! (fn [_]) {} 1)                  ; this process drives one, its one slot taken
        (is (= {:resumed "auto" :queued? true :via :substrate}
               (resume/resume! :brian (:id w) "use option B")))
        (is (nil? (get-in (session/read-session :brian (:id w) "auto") [:claude :queued]))
            "not given to conduct while another working session fills the global cap")
        (ex/tick! (fn [_]) {} 0)                  ; the slot frees
        (ex/await-idle! 2000)
        (is (= "use option B" (get-in (session/read-session :brian (:id w) "auto") [:claude :queued]))
            "queued for conduct once the turn is admitted")))))
