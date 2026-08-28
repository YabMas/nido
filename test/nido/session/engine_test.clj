(ns nido.session.engine-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.core :as core]
   [nido.process :as proc]
   [nido.session.engine :as engine]
   [nido.session.launcher :as launcher]
   [nido.session.services.eval :as eval-svc]
   [nido.session.state :as state]))

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
  ;; resolve-instance-id falls back to the leaf path component when the path
  ;; isn't in project config; the leaf "test-session" becomes the instance-id.
  ;; With core/nido-home mocked, state/instance-state-dir("test-session") resolves
  ;; to tmp/state/test-session/ — no config required.
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))]
        (let [wt (str (fs/path tmp "worktrees" "test-session"))
              _  (fs/create-dirs wt)
              p  {:services [] :worktree {:strategy :symlink :target "/tmp/x"}}]
          (engine/write-profile-for-session! wt p)
          (is (= p (engine/read-profile-for-session wt)))))
      (finally (fs/delete-tree tmp)))))

(deftest read-profile-returns-nil-when-absent
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))]
        (let [wt (str (fs/path tmp "worktrees" "no-profile-session"))]
          (fs/create-dirs wt)
          (is (nil? (engine/read-profile-for-session wt)))))
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
  {:service-defs    [{:type :process :name :repl}
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
                  proc/process-alive?         (constantly true)
                  launcher/write-artifacts!   (fn [& _] nil)
                  eval-svc/start-app!         (fn [& args] (swap! calls conj (vec args)) true)]
      (engine/start-session! "/tmp/wt" {}))
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
