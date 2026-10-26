(ns tasks.nido-session-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [babashka.fs :as fs]
   [nido.coordinator.lane.conduct :as conduct]
   [nido.coordinator.lane.conductor :as conductor]
   [nido.coordinator.lane.seats :as seats]
   [nido.coordinator.record.session :as csession]
   [nido.coordinator.lane.scratch :as scratch]
   [nido.coordinator.record.session-events :as session-events]
   [nido.session.casualties :as casualties]
   [nido.session.fleet :as fleet]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as state]
   [tasks.nido-session :as task]))

(deftest up-births-a-loose-workstream-for-the-session
  (let [calls (atom [])]
    (with-redefs [lifecycle/up!            (fn [s o] (swap! calls conj [:up s o]))
                  state/session-home-dir   (fn [_ _] "/tmp/home")
                  lifecycle/session-weight (fn [s _] (swap! calls conj [:weight s]) :heavy)
                  scratch/birth!           (fn [p s w & _] (swap! calls conj [:birth p s w]) "ws-loose")]
      (task/up ":project" "brian" "refshot")
      (is (some #(= "refshot" (second %)) (filter #(= :up (first %)) @calls)) "lifecycle up still runs")
      (is (some #(= [:birth :brian "refshot" :heavy] %) @calls)
          "loose workstream born carrying the provisioned weight")
      (is (< (.indexOf @calls [:up "refshot" {:project "brian" :wait false :runtime-key "ws-loose"}])
             (.indexOf @calls [:weight "refshot"]))
          "weight is read AFTER up! — up! is what persists the profile it reads"))))

(deftest up-with-a-workstream-claims-the-name-before-provisioning
  (let [calls (atom [])]
    (with-redefs [lifecycle/up!            (fn [s o] (swap! calls conj [:up s o]))
                  state/session-home-dir   (fn [_ _] "/tmp/home")
                  lifecycle/session-weight (fn [_ _] :heavy)
                  scratch/joinable         (fn [& _] nil)
                  scratch/birth!           (fn [p s w ws] (swap! calls conj [:birth p s w ws]) ws)]
      (task/up ":project" "brian" "child-work" ":ws-id" "ws-child")
      (is (= [[:birth :brian "child-work" nil "ws-child"]
              [:up "child-work" {:project "brian" :wait false :runtime-key "ws-child"}]
              [:birth :brian "child-work" :heavy "ws-child"]]
             @calls)
          "the record is written on the named workstream first, up! comes up on that workstream's runtime rather than seeing :ws-id, and the weight is reconciled after"))))

(deftest up-refuses-an-unjoinable-workstream-before-anything-starts
  (let [calls (atom [])]
    (with-redefs [lifecycle/up!    (fn [s o] (swap! calls conj [:up s o]))
                  scratch/joinable (fn [& _] {:reason :closed :ws-id "ws-child"})
                  scratch/birth!   (fn [& a] (swap! calls conj (into [:birth] a)))]
      (let [out (with-out-str
                  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exit 1"
                                        (with-redefs [task/exit! (fn [c] (throw (ex-info (str "exit " c) {})))]
                                          (task/up ":project" "brian" "child-work" ":ws-id" "ws-child")))))]
        (is (str/includes? out "closed"))
        (is (= [] @calls) "no record, no provisioning")))))

(deftest destroy-reaps-the-loose-workstream
  (let [calls (atom [])]
    (with-redefs [lifecycle/destroy! (fn [s o] (swap! calls conj [:destroy s o]))
                  scratch/reap!      (fn [p s] (swap! calls conj [:reap p s]))]
      (task/destroy ":project" "brian" "refshot")
      (is (some #(= "refshot" (second %)) (filter #(= :destroy (first %)) @calls)) "lifecycle destroy still runs")
      (is (some #(= [:reap :brian "refshot"] %) @calls) "loose workstream reaped"))))

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
                  lifecycle/session-weight (fn [_ _] :heavy)
                  scratch/birth!           (fn [& _] nil)]
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
                  scratch/birth!           (fn [& _] nil)
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
                  scratch/birth!           (fn [& _] nil)
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

(defn- spawn-calls
  "Run spawn with every effect recorded instead of performed — the child carried when `carried`
   names its seat, else driven occupying none. Returns [calls printed-output]."
  [{:keys [joinable warp? carried] :or {warp? true}} & args]
  (let [calls (atom [])
        brief (str (babashka.fs/create-temp-file))]
    (spit brief "Build the widget.")
    (with-redefs [lifecycle/session-from-cwd            (fn [] {:project "nido" :session "parent"})
                  lifecycle/warp?                       (fn [] warp?)
                  lifecycle/up!                         (fn [s _] (swap! calls conj [:up s]))
                  lifecycle/session-weight              (fn [_ _] :heavy)
                  lifecycle/link-add!                   (fn [s o] (swap! calls conj [:link s (select-keys o [:project :type :url :title])]))
                  conductor/carry!                      (fn [_p _w s & [b m]]
                                                          (swap! calls conj [:carry s b m])
                                                          {:carried carried})
                  seats/drive!                          (fn [_p _w s b & [m]] (swap! calls conj [:drive s b m]))
                  csession/workstream-id-for            (fn [_ _] (when (some #(= :birth (first %)) @calls) "ws-child"))
                  csession/read-session                 (fn [_ _ _] {:claude {:session-id "sid-1"}})
                  lifecycle/spawn-tab!                  (fn [s o] (swap! calls conj [:tab s (:cd o) (:command o)]))
                  state/session-home-dir                (fn [_ _] "/tmp/home")
                  scratch/joinable                      (or joinable (fn [& _] nil))
                  scratch/birth!                        (fn [p s _w ws] (swap! calls conj [:birth p s ws]) ws)
                  task/exit!                            (fn [c] (throw (ex-info (str "exit " c) {})))]
      (let [out (with-out-str
                  (try (apply task/spawn (concat [":project" "nido" "kid" ":parent-agent" "parent-a1"
                                                  ":brief-file" brief]
                                                 args))
                       (catch clojure.lang.ExceptionInfo _ nil)))]
        [@calls out]))))

(deftest spawn-goes-through-up-and-hands-it-the-workstream
  (let [[calls _] (spawn-calls {} ":ws-id" "ws-child")]
    (is (= [[:birth :nido "kid" "ws-child"] [:up "kid"] [:birth :nido "kid" "ws-child"]]
           (filterv #(#{:birth :up} (first %)) calls))
        ":ws-id reaches the workstream claim exactly as up passes it, and nowhere else")))

(deftest spawn-refused-by-up-links-and-stages-nothing
  (let [[calls out] (spawn-calls {:joinable (fn [& _] {:reason :closed})} ":ws-id" "ws-child")]
    (is (str/includes? out "closed"))
    (is (= [] calls))))

(deftest spawn-links-both-sessions-to-each-other
  (let [[calls _] (spawn-calls {})]
    (is (= #{[:link "parent" {:project "nido" :type :session :url "nido://session/nido/kid"
                              :title "child · agent kid"}]
             [:link "kid" {:project "nido" :type :session :url "nido://session/nido/parent"
                           :title "parent · agent parent-a1"}]}
           (set (filter #(= :link (first %)) calls))))))

(deftest spawn-drives-the-child-on-a-brief-addressed-back-to-the-parent
  (let [[calls _] (spawn-calls {})
        [_ s b]   (first (filter #(= :drive (first %)) calls))]
    (is (= "kid" s) "not carried, the child is driven on its brief all the same")
    (is (str/includes? b "SendMessage to `parent-a1`"))
    (is (str/ends-with? b "Build the widget.\n") "the brief verbatim, last")
    (is (some #(and (= :tab (first %)) (= ["kid" :home] (subvec % 1 3))
                    (str/ends-with? (nth % 3) "nido:session:attach :session-id sid-1"))
              calls)
        "the tab attaches to the driven conversation rather than starting one")))

(deftest spawn-carried-is-not-driven-again
  (let [[calls out] (spawn-calls {:carried {:seat 1 :session "kid"}})]
    (is (some #(= :carry (first %)) calls))
    (is (not-any? #(= :drive (first %)) calls) "carrying started its conversation")
    (is (str/includes? out "whole seat"))))

(deftest spawn-outside-warp-opens-no-tab
  (let [[calls out] (spawn-calls {:warp? false})]
    (is (not-any? #(= :tab (first %)) calls))
    (is (str/includes? out "nido:session:attach :session-id sid-1"))))

(deftest spawn-never-asks-the-budget-question
  (let [asked (atom 0)]
    (with-redefs [task/interactive? (fn [] true)
                  task/confirm?     (fn [] (swap! asked inc) false)
                  fleet/snapshot    (fn [] (throw (ex-info "probed" {})))]
      (let [[calls out] (spawn-calls {})]
        (is (zero? @asked))
        (is (not (str/includes? out "Aborted")))
        (is (some #(= [:up "kid"] %) calls))))))

(deftest spawn-hands-a-model-to-the-child-only-when-given
  (let [model-of (fn [k calls] (last (first (filter #(= k (first %)) calls))))]
    (is (nil? (model-of :drive (first (spawn-calls {})))))
    (is (= "claude-opus-5-5" (model-of :carry (first (spawn-calls {} ":model" "claude-opus-5-5")))))
    (is (= "claude-opus-5-5" (model-of :drive (first (spawn-calls {} ":model" "claude-opus-5-5")))))))

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
      (with-redefs [casualties/unsettled         (constantly {:casualties cs :unreadable []})
                    casualties/settle!           (fn [c] (swap! calls conj [:settle (mapv :instance-id c)]) true)
                    nido.platform.project/list-projects (constantly {"brian" {:directory "/x"}})
                    lifecycle/worktrees-dir      (constantly base)
                    state/session-home-dir       (fn [_ s] (str "/homes/" s))
                    session-events/conversations (constantly [{:id "c-quiz" :cwd (str (fs/path base "quiz" "src"))
                                                               :last-at-ms 2}
                                                              {:id "c-conf" :cwd (str (fs/path base "conflicted"))
                                                               :last-at-ms 1}])
                    conduct/live-states          (constantly {"c-quiz" {:cwd "/running"}})
                    fleet/snapshot               (constantly [])
                    fleet/machine-bytes          (constantly (* 100 1024 1024 1024))
                    fleet/in-use-bytes           (constantly 0)
                    lifecycle/warp?              (constantly true)
                    lifecycle/up!                (fn [s o] (swap! calls conj [:up s (:project o)]))
                    lifecycle/spawn-tab!         (fn [s o] (swap! calls conj [:tab s o]))
                    babashka.process/shell       (fn [opts & cmd]
                                                   {:exit 0 :out (if (str/ends-with? (:dir opts) "conflicted") "xyz" "")})
                    fs/exists?                   (fn [p & _] (or (str/ends-with? (str p) ".jj") (.exists (java.io.File. (str p)))))]
        (f))
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
    (with-redefs [nido.coordinator.record.session/workstream-id-for
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
                    nido.coordinator.record.session/workstream-id-for
                    (fn [p s] (when (= [:brian "held"] [p s]) "ws-7"))]
        (verb ":project" "brian" "held")
        (is (= "ws-7" (:runtime-key @seen)) (str v " starts it on its workstream's runtime"))
        (verb ":project" "brian" "loose")
        (is (nil? (:runtime-key @seen)) (str v " leaves a session no workstream holds on its own"))))))
