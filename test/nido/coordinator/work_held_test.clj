(ns nido.coordinator.work-held-test
  "The held model: a process that holds its rows answers every board reading from them, and
   only a list refresh, a selection refresh or the sweep moves them — each reaching only the
   rows it is meant to."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.record.activity :as activity]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as workstream]
   [nido.coordinator.source.notion-cache :as notion-cache]
   [nido.coordinator.work :as work]
   [nido.platform.core :as core]
   [nido.platform.project :as project]))

(defn- with-store
  "Run `f` against an empty store holding project :p, with `live` the session names the
   registry shows up, and this process holding its rows with no sweep thread — the test drives
   `work/sweep!` itself. Releases before the store goes, so nothing held outlives it."
  [live f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root                    (constantly (str tmp))
                    project/list-projects             (constantly {:p {}})
                    work/live-session-names           (fn [_] @live)
                    notion-cache/project-page-facts   (constantly {})]
        (cstate/ensure-dirs!)
        (try
          (f)
          (finally (work/release!))))
      (finally (fs/delete-tree tmp)))))

(defn- open-one! [label]
  (let [w (workstream/create! :p {:external-refs []})]
    (workstream/append-entry! :p (:id w) {:kind :scratch} (pr-str {:format :scratch}))
    (session/create! :p (:id w) {:name (str label "-s") :weight :light :autonomy nil})
    (:id w)))

(defn- settled-one! [label]
  (let [id (open-one! label)]
    (workstream/close! :p id :done)
    id))

(defn- reads-of
  "The workstream ids `f` reads the records of, in order."
  [f]
  (let [seen (atom [])
        read workstream/read-ws]
    (with-redefs [workstream/read-ws (fn [p id] (swap! seen conj id) (read p id))]
      (f))
    @seen))

(defn- tick
  "Let the filesystem clock move, so a write after a read moves the directory's mtime."
  []
  (Thread/sleep 15))

