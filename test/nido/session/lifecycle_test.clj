(ns nido.session.lifecycle-test
  (:require
   [babashka.fs :as fs]
   [babashka.process]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.core]
   [nido.session.engine]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state]))

(deftest symlink-worktree-creates-symlink-to-target
  (let [tmp    (fs/create-temp-dir)
        target (str (fs/create-dirs (str (fs/path tmp "fake-checkout"))))
        wt     (str (fs/path tmp "wt-link"))]
    (try
      (lifecycle/create-symlink-worktree! wt target)
      (is (fs/sym-link? wt) "wt-link should be a symlink")
      (is (= (str (fs/real-path target))
             (str (fs/real-path wt)))
          "symlink should resolve to the target dir")
      (finally (fs/delete-tree tmp)))))

(deftest symlink-worktree-refuses-if-target-missing
  (let [tmp (fs/create-temp-dir)
        wt  (str (fs/path tmp "wt-link"))]
    (try
      (is (thrown? clojure.lang.ExceptionInfo
                   (lifecycle/create-symlink-worktree! wt "/no/such/path")))
      (finally (fs/delete-tree tmp)))))

(deftest symlink-worktree-replaces-stale-symlink
  (let [tmp     (fs/create-temp-dir)
        old-tg  (str (fs/create-dirs (str (fs/path tmp "old-target"))))
        new-tg  (str (fs/create-dirs (str (fs/path tmp "new-target"))))
        wt      (str (fs/path tmp "wt-link"))]
    (try
      (lifecycle/create-symlink-worktree! wt old-tg)
      (lifecycle/create-symlink-worktree! wt new-tg)
      (is (= (str (fs/real-path new-tg))
             (str (fs/real-path wt)))
          "second call should replace the stale link, pointing at the new target")
      (finally (fs/delete-tree tmp)))))

(deftest remove-symlink-worktree-removes-link-not-target
  (let [tmp    (fs/create-temp-dir)
        target (str (fs/create-dirs (str (fs/path tmp "shared-checkout"))))
        wt     (str (fs/path tmp "wt-link"))]
    (try
      (lifecycle/create-symlink-worktree! wt target)
      (lifecycle/remove-symlink-worktree! wt)
      (is (not (fs/exists? wt)) "symlink should be removed")
      (is (fs/exists? target) "target should NOT be removed")
      (finally (fs/delete-tree tmp)))))

(deftest remove-symlink-worktree-refuses-non-symlink-paths
  (let [tmp      (fs/create-temp-dir)
        real-dir (str (fs/create-dirs (str (fs/path tmp "real-dir"))))]
    (try
      ;; calling on a real dir should be a no-op (safety: never recurse-delete a dir)
      (lifecycle/remove-symlink-worktree! real-dir)
      (is (fs/exists? real-dir)
          "real directory must NOT be deleted by remove-symlink-worktree!")
      (finally (fs/delete-tree tmp)))))

(deftest enter!-auto-up?-calls-up!-when-session-home-missing
  (let [tmp (fs/create-temp-dir)
        project-name "fakeproj"
        session-name "feat-x"
        session-home (str (fs/path tmp "sessions" project-name session-name))
        up-called?   (atom false)]
    (try
      (with-redefs [nido.core/nido-home              (constantly (str tmp))
                    nido.session.state/session-home-dir (fn [_ _] session-home)
                    nido.session.lifecycle/resolve-project
                    (fn [_] [project-name {:directory (str tmp)}])
                    nido.session.lifecycle/up!
                    (fn [_n _]
                      (reset! up-called? true)
                      ;; simulate up! creating the session-home
                      (fs/create-dirs session-home))]
        (lifecycle/enter! session-name {:project project-name :auto-up? true})
        (is @up-called? "up! must be called when :auto-up? true")
        (is (= session-home
               (slurp (str (fs/path tmp ".last-cd"))))
            "after auto-up, .last-cd points at the session-home"))
      (finally (fs/delete-tree tmp)))))

(deftest enter!-worktree-falls-back-to-on-disk-path-when-session-home-missing
  (let [tmp (fs/create-temp-dir)
        project-name "fakeproj"
        project-dir  (str (fs/path tmp "src" project-name))
        session-name "feat-x"
        wt-root      (str (fs/path tmp "src" (str project-name "-worktrees")))
        wt-path      (str (fs/path wt-root session-name))
        session-home (str (fs/path tmp "sessions" project-name session-name))]
    (try
      (fs/create-dirs wt-path)               ; on-disk worktree exists
      (fs/create-dirs project-dir)
      ;; session-home is deliberately NOT created
      (with-redefs [nido.core/nido-home              (constantly (str tmp))
                    nido.session.state/session-home-dir (fn [_ _] session-home)
                    nido.session.lifecycle/resolve-project
                    (fn [_] [project-name {:directory project-dir}])
                    nido.session.engine/load-session-edn
                    (fn [_] {})]              ; default worktrees-dir
        (lifecycle/enter! session-name {:project project-name :cd :worktree})
        (is (= wt-path
               (slurp (str (fs/path tmp ".last-cd"))))
            ".last-cd points at the on-disk worktree, not the session-home symlink"))
      (finally (fs/delete-tree tmp)))))

