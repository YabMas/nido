(ns tasks.nido-workstream-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.work]
   [tasks.nido-workstream :as task]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f tmp))
         (finally (fs/delete-tree tmp)))))

(deftest stage-advance-routes-through-set-stage
  (let [calls (atom [])]
    (with-redefs [nido.coordinator.work/set-stage! (fn [p w t] (swap! calls conj [p w t]) {:decision :advanced})]
      (#'tasks.nido-workstream/stage-advance*
        {:project "brian" :ws-id "ws-1" :stage "in-progress"})
      (is (= [[:brian "ws-1" :in-progress]] @calls)
          "delegates to work/set-stage! so :in-progress provisions the planning leg"))))

(deftest entry-add-stage-advance-close
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-3"}]})]
        (task/entry-add* {:project "brian" :ref "BR-3" :kind "note" :content "found a bug"})
        (let [w2 (ws/read-ws :brian (:id w))]
          (is (= 1 (count (:entries w2))))
          (is (= :note (-> w2 :entries first :kind))))
        (task/stage-advance* {:project "brian" :ref "BR-3" :stage "ready"})
        (is (= :ready (:stage (ws/newest-choice (ws/read-ws :brian (:id w))))))
        (task/close* {:project "brian" :ref "BR-3" :outcome "done"})
        (is (= :done (:outcome (ws/closure (ws/read-ws :brian (:id w))))))))))

(deftest an-intent-amending-a-built-on-goal-is-followed-by-what-it-owes
  ;; The append that starts an amendment is the first moment its author can be
  ;; told the whole chain, rather than learning it one refusal at a time.
  (with-tmp
    (fn [_]
      (let [w   (ws/create! :brian {:external-refs []})
            add #(task/entry-add* {:project "brian" :ws-id (:id w) :kind %1 :content (pr-str %2)})]
        (add "intent" {:format :intent :goal "g" :done-when ["d"]})
        (add "baseline" {:format :baseline :strata [] :intent {:seq 1}
                         :area "a" :bounded-by "b" :shape "s"
                         :model {:elements [{:id "m" :sort :module :hides "h" :interface "i"}]
                                 :claims [{:id "c1" :about ["m"] :statement "p"
                                           :falsified-by "f" :evidence {:by :round}}]}
                         :read ["src/a.clj"]})
        (let [out (with-out-str
                    (add "intent" {:format :intent :goal "g2" :done-when ["d"]
                                   :supersedes {:seq 1 :why "moved"}}))]
          (is (str/includes? out "The goal amended to entry 3 still owes, in order:"))
          (is (str/includes? out ":supersedes {:seq 2")))
        (let [opts {:project "brian" :ws-id (:id w)}
              e    (try (add "baseline-review" {:format :baseline-review :baseline-seq 2
                                                :verdict :sufficient :reason "holds"})
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
          (is (= "The goal amended to entry 3 still owes, in order:"
                 (first (#'task/refusal-lines opts e)))
              "a refusal over the replaced goal is followed by what its unit owes"))))))

(deftest stage-advance-refuses-a-stage-outside-the-vocabulary
  (with-tmp
    (fn [_]
      (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-3"}]})
      ;; :implementing is a ticket status. The CLI keywordizes whatever you type,
      ;; so this is the surface a stage nothing projects arrived through.
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo #"Unknown workstream stage"
            (task/stage-advance* {:project "brian" :ref "BR-3" :stage "implementing"})))
      (is (nil? (ws/newest-choice (ws/read-ws :brian (:id (ws/find-by-ref :brian :notion "BR-3")))))
          "the refused stage is not recorded"))))

(deftest ref-add-stamps-github-ref
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-9"}]})]
        (task/ref-add* {:project "brian" :ref "BR-9"
                        :adapter "github" :id "brian-study/brian#412"
                        :url "https://github.com/brian-study/brian/pull/412"
                        :title "Fix X"})
        (let [refs (:external-refs (ws/read-ws :brian (:id w)))]
          (is (= 2 (count refs)))
          (is (some #(and (= :github (:adapter %))
                          (= "brian-study/brian#412" (:id %))
                          (= "https://github.com/brian-study/brian/pull/412" (:url %)))
                    refs)))
        ;; idempotent: same id ⇒ no duplicate
        (task/ref-add* {:project "brian" :ref "BR-9"
                        :adapter "github" :id "brian-study/brian#412"})
        (is (= 2 (count (:external-refs (ws/read-ws :brian (:id w))))))))))

(deftest ref-add-files-the-pr-opened-event
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-9"}]})]
        (task/ref-add* {:project "brian" :ref "BR-9"
                        :adapter "github" :id "brian-study/brian#412"
                        :url "https://gh/412" :title "Fix X"
                        :summary "collapses the two writers into one"})
        (is (= {:format :pr-opened :url "https://gh/412" :title "Fix X"
                :summary "collapses the two writers into one"}
               (dissoc (ws/latest-entry :brian (:id w) :pr-opened) :seq :at)))
        ;; the stack case: later layers stamp refs and stay silent
        (task/ref-add* {:project "brian" :ref "BR-9"
                        :adapter "github" :id "brian-study/brian#413"
                        :url "https://gh/413" :title "layer 2"})
        (is (= 1 (count (filter #(= :pr-opened (:kind %))
                                (:entries (ws/read-ws :brian (:id w))))))
            "one event per shipment, not one per layer")))))

