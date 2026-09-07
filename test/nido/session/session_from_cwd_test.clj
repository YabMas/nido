(ns nido.session.session-from-cwd-test
  (:require
   [babashka.fs :as fs]
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

;; ---------------------------------------------------------------------------
;; A `:lite` worktree is a symlink to the project checkout, so it canonicalizes
;; to the checkout ROOT. That root prefixes cwds the entry names nothing about,
;; and is identical for every lite session running at once — so a match on one
;; is a session picked arbitrarily from the ones that tied, whose name
;; relativizes outside worktrees-dir (`..`, `../nido`) and therefore keys no
;; workstream, no state dir and no session home. Containment refuses it.
;; ---------------------------------------------------------------------------

(deftest lite-worktrees-do-not-answer-for-the-bare-checkout
  ;; Nested layout (brian: worktrees-dir INSIDE the checkout), on real symlinks
  ;; so canonicalize does the resolving rather than a stub.
  (let [tmp  (fs/create-temp-dir)
        pdir (str (fs/path tmp "brian"))
        base (str (fs/path pdir ".worktrees"))]
    (try
      (fs/create-dirs base)
      (fs/create-sym-link (fs/path base "run-triage-a") pdir)
      (fs/create-sym-link (fs/path base "run-triage-b") pdir)
      (with-redefs [state/read-registry
                    (fn [] {(str (fs/path base "run-triage-a"))
                            {:project-name "brian" :instance-id "brian--a"}
                            (str (fs/path base "run-triage-b"))
                            {:project-name "brian" :instance-id "brian--b"}})
                    config/read-projects    (fn [] {"brian" {:directory pdir}})
                    lifecycle/worktrees-dir (fn [_p _d] base)]
        (is (nil? (lifecycle/session-from-cwd pdir))
            "the bare checkout is where no session lives, and the two lite entries tie on it — an answer here names a worktree the caller is not standing in")
        (is (nil? (lifecycle/session-from-cwd (str (fs/path pdir "src"))))
            "a subdirectory of the checkout inherits the same tie"))
      (finally (fs/delete-tree tmp)))))

(deftest lite-worktree-in-a-sibling-worktrees-dir-is-refused-too
  ;; Sibling layout (nido: ~/Code/nido-worktrees alongside ~/Code/nido), which
  ;; yields the "../nido" name rather than "..". Synthetic, with canonical
  ;; standing in for the symlink resolution.
  (with-redefs [state/read-registry
                (fn [] {"/Code/nido-worktrees/run-analysis-a"
                        {:project-name "nido" :instance-id "nido--a"}})
                config/read-projects    (fn [] {"nido" {:directory "/Code/nido"}})
                lifecycle/worktrees-dir (fn [_p _d] "/Code/nido-worktrees")
                lifecycle/canonical
                (fn [p] (if (= (str p) "/Code/nido-worktrees/run-analysis-a")
                          "/Code/nido"
                          (str p)))]
    (is (nil? (lifecycle/session-from-cwd "/Code/nido"))
        "an escaping name is refused wherever worktrees-dir sits relative to the checkout, not only when it is nested inside it")))

(deftest a-lite-entry-does-not-suppress-the-real-worktree-the-caller-is-in
  ;; Containment has to be asked of each candidate, not of the winner: an agent
  ;; standing in a real worktree must keep resolving while lite sessions — whose
  ;; canonical root prefixes that worktree — are registered alongside it.
  (with-redefs [state/read-registry
                (fn [] {"/Code/brian/.worktrees/run-triage-a"
                        {:project-name "brian" :instance-id "brian--a"}
                        "/Code/brian/.worktrees/fix/ordering"
                        {:project-name "brian" :instance-id "brian--ordering"}})
                config/read-projects    (fn [] {"brian" {:directory "/Code/brian"}})
                lifecycle/worktrees-dir (fn [_p _d] "/Code/brian/.worktrees")
                lifecycle/canonical
                (fn [p] (if (= (str p) "/Code/brian/.worktrees/run-triage-a")
                          "/Code/brian"
                          (str p)))]
    (is (= "fix/ordering"
           (:session (lifecycle/session-from-cwd
                      "/Code/brian/.worktrees/fix/ordering/src")))
        "a lite entry is skipped as a candidate rather than answered with, so the session the caller is actually in still wins")))

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
