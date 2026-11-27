(ns nido.session.destroy-ownership-test
  "A destroy removes only the session's own worktree. Run through the provision verb's :want
   destroyed — the path the core takes — against temp dirs laid out as a project's source checkout
   and its worktrees dir; nothing here touches a real path."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core]
   [nido.session.engine :as engine]
   [nido.session.lifecycle :as lifecycle]
   [tasks.nido-session-provision :as provision]))

(defn- jj! [dir & args]
  (apply p/shell {:dir (str dir) :out :string :err :string} "jj" "--quiet" args))

(defn- with-layout
  "Call `(f {:root :proj :wts :touched})` with a temp project `proj` — a colocated jj checkout,
   `.jj/repo` and `.git` both directories — and its default worktrees dir beside it, nido's home
   and engine pointed at the temp dir, and `touched` collecting every stop the destroy made."
  [f]
  (let [root    (fs/create-temp-dir {:prefix "nido-destroy-"})
        proj    (fs/path root "proj")
        wts     (fs/path root "proj-worktrees")
        touched (atom [])]
    (try
      (fs/create-dirs proj)
      (fs/create-dirs wts)
      (jj! proj "git" "init" "--colocate")
      (spit (str (fs/path proj "precious.txt")) "unpushed work")
      (with-redefs [nido.platform.core/nido-home          (constantly (str (fs/path root "home")))
                    nido.platform.core/log-step           (constantly nil)
                    lifecycle/resolve-project             (constantly ["proj" {:directory (str proj)}])
                    engine/read-session-edn               (constantly nil)
                    engine/read-profile-for-session       (constantly nil)
                    engine/refuse-backing-worktree!       (fn [& _] nil)
                    engine/stop-session!                  (fn [& a] (swap! touched conj [:stop a]))]
        (f {:root root :proj proj :wts wts :touched touched}))
      (finally (fs/delete-tree root)))))

(defn- destroy-through-verb!
  "Run the provision verb's :want destroyed for `session`, with :path when given; its result map."
  [session path]
  (let [out (atom nil)]
    (with-redefs [provision/result! (fn [m code] (reset! out (assoc m ::exit code)))]
      (apply provision/provision (cond-> [":project" "proj" ":want" "destroyed" session]
                                   path (into [":path" (str path)]))))
    @out))

(defn- refused-and-kept? [{:keys [touched]} res dir]
  (and (= :not-owned (:refused res))
       (= 1 (::exit res))
       (empty? @touched)
       (fs/exists? dir)))

(deftest a-destroy-never-removes-the-source-checkout
  (with-layout
    (fn [{:keys [proj] :as l}]
      (testing ":path the project directory itself — the 2026-11-26 loss"
        (let [res (destroy-through-verb! "proj" proj)]
          (is (refused-and-kept? l res proj) (pr-str res))
          (is (= (str proj) (:path res)) "the refusal names the directory it kept")
          (is (fs/directory? (fs/path proj ".jj" "repo")))
          (is (= "unpushed work" (slurp (str (fs/path proj "precious.txt")))))))
      (testing "a session named after the project, its default path the checkout's sibling, is not fooled"
        (is (refused-and-kept? l (destroy-through-verb! "s" proj) proj))))))

(deftest a-destroy-never-removes-a-repository-or-anything-outside-the-worktrees-dir
  (with-layout
    (fn [{:keys [root wts] :as l}]
      (testing "a directory holding a .jj/repo directory, at the session's own worktree-path"
        (let [d (fs/path wts "s1")]
          (fs/create-dirs (fs/path d ".jj" "repo"))
          (is (refused-and-kept? l (destroy-through-verb! "s1" nil) d))))
      (testing "a directory holding a .git directory, at the session's own worktree-path"
        (let [d (fs/path wts "s2")]
          (fs/create-dirs (fs/path d ".git"))
          (is (refused-and-kept? l (destroy-through-verb! "s2" nil) d))))
      (testing "a path outside the worktrees dir"
        (let [d (fs/path root "elsewhere" "s3")]
          (fs/create-dirs d)
          (spit (str (fs/path d "f")) "x")
          (is (refused-and-kept? l (destroy-through-verb! "s3" d) d))
          (is (fs/exists? (fs/path d "f")))))
      (testing "another session's worktree under the same worktrees dir"
        (let [d (fs/path wts "other")]
          (fs/create-dirs d)
          (is (refused-and-kept? l (destroy-through-verb! "mine" d) d))))
      (testing "the worktrees dir itself"
        (is (refused-and-kept? l (destroy-through-verb! "s4" wts) wts))))))

