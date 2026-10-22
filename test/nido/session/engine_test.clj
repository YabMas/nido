(ns nido.session.engine-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [babashka.process :as bp]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.platform.process :as proc]
   [nido.session.checkout :as checkout]
   [nido.session.engine :as engine]
   [nido.session.launcher :as launcher]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.service :as service]
   [nido.session.services.eval :as eval-svc]
   [nido.session.state :as state]
   [nido.session.test-pg :as test-pg]))

(def fake-services
  [{:type :postgresql :name :pg}
   {:type :process    :name :repl}
   {:type :eval       :name :app}])

(deftest filter-services-by-profile-allowlist
  (testing ":all returns every service"
    (is (= 3 (count (engine/filter-services fake-services :all)))))
  (testing "empty allowlist returns nothing"
    (is (= 0 (count (engine/filter-services fake-services [])))))
  (testing "specific allowlist returns matching :type"
    (is (= [:postgresql]
           (mapv :type (engine/filter-services fake-services [:postgresql])))))
  (testing "specific allowlist with multiple matches"
    (is (= [:postgresql :process]
           (mapv :type (engine/filter-services fake-services [:postgresql :process]))))))

(deftest profile-persists-across-read
  ;; A profile is kept under the session's own key, whatever runtime it is on.
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))]
        (let [p {:services [] :worktree {:strategy :symlink :target "/tmp/x"}}]
          (engine/write-profile-for-session! "p" "test-session" p)
          (is (= p (engine/read-profile-for-session "p" "test-session")))))
      (finally (fs/delete-tree tmp)))))

(deftest read-profile-returns-nil-when-absent
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))]
        (is (nil? (engine/read-profile-for-session "p" "no-profile-session"))))
      (finally (fs/delete-tree tmp)))))

;; When a service dies during start, the cause lives in the :log-tail that
;; services/process attaches to its ex-data. The rollback handler used to print
;; only (ex-message e) and drop the rest, so a classpath error 400 lines deep in
;; ~/.nido/state/<instance>/logs/repl.log surfaced as a bare one-line failure and
;; the operator had to go find the log by hand.