(deftest holding-answers-what-reading-answers
  (let [live (atom #{"c-s"})]
    (with-store live
      (fn []
        (let [_a (open-one! "a")
              _b (settled-one! "b")
              _c (settled-one! "c")
              read-grouped (work/grouped :p)
              read-rows    (work/list-workstreams :p)
              read-gates   (work/gates :p)]
          (work/hold! {:sweep? false})
          (is (work/holding?))
          (is (= (set read-rows) (set (work/list-workstreams :p)))
              "the same rows, read once into the model")
          (is (= read-gates (work/gates :p)))
          (is (= (dissoc read-grouped :winding-down) (dissoc (work/grouped :p) :winding-down)))
          (is (= (set (:winding-down read-grouped)) (set (:winding-down (work/grouped :p))))
              "a finished workstream holding a live session winds down either way"))))))

(deftest a-reading-never-refreshes
  (let [live (atom #{})]
    (with-store live
      (fn []
        (let [a (open-one! "a")]
          (work/hold! {:sweep? false})
          (work/grouped :p)
          (tick)
          (workstream/append-entry! :p a {:kind :note} (pr-str {:format :note :text "x"}))
          (is (empty? (reads-of #(do (work/grouped :p) (work/gates :p) (work/list-workstreams :p)
                                     (work/board-stamp :p))))
              "renders read what is held; only a refresh reads a record"))))))

(deftest a-list-refresh-reaches-the-unsettled-and-not-closed-history
  (let [live (atom #{})]
    (with-store live
      (fn []
        (let [a (open-one! "a")
              b (settled-one! "b")]
          (work/hold! {:sweep? false})
          (work/grouped :p)
          (tick)
          (workstream/append-entry! :p a {:kind :note} (pr-str {:format :note :text "x"}))
          (workstream/append-entry! :p b {:kind :note} (pr-str {:format :note :text "x"}))
          (let [reads (set (reads-of #(work/refresh-list! :p)))]
            (is (contains? reads a) "the open workstream whose stamp moved is recomputed")
            (is (not (contains? reads b)) "the settled one is the sweep's alone"))
          (reset! live #{"b-s"})
          (is (contains? (set (reads-of #(work/refresh-list! :p))) b)
              "a settled workstream with a live session is reached by a list refresh"))))))

(deftest a-list-refresh-keeps-a-row-whose-stamp-did-not-move
  (let [live (atom #{})]
    (with-store live
      (fn []
        (let [a (open-one! "a")]
          (work/hold! {:sweep? false})
          (work/grouped :p)
          (is (empty? (reads-of #(work/refresh-list! :p)))
              "nothing moved and no liveness came due: nothing is read")
          (is (= [a] (mapv :ws-id (:in-progress (work/grouped :p))))))))))

(deftest a-new-workstream-is-held-at-the-next-list-refresh
  (let [live (atom #{})]
    (with-store live
      (fn []
        (open-one! "a")
        (work/hold! {:sweep? false})
        (work/grouped :p)
        (tick)
        (let [b (open-one! "b")]
          (is (not-any? #(= b (:ws-id %)) (work/list-workstreams :p)) "not until a refresh")
          (work/refresh-list! :p)
          (is (some #(= b (:ws-id %)) (work/list-workstreams :p))))))))

(deftest a-selection-refresh-reaches-the-selected-workstream-alone
  (let [live (atom #{})]
    (with-store live
      (fn []
        (let [a (open-one! "a")
              b (open-one! "b")]
          (work/hold! {:sweep? false})
          (work/grouped :p)
          (tick)
          (workstream/append-entry! :p a {:kind :note} (pr-str {:format :note :text "x"}))
          (workstream/append-entry! :p b {:kind :note} (pr-str {:format :note :text "x"}))
          (is (= #{a} (set (reads-of #(work/refresh-selected! :p a))))))))))

(deftest the-sweep-reaches-settled-rows-and-recomputes-what-moved
  (let [live (atom #{})]
    (with-store live
      (fn []
        (let [b (settled-one! "b")]
          (work/hold! {:sweep? false})
          (work/grouped :p)
          (tick)
          (workstream/reopen! :p b :in-progress)
          (work/refresh-list! :p)
          (is (= :done (:stage (first (filter #(= b (:ws-id %)) (work/list-workstreams :p)))))
              "a list refresh does not reach the settled row")
          ;; Every row comes due: the sweep reaches each, and recomputes the one whose stamp moved.
          (with-redefs [work/liveness-interval-ms 0]
            (work/sweep!))
          (is (not= :done (:stage (first (filter #(= b (:ws-id %)) (work/list-workstreams :p)))))
              "the sweep found it reopened; list refreshes reach it from now on"))))))

(deftest a-settled-row-that-came-due-is-probed-not-reread
  (let [live (atom #{})]
    (with-store live
      (fn []
        (let [b (settled-one! "b")]
          (work/hold! {:sweep? false})
          (work/grouped :p)
          (with-redefs [work/liveness-interval-ms 0]
            (is (not-any? #{b} (reads-of #(work/sweep!)))
                "its stamp and its probe are unchanged, so its records are not read")))))))

(deftest board-stamp-moves-only-when-a-row-did
  (let [live (atom #{})]
    (with-store live
      (fn []
        (let [a (open-one! "a")]
          (work/hold! {:sweep? false})
          (let [s0 (work/board-stamp :p)]
            (work/refresh-list! :p)
            (is (= s0 (work/board-stamp :p)) "a refresh that changed nothing leaves it")
            (tick)
            (workstream/close! :p a :done)
            (work/refresh-list! :p)
            (is (not= s0 (work/board-stamp :p)))))))))

(deftest casualties-shown-are-the-sweeps
  (let [live  (atom #{})
        calls (atom 0)]
    (with-store live
      (fn []
        (with-redefs [work/owed-revivals (fn [] (swap! calls inc) {:casualties [{:n @calls}] :unreadable []})]
          (work/hold! {:sweep? false})
          (is (= [{:n 1}] (:casualties (work/shown-revivals))) "the first render waits on one reading")
          (is (= [{:n 1}] (:casualties (work/shown-revivals))) "and then renders read the held copy")
          (work/sweep!)
          (is (= [{:n 2}] (:casualties (work/shown-revivals))) "only the sweep recomputes it"))))))

(deftest not-holding-reads-the-disk
  (let [live (atom #{})]
    (with-store live
      (fn []
        (let [a (open-one! "a")]
          (work/grouped :p)
          (tick)
          (workstream/close! :p a :done)
          (is (empty? (:in-progress (work/grouped :p)))
              "a process that does not hold sees every write at once, as before"))))))

(deftest an-open-row-that-came-due-is-reread-only-when-its-probe-moved
  (let [live (atom #{})]
    (with-store live
      (fn []
        (let [a (open-one! "a")]
          (work/hold! {:sweep? false})
          (work/grouped :p)
          (with-redefs [work/liveness-interval-ms 0]
            (is (empty? (reads-of #(work/refresh-list! :p)))
                "due, but nothing a file records moved and nothing it observes changed")
            (with-redefs [activity/held? (fn [_ id] (= id a))]
              (is (= [a] (distinct (reads-of #(work/refresh-list! :p))))
                  "a claim taken under it is a change only liveness sees: the row is read again"))))))))

;; ── phase 2: the snapshot a cold start seeds from ───────────────────────────

(defn- snapshot-file [] (#'work/snapshot-path "p"))

(deftest the-sweep-writes-a-snapshot-a-cold-start-seeds-from
  (let [live (atom #{})]
    (with-store live
      (fn []
        (let [a (open-one! "a")
              _ (settled-one! "b")]
          (work/hold! {:sweep? false})
          (work/sweep!)
          (is (fs/exists? (snapshot-file)) "the sweep wrote it")
          (let [rows (set (work/list-workstreams :p))]
            (work/release!)
            (work/hold! {:sweep? false})
            (is (empty? (reads-of #(work/list-workstreams :p)))
                "a cold start seeds from the snapshot without reading a workstream")
            (is (= rows (set (work/list-workstreams :p))) "and holds the same rows")
            (is (some #(= a (:ws-id %)) (:in-progress (work/grouped :p))))))))))

(deftest seeding-keeps-when-liveness-was-observed
  (let [live (atom #{})]
    (with-store live
      (fn []
        (open-one! "a")
        (work/hold! {:sweep? false})
        (work/sweep!)
        (let [observed (->> (get-in @@#'work/model [:projects "p" :entries]) vals (map :observed-at) set)]
          (work/release!)
          (work/hold! {:sweep? false})
          (work/grouped :p)
          (is (= observed (->> (get-in @@#'work/model [:projects "p" :entries]) vals (map :observed-at) set))
              "neither writing nor seeding renews a row's liveness"))))))

(deftest a-seeded-row-whose-stamp-moved-is-recomputed
  (let [live (atom #{})]
    (with-store live
      (fn []
        (let [a (open-one! "a")]
          (work/hold! {:sweep? false})
          (work/sweep!)
          (work/release!)
          (tick)
          (workstream/close! :p a :done)
          (work/hold! {:sweep? false})
          (work/refresh-list! :p)
          (is (empty? (:in-progress (work/grouped :p)))
              "the snapshot is a starting point, held as any row is: its stamp moved"))))))

(deftest an-unusable-snapshot-is-discarded-whole
  (doseq [[why spoil] [["does not parse"          #(spit (str (snapshot-file)) "{not transit")]
                       ["another nido revision"   #(with-redefs [work/source-revision (delay "elsewhere")]
                                                     (#'work/write-snapshot! "p" (get-in @@#'work/model [:projects "p"])))]]]
    (let [live (atom #{})]
      (with-store live
        (fn []
          (open-one! "a")
          (work/hold! {:sweep? false})
          (work/sweep!)
          (spoil)
          (work/release!)
          (work/hold! {:sweep? false})
          (is (seq (reads-of #(work/list-workstreams :p))) (str "a snapshot that " why " is rebuilt from the records"))
          (is (not (fs/exists? (snapshot-file))) (str "and one that " why " is deleted")))))))
