(ns nido.session.session-from-cwd-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.platform.config :as config]
   [nido.session.engine :as engine]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as state]))

;; A brian session with a SLASHED name, plus its session-home location.
(def slashed-registry
  {"/Code/brian/.worktrees/feat/fold-ai-tutor-tab"
   {:project-name "brian" :instance-id "brian--fold"}})

;; A brian session whose worktree is ~/Code/brian/.worktrees/fix/ordering,
;; i.e. worktrees-dir = ~/Code/brian/.worktrees, session = "fix/ordering".
(def fake-registry
  {"/Code/brian/.worktrees/fix/ordering"
   {:project-name "brian" :instance-id "brian--ordering"}
   "/Code/brian/.worktrees/feat/auth"
   {:project-name "brian" :instance-id "brian--auth"}})

(def fake-projects {"brian" {:directory "/Code/brian"}})

(defn- with-fakes [f]
  (with-redefs [state/read-registry  (fn [] fake-registry)
                config/read-projects (fn [] fake-projects)
                lifecycle/worktrees-dir (fn [_p _d] "/Code/brian/.worktrees")
                ;; canonicalize is identity here (paths are synthetic, not on disk)
                lifecycle/canonical  (fn [p] (str p))]
    (f)))

(deftest resolves-session-from-worktree-cwd
  (with-fakes
    (fn []
      (is (= {:project "brian" :session "fix/ordering"
              :worktree "/Code/brian/.worktrees/fix/ordering"
              :instance-id "brian--ordering"}
             (lifecycle/session-from-cwd "/Code/brian/.worktrees/fix/ordering"))))))

(deftest resolves-from-a-subdir-of-the-worktree
  (with-fakes
    (fn []
      (is (= "fix/ordering"
             (:session (lifecycle/session-from-cwd
                        "/Code/brian/.worktrees/fix/ordering/src/app")))))))

(deftest longest-prefix-wins-for-nested-worktrees
  ;; cwd under feat/auth must not match fix/ordering and vice-versa
  (with-fakes
    (fn []
      (is (= "brian--auth"
             (:instance-id (lifecycle/session-from-cwd
                            "/Code/brian/.worktrees/feat/auth/test")))))))

(deftest returns-nil-when-cwd-is-outside-any-worktree
  (with-fakes
    (fn []
      (is (nil? (lifecycle/session-from-cwd "/tmp/elsewhere")))
      ;; a sibling that only shares a path prefix segment must NOT match
      (is (nil? (lifecycle/session-from-cwd "/Code/brian/.worktrees/fix/orderingX"))))))

(deftest skips-entries-whose-project-is-not-registered
  ;; Foreign/legacy registry entries — a codex worktree with :project-name nil,
  ;; or a project no longer in config — must not be matched: cwd inside one
  ;; resolves to nil (graceful), never crashing in worktrees-dir/load-session-edn.
  (with-redefs [state/read-registry
                (fn [] {"/Codex/worktrees/6aa4/brian-next"
                        {:project-name nil :instance-id nil}
                        "/Code/ghost/.worktrees/x"
                        {:project-name "ghost" :instance-id "ghost--x"}})
                config/read-projects (fn [] {"brian" {:directory "/Code/brian"}})
                lifecycle/worktrees-dir (fn [_p _d] "/never")
                lifecycle/canonical (fn [p] (str p))]
    (is (nil? (lifecycle/session-from-cwd "/Codex/worktrees/6aa4/brian-next/src")))
    (is (nil? (lifecycle/session-from-cwd "/Code/ghost/.worktrees/x/src")))))

;; resolve-link-coords now consults session-from-cwd as a resolution source.
;; It recomputes worktree/instance-id from the resolved project+session
;; (always correct, incl. when explicit :project/<session> differ from cwd),
;; so we stub worktree-path + resolve-instance-id to known values.
;; ---------------------------------------------------------------------------
;; worktree-from-cwd: the home-aware union that lets cwd-based verbs (review:loop)
;; reach the code whether cwd is inside the worktree OR at the session-home.
;; ---------------------------------------------------------------------------

(deftest worktree-from-cwd-resolves-from-inside-the-worktree
  (with-fakes
    (fn []
      (is (= "/Code/brian/.worktrees/fix/ordering"
             (lifecycle/worktree-from-cwd
              "/Code/brian/.worktrees/fix/ordering/src/app"))))))

(deftest worktree-from-cwd-resolves-from-the-session-home
  ;; cwd is the session-home (~/.nido/sessions/<p>/<s>), NOT a jj workspace.
  ;; It must still resolve to the worktree — and for a SLASHED session name.
  (with-redefs [state/read-registry     (fn [] slashed-registry)
                config/read-projects    (fn [] {"brian" {:directory "/Code/brian"}})
                lifecycle/worktrees-dir (fn [_p _d] "/Code/brian/.worktrees")
                lifecycle/canonical     (fn [p] (str p))
                state/sessions-root     (fn [] "/home/.nido/sessions")]
    (is (= "/Code/brian/.worktrees/feat/fold-ai-tutor-tab"
           (lifecycle/worktree-from-cwd
            "/home/.nido/sessions/brian/feat/fold-ai-tutor-tab")))))

(deftest worktree-from-cwd-is-nil-outside-any-session
  (with-fakes
    (fn []
      (with-redefs [state/sessions-root (fn [] "/home/.nido/sessions")]
        (is (nil? (lifecycle/worktree-from-cwd "/tmp/elsewhere")))))))

(deftest session-home-coords-handles-slashed-session-names
  ;; Regression: splitting on "/" and taking the 2nd segment dropped the rest
  ;; of a slashed session ("feat/x" → "feat"). Session = everything after the
  ;; first path segment (the project).
  (with-redefs [state/sessions-root (fn [] "/home/.nido/sessions")]
    (is (= ["brian" "feat/fold-ai-tutor-tab"]
           (#'lifecycle/session-home-coords-from-cwd
            "/home/.nido/sessions/brian/feat/fold-ai-tutor-tab")))))

(deftest link-coords-resolve-from-worktree-cwd
  (with-redefs [lifecycle/session-from-cwd
                (fn [& _] {:project "brian" :session "fix/ordering"})
                lifecycle/session-home-coords-from-cwd (fn [] nil)
                config/read-projects (fn [] {"brian" {:directory "/Code/brian"}})
                lifecycle/worktree-path
                (fn [_p _d s] (str "/Code/brian/.worktrees/" s))
                engine/resolve-instance-id (fn [_] "brian--ordering")]
    ;; resolve-link-coords is private; exercise via the var.
    (is (= ["brian" "fix/ordering" "brian--ordering"
            "/Code/brian/.worktrees/fix/ordering"]
           (#'lifecycle/resolve-link-coords {} nil)))))