(deftest a-destroy-still-removes-the-sessions-own-jj-workspace
  (with-layout
    (fn [{:keys [proj wts touched]}]
      (doseq [n ["plain" "feat/slashed"]]
        (testing (str "session " n)
          (let [wt (fs/path wts n)]
            (fs/create-dirs (fs/parent wt))
            (jj! proj "workspace" "add" "--name" n (str wt))
            (is (fs/regular-file? (fs/path wt ".jj" "repo")) "a workspace's .jj/repo is a pointer file")
            (reset! touched [])
            (let [res (destroy-through-verb! n wt)]
              (is (= {:destroyed true ::exit 0} res))
              (is (seq @touched) "the session was stopped")
              (is (not (fs/exists? wt)) "its worktree is gone")
              (is (fs/exists? (fs/path proj ".jj" "repo")) "the source checkout is not")))))
      (testing "a destroy with no :path takes the session's own worktree-path"
        (let [wt (fs/path wts "nopath")]
          (jj! proj "workspace" "add" "--name" "nopath" (str wt))
          (is (= {:destroyed true ::exit 0} (destroy-through-verb! "nopath" nil)))
          (is (not (fs/exists? wt))))))))

(deftest a-destroy-removes-a-symlink-worktree-and-never-its-target
  (with-layout
    (fn [{:keys [proj wts]}]
      (let [wt (fs/path wts "linked")]
        (fs/create-sym-link wt proj)
        (with-redefs [engine/read-profile-for-session (constantly {:worktree {:strategy :symlink}})]
          (is (= {:destroyed true ::exit 0} (destroy-through-verb! "linked" wt))))
        (is (not (fs/exists? wt {:nofollow-links true})) "the link is gone")
        (is (fs/directory? (fs/path proj ".jj" "repo")) "its target, a source checkout, is not")))))

(deftest recreating-a-poisoned-worktree-that-is-not-the-sessions-own-is-refused
  (with-layout
    (fn [{:keys [proj touched]}]
      (let [removed (atom [])]
        (with-redefs [lifecycle/jj-worktree-poisoned?               (constantly true)
                      engine/refuse-start-in-backing-worktree!     (fn [& _] nil)
                      lifecycle/remove-jj-workspace!               (fn [& a] (swap! removed conj a))]
          (is (= :not-owned
                 (try (lifecycle/up! "proj" {:project "proj" :path (str proj)}) nil
                      (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))))
        (is (empty? @removed) "nothing was forgotten or removed")
        (is (empty? @touched))
        (is (fs/directory? (fs/path proj ".jj" "repo")))))))

(deftest a-destroy-judges-the-path-it-removes-not-a-lexical-reading-of-it
  (with-layout
    (fn [{:keys [root wts] :as l}]
      (testing "a symlink before `..`: /wt/link/../s is beside link's target, not /wt/s"
        (let [outside (fs/path root "outside")
              victim  (fs/path outside "s")]
          (fs/create-dirs (fs/path outside "subdir"))
          (fs/create-dirs victim)
          (spit (str (fs/path victim "f")) "x")
          (fs/create-sym-link (fs/path wts "link") (fs/path outside "subdir"))
          (is (refused-and-kept? l (destroy-through-verb! "s" (str wts "/link/../s")) victim))
          (is (fs/exists? (fs/path victim "f"))))))))
