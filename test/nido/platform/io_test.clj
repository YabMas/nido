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

;; ── update-edn! ─────────────────────────────────────────────────────────────

(deftest update-edn!-applies-and-returns
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [p (str (fs/path tmp "m.edn"))]
        (io/write-edn! p {:n 1})
        (is (= {:n 2} (io/update-edn! p #(update % :n inc))))
        (is (= {:n 2} (io/read-edn p))))
      (finally (fs/delete-tree tmp)))))

(deftest update-edn!-sees-nil-for-a-missing-file
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [p (str (fs/path tmp "absent.edn"))]
        (is (= {:seeded true} (io/update-edn! p #(or % {:seeded true}))))
        (is (= {:seeded true} (io/read-edn p))))
      (finally (fs/delete-tree tmp)))))

(deftest update-edn!-throwing-f-writes-nothing
  ;; A refusal expressed by throwing INSIDE the update must leave the file as it
  ;; was, not rewrite it with what it already held.
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [p (str (fs/path tmp "m.edn"))]
        (io/write-edn! p {:n 1})
        (is (thrown? Exception (io/update-edn! p (fn [_] (throw (ex-info "no" {}))))))
        (is (= {:n 1} (io/read-edn p))))
      (finally (fs/delete-tree tmp)))))

(deftest update-edn!-concurrent-updaters-lose-nothing
  ;; The lost update, which is what this exists to prevent: 40 writers each
  ;; adding one distinct key to the same map. Read-then-write drops most of
  ;; them and leaves a perfectly well-formed file behind; every one must
  ;; survive here.
  (let [tmp (fs/create-temp-dir)]
    (try
      (let [p     (str (fs/path tmp "reg.edn"))
            tasks (mapv (fn [i]
                          (future (io/update-edn! p #(assoc (or % {}) i true))))
                        (range 40))]
        (run! deref tasks)
        (is (= (set (range 40)) (set (keys (io/read-edn p))))))
      (finally (fs/delete-tree tmp)))))

(deftest lock-path-for-is-a-hidden-sibling
  ;; Same file, same lock name, from any process — and out of the way of anything
  ;; listing the directory.
  (is (= "/a/b/.links.edn.lock" (io/lock-path-for "/a/b/links.edn")))
  (is (= (io/lock-path-for "/a/b/links.edn") (io/lock-path-for "/a/b/links.edn"))))