(deftest start-failure-report-surfaces-the-service-log-tail
  (let [e (ex-info "repl process exited before writing .nrepl-port"
                   {:outcome :process-died
                    :log-path "/tmp/logs/repl.log"
                    :log-tail (str "Error building classpath.\n"
                                   "fatal: could not read Username for 'https://github.com'")})
        report (#'engine/start-failure-report e 2)]
    (is (str/includes? report "repl process exited before writing .nrepl-port")
        "keeps the original failure message")
    (is (str/includes? report "rolling back 2 started service(s)")
        "keeps the rollback count")
    (is (str/includes? report "Error building classpath.")
        "surfaces the log tail that actually explains the failure")
    (is (str/includes? report "/tmp/logs/repl.log")
        "points at the full log for everything the tail cut off")))

(deftest start-failure-report-omits-log-section-when-service-captured-none
  (let [e (ex-info "Timed out waiting for PostgreSQL" {:port 5499})
        report (#'engine/start-failure-report e 1)]
    (is (str/includes? report "Timed out waiting for PostgreSQL"))
    (is (str/includes? report "rolling back 1 started service(s)"))
    (is (not (str/includes? report "last output"))
        "no empty log heading when the service never captured output")))

(deftest start-failure-report-ignores-a-blank-log-tail
  (let [e (ex-info "boom" {:log-tail "   \n  "})
        report (#'engine/start-failure-report e 0)]
    (is (not (str/includes? report "last output"))
        "whitespace-only tail is treated as no tail")))

;; ---------------------------------------------------------------------------
;; App reconciliation on the idempotent `up` path
;; ---------------------------------------------------------------------------

(def ^:private live-jvm-dead-app
  "A session as it looks on disk after its app died inside a JVM that kept
   running: the repl pid is alive, the :eval service holds saved state, and
   nothing is listening on the app port."
  {:instance-id     "p--s"
   :project-name    "p"
   :service-defs    [{:type :process :name :repl}
                     {:type :eval :name :app :start-form "(start)"}]
   :service-states  {:repl {:pid 4242}
                     :app  {:app-port 3646 :nrepl-port 53250 :host "s.p.localhost"}}
   :context         {:session {:instance-id "p--s"}
                     :app {:port 3646}}})

(defn- start-session-on-live-session!
  "Drive `start-session!` down its idempotent branch, collecting the
   `start-app!` calls it makes. Returns the collected argument vectors."
  [existing]
  (let [calls (atom [])]
    (with-redefs [engine/resolve-project-name (constantly "p")
                  engine/resolve-instance-id  (constantly "p--s")
                  engine/load-session-edn     (constantly {:services []})
                  state/read-session          (constantly existing)
                  state/write-session!        (fn [& _] nil)
                  state/upsert-registry!      (fn [& _] nil)
                  proc/process-alive?         (constantly true)
                  launcher/write-artifacts!   (fn [& _] nil)
                  eval-svc/start-app!         (fn [& args] (swap! calls conj (vec args)) true)]
      (engine/start-session! "/tmp/wt" {:runtime-key "s" :session-name "s"}))
    @calls))

(deftest idempotent-up-restarts-an-app-that-died-inside-a-live-jvm
  ;; The regression: `session-alive?` answers a question about processes, and
  ;; the :eval service owns none — so `up` (and the dashboard retry behind it)
  ;; short-circuited on the live repl pid and never evaluated the start-form.
  (let [calls (start-session-on-live-session! live-jvm-dead-app)]
    (is (= 1 (count calls))
        "the app is started even though the session is 'already running'")
    (let [[svc-def saved-state session-ctx] (first calls)]
      (is (= "(start)" (:start-form svc-def)))
      (is (= 3646 (:app-port saved-state))
          "hands over the saved state, so the app returns on its own port")
      (is (= 53250 (:nrepl-port saved-state))
          "targets the JVM that is still running, not a fresh one")
      (is (= (:context live-jvm-dead-app) session-ctx)
          "substitutes the start-form against the persisted session context"))))

(deftest idempotent-up-leaves-a-session-without-an-app-alone
  (testing "no :eval service at all (an nrepl-only project)"
    (is (empty? (start-session-on-live-session!
                 (-> live-jvm-dead-app
                     (update :service-defs #(filterv (comp #{:process} :type) %))
                     (update :service-states dissoc :app))))))
  (testing ":eval service the session's profile never started"
    (is (empty? (start-session-on-live-session!
                 (update live-jvm-dead-app :service-states dissoc :app)))
        "no saved state means no reserved port — provisioning is not this path's job")))

(deftest reconcile-app-lets-the-real-failure-through
  ;; `dev-action!` turns a thrown cause into the message it shows; swallowing
  ;; here is what produced the fabricated "did not open its port" report.
  (with-redefs [eval-svc/start-app! (fn [& _] (throw (ex-info "nREPL is not listening" {})))]
    (is (thrown-with-msg? Exception #"nREPL is not listening"
                          (engine/reconcile-app! live-jvm-dead-app)))))

;; ---------------------------------------------------------------------------
;; Stopping a session whose start never completed
;; ---------------------------------------------------------------------------

(deftest stop-session-without-session-edn-stops-what-the-pid-files-name
  ;; A start killed after spawning writes no session.edn, so down used to find
  ;; "no session" and leave the detached process running.
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))
                    core/log-step (constantly nil)
                    engine/resolve-project-name (constantly "p")
                    engine/resolve-instance-id  (constantly "p--s")
                    engine/read-session-edn     (constantly {:services fake-services})]
        (let [proc     (bp/process ["sleep" "30"])
              pid      (.pid ^Process (:proc proc))
              pid-file (state/pid-file "p--s" :repl)]
          (try
            (fs/create-dirs (fs/parent pid-file))
            (spit pid-file (str pid))
            (with-out-str (engine/stop-session! (str tmp)))
            (is (not (proc/process-alive? pid)))
            (is (not (fs/exists? pid-file)))
            (finally (proc/stop-process! pid)))))
      (finally (fs/delete-tree tmp)))))

(deftest stop-session-reaches-the-record-of-a-service-session-edn-never-saved
  ;; session.edn from a start that completed without this :process service
  ;; (a narrower profile), and a later start killed after spawning it.
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))
                    core/log-step (constantly nil)
                    engine/resolve-instance-id (constantly "p--s")
                    state/read-session (constantly {:project-dir (str tmp) :name "s"
                                                    :service-defs fake-services
                                                    :service-states {}})
                    state/delete-session! (fn [_] nil)
                    state/remove-from-registry! (fn [_] nil)
                    launcher/remove-artifacts! (fn [& _] nil)]
        (let [proc     (bp/process ["sleep" "30"])
              pid      (.pid ^Process (:proc proc))
              pid-file (state/pid-file "p--s" :repl)]
          (try
            (fs/create-dirs (fs/parent pid-file))
            (spit pid-file (str pid))
            (with-out-str (engine/stop-session! (str tmp)))
            (is (not (proc/process-alive? pid)))
            (finally (proc/stop-process! pid)))))
      (finally (fs/delete-tree tmp)))))