(deftest enter!-worktree-throws-focused-error-when-worktree-also-gone
  (let [tmp (fs/create-temp-dir)
        project-name "fakeproj"
        project-dir  (str (fs/path tmp "src" project-name))
        session-name "feat-x"
        session-home (str (fs/path tmp "sessions" project-name session-name))]
    (try
      (fs/create-dirs project-dir)
      (with-redefs [nido.core/nido-home              (constantly (str tmp))
                    nido.session.state/session-home-dir (fn [_ _] session-home)
                    nido.session.lifecycle/resolve-project
                    (fn [_] [project-name {:directory project-dir}])
                    nido.session.engine/load-session-edn
                    (fn [_] {})]
        (let [ex (try (lifecycle/enter! session-name
                                        {:project project-name :cd :worktree})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is ex "enter! must throw when neither session-home nor worktree exists")
          (is (re-find #"Worktree no longer exists for 'feat-x'" (ex-message ex)))))
      (finally (fs/delete-tree tmp)))))

(deftest spawn-tab!-opens-warp-new-tab-at-session-home-without-handoff-file
  (let [tmp (fs/create-temp-dir)
        project-name "fakeproj"
        session-name "feat-x"
        session-home (str (fs/path tmp "sessions" project-name session-name))
        captured     (atom nil)]
    (try
      (fs/create-dirs session-home)
      (with-redefs [nido.core/nido-home                 (constantly (str tmp))
                    nido.session.state/session-home-dir (fn [_ _] session-home)
                    nido.session.lifecycle/resolve-project
                    (fn [_] [project-name {:directory (str tmp)}])
                    babashka.process/shell
                    (fn [& args] (reset! captured (vec args)) nil)]
        (let [ret (lifecycle/spawn-tab! session-name {:project project-name})]
          (is (= session-home ret) "spawn-tab! returns the resolved cwd")
          (is (= ["open" (str "warp://action/new_tab?path=" session-home)]
                 (remove map? @captured))
              "opens a Warp new_tab URI at the session-home")
          (is (not (fs/exists? (str (fs/path tmp ".last-cd"))))
              "spawn-tab! must NOT write the cd-target-file handoff")))
      (finally (fs/delete-tree tmp)))))

(deftest spawn-tab!-url-encodes-the-path-but-keeps-slashes-literal
  (let [tmp (fs/create-temp-dir)
        project-name "fakeproj"
        session-name "feat x"                  ; a space forces encoding
        session-home (str (fs/path tmp "sessions" project-name session-name))
        captured     (atom nil)]
    (try
      (fs/create-dirs session-home)
      (with-redefs [nido.core/nido-home                 (constantly (str tmp))
                    nido.session.state/session-home-dir (fn [_ _] session-home)
                    nido.session.lifecycle/resolve-project
                    (fn [_] [project-name {:directory (str tmp)}])
                    babashka.process/shell
                    (fn [& args] (reset! captured (vec args)) nil)]
        (lifecycle/spawn-tab! session-name {:project project-name})
        (let [uri (last (remove map? @captured))]
          (is (str/starts-with? uri "warp://action/new_tab?path=/")
              "leading path slashes stay literal (Warp accepts them)")
          (is (str/includes? uri "feat%20x") "spaces are percent-encoded")
          (is (not (str/includes? uri "feat x")) "no raw space survives in the URI")))
      (finally (fs/delete-tree tmp)))))

;; bookmark-exists? — `jj bookmark list <name>` exits 0 for both hit and miss,
;; so existence is read from stdout; a non-zero exit (stale/locked working
;; copy) must propagate as an error, never be read as "missing".

(deftest bookmark-exists?-true-when-jj-reports-the-name
  (with-redefs [nido.session.lifecycle/jj!
                (fn [_dir _args & _] {:exit 0 :out "run-foo\n" :err ""})]
    (is (true? (#'lifecycle/bookmark-exists? "/proj" "run-foo")))))

(deftest bookmark-exists?-false-when-jj-stdout-blank
  (with-redefs [nido.session.lifecycle/jj!
                (fn [_dir _args & _] {:exit 0 :out "" :err "Warning: No matching bookmarks"})]
    (is (false? (#'lifecycle/bookmark-exists? "/proj" "run-foo")))))

(deftest bookmark-exists?-propagates-jj-errors-instead-of-reporting-missing
  ;; jj! throws on non-zero exit (stale working copy) — bookmark-exists? must
  ;; not swallow it into a false, which would trigger a spurious create.
  (with-redefs [nido.session.lifecycle/jj!
                (fn [_dir _args & _]
                  (throw (ex-info "jj bookmark list failed"
                                  {:exit 1 :err "The working copy is stale"})))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'lifecycle/bookmark-exists? "/proj" "run-foo")))))
