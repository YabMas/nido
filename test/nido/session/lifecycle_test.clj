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

;; jj! runs against the source repo's *default* workspace, which nido never
;; edits — every call is a metadata op (bookmark list/create, workspace
;; add/forget). Snapshotting that working copy is incidental, and it trips jj's
;; stale-working-copy guard the moment concurrent session workspaces advance the
;; shared op log. The global --ignore-working-copy flag skips the snapshot, so a
;; stale default workspace never blocks session creation.

(deftest jj!-passes-ignore-working-copy-as-a-global-flag
  (let [captured (atom nil)]
    (with-redefs [babashka.process/shell
                  (fn [_opts & args]
                    (reset! captured (vec args))
                    {:exit 0 :out "" :err ""})]
      (#'lifecycle/jj! "/proj" ["bookmark" "list" "foo"])
      (is (= ["jj" "--ignore-working-copy" "bookmark" "list" "foo"] @captured)
          "--ignore-working-copy must precede the subcommand (jj global flag)"))))

;; --ignore-working-copy globally disables jj's working-copy updates, which is
;; right for metadata ops but fatal for `jj workspace add` — that command's whole
;; job is to materialize the new workspace's working copy, so jj 0.41 rejects the
;; flag there ("This command must be able to update the working copy"). :ignore-wc?
;; (default true) lets the workspace-add call opt out so :full sessions can spawn.

(deftest jj!-includes-ignore-working-copy-by-default
  (let [captured (atom nil)]
    (with-redefs [babashka.process/shell
                  (fn [_opts & args]
                    (reset! captured (vec args))
                    {:exit 0 :out "" :err ""})]
      (#'lifecycle/jj! "/proj" ["bookmark" "list" "foo"])
      (is (= "jj" (first @captured)))
      (is (some #{"--ignore-working-copy"} @captured)
          "metadata ops keep --ignore-working-copy (stale-default-workspace guard)"))))

(deftest jj!-omits-ignore-working-copy-when-ignore-wc-false
  (let [captured (atom nil)]
    (with-redefs [babashka.process/shell
                  (fn [_opts & args]
                    (reset! captured (vec args))
                    {:exit 0 :out "" :err ""})]
      (#'lifecycle/jj! "/proj"
                       ["workspace" "add" "--name" "b" "--revision" "b" "/wt"]
                       :ignore-wc? false)
      (is (not (some #{"--ignore-working-copy"} @captured))
          "workspace add must NOT get --ignore-working-copy (jj rejects it)")
      (is (= ["jj" "workspace" "add" "--name" "b" "--revision" "b" "/wt"] @captured)
          "the jj command is otherwise unchanged"))))

;; jj-worktree-poisoned? — a failed `jj workspace add` leaves the dir + `.jj/`
;; but strands `@` on the all-zeros root commit with an empty working copy. The
;; poisoned signal is `@-` (parent of the workspace's working copy) resolving to
;; the root commit. CONSERVATIVE: only true on a definitive root-parent signal;
;; any jj error or a real parent commit → false, so we never destroy a healthy
;; worktree (uncommitted work auto-snapshots, leaving `@-` at a real bookmark).

(deftest jj-worktree-poisoned?-true-when-parent-is-root-commit
  (with-redefs [babashka.fs/exists? (fn [_] true)               ; .jj/ probe → jj-workspace?
                babashka.process/shell
                (fn [_opts & _args]
                  {:exit 0 :out "0000000000000000000000000000000000000000\n" :err ""})]
    (is (true? (#'lifecycle/jj-worktree-poisoned? "/wt"))
        "an all-zeros @- (root commit) marks the worktree poisoned")))

(deftest jj-worktree-poisoned?-false-when-parent-is-real-commit
  (with-redefs [babashka.fs/exists? (fn [_] true)
                babashka.process/shell
                (fn [_opts & _args]
                  {:exit 0 :out "b3140745aabb1c2d3e4f56789a0b1c2d3e4f5678\n" :err ""})]
    (is (false? (#'lifecycle/jj-worktree-poisoned? "/wt"))
        "a real @- commit (even with uncommitted work) is NOT poisoned")))

(deftest jj-worktree-poisoned?-false-when-not-a-jj-workspace
  (let [jj-called? (atom false)]
    (with-redefs [babashka.fs/exists? (fn [_] false)            ; no .jj/ → not a jj workspace
                  babashka.process/shell
                  (fn [_opts & _args]
                    (reset! jj-called? true)
                    {:exit 0 :out "" :err ""})]
      (is (false? (#'lifecycle/jj-worktree-poisoned? "/wt"))
          "a non-jj dir is never poisoned")
      (is (false? @jj-called?)
          "must NOT invoke jj when the dir isn't a jj workspace"))))

(deftest jj-worktree-poisoned?-false-on-jj-error
  (with-redefs [babashka.fs/exists? (fn [_] true)
                babashka.process/shell
                (fn [_opts & _args]
                  {:exit 1 :out "" :err "boom"})]
    (is (false? (#'lifecycle/jj-worktree-poisoned? "/wt"))
        "conservative: a jj error must never mark a worktree poisoned")))

;; workspace-stale? — `jj workspace add` is the one nido jj call that can't take
;; --ignore-working-copy, so it snapshots the (shared) default workspace and
;; aborts with "The working copy is stale (not updated since operation …)" when
;; that workspace lags the op log. The predicate recognises exactly that failure
;; so create-jj-workspace! can self-heal it (vs every other failure, which throws).

(deftest workspace-stale?-true-on-stale-working-copy-error
  (is (true? (#'lifecycle/workspace-stale?
              {:exit 1 :out ""
               :err "Error: The working copy is stale (not updated since operation 01d4e90ca96d).\nHint: Run `jj workspace update-stale` to update it."}))))

(deftest workspace-stale?-false-on-success
  (is (false? (#'lifecycle/workspace-stale? {:exit 0 :out "" :err ""}))
      "a successful add is never 'stale' even if stderr were noisy"))

(deftest workspace-stale?-false-on-unrelated-failure
  (is (false? (#'lifecycle/workspace-stale?
               {:exit 1 :out "" :err "Error: Destination path already exists."}))
      "only the stale-working-copy failure is self-healable; other errors propagate"))

;; jj-workspace-add! — materialize the new session workspace, self-healing the
;; stale-default-workspace failure (the harness's most common session-creation
;; abort) by running the `jj workspace update-stale` the error itself prescribes
;; and retrying the add ONCE. Any other failure, or a second stale failure, throws.

(defn- scripted-jj!
  "A jj! stand-in that faithfully mimics jj!'s contract — returns the scripted
   result map, but throws on a non-zero exit unless :continue? was passed (just
   as the real jj! does). `script` maps a subcommand vector (first 2 args) to a
   0-arg thunk returning {:exit :out :err}; each subcommand's thunk is called
   fresh per invocation so it can vary across retries via a closed-over counter.
   Records every subcommand (first 2 tokens) into `calls`."
  [script calls]
  (fn [_dir args & {:keys [continue?]}]
    (let [key (vec (take 2 args))
          _   (swap! calls conj key)
          r   ((get script key (fn [] {:exit 0 :out "" :err ""})))]
      (if (and (not continue?) (not (zero? (:exit r))))
        (throw (ex-info (str "jj " (str/join " " args) " failed")
                        {:exit (:exit r) :err (:err r)}))
        r))))

(deftest jj-workspace-add!-succeeds-without-recovery-when-not-stale
  (let [calls (atom [])]
    (with-redefs [nido.session.lifecycle/jj!
                  (scripted-jj! {["workspace" "add"] (constantly {:exit 0 :out "" :err ""})}
                                calls)]
      (#'lifecycle/jj-workspace-add! "/proj" "/wt" "feat/x")
      (is (= [["workspace" "add"]] @calls)
          "a clean add neither runs update-stale nor retries"))))

(deftest jj-workspace-add!-recovers-from-stale-default-workspace
  (let [calls (atom [])
        adds  (atom 0)]
    (with-redefs [nido.session.lifecycle/jj!
                  (scripted-jj!
                   {["workspace" "add"]
                    (fn [] (if (= 1 (swap! adds inc))
                             {:exit 1 :out ""
                              :err "Error: The working copy is stale (not updated since operation 01d4e90ca96d)."}
                             {:exit 0 :out "" :err ""}))
                    ["workspace" "update-stale"]
                    (constantly {:exit 0 :out "Working copy now at: ..." :err ""})}
                   calls)]
      (#'lifecycle/jj-workspace-add! "/proj" "/wt" "feat/x")
      (is (= [["workspace" "add"]            ; first try → stale
              ["workspace" "update-stale"]   ; self-heal the default workspace
              ["workspace" "add"]]           ; retry → succeeds
             @calls)
          "stale add triggers update-stale, then exactly one retry"))))

(deftest jj-workspace-add!-propagates-non-stale-failures-without-recovery
  (let [calls (atom [])]
    (with-redefs [nido.session.lifecycle/jj!
                  (scripted-jj!
                   {["workspace" "add"]
                    (constantly {:exit 1 :out "" :err "Error: Destination path already exists."})}
                   calls)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (#'lifecycle/jj-workspace-add! "/proj" "/wt" "feat/x")))
      (is (= [["workspace" "add"]] @calls)
          "a non-stale failure must NOT run update-stale or retry"))))

(deftest jj-workspace-add!-throws-when-retry-after-update-stale-still-fails
  (let [calls (atom [])]
    (with-redefs [nido.session.lifecycle/jj!
                  (scripted-jj!
                   {["workspace" "add"]
                    (constantly {:exit 1 :out ""
                                 :err "Error: The working copy is stale (not updated since operation deadbeef)."})
                    ["workspace" "update-stale"]
                    (constantly {:exit 0 :out "" :err ""})}
                   calls)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (#'lifecycle/jj-workspace-add! "/proj" "/wt" "feat/x"))
          "if the add is still stale after update-stale, surface the failure")
      (is (= [["workspace" "add"]
              ["workspace" "update-stale"]
              ["workspace" "add"]]
             @calls)
          "recovery is attempted exactly once — no infinite retry loop"))))