;; ---------------------------------------------------------------------------
;; One runtime per key: sessions join it, and it stops with its last member
;; ---------------------------------------------------------------------------

(deftest an-instance-id-is-the-whole-key
  (is (= "p--ws-1" (engine/instance-id-for-key "p" "ws-1")))
  (is (= "p--feat~x" (engine/instance-id-for-key "p" "feat/x"))
      "a slashed key keeps its whole name, as one path segment")
  (is (not= (engine/instance-id-for-key "p" "a/x") (engine/instance-id-for-key "p" "b/x"))
      "two sessions that share a leaf do not share a runtime"))

(defn- with-runtime-home
  "Run `f` against a temp nido home holding a live runtime `p--ws-1` that the session `a` in
   worktree `wt-a` started; `f` gets the two worktree paths."
  [f]
  (let [tmp  (fs/create-temp-dir)
        wt-a (str (fs/path tmp "worktrees" "a"))
        wt-b (str (fs/path tmp "worktrees" "b"))]
    (try
      (with-redefs [core/nido-home               (constantly (str tmp))
                    engine/resolve-project-name  (constantly "p")
                    engine/load-session-edn      (constantly {:services []})
                    proc/process-alive?          (constantly true)
                    launcher/write-artifacts!    (fn [& _] nil)
                    launcher/remove-artifacts!   (fn [& _] nil)
                    eval-svc/start-app!          (fn [& _] true)]
        (fs/create-dirs wt-a)
        (fs/create-dirs wt-b)
        (state/write-session! "p--ws-1" {:project-dir wt-a :project-name "p" :instance-id "p--ws-1"
                                         :runtime-key "ws-1" :name "a" :members {wt-a "a"}
                                         :service-defs [] :service-states {:repl {:pid 1}}
                                         :context {:session {:instance-id "p--ws-1" :name "a"}}})
        (state/upsert-registry! wt-a {:instance-id "p--ws-1" :runtime-key "ws-1" :session-name "a"})
        (f wt-a wt-b))
      (finally (fs/delete-tree tmp)))))

