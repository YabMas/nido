(ns nido.platform.io-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.platform.io :as io]))

(deftest write-edn!-round-trip
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [p (str (fs/path tmp "foo.edn"))]
        (io/write-edn! p {:x 1 :y :ok})
        (is (= {:x 1 :y :ok} (io/read-edn p))))
      (finally (fs/delete-tree tmp)))))

(deftest write-edn!-overwrites-cleanly
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [p (str (fs/path tmp "foo.edn"))]
        (io/write-edn! p {:v 1})
        (io/write-edn! p {:v 2})
        (is (= {:v 2} (io/read-edn p))))
      (finally (fs/delete-tree tmp)))))

(deftest write-edn!-leaves-no-tmp-trail
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [p (str (fs/path tmp "foo.edn"))]
        (io/write-edn! p {:v 1})
        (is (false? (fs/exists? (str p ".tmp")))
            "atomic rename should remove the tmp sibling"))
      (finally (fs/delete-tree tmp)))))

(deftest write-edn-roundtrips
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [p (str (fs/path tmp "a.edn"))]
        (io/write-edn! p {:x 1 :y [2 3]})
        (is (= {:x 1 :y [2 3]} (io/read-edn p))))
      (finally (fs/delete-tree tmp)))))

(deftest write-edn-concurrent-same-path-no-rename-race
  ;; Many threads hammering the SAME path must not throw on the rename, and the
  ;; final file must be valid EDN. With a fixed <path>.tmp this races and throws;
  ;; with a unique temp name it is safe.
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [p      (str (fs/path tmp "reg.edn"))
            errors (atom [])
            tasks  (mapv (fn [i]
                           (future
                             (try (dotimes [_ 25] (io/write-edn! p {:w i}))
                                  (catch Throwable t (swap! errors conj t)))))
                         (range 40))]
        (run! deref tasks)
        (is (empty? @errors) (str "concurrent writes raced: "
                                  (mapv #(.getMessage ^Throwable %) @errors)))
        (is (map? (io/read-edn p)) "final file is valid EDN"))
      (finally (fs/delete-tree tmp)))))
