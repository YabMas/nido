;; test/tasks/nido_attach_test.clj
(ns tasks.nido-attach-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
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
  ;; :intent names entry 1: these fixtures append the goal to a fresh
  ;; workstream before the survey, which is the order the boundary now requires.
  {:format :baseline :strata [] :intent {:seq 1}
   :area "a" :bounded-by "b" :shape "s"
   :model {:elements [{:id "m" :sort :module :hides "h" :interface "i"}]
           :claims [{:id "c1" :about ["m"] :statement "p" :falsified-by "f"
                     :evidence {:by :round}}]}
   :read ["src/a.clj"]})

(defn- a-ws []
  (:id (ws/create! :brian {:external-refs []})))

(defn- baselined! [id]
  (ws/append-entry! :brian id {:kind :intent}
                    (pr-str {:format :intent :goal "g" :done-when ["d"]}))
  (ws/append-entry! :brian id {:kind :baseline} (pr-str a-baseline)))

;; ── The door names no stage ─────────────────────────────────────────────────

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
                                              :converged)]
          (is (= :converged (attach/attach! :brian id "/wt/impl-1")))
          (is (= "r-1" @followed)
              "the position says :verify-baseline and a design round is live — it still follows"))))))

(deftest attach-starts-nothing-when-a-person-owes-the-next-move
  (with-tmp
    (fn []
      (let [id (a-ws)]
        (baselined! id)
        (ws/append-entry! :brian id {:kind :blocker}
                          (pr-str {:format :blocker :summary "s" :needs "n?" :after "The work resumes once you answer."}))
        (with-redefs [activity/read-live (constantly nil)]
          (is (= :waiting-on-a-human (attach/attach! :brian id "/wt/impl-1"))))))))

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
                          (pr-str {:format :blocker :summary "s" :needs "n?" :after "The work resumes once you answer."}))
        (let [pos (pipeline/of :brian id)
              out (str/join "\n" (attach/skip-lines :brian id pos {:skip (#'attach/owed pos)}))]
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
                          (pr-str {:format :design :strata [] :summary "s" :shape "sh"
                                   :model {:elements [{:id "m" :sort :module}]
                                           :claims [{:id "i" :about ["m"] :statement "i"
                                                     :falsified-by "f" :evidence {:by :round}}]}
                                   :standing {:relation :conforms}
                                   :baseline {:seq 2 :relation :within}
                                   :intent {:seq 1} :effort :S}))
        (ws/append-decision! :brian id
                          (pr-str {:format :design-decision :design-seq 4
                                   :recommend :proceed :reason "r"
                                   :checks [{:check :decomposable :status :held :note "n"}]
                                   :asks "worth it?"}))
        (ws/append-entry-at! :brian id (count (:entries (ws/read-ws :brian id))) {:kind :design-cleared}
                             (pr-str {:format :design-cleared :design {:seq 4}}))
        (let [pos (pipeline/of :brian id)
              out (str/join "\n" (attach/skip-lines :brian id pos {:skip (#'attach/owed pos)}))]
          (is (str/includes? out "implement"))
          (is (str/includes? out "bb nido:session:enter :project brian impl-1")))))))

(deftest a-moved-goal-names-every-record-the-amendment-owes
  ;; The position's next stage is only the first link; an author told only that
  ;; learns the rest one refusal at a time.
  (with-tmp
    (fn []
      (let [id (a-ws)]
        (baselined! id)                                                          ; 1, 2
        (ws/append-entry! :brian id {:kind :baseline-review}                     ; 3
                          (pr-str {:format :baseline-review :baseline-seq 2
                                   :verdict :sufficient :reason "r"}))
        (ws/append-entry! :brian id {:kind :intent}                              ; 4
                          (pr-str {:format :intent :goal "wider" :done-when ["d"]
                                   :supersedes {:seq 1 :why "moved"}}))
        (let [pos (pipeline/of :brian id)
              out (attach/skip-lines :brian id pos {:skip (#'attach/owed pos)})]
          (is (= :goal-superseded (:at pos)))
          (is (= (attach/amendment-lines (:owed (:next pos)))
                 (filterv #(or (str/starts-with? % "The goal amended") (re-find #"^  \d+\. " %)) out))
              "the position's chain, whole")
          (is (= 4 (count (filter #(re-find #"^  \d+\. " %) out)))))))))

(deftest an-amendment-is-worded-as-its-whole-chain
  (is (= ["The goal amended to entry 4 still owes, in order:"
          "  1. a :baseline with :intent {:seq 4} and :supersedes {:seq 2 :why \"…\"}"
          "  2. bb nido:review:baseline — until it answers `sufficient`"
          "  3. a :design with :baseline {:seq <that baseline>} and :intent {:seq 4}, and :supersedes {:seq 3 :why \"…\"}"
          "  4. bb nido:review:design — until it proceeds, which clears it"]
         (attach/amendment-lines [{:stage :rebaseline :intent 4 :supersedes 2}
                                  {:stage :verify-baseline :intent 4 :baseline nil}
                                  {:stage :design :intent 4 :baseline nil :supersedes 3}
                                  {:stage :decide-design :intent 4 :design nil}])))
  (is (= ["The goal amended to entry 4 still owes, in order:"
          "  1. bb nido:review:design (it decides the newest design, entry 7) — until it proceeds, which clears it"]
         (attach/amendment-lines [{:stage :decide-design :intent 4 :design 7}])))
  (is (= [] (attach/amendment-lines []))))

(deftest a-mechanical-stage-is-the-conductors
  (with-tmp
    (fn []
      (let [id (a-ws)]
        (baselined! id)
        (with-redefs [activity/read-live (constantly nil)]
          (let [out (with-out-str (is (= :conducted (attach/attach! :brian id "/wt/impl-1"))))]
            (is (str/includes? out "verify-baseline"))
            (is (str/includes? out "conductor"))))))))