(deftest a-second-session-joins-the-live-runtime
  (with-runtime-home
    (fn [wt-a wt-b]
      (let [started (atom 0)]
        (with-redefs [service/start-service! (fn [& _] (swap! started inc) {:state {} :context {}})]
          (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"}))
        (is (zero? @started) "joining provisions nothing")
        (is (= {wt-a "a" wt-b "b"} (:members (state/read-session "p--ws-1"))))
        (is (= "p--ws-1" (engine/resolve-instance-id wt-b)))
        (is (= ["a" "b"] (sort (engine/sessions-on "p--ws-1"))))))))

(deftest leaving-a-shared-runtime-stops-nothing
  (with-runtime-home
    (fn [wt-a wt-b]
      (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"})
      (let [stopped (atom 0)]
        (with-redefs [service/stop-service! (fn [& _] (swap! stopped inc))]
          (engine/stop-session! wt-a)
          (is (zero? @stopped) "b is still on it")
          (is (= {wt-b "b"} (:members (state/read-session "p--ws-1"))))
          (is (nil? (engine/resolve-instance-id wt-a)))
          (engine/stop-session! wt-b)
          (is (nil? (state/read-session "p--ws-1")) "the last member takes the runtime down"))))))

(deftest a-joiner-is-rendered-a-test-database-of-its-own
  (with-runtime-home
    (fn [wt-a wt-b]
      (let [defs [{:type :test-pg :name :test-pg :db-stem "t"}
                  {:type :config-file :name :local-edn :path "local.edn"
                   :template {:test-db "{{test-pg.db-name}}" :repl "{{repl.port}}"}}]
            rt   (state/read-session "p--ws-1")
            keys (atom [])]
        (state/write-session! "p--ws-1" (assoc rt :service-defs defs
                                               :service-states {:test-pg {} :local-edn {} :repl {:pid 1}}
                                               :context (assoc (:context rt)
                                                               :test-pg {:db-name "t_runtime"}
                                                               :repl {:port 7})))
        (with-redefs [test-pg/member-context (fn [_ _ own] (swap! keys conj own) {:db-name (str "t_" own)})]
          (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"}))
        (is (= ["p--b"] @keys) "keyed by the joining session, not the runtime")
        (is (= {:test-db "t_p--b" :repl 7} (edn/read-string (slurp (str (fs/path wt-b "local.edn")))))
            "its own test database beside the runtime's ports")
        (is (not (fs/exists? (str (fs/path wt-a "local.edn"))))
            "the worktree the runtime runs out of is its services' to render")
        (engine/stop-session! wt-b)
        (is (not (fs/exists? (str (fs/path wt-b "local.edn")))) "leaving restores what was there")
        (is (nil? (get-in (state/read-session "p--ws-1") [:member-states wt-b])))))))

(deftest a-session-on-another-runtime-leaves-it-first
  (with-runtime-home
    (fn [wt-a _]
      (with-redefs [service/start-service! (fn [& _] {:state {} :context {}})]
        (engine/start-session! wt-a {:runtime-key "ws-2" :session-name "a"}))
      (is (nil? (state/read-session "p--ws-1")) "a was its only member")
      (is (= "p--ws-2" (engine/resolve-instance-id wt-a)))
      (is (= "ws-2" (engine/runtime-key-of wt-a))))))

(deftest a-worktree-with-no-key-is-refused
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home              (constantly (str tmp))
                    engine/resolve-project-name (constantly "p")
                    engine/load-session-edn     (constantly {:services []})]
        (is (thrown-with-msg? Exception #"No runtime key"
                              (engine/start-session! (str (fs/path tmp "wt")) {:session-name "x"}))))
      (finally (fs/delete-tree tmp)))))

(deftest recovering-a-dead-runtime-keeps-every-member
  (with-runtime-home
    (fn [wt-a wt-b]
      (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"})
      (let [briefed (atom [])]
        (with-redefs [proc/process-alive?       (constantly false)
                      launcher/write-artifacts! (fn [ctx & _] (swap! briefed conj (get-in ctx [:session :name])))]
          (engine/start-session! wt-a {:runtime-key "ws-1" :session-name "a"}))
        (is (= {wt-a "a" wt-b "b"} (:members (state/read-session "p--ws-1")))
            "b is still on the runtime a restarted")
        (is (= "p--ws-1" (engine/resolve-instance-id wt-b)))
        (is (= ["a" "b"] (sort @briefed)) "b is briefed with the recovered runtime too")
        (let [stopped (atom 0)]
          (with-redefs [service/stop-service! (fn [& _] (swap! stopped inc))]
            (engine/stop-session! wt-a))
          (is (zero? @stopped) "a leaving does not stop the runtime b is on"))))))

(deftest leaving-another-runtime-keeps-the-run-link
  (with-runtime-home
    (fn [wt-a _]
      (let [home    (state/session-home-dir "p" "a")
            run-dir (str (fs/create-dirs (fs/path (fs/parent home) "run-1")))
            written (atom ::none)]
        (fs/create-dirs home)
        (fs/create-sym-link (fs/path home "run-link") run-dir)
        (with-redefs [service/start-service!     (fn [& _] {:state {} :context {}})
                      launcher/remove-artifacts! (fn [& _] (fs/delete-tree home))
                      launcher/write-artifacts!  (fn [_ _ rd] (reset! written rd))]
          (engine/start-session! wt-a {:runtime-key "ws-2" :session-name "a"}))
        (is (= run-dir @written)
            "the home's run link is written back on the runtime the session moved to")))))

(deftest a-joining-session-keeps-its-own-profile-and-setup
  (with-runtime-home
    (fn [wt-a wt-b]
      (let [full  {:services :all :worktree {:strategy :git-worktree}}
            lite  {:services [] :worktree {:strategy :symlink :target "/tmp/x"}}
            setup (atom [])]
        (engine/write-profile-for-session! "p" "a" full)
        (with-redefs [engine/load-session-edn (constantly {:services [] :setup [{:type :probe}]})
                      engine/run-setup-step!  (fn [_ dir] (swap! setup conj dir))]
          (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b" :profile lite}))
        (is (= [wt-b] @setup) "the joiner's own working copy is set up")
        (is (= lite (engine/read-profile-for-session "p" "b")) "the joiner keeps its own profile")
        (is (= full (engine/read-profile-for-session "p" "a")) "and leaves the starter's alone")))))

(deftest a-profile-kept-before-sessions-had-their-own-is-still-read
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))]
        (let [p {:services [] :worktree {:strategy :symlink :target "/tmp/x"}}]
          (fs/create-dirs (state/instance-state-dir "p--old"))
          (spit (str (fs/path (state/instance-state-dir "p--old") "profile.edn")) (pr-str p))
          (is (= p (engine/read-profile-for-session "p" "old"))
              "a lite session restarted onto its workstream does not come back full")
          (fs/delete-tree (state/instance-state-dir "p--old"))
          (is (= p (engine/read-profile-for-session "p" "old"))
              "the profile outlives the runtime directory it was kept in, once reclaim takes it")))
      (finally (fs/delete-tree tmp)))))

