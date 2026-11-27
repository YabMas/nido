(ns nido.session.migration-guard-test
  "A runtime on a project's shared Postgres cluster never serves a migration main@origin does not
   hold: the rule (`shared-pg/offending-migrations`), its refusal, the checkout refresh that asks
   it before writing, and the engine verbs it refuses."
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
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
   [nido.session.services.postgresql :as pg]
   [nido.session.shared-pg :as shared-pg]
   [nido.session.state :as state]))

(def ^:private mig "resources/db/migrations")

(defn- sh [dir & args]
  (apply shell {:dir (str dir) :out :string :err :string} args))

(defn- put! [dir rel content]
  (let [f (fs/path dir rel)]
    (fs/create-dirs (fs/parent f))
    (spit (str f) content)))

(defn- with-repo
  "Run `f` with a colocated jj repo whose main@origin holds V1 and V2 migrations, two workspaces
   `a` and `b` of it, and the nido home in the same temp dir; `f` gets the source and the two
   workspace paths."
  [f]
  (let [tmp (fs/create-temp-dir)
        src (fs/path tmp "src")
        a   (str (fs/path tmp "a"))
        b   (str (fs/path tmp "b"))]
    (try
      (fs/create-dirs src)
      (sh tmp "git" "init" "-q" "--bare" "origin.git")
      (sh src "jj" "git" "init" "--colocate")
      (put! src (str mig "/V1__one.sql") "create table one ();\n")
      (put! src (str mig "/V2__two.sql") "create table two ();\n")
      (put! src "f.txt" "base\n")
      (sh src "jj" "commit" "-m" "base")
      (sh src "jj" "git" "remote" "add" "origin" (str (fs/path tmp "origin.git")))
      (sh src "jj" "bookmark" "set" "main" "-r" "@-")
      (sh src "jj" "git" "push" "-b" "main")
      (sh src "jj" "workspace" "add" "--name" "a" a)
      (sh src "jj" "workspace" "add" "--name" "b" b)
      (with-redefs [core/nido-home (constantly (str tmp))]
        (f (str src) a b))
      (finally (fs/delete-tree tmp)))))

;; ---------------------------------------------------------------------------
;; The rule: what a revision adds or changes relative to main@origin
;; ---------------------------------------------------------------------------

(defn- offending
  "`offending-migrations` of workspace `a`'s working copy, snapshotted first as a refresh does."
  [a]
  (sh a "jj" "status")
  (shared-pg/offending-migrations a "@"))

(deftest a-migration-offends-when-it-is-added-or-changed-and-never-when-identical-or-deleted
  (with-repo
    (fn [_ a _]
      (testing "identical to main"
        (put! a "f.txt" "edited\n")
        (is (= [] (offending a))))
      (testing "only deleted"
        (fs/delete (fs/path a mig "V1__one.sql"))
        (is (= [] (offending a))))
      (testing "added, changed and renamed"
        (put! a (str mig "/V3__three.sql") "create table three ();\n")
        (put! a (str mig "/V2__two.sql") "create table two (id int);\n")
        (fs/move (fs/path a mig "V2__two.sql") (fs/path a mig "V9__two.sql"))
        (put! a (str mig "/V2__two.sql") "create table two (x int);\n")
        (is (= [(str mig "/V2__two.sql") (str mig "/V3__three.sql") (str mig "/V9__two.sql")]
               (offending a)))))))

(deftest the-refusal-says-how-to-isolate-and-the-other-ways-out
  (let [e   (shared-pg/migration-refusal {:project "brian" :session "feat" :sessions ["feat" "qa"]
                                          :paths [(str mig "/V3__three.sql")]})
        msg (ex-message e)]
    (is (= :migration-on-shared-db (:refused (ex-data e))))
    (doseq [part [(str mig "/V3__three.sql") "feat, qa"
                  "bb nido:session:isolate :project brian feat" "nido session:isolate"
                  "private copy-on-write clone of brian's template database"
                  "the shared cluster and every other session are untouched"
                  "refused while another session is on the same runtime"
                  "nido:session:share" "dropping the private copy"
                  "take the migration out of the working copy" "land it on main first"
                  "keeps the checkout it had"]]
      (is (str/includes? msg part) part))
    (is (= (select-keys (ex-data e) [:refused :reason])
           (select-keys (ex-data (shared-pg/migration-refusal
                                  {:project "brian" :session "other" :sessions ["other"]
                                   :paths ["x"]}))
                        [:refused :reason]))
        "two sessions' refusals are one failure cause")))

