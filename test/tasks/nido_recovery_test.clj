(ns tasks.nido-recovery-test
  "The recovery land verb refuses before it touches a remote."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.record.records :as records]
   [nido.session.lifecycle :as lifecycle]
   [nido.platform.jj :as jj]
   [tasks.nido-recovery :as recovery]))

(defn- land-in [{:keys [project diagnosis run] :or {run "recover-x"}}]
  (let [jj-calls (atom [])
        read     (atom [])
        out      (with-out-str
                   (with-redefs [lifecycle/worktree-from-cwd (constantly "/wt")
                                 lifecycle/session-from-cwd  (constantly {:project project :session "recover-x"})
                                 records/of                  (fn [k] (swap! read conj k)
                                                               (when diagnosis
                                                                 [(assoc diagnosis :kind :session-diagnosis)]))
                                 jj/jj!                      (fn [& args] (swap! jj-calls conj args) {:exit 1})]
                     (is (= 1 (apply recovery/land (cond-> [":cwd" "/wt"] run (into [":run" run])))))))]
    {:out out :jj-calls @jj-calls :read @read}))

(deftest land-refuses-outside-nidos-own-repository
  (let [{:keys [out jj-calls]} (land-in {:project "brian" :diagnosis {:verdict :nido-defect}})]
    (is (str/includes? out "lands only nido's own repository"))
    (is (empty? jj-calls) "a project defect is a pull request, and nothing is fetched or pushed")))

(deftest land-refuses-without-a-nido-defect-diagnosis
  (doseq [diagnosis [nil {:verdict :one-off} {:verdict :project-defect}]]
    (let [{:keys [out jj-calls read]} (land-in {:project "nido" :diagnosis diagnosis})]
      (is (str/includes? out "not a nido defect"))
      (is (= ["run:nido/recover-x"] read) "the diagnosis read is the one the run recorded")
      (is (empty? jj-calls)))))

(deftest land-refuses-without-a-run
  (when-not (System/getenv "NIDO_CORE_RUN")
    (let [{:keys [out jj-calls]} (land-in {:project "nido" :run nil :diagnosis {:verdict :nido-defect}})]
      (is (str/includes? out "no recovery run"))
      (is (empty? jj-calls)))))
