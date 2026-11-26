(ns nido.session.shared-pg-budget-test
  "The shared cluster's connection budget: the limit nido starts it with, the validation of
   a shared-mode definition, admission against sibling runtimes' claims, the release of a
   claim, and the refusal a start that does not fit is thrown and kept as."
  (:require
   [babashka.fs :as fs]
   [clojure.edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.process :as proc]
   [nido.session.engine :as engine]
   [nido.session.failure :as failure]
   [nido.session.service :as service]
   [nido.session.services.postgresql :as pg]
   [nido.session.shared-pg :as shared]
   [nido.session.state :as state]
   [tasks.nido-shared-pg :as task]))

(def ^:private brian-budget
  {:max-connections 1000 :reserved-connections 20 :session-connections 45})

(defn- with-cluster
  "Call `(f dir)` with the project's shared dir at a temp `dir`, a data dir whose settings
   file says it started with `limit`, the cluster up on port 7450, and `registry` as the
   session registry (an entry is live unless it says :down?)."
  [{:keys [limit registry] :or {registry {}}} f]
  (let [dir  (str (fs/create-temp-dir))
        data (str (fs/path dir "pg-data"))]
    (fs/create-dirs data)
    (when limit
      (spit (str (fs/path data "nido-shared.conf")) (str "max_connections = " limit "\n")))
    (try
      (with-redefs [state/project-shared-dir (fn [_] dir)
                    state/shared-pg-data-dir (fn [_] data)
                    shared/ensure-up!        (fn [_ _] {:port 7450})
                    state/read-registry      (fn [] registry)
                    state/session-live?      (fn [e] (not (:down? e)))]
        (f dir))
      (finally (fs/delete-tree dir)))))

(defn- claims [dir]
  (let [f (str (fs/path dir "claims.edn"))]
    (if (fs/exists? f) (read-string (slurp f)) {})))

(defn- up-entry [instance-id & {:as more}]
  (merge {:project-name "brian" :instance-id instance-id :pg-port 7450 :app-port 1}
         more))

;; ---------------------------------------------------------------------------
;; shared-limit-declared / no-restart-to-apply
;; ---------------------------------------------------------------------------

