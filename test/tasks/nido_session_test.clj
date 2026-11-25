(ns tasks.nido-session-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [babashka.fs :as fs]
   [nido.coordinator.record.workstream]
   [nido.coordinator.work :as work]
   [nido.coordinator.record.session-events :as session-events]
   [nido.session.casualties :as casualties]
   [nido.session.fleet :as fleet]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as state]
   [tasks.nido-session :as task]))

;; ---------------------------------------------------------------------------
;; Fleet budget pre-flight
;;
;; The decision has to stay with the human, so what is tested here is that a
;; "no" actually stops the boot, and that a machine with room never asks.
;; ---------------------------------------------------------------------------

(def ^:private gb (* 1024 1024 1024))

(def ^:private busy-fleet
  [{:project "brian" :session "learning-goals" :bytes (* 3 gb) :candidate? false}
   {:project "brian" :session "stale-one"      :bytes (* 2 gb) :candidate? true
    :idle-ms (* 47 60 60 1000) :agent-seen-ms 1}])

(defn- with-fleet
  "Run f against a fixed fleet on a machine of the given size, with a human
   present who answers `answer`. Returns [result printed-output]."
  [{:keys [in-use machine answer]} f]
  (let [out (java.io.StringWriter.)]
    (with-redefs [fleet/snapshot    (fn [] busy-fleet)
                  fleet/in-use-bytes (fn [] in-use)
                  fleet/machine-bytes (fn [] machine)
                  task/interactive? (fn [] true)
                  task/confirm?     (fn [] answer)]
      (let [r (binding [*out* out] (f))]
        [r (str out)]))))

(deftest up-brings-a-persons-own-session-up-as-no-work
  (let [calls (atom [])]
    (with-redefs [lifecycle/up!          (fn [s o] (swap! calls conj [:up s o]))
                  state/session-home-dir (fn [_ _] "/tmp/home")]
      (binding [*out* (java.io.StringWriter.)]
        (task/up ":project" "brian" "refshot"))
      (is (= [[:up "refshot" {:project "brian" :wait false}]] @calls)
          "up! alone: no workstream, no runtime key but the one it is up under or its own name"))))

(deftest up-refuses-a-workstream-and-names-the-activation
  (let [calls (atom [])]
    (with-redefs [lifecycle/up! (fn [s o] (swap! calls conj [:up s o]))]
      (let [out (with-out-str
                  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exit 1"
                                        (with-redefs [task/exit! (fn [c] (throw (ex-info (str "exit " c) {})))]
                                          (task/up ":project" "brian" "child-work" ":ws-id" "ws-child")))))]
        (is (str/includes? out "bb nido:activate :project brian :ws ws-child"))
        (is (= [] @calls) "nothing is provisioned")))))

(deftest destroy-touches-no-workstream
  (let [calls (atom [])]
    (with-redefs [lifecycle/destroy! (fn [s o] (swap! calls conj [:destroy s o]))]
      (task/destroy ":project" "brian" "refshot")
      (is (= 1 (count @calls)) "lifecycle destroy, and nothing else"))))

