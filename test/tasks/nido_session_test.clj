(ns tasks.nido-session-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.lane.scratch :as scratch]
   [nido.session.fleet :as fleet]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as state]
   [tasks.nido-session :as task]))

(deftest up-births-a-loose-workstream-for-the-session
  (let [calls (atom [])]
    (with-redefs [lifecycle/up!            (fn [s o] (swap! calls conj [:up s o]))
                  state/session-home-dir   (fn [_ _] "/tmp/home")
                  lifecycle/session-weight (fn [s _] (swap! calls conj [:weight s]) :heavy)
                  scratch/birth!           (fn [p s w & _] (swap! calls conj [:birth p s w]))]
      (task/up ":project" "brian" "refshot")
      (is (some #(= "refshot" (second %)) (filter #(= :up (first %)) @calls)) "lifecycle up still runs")
      (is (some #(= [:birth :brian "refshot" :heavy] %) @calls)
          "loose workstream born carrying the provisioned weight")
      (is (< (.indexOf @calls [:up "refshot" {:project "brian"}])
             (.indexOf @calls [:weight "refshot"]))
          "weight is read AFTER up! — up! is what persists the profile it reads"))))

(deftest up-with-a-workstream-claims-the-name-before-provisioning
  (let [calls (atom [])]
    (with-redefs [lifecycle/up!            (fn [s o] (swap! calls conj [:up s o]))
                  state/session-home-dir   (fn [_ _] "/tmp/home")
                  lifecycle/session-weight (fn [_ _] :heavy)
                  scratch/joinable         (fn [& _] nil)
                  scratch/birth!           (fn [p s w ws] (swap! calls conj [:birth p s w ws]))]
      (task/up ":project" "brian" "child-work" ":ws-id" "ws-child")
      (is (= [[:birth :brian "child-work" nil "ws-child"]
              [:up "child-work" {:project "brian"}]
              [:birth :brian "child-work" :heavy "ws-child"]]
             @calls)
          "the record is written on the named workstream first, :ws-id never reaches up!, and the weight is reconciled after"))))

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
  "Run spawn with every effect recorded instead of performed. Returns
   [calls printed-output]."
  [{:keys [joinable hook? warp?] :or {hook? true warp? true}} & args]
  (let [calls (atom [])
        brief (str (babashka.fs/create-temp-file))]
    (spit brief "Build the widget.")
    (with-redefs [lifecycle/session-from-cwd            (fn [] {:project "nido" :session "parent"})
                  lifecycle/kickoff-hook-installed?     (fn [] hook?)
                  lifecycle/warp?                       (fn [] warp?)
                  lifecycle/up!                         (fn [s _] (swap! calls conj [:up s]))
                  lifecycle/session-weight              (fn [_ _] :heavy)
                  lifecycle/link-add!                   (fn [s o] (swap! calls conj [:link s (select-keys o [:project :type :url :title])]))
                  lifecycle/stage-kickoff!              (fn [s o] (swap! calls conj [:kickoff s o]) "cd /h && zsh k")
                  lifecycle/spawn-tab!                  (fn [s o] (swap! calls conj [:tab s (:cd o)]))
                  state/session-home-dir                (fn [_ _] "/tmp/home")
                  scratch/joinable                      (or joinable (fn [& _] nil))
                  scratch/birth!                        (fn [p s _w ws] (swap! calls conj [:birth p s ws]))
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

(deftest spawn-stages-a-kickoff-addressed-back-to-the-parent
  (let [[calls _] (spawn-calls {})
        [_ s o]   (first (filter #(= :kickoff (first %)) calls))]
    (is (= "kid" s))
    (is (= "kid" (:agent-name o)) "the child's agent answers to the child's session name")
    (is (str/includes? (:prompt o) "SendMessage to `parent-a1`"))
    (is (str/ends-with? (:prompt o) "Build the widget.\n") "the brief verbatim, last")
    (is (some #(= [:tab "kid" :home] %) calls) "the tab opens at the home, where the hook looks")))

(deftest spawn-without-the-hook-says-so-and-what-to-run
  (let [[calls out] (spawn-calls {:hook? false})]
    (is (str/includes? out "_nido_kickoff()") "the snippet to install")
    (is (str/includes? out "cd /h && zsh k") "the command that starts the agent by hand")
    (is (some #(= :kickoff (first %)) calls) "the kickoff is still staged")))

(deftest spawn-outside-warp-opens-no-tab
  (let [[calls out] (spawn-calls {:warp? false})]
    (is (not-any? #(= :tab (first %)) calls))
    (is (str/includes? out "cd /h && zsh k"))))

(deftest spawn-starts-the-child-in-bypass-unless-told-otherwise
  (let [mode-of (fn [calls] (:permission-mode (last (first (filter #(= :kickoff (first %)) calls)))))]
    (is (= "bypassPermissions" (mode-of (first (spawn-calls {})))))
    (is (= "acceptEdits" (mode-of (first (spawn-calls {} ":permission-mode" "acceptEdits")))))))

(deftest spawn-never-asks-the-budget-question
  (let [asked (atom 0)]
    (with-redefs [task/interactive? (fn [] true)
                  task/confirm?     (fn [] (swap! asked inc) false)
                  fleet/snapshot    (fn [] (throw (ex-info "probed" {})))]
      (let [[calls out] (spawn-calls {})]
        (is (zero? @asked))
        (is (not (str/includes? out "Aborted")))
        (is (some #(= [:up "kid"] %) calls))))))
