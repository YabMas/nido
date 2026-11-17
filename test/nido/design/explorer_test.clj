(ns nido.design.explorer-test
  "Running fukan's explorer, exercised without a JVM: `:cmd` is configuration, so a shell script
   stands in for `fukan.cli explore` and announces — or fails to — the way it would."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.design.explorer :as explorer]
   [nido.platform.project :as project]))

(defn- canvas-dir []
  (let [d (fs/create-temp-dir)]
    (fs/create-dirs (fs/path d "canvas"))
    (spit (str (fs/path d "canvas" "bands.clj")) "(ns canvas.bands)")
    d))

(defn- settled
  "The handle's address once it stops answering `:starting`, or `:starting` after ~5s."
  [h]
  (loop [n 50]
    (let [a (explorer/address h)]
      (if (and (= :starting (:status a)) (pos? n))
        (do (Thread/sleep 100) (recur (dec n)))
        a))))

(deftest the-announced-port-is-where-it-serves
  (let [d (canvas-dir)]
    (try
      (with-redefs [project/get-project
                    (constantly {:design {:cmd ["sh" "-c" "echo 'fukan explorer: http://localhost:4711/'; sleep 30" "--"]}})]
        (let [h (explorer/start! "p" d)]
          (try
            (is (= {:status :serving :port 4711} (settled h)))
            (finally (explorer/stop! h)))))
      (finally (fs/delete-tree d)))))

(deftest an-explorer-that-exits-unannounced-failed-and-says-why
  (let [d (canvas-dir)]
    (try
      (with-redefs [project/get-project
                    (constantly {:design {:cmd ["sh" "-c" "echo 'the canvas would not load' >&2; exit 2" "--"]}})]
        (let [a (settled (explorer/start! "p" d))]
          (is (= :failed (:status a)))
          (is (= "the canvas would not load" (:error a)))))
      (finally (fs/delete-tree d)))))

(deftest a-directory-with-no-declaration-starts-nothing
  (testing "unmodelled is not a failure: there is simply nothing to draw"
    (let [d (fs/create-temp-dir)]
      (try
        (with-redefs [project/get-project (constantly nil)]
          (is (nil? (explorer/start! "p" d))))
        (finally (fs/delete-tree d))))))