(deftest a-slash-named-sessions-old-profile-is-read-under-its-worktree-leaf
  ;; Before runtimes were keyed by session, `feat/x`'s worktree leaf `x` keyed its state.
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))]
        (let [p {:services [] :worktree {:strategy :symlink :target "/tmp/x"}}]
          (fs/create-dirs (state/instance-state-dir "p--x"))
          (spit (str (fs/path (state/instance-state-dir "p--x") "profile.edn")) (pr-str p))
          (is (= p (engine/read-profile-for-session "p" "feat/x"))
              "a lite session named with a slash does not come back full")
          (is (fs/exists? (state/session-profile-path "p" "feat/x")) "and is copied to its own key")))
      (finally (fs/delete-tree tmp)))))

(deftest starting-a-member-again-keeps-its-recorded-profile
  (with-runtime-home
    (fn [_ wt-b]
      (let [lite {:services [] :worktree {:strategy :symlink :target "/tmp/x"}}
            full {:services :all :worktree {:strategy :git-worktree}}]
        (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b" :profile lite})
        (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b" :profile full})
        (is (= lite (engine/read-profile-for-session "p" "b"))
            "an idempotent start with a defaulted profile does not rewrite how b's working copy was made")))))

(deftest recovering-a-runtime-provides-what-every-member-asks-for
  (with-runtime-home
    (fn [wt-a wt-b]
      (engine/write-profile-for-session! "p" "a" {:services :all :worktree {:strategy :git-worktree}})
      (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"
                                   :profile {:services [] :worktree {:strategy :symlink :target "/x"}}})
      (let [started (atom [])]
        (with-redefs [proc/process-alive?     (constantly false)
                      engine/load-session-edn (constantly {:services fake-services})
                      service/start-service!  (fn [svc & _] (swap! started conj (:name svc))
                                                {:state {:pid 1} :context {}})]
          (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"
                                       :profile {:services [] :worktree {:strategy :symlink :target "/x"}}}))
        (is (= [:pg :repl :app] @started)
            "b restarting the dead runtime brings back the services a, still on it, needs")))))