(deftest ref-add-without-url-still-stamps-the-ref
  ;; PrOpened needs a :url and :title; correlation is what the merge poller
  ;; needs, so a malformed event must never cost the ref.
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-9"}]})]
        (task/ref-add* {:project "brian" :ref "BR-9"
                        :adapter "github" :id "brian-study/brian#414"})
        (let [w' (ws/read-ws :brian (:id w))]
          (is (some #(= "brian-study/brian#414" (:id %)) (:external-refs w')))
          (is (empty? (filter #(= :pr-opened (:kind %)) (:entries w')))))))))

(deftest ref-add-notion-files-nothing
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:external-refs []})]
        (task/ref-add* {:project "brian" :ws-id (:id w)
                        :adapter "notion" :id "BR-9" :url "https://notion/9" :title "t"})
        (is (empty? (:entries (ws/read-ws :brian (:id w)))))))))

;; The arg path, not the fn: every test above calls entry-add* with a map
;; already built, which is how a typed body could never survive `bb
;; nido:workstream:entry:add` without anyone noticing. split-args read the
;; leading brace as a map and handed entry-payload something it could not parse,
;; so the one documented route for a ledger-only workstream (/design §7) could
;; append notes and nothing else.
(def ^:private a-typed-event
  "Any registered kind proves the claim — that an EDN body survives the arg path.
   :blocker on purpose: the design schema is the one most likely to be tightened
   by later work, and a test down here has no business depending on that."
  {:format :blocker
   :summary "the importer bypasses the aggregate"
   :needs   "Should the importer go through the aggregate?"
   :after   "The importer is rewritten once you answer."})

(deftest entry-add-keeps-a-typed-body-intact-through-the-arg-path
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:external-refs []})]
        (task/entry-add ":project" "brian" ":ws-id" (:id w)
                        ":kind" "blocker" ":content" (pr-str a-typed-event))
        (is (= a-typed-event (dissoc (ws/latest-entry :brian (:id w) :blocker) :seq :at))
            "the EDN body round-trips: parsed, validated, and readable back")))))

(deftest entry-add-reads-a-typed-body-from-file
  (with-tmp
    (fn [tmp]
      (let [w    (ws/create! :brian {:external-refs []})
            path (str (fs/path tmp "blocker.edn"))]
        (spit path (pr-str a-typed-event))
        (task/entry-add ":project" "brian" ":ws-id" (:id w)
                        ":kind" "blocker" ":file" path)
        (is (= a-typed-event (dissoc (ws/latest-entry :brian (:id w) :blocker) :seq :at))
            ":file is the route a report of any real size has to take")))))

(deftest entry-add-still-appends-a-freeform-note
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:external-refs []})]
        (task/entry-add ":project" "brian" ":ws-id" (:id w)
                        ":kind" "note" ":content" "found a bug in the parser")
        (let [e (first (:entries (ws/read-ws :brian (:id w))))]
          (is (= :note (:kind e)))
          (is (= "found a bug in the parser"
                 (slurp (str (fs/path (cstate/workstream-dir :brian (:id w)) (:file e)))))
              "an untyped body is still stored verbatim, not EDN-mangled"))))))

