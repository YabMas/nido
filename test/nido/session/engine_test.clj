(ns nido.session.engine-test
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [babashka.process :as bp]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.platform.process :as proc]
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

(deftest a-join-re-derives-what-the-runtime-rendered-before-it-grew
  ;; a's runtime rendered its config with no app, so `{{app.port}}` stayed unresolved. b's join
  ;; adds the app: the config is rendered again over the grown context, before the app starts.
  (with-runtime-home
    (fn [wt-a wt-b]
      (let [svcs  [{:type :config-file :name :cfg :template {:port "{{app.port}}"}}
                   {:type :eval :name :app}]
            calls (atom [])]
        (state/write-session! "p--ws-1" {:project-dir wt-a :project-name "p" :instance-id "p--ws-1"
                                         :runtime-key "ws-1" :name "a" :members {wt-a "a"}
                                         :service-defs svcs :service-states {:cfg {:path "x"} :repl {:pid 1}}
                                         :context {:session {:instance-id "p--ws-1" :name "a"}}})
        (engine/write-profile-for-session! "p" "a" {:services [:config-file] :worktree {:strategy :git-worktree}})
        (with-redefs [engine/load-session-edn (constantly {:services svcs})
                      service/stop-service!   (fn [d _] (swap! calls conj [:stop (:name d)]))
                      service/start-service!  (fn [d _ _] (swap! calls conj [:start (:name d) (:template d)])
                                                {:state {:pid 2} :context {}})]
          (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"}))
        (let [[stop [_ cfg tpl] [_ app]] @calls]
          (is (= [:stop :cfg] stop))
          (is (= :cfg cfg))
          (is (integer? (:port tpl)) "the config now names the app's port")
          (is (= :app app) "and is rendered before the app starts"))))))

(deftest a-failed-join-puts-back-what-it-re-derived
  ;; The join stops a's config to render it again over the app's port; when the app then fails to
  ;; start, a's config is rendered again as it was, and the runtime's record still describes it.
  (with-runtime-home
    (fn [wt-a wt-b]
      (let [svcs   [{:type :config-file :name :cfg :template {:port "{{app.port}}"}}
                    {:type :eval :name :app}]
            before {:project-dir wt-a :project-name "p" :instance-id "p--ws-1"
                    :runtime-key "ws-1" :name "a" :members {wt-a "a"}
                    :service-defs svcs :service-states {:cfg {:path "x"} :repl {:pid 1}}
                    :context {:session {:instance-id "p--ws-1" :name "a"} :cfg {}}}
            calls  (atom [])]
        (state/write-session! "p--ws-1" before)
        (engine/write-profile-for-session! "p" "a" {:services [:config-file] :worktree {:strategy :git-worktree}})
        (with-redefs [engine/load-session-edn (constantly {:services svcs})
                      service/stop-service!   (fn [d _] (swap! calls conj [:stop (:name d)]))
                      service/start-service!  (fn [d _ _]
                                                (swap! calls conj [:start (:name d) (:template d)])
                                                (when (= :app (:name d)) (throw (ex-info "boom" {})))
                                                {:state {:path "x"} :context {}})]
          (is (thrown? clojure.lang.ExceptionInfo
                       (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"}))))
        (is (= [:start :cfg {:port "{{app.port}}"}] (last @calls))
            "a's config is rendered again from the context it was first rendered from")
        (is (= before (state/read-session "p--ws-1")) "and the record still describes the runtime")))))

(deftest a-failed-join-tells-the-members-where-what-it-restored-now-is
  ;; The join re-derives a's REPL over the app's port; the app then fails, and the REPL restored
  ;; over the old context comes up on another port. The record and a's registration name that
  ;; port, not the one the stopped process had.
  (with-runtime-home
    (fn [wt-a wt-b]
      (let [svcs  [{:type :process :name :repl :args ["{{app.port}}"]}
                   {:type :eval :name :app}]
            ports (atom 1)]
        (state/write-session! "p--ws-1" {:project-dir wt-a :project-name "p" :instance-id "p--ws-1"
                                         :runtime-key "ws-1" :name "a" :members {wt-a "a"}
                                         :service-defs svcs :service-states {:repl {:pid 1}}
                                         :context {:session {:instance-id "p--ws-1" :name "a"}
                                                   :repl {:port 1}}})
        (state/upsert-registry! wt-a {:instance-id "p--ws-1" :runtime-key "ws-1" :session-name "a"
                                      :nrepl-port 1})
        (engine/write-profile-for-session! "p" "a" {:services [:process] :worktree {:strategy :git-worktree}})
        (with-redefs [engine/load-session-edn (constantly {:services svcs})
                      service/stop-service!   (fn [& _] nil)
                      service/start-service!  (fn [d _ _]
                                                (when (= :app (:name d)) (throw (ex-info "boom" {})))
                                                (let [p (swap! ports inc)]
                                                  {:state {:pid p} :context {:port p}}))]
          (is (thrown? clojure.lang.ExceptionInfo
                       (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"}))))
        (let [rt (state/read-session "p--ws-1")]
          (is (= {:pid 3} (get-in rt [:service-states :repl])) "the restored process's state")
          (is (= {:port 3} (get-in rt [:context :repl])) "and the context it returned"))
        (is (= 3 (:nrepl-port (#'engine/registry-entry wt-a)))
            "a is registered with the restored REPL's port")))))

(deftest a-join-records-the-services-it-adds
  ;; A service the declaration gained after the runtime started is started by the join, and the
  ;; record names it — so the last session's stop stops it rather than leaving it running.
  (with-runtime-home
    (fn [_ wt-b]
      (with-redefs [engine/load-session-edn (constantly {:services [{:type :process :name :extra}]})
                    service/start-service!  (fn [& _] {:state {:pid 7} :context {}})]
        (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"}))
      (let [rt (state/read-session "p--ws-1")]
        (is (= {:pid 7} (get-in rt [:service-states :extra])))
        (is (= [:extra] (mapv :name (:service-defs rt))))))))

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

(deftest a-joiner-gets-the-services-the-runtime-lacks
  (with-runtime-home
    (fn [_ wt-b]
      (engine/write-profile-for-session! "p" "a" {:services [:process] :worktree {:strategy :git-worktree}})
      (let [started (atom [])
            briefed (atom [])]
        (with-redefs [engine/load-session-edn   (constantly {:services fake-services})
                      service/start-service!    (fn [svc & _] (swap! started conj (:name svc))
                                                  {:state {:pid 2} :context {:port 1}})
                      launcher/write-artifacts! (fn [ctx & _] (swap! briefed conj (get-in ctx [:session :name])))]
          (engine/start-session! wt-b {:runtime-key "ws-1" :session-name "b"
                                       :profile {:services :all :worktree {:strategy :git-worktree}}}))
        (is (= [:pg :app] @started) "a's repl stays; what b asks for and nobody ran is started")
        (is (= #{:repl :pg :app} (set (keys (:service-states (state/read-session "p--ws-1"))))))
        (is (= #{"a" "b"} (set @briefed)) "a is told the services b brought up")))))
