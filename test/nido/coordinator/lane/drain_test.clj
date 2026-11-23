(ns nido.coordinator.lane.drain-test
  "lane-drain: only the drain's coordinator — its conductor turn — pulls its levers and only a
   slice's turn records a misfit; a slice is briefed from its drain's recipe and its unit opened
   only once the coordinator heard its newest wake; and a slice lands by the person's grant,
   never before what it rests on or its review."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.coordinator.lane.drain :as lane]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.record.drain :as drain]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.core :as core]
   [nido.review.stages :as stages]
   [tasks.nido-review :as review]
   [nido.platform.project :as project]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as sstate]))

(def ^:private p :brian)

(def ^:dynamic ^:private *core*
  "The fake core every test reaches through: {:states (atom {ws-id {:chat [] :inbox {}}})
   :opened (atom [[slice unit brief]]) :landed (atom [slice])} plus the lane's `core` map."
  nil)

(defn- fake-core []
  (let [states (atom {}) opened (atom []) landed (atom [])]
    {:states states :opened opened :landed landed
     :state       (fn [_ ws-id] (get @states ws-id {:chat [] :inbox {}}))
     ;; As the core decides :post-chat: a message id already posted is refused, not posted again.
     :post!       (fn [_ ws-id k text]
                    (if (some #(= k (:id %)) (get-in @states [ws-id :chat]))
                      {:refused {:reason :message-exists}}
                      (do (swap! states update ws-id
                                 (fn [st] (-> (or st {:chat [] :inbox {}})
                                              (update :chat conj {:id k :from :person :text text})
                                              (assoc-in [:inbox k] {:kind :chat :message k :text text}))))
                          {:events [:chat-posted]})))
     :open-slice! (fn [proj slice unit brief]
                    (swap! opened conj [slice unit brief])
                    (session/create! proj slice {:name unit :weight :heavy :autonomy nil}))
     :land!       (fn [_ slice] (swap! landed conj slice) :granted)}))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root          (constantly (str tmp))
                       sstate/sessions-root    (constantly (str (fs/path tmp "sessions")))
                       project/list-projects   (fn [] {"brian" {:directory "/src/brian"}})
                       lifecycle/session-coords (fn [n _] {:wt-path (str "/wt/" n) :instance-id "brian--ws"})]
           (binding [*core* (fake-core)] (f)))
         (finally (fs/delete-tree tmp)))))