(deftest ref-add-keeps-a-stack-pr-title-verbatim
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-77"}]})]
        ;; Through the CLI arg path, not ref-add* — the bug was in parsing.
        ;; "[2/2] …" read as EDN is a vector holding the ratio 2/2, i.e. [1],
        ;; and the Workstream schema then rejects the whole record. Every stack
        ;; PR above the bottom carries this title shape (/prepare-draft-pr §3).
        (task/ref-add ":project" "brian" ":ref" "BR-77"
                      ":adapter" "github" ":id" "brian-study/brian#4696"
                      ":url" "https://github.com/brian-study/brian/pull/4696"
                      ":title" "[2/2] feat(course): let a course be taught in the language it teaches")
        (let [r (->> (:external-refs (ws/read-ws :brian (:id w)))
                     (filter #(= :github (:adapter %)))
                     first)]
          (is (= "[2/2] feat(course): let a course be taught in the language it teaches"
                 (:title r)))
          (is (= "https://github.com/brian-study/brian/pull/4696" (:url r))))))))

(deftest ref-add-keeps-a-summary-that-opens-with-a-number
  (with-tmp
    (fn [_]
      (let [w (ws/create! :brian {:external-refs [{:adapter :notion :id "BR-78"}]})]
        ;; The symbol carve-out rescues prose starting with a letter; a summary
        ;; opening on a digit is read as a number and everything after it lost.
        (task/ref-add ":project" "brian" ":ref" "BR-78"
                      ":adapter" "github" ":id" "brian-study/brian#1"
                      ":url" "https://github.com/brian-study/brian/pull/1"
                      ":title" "Some PR"
                      ":summary" "2 layers: copy, then behaviour. Refs BR-78")
        (let [e (last (:entries (ws/read-ws :brian (:id w))))]
          (is (= :pr-opened (:kind e)))
          (is (str/includes?
                (slurp (str (fs/path (cstate/workstream-dir :brian (:id w)) (:file e))))
                "2 layers: copy, then behaviour")
              "the whole sentence survives, not the leading 2"))))))

(defn- list-out [rows & [opts]]
  (with-redefs [nido.coordinator.work/list-workstreams (fn [_] rows)]
    (str/split-lines (with-out-str (task/list* (merge {:project "nido"} opts))))))

