(ns nido.io-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.io :as io]))

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
