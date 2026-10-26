(ns nido.coordinator.lane.seats-test
  "Occupancy: every seat its own driven session in the current generation, the conductor apart,
   writers stamped from where they run, seat messages logged and handed to conduct, a new
   generation retiring the old occupants, and the hook holding a seat's publications to its seat."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.platform.project :as project]
   [nido.coordinator.agent.substrate :as substrate]
   [nido.coordinator.lane.conduct :as conduct]
   [nido.coordinator.lane.embodied-strata :as embodied]
   [nido.coordinator.lane.seats :as seats]
   [nido.coordinator.record.seats :as rseats]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.workstream :as ws]
   [nido.session.commit-gate :as commit-gate]
   [nido.session.launcher :as launcher]
   [nido.session.lifecycle :as lifecycle]))

(def ^:private strata ["canvas.strata/ledger" "canvas.strata/seats"])

(def ^:private a-baseline
  {:format :baseline :strata [] :intent {:seq 1}
   :area "order totalling" :bounded-by "money on an order" :shape "one summing path"
   :model {:elements [{:id "agg" :sort :module :hides "the summing order" :interface "a total"}]
           :claims [{:id "c1" :about ["agg"] :statement "the aggregate is the only summing path"
                     :falsified-by "a second path that sums lines" :evidence {:by :round}
                     :read-at ["src/a.clj:1"]}]}
   :read ["src/a.clj"]})

(defn- a-design
  "A design over the baseline at 2 whose strata are `strata`, marking `marked`."
  [marked & {:as extra}]
  (let [marked (set marked)]
    (merge {:format :design :strata strata :summary "s" :shape "sh"
            :model {:elements (into [(cond-> {:id "agg" :sort :module} (marked "agg") (assoc :defines-generation true))]
                                    (map #(cond-> {:id % :sort :stratum :interface (str (last (str/split % #"/")) " words")
                                                   :readings [{:lens :stratified/level :verdict :sound :because "b"}]}
                                            (marked %) (assoc :defines-generation true)))
                                    strata)
                    :claims [{:id "one-summing-path" :about ["agg"] :statement "one summing path"
                              :falsified-by "a second path" :evidence {:by :round}}]}
            :standing {:relation :conforms}
            :baseline {:seq 2 :relation :within}
            :intent {:seq 1} :effort :S}
           extra)))

(defn- ledger
  "A workstream whose approved design marks `marked`. Returns [id add]."
  [marked]
  (let [id  (:id (ws/create! :brian {:external-refs []}))
        add (fn [kind record] (ws/append-entry! :brian id {:kind kind} (pr-str record))
              (count (:entries (ws/read-ws :brian id))))]
    (add :intent {:format :intent :goal "g" :done-when ["d"]})
    (add :baseline a-baseline)
    (add :baseline-review {:format :baseline-review :verdict :sufficient :baseline-seq 2 :reason "ok"})
    (let [d (add :design (a-design marked))]
      (add :design-approved {:format :design-approved :design {:seq d} :at-seq d}))
    [id add]))

