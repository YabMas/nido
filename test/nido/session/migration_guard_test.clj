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
