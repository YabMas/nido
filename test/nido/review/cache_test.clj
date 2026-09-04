;; test/nido/review/cache_test.clj
(ns nido.review.cache-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.record.state :as cstate]
   [nido.review.cache :as cache]))

(defn- with-tmp [f]
  (let [tmp (str (fs/create-temp-dir))]
    (with-redefs [cstate/workstream-dir (fn [_ _] tmp)]
      (f))))

(deftest converged-only-for-the-exact-patch
  (let [c (cache/record {} "hash-a" {:status :converged :label "drop-legacy"})]
    (is (cache/converged? c "hash-a"))
    (is (not (cache/converged? c "hash-b"))
        "a changed layer hashes differently and is reviewed again")))

(deftest record-never-removes-an-earlier-entry
  ;; The store only grows, so a patch that comes back — an align that was
  ;; reverted, a layer spun out and re-landed — is still a hit.
  (let [c (-> {}
              (cache/record "hash-a" {:status :converged})
              (cache/record "hash-b" {:status :converged}))]
    (is (cache/converged? c "hash-a"))
    (is (cache/converged? c "hash-b"))))

(deftest a-patch-that-still-owes-something-is-reviewed-and-keeps-its-answers
  ;; The entry the read path actually reaches. A converged patch is one the next
  ;; round skips, so its answers are never asked for; a patch still owing
  ;; something is reviewed again, and the answers on it are what stop the round
  ;; that reviews it re-adjudicating what the last one decided.
  (let [c (cache/record {} "hash-a" {:status   :partial
                                     :answered [{:id "aa11" :disposition :declined}]})]
    (is (not (cache/converged? c "hash-a"))
        "only :converged grants a skip, so this patch is reviewed again")
    (is (= ["aa11"] (map :id (cache/answered c "hash-a")))
        "and reviewing it is exactly when its answers are read")))

(deftest an-entry-that-names-no-status-is-reviewed
  ;; The default used to be :converged, which made forgetting to say cost a
  ;; skipped review rather than a duplicated one — the one direction this store
  ;; must never lean.
  (is (not (cache/converged? (cache/record {} "hash-a" {:label "l"}) "hash-a"))))

(deftest answers-hang-off-the-patch-and-evaporate-when-it-changes
  (let [c (cache/record {} "hash-a" {:answered [{:id "aa11" :because "out of scope"}]})]
    (is (= ["aa11"] (map :id (cache/answered c "hash-a"))))
    (is (= [] (cache/answered c "hash-b"))
        "answers were about that content; different content re-opens the question")))

(deftest round-trips-through-the-workstream-dir
  (with-tmp
    (fn []
      (is (= {} (cache/read-cache :nido "ws-1")) "no cache yet is not an error")
      (cache/write! :nido "ws-1"
                    (cache/record {} "hash-a" {:status :converged :label "l"}))
      (is (cache/converged? (cache/read-cache :nido "ws-1") "hash-a")))))

(deftest an-unreadable-cache-degrades-to-reviewing-everything
  (with-tmp
    (fn []
      (spit (cache/path :nido "ws-1") "{not edn")
      (is (= {} (cache/read-cache :nido "ws-1"))
          "corrupt must mean review, never skip"))))
