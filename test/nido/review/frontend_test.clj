(ns nido.review.frontend-test
  (:require
   [clojure.test :refer [deftest is]]
   [cheshire.core :as json]
   [babashka.fs :as fs]
   [nido.review.frontend :as frontend]))

(def clock (constantly (java.time.Instant/parse "2026-06-30T14:00:00Z")))

(deftest emit-fn-folds-and-persists
  (let [dir  (str (fs/create-temp-dir))
        path (str (fs/path dir "report.json"))
        a    (atom nil)
        emit (frontend/emit-fn a path clock false)]
    (emit {:event :run-started :run-id "r" :cwd "/w" :base "main" :at "2026-06-30T14:00:00Z"})
    (emit {:event :phase-started :iter 1 :phase :review :at "2026-06-30T14:00:01Z"})
    (is (= "running" (:status @a)))
    (is (= 1 (count (:rounds @a))))
    (is (fs/exists? path))
    (is (= "r" (:run-id (json/parse-string (slurp path) true))))))

(deftest with-live-display-plain-runs-and-returns
  ;; Plain mode (no render thread): runs f, returns its value, leaves a report.
  (let [dir  (str (fs/create-temp-dir))
        path (str (fs/path dir "report.json"))
        a    (atom nil)
        out  (with-out-str
               (let [v (frontend/with-live-display
                         {:report-atom a :report-path path :clock clock :plain? true}
                         (fn [emit]
                           (emit {:event :run-started :run-id "r" :cwd "/w" :base "main" :at "2026-06-30T14:00:00Z"})
                           (emit {:event :phase-started :iter 1 :phase :review :at "2026-06-30T14:00:01Z"})
                           (emit {:event :phase-finished :iter 1 :phase :review :at "2026-06-30T14:00:02Z"
                                  :ctx {:findings []}})
                           (emit {:event :run-finalized :status :clean :ctx {} :at "2026-06-30T14:00:03Z"})
                           :ok))]
                 (is (= :ok v))))]
    (is (= "clean" (:status @a)))
    (is (re-find #"review" out) "plain mode narrates phases to stdout")))
