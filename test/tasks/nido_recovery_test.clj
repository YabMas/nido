(ns tasks.nido-recovery-test
  "The recovery land verb refuses before it touches a remote."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.record.workstream :as cws]
   [nido.review.stages :as stages]
   [nido.session.lifecycle :as lifecycle]
   [nido.vsdd.jj :as jj]
   [tasks.nido-recovery :as recovery]))

(defn- land-in [{:keys [project diagnosis]}]
  (let [jj-calls (atom [])
        out      (with-out-str
                   (with-redefs [lifecycle/worktree-from-cwd (constantly "/wt")
                                 lifecycle/session-from-cwd  (constantly {:project project :session "recover-x"})
                                 stages/project+ws-from-cwd  (constantly [:nido "ws-r"])
                                 cws/latest-entry            (fn [_ _ kind] (when (= :session-diagnosis kind) diagnosis))
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