(defn- in-turn
  "Run `f` as if from inside a core conductor turn on brian's workstream `ws-id` (nil: outside every
   turn) — whatever turn the suite itself runs in."
  [ws-id f]
  (with-redefs-fn {#'lane/turn-ws (fn [] (when ws-id ["brian" ws-id]))
                   #'lane/turn-unit (constantly nil)} f))

(defn- start-slice! [d plan] (#'lane/start-slice! *core* p d plan))

(def ^:private a-recipe
  {:steps ["move each call site behind the owning door"]
   :ready-gates ["bb test green"]
   :standing-orders ["run the test-suite review prompt"]
   :counter {:command "bb countdown"}})

(defn- refused [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(defn- coordinated-drain
  "A drain with an approved recipe and a census of 4. [drain drain] — the second is the workstream
   its coordinator's turns run on."
  []
  (let [d (drain/start-drain! p {:established-by {:commit "abcdef1"} :exit "wrap-conn deleted"})]
    (drain/approve-recipe! p d (drain/propose-recipe! p d {:reason "r" :recipe a-recipe}))
    (drain/record-census! p d {:recipe (drain/version (ws/read-ws p d)) :remaining 4 :groups []})
    [d d]))

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

(defn- wakes [d] (filterv #(str/starts-with? (str (:id %)) "wake:") (:chat ((:state *core*) p d))))

(defn- deliver-wakes!
  "Post every wake owed and let the coordinator's turn hear them."
  [d]
  (lane/wakes! p *core*)
  (swap! (:states *core*) assoc-in [d :inbox] {}))

(deftest only-the-coordinator-pulls-a-lever
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            a-plan {:sites ["a"] :rests-on [] :census census}]
        (is (= :not-the-coordinator (refused #(in-turn nil (fn [] (lane/plan! p d a-plan))))))
        (is (= :not-the-coordinator (refused #(in-turn "ws-else" (fn [] (lane/plan! p d a-plan))))))
        (is (int? (in-turn c #(lane/plan! p d a-plan))))))))

(deftest a-slice-starts-once-the-coordinator-heard-its-newest-wake
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            plan   (in-turn c #(lane/plan! p d {:sites ["src/a.clj"] :rests-on [] :census census}))]
        (is (lane/wake-pending? p d *core*) "the census's wake is not yet posted")
        (lane/wakes! p *core*)
        (is (= 1 (count (wakes d))))
        (is (str/includes? (:text (first (wakes d))) "You coordinate drain") "a wake carries the coordinator brief")
        (lane/wakes! p *core*)
        (is (= 1 (count (wakes d))) "posted once per event")
        (is (= :wake-pending (refused #(start-slice! d plan))) "posted is not heard")
        (deliver-wakes! d)
        (let [{:keys [slice session]} (start-slice! d plan)
              [[s unit brief]] @(:opened *core*)]
          (is (= [slice session] [s unit]))
          (is (= (str slice "--slice-g1") unit))
          (is (str/includes? brief "move each call site behind the owning door") "the recipe's steps, verbatim")
          (is (str/includes? brief "bb test green") "its ready gates")
          (is (str/includes? brief "src/a.clj") "the plan it started from")
          (is (drain/slice? (ws/read-ws p slice))))))))

(deftest only-a-slice-records-a-misfit
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            plan   (in-turn c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            _      (deliver-wakes! d)
            {:keys [slice]} (start-slice! d plan)]
        (is (= :not-a-slice (refused #(in-turn c (fn [] (lane/misfit! p {:site "a" :note "n"}))))))
        (is (= d (:drain (in-turn slice #(lane/misfit! p {:site "a" :note "odd"})))))))))

(deftest a-slice-whose-unit-failed-to-open-keeps-its-plan-consumed
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            plan   (in-turn c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            _      (deliver-wakes! d)
            e      (binding [*core* (assoc *core* :open-slice! (fn [& _] (throw (ex-info "the core refused" {}))))]
                     (try (start-slice! d plan) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))]
        (is (= :seat-failed (:reason e)) "the slice was made; its unit, not its plan, failed")
        (is (empty? (drain/refused-plans (ws/read-ws p d))) "a consumed plan is not recorded refused")))))

(deftest a-slice-lands-by-the-persons-grant-after-what-it-rests-on
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            a      (in-turn c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            b      (in-turn c #(lane/plan! p d {:sites ["b"] :rests-on [a] :start :on-top :census census}))
            _      (deliver-wakes! d)
            sa     (:slice (start-slice! d a))
            sb     (:slice (with-redefs [lane/jj-repo? (constantly true)
                                         lane/sh (fn [_ _] {:exit 0 :out "" :err ""})]
                             (start-slice! d b)))]
        (is (= :rests-unlanded (refused #(lane/land! *core* p sb))) "b rests on a, which has not landed")
        (is (= :not-ready (refused #(lane/land! *core* p sa)))
            "nor before its review under its recipe version passed: an override waives no ready gate")
        (passed-review! sa)
        (let [r (lane/land! *core* p sa)]
          (is (= {:slice sa :override? true :granted :granted} r)
              "a landing of an uncleared slice is the person's override, granted in the core"))
        (in-turn c #(lane/clear! p d sb []))
        (landed! sa)
        (passed-review! sb)
        (is (false? (:override? (lane/land! *core* p sb))) "a cleared slice is no override")
        (is (= [sa sb] @(:landed *core*)))))))

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

(deftest a-newer-unresolved-review-withdraws-an-older-pass
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            a      (in-turn c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            _      (deliver-wakes! d)
            sa     (:slice (start-slice! d a))
            done   (pr-str {:format :closed :outcome :done :by :person})]
        (passed-review! sa)
        (unresolved-review! sa)
        (is (= :not-ready (refused #(lane/land! *core* p sa))) "its newest review under the version left findings")
        (is (= :not-ready (refused #(ws/append-entry! p sa {:kind :closed} done))) "nor is its landing recorded")
        (passed-review! sa)
        (is (some? (ws/append-entry! p sa {:kind :closed} done)) "passed again, it lands")))))

(deftest a-slice-on-top-of-work-that-cannot-be-snapshotted-is-not-started
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            a      (in-turn c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            b      (in-turn c #(lane/plan! p d {:sites ["b"] :rests-on [a] :start :on-top :census census}))
            _      (deliver-wakes! d)
            _      (start-slice! d a)
            stub   (repo-stub (atom []))]
        (with-redefs [lane/jj-repo? (constantly true)
                      lane/sh (fn [dir argv] (if (= ["jj" "status"] argv)
                                               {:exit 1 :out "" :err "The working copy is stale"}
                                               (stub dir argv)))]
          (is (= :base-failed (refused #(start-slice! d b)))
              "a stale snapshot is no reading of the work it rests on")
          (is (= 1 (count (drain/slices (ws/read-ws p d)))) "and no slice is made"))))))

(deftest a-git-slice-on-top-waits-for-the-work-it-rests-on-to-be-committed
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            a      (in-turn c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            b      (in-turn c #(lane/plan! p d {:sites ["b"] :rests-on [a] :start :on-top :census census}))
            _      (deliver-wakes! d)
            _      (start-slice! d a)
            stub   (repo-stub (atom []))]
        (with-redefs [lane/jj-repo? (constantly false)
                      lane/sh (fn [dir argv] (if (some #{"--porcelain"} argv)
                                               {:exit 0 :out " M src/a.clj\n" :err ""}
                                               (stub dir argv)))]
          (is (= :base-uncommitted (refused #(start-slice! d b)))
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
                a      (in-turn c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
                a2     (in-turn c #(lane/plan! p d {:sites ["a2"] :rests-on [] :census census}))
                b      (in-turn c #(lane/plan! p d {:sites ["b"] :rests-on [a] :start :on-top :census census}))
                m      (in-turn c #(lane/plan! p d {:sites ["m"] :rests-on [a a2] :start :on-top :census census}))
                _      (deliver-wakes! d)
                {sa :slice na :session} (start-slice! d a)
                {na2 :session}          (start-slice! d a2)
                calls  (atom [])]
            (with-redefs [lane/jj-repo? (constantly jj?) lane/sh (repo-stub calls)]
              (let [{nb :session} (start-slice! d b)]
                (is (some #(= (if jj?
                                ["jj" "--ignore-working-copy" "bookmark" "create" nb "-r" (str "commit-of-" na "@")]
                                ["git" "branch" nb (str "sha-refs/heads/" na)])
                              %)
                          @calls)
                    "one rested-on slice: the session starts on its work"))
              (reset! calls [])
              (let [{nm :session} (start-slice! d m)]
                (is (some #(= (if jj?
                                ["jj" "--ignore-working-copy" "bookmark" "create" nm "-r" "commit-of-kzmerge"]
                                ["git" "branch" nm "merged-sha"])
                              %)
                          @calls)
                    "several: a merge of their work")))
            (testing "a merge that conflicts is refused before the slice is made, and wakes the coordinator once"
              (let [x  (in-turn c #(lane/plan! p d {:sites ["x"] :rests-on [a a2] :start :on-top :census census}))
                    n  (count (drain/slices (ws/read-ws p d)))
                    st (repo-stub (atom []))]
                (deliver-wakes! d)
                (with-redefs [lane/jj-repo? (constantly false)
                              lane/sh (fn [dir argv] (if (some #{"merge-tree"} argv) {:exit 1 :out "CONFLICT" :err ""} (st dir argv)))]
                  (is (= :base-failed (refused #(start-slice! d x))))
                  (dotimes [_ 2] (lane/handle-fire! *core* {:what :start-slice :project "brian" :drain d :plan x
                                                     :id (str d "/start/" x)})))
                (is (= n (count (drain/slices (ws/read-ws p d)))))
                (is (= #{x} (drain/refused-plans (ws/read-ws p d)))
                    "recorded refused once, so source-drain fires it no more")
                (is (= 1 (count (ws/entries-of p d :start-refused))))
                (is (= 1 (count (filter #(str/includes? (str (:text %)) "cannot be started") (wakes d)))))))
            (testing "a landed slice is on main: nothing to start on"
              (landed! sa)
              (let [b2 (in-turn c #(lane/plan! p d {:sites ["b2"] :rests-on [a] :start :on-top :census census}))
                    calls (atom [])]
                (deliver-wakes! d)
                (with-redefs [lane/sh (fn [_ argv] (swap! calls conj argv) {:exit 0 :out "" :err ""})]
                  (start-slice! d b2))
                (is (= [] @calls))))))))))

(deftest a-newer-census-wakes-even-after-a-landing-was-delivered
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            a      (in-turn c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            _      (deliver-wakes! d)
            sa     (:slice (start-slice! d a))]
        (landed! sa)
        (deliver-wakes! d)
        (is (not (lane/wake-pending? p d *core*)))
        (drain/record-census! p d {:recipe (drain/version (ws/read-ws p d)) :remaining 3 :groups []})
        (is (lane/wake-pending? p d *core*) "the new census's wake is not yet delivered")))))

(deftest every-census-recorded-between-passes-gets-its-wake
  (with-tmp
    (fn []
      (let [[d _] (coordinated-drain)
            v     (drain/version (ws/read-ws p d))]
        (deliver-wakes! d)
        (drain/record-census! p d {:recipe v :remaining 3 :groups []})
        (drain/record-census! p d {:recipe v :remaining 2 :groups []})
        (lane/wakes! p *core*)
        (is (= #{"3 remaining." "2 remaining."}
               (set (for [m (wakes d)
                          :let [t (re-find #"[32] remaining\." (:text m))]
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

(deftest a-slice-is-reviewed-against-its-recipe-version-and-plan
  (with-tmp
    (fn []
      (let [[d c] (coordinated-drain)
            census (:seq (drain/newest-census (ws/read-ws p d)))
            plan   (in-turn c #(lane/plan! p d {:sites ["src/a.clj"] :rests-on [] :census census}))
            _      (deliver-wakes! d)
            {:keys [slice]} (start-slice! d plan)
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
            a      (in-turn c #(lane/plan! p d {:sites ["a"] :rests-on [] :census census}))
            b      (in-turn c #(lane/plan! p d {:sites ["b"] :rests-on [a] :start :after-landing :census census}))
            _      (deliver-wakes! d)
            sa     (:slice (start-slice! d a))
            sb     (:slice (start-slice! d b))
            sc     (:slice (start-slice! d (in-turn c #(lane/plan! p d {:sites ["c"] :rests-on [] :census census}))))
            done   (pr-str {:format :closed :outcome :done :by :person})]
        (is (= :not-ready (refused #(ws/append-entry! p sa {:kind :closed} done))) "no review under its version")
        (passed-review! sb)
        (is (= :rests-unlanded (refused #(ws/close! p sb :done nil :person))) "b's rested-on a has not landed")
        (landed! sa)
        (is (some? (ws/close! p sb :done nil :person)))
        (is (some? (ws/close! p sc :dropped nil :person)) "a slice given up on closes without either")))))

