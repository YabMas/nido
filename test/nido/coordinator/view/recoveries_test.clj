(ns nido.coordinator.view.recoveries-test
  "The recovery overview, derived from records handed to it."
  (:require
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.source.start-failures :as sf]
   [nido.coordinator.view.recoveries :as rv]))

(def ^:private now (java.time.Instant/parse "2026-09-17T12:00:00Z"))
(def ^:private pacing {:poll-ms 60000 :ceiling-ms (* 60 60000)})

(defn- failure [id cause & {:keys [at session] :or {at "2026-09-17T11:00:00Z" session "feat"}}]
  {:id id :cause cause :at at :project "brian" :session session :verb :up
   :origin {:kind :person}
   :error [{:class "clojure.lang.ExceptionInfo" :message "outer"}
           {:class "java.io.IOException" :message (str "inner " cause)}]})

(defn- run [id state & {:keys [started ended failures reason]
                        :or {started "2026-09-17T11:01:00Z" ended "2026-09-17T11:30:00Z" failures "F1"}}]
  {:id id :state state :event-payload {:failures failures}
   :error (when reason {:reason reason})
   :state-history [{:at started :state :queued} {:at ended :state state}]})

(defn- recovery [ws-id cause & {:keys [closed named runs phases entries]}]
  {:ws-id ws-id :cause cause :closed closed :named (set named)
   :settled #{} :runs (vec runs)
   :sessions (vec (for [p phases] {:autonomy {:phase p}}))
   :entries (or entries {})})

(defn- overview [failures recoveries & {:keys [feed-from]}]
  (rv/overview {:failures failures :recoveries recoveries :now now :pacing pacing :feed-from feed-from}))