(deftest declining-the-budget-question-starts-nothing
  (let [started (atom [])]
    (with-redefs [lifecycle/up! (fn [s o] (swap! started conj [s o]))]
      (let [[_ out] (with-fleet {:in-use (* 34 gb) :machine (* 48 gb) :answer false}
                                #(task/up ":project" "brian" "another"))]
        (is (empty? @started) "a no must not boot the session")
        (is (str/includes? out "Aborted") "and says so plainly")
        (is (str/includes? out "would be session #3") "after showing what it would cost")
        (is (str/includes? out "brian/stale-one") "and what could be freed instead")))))

(deftest accepting-the-budget-question-boots-normally
  (let [started (atom [])]
    (with-redefs [lifecycle/up!            (fn [s o] (swap! started conj [s o]))
                  state/session-home-dir   (fn [_ _] "/tmp/home")
                  lifecycle/session-weight (fn [_ _] :heavy)]
      (let [[_ out] (with-fleet {:in-use (* 34 gb) :machine (* 48 gb) :answer true}
                                #(task/up ":project" "brian" "another"))]
        (is (= 1 (count @started)) "yes boots it")
        (is (str/includes? out "Session ready"))))))

(deftest a-machine-with-room-never-asks
  (let [asked (atom false)
        started (atom [])]
    (with-redefs [lifecycle/up!            (fn [s o] (swap! started conj [s o]))
                  state/session-home-dir   (fn [_ _] "/tmp/home")
                  lifecycle/session-weight (fn [_ _] :heavy)
                  fleet/snapshot           (fn [] busy-fleet)
                  fleet/in-use-bytes       (fn [] (* 12 gb))
                  fleet/machine-bytes      (fn [] (* 48 gb))
                  task/interactive?        (fn [] true)
                  task/confirm?            (fn [] (reset! asked true) false)]
      (let [out (java.io.StringWriter.)]
        (binding [*out* out] (task/up ":project" "brian" "another"))
        (is (false? @asked) "no question when the machine has room")
        (is (= 1 (count @started)) "and it just boots")
        (is (str/includes? (str out) "Fleet budget:") "the number is still shown")))))

(deftest an-already-live-session-is-never-questioned
  ;; `up` is idempotent and re-run just to refresh session-home artifacts. That
  ;; costs no memory, so it must not be charged for one.
  (let [asked (atom false)]
    (with-redefs [lifecycle/up!            (fn [_ _] nil)
                  state/session-home-dir   (fn [_ _] "/tmp/home")
                  lifecycle/session-weight (fn [_ _] :heavy)
                  fleet/snapshot           (fn [] busy-fleet)
                  fleet/in-use-bytes       (fn [] (* 40 gb))
                  fleet/machine-bytes      (fn [] (* 48 gb))
                  task/interactive?        (fn [] true)
                  task/confirm?            (fn [] (reset! asked true) false)]
      (binding [*out* (java.io.StringWriter.)]
        (task/up ":project" "brian" "learning-goals"))
      (is (false? @asked) "already live — refreshing it adds nothing to the fleet"))))

(deftest the-report-names-the-constraint-when-nothing-is-idle
  (let [rows   [{:project "brian" :session "a" :bytes (* 3 gb) :candidate? false}]
        totals {:sessions 1 :fleet (* 3 gb) :in-use (* 40 gb)
                :machine (* 48 gb) :typical (* 3 gb)}
        lines  (task/budget-report rows totals "brian" "new-one")]
    (is (some #(str/includes? % "No idle sessions") lines)
        "the honest answer when concurrency, not neglect, is the constraint")
    (is (not-any? #(str/includes? % "down one to make room") lines))))

(defn- start-calls
  "Run start with every effect recorded instead of performed. `held` is the workstream already
   holding the name, `wt?` whether the name's worktree exists. Returns [calls printed-output]."
  [{:keys [held wt?]} & args]
  (let [calls (atom [])]
    (with-redefs [lifecycle/session-coords              (fn [s _] {:wt-path (str "/nonexistent-wt/" s)})
                  babashka.fs/exists?                   (fn [p & _] (boolean (and wt? (str/starts-with? (str p) "/nonexistent-wt/"))))
                  lifecycle/up!                         (fn [s _] (swap! calls conj [:up s]))
                  work/start-goal!                      (fn [p s b] (swap! calls conj [:start-goal p s b]) "ws-new")
                  nido.coordinator.record.workstream/of-session            (fn [_ _] held)
                  task/exit!                            (fn [c] (throw (ex-info (str "exit " c) {:exit c})))]
      (let [out (with-out-str
                  (try (apply task/start (concat [":project" "nido" "goal"] args))
                       (catch clojure.lang.ExceptionInfo e (println (ex-message e)))))]
        [@calls out]))))

(defn- the-brief [calls] (nth (first (filter #(= :start-goal (first %)) calls)) 3))

(deftest start-hands-a-minted-workstream-to-the-core-and-brings-nothing-up-itself
  (let [[calls out] (start-calls {} ":brief" "Make the widget; done when it renders.")]
    (is (= [[:start-goal :nido "goal"]] (mapv #(vec (take 3 %)) calls))
        "the core provisions the session: start brings none up and adopts none")
    (is (str/includes? out "Started workstream ws-new"))))

(deftest start-reads-its-brief-from-a-file
  (let [f (str (babashka.fs/create-temp-file))]
    (spit f "From a file.")
    (is (str/ends-with? (the-brief (first (start-calls {} ":brief-file" f))) "\n\nFrom a file."))))

(deftest start-tells-the-session-to-write-the-recipe-before-code
  (let [b  (the-brief (first (start-calls {} ":brief" "Ship the widget.")))
        at #(str/index-of b %)]
    (is (every? at [":intent" ":baseline" ":design"]) "every record the recipe owes is named")
    (is (< (at ":intent") (at ":baseline") (at ":design") (at "Only then build")) "in the recipe's order, code last")
    (is (str/ends-with? b "\n\nShip the widget.") "the brief verbatim, last")))

(deftest start-refuses-a-name-a-session-already-has
  (doseq [[why opts] [["a workstream holds it" {:held "ws-old"}]
                      ["its worktree exists"   {:wt? true}]]]
    (let [[calls out] (start-calls opts ":brief" "Goal.")]
      (is (= [] calls) (str why ": refused before anything is written"))
      (is (str/includes? out "already exists") why))))

(deftest start-without-a-brief-starts-nothing
  (let [[calls out] (start-calls {})]
    (is (= [] calls))
    (is (str/includes? out "Missing :brief-file"))))

(defn- revive-world
  "Two casualties in temp worktrees — `quiz`, whose conversation is already running, and
   `conflicted` — with every effect revive could have recorded in `calls`."
  [calls f]
  (let [tmp  (fs/create-temp-dir)
        base (str (fs/create-dirs (fs/path tmp "brian-worktrees")))
        wt   (fn [n] (str (fs/create-dirs (fs/path base n))))
        cs   [{:boot-id "b" :instance-id "brian--quiz" :project "brian" :worktree (wt "quiz")
               :registered-at "2026-10-24T12:00:00Z"}
              {:boot-id "b" :instance-id "brian--conflicted" :project "brian"
               :worktree (wt "conflicted") :registered-at "2026-10-24T12:00:00Z"}]]
    (try
      (with-redefs [nido.coordinator.work/owed-revivals (constantly {:casualties cs :unreadable []})
                    casualties/settle!           (fn [c] (swap! calls conj [:settle (mapv :instance-id c)]) true)
                    nido.platform.project/list-projects (constantly {"brian" {:directory "/x"}})
                    lifecycle/worktrees-dir      (constantly base)
                    state/session-home-dir       (fn [_ s] (str "/homes/" s))
                    session-events/conversations (constantly [{:id "c-quiz" :cwd (str (fs/path base "quiz" "src"))
                                                               :last-at-ms 2}
                                                              {:id "c-conf" :cwd (str (fs/path base "conflicted"))
                                                               :last-at-ms 1}])
                    fleet/snapshot               (constantly [])
                    fleet/machine-bytes          (constantly (* 100 1024 1024 1024))
                    fleet/in-use-bytes           (constantly 0)
                    lifecycle/warp?              (constantly true)
                    lifecycle/up!                (fn [s o] (swap! calls conj [:up s (:project o)]))
                    lifecycle/spawn-tab!         (fn [s o] (swap! calls conj [:tab s o]))
                    babashka.process/shell       (fn [opts & cmd]
                                                   {:exit 0 :out (if (str/ends-with? (:dir opts) "conflicted") "xyz" "")})
                    fs/exists?                   (fn [p & _] (or (str/ends-with? (str p) ".jj") (.exists (java.io.File. (str p)))))]
        (with-redefs-fn {#'task/running-conversations (constantly {"c-quiz" {:cwd "/running"}})} f))
      (finally (fs/delete-tree tmp)))))

(deftest revive-dry-run-changes-nothing
  (let [calls (atom [])
        out   (revive-world calls #(with-out-str (task/revive ":dry-run" "true")))]
    (is (= [] @calls) "no start, no tab, no settle")
    (is (str/includes? out "revive"))
    (is (str/includes? out "worktree has unresolved conflicts"))))

(deftest revive-starts-what-it-may-and-reopens-only-what-is-not-running
  (let [calls (atom [])
        out   (revive-world calls #(with-out-str (task/revive)))]
    (is (= [[:up "quiz" "brian"]] (filter #(= :up (first %)) @calls))
        "the conflicted worktree is never started")
    (let [tabs (filter #(= :tab (first %)) @calls)]
      (is (= [["conflicted" "claude --resume c-conf --dangerously-skip-permissions"]]
             (map (fn [[_ s o]] [s (:command o)]) tabs))
          "the running conversation is left alone; the other reopens")
      (is (str/ends-with? (:dir (nth (first tabs) 2)) "/brian-worktrees/conflicted")
          "in the cwd it ran in"))
    (is (str/includes? out "already running in /running"))
    (is (= #{["brian--quiz"] ["brian--conflicted"]}
           (set (map second (filter #(= :settle (first %)) @calls)))))))

(deftest revive-brings-a-held-session-back-on-its-workstream
  (let [calls (atom [])
        keys  (atom [])]
    (with-redefs [nido.coordinator.record.workstream/of-session
                  (fn [p s] (when (= [:brian "quiz"] [p s]) "ws-9"))]
      (revive-world calls
                    #(with-redefs [lifecycle/up! (fn [_ o] (swap! keys conj (:runtime-key o)))]
                       (with-out-str (task/revive)))))
    (is (= ["ws-9"] @keys) "a revived session rejoins its workstream's runtime")))

(deftest recovery-verbs-bring-a-held-session-back-on-its-workstream
  (doseq [[verb v] [[task/reset 'reset!] [task/isolate 'isolate!] [task/share 'share!]]]
    (let [seen (atom nil)]
      (with-redefs [lifecycle/reset!   (fn [_ o] (reset! seen o))
                    lifecycle/isolate! (fn [_ o] (reset! seen o))
                    lifecycle/share!   (fn [_ o] (reset! seen o))
                    nido.coordinator.record.workstream/of-session
                    (fn [p s] (when (= [:brian "held"] [p s]) "ws-7"))]
        (verb ":project" "brian" "held")
        (is (= "ws-7" (:runtime-key @seen)) (str v " starts it on its workstream's runtime"))
        (verb ":project" "brian" "loose")
        (is (nil? (:runtime-key @seen)) (str v " leaves a session no workstream holds on its own"))))))
