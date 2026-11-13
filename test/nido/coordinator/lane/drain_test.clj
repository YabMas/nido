(ns nido.coordinator.lane.drain-test
  "lane-drain: only the drain's coordinator pulls its levers and only a slice records a misfit;
   a slice is briefed from its drain's recipe and starts only once the coordinator was given its
   newest wake; the coordinator is kept while the drain is open, worked or not, is briefed from the
   drain's records, answers every slice's hand-back, and is refused while that slice's misfit is
   open; and a slice lands by its own landing Run, never before what it rests on."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.boot.core :as boot]
   [nido.coordinator.daemon.reconcile :as reconcile]
   [nido.coordinator.executor :as executor]
   [nido.coordinator.lane.conduct :as conduct]
   [nido.coordinator.lane.conductor :as conductor]
   [nido.coordinator.lane.drain :as lane]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.lane.seats :as seats]
   [nido.coordinator.record.drain :as drain]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.core :as core]
   [nido.review.stages :as stages]
   [tasks.nido-review :as review]
   [nido.platform.project :as project]
   [nido.session.launcher :as launcher]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as sstate]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root       (constantly (str tmp))
                       sstate/sessions-root (constantly (str (fs/path tmp "sessions")))]
           (f))
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
  "Run `f` as if from inside brian's nido session `n`, or outside every session for nil."
  [n f]
  (with-redefs [lifecycle/session-from-cwd (fn [& _] (when n {:project "brian" :session n}))
                lifecycle/session-home-coords-from-cwd (fn [& _] nil)]
    (f)))

(def ^:private p :brian)

(def ^:private a-recipe
  {:steps ["move each call site behind the owning door"]
   :ready-gates ["bb test green"]
   :standing-orders ["run the test-suite review prompt"]
   :counter {:command "bb countdown"}})

