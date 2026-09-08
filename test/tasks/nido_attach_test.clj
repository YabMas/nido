;; test/tasks/nido_attach_test.clj
(ns tasks.nido-attach-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.coordinator.lane.drive :as drive]
   [nido.coordinator.lane.pipeline :as pipeline]
   [nido.coordinator.record.activity :as activity]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.core :as core]
   [nido.review.frontend :as frontend]
   [tasks.nido-attach :as attach]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try
      (with-redefs [core/nido-root (constantly (str tmp))]
        (f))
      (finally (fs/delete-tree tmp)))))

(def ^:private a-baseline
  {:format :baseline :area "a" :bounded-by "b" :shape "s"
   :modules [{:id "m" :module "m" :hides "h" :interface "i"}]
   :composition "c"
   :load-bearing [{:id "c1" :property "p" :falsified-by "f"}]
   :read ["src/a.clj"]})

(defn- a-ws []
  (:id (ws/create! :brian {:stage :in-progress :external-refs []})))

(defn- baselined! [id]
  (ws/append-entry! :brian id {:kind :intent}
                    (pr-str {:format :intent :goal "g" :done-when ["d"]}))
  (ws/append-entry! :brian id {:kind :baseline} (pr-str a-baseline)))

;; ── The door names no stage ─────────────────────────────────────────────────

(deftest attach-runs-what-the-position-names-without-being-told
  (with-tmp
    (fn []
      (let [id (a-ws)
            ran (atom nil)]
        (baselined! id)
        (with-redefs [activity/read-live (constantly nil)
                      drive/run-stage! (fn [_p _w stage & _]
                                         (reset! ran stage)
                                         {:outcome :sufficient})]
          (is (= :sufficient (attach/attach! :brian id "/wt/impl-1")))
          (is (= :verify-baseline @ran)
              "the ledger chose it — nothing in the call said which stage"))))))

(deftest the-stage-runs-in-the-worktree-the-caller-is-standing-in
  ;; Left to itself run-stage! takes the first session list-sessions hands it,
  ;; ordered by nothing and filtered by liveness not at all. The door already
  ;; resolved a worktree to find the workstream; the stage is for that one.
  (with-tmp
    (fn []
      (let [id (a-ws)
            against (atom nil)]
        (baselined! id)
        (with-redefs [activity/read-live (constantly nil)
                      drive/run-stage! (fn [_p _w _stage opts]
                                         (reset! against (:cwd opts))
                                         {:outcome :sufficient})]
          (is (= :sufficient (attach/attach! :brian id "/wt/the-one-i-am-in")))
          (is (= "/wt/the-one-i-am-in" @against)
              "the resolved worktree reaches the runner rather than being derived again"))))))

(deftest attach-follows-a-live-holder-whatever-it-is-doing
  ;; No kind-and-target comparison: attach named no work, so any live work is
  ;; the answer to what it asked. join-or-refuse!'s comparison protects a caller
  ;; who NAMED work, and this caller named none.
  (with-tmp
    (fn []
      (let [id (a-ws)
            followed (atom nil)
            holder {:kind :design-round :target {:seq 9} :run-id "r-1"
                    :report-path "/nowhere/report.json"
                    :started-at "2026-09-08T00:00:00Z" :pid 1}]
        (baselined! id)
        (with-redefs [activity/read-live (constantly holder)
                      attach/follow-holder! (fn [_p _w h]
                                              (reset! followed (:run-id h))
                                              :converged)
                      drive/run-stage! (fn [& _] (throw (ex-info "must not run" {})))]
          (is (= :converged (attach/attach! :brian id "/wt/impl-1")))
          (is (= "r-1" @followed)
              "the position says :verify-baseline and a design round is live — it still follows"))))))

(deftest attach-starts-nothing-when-a-person-owes-the-next-move
  (with-tmp
    (fn []
      (let [id (a-ws)]
        (baselined! id)
        (ws/append-entry! :brian id {:kind :blocker}
                          (pr-str {:format :blocker :summary "s" :needs "n"}))
        (with-redefs [activity/read-live (constantly nil)
                      drive/run-stage! (fn [& _] (throw (ex-info "must not run" {})))]
          (is (= :waiting-on-a-human (attach/attach! :brian id "/wt/impl-1"))))))))