(deftest a-worktree-backing-a-runtime-others-are-on-is-named
  (with-runtime-home
    (fn [wt-a wt-b]
      (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"})
      (is (= ["b"] (engine/sessions-backed-by wt-a nil)) "a started the runtime b is on")
      (engine/stop-session! wt-a)
      (is (= ["b"] (engine/sessions-backed-by wt-a nil)) "and it still runs out of a's worktree after a leaves")
      (is (empty? (engine/sessions-backed-by wt-b nil)) "b's own worktree backs nothing"))))

(deftest a-worktree-backing-a-runtime-others-are-on-starts-no-other-runtime
  ;; Moving a to a fresh runtime would start it in the worktree the first one still runs out of
  ;; for b, so the two would overwrite and delete each other's generated config and port file.
  (with-runtime-home
    (fn [wt-a wt-b]
      (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"})
      (let [started (atom 0)]
        (with-redefs [service/start-service! (fn [& _] (swap! started inc) {:state {} :context {}})]
          (is (= :runtime-backed
                 (try (engine/start-session! wt-a {:runtime-key "ws-2" :session-name "a"}) nil
                      (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))
        (is (zero? @started) "nothing is provisioned in a's worktree")
        (is (nil? (state/read-session "p--ws-2")))
        (is (= #{"a" "b"} (set (engine/sessions-on "p--ws-1"))) "refused before a left anything"))
      (engine/stop-session! wt-b)
      (with-redefs [service/start-service! (fn [& _] {:state {} :context {}})]
        (engine/start-session! wt-a {:runtime-key "ws-2" :session-name "a"})
        (is (= ["a"] (engine/sessions-on "p--ws-2")) "with nobody else on it, a moves")))))

(deftest a-stopped-session-keeps-no-key-of-its-own
  ;; Which workstream holds a session is nido's to say each time, and can change while it is down:
  ;; once stopped, a start that names no key brings it up on its own runtime, never on one a key
  ;; kept from an earlier start names.
  (with-runtime-home
    (fn [_ wt-b]
      (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"})
      (engine/stop-session! wt-b)
      (with-redefs [lifecycle/resolve-project (constantly ["p" {:directory "/nonexistent"}])]
        (is (= "b" (:runtime-key (#'lifecycle/with-context "b" {:project "p" :path wt-b}))))))))

(deftest a-joiner-asking-for-a-missing-service-restarts-the-runtime-under-the-union
  ;; a's runtime runs only its repl; b asks for everything. The runtime is not grown by the
  ;; services it lacks: it is stopped whole and started cold under a's and b's union, out of the
  ;; worktree it ran out of, and both are told what it came up on.
  (with-runtime-home
    (fn [wt-a wt-b]
      (engine/write-profile-for-session! "p" "a" {:services [:process] :worktree {:strategy :git-worktree}})
      (let [calls   (atom [])
            briefed (atom [])]
        (with-redefs [engine/load-session-edn   (constantly {:services fake-services})
                      service/stop-service!     (fn [d _] (swap! calls conj [:stop (:name d)]))
                      service/start-service!    (fn [svc & _] (swap! calls conj [:start (:name svc)])
                                                  {:state {:pid 2} :context {:port 1}})
                      launcher/write-artifacts! (fn [ctx & _] (swap! briefed conj (get-in ctx [:session :name])))]
          (state/write-session! "p--ws-1" (assoc (state/read-session "p--ws-1")
                                                 :service-defs [{:type :process :name :repl}]))
          (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"
                                       :profile {:services :all :worktree {:strategy :git-worktree}}}))
        (is (= [[:stop :repl] [:start :pg] [:start :repl] [:start :app]] @calls)
            "the running repl is stopped and everything the union asks for started from nothing")
        (let [rt (state/read-session "p--ws-1")]
          (is (= {wt-a "a" wt-b "b"} (:members rt)) "every member stays on it")
          (is (= wt-a (:project-dir rt)) "out of the worktree it ran out of")
          (is (= #{:repl :pg :app} (set (keys (:service-states rt))))))
        (is (= #{"a" "b"} (set @briefed)) "a is told what the runtime came up on")))))

(deftest a-joiner-asking-for-nothing-new-provisions-nothing
  (with-runtime-home
    (fn [_ wt-b]
      (let [calls (atom 0)]
        (with-redefs [service/stop-service!  (fn [& _] (swap! calls inc))
                      service/start-service! (fn [& _] (swap! calls inc) {:state {} :context {}})]
          (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"
                                       :profile {:services [] :worktree {:strategy :git-worktree}}}))
        (is (zero? @calls) "nothing is stopped or started")))))

;; ---------------------------------------------------------------------------
;; A runtime of jj workspaces runs out of a checkout of its own
;; ---------------------------------------------------------------------------

(defn- with-checkout-stubs
  "Run `f` with every worktree a jj workspace and the checkout's refresh recorded in `refreshes`
   (the members each was asked for) — or throwing a conflict when `conflict?` holds."
  [refreshes conflict? f]
  (with-redefs [checkout/jj-workspace? (constantly true)
                checkout/refresh!      (fn [id members & _]
                                         (when @conflict?
                                           (throw (ex-info "conflict" {:reason :checkout-conflict
                                                                       :paths ["x"]})))
                                         (swap! refreshes conj (set (vals members)))
                                         (str "/state/" id "/checkout"))
                checkout/remove!       (fn [id _] (swap! refreshes conj [:removed id]) nil)]
    (f)))

(deftest a-runtime-of-jj-workspaces-runs-out-of-its-own-checkout
  (let [tmp (fs/create-temp-dir)
        wt  (str (fs/path tmp "wt"))
        refreshes (atom [])]
    (try
      (with-redefs [core/nido-home              (constantly (str tmp))
                    engine/resolve-project-name (constantly "p")
                    engine/load-session-edn     (constantly {:services []})
                    launcher/write-artifacts!   (fn [& _] nil)
                    launcher/remove-artifacts!  (fn [& _] nil)
                    service/start-service!      (fn [& _] {:state {} :context {}})]
        (with-checkout-stubs refreshes (atom false)
          (fn []
            (engine/start-session! wt {:runtime-key "ws-9" :session-name "s"})
            (let [rt (state/read-session "p--ws-9")]
              (is (= "/state/p--ws-9/checkout" (:project-dir rt) (:checkout rt))
                  "the JVM runs out of the checkout, not the worktree")
              (is (= {wt "s"} (:members rt))))
            (is (= "/state/p--ws-9/checkout" (engine/sync-runtime! wt)))
            (engine/stop-session! wt)
            (is (= [:removed "p--ws-9"] (last @refreshes)) "the last stop removes the checkout"))))
      (finally (fs/delete-tree tmp)))))

(deftest a-conflicting-checkout-refuses-a-restarting-join-before-anything-stops
  (with-runtime-home
    (fn [wt-a wt-b]
      (state/write-session! "p--ws-1" (assoc (state/read-session "p--ws-1") :checkout "/co" :project-dir "/co"))
      (engine/write-profile-for-session! "p" "a" {:services [] :worktree {:strategy :git-worktree}})
      (let [stopped (atom 0)]
        (with-checkout-stubs (atom []) (atom true)
          (fn []
            (with-redefs [engine/load-session-edn (constantly {:services fake-services})
                          service/stop-service!   (fn [& _] (swap! stopped inc))]
              (is (= :checkout-conflict
                     (try (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"
                                                       :profile {:services :all :worktree {:strategy :git-worktree}}})
                          nil
                          (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))))
        (is (zero? @stopped) "the runtime keeps running what it ran")
        (is (= {wt-a "a"} (:members (state/read-session "p--ws-1"))))))))

(deftest a-join-and-a-leave-bring-the-checkout-up-to-date
  (with-runtime-home
    (fn [wt-a wt-b]
      (state/write-session! "p--ws-1" (assoc (state/read-session "p--ws-1") :checkout "/co" :project-dir "/co"))
      (let [refreshes (atom [])
            conflict? (atom false)]
        (with-checkout-stubs refreshes conflict?
          (fn []
            (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"})
            (is (= [#{"a" "b"}] @refreshes) "b's working copy is merged in as it joins")
            (reset! conflict? true)
            (engine/stop-session! wt-b)
            (is (= {wt-a "a"} (:members (state/read-session "p--ws-1")))
                "a conflict while leaving keeps the old checkout and still lets b go")
            (reset! conflict? false)
            (is (thrown? clojure.lang.ExceptionInfo
                         (do (reset! conflict? true) (engine/sync-runtime! wt-a)))
                "a sync that conflicts is refused")))))))

(deftest joining-a-runtime-recorded-before-members-keeps-its-starter
  ;; A runtime written before runtimes recorded :members has one member, the session that
  ;; started it; a joiner is added beside it, so the starter leaving stops nothing.
  (with-runtime-home
    (fn [wt-a wt-b]
      (state/write-session! "p--ws-1" (dissoc (state/read-session "p--ws-1") :members))
      (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"})
      (is (= {wt-a "a" wt-b "b"} (:members (state/read-session "p--ws-1"))))
      (let [stopped (atom 0)]
        (with-redefs [service/stop-service! (fn [& _] (swap! stopped inc))]
          (engine/stop-session! wt-b)
          (is (zero? @stopped) "a is still on it")
          (is (= {wt-a "a"} (:members (state/read-session "p--ws-1")))))))))

(deftest a-live-runtime-of-jj-workspaces-is-moved-onto-a-checkout-of-its-own
  ;; Started before runtimes of jj workspaces ran out of a checkout: it runs out of a's worktree.
  ;; The next session to come up on it restarts it onto one, every member kept.
  (with-runtime-home
    (fn [wt-a wt-b]
      (is (true? (with-redefs [checkout/jj-workspace? (constantly true)]
                   (engine/owes-checkout? (state/read-session "p--ws-1"))))
          "every member a jj workspace, and no checkout")
      (let [refreshes (atom [])
            stopped   (atom 0)]
        (with-checkout-stubs refreshes (atom false)
          (fn []
            (with-redefs [service/stop-service!  (fn [& _] (swap! stopped inc))
                          service/start-service! (fn [& _] {:state {} :context {}})]
              (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"}))
            (let [rt (state/read-session "p--ws-1")]
              (is (= "/state/p--ws-1/checkout" (:checkout rt) (:project-dir rt)))
              (is (= {wt-a "a" wt-b "b"} (:members rt)))
              (is (false? (engine/owes-checkout? rt))))))))))