(defn- state-of [ov cause]
  (:state (first (filter #(= cause (:cause %)) (:causes ov)))))

(deftest nothing-failed-is-an-empty-overview
  (is (= {:counts {:owed 0 :recovering 0 :needs-you 0 :waiting 0 :restored 0}
          :causes [] :feed {:from nil :events [] :next nil}}
         (overview [] []))))

(deftest each-live-cause-is-one-row-in-one-state
  (let [ov (overview
            [(failure "F1" "c-owed") (failure "F2" "c-owed" :session "other")
             (failure "F3" "c-run") (failure "F4" "c-park") (failure "F5" "c-wait")]
            [(recovery "ws-run" "c-run" :named ["F3"] :phases [:running])
             (recovery "ws-park" "c-park" :named ["F4"] :phases [:parked])
             (recovery "ws-wait" "c-wait" :named ["F5"] :phases [:failed]
                       :runs [(run "r1" :failed :ended "2026-09-17T11:59:30Z")])])]
    (is (= :owed (state-of ov "c-owed")))
    (is (= :recovering (state-of ov "c-run")))
    (is (= :needs-you (state-of ov "c-park")))
    (is (= :waiting (state-of ov "c-wait")))
    (is (= 4 (count (:causes ov))) "one row per cause, however many failures")
    (is (= ["brian/feat" "brian/other"] (:sessions (first (filter #(= "c-owed" (:cause %)) (:causes ov))))))
    (is (= "ws-park" (:gate-ws (first (filter #(= "c-park" (:cause %)) (:causes ov)))))
        "a parked recovery names the workstream whose gate asks the person")
    (is (= {:owed 5 :recovering 1 :needs-you 1 :waiting 1 :restored 0} (:counts ov))
        "owed counts every failed start not yet settled, including those a recovery is working on; the rest count causes")
    (is (= [:needs-you :recovering :waiting :owed] (mapv :state (:causes ov)))
        "what needs a person first, then what nido is doing, then what waits")))

(deftest a-waiting-cause-says-when-it-is-next-due-as-the-source-does
  (let [runs [(run "r1" :failed :ended "2026-09-17T11:50:00Z")
              (run "r2" :failed :ended "2026-09-17T11:59:00Z")]
        ov   (overview [(failure "F1" "c")] [(recovery "ws" "c" :named ["F1"] :runs runs)])
        row  (first (:causes ov))]
    (is (= :waiting (:state row)))
    (is (= 2 (:failed-in-a-row row)))
    (is (= "2026-09-17T12:01:00Z" (:due-at row)) "two failures wait two poll intervals from the last")
    (is (not (sf/due? runs now pacing)) "the page and the source agree it is not yet due")))

(deftest a-cause-past-its-delay-is-owed-not-waiting
  (let [ov (overview [(failure "F1" "c")]
                     [(recovery "ws" "c" :named ["F1"] :runs [(run "r1" :failed :ended "2026-09-17T11:00:00Z")])])]
    (is (= :owed (state-of ov "c")) "due now: the next poll fires it")))

(deftest settled-causes-show-for-the-window-only
  (let [ov (overview [(failure "F1" "done") (failure "F2" "gone") (failure "F3" "old" :at "2026-09-01T00:00:00Z")]
                     [(recovery "ws-done" "done" :named ["F1"] :closed {:at "2026-09-17T11:40:00Z" :outcome :done})
                      (recovery "ws-gone" "gone" :named ["F2"] :closed {:at "2026-09-16T10:00:00Z" :outcome :dismissed})
                      (recovery "ws-old" "old" :named ["F3"] :closed {:at "2026-09-02T00:00:00Z" :outcome :done})])]
    (is (= :restored (state-of ov "done")))
    (is (= :dismissed (state-of ov "gone")))
    (is (nil? (state-of ov "old")) "a recovery that closed before the window is not a row")
    (is (= 1 (:restored (:counts ov))))))

(deftest a-row-says-what-was-found-and-done
  (let [ov  (overview [(failure "F1" "c")]
                      [(recovery "ws" "c" :named ["F1"] :closed {:at "2026-09-17T11:50:00Z" :outcome :done}
                                 :entries {:session-diagnosis [{:verdict :nido-defect :cause "advance skipped a migration"
                                                                :remedy "fix the ordering" :at "2026-09-17T11:10:00Z"}]
                                           :merged [{:commit "abc123def456" :url "u" :title "fix(session): order"
                                                     :at "2026-09-17T11:30:00Z"}]
                                           :session-restored [{:at "2026-09-17T11:45:00Z"
                                                               :outcomes [{:failure "F1" :outcome :restored}]}]})])
        row (first (:causes ov))]
    (is (= :nido-defect (-> row :diagnosis :verdict)))
    (is (= "abc123def456" (-> row :landed first :commit)))
    (is (= {:restored 1} (:restores row)))
    (is (= "inner c" (:message row)) "the innermost message is where a wrapped failure says what went wrong")))

(deftest the-feed-is-every-recovery-fact-newest-first
  (let [ov (overview [(failure "F1" "c" :at "2026-09-17T11:00:00Z")
                      (failure "F0" "c" :at "2026-09-01T00:00:00Z")]
                     [(recovery "ws" "c" :named ["F1"] :closed {:at "2026-09-17T11:50:00Z" :outcome :done}
                                :runs [(run "r1" :failed :started "2026-09-17T11:01:00Z" :ended "2026-09-17T11:05:00Z" :reason :undiagnosed)
                                       (run "r2" :done :started "2026-09-17T11:08:00Z" :ended "2026-09-17T11:49:00Z")]
                                :entries {:session-diagnosis [{:verdict :one-off :cause "stale pid" :at "2026-09-17T11:10:00Z"}]
                                          :blocker [{:summary "needs the shared cluster reset" :at "2026-09-17T11:20:00Z"}]
                                          :merged [{:commit "abc" :url "u" :title "t" :at "2026-09-17T11:30:00Z"}]
                                          :session-restored [{:at "2026-09-17T11:45:00Z"
                                                              :outcomes [{:failure "F1" :outcome :restored}]}]})])]
    (is (= [:closed :recovery-ended :restore :landed :parked :diagnosed :recovery-fired
            :recovery-ended :recovery-fired :failure-kept :failure-kept]
           (mapv :kind (-> ov :feed :events))))
    (is (= :undiagnosed (:reason (first (filter #(and (= :recovery-ended (:kind %)) (= :failed (:state %)))
                                                (-> ov :feed :events))))))
    (testing "the feed has no window: a fact from weeks ago is still in the trail"
      (is (= "F0" (:failure (peek (-> ov :feed :events))))))))

(deftest following-older-pages-reaches-every-event-once
  (let [failures (for [i (range 175)]
                   (failure (format "F%03d" i) "c"
                            :at (str (java.time.Instant/ofEpochSecond (+ 1789000000 (quot i 3))))))
        pages    (loop [from nil, acc []]
                   (let [{:keys [events next]} (:feed (overview (vec failures) [] :feed-from from))
                         acc (conj acc events)]
                     (if next (recur next acc) acc)))]
    (is (= [80 80 15] (mapv count pages)) "a page is at most eighty events")
    (is (= (set (map :id failures)) (set (map :failure (apply concat pages))))
        "every event is reachable from the newest page")
    (is (apply distinct? (map :key (apply concat pages))) "and none is reached twice, though times repeat")
    (is (= (map :key (apply concat pages)) (sort #(compare %2 %1) (map :key (apply concat pages))))
        "newest first across page boundaries")))