(deftest list-prints-the-work-planes-reading-of-each-row
  ;; The band and who owes are printed as the listing answered them: nothing is created on disk,
  ;; so a line that derived either itself would have nothing to derive it from.
  (let [[line total] (list-out [{:ws-id "ws-a" :stage :in-progress :label "Build it"
                                 :status {:owed-by :agent} :needs-you false
                                 :last-activity "2026-11-16T10:00:00Z"}])]
    (is (re-find #"^ws-a\s+in-progress\s+agent\s+-\s+Build it$" line))
    (is (= "1 workstream(s), 0 waiting on a person" total))))

(deftest list-puts-what-waits-on-a-person-first
  (let [lines (list-out [{:ws-id "ws-new" :stage :in-progress :label "n" :needs-you false
                          :last-activity "2026-11-16T12:00:00Z"}
                         {:ws-id "ws-you" :stage :awaiting-gate :label "y" :needs-you true
                          :status {:owed-by :person} :last-activity "2026-11-15T09:00:00Z"}
                         {:ws-id "ws-old" :stage :in-progress :label "o" :needs-you false
                          :last-activity "2026-11-14T09:00:00Z"}])]
    (is (= ["ws-you" "ws-new" "ws-old"] (mapv #(first (str/split % #"\s+")) (butlast lines))))
    (is (= "3 workstream(s), 1 waiting on a person" (last lines)))))

(deftest list-leaves-out-settled-work-and-uncovered-tickets-unless-asked
  (let [rows [{:ws-id "ws-live" :stage :in-progress :label "l" :needs-you false}
              {:ws-id "ws-done" :stage :done :label "d" :needs-you false}
              {:ws-id nil :bare? true :stage :incoming :label "a ticket" :needs-you false}]]
    (is (= ["ws-live" "1"] (mapv #(first (str/split % #"\s+")) (list-out rows))))
    (is (= ["ws-live" "ws-done" "2"]
           (mapv #(first (str/split % #"\s+")) (list-out rows {:all true})))
        ":all adds settled work; a ticket no workstream covers has no id and is never listed")))

(deftest tell-passes-the-chats-word-to-the-conductor-of-the-workstream-it-runs-in
  (let [told (atom [])]
    (with-redefs [nido.review.stages/project+ws-from-cwd (fn [_] [:brian "ws-9"])
                  nido.coordinator.lane.conductor/tell!  (fn [p id text grants] (swap! told conj [p id text grants]) {:id "hb-1"})]
      (let [out (with-out-str (task/tell* {:text "drop the export"}))]
        (is (= [[:brian "ws-9" "drop the export" nil]] @told) "the workstream is the one the chat's session is on")
        (is (str/includes? out "hand-back hb-1")))
      (task/tell* {:text "/land" :grants 'merge})
      (is (= [:merge] (last (last @told))) "the person's grant is passed on as the acts it names"))))

(deftest pr-land-lands-only-the-workstreams-own-pr
  (with-tmp
    (fn [_]
      (let [w      (ws/create! :brian {:external-refs [{:adapter :github :id "brian-study/brian#412"}
                                                       {:adapter :github :id "other/repo#7"}
                                                       {:adapter :github :id "brian-study/brian#7"}]})
            landed (atom [])]
        (with-redefs [nido.review.stages/project+ws-from-cwd (fn [_] [:brian (:id w)])
                      nido.github.client/land-pr! (fn [repo n] (swap! landed conj [repo n]) {:status :ok :via :queue})]
          (is (= {:status :ok :via :queue :procedure :pr-auto :pr "brian-study/brian#412"}
                 (task/pr-land* {:pr "brian-study/brian#412"}))
              "an undeclared project lands by :pr-auto")
          (is (= [["brian-study" "brian" ] ] (map #(vec (take 2 (str/split (first %) #"/"))) @landed))
              "the repository is the ref's")
          (is (= 412 (second (first @landed))) "and so is the number")
          (task/pr-land* {:pr "412"})
          (is (= 2 (count @landed)) "a bare number names the one ref that has it")
          (doseq [pr ["7" "brian-study/brian#413" "elsewhere/brian#412" nil]]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not one workstream"
                                  (task/pr-land* {:pr pr}))
                (str (pr-str pr) " is refused")))
          (is (= 2 (count @landed)) "and nothing it refused reached gh"))
        (with-redefs [nido.review.stages/project+ws-from-cwd (fn [_] nil)
                      nido.github.client/land-pr! (fn [& _] (swap! landed conj :called) {:status :ok})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No workstream here"
                                (task/pr-land* {:pr "412"})))
          (is (= 2 (count @landed)) "outside every workstream, nothing lands"))))))

(deftest a-blocker-answer-carries-the-grant-the-person-gave-in-words
  (with-tmp
    (fn [_]
      (let [w  (ws/create! :brian {:external-refs []})
            id (:id w)]
        (ws/append-entry! :brian id {:kind :blocker}
                          (pr-str {:format :blocker :summary "s" :needs "land it?" :after "a"}))
        (task/blocker-answer* {:project "brian" :ws-id id :answer "yes, land it" :grants 'merge})
        (is (= [:merge] (:grants (last (ws/entries-of :brian id :blocker-answered)))))
        (ws/append-entry! :brian id {:kind :blocker}
                          (pr-str {:format :blocker :summary "s" :needs "close it?" :after "a"}))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"stays the person's own"
                              (task/blocker-answer* {:project "brian" :ws-id id :answer "close" :grants [:closed]}))
            "closing cannot be granted")
        (task/blocker-answer* {:project "brian" :ws-id id :answer "land it, please"})
        (is (nil? (:grants (last (ws/entries-of :brian id :blocker-answered))))
            "words with no :grants grant nothing — the text is not read")))))

(deftest pr-land-runs-the-declared-procedure
  (with-tmp
    (fn [_]
      (let [w     (ws/create! :brian {:external-refs [{:adapter :github :id "o/r#3"}]})
            calls (atom [])]
        (with-redefs [nido.review.stages/project+ws-from-cwd (fn [_] [:nido (:id w)])
                      nido.coordinator.lane.landing/names-pr? (constantly false)
                      nido.coordinator.lane.landing/land!     (fn [p id opts] (swap! calls conj [p id opts])
                                                                {:status :ok :via :push :procedure :push-main})]
          (is (= :push (:via (task/pr-land* {}))) "a project that lands no PR needs none named")
          (is (nil? (:pr (nth (first @calls) 2))) "and none is handed on"))
        (with-redefs [nido.review.stages/project+ws-from-cwd (fn [_] [:brian (:id w)])
                      nido.coordinator.lane.landing/names-pr? (constantly {:error :undeclared-landing :value :x})]
          (is (thrown? clojure.lang.ExceptionInfo (task/pr-land* {:pr "o/r#3"}))
              "an undeclared landing value is refused before anything runs"))))))