(defn- refused [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(defn- coordinated-drain
  "A drain with an approved recipe, a census of 4 and its coordinator started. [drain conductor]."
  []
  (let [d (drain/start-drain! p {:established-by {:commit "abcdef1"} :exit "wrap-conn deleted"})]
    (drain/approve-recipe! p d (drain/propose-recipe! p d {:reason "r" :recipe a-recipe}))
    (drain/record-census! p d {:recipe (drain/version (ws/read-ws p d)) :remaining 4 :groups []})
    (launching [_] (conductor/conductors! p :unread))
    [d (str d "--conductor")]))

(defn- passed-review!
  "Slice `slice`'s review under its recipe version, passed — its recipe's ready gates judged."
  [slice]
  (with-redefs [stages/project+ws-from-cwd (fn [_] [p slice])]
    (review/append-review-entry! "/wt" {:status :converged :findings []
                                        :design (stages/discover-design-record "/wt")}
                                 {:summary {:rounds 1 :fix-attempts 0} :target {:base "main" :base-rev "abc"}}
                                 "/runs/r/report.json")))

(defn- unresolved-review!
  "Slice `slice`'s review under its recipe version, ended with findings left standing."
  [slice]
  (with-redefs [stages/project+ws-from-cwd (fn [_] [p slice])]
    (review/append-review-entry! "/wt" {:status :unresolved :findings []
                                        :design (stages/discover-design-record "/wt")}
                                 {:summary {:rounds 1 :fix-attempts 0} :target {:base "main" :base-rev "abc"}}
                                 "/runs/r/report.json")))

(defn- landed!
  "Slice `slice` reviewed and recorded landed."
  [slice]
  (passed-review! slice)
  (ws/append-entry! p slice {:kind :closed} (pr-str {:format :closed :outcome :done :note "landed"})))

(defn- deliver-wakes! [d]
  (lane/wakes! p)
  (doseq [m (seats/traffic p d 1) :when (= :wake (:kind m))]
    (seats/delivered! p d (:id m) "tok")))

(deftest a-drains-coordinator-is-kept-whether-or-not-anyone-typed
  (with-tmp
    (fn []
      (let [d (drain/start-drain! p {:established-by {:commit "abcdef1"} :exit "x"})]
        (launching [launched]
          (is (= [{:ws-id d :act :started :session (str d "--conductor")}]
                 (conductor/conductors! p :unread))
              "an open drain's coordinator starts though the prompt log is unread and nobody typed")
          (is (str/includes? (second (first @launched)) "You coordinate drain")
              "briefed with the coordinator brief")
          (is (str/includes? (second (first @launched)) "Charter: established by")
              "composed from the drain's records"))))))

(deftest only-the-coordinator-pulls-a-lever
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            a-plan {:sites ["a"] :rests-on [] :census census}]
        (is (= :not-the-coordinator (refused #(in-session nil (fn [] (lane/plan! p d a-plan))))))
        (is (= :not-the-coordinator (refused #(in-session "someone-else" (fn [] (lane/plan! p d a-plan))))))
        (is (int? (in-session c #(lane/plan! p d a-plan))))))))

(deftest a-slice-starts-once-the-coordinator-has-its-newest-wake
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            plan   (in-session c #(lane/plan! p d {:sites ["src/a.clj"] :rests-on [] :census census}))]
        (is (lane/wake-pending? p d) "the census's wake is not yet posted")
        (lane/wakes! p)
        (is (= 1 (count (filter #(= :wake (:kind %)) (seats/traffic p d 1)))))
        (lane/wakes! p)
        (is (= 1 (count (filter #(= :wake (:kind %)) (seats/traffic p d 1)))) "posted once per event")
        (is (= :wake-pending (refused #(lane/start-slice! p d plan))) "posted is not delivered")
        (deliver-wakes! d)
        (launching [launched]
          (let [{:keys [slice session]} (lane/start-slice! p d plan)
                brief (second (first @launched))]
            (is (= session (first (first @launched))))
            (is (str/includes? brief "move each call site behind the owning door") "the recipe's steps, verbatim")
            (is (str/includes? brief "bb test green") "its ready gates")
            (is (str/includes? brief "src/a.clj") "the plan it started from")
            (is (drain/slice? (ws/read-ws p slice)))))))))

(deftest only-a-slice-records-a-misfit-and-its-coordinator-answers-first
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            plan   (in-session c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            _      (deliver-wakes! d)
            {:keys [slice session]} (launching [_] (lane/start-slice! p d plan))]
        (is (= :not-a-slice (refused #(in-session c (fn [] (lane/misfit! p d {:site "x" :note "y"})))))
            "the coordinator records no misfit")
        (let [{m :misfit} (in-session session #(lane/misfit! p {:site "src/a.clj:9" :note "job shape"}))
              hb (:id (first (conductor/hand-backs! {:turn-ends [{:project p :ws-id slice :session session
                                                                  :event {:last-message "misfit at a:9"}
                                                                  :instruction {:token "t1"} :run nil}]})))
              go {:hand-back hb :decision [:instruct [{:slice slice :seat 1 :text "carry on"}]]}]
          (testing "a slice's hand-back is posted to its drain's coordinator, naming the slice"
            (let [msg (first (filter #(= hb (:id %)) (seats/traffic p d 1)))]
              (is (= slice (:slice msg)))
              (is (= :conductor (:to msg)))))
          (is (= [:refused :open-misfit] (in-session c #(conductor/decide! p d go))))
          (in-session c #(lane/answer-misfit! p d m {:as :carved :site "src/a.clj:9" :disposition {:to :closing}}))
          (is (= :accepted (first (in-session c #(conductor/decide! p d go)))))
          (is (= ["carry on"] (mapv :text (filter #(= :instruction (:kind %)) (seats/traffic p slice 1))))
              "the instruction reaches the slice's own seat"))))))

(deftest an-instruction-to-a-slice-behind-the-recipe-carries-its-brief
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            plan   (in-session c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            _      (deliver-wakes! d)
            {:keys [slice session]} (launching [_] (lane/start-slice! p d plan))
            v2     (drain/approve-recipe! p d (in-session c #(lane/propose! p d {:reason "amend"
                                                                                 :recipe (assoc a-recipe :steps ["the amended step"])})))
            hb     (:id (first (conductor/hand-backs! {:turn-ends [{:project p :ws-id slice :session session :event {}
                                                                    :instruction {:token "t1"} :run nil}]})))]
        (in-session c #(conductor/decide! p d {:hand-back hb :decision [:instruct [{:slice slice :seat 1 :text "go"}]]}))
        (is (= v2 (drain/slice-version (ws/read-ws p slice))) "the slice moved to the current version")
        (is (str/includes? (:text (first (filter #(= :instruction (:kind %)) (seats/traffic p slice 1))))
                           "the amended step"))))))

(deftest a-slice-lands-by-its-own-run-after-what-it-rests-on
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            a      (in-session c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            b      (in-session c #(lane/plan! p d {:sites ["b"] :rests-on [a] :start :on-top :census census}))
            _      (deliver-wakes! d)
            sa     (:slice (launching [_] (lane/start-slice! p d a)))
            sb     (:slice (launching [_] (with-redefs [lane/jj-repo? (constantly true)
                                                        lane/sh (fn [_ _] {:exit 0 :out "" :err ""})]
                                            (lane/start-slice! p d b))))
            submitted (atom [])]
        (with-redefs [executor/submit! (fn [& args] (swap! submitted conj args))]
          (is (= :rests-unlanded (refused #(lane/land! p sb))) "b rests on a, which has not landed")
          (is (= :not-ready (refused #(lane/land! p sa)))
              "nor before its review under its recipe version passed: an override waives no ready gate")
          (passed-review! sa)
          (let [run (lane/land! p sa)]
            (is (:override (:event-payload run)) "a landing of an uncleared slice is the person's override")
            (is (= sa (:workstream-id run)))
            (is (not= :merge (:trigger run)) "never the serial merge lane")
            (is (= [] @submitted) "not this process's executor, which nobody ticks after a CLI exits")
            (is (= [{:type :submit-run :run-id (:id run)}]
                   (mapv #(select-keys (read-string (slurp (str %))) [:type :run-id])
                         (fs/glob (cstate/queue-dir) "*.edn")))
                "handed to the daemon on its durable queue")
            (is (= :landing-in-flight (refused #(lane/land! p sa)))))
          (in-session c #(lane/clear! p d sb []))
          (landed! sa)
          (passed-review! sb)
          (is (not (:override (:event-payload (lane/land! p sb)))) "a cleared slice is no override"))
        (is (= 2 (count (filter #(str/includes? % "-drain-land-") (runs/list-run-ids)))))))))

(deftest a-newer-unresolved-review-withdraws-an-older-pass
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            a      (in-session c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            _      (deliver-wakes! d)
            sa     (:slice (launching [_] (lane/start-slice! p d a)))
            done   (pr-str {:format :closed :outcome :done :by :person})]
        (passed-review! sa)
        (unresolved-review! sa)
        (is (= :not-ready (refused #(lane/land! p sa))) "its newest review under the version left findings")
        (is (= :not-ready (refused #(ws/append-entry! p sa {:kind :closed} done))) "nor is its landing recorded")
        (passed-review! sa)
        (is (some? (ws/append-entry! p sa {:kind :closed} done)) "passed again, it lands")))))

(deftest a-slice-landing-run-settles-from-the-slices-own-records
  ;; Its /drive-home turn ends with no :complete status written: the merge lane's settle reads a
  ;; ticket ledger a slice has none of, and would park a landing that reached main as failed.
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            a      (in-session c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            b      (in-session c #(lane/plan! p d {:sites ["b"] :rests-on [] :census census}))
            _      (deliver-wakes! d)
            sa     (:slice (launching [_] (lane/start-slice! p d a)))
            sb     (:slice (launching [_] (lane/start-slice! p d b)))
            _      (passed-review! sa)
            _      (passed-review! sb)
            ra     (:id (lane/land! p sa))
            rb     (:id (lane/land! p sb))
            end!   (fn [rid] (runs/transition! rid :running)
                     (#'boot/settle-turn-ends! {:turn-ends [{:project p :ws-id (:workstream-id (runs/read-run rid))
                                                             :session (:session-name (runs/read-run rid))
                                                             :event {} :instruction {:for rid}}]}))]
        (ws/append-entry! p sa {:kind :closed} (pr-str {:format :closed :outcome :done :note "landed"}))
        (end! ra)
        (is (= :done (:state (runs/read-run ra))) "landed: the landing is done")
        (end! rb)
        (is (= :awaiting-review (:state (runs/read-run rb))) "not landed: a failed landing")
        (lane/wakes! p)
        (is (= [(str "The landing of slice " sb " (Run " rb ") did not finish: awaiting-review.")]
               (filterv #(str/starts-with? % "The landing") (map :text (seats/traffic p d 1))))
            "only the failed landing wakes the coordinator as failed")))))

(deftest a-slice-landing-run-a-restart-finds-is-read-from-the-slice
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            a      (in-session c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            _      (deliver-wakes! d)
            sa     (:slice (launching [_] (lane/start-slice! p d a)))
            _      (passed-review! sa)
            ra     (:id (lane/land! p sa))]
        (runs/write-run! (assoc (runs/read-run ra) :session-name "no-such-session"))
        (runs/transition! ra :running)
        (ws/append-entry! p sa {:kind :closed} (pr-str {:format :closed :outcome :done :note "landed"}))
        (#'reconcile/reconcile-one! ra)
        (is (= :done (:state (runs/read-run ra))) "a landing that reached main before the restart is done")))))

(deftest a-slice-has-no-conductor-and-its-acts-are-the-persons
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            plan   (in-session c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            _      (deliver-wakes! d)
            {:keys [slice]} (launching [_] (lane/start-slice! p d plan))]
        (launching [launched]
          (conductor/conductors! p :unread)
          (is (not-any? #(= (str slice "--conductor") (first %)) @launched) "no conductor starts on a slice"))
        (doseq [task ["nido:drain:approve" "nido:drain:land" "nido:drain:close" "nido:drain:start"]]
          (is (contains? @#'conductor/person-tasks task) (str task " is a person's act")))))))

(deftest a-start-given-turn-in-a-slices-session-is-answered-for-by-the-conductor
  ;; `nido:session:spawn` on an existing slice, from a person's turn, marks its instruction
  ;; :given-by :start; the slice's class, not that marker, says who answers for the turn.
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            plan   (in-session c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            _      (deliver-wakes! d)
            {:keys [slice]} (launching [_] (lane/start-slice! p d plan))
            given  {:pending {:token "tok-1" :given-by :start} :session-id "conv-1"}
            answered? (fn [s] (with-redefs [conductor/turn-prompt (fn [_] {:prompt "[nido-send tok-1] go"})]
                                (#'conductor/answering-for-conductor? s nil)))]
        (is (true? (answered? {:project p :workstream-id slice :claude given}))
            "a slice's turn nido gave for no Run is the conductor's, whatever marker it carries")
        (is (false? (answered? {:project p :workstream-id (:id (ws/create! p {})) :claude given}))
            "a start's instruction on any other workstream stays the person's")))))

(deftest a-slice-whose-seat-failed-at-its-start-is-reseated
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            plan   (in-session c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            _      (deliver-wakes! d)
            e      (launching [_]
                     (with-redefs-fn {#'lane/occupy-slice-on! (fn [& _] (throw (ex-info "bookmark create failed"
                                                                                       {:reason :base-failed})))}
                       #(try (lane/start-slice! p d plan) nil (catch clojure.lang.ExceptionInfo e (ex-data e)))))
            slice  (:ws-id e)]
        (is (= :seat-failed (:reason e)) "the slice was made; its seat, not its plan, failed")
        (is (empty? (drain/refused-plans (ws/read-ws p d))) "a consumed plan is not recorded refused")
        (launching [launched]
          (conductor/reseat! p slice :unread)
          (is (= [(str slice "--slice-g1")] (mapv first @launched))
              "a slice never carried is seated by the next pass, whoever typed"))))))

(deftest a-slice-seated-after-its-recipe-moved-is-rebased-before-it-is-briefed
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            plan   (in-session c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            _      (deliver-wakes! d)
            slice  (:ws-id (launching [_]
                             (with-redefs-fn {#'lane/occupy-slice-on! (fn [& _] (throw (ex-info "bookmark create failed" {})))}
                               #(try (lane/start-slice! p d plan) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))))
            v2     (drain/approve-recipe! p d (drain/propose-recipe! p d {:reason "r2" :recipe (assoc a-recipe :steps ["the new step"])}))]
        (launching [launched]
          (conductor/reseat! p slice :unread)
          (is (= v2 (drain/slice-version (ws/read-ws p slice)))
              "the slice records the version its recovered session is briefed under")
          (is (str/includes? (second (first @launched)) "the new step")))))))

(defn- repo-stub
  "A `sh` stub answering the repository calls a start on rested-on work makes, recording each argv."
  [calls]
  (fn [_ argv]
    (swap! calls conj argv)
    (cond (some #{"list"} argv)        {:exit 0 :out "" :err ""}
          (some #{"new"} argv)         {:exit 0 :out "" :err "Created new commit kzmerge 1234abcd (empty)"}
          (some #{"log"} argv)         {:exit 0 :out (str "commit-of-" (nth argv 5)) :err ""}
          (some #{"--quiet"} argv)     {:exit 1 :out "" :err ""}
          (some #{"rev-parse"} argv)   {:exit 0 :out (str "sha-" (last argv) "\n") :err ""}
          (some #{"merge-tree"} argv)  {:exit 0 :out "tree1\n" :err ""}
          (some #{"commit-tree"} argv) {:exit 0 :out "merged-sha\n" :err ""}
          :else                        {:exit 0 :out "" :err ""})))

(deftest a-slice-on-top-of-work-that-cannot-be-snapshotted-is-not-started
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            a      (in-session c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            b      (in-session c #(lane/plan! p d {:sites ["b"] :rests-on [a] :start :on-top :census census}))
            _      (deliver-wakes! d)
            _      (launching [_] (lane/start-slice! p d a))
            stub   (repo-stub (atom []))]
        (with-redefs [lane/jj-repo? (constantly true)
                      lane/sh (fn [dir argv] (if (= ["jj" "status"] argv)
                                               {:exit 1 :out "" :err "The working copy is stale"}
                                               (stub dir argv)))]
          (is (= :base-failed (refused #(launching [_] (lane/start-slice! p d b))))
              "a stale snapshot is no reading of the work it rests on")
          (is (= 1 (count (drain/slices (ws/read-ws p d)))) "and no slice is made"))))))

(deftest a-git-slice-on-top-waits-for-the-work-it-rests-on-to-be-committed
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            a      (in-session c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            b      (in-session c #(lane/plan! p d {:sites ["b"] :rests-on [a] :start :on-top :census census}))
            _      (deliver-wakes! d)
            _      (launching [_] (lane/start-slice! p d a))
            stub   (repo-stub (atom []))]
        (with-redefs [lane/jj-repo? (constantly false)
                      lane/sh (fn [dir argv] (if (some #{"--porcelain"} argv)
                                               {:exit 0 :out " M src/a.clj\n" :err ""}
                                               (stub dir argv)))]
          (is (= :base-uncommitted (refused #(launching [_] (lane/start-slice! p d b))))
              "uncommitted edits are never left out of the work a slice starts on")
          (is (= 1 (count (drain/slices (ws/read-ws p d)))) "and no slice is made")
          (is (empty? (drain/refused-plans (ws/read-ws p d)))
              "nor is the plan refused: the next poll's fire tries again"))))))

(deftest a-slice-on-top-starts-on-the-unlanded-work-it-rests-on
  (doseq [jj? [true false]]
    (testing (if jj? "a jj project" "a git project")
      (with-tmp
        (fn []
          (let [[d c] (coordinated-drain)
                census (:seq (drain/newest-census (ws/read-ws p d)))
                a      (in-session c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
                a2     (in-session c #(lane/plan! p d {:sites ["a2"] :rests-on [] :census census}))
                b      (in-session c #(lane/plan! p d {:sites ["b"] :rests-on [a] :start :on-top :census census}))
                m      (in-session c #(lane/plan! p d {:sites ["m"] :rests-on [a a2] :start :on-top :census census}))
                _      (deliver-wakes! d)
                {sa :slice na :session} (launching [_] (lane/start-slice! p d a))
                {na2 :session}          (launching [_] (lane/start-slice! p d a2))
                calls  (atom [])]
            (with-redefs [lane/jj-repo? (constantly jj?) lane/sh (repo-stub calls)]
              (let [{nb :session} (launching [_] (lane/start-slice! p d b))]
                (is (some #(= (if jj?
                                ["jj" "--ignore-working-copy" "bookmark" "create" nb "-r" (str "commit-of-" na "@")]
                                ["git" "branch" nb (str "sha-refs/heads/" na)])
                              %)
                          @calls)
                    "one rested-on slice: the session starts on its work"))
              (reset! calls [])
              (let [{nm :session} (launching [_] (lane/start-slice! p d m))]
                (is (some #(= (if jj?
                                ["jj" "--ignore-working-copy" "bookmark" "create" nm "-r" "commit-of-kzmerge"]
                                ["git" "branch" nm "merged-sha"])
                              %)
                          @calls)
                    "several: a merge of their work")))
            (testing "a merge that conflicts is refused before the slice is made, and wakes the coordinator once"
              (let [x  (in-session c #(lane/plan! p d {:sites ["x"] :rests-on [a a2] :start :on-top :census census}))
                    n  (count (drain/slices (ws/read-ws p d)))
                    st (repo-stub (atom []))]
                (deliver-wakes! d)
                (with-redefs [lane/jj-repo? (constantly false)
                              lane/sh (fn [dir argv] (if (some #{"merge-tree"} argv) {:exit 1 :out "CONFLICT" :err ""} (st dir argv)))]
                  (is (= :base-failed (refused #(launching [_] (lane/start-slice! p d x)))))
                  (dotimes [_ 2] (lane/handle-fire! {:what :start-slice :project "brian" :drain d :plan x
                                                     :id (str d "/start/" x)})))
                (is (= n (count (drain/slices (ws/read-ws p d)))))
                (is (= #{x} (drain/refused-plans (ws/read-ws p d)))
                    "recorded refused once, so source-drain fires it no more")
                (is (= 1 (count (ws/entries-of p d :start-refused))))
                (is (= 1 (count (filter #(str/includes? (str (:text %)) "cannot be started") (seats/traffic p d 1)))))))
            (testing "a landed slice is on main: nothing to start on"
              (landed! sa)
              (let [b2 (in-session c #(lane/plan! p d {:sites ["b2"] :rests-on [a] :start :on-top :census census}))
                    calls (atom [])]
                (deliver-wakes! d)
                (with-redefs [lane/sh (fn [_ argv] (swap! calls conj argv) {:exit 0 :out "" :err ""})]
                  (launching [_] (lane/start-slice! p d b2)))
                (is (= [] @calls))))))))))

(deftest a-newer-census-wakes-even-after-a-landing-was-delivered
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            a      (in-session c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            _      (deliver-wakes! d)
            sa     (:slice (launching [_] (lane/start-slice! p d a)))]
        (landed! sa)
        (deliver-wakes! d)
        (is (not (lane/wake-pending? p d)))
        (drain/record-census! p d {:recipe (drain/version (ws/read-ws p d)) :remaining 3 :groups []})
        (is (lane/wake-pending? p d) "the new census's wake is not yet delivered")))))

(deftest every-census-recorded-between-passes-gets-its-wake
  (with-tmp
    (fn []
      (let [[d _] (coordinated-drain)
            v     (drain/version (ws/read-ws p d))]
        (deliver-wakes! d)
        (drain/record-census! p d {:recipe v :remaining 3 :groups []})
        (drain/record-census! p d {:recipe v :remaining 2 :groups []})
        (lane/wakes! p)
        (is (= #{"3 remaining." "2 remaining."}
               (set (for [m (seats/traffic p d 1)
                          :when (= :wake (:kind m))
                          :let [t (re-find #"[32] remaining\.$" (:text m))]
                          :when t]
                      t)))
            "neither census of the two is lost to the newer")))))

(deftest a-drain-starts-only-from-a-pattern-the-project-holds
  (with-tmp
    (fn []
      (with-redefs [project/list-projects (fn [] {"brian" {:directory "/src/brian"}})
                    lane/jj-repo?         (constantly false)
                    lane/sh               (fn [_ argv]
                                            (if (some #{"abcdef1^{commit}"} argv)
                                              {:exit 0 :out "abcdef1234567890\n" :err ""}
                                              {:exit 1 :out "" :err ""}))]
        (is (= :no-such-workstream (refused #(lane/start-drain! p {:established-by {:ws-id "ws-nope"} :exit "x"}))))
        (is (= :no-such-commit (refused #(lane/start-drain! p {:established-by {:commit "0000000"} :exit "x"}))))
        (let [d (lane/start-drain! p {:established-by {:commit "abcdef1"} :exit "x"})]
          (is (drain/drain? (ws/read-ws p d)))
          (is (string? (lane/start-drain! p {:established-by {:ws-id d} :exit "y"}))))
        (is (= 2 (count (filter #(drain/drain? (ws/read-ws p %)) (ws/list-ids p))))
            "a refused start makes no workstream")))))

(deftest a-drain-is-closed-done-only-on-its-observed-exit-and-once
  (with-tmp
    (fn []
      (let [[d _] (coordinated-drain)]
        (is (= :exit-not-observed
               (refused #(ws/close! p d :done nil :person)))
            "a generic close of a drain to done is refused")
        (is (= :exit-not-observed
               (refused #(ws/append-entry! p d {:kind :closed} (pr-str {:format :closed :outcome :done :by :person})))))
        (is (nil? (ws/closure (ws/read-ws p d))))
        (drain/record-census! p d {:recipe (drain/version (ws/read-ws p d)) :remaining 0 :groups []})
        (is (int? (lane/close! p d)))
        (is (= :done (:outcome (ws/closure (ws/read-ws p d)))))
        (let [n (count (:entries (ws/read-ws p d)))]
          (is (= :closed (refused #(lane/close! p d))) "a closed drain is not closed again")
          (is (= n (count (:entries (ws/read-ws p d))))))))))

(deftest approving-a-recipe-leaves-nothing-open-from-an-escalation-at-it
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            plan   (in-session c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            _      (deliver-wakes! d)
            {:keys [slice session]} (launching [_] (lane/start-slice! p d plan))
            proposal (in-session c #(lane/propose! p d {:reason "amend" :recipe (assoc a-recipe :steps ["s2"])}))
            hb     (:id (first (conductor/hand-backs! {:turn-ends [{:project p :ws-id slice :session session :event {}
                                                                    :instruction {:token "t1"} :run nil}]})))
            [v _]  (in-session c #(conductor/decide! p d {:hand-back hb
                                                          :decision [:escalate {:needs "approve the amended recipe?"
                                                                                :summary "the recipe needs a second step"
                                                                                :after "slices move to it"}]}))]
        (is (= :accepted v))
        (is (empty? (ws/entries-of p d :blocker)) "an escalation at the person's own step parks no blocker")
        (drain/approve-recipe! p d proposal)
        (conductor/answers! p)
        (is (not (#{:recipe-proposed :blocked} (:at (pipeline/of p d))))
            "approved, the drain moves on: nothing left open holds it")))))

(deftest a-slice-is-reviewed-against-its-recipe-version-and-plan
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            plan   (in-session c #(lane/plan! p d {:sites ["src/a.clj"] :rests-on [] :census census}))
            _      (deliver-wakes! d)
            {:keys [slice]} (launching [_] (lane/start-slice! p d plan))
            v      (drain/slice-version (ws/read-ws p slice))]
        (with-redefs [stages/project+ws-from-cwd (fn [_] [p slice])]
          (let [y (stages/discover-design-record "/wt")]
            (is (= {:seq v} (:recipe y)) "the yardstick is the recipe version the slice runs under")
            (is (some #(str/includes? (str %) "move each call site behind the owning door") (:invariants y))
                "its steps, verbatim")
            (is (str/includes? (:shape y) "src/a.clj") "and the plan it was started from")
            (is (nil? (review/no-yardstick "/wt")) "so the diff loop runs on it")
            (is (= :appended (:ledger (review/append-review-entry!
                                       "/wt" {:status :converged :findings [] :design y}
                                       {:summary {:rounds 1 :fix-attempts 0} :target {:base "main" :base-rev "abc"}}
                                       "/runs/r/report.json"))))
            (is (= :slice-reviewed (:at (pipeline/of p slice)))
                "its :review cites the version, which the slice recipe reads as reviewed")))))))

(deftest a-slice-is-recorded-landed-only-after-its-rests-and-its-review
  ;; Whichever path records it — `nido:land:record`, a generic close — not only `land!`.
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            a      (in-session c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            b      (in-session c #(lane/plan! p d {:sites ["b"] :rests-on [a] :start :after-landing :census census}))
            _      (deliver-wakes! d)
            sa     (:slice (launching [_] (lane/start-slice! p d a)))
            sb     (:slice (launching [_] (lane/start-slice! p d b)))
            sc     (:slice (launching [_] (lane/start-slice! p d (in-session c #(lane/plan! p d {:sites ["c"] :rests-on [] :census census})))))
            done   (pr-str {:format :closed :outcome :done :by :person})]
        (is (= :not-ready (refused #(ws/append-entry! p sa {:kind :closed} done))) "no review under its version")
        (passed-review! sb)
        (is (= :rests-unlanded (refused #(ws/close! p sb :done nil :person))) "b's rested-on a has not landed")
        (landed! sa)
        (is (some? (ws/close! p sb :done nil :person)))
        (is (some? (ws/close! p sc :dropped nil :person)) "a slice given up on closes without either")))))