(defn- start-existing-in
  "Run start-existing! over a temp data dir holding `conf` as its postgresql.conf, with the
   postmaster `status`. Answers {:calls :conf :settings} after."
  [status conf max-connections]
  (let [data  (str (fs/create-temp-dir))
        calls (atom [])]
    (spit (str (fs/path data "postgresql.conf")) conf)
    (with-redefs [state/shared-pg-data-dir     (fn [_] data)
                  state/shared-log-file        (fn [_] (str data ".log"))
                  state/read-shared-meta       (fn [_] {})
                  state/write-shared-meta!     (fn [& _] nil)
                  pg/find-pg-bin-dir           (constantly "/bin")
                  pg/detect-running-postmaster (fn [_] status)
                  pg/finish-stopping!          (fn [_ s] s)
                  pg/pg-ctl-stop!              (fn [_] (swap! calls conj :stop))
                  pg/pg-ctl-start!             (fn [& _]
                                                 (swap! calls conj
                                                        [:start (slurp (str (fs/path data "nido-shared.conf")))]))
                  pg/wait-for-tcp!             (fn [_] true)
                  shared/resolve-shared-port   (constantly 6145)
                  proc/tcp-open?               (fn [_] false)]
      (#'shared/start-existing! "brian" max-connections)
      (let [settings (fs/path data "nido-shared.conf")]
        (try
          {:calls    @calls
           :conf     (slurp (str (fs/path data "postgresql.conf")))
           :settings (when (fs/exists? settings) (slurp (str settings)))
           :limit    (with-redefs [state/shared-pg-data-dir (fn [_] data)]
                       (shared/started-with-limit "brian"))}
          (finally (fs/delete-tree data)))))))

(deftest a-start-writes-the-declared-limit-into-an-included-settings-file-just-before-it
  (let [{:keys [calls conf settings limit]}
        (start-existing-in nil "max_connections = 100\n" 1000)]
    (is (= 1 (count calls)) "exactly one start, and no stop")
    (is (str/includes? (second (first calls)) "max_connections = 1000")
        "the settings file says 1000 by the time the postmaster starts")
    (is (str/includes? settings "max_connections = 1000"))
    (is (str/ends-with? (str/trim conf) "include_if_exists = 'nido-shared.conf'")
        "the include comes last, so it overrides the clone's own max_connections = 100")
    (is (= 1000 limit) "the started-with limit is read from the settings file")))

(deftest the-include-is-added-once-across-starts
  (let [{:keys [conf]} (start-existing-in
                        nil "max_connections = 100\n\ninclude_if_exists = 'nido-shared.conf'\n" 500)]
    (is (= 1 (count (re-seq #"include_if_exists = 'nido-shared.conf'" conf))))))

(deftest a-commented-out-include-is-made-active-and-last
  (let [{:keys [conf]} (start-existing-in
                        nil "#include_if_exists = 'nido-shared.conf'\nmax_connections = 100\n" 500)]
    (is (str/ends-with? (str/trim conf) "\ninclude_if_exists = 'nido-shared.conf'"))
    (is (not (str/includes? conf "#include_if_exists")))))

(deftest a-setting-after-the-include-no-longer-overrides-it
  (let [{:keys [conf]} (start-existing-in
                        nil "include_if_exists = 'nido-shared.conf'\nmax_connections = 100\n" 500)]
    (is (= 1 (count (re-seq #"include_if_exists = 'nido-shared.conf'" conf))))
    (is (str/ends-with? (str/trim conf) "include_if_exists = 'nido-shared.conf'")
        "the include follows the later max_connections = 100, so 500 wins")))

(deftest an-alter-system-max-connections-is-dropped-before-a-start
  (let [data (str (fs/create-temp-dir))]
    (try
      (spit (str (fs/path data "postgresql.conf")) "max_connections = 100\n")
      (spit (str (fs/path data "postgresql.auto.conf"))
            "# Do not edit this file manually!\nmax_connections = '50'\nwork_mem = '8MB'\n")
      (#'shared/write-limit! data 500)
      (let [auto (slurp (str (fs/path data "postgresql.auto.conf")))]
        (is (not (str/includes? auto "max_connections")))
        (is (str/includes? auto "work_mem = '8MB'") "other ALTER SYSTEM settings are kept"))
      (finally (fs/delete-tree data)))))

(deftest a-running-cluster-is-adopted-never-restarted-to-apply-a-changed-limit
  (let [{:keys [calls settings limit]}
        (start-existing-in {:status :running :pid 1 :port 7450} "max_connections = 100\n" 1000)]
    (is (empty? calls) "no stop, no start")
    (is (nil? settings) "the settings file is written only immediately before a start")
    (is (= 100 limit)
        "a postmaster nido found running with no settings file started with postgresql.conf's value")))

(deftest started-with-limit-falls-back-to-postgres-stock-default
  (let [data (str (fs/create-temp-dir))]
    (spit (str (fs/path data "postgresql.conf")) "#max_connections = 300\nshared_buffers = 128MB\n")
    (with-redefs [state/shared-pg-data-dir (fn [_] data)]
      (is (= 100 (shared/started-with-limit "brian")) "a commented-out setting is not a setting"))
    (fs/delete-tree data)))

(deftest started-with-limit-honours-alter-system-on-a-cluster-nido-did-not-start
  (let [data (str (fs/create-temp-dir))]
    (try
      (spit (str (fs/path data "postgresql.conf")) "max_connections = 100\n")
      (spit (str (fs/path data "postgresql.auto.conf"))
            "# Do not edit this file manually!\nmax_connections = '250'\n")
      (with-redefs [state/shared-pg-data-dir (fn [_] data)]
        (is (= 250 (shared/started-with-limit "brian"))
            "PostgreSQL applies postgresql.auto.conf after postgresql.conf")
        (spit (str (fs/path data "nido-shared.conf")) "max_connections = 1000\n")
        (is (= 1000 (shared/started-with-limit "brian"))
            "a start nido made is answered by its own settings file"))
      (finally (fs/delete-tree data)))))

(deftest started-with-limit-reads-every-assignment-postgres-accepts
  (let [data (str (fs/create-temp-dir))]
    (with-redefs [state/shared-pg-data-dir (fn [_] data)]
      (doseq [[conf want] [["max_connections 20\n" 20]
                           ["max_connections=30 # tuned\n" 30]
                           ["MAX_CONNECTIONS = '40'\n" 40]
                           ["max_connections = 50\nmax_connections 60\n" 60]]]
        (spit (str (fs/path data "postgresql.conf")) conf)
        (is (= want (shared/started-with-limit "brian")) conf)))
    (fs/delete-tree data)))

(deftest ensure-up-starts-with-the-declared-limit-or-the-default
  (let [seen (atom [])]
    (with-redefs [shared/with-lock (fn [_ f] (f))
                  fs/exists?        (fn [_] true)
                  shared/start-existing! (fn [_ limit] (swap! seen conj limit) 7450)]
      (shared/ensure-up! "brian" {:max-connections 1000})
      (shared/ensure-up! "brian" nil)
      (is (= [1000 200] @seen)))))

(deftest ensure-ready-hands-the-declared-limit-to-ensure-up
  (let [got (atom nil)]
    (with-redefs [shared/ensure-up! (fn [_ opts] (reset! got opts) {:port 7450})]
      (shared/ensure-ready! "brian" {:max-connections 1000})
      (is (= 1000 (:max-connections @got))))))

(deftest the-operators-up-hands-the-declared-limit
  (let [got (atom nil)]
    (with-redefs [shared/ensure-ready! (fn [_ opts] (reset! got opts) {:port 1})
                  engine/load-session-edn
                  (fn [_] {:services [(merge {:type :postgresql :db-name "brian"} brian-budget)]})
                  task/project-source-dir (fn [_] "/x/Code/brian")]
      (with-out-str (task/up ":project" "brian"))
      (is (= 1000 (:max-connections @got))))))

(deftest a-non-integer-limit-is-refused-naming-the-field
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":max-connections"
                        (shared/ensure-up! "brian" {:max-connections "1000"}))))

;; ---------------------------------------------------------------------------
;; declared-or-refused
;; ---------------------------------------------------------------------------

(deftest validated-budget-parses-the-declaration
  (is (= brian-budget (shared/validated-budget "brian" (assoc brian-budget :mode :shared))))
  (is (= {:max-connections 200 :reserved-connections 10 :session-connections 45}
         (shared/validated-budget "brian" {:session-connections 45}))
      "nido's defaults stand in for an undeclared limit and reserve"))

(deftest a-definition-without-a-positive-session-share-is-refused-naming-field-and-project
  (doseq [def [{} {:session-connections 0} {:session-connections -5}
               {:session-connections "45"} {:session-connections 4.5}]]
    (let [e (try (shared/validated-budget "brian" def) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is e (str "refused: " (pr-str def)))
      (is (str/includes? (ex-message e) ":session-connections"))
      (is (str/includes? (ex-message e) "brian"))
      (is (= :session-connections (:field (ex-data e))))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":reserved-connections"
                        (shared/validated-budget "brian" {:session-connections 45
                                                          :reserved-connections -1}))))

(deftest a-shared-start-without-a-declared-share-never-touches-the-cluster
  (let [touched (atom [])]
    (with-redefs [shared/admit!        (fn [& _] (swap! touched conj :admit) {:admitted {}})
                  shared/ensure-ready! (fn [& _] (swap! touched conj :ready) {:port 1})
                  shared/ensure-up!    (fn [& _] (swap! touched conj :up) {:port 1})
                  engine/source-project-root (fn [_] "/x")]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #":session-connections"
           (service/start-service! {:type :postgresql :name :pg :mode :shared :db-name "brian"}
                                   {:session {:project-name "brian" :instance-id "brian--a"
                                              :project-dir "/tmp/wt"}}
                                   {})))
      (is (empty? @touched)))))

;; ---------------------------------------------------------------------------
;; admitted-before-jvm
;; ---------------------------------------------------------------------------

(def ^:private jvm-started (atom 0))

(defmethod service/start-service! ::jvm [_ _ _]
  (swap! jvm-started inc)
  {:state {} :context {}})

(deftest a-refused-admission-throws-before-the-jvm-is-launched
  (reset! jvm-started 0)
  (let [order (atom [])]
    (with-redefs [shared/admit!        (fn [& _] (swap! order conj :admit)
                                         {:refused {:project-name "brian" :max-connections 100
                                                    :reserved-connections 20 :runtimes 2
                                                    :claimed 90 :session-connections 45}})
                  shared/ensure-ready! (fn [& _] (swap! order conj :ready) {:port 1})
                  engine/source-project-root (fn [_] "/x")]
      (let [e (try (#'engine/start-each!
                    {:session {:project-name "brian" :instance-id "brian--a" :project-dir "/tmp/wt"}}
                    [(merge {:type :postgresql :name :pg :mode :shared :db-name "brian"} brian-budget)
                     {:type ::jvm :name :repl}]
                    {})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :connection-budget (:refused (ex-data e))))
        (is (= [:admit] @order) "ensure-ready! is not called on a refusal")
        (is (zero? @jvm-started) "no later service — the JVM — was started")))))

(deftest admission-precedes-ensure-ready
  (let [order (atom [])]
    (with-redefs [shared/admit!        (fn [_ _ b] (swap! order conj [:admit b]) {:admitted {}})
                  shared/ensure-ready! (fn [& _] (swap! order conj :ready) {:port 1})
                  engine/source-project-root (fn [_] "/x")]
      (service/start-service! (merge {:type :postgresql :name :pg :mode :shared :db-name "brian"}
                                     brian-budget)
                              {:session {:project-name "brian" :instance-id "brian--a"
                                         :project-dir "/tmp/wt"}}
                              {})
      (is (= [[:admit brian-budget] :ready] @order)))))

(deftest admission-judges-against-the-started-with-limit
  (with-cluster {:limit 100}
    (fn [_]
      (is (:admitted (shared/admit! "brian" "brian--a"
                                    {:max-connections 1000 :reserved-connections 20
                                     :session-connections 80}))
          "80 + 20 = 100 fits the 100 the cluster runs with")
      (let [{:keys [refused]} (shared/admit! "brian" "brian--b"
                                             {:max-connections 1000 :reserved-connections 20
                                              :session-connections 1})]
        (is refused "a declared 1000 the cluster has not been restarted onto admits nothing")
        (is (= 100 (:max-connections refused)))))))

;; ---------------------------------------------------------------------------
;; claims-count-every-sibling
;; ---------------------------------------------------------------------------

(deftest claims-count-claims-starting-ones-and-unclaimed-up-runtimes-once
  (with-cluster {:limit 200
                 :registry {"/wt/a"  (up-entry "brian--a")
                            ;; one runtime, two sessions on it: counted once
                            "/wt/b1" (up-entry "brian--b")
                            "/wt/b2" (up-entry "brian--b")
                            "/wt/d"  (up-entry "brian--d" :down? true)
                            "/wt/i"  (up-entry "brian--iso" :pg-port 6000)
                            "/wt/o"  (up-entry "other--x" :project-name "other")}}
    (fn [dir]
      ;; brian--s is starting: it holds a claim from its admission, not yet registered up.
      (spit (str (fs/path dir "claims.edn"))
            (pr-str {"brian--s" {:connections 30}}))
      (let [{:keys [admitted]} (shared/admit! "brian" "brian--new"
                                              {:max-connections 200 :reserved-connections 20
                                               :session-connections 45})]
        (is admitted)
        (is (= 3 (:runtimes admitted)) "the starting claim, a, and b once")
        (is (= (+ 30 45 45) (:claimed admitted))
            "unclaimed up runtimes count at the admitting definition's share")
        (is (= {"brian--s" 30 "brian--new" 45}
               (update-vals (claims dir) :connections)))))))

(deftest a-runtime-re-admitted-replaces-its-own-claim
  (with-cluster {:limit 100 :registry {"/wt/a" (up-entry "brian--a")}}
    (fn [dir]
      (spit (str (fs/path dir "claims.edn")) (pr-str {"brian--a" {:connections 45}}))
      (let [{:keys [admitted]} (shared/admit! "brian" "brian--a"
                                              {:reserved-connections 20 :session-connections 45})]
        (is (= 0 (:runtimes admitted)) "itself is not a sibling")
        (is (= {"brian--a" 45} (update-vals (claims dir) :connections)))))))

(deftest concurrent-starts-are-admitted-one-at-a-time
  (with-cluster {:limit 100}
    (fn [_]
      (let [go      (promise)
            results (doall (for [id ["brian--a" "brian--b" "brian--c"]]
                             (future @go
                                     (shared/admit! "brian" id {:reserved-connections 10
                                                                :session-connections 50}))))]
        (deliver go true)
        (is (= 1 (count (filter :admitted (map deref results))))
            "headroom for one admits exactly one")))))

(deftest stops-release-the-claim
  (with-cluster {:limit 1000}
    (fn [dir]
      (let [budget {:reserved-connections 20 :session-connections 45}]
        (shared/admit! "brian" "brian--a" budget)
        (shared/admit! "brian" "brian--b" budget)
        (shared/admit! "brian" "brian--c" budget)
        (testing "the :postgresql stop of a shared session"
          (service/stop-service! {:type :postgresql :mode :shared}
                                 {:mode :shared :project-name "brian" :instance-id "brian--a"})
          (is (= #{"brian--b" "brian--c"} (set (keys (claims dir))))))
        (testing "stop-recorded!, for a start that saved no state"
          (with-redefs [state/shared-dir (constantly (str (fs/parent dir)))]
            (service/stop-recorded! {:type :postgresql :mode :shared} "brian--b"))
          (is (= #{"brian--c"} (set (keys (claims dir))))))))))

(deftest destroying-the-cluster-keeps-its-runtimes-claims
  ;; A runtime's claim is held until nido stops the runtime: destroying the cluster under it
  ;; drops the cluster, not what its runtimes were admitted with.
  (with-cluster {:limit 1000}
    (fn [dir]
      (shared/admit! "brian" "brian--a" {:reserved-connections 20 :session-connections 45})
      (with-out-str (shared/destroy! "brian"))
      (is (not (fs/exists? (fs/path dir "pg-data"))) "the cluster's data goes")
      (is (= {"brian--a" 45} (update-vals (claims dir) :connections))))))

(deftest status-shows-the-claims-a-destroyed-cluster-keeps
  ;; Kept claims still count against admission, so status names their runtimes when the
  ;; cluster is gone too.
  (with-cluster {:limit 1000 :registry {"/wt/x" (up-entry "brian--x" :pg-port nil)}}
    (fn [dir]
      (shared/admit! "brian" "brian--a" {:reserved-connections 20 :session-connections 45})
      (with-out-str (shared/destroy! "brian"))
      (let [out (with-out-str (shared/status "brian" brian-budget))]
        (is (re-find #"brian--a  45  since" out))
        (is (not (re-find #"brian--x" out)) "no runtime is up on a cluster with no port")))))

(deftest a-start-that-throws-after-admission-releases-its-claim
  (with-cluster {:limit 1000}
    (fn [dir]
      (with-redefs [shared/ensure-ready! (fn [& _] (throw (ex-info "advance failed" {})))
                    engine/source-project-root (fn [_] "/x")]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"advance failed"
             (service/start-service! (merge {:type :postgresql :name :pg :mode :shared
                                             :db-name "brian"} brian-budget)
                                     {:session {:project-name "brian" :instance-id "brian--a"
                                                :project-dir "/tmp/wt"}}
                                     {}))))
      (is (empty? (claims dir))))))

(deftest a-start-whose-source-resolution-throws-holds-no-claim
  (with-cluster {:limit 1000}
    (fn [dir]
      (with-redefs [shared/ensure-ready! (fn [& _] {:port 7450})
                    engine/source-project-root (fn [_] (throw (ex-info "unreadable .jj/repo" {})))]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"unreadable"
             (service/start-service! (merge {:type :postgresql :name :pg :mode :shared
                                             :db-name "brian"} brian-budget)
                                     {:session {:project-name "brian" :instance-id "brian--a"
                                                :project-dir "/tmp/wt"}}
                                     {}))))
      (is (empty? (claims dir))))))

;; ---------------------------------------------------------------------------
;; refusal-names-budget
;; ---------------------------------------------------------------------------

(def ^:private figures
  {:project-name "brian" :port 7450 :max-connections 1000 :reserved-connections 20
   :runtimes 21 :claimed 945 :session-connections 45})

(deftest a-refusal-names-every-figure-and-the-ways-out
  (let [m (ex-message (shared/refusal figures))]
    (doseq [s ["brian" "max_connections 1000" "reserve 20" "runtimes on it 21" "claiming 945"
               ":session-connections 45" "Stop a session" "raise :max-connections"
               "restart the shared cluster"]]
      (is (str/includes? m s) s))))

(deftest refusals-are-kept-and-share-one-cause-per-project
  (let [dir (str (fs/create-temp-dir))]
    (try
      (with-redefs [failure/failures-dir (constantly dir)
                    state/state-dir      (constantly dir)]
        (let [keep! (fn [session fs*]
                      (failure/record! {:verb :up :session session :project "brian"
                                        :instance-id (str "brian--" session)}
                                       (shared/refusal fs*)))
              a (keep! "feat-a" figures)
              b (keep! "feat-b" (assoc figures :runtimes 3 :claimed 135 :max-connections 100))
              c (failure/record! {:verb :up :session "x" :project "fukan" :instance-id "fukan--x"}
                                 (shared/refusal (assoc figures :project-name "fukan")))]
          (is (= 3 (count (failure/ids))) "kept under the failures dir like any failed start")
          (is (= (failure/cause a) (failure/cause b)))
          (is (= (failure/cause a) (failure/cause (keep! "budget" figures)))
              "a session whose name is a word of the message is the same cause")
          (is (not= (failure/cause a) (failure/cause c)))
          (is (str/includes? (get-in a [:error 0 :message]) "connection budget")
              "the cause reads as the budget, not a Postgres client error")))
      (finally (fs/delete-tree dir)))))

;; ---------------------------------------------------------------------------
;; demand-declared-not-derived
;; ---------------------------------------------------------------------------

(deftest the-budget-reads-no-pool-sizes
  (doseq [f ["src/nido/session/shared_pg.clj" "src/nido/session/services/postgresql.clj"]]
    (let [src (slurp f)]
      (doseq [k ["maximumPoolSize" "minimumIdle" "pool-max-size"]]
        (is (not (str/includes? src k)) (str f " mentions " k))))))

;; ---------------------------------------------------------------------------
;; brian-budget-admits-eleven
;; ---------------------------------------------------------------------------

;; The claim is about the person's own declaration, which lives in their nido home rather than in
;; this repo, and the suite runs under a hermetic NIDO_HOME — so the file is read, read-only, from
;; the real home. A host with no brian project has nothing to check.

(def ^:private brian-session-edn
  (str (fs/path (System/getProperty "user.home") ".nido" "projects" "brian" "session.edn")))

(defn- brian-shared-pg []
  (->> (:services (clojure.edn/read-string (slurp brian-session-edn)))
       (filter #(and (= :postgresql (:type %)) (= :shared (pg/resolve-pg-mode %))))
       first))

(defn- admits [b n]
  (with-cluster {:limit (:max-connections b)}
    (fn [_] (mapv #(shared/admit! "brian" (str "brian--s" %) b) (range n)))))

(deftest brian-budget-admits-eleven
  (if-not (fs/exists? brian-session-edn)
    (println "brian-budget-admits-eleven: no" brian-session-edn "on this host — skipped")
    (let [svc (brian-shared-pg)]
      (is svc "brian's session.edn declares a shared :postgresql service")
      (when svc
        (let [{:keys [max-connections reserved-connections session-connections] :as b}
              (shared/validated-budget "brian" svc)
              answers (admits b 22)]
          (is (= brian-budget b) "declared as the design states")
          (is (<= (+ (* 11 session-connections) reserved-connections) max-connections))
          (is (every? :admitted (take 11 answers)) "at least 11 at once")
          (is (= 21 (count (filter :admitted answers))) "and 21 in all")
          (is (:refused (last answers)) "the 22nd is refused"))))))

(deftest a-release-that-fails-is-reported-and-never-throws
  (with-cluster {:limit 1000}
    (fn [dir]
      (shared/admit! "brian" "brian--a" {:reserved-connections 20 :session-connections 45})
      (let [err (java.io.StringWriter.)]
        (with-redefs [nido.platform.io/write-edn! (fn [& _] (throw (java.io.IOException. "disk full")))]
          (binding [*err* err]
            (is (nil? (shared/release-claim! "brian" "brian--a")) "the stop goes on")))
        (is (str/includes? (str err) "claims.edn") "the warning names the file to fix by hand")
        (is (str/includes? (str err) "brian--a"))
        (is (contains? (claims dir) "brian--a") "the claim stays counted — the accepted gap")))))