(deftest a-holder-that-appears-after-the-read-is-followed-not-refused
  ;; The claim, not the read, is what stops a second run. Losing it comes back
  ;; as {:skip :claimed}, and the answer to that is to read again and follow.
  (with-tmp
    (fn []
      (let [id (a-ws)
            reads (atom [nil {:kind :diff-review :run-id "r-2" :pid 2
                              :report-path "/nowhere/report.json"
                              :started-at "2026-09-08T00:00:00Z"}])]
        (baselined! id)
        (with-redefs [activity/read-live (fn [_ _] (let [[h & t] @reads]
                                                     (when t (reset! reads t))
                                                     h))
                      drive/run-stage! (fn [& _] {:skip :claimed})
                      attach/follow-holder! (fn [_p _w h] (keyword (str "followed-" (:run-id h))))]
          (is (= :followed-r-2 (attach/attach! :brian id "/wt/impl-1"))))))))

(deftest a-holder-replaced-mid-follow-is-followed-not-reported-as-this-attachs-answer
  ;; read-live may hand back the PREVIOUS holder's payload while a replacement
  ;; takes the claim and before it publishes, so following can stop on a run
  ;; that reached its verdict before this attach existed. Reporting that
  ;; verdict would answer with work nobody asked for — and a door that named
  ;; none has somewhere to go instead: the run that holds the claim now.
  (with-tmp
    (fn []
      (let [id      (a-ws)
            stale   {:kind :diff-review :run-id "r-1" :pid 1
                     :report-path "/nowhere/r-1.json"
                     :started-at "2026-09-08T00:00:00Z"}
            live    {:kind :diff-review :run-id "r-2" :pid 2
                     :report-path "/nowhere/r-2.json"
                     :started-at "2026-09-08T00:01:00Z"}
            painted (atom [])]
        (with-redefs [frontend/follow! (fn [{:keys [report-path]}]
                                         (swap! painted conj report-path))
                      frontend/read-report (fn [p] {:status (if (= p "/nowhere/r-1.json")
                                                              "converged"
                                                              "clean")})
                      ;; The claim is r-2's once r-1's frames stop, and free
                      ;; once r-2's do.
                      activity/read-live (fn [_ _] (when (= 1 (count @painted)) live))]
          (is (= :clean (attach/follow-holder! :brian id stale))
              "r-1's terminal report was never this invocation's to report")
          (is (= ["/nowhere/r-1.json" "/nowhere/r-2.json"] @painted)
              "the replacement is followed, not merely noticed"))))))

;; ── What a person is told when nothing will run ─────────────────────────────

(deftest a-human-stage-names-the-gate-rather-than-a-command-that-would-run-it
  (with-tmp
    (fn []
      (let [id (a-ws)]
        (baselined! id)
        (ws/append-entry! :brian id {:kind :blocker}
                          (pr-str {:format :blocker :summary "s" :needs "n"}))
        (let [pos (pipeline/of :brian id)
              out (str/join "\n" (attach/skip-lines :brian id pos (drive/fireable pos)))]
          (is (str/includes? out "answer-blocker"))
          (is (str/includes? out "bb nido:ui"))
          (is (not (str/includes? out "session:enter"))
              "a decision is not a turn in the tree"))))))

(deftest a-working-copy-stage-names-the-session-to-take-it-in
  (with-tmp
    (fn []
      (let [id (a-ws)]
        (session/create! :brian id {:name "impl-1" :weight :heavy :autonomy nil})
        (baselined! id)
        (ws/append-entry! :brian id {:kind :baseline-review}
                          (pr-str {:format :baseline-review :baseline-seq 2
                                   :verdict :sufficient :reason "r"}))
        (ws/append-entry! :brian id {:kind :design}
                          (pr-str {:format :design :summary "s" :shape "sh"
                                   :invariants ["i"] :standing {:relation :conforms}
                                   :baseline {:seq 2 :relation :within}
                                   :intent {:seq 1} :effort :S}))
        (ws/append-entry! :brian id {:kind :design-decision}
                          (pr-str {:format :design-decision :design-seq 4
                                   :recommend :proceed :reason "r"
                                   :checks [{:check :decomposable :status :held :note "n"}]
                                   :asks "worth it?"}))
        (ws/append-entry! :brian id {:kind :design-approved}
                          (pr-str {:format :design-approved :design {:seq 4} :at-seq 5}))
        (let [pos (pipeline/of :brian id)
              out (str/join "\n" (attach/skip-lines :brian id pos (drive/fireable pos)))]
          (is (str/includes? out "implement"))
          (is (str/includes? out "bb nido:session:enter :project brian impl-1")))))))

(deftest an-unrunnable-mechanical-stage-says-so-rather-than-going-quiet
  (with-tmp
    (fn []
      (let [id (a-ws)]
        (baselined! id)
        (with-redefs [drive/mechanical-stages {}]
          (let [pos (pipeline/of :brian id)
                out (str/join "\n" (attach/skip-lines :brian id pos (drive/fireable pos)))]
            (is (str/includes? out "verify-baseline"))
            (is (str/includes? out "mechanical-stages"))))))))
