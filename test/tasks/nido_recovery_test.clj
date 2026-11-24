(ns tasks.nido-recovery-test
  "The recovery land verb refuses before it touches a remote."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.record.workstream :as cws]
   [nido.review.stages :as stages]
   [nido.session.lifecycle :as lifecycle]
   [nido.platform.jj :as jj]
   [tasks.nido-recovery :as recovery]))

(defn- land-in [{:keys [project diagnosis cwd-ws turn-ws] :or {cwd-ws [:nido "ws-r"]}}]
  (let [jj-calls (atom [])
        out      (with-out-str
                   (with-redefs [lifecycle/worktree-from-cwd (constantly "/wt")
                                 lifecycle/session-from-cwd  (constantly {:project project :session "recover-x"})
                                 stages/project+ws-from-cwd  (constantly cwd-ws)
                                 recovery/turn-ws            (constantly turn-ws)
                                 cws/latest-entry            (fn [p ws kind]
                                                               (when (and (= :session-diagnosis kind)
                                                                          (= [:nido (or (second cwd-ws) (second turn-ws))] [p ws]))
                                                                 diagnosis))
                                 jj/jj!                      (fn [& args] (swap! jj-calls conj args) {:exit 1})]
                     (is (= 1 (recovery/land ":cwd" "/wt")))))]
    {:out out :jj-calls @jj-calls}))

(deftest land-refuses-outside-nidos-own-repository
  (let [{:keys [out jj-calls]} (land-in {:project "brian" :diagnosis {:verdict :nido-defect}})]
    (is (str/includes? out "lands only nido's own repository"))
    (is (empty? jj-calls) "a project defect is a pull request, and nothing is fetched or pushed")))

(deftest land-refuses-without-a-nido-defect-diagnosis
  (doseq [diagnosis [nil {:verdict :one-off} {:verdict :project-defect}]]
    (let [{:keys [out jj-calls]} (land-in {:project "nido" :diagnosis diagnosis})]
      (is (str/includes? out "not a nido defect"))
      (is (empty? jj-calls)))))

;; The core provisions a recovery as a unit whose session no workstream record holds, so its
;; worktree names no workstream; the verb refused every such recovery as "belongs to no
;; workstream". The core turn names its workstream in its environment.

(deftest land-reads-the-workstream-of-the-core-turn-it-runs-in
  (let [{:keys [out]} (land-in {:project "nido" :cwd-ws nil :turn-ws [:nido "ws-core"]
                                :diagnosis {:verdict :one-off}})]
    (is (str/includes? out "not a nido defect") "the turn's workstream's diagnosis is the one read"))
  (let [{:keys [out]} (land-in {:project "nido" :cwd-ws nil :turn-ws nil})]
    (is (str/includes? out "belongs to no workstream"))))