(defmacro ^:private launching
  "Run `body` with every side effect of starting a session stubbed, `launched` an atom collecting
   [session-name prompt] per start, and `retired` one collecting retired session names."
  [[launched retired] & body]
  `(let [~launched (atom []) ~retired (atom [])]
     (with-redefs [lifecycle/up!               (fn [~'_ ~'_] nil)
                   lifecycle/runtime-of        (fn [~'_ ~'_] {:instance-id "brian--ws" :key "ws"})
                   lifecycle/session-coords    (fn [n# ~'_] {:wt-path (str "/wt/" n#) :instance-id "brian--ws"})
                   launcher/session-briefing   (fn [~'& ~'_] "briefing")
                   launcher/nido-add-dirs      (fn [] [])
                   project/list-projects       (fn [] {"brian" {:directory "/src/brian"}})
                   conduct/start!              (fn [o#]
                                                 (swap! ~launched conj [(:session-name o#) (:prompt o#) (:cwd o#)])
                                                 (session/update-substrate! (:project o#) (:ws-id o#) (:session-name o#)
                                                                            (fn [m#] (assoc m# :dispatching {:token "t" :at "now"})))
                                                 {})
                   conduct/retire!             (fn [~'_ ~'_ n# ~'_] (swap! ~retired conj n#) nil)
                   conduct/live-states         (fn [~'_] {})]
       ~@body)))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))] (f))
         (finally (fs/delete-tree tmp)))))

(defn- in-session
  "Run `f` as if from inside the nido session `n`, or outside every session for nil."
  [n f]
  (with-redefs [lifecycle/session-from-cwd (fn [_] (when n {:project "brian" :session n}))
                lifecycle/session-home-coords-from-cwd (fn [_] nil)]
    (f)))

(deftest each-seat-is-occupied-by-a-session-of-its-own
  (with-tmp
    (fn []
      (let [[id] (ledger strata)]
        (launching [launched _]
          (is (= {:occupied [{:seat "canvas.strata/ledger" :session (str id "--canvas_dstrata_sledger-g1")}
                             {:seat "canvas.strata/seats" :session (str id "--canvas_dstrata_sseats-g1")}]}
                 (embodied/carry! :brian id)))
          (is (= {:id "canvas.strata/ledger" :generation 1}
                 (:seat (session/read-session :brian id (str id "--canvas_dstrata_sledger-g1")))))
          (is (str/includes? (second (first @launched)) "ledger words") "briefed with its interface")
          (is (= {:occupied []} (embodied/carry! :brian id)) "carrying again occupies nothing")
          (is (= :occupied (:reason (ex-data (try (seats/occupy! :brian id (embodied/embodiment) "canvas.strata/ledger")
                                                  (catch Exception e e))))))
          (is (= :not-a-seat (:reason (ex-data (try (seats/occupy! :brian id (embodied/embodiment) "agg")
                                                    (catch Exception e e)))))))))))

(deftest embodied-strata-carries-only-a-design-marking-exactly-its-strata
  (with-tmp
    (fn []
      (testing "a stratum left unmarked"
        (let [[id] (ledger ["canvas.strata/ledger"])
              e    (try (embodied/carry! :brian id) (catch Exception e e))]
          (is (= ["canvas.strata/seats"] (:unmarked (ex-data e))))
          (is (str/includes? (ex-message e) "canvas.strata/seats"))))
      (testing "a non-stratum marked"
        (let [[id] (ledger (conj strata "agg"))
              e    (try (embodied/carry! :brian id) (catch Exception e e))]
          (is (= ["agg"] (:foreign (ex-data e))))))
      (testing "a draft marks nothing to carry"
        (let [id  (:id (ws/create! :brian {:external-refs []}))]
          (is (= :no-design (:reason (ex-data (try (embodied/carry! :brian id) (catch Exception e e)))))))))))

(deftest a-writer-is-stamped-from-where-it-runs
  (with-tmp
    (fn []
      (let [[id] (ledger strata)
            seat (str id "--canvas_dstrata_sledger-g1")]
        (launching [_ _]
          (embodied/carry! :brian id)
          (seats/conductor! :brian id "conduct it"))
        (let [t (in-session seat #(seats/record! :brian id {:format :tension :to "canvas.strata/seats"
                                                              :about ["canvas.strata/ledger"] :statement "s"}))]
          (is (= {:as :seat :seat "canvas.strata/ledger" :session seat} (:by t))))
        (is (= {:as :conductor :session (str id "--conductor")}
               (:by (in-session (str id "--conductor")
                                #(seats/record! :brian id {:format :accord :tension {:seq 6} :terms "t"})))))
        (testing "outside the ensemble only a re-cut is taken, by whoever runs it"
          (is (= :not-in-ensemble
                 (:reason (ex-data (try (in-session nil #(seats/record! :brian id {:format :tension :to :conductor
                                                                                   :about ["agg"] :statement "s"}))
                                        (catch Exception e e))))))
          (is (= {:as :person}
                 (:by (in-session nil #(seats/record! :brian id {:format :recut :ids strata :why "w"}))))))
        (is (= :stated-writer
               (:reason (ex-data (try (in-session seat #(seats/record! :brian id {:format :recut :ids strata :why "w"
                                                                                  :by {:as :person}}))
                                      (catch Exception e e))))))))))

(deftest a-seat-message-is-logged-and-owed-to-whoever-holds-the-seat
  ;; The log is all there is of a seat message: a seat is owed what the log addresses to it with
  ;; no delivery recorded, whichever session holds it when conduct asks.
  (with-tmp
    (fn []
      (let [[id] (ledger strata)
            ledger-seat (str id "--canvas_dstrata_sledger-g1")
            seats-seat  (str id "--canvas_dstrata_sseats-g1")]
        (launching [_ _]
          (embodied/carry! :brian id)
          (seats/conductor! :brian id "conduct it"))
        (in-session ledger-seat #(seats/send! :brian id {:to "canvas.strata/seats" :text "your read is mine"}))
        (is (= :conductor-sends
               (:reason (ex-data (try (in-session (str id "--conductor")
                                                  #(seats/send! :brian id {:to "canvas.strata/ledger" :text "x"}))
                                      (catch Exception e e)))))
            "the conductor's word reaches a seat only as a decided instruction")
        (let [posted (seats/post! :brian id {:key "d1" :kind :instruction :to "canvas.strata/ledger" :text "settle it"})]
          (is (= posted (seats/post! :brian id {:key "d1" :kind :instruction :to "canvas.strata/ledger" :text "again"}))
              "a second post under one key logs nothing and answers the first"))
        (is (= ["[seat message from seat canvas.strata/ledger]\n\nyour read is mine"]
               (mapv :text (seats/owed :brian id seats-seat))))
        (is (= ["[seat message from nido]\n\nsettle it"]
               (mapv :text (seats/owed :brian id ledger-seat))))
        (is (= [] (seats/owed :brian id (str id "--conductor"))))
        (is (= [["canvas.strata/seats" "your read is mine"] ["canvas.strata/ledger" "settle it"]]
               (mapv (juxt :to :text) (seats/traffic :brian id 1))))
        (is (= [] (seats/traffic :brian id 2)))
        (testing "a delivered message is owed no more"
          (seats/delivered! :brian id (:id (first (seats/owed :brian id ledger-seat))) "tok")
          (is (= [] (seats/owed :brian id ledger-seat)))
          (is (= [false true] (mapv :delivered? (seats/traffic :brian id 1)))))
        (testing "the seat's next occupant is owed what its predecessor was"
          (launching [_launched _retired]
            (seats/replace! :brian id (embodied/embodiment) "canvas.strata/seats"))
          (let [successor (:session (first (filter #(= "canvas.strata/seats" (:seat %)) (seats/occupants :brian id))))]
            (is (= ["[seat message from seat canvas.strata/ledger]\n\nyour read is mine"]
                   (mapv :text (seats/owed :brian id successor))))))
        (is (= :not-in-ensemble
               (:reason (ex-data (try (in-session nil #(seats/send! :brian id {:to :conductor :text "x"}))
                                      (catch Exception e e))))))
        (is (= :no-addressee
               (:reason (ex-data (try (in-session ledger-seat #(seats/send! :brian id {:to "nowhere" :text "x"}))
                                      (catch Exception e e))))))))))

(deftest an-occupant-is-stamped-against-the-generation-its-entry-joins
  (with-tmp
    (fn []
      (let [[id] (ledger strata)
            seat (str id "--canvas_dstrata_sledger-g1")]
        (launching [_ _]
          (embodied/carry! :brian id)
          (seats/conductor! :brian id "conduct it"))
        (let [r      (in-session nil #(count (do (seats/record! :brian id {:format :recut :ids strata :why "again"})
                                                 (:entries (ws/read-ws :brian id)))))
              append ws/append-stamped!]
          ;; The generation opens as the tension reaches the ledger — after its writer could have
          ;; been resolved, before the append takes the lock.
          (with-redefs [ws/append-stamped! (fn [p i payload]
                                             (ws/open-generation! p i {:format :generation :recut {:seq r}
                                                                       :words "again" :ids strata :base-rev "abc"})
                                             (append p i payload))]
            (is (= :not-in-ensemble
                   (:reason (ex-data (try (in-session seat #(seats/record! :brian id {:format :tension :to :conductor
                                                                                      :about ["agg"] :statement "s"}))
                                          (catch Exception e e)))))
                "an occupant of the closed generation does not write as a seat of the open one"))
          (is (not-any? #(= :tension (:kind %)) (:entries (ws/read-ws :brian id)))))))))

(deftest no-generation-opens-while-a-seat-message-is-in-flight
  (with-tmp
    (fn []
      (let [[id] (ledger strata)
            opened-in-flight (atom nil)
            opening          (atom nil)]
        (launching [_ _]
          (embodied/carry! :brian id)
          (seats/conductor! :brian id "conduct it"))
        (let [r (in-session nil #(count (do (seats/record! :brian id {:format :recut :ids strata :why "again"})
                                            (:entries (ws/read-ws :brian id)))))]
          (with-redefs [io/update-edn! (let [update io/update-edn!]
                                         (fn [path & args]
                                           (when-not @opening
                                             (let [f (future (ws/open-generation! :brian id {:format :generation :recut {:seq r}
                                                                                            :words "again" :ids strata :base-rev "abc"}))]
                                               (reset! opening f)
                                               (reset! opened-in-flight (not= ::waiting (deref f 500 ::waiting)))))
                                           (apply update path args)))]
            (let [msg (in-session (str id "--canvas_dstrata_sledger-g1")
                                  #(seats/send! :brian id {:to "canvas.strata/seats" :text "x"}))]
              (is (= 1 (:generation msg)))
              (is (false? @opened-in-flight)
                  "the opening waits until the message resolved under generation 1 is logged")
              (is (= :generation (:kind (last (:entries (deref @opening 5000 nil)))))
                  "and opens once it is"))))))))

(deftest the-embodiments-refusal-is-made-inside-the-occupancy
  ;; The embodiment's refusal runs against the generation the occupation seats, under its locks —
  ;; so an occupation it refuses launches nothing, whatever was checked before.
  (with-tmp
    (fn []
      (let [[id] (ledger strata)]
        (launching [launched _]
          (let [refusing (assoc (embodied/embodiment)
                                :refuse (fn [_ _] (throw (ex-info "not carried" {:reason :not-strata}))))]
            (is (= :not-strata (:reason (ex-data (try (seats/occupy! :brian id refusing "canvas.strata/ledger")
                                                      (catch Exception e e))))))
            (is (= :not-strata (:reason (ex-data (try (seats/replace! :brian id refusing "canvas.strata/ledger")
                                                      (catch Exception e e))))))
            (is (= [] @launched))))))))

(deftest no-generation-opens-while-an-occupant-is-launched
  ;; An occupant is read, recorded and launched under the lock an opening takes: an opening
  ;; asked for mid-launch waits, so no occupant starts in a generation that has closed.
  (with-tmp
    (fn []
      (let [[id] (ledger strata)
            opened-in-flight (atom nil)
            opening          (atom nil)
            r (in-session nil #(count (do (seats/record! :brian id {:format :recut :ids strata :why "again"})
                                          (:entries (ws/read-ws :brian id)))))]
        (launching [launched _]
          (let [start conduct/start!]
            (with-redefs [conduct/start! (fn [o]
                                           (when-not @opening
                                             (let [f (future (ws/open-generation! :brian id {:format :generation :recut {:seq r}
                                                                                            :words "again" :ids strata :base-rev "abc"}))]
                                               (reset! opening f)
                                               (reset! opened-in-flight (not= ::waiting (deref f 500 ::waiting)))))
                                           (start o))]
              (seats/occupy! :brian id (embodied/embodiment) "canvas.strata/ledger")))
          (is (false? @opened-in-flight) "the opening waits for the launch")
          (is (= [(str id "--canvas_dstrata_sledger-g1")] (mapv first @launched)))
          (is (= :generation (:kind (last (:entries (deref @opening 5000 nil)))))
              "and opens once it is done"))))))

(deftest a-new-generation-retires-its-predecessors-occupants-and-keeps-the-conductor
  (with-tmp
    (fn []
      (let [[id add] (ledger strata)]
        (launching [launched retired]
          (embodied/carry! :brian id)
          (seats/conductor! :brian id "conduct it")
          (let [r (in-session nil #(count (do (seats/record! :brian id {:format :recut :ids strata :why "again"})
                                              (:entries (ws/read-ws :brian id)))))]
            (ws/open-generation! :brian id {:format :generation :recut {:seq r} :words "again"
                                            :ids strata :base-rev "abc"})
            (is (= {:retired [(str id "--canvas_dstrata_sledger-g1") (str id "--canvas_dstrata_sseats-g1")]} (seats/reseat! :brian id)))
            (is (= (set [(str id "--canvas_dstrata_sledger-g1") (str id "--canvas_dstrata_sseats-g1")]) (set @retired)))
            (is (= :archived (:substrate (session/read-session :brian id (str id "--canvas_dstrata_sledger-g1")))))
            (is (= :live (:substrate (session/read-session :brian id (str id "--conductor"))))
                "the conductor continues")
            (is (= [] (seats/occupants :brian id)) "no earlier generation's occupant is one now")
            (add :design (a-design strata :supersedes {:seq 4 :why "re-cut"}))
            (is (= [(str id "--canvas_dstrata_sledger-g2") (str id "--canvas_dstrata_sseats-g2")]
                   (mapv :session (:occupied (embodied/carry! :brian id))))
                "the new generation's seats get sessions that occupied none before")))))))

(deftest replacing-an-occupant-retires-it-before-starting-afresh
  (with-tmp
    (fn []
      (let [[id] (ledger strata)]
        (launching [launched retired]
          (embodied/carry! :brian id)
          (reset! launched [])
          (seats/replace! :brian id (embodied/embodiment) "canvas.strata/seats")
          (is (= [(str id "--canvas_dstrata_sseats-g1")] @retired))
          (is (= [(str id "--canvas_dstrata_sseats-g1")] (mapv first @launched)) "on the same record")
          (is (= 2 (count (seats/occupants :brian id)))))))))

(deftest a-workstream-has-one-conductor
  (with-tmp
    (fn []
      (let [[id] (ledger strata)]
        (launching [_ _]
          (seats/conductor! :brian id "conduct it")
          (is (true? (:conductor (session/read-session :brian id (str id "--conductor")))))
          (is (nil? (:seat (session/read-session :brian id (str id "--conductor")))))
          (is (= :conducted (:reason (ex-data (try (seats/conductor! :brian id "conduct it") (catch Exception e e)))))))))))

(deftest a-session-record-names-a-seat-or-the-conductor-never-both
  (with-tmp
    (fn []
      (let [id (:id (ws/create! :brian {:external-refs []}))]
        (is (thrown-with-msg? Exception #"never both"
                              (session/create! :brian id {:name "both" :weight :light :autonomy nil
                                                          :seat {:id "s" :generation 1} :conductor true})))))))

(deftest a-seats-publications-name-its-seat-and-position
  (with-tmp
    (fn []
      (let [[id] (ledger strata)
            seat (str id "--canvas_dstrata_sledger-g1")
            input (fn [cmd] {:tool_name "Bash" :tool_input {:command cmd} :cwd "/wt"})]
        (launching [_ _] (embodied/carry! :brian id))
        (in-session seat
          (fn []
            (with-redefs [commit-gate/push-range (fn [_ _] [{:change "abc" :description "Do it\n\nSeat: canvas.strata/ledger 2/3\n"}
                                                           {:change "def" :description "Do more\n"}])]
              (is (str/includes? (seats/gate-verdict (input "jj git push")) "commit def"))
              (with-redefs [commit-gate/push-range (fn [_ _] [{:change "abc" :description "Do it\n\nSeat: canvas.strata/ledger 2/3"}])]
                (is (nil? (seats/gate-verdict (input "jj git push")))))
              (with-redefs [commit-gate/push-range (fn [_ _] [])]
                (is (nil? (seats/gate-verdict (input "jj git push"))) "a push that publishes nothing"))
              (with-redefs [commit-gate/push-range (fn [_ _] nil)]
                (is (str/includes? (seats/gate-verdict (input "jj git push")) "could not be checked")
                    "a seat's push jj cannot read is refused, not let through"))
              (with-redefs [rseats/seats (fn [_ _] (throw (ex-info "ledger unreadable" {})))]
                (is (str/includes? (seats/gate-verdict (input "jj git push")) "ledger unreadable"))))
            (is (some? (seats/gate-verdict (input "gh pr create --title 'Do it'"))))
            (is (nil? (seats/gate-verdict (input "gh pr create --title '[canvas.strata/ledger 2/3] Do it'"))))
            (is (some? (seats/gate-verdict (input "gh pr create --title '[canvas.strata/seats 2/3] Do it'")))
                "another seat's name is not this one's")
            (doseq [cmd ["gh pr create --title \"$title\"" "gh pr create --fill" "jj git push -b \"$B\""
                         "cd \"$D\" && jj git push"]]
              (is (str/includes? (str (seats/gate-verdict (input cmd))) "cannot be read")
                  (str "a seat's publication the gate cannot read is refused: " cmd)))
            (let [read-session session/read-session
                  as     (fn [conv cmd] (seats/gate-verdict (assoc (input cmd) :session_id conv)))]
              (with-redefs [session/read-session (fn [& args] (assoc-in (apply read-session args) [:claude :session-id] "occupant"))]
                (is (some? (as "occupant" "gh pr create --title 'Do it'")) "the occupant's conversation is held")
                (is (nil? (as "person" "gh pr create --title 'Do it'"))
                    "a person's other conversation in the seat's folder occupies no seat")
                (is (some? (as nil "gh pr create --title 'Do it'")) "an unknown hook conversation is held"))
              (is (some? (as "person" "gh pr create --title 'Do it'"))
                  "a record whose conversation is unknown holds whoever runs there"))))
        (in-session nil
          #(is (nil? (seats/gate-verdict (input "gh pr create --title 'Do it'"))) "a session with no seat is not held"))))))

(deftest two-occupations-of-one-seat-start-one-session
  (with-tmp
    (fn []
      (let [[id] (ledger strata)
            create session/create!]
        (launching [launched _]
          (with-redefs [session/create! (fn [& args] (Thread/sleep 300) (apply create args))]
            (let [occupy  #(future (try (seats/occupy! :brian id (embodied/embodiment) "canvas.strata/ledger")
                                        (catch Exception e (:reason (ex-data e)))))
                  results [(occupy) (occupy)]]
              (is (= #{:occupied {:seat "canvas.strata/ledger" :session (str id "--canvas_dstrata_sledger-g1")}}
                     (set (map deref results))))
              (is (= 1 (count @launched)) "one session started"))))))))

(deftest a-failed-launch-can-be-retried
  (with-tmp
    (fn []
      (let [[id] (ledger strata)]
        (launching [launched _]
          (let [failing (fn [f] (with-redefs [lifecycle/up! (fn [_ _] (throw (ex-info "no runtime" {})))]
                                  (is (thrown-with-msg? Exception #"no runtime" (f)))))]
            (testing "the conductor"
              (failing #(seats/conductor! :brian id "conduct it"))
              (is (= {:session (str id "--conductor")} (seats/conductor! :brian id "conduct it")) "started on the record left")
              (is (= :conducted (:reason (ex-data (try (seats/conductor! :brian id "conduct it") (catch Exception e e)))))))
            (testing "a seat"
              (failing #(seats/occupy! :brian id (embodied/embodiment) "canvas.strata/ledger"))
              (is (= {:seat "canvas.strata/ledger" :session (str id "--canvas_dstrata_sledger-g1")}
                     (seats/occupy! :brian id (embodied/embodiment) "canvas.strata/ledger")))
              (is (= :occupied (:reason (ex-data (try (seats/occupy! :brian id (embodied/embodiment) "canvas.strata/ledger")
                                                      (catch Exception e e)))))))
            (is (= [(str id "--conductor") (str id "--canvas_dstrata_sledger-g1")] (mapv first @launched)))))))))

(deftest a-seat-whose-relaunch-failed-is-carried-again
  ;; A replacement retires the occupant before it launches; when that launch fails, the retired
  ;; conversation holds the seat no longer, and carrying the workstream starts it again.
  (with-tmp
    (fn []
      (let [[id] (ledger strata)
            n      (str id "--canvas_dstrata_sseats-g1")
            retire conduct/retire!]
        (launching [launched _]
          (with-redefs [conduct/retire! retire
                        substrate/stop! (fn [_ _] nil)
                        conduct/start!  (fn [o]
                                          (swap! launched conj [(:session-name o)])
                                          (session/update-substrate! (:project o) (:ws-id o) (:session-name o)
                                                                     #(assoc % :claude (#'conduct/fresh-facet :nido "now" {:session-id (:session-name o) :bg-id nil})))
                                          {})]
            (embodied/carry! :brian id)
            (with-redefs [lifecycle/up! (fn [_ _] (throw (ex-info "no runtime" {})))]
              (is (thrown-with-msg? Exception #"no runtime" (seats/replace! :brian id (embodied/embodiment) "canvas.strata/seats"))))
            (reset! launched [])
            (is (= [{:seat "canvas.strata/seats" :session n}] (:occupied (embodied/carry! :brian id))))
            (is (= [[n]] @launched) "the retired occupant's record is started on again")))))))

(deftest a-held-occupant-whose-relaunch-failed-is-carried-again
  ;; A hold was on the conversation retirement ended: releasing or handing back the stopped
  ;; predecessor drives nothing, so it never holds its seat again and carrying starts the seat.
  (with-tmp
    (fn []
      (let [[id] (ledger strata)
            n      (str id "--canvas_dstrata_sseats-g1")
            retire conduct/retire!]
        (launching [launched _]
          (with-redefs [conduct/retire! retire
                        substrate/stop! (fn [_ _] nil)
                        conduct/start!  (fn [o]
                                          (swap! launched conj [(:session-name o)])
                                          (session/update-substrate! (:project o) (:ws-id o) (:session-name o)
                                                                     #(assoc % :claude (#'conduct/fresh-facet :nido "now" {:session-id (:session-name o) :bg-id nil})))
                                          {})]
            (embodied/carry! :brian id)
            (session/update-claude! :brian id n #(assoc % :hold {:at "t" :because :prompt} :driver :person))
            (with-redefs [lifecycle/up! (fn [_ _] (throw (ex-info "no runtime" {})))]
              (is (thrown-with-msg? Exception #"no runtime" (seats/replace! :brian id (embodied/embodiment) "canvas.strata/seats"))))
            (conduct/release! :brian id n)
            (with-redefs [nido.coordinator.lane.conduct/caught-up (fn [f _] f)]
              (conduct/hand-back! :brian id n))
            (reset! launched [])
            (is (= [{:seat "canvas.strata/seats" :session n}] (:occupied (embodied/carry! :brian id))))
            (is (= [[n]] @launched) "the stopped predecessor holds the seat no longer")))))))

(deftest an-adopted-occupant-holds-its-seat-again
  ;; Retirement lasts until a conversation is driven on the record again: a retired occupant nido
  ;; adopts back is its seat's occupant, and carrying the workstream starts nobody over it.
  (with-tmp
    (fn []
      (let [[id] (ledger strata)
            n      (str id "--canvas_dstrata_sseats-g1")
            retire conduct/retire!]
        (launching [launched _]
          (with-redefs [conduct/retire! retire
                        substrate/stop! (fn [_ _] nil)
                        conduct/start!  (fn [o]
                                          (swap! launched conj [(:session-name o)])
                                          (session/update-substrate! (:project o) (:ws-id o) (:session-name o)
                                                                     #(assoc % :claude (#'conduct/fresh-facet :nido "now" {:session-id (:session-name o) :bg-id "bg"})))
                                          {})]
            (embodied/carry! :brian id)
            (conduct/retire! :brian id n {})
            (with-redefs [substrate/listing           (fn [_] [{:kind "background" :sessionId n :id "bg" :pid 1}])
                          conduct/baseline-shown      (fn [_] nil)
                          nido.coordinator.lane.conduct/baseline-refusal (fn [_ _] nil)
                          nido.coordinator.lane.conduct/caught-up        (fn [f _] f)]
              (conduct/adopt! {:project :brian :ws-id id :session-name n :session-id n}))
            (reset! launched [])
            (is (= [] (:occupied (embodied/carry! :brian id))))
            (is (= [] @launched) "nobody is started over the adopted occupant")))))))

(deftest a-writer-in-another-projects-session-is-not-in-the-ensemble
  (with-tmp
    (fn []
      (let [[id] (ledger strata)]
        (launching [_ _] (embodied/carry! :brian id))
        (with-redefs [lifecycle/session-from-cwd (fn [_] {:project "other" :session (str id "--canvas_dstrata_sledger-g1")})
                      lifecycle/session-home-coords-from-cwd (fn [_] nil)]
          (is (= :not-in-ensemble
                 (:reason (ex-data (try (seats/record! :brian id {:format :tension :to :conductor
                                                                  :about ["agg"] :statement "s"})
                                        (catch Exception e e))))))
          (is (= {:as :session :session (str id "--canvas_dstrata_sledger-g1")}
                 (:by (seats/record! :brian id {:format :recut :ids strata :why "w"})))))))))

(deftest a-brief-carries-the-accords-and-what-the-generation-inherited
  (with-tmp
    (fn []
      (let [[id add] (ledger strata)
            ledger-s (str id "--canvas_dstrata_sledger-g1")]
        (launching [launched _retired]
          (embodied/carry! :brian id)
          (seats/conductor! :brian id "conduct it")
          (let [t (in-session ledger-s #(do (seats/record! :brian id {:format :tension :to :conductor
                                                                       :about ["canvas.strata/ledger"] :statement "too wide"})
                                            (count (:entries (ws/read-ws :brian id)))))]
            (in-session (str id "--conductor")
                        #(seats/record! :brian id {:format :accord :tension {:seq t} :terms "narrow it to entries"}))
            (reset! launched [])
            (seats/replace! :brian id (embodied/embodiment) "canvas.strata/ledger")
            (is (str/includes? (second (first @launched)) "narrow it to entries") "the accord's terms")
            (let [r (in-session nil #(count (do (seats/record! :brian id {:format :recut :ids strata :why "again"
                                                                          :tensions [{:seq t}]})
                                                (:entries (ws/read-ws :brian id)))))]
              (ws/open-generation! :brian id {:format :generation :recut {:seq r} :words "again" :ids strata
                                              :base-rev "rev-xyz"})
              (seats/reseat! :brian id)
              (add :design (a-design strata :supersedes {:seq 4 :why "re-cut"}))
              (reset! launched [])
              ;; The ledger derives :falsified from the closing span's decisions; this ledger has none.
              (let [read rseats/inherited]
                (with-redefs [rseats/inherited (fn [p i] (assoc (read p i) :falsified [{:claim "one-summing-path"
                                                                                        :by "a second path seen"}]))]
                  (embodied/carry! :brian id)))
              (let [b (second (first @launched))]
                (is (str/includes? b "rev-xyz"))
                (is (str/includes? b "a second path seen"))
                (is (str/includes? b "too wide"))))))))))

(deftest a-push-holds-each-layer-to-its-own-seat
  (with-tmp
    (fn []
      (let [[id] (ledger strata)
            input {:tool_name "Bash" :tool_input {:command "jj git push"} :cwd "/wt"}]
        (launching [_ _] (embodied/carry! :brian id))
        (in-session (str id "--canvas_dstrata_sseats-g1")
          (fn []
            (with-redefs [commit-gate/push-range (fn [_ _] [{:change "b" :description "B\n\nSeat: canvas.strata/seats 1/1"}
                                                           {:change "a" :description "A\n\nSeat: canvas.strata/ledger 1/1"}])]
              (is (nil? (seats/gate-verdict input)) "another seat's layer under the pusher's, naming its own seat"))
            (with-redefs [commit-gate/push-range (fn [_ _] [{:change "a" :description "A\n\nSeat: agg 1/1"}])]
              (is (str/includes? (seats/gate-verdict input) "commit a") "a name that is no seat"))))))))

(deftest an-occupant-starts-in-its-own-working-copy
  (with-tmp
    (fn []
      (let [[id] (ledger strata)]
        (launching [launched _retired]
          (seats/occupy! :brian id (embodied/embodiment) "canvas.strata/ledger")
          (seats/conductor! :brian id "conduct it")
          (is (= [(str "/wt/" id "--canvas_dstrata_sledger-g1") (str "/wt/" id "--conductor")] (mapv #(nth % 2) @launched))))))))

(deftest a-retirement-that-failed-at-the-opening-completes-before-anyone-is-seated
  (with-tmp
    (fn []
      (let [[id add] (ledger strata)]
        (launching [launched retired]
          (embodied/carry! :brian id)
          (let [r (in-session nil #(count (do (seats/record! :brian id {:format :recut :ids strata :why "again"})
                                              (:entries (ws/read-ws :brian id)))))]
            (ws/open-generation! :brian id {:format :generation :recut {:seq r} :words "again"
                                            :ids strata :base-rev "abc"})
            (with-redefs [conduct/retire! (fn [_ _ _ _] (throw (ex-info "stop refused" {:reason :stop-refused})))]
              (is (thrown-with-msg? Exception #"stop refused" (seats/reseat! :brian id))))
            (is (= :live (:substrate (session/read-session :brian id (str id "--canvas_dstrata_sledger-g1")))) "left running")
            (add :design (a-design strata :supersedes {:seq 4 :why "re-cut"}))
            (reset! launched [])
            (embodied/carry! :brian id)
            (is (= #{(str id "--canvas_dstrata_sledger-g1") (str id "--canvas_dstrata_sseats-g1")} (set @retired)) "retired before seating")
            (is (= :archived (:substrate (session/read-session :brian id (str id "--canvas_dstrata_sseats-g1")))))
            (is (= [(str id "--canvas_dstrata_sledger-g2") (str id "--canvas_dstrata_sseats-g2")] (mapv first @launched)))))))))

(deftest a-start-carries-its-session-into-the-whole-seat
  (with-tmp
    (fn []
      (let [[id add] (ledger [])
            mine     "fix-it"]
        (session/create! :brian id {:name mine :weight :heavy :autonomy nil})
        (launching [launched retired]
          (is (= {:seat 1 :session mine} (seats/occupy! :brian id (seats/default-embodiment) 1 mine)))
          (is (= {:id 1 :generation 1 :carried true} (:seat (session/read-session :brian id mine))))
          (is (= [mine] (mapv first @launched)) "started on the brief, nido not yet driving it")
          (is (str/includes? (second (first @launched)) "whole seat"))
          (is (= :occupied (:reason (ex-data (try (seats/occupy! :brian id (seats/default-embodiment) 1 "other")
                                                  (catch Exception e e)))))
              "a later start displaces no one")
          (testing "once the generation fixes ids, a carried occupant is released, not archived"
            (let [d (add :design (a-design ["canvas.strata/ledger"] :supersedes {:seq 4 :why "mark"}))]
              (add :design-approved {:format :design-approved :design {:seq d} :at-seq d}))
            (seats/reseat! :brian id)
            (let [s (session/read-session :brian id mine)]
              (is (nil? (:seat s)))
              (is (:released s) "it keeps saying its workstream was carried")
              (is (session/live? s) "still its person's"))
            (is (= [] @retired) "nothing of it stopped")
            (is (= :fixes-ids (:reason (ex-data (try (seats/occupy! :brian id (seats/default-embodiment) 1)
                                                     (catch Exception e e))))))))))))

(deftest a-runs-carried-session-stays-driven-for-its-run-once-its-seat-is-gone
  (with-tmp
    (fn []
      (let [[id add] (ledger [])
            run      "run-1"]
        (session/create! :brian id {:name run :weight :light
                                    :autonomy {:skill :x :first-message "x" :agent :claude
                                               :claude-session-id nil :trigger :t :limits {} :priority 0
                                               :uncapped? false :on-promote nil :error nil}})
        (session/update-substrate! :brian id run (constantly {:claude {:session-id "conv-1" :bg-id "b" :origin :nido
                                                                      :driver :nido :hold nil :pending nil
                                                                      :queued "the run's next" :queued-for "r1"
                                                                      :own-work? false
                                                                      :policy {:auto-respawn? false} :cursor 0
                                                                      :driving-since nil :announced-blocked? false
                                                                      :recovered-boot nil}
                                                             :dispatching nil}))
        (launching [_ retired]
          (seats/occupy! :brian id (seats/default-embodiment) 1 run)
          (let [d (add :design (a-design ["canvas.strata/ledger"] :supersedes {:seq 4 :why "mark"}))]
            (add :design-approved {:format :design-approved :design {:seq d} :at-seq d}))
          (seats/reseat! :brian id)
          (let [s (session/read-session :brian id run)]
            (is (nil? (:seat s)))
            (is (:released s))
            (is (= :nido (get-in s [:claude :driver])) "nido still drives it for its Run")
            (is (= "the run's next" (get-in s [:claude :queued])) "its Run's instruction is kept"))
          (is (= [] @retired)))))))

(deftest the-conductor-is-owed-its-hand-backs-until-one-is-answered
  (with-tmp
    (fn []
      (let [[id add] (ledger [])
            c        (str id "--conductor")]
        (launching [_ _] (seats/conductor! :brian id "conduct it"))
        (session/update-substrate! :brian id c (constantly {:claude {:session-id "conv-1" :bg-id "b" :origin :nido
                                                                    :driver :nido :hold nil
                                                                    :pending {:token "tok" :at "t" :via :send :seen? false}
                                                                    :queued nil :own-work? false
                                                                    :policy {:auto-respawn? false} :cursor 0
                                                                    :driving-since nil :announced-blocked? false
                                                                    :recovered-boot nil}
                                                           :dispatching nil}))
        (let [hb (seats/post! :brian id {:key "turn-1" :kind :hand-back :to :conductor :text "seat 1 stopped"})]
          (is (= ["[seat message from nido]\n\nseat 1 stopped"] (mapv :text (seats/owed :brian id c))))
          (seats/delivered! :brian id (:id hb) "tok")
          (is (= [] (seats/owed :brian id c)) "given to its running conversation")
          (session/update-claude! :brian id c #(assoc % :session-id "conv-2"))
          (is (= [(:id hb)] (mapv :id (seats/owed :brian id c)))
              "a conversation that stopped before answering it leaves it owed to the next")
          (ws/append-stamped! :brian id {:format :conduct-decision :hand-back (:id hb) :position :intake :by :conductor
                                         :decision [:instruct [{:seat 1 :text "go on"}]]})
          (is (= [] (seats/owed :brian id c)) "answered, it is owed no more"))))))

(deftest retiring-the-conductor-stops-it-and-brings-its-session-down
  (with-tmp
    (fn []
      (let [[id] (ledger [])
            downed (atom [])]
        (is (= {} (seats/retire-conductor! :brian id)) "nothing to retire without a record")
        (launching [_ retired]
          (seats/conductor! :brian id "conduct it")
          (with-redefs [fs/exists?       (constantly true)
                        lifecycle/down!  (fn [n _] (swap! downed conj n))]
            (is (= {:session (str id "--conductor")} (seats/retire-conductor! :brian id))))
          (is (= [(str id "--conductor")] @retired))
          (is (= [(str id "--conductor")] @downed))
          (is (true? (:conductor (session/read-session :brian id (str id "--conductor")))) "its record kept"))))))

(deftest a-tension-goes-to-a-seat-or-the-conductor
  (with-tmp
    (fn []
      (let [[id] (ledger [])
            mine "fix-it"]
        (session/create! :brian id {:name mine :weight :heavy :autonomy nil})
        (launching [_ _] (seats/occupy! :brian id (seats/default-embodiment) 1 mine))
        (is (= :no-addressee
               (:reason (ex-data (try (in-session mine #(seats/record! :brian id {:format :tension :to "nowhere"
                                                                                  :about ["agg"] :statement "s"}))
                                      (catch Exception e e))))))
        (is (= :conductor (:to (in-session mine #(seats/record! :brian id {:format :tension :to :conductor
                                                                          :about ["agg"] :statement "s"})))))))))
