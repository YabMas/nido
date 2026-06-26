(ns nido.session.session-from-cwd-test
  (:require
   [clojure.test :refer [deftest is]]
   [nido.config :as config]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.state :as state]))

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
