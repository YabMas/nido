(ns nido.session.casualties-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.process :as process]
   [nido.session.casualties :as casualties]
   [nido.session.state :as state]))

(def ^:private boot {:id "1761000000" :began-at "2026-10-25T10:00:00Z"})

(defn- entry [id wt at]
  {:instance-id id :project-name "brian" :project-dir wt :created-at at})

(defn- with-dirs
  "Run `f` with the casualty record and the registry pointed at a temp dir and `registry`."
  [registry f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [state/casualties-dir (constantly (str (fs/path tmp "casualties")))
                    state/read-registry  (fn [] @registry)
                    process/host-boot    (constantly boot)]
        (f tmp))
      (finally (fs/delete-tree tmp)))))

(deftest pre-boot-is-registration-against-the-boot
  (is (casualties/pre-boot? (entry "a" "/w" "2026-10-24T12:00:00Z") boot))
  (is (not (casualties/pre-boot? (entry "a" "/w" "2026-10-25T11:00:00Z") boot)))
  (testing "no readable time is taken as old, as the prune takes it"
    (is (casualties/pre-boot? (entry "a" "/w" nil) boot))
    (is (casualties/pre-boot? (entry "a" "/w" "garbage") boot)))
  (testing "an unknown boot classifies nothing"
    (is (not (casualties/pre-boot? (entry "a" "/w" nil) nil)))))

(deftest record-holds-pre-boot-entries-once-and-unsettled-derives-the-rest
  (let [registry (atom {})]
    (with-dirs registry
      (fn [tmp]
        (let [wt-a (str (fs/create-dirs (fs/path tmp "a")))
              wt-b (str (fs/create-dirs (fs/path tmp "b")))
              a    (entry "brian--a" wt-a "2026-10-24T12:00:00Z")
              b    (entry "brian--b" wt-b "2026-10-24T13:00:00Z")
              gone (entry "brian--gone" (str (fs/path tmp "nope")) "2026-10-24T13:00:00Z")
              new  (entry "brian--new" wt-a "2026-10-25T11:00:00Z")
              held (casualties/record! [a b gone new])]
          (is (= :held (:outcome held)))
          (is (= #{"brian--a" "brian--b" "brian--gone"} (set (map :instance-id (:held held))))
              "only what was registered before the boot")
          (casualties/record! [(assoc a :created-at "2026-10-24T09:00:00Z")])
          (is (= ["brian--a" "brian--b"] (map :instance-id (:casualties (casualties/unsettled))))
              "held once, and a casualty whose worktree is gone is owed nothing")
          (is (= "2026-10-24T12:00:00Z"
                 (:registered-at (first (:casualties (casualties/unsettled)))))
              "an instance keeps the entry it was first held with")
          (testing "coming back up after the boot settles it"
            (reset! registry {wt-b (entry "brian--b" wt-b "2026-10-25T12:00:00Z")})
            (is (= ["brian--a"] (map :instance-id (:casualties (casualties/unsettled))))))
          (testing "settle! settles"
            (is (true? (casualties/settle! (:casualties (casualties/unsettled)))))
            (is (= [] (:casualties (casualties/unsettled))))))))))

(deftest an-unreadable-record-is-reported-not-taken-as-empty
  (with-dirs (atom {})
    (fn [tmp]
      (let [dir (fs/create-dirs (fs/path tmp "casualties"))]
        (spit (str (fs/path dir "bad.edn")) "{:boot")
        (let [{:keys [casualties unreadable]} (casualties/unsettled)]
          (is (= [] casualties))
          (is (= 1 (count unreadable))))))))

(deftest record-answers-why-it-held-nothing
  (with-redefs [process/host-boot (constantly nil)]
    (is (= {:outcome :no-boot} (casualties/record! [(entry "a" "/w" nil)]))))
  (with-redefs [process/host-boot    (constantly boot)
                state/casualties-dir (constantly "/dev/null/cannot")]
    (is (= :failed (:outcome (casualties/record! [(entry "a" "/w" nil)]))))))