(deftest a-migration-whose-executable-bit-alone-changed-does-not-offend
  (with-repo
    (fn [_ a _]
      (sh a "chmod" "+x" (str (fs/path a mig "V1__one.sql")))
      (sh a "jj" "status")
      (is (seq (:out (sh a "jj" "--ignore-working-copy" "diff" "--from" "main@origin" "--to" "@"
                         "--name-only")))
          "jj reads the bit as a change")
      (is (= [] (shared-pg/offending-migrations a "@"))))))

(deftest a-pinned-main-admits-what-it-admitted-after-main-moves
  ;; A guard admits before a stop and writes after it; a fetch moving main between the two must
  ;; not turn the admitted tree into a refusal once services are down.
  (with-repo
    (fn [src a _]
      (sh a "jj" "status")
      (let [pin (shared-pg/main-commit a)]
        (is (= [] (shared-pg/offending-migrations a "@" pin)))
        (put! src (str mig "/V2__two.sql") "create table two (id int);\n")
        (sh src "jj" "commit" "-m" "main changes V2")
        (sh src "jj" "bookmark" "set" "main" "-r" "@-")
        (sh src "jj" "git" "push" "-b" "main")
        (is (not= pin (shared-pg/main-commit a)) "main@origin moved")
        (is (= [(str mig "/V2__two.sql")] (shared-pg/offending-migrations a "@"))
            "against main as it is now, the old V2 is a change")
        (is (= [] (shared-pg/offending-migrations a "@" pin))
            "against the pinned main, it is what was admitted")))))

(deftest an-unpinned-comparison-reads-the-one-main-it-resolved
  ;; With no pin given, main is resolved to a commit once and every read of the comparison uses
  ;; it: a fetch landing after that resolution cannot mix two mains into one answer.
  (with-repo
    (fn [src a _]
      (sh a "jj" "status")
      (let [pin (shared-pg/main-commit a)]
        (put! src (str mig "/V2__two.sql") "create table two (id int);\n")
        (sh src "jj" "commit" "-m" "main changes V2")
        (sh src "jj" "bookmark" "set" "main" "-r" "@-")
        (sh src "jj" "git" "push" "-b" "main")
        (with-redefs [shared-pg/main-commit (constantly pin)]
          (is (= [] (shared-pg/offending-migrations a "@"))
              "every read saw the main resolved before the fetch"))))))

;; ---------------------------------------------------------------------------
;; The checkout refresh asks before it writes
;; ---------------------------------------------------------------------------

(deftest a-refused-admission-leaves-the-checkout-as-it-was
  (with-repo
    (fn [_ a _]
      (let [dir (checkout/refresh! "p--ws" {a "a"})]
        (put! a "f.txt" "edited\n")
        (let [asked (atom nil)
              e     (try (checkout/refresh! "p--ws" {a "a"} (fn [_])
                                            (fn [repo rev]
                                              (reset! asked (sh repo "jj" "--ignore-working-copy"
                                                                "file" "show" "-r" rev "f.txt"))
                                              (throw (ex-info "no" {:refused :x}))))
                         nil
                         (catch clojure.lang.ExceptionInfo e e))]
          (is (= "edited\n" (:out @asked)) "admission is asked about the merged tree")
          (is (= {:refused :x} (ex-data e)) "its error goes on to the caller")
          (is (= "base\n" (slurp (str (fs/path dir "f.txt")))) "nothing was written")
          (is (str/blank? (:out (sh a "jj" "log" "--no-graph" "-r"
                                    "description(substring:\"nido runtime\") ~ ::nido-runtime-p--ws@"
                                    "-T" "change_id")))
              "the merge it was asked about is abandoned"))))))

(deftest what-is-admitted-is-what-is-written-though-a-member-snapshots-in-between
  (with-repo
    (fn [_ a _]
      (let [dir (checkout/refresh! "p--ws" {a "a"})]
        (put! a "f.txt" "edited\n")
        ;; Admission passes, then the member snapshots a migration before the copy is written.
        (checkout/refresh! "p--ws" {a "a"} (fn [_])
                           (fn [_ _]
                             (put! a (str mig "/V3__three.sql") "create table three ();\n")
                             (sh a "jj" "status")))
        (is (= "edited\n" (slurp (str (fs/path dir "f.txt")))) "the admitted tree is written")
        (is (not (fs/exists? (fs/path dir mig "V3__three.sql")))
            "a migration snapshotted after admission is not")))))

