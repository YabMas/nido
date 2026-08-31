(ns nido.session.links-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.session.links :as links]
   [nido.session.state :as state]))

(defn- with-tmp-home [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-home (constantly (str tmp))]
        (f (str tmp)))
      (finally (fs/delete-tree tmp)))))

(defn- legacy-path [instance-id]
  (str (fs/path (state/instance-state-dir instance-id) "links.edn")))

(deftest add!-dedupes-on-url-in-place
  (with-tmp-home
    (fn [_]
      (links/add! "p--s" {:type :pr :url "https://x/pull/1"})
      (links/add! "p--s" {:type :notion-ticket :url "https://notion.so/BR-1"})
      (links/add! "p--s" {:type :pr :url "https://x/pull/1" :title "renamed"})
      (let [ls (links/read-links "p--s")]
        (is (= 2 (count ls)) "same url replaces rather than repeats")
        (is (= "renamed" (:title (first ls))) "replaced in place, order preserved")))))

(deftest remove-by-url!-refuses-a-miss-without-writing
  (with-tmp-home
    (fn [_]
      (links/add! "p--s" {:type :pr :url "https://x/pull/1"})
      (is (thrown? Exception (links/remove-by-url! "p--s" "https://nope")))
      (is (= 1 (count (links/read-links "p--s"))) "a refused removal leaves the file alone"))))

(deftest links-survive-reclaiming-the-instance-state-dir
  ;; The property this file exists for. `down` deregisters the session, which
  ;; makes its state dir an orphan, and the coordinator deletes orphans an hour
  ;; later. Links must not be in the blast radius.
  (with-tmp-home
    (fn [_]
      (links/add! "p--s" {:type :pr :url "https://x/pull/1"})
      (let [state-dir (state/instance-state-dir "p--s")]
        (fs/create-dirs state-dir)
        (fs/delete-tree state-dir))
      (is (= 1 (count (links/read-links "p--s")))
          "links outlive the state dir reclaim deletes"))))

(deftest legacy-links-migrate-out-of-the-state-dir
  (with-tmp-home
    (fn [_]
      (let [old (legacy-path "p--s")]
        (fs/create-dirs (fs/parent old))
        (io/write-edn! old {:links [{:type :pr :url "https://old/pull/1"}]})
        (testing "read finds them"
          (is (= [{:type :pr :url "https://old/pull/1"}] (links/read-links "p--s"))))
        (testing "and moves them somewhere nothing sweeps"
          (is (fs/exists? (links/links-path "p--s")))
          (is (not (fs/exists? old))))))))

(deftest delete-links!-ends-them-for-destroy
  (with-tmp-home
    (fn [_]
      (links/add! "p--s" {:type :pr :url "https://x/pull/1"})
      (let [old (legacy-path "p--s")]
        (fs/create-dirs (fs/parent old))
        (io/write-edn! old {:links [{:type :pr :url "https://stale/pull/9"}]})
        (links/delete-links! "p--s")
        (is (= [] (links/read-links "p--s")))
        (is (not (fs/exists? old))
            "the legacy copy goes too, so a later same-named session cannot inherit it")))))