;; ---------------------------------------------------------------------------
;; The engine's verbs refuse a shared runtime, and only a shared one
;; ---------------------------------------------------------------------------

(defn- with-engine
  "Run `f` with `with-repo`'s workspaces under project `p`, whose session.edn declares Postgres in
   `mode` and a REPL. Services are stubbed: a started one is recorded in `started`, the Postgres
   state records its resolved mode as the real one does."
  [mode f]
  (with-repo
    (fn [src a b]
      (let [started (atom [])
            stopped (atom [])]
        (with-redefs [engine/resolve-project-name (constantly "p")
                      engine/load-session-edn     (constantly {:services [{:type :postgresql :name :pg :mode mode}
                                                                          {:type :process :name :repl}]})
                      proc/process-alive?         (constantly true)
                      launcher/write-artifacts!   (fn [& _] nil)
                      launcher/remove-artifacts!  (fn [& _] nil)
                      eval-svc/start-app!         (fn [& _] true)
                      service/start-service!      (fn [d _ _]
                                                    (swap! started conj (:name d))
                                                    {:state   (if (= :postgresql (:type d))
                                                                {:mode (pg/resolve-pg-mode d)}
                                                                {:pid 1})
                                                     :context {}})
                      service/stop-service!       (fn [d _] (swap! stopped conj (:name d)) nil)
                      service/stop-recorded!      (fn [& _] nil)]
          (f {:src src :a a :b b :started started :stopped stopped}))))))

(defn- up! [wt session]
  (engine/start-session! wt {:runtime-key "ws" :session-name session}))

(defn- refusal [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e
         (when (= :migration-on-shared-db (:refused (ex-data e))) e))))

(def ^:private v3 (str mig "/V3__three.sql"))
(def ^:private v2 (str mig "/V2__two.sql"))

(deftest a-shared-session-holding-a-branch-migration-is-refused-by-up-before-anything-starts
  (with-engine :shared
    (fn [{:keys [a started]}]
      (put! a v3 "create table three ();\n")
      (let [e (refusal #(up! a "a"))]
        (is e "up is refused")
        (is (= [v3] (:paths (ex-data e))))
        (is (str/includes? (ex-message e) "bb nido:session:isolate :project p a")))
      (is (= [] @started) "neither Postgres nor the JVM started")
      (is (nil? (state/read-session "p--ws")))
      (is (not (fs/exists? (fs/path (checkout/checkout-dir "p--ws") v3)))
          "the checkout does not hold the migration"))))

(deftest a-shared-session-holding-a-changed-migration-is-refused-by-up-before-anything-starts
  (with-engine :shared
    (fn [{:keys [a started]}]
      (put! a v2 "create table two (id int);\n")
      (let [e (refusal #(up! a "a"))]
        (is e "up is refused")
        (is (= [v2] (:paths (ex-data e))))
        (is (str/includes? (ex-message e) "bb nido:session:isolate :project p a")))
      (is (= [] @started) "neither Postgres nor the JVM started")
      (is (nil? (state/read-session "p--ws")))
      (let [f (fs/path (checkout/checkout-dir "p--ws") v2)]
        (is (not (and (fs/exists? f) (= "create table two (id int);\n" (slurp (str f)))))
            "the checkout does not hold the changed migration")))))

(deftest a-shared-session-is-refused-by-sync-and-its-checkout-keeps-what-it-had
  (with-engine :shared
    (fn [{:keys [a]}]
      (up! a "a")
      (let [dir (checkout/checkout-dir "p--ws")]
        (testing "an added migration"
          (put! a v3 "create table three ();\n")
          (is (refusal #(engine/sync-runtime! a)))
          (is (not (fs/exists? (fs/path dir v3)))))
        (testing "a changed one keeps the bytes it had"
          (fs/delete (fs/path a v3))
          (put! a v2 "create table two (id int);\n")
          (is (refusal #(engine/sync-runtime! a)))
          (is (= "create table two ();\n" (slurp (str (fs/path dir v2))))))
        (testing "an only-deleted or identical tree syncs"
          (fs/delete (fs/path a v2))
          (put! a "f.txt" "edited\n")
          (is (= dir (engine/sync-runtime! a)))
          (is (= "edited\n" (slurp (str (fs/path dir "f.txt")))))
          (is (not (fs/exists? (fs/path dir v2)))))))))

(deftest a-shared-join-is-refused-before-the-joiner-is-recorded-or-anything-stops
  (with-engine :shared
    (fn [{:keys [a b stopped]}]
      (up! a "a")
      (put! b v3 "create table three ();\n")
      (let [e (refusal #(up! b "b"))]
        (is e "the join is refused")
        (is (str/includes? (ex-message e) "bb nido:session:isolate :project p b")))
      (is (= {a "a"} (:members (state/read-session "p--ws"))) "b is not on the runtime")
      (is (= [] @stopped) "nothing was stopped")
      (is (not (fs/exists? (fs/path (checkout/checkout-dir "p--ws") v3))))
      (testing "a restarting join is refused the same way"
        (with-redefs [engine/load-session-edn
                      (constantly {:services [{:type :postgresql :name :pg :mode :shared}
                                              {:type :process :name :repl}
                                              {:type :process :name :extra}]})]
          (is (refusal #(up! b "b")))
          (is (= [] @stopped))
          (is (= {a "a"} (:members (state/read-session "p--ws")))))))))

(deftest a-member-leaving-a-shared-runtime-only-warns
  (with-engine :shared
    (fn [{:keys [a b]}]
      (up! a "a")
      (up! b "b")
      ;; b's working copy now holds a migration a's departure would merge into the checkout.
      (put! b v3 "create table three ();\n")
      (let [out (with-out-str (engine/stop-session! a))]
        (is (str/includes? out "warning: Refused: b on p's shared Postgres cluster") out))
      (is (= {b "b"} (:members (state/read-session "p--ws"))) "a has left")
      (is (not (fs/exists? (fs/path (checkout/checkout-dir "p--ws") v3)))))))

(deftest an-isolated-or-clone-session-is-never-compared
  (doseq [mode [:isolated :clone]]
    (testing (name mode)
      (with-engine mode
        (fn [{:keys [a b]}]
          (put! a v3 "create table three ();\n")
          (with-redefs [shared-pg/offending-migrations (fn [& _] (throw (ex-info "compared" {})))]
            (up! a "a")
            (put! b v2 "create table two (id int);\n")
            (up! b "b")
            (is (= (checkout/checkout-dir "p--ws") (engine/sync-runtime! a))))
          (let [dir (checkout/checkout-dir "p--ws")]
            (is (fs/exists? (fs/path dir v3)) "its checkout holds the migration")
            (is (= "create table two (id int);\n" (slurp (str (fs/path dir v2)))))))))))

(deftest an-isolate-override-takes-a-shared-session-out-of-the-guard
  (with-engine :shared
    (fn [{:keys [a]}]
      (fs/create-dirs (state/instance-state-dir "p--ws"))
      (spit (state/pg-mode-override-file "p--ws") (pr-str {:mode :isolated}))
      (put! a v3 "create table three ();\n")
      (up! a "a")
      (is (fs/exists? (fs/path (checkout/checkout-dir "p--ws") v3))))))

(deftest a-restarting-join-onto-an-isolated-runtime-writes-no-migration-the-shared-one-still-serves
  (with-engine :shared
    (fn [{:keys [a b]}]
      (up! a "a")
      (spit (state/pg-mode-override-file "p--ws") (pr-str {:mode :isolated}))
      (put! b v3 "create table three ();\n")
      (let [dir        (checkout/checkout-dir "p--ws")
            while-held (atom [])]
        (with-redefs [engine/load-session-edn
                      (constantly {:services [{:type :postgresql :name :pg :mode :shared}
                                              {:type :process :name :repl}
                                              {:type :process :name :extra}]})
                      service/stop-service!
                      (fn [d _] (swap! while-held conj (fs/exists? (fs/path dir v3))) nil)]
          (up! b "b"))
        (is (seq @while-held) "the shared runtime was stopped")
        (is (not-any? true? @while-held) "its checkout never held the migration while it ran")
        (is (fs/exists? (fs/path dir v3)) "the isolated runtime it restarted as serves it")))))

(deftest a-restarting-shared-join-serves-what-it-admitted-before-the-stop
  (with-engine :shared
    (fn [{:keys [a b]}]
      (up! a "a")
      (put! b "f.txt" "edited\n")
      (let [dir (checkout/checkout-dir "p--ws")]
        (with-redefs [engine/load-session-edn
                      (constantly {:services [{:type :postgresql :name :pg :mode :shared}
                                              {:type :process :name :repl}
                                              {:type :process :name :extra}]})
                      ;; A member adds a migration while the old services are stopping.
                      service/stop-service!
                      (fn [_ _] (put! b v3 "create table three ();\n") nil)]
          (is (nil? (refusal #(up! b "b"))) "nothing is refused once a service has stopped"))
        (is (= {a "a" b "b"} (:members (state/read-session "p--ws"))) "the runtime came back up")
        (is (= "edited\n" (slurp (str (fs/path dir "f.txt")))) "it serves the admitted tree")
        (is (not (fs/exists? (fs/path dir v3))) "and not the migration added after it")))))

(deftest a-move-onto-a-shared-runtime-is-refused-before-the-session-leaves-its-own
  (with-engine :shared
    (fn [{:keys [a b stopped]}]
      (fs/create-dirs (state/instance-state-dir "p--solo"))
      (spit (state/pg-mode-override-file "p--solo") (pr-str {:mode :isolated}))
      (put! a v3 "create table three ();\n")
      (engine/start-session! a {:runtime-key "solo" :session-name "a"})
      (up! b "b")
      (let [e (refusal #(engine/start-session! a {:runtime-key "ws" :session-name "a"}))]
        (is e "the move is refused")
        (is (= [v3] (:paths (ex-data e)))))
      (is (= [] @stopped) "nothing a ran on was stopped")
      (is (= {a "a"} (:members (state/read-session "p--solo"))) "a is still on its own runtime")
      (is (= {b "b"} (:members (state/read-session "p--ws"))))
      (is (not (fs/exists? (fs/path (checkout/checkout-dir "p--ws") v3)))))))

(deftest a-move-onto-a-shared-runtime-writes-what-it-admitted-before-the-source-stopped
  (with-engine :shared
    (fn [{:keys [a b]}]
      (fs/create-dirs (state/instance-state-dir "p--solo"))
      (spit (state/pg-mode-override-file "p--solo") (pr-str {:mode :isolated}))
      (engine/start-session! a {:runtime-key "solo" :session-name "a"})
      (up! b "b")
      (put! a "f.txt" "edited\n")
      (with-redefs [service/stop-service!
                    ;; A migration lands in a's working copy while its own runtime stops.
                    (fn [_ _] (put! a v3 "create table three ();\n") nil)]
        (is (nil? (refusal #(engine/start-session! a {:runtime-key "ws" :session-name "a"})))
            "nothing is refused once the source has stopped"))
      (let [dir (checkout/checkout-dir "p--ws")]
        (is (= {a "a" b "b"} (:members (state/read-session "p--ws"))) "a moved")
        (is (= "edited\n" (slurp (str (fs/path dir "f.txt")))) "the admitted tree is served")
        (is (not (fs/exists? (fs/path dir v3))) "and not the migration added after it")))))

(deftest a-share-refused-for-a-migration-keeps-the-session-isolated-with-its-data
  (with-engine :shared
    (fn [{:keys [a stopped]}]
      (fs/create-dirs (state/instance-state-dir "p--ws"))
      (spit (state/pg-mode-override-file "p--ws") (pr-str {:mode :isolated}))
      (put! a v3 "create table three ();\n")
      (up! a "a")
      (let [pg-data (state/pg-data-dir "p--ws")]
        (fs/create-dirs pg-data)
        (spit (str (fs/path pg-data "PG_VERSION")) "16\n")
        (with-redefs [lifecycle/resolve-project (constantly ["p" {}])
                      lifecycle/with-context
                      (fn [_ _] {:project-name "p" :instance-id "p--ws" :on "p--ws"
                                 :runtime-key "ws" :wt-path a})]
          (is (refusal #(lifecycle/share! "a" {:project "p" :runtime-key "ws"}))
              "the share is refused"))
        (is (= [] @stopped) "nothing was stopped")
        (is (fs/exists? (fs/path pg-data "PG_VERSION")) "its private database is kept")
        (is (= {:mode :isolated} (state/read-pg-mode-override "p--ws")) "it stays isolated")
        (is (= {a "a"} (:members (state/read-session "p--ws"))))))))
