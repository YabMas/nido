(ns nido.coordinator.lane.seats-test
  "Occupancy: every seat its own driven session in the current generation, the conductor apart,
   writers stamped from where they run, seat messages logged and handed to conduct, a new
   generation retiring the old occupants, and the hook holding a seat's publications to its seat."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.platform.project :as project]
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
                   conduct/retire!             (fn [~'_ ~'_ n# ~'_] (swap! ~retired conj n#) nil)]
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
          (is (= {:occupied [{:seat "canvas.strata/ledger" :session (str id "--ledger-g1")}
                             {:seat "canvas.strata/seats" :session (str id "--seats-g1")}]}
                 (embodied/carry! :brian id)))
          (is (= {:id "canvas.strata/ledger" :generation 1}
                 (:seat (session/read-session :brian id (str id "--ledger-g1")))))
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
            seat (str id "--ledger-g1")]
        (launching [_ _]
          (embodied/carry! :brian id)
          (seats/conductor! :brian id))
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

(deftest a-seat-message-is-logged-and-handed-to-conduct
  (with-tmp
    (fn []
      (let [[id] (ledger strata)
            given (atom [])]
        (launching [_ _]
          (embodied/carry! :brian id)
          (seats/conductor! :brian id))
        (with-redefs [conduct/message! (fn [_ _ n text] (swap! given conj [n text]) {})]
          (in-session (str id "--ledger-g1")
                      #(seats/send! :brian id {:to "canvas.strata/seats" :text "your read is mine"}))
          (in-session (str id "--conductor")
                      #(seats/send! :brian id {:to "canvas.strata/ledger" :text "settle it"}))
          (is (= [[(str id "--seats-g1") "[seat message from seat canvas.strata/ledger]\n\nyour read is mine"]
                  [(str id "--ledger-g1") "[seat message from the conductor]\n\nsettle it"]]
                 @given))
          (is (= [["canvas.strata/seats" "your read is mine"] ["canvas.strata/ledger" "settle it"]]
                 (mapv (juxt :to :text) (seats/traffic :brian id 1))))
          (is (= [] (seats/traffic :brian id 2)))
          (is (= :not-in-ensemble
                 (:reason (ex-data (try (in-session nil #(seats/send! :brian id {:to :conductor :text "x"}))
                                        (catch Exception e e)))))))))))

(deftest a-new-generation-retires-its-predecessors-occupants-and-keeps-the-conductor
  (with-tmp
    (fn []
      (let [[id add] (ledger strata)]
        (launching [launched retired]
          (embodied/carry! :brian id)
          (seats/conductor! :brian id)
          (let [r (in-session nil #(count (do (seats/record! :brian id {:format :recut :ids strata :why "again"})
                                              (:entries (ws/read-ws :brian id)))))]
            (ws/open-generation! :brian id {:format :generation :recut {:seq r} :words "again"
                                            :ids strata :base-rev "abc"})
            (is (= {:retired [(str id "--ledger-g1") (str id "--seats-g1")]} (seats/reseat! :brian id)))
            (is (= (set [(str id "--ledger-g1") (str id "--seats-g1")]) (set @retired)))
            (is (= :archived (:substrate (session/read-session :brian id (str id "--ledger-g1")))))
            (is (= :live (:substrate (session/read-session :brian id (str id "--conductor"))))
                "the conductor continues")
            (is (= [] (seats/occupants :brian id)) "no earlier generation's occupant is one now")
            (add :design (a-design strata :supersedes {:seq 4 :why "re-cut"}))
            (is (= [(str id "--ledger-g2") (str id "--seats-g2")]
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
          (is (= [(str id "--seats-g1")] @retired))
          (is (= [(str id "--seats-g1")] (mapv first @launched)) "on the same record")
          (is (= 2 (count (seats/occupants :brian id)))))))))

(deftest a-workstream-has-one-conductor
  (with-tmp
    (fn []
      (let [[id] (ledger strata)]
        (launching [_ _]
          (seats/conductor! :brian id)
          (is (true? (:conductor (session/read-session :brian id (str id "--conductor")))))
          (is (nil? (:seat (session/read-session :brian id (str id "--conductor")))))
          (is (= :conducted (:reason (ex-data (try (seats/conductor! :brian id) (catch Exception e e)))))))))))

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
            seat (str id "--ledger-g1")
            input (fn [cmd] {:tool_name "Bash" :tool_input {:command cmd} :cwd "/wt"})]
        (launching [_ _] (embodied/carry! :brian id))
        (in-session seat
          (fn []
            (with-redefs [commit-gate/push-range (fn [_ _] [{:change "abc" :description "Do it\n\nSeat: canvas.strata/ledger 2/3\n"}
                                                           {:change "def" :description "Do more\n"}])]
              (is (str/includes? (seats/gate-verdict (input "jj git push")) "commit def"))
              (with-redefs [commit-gate/push-range (fn [_ _] [{:change "abc" :description "Do it\n\nSeat: canvas.strata/ledger 2/3"}])]
                (is (nil? (seats/gate-verdict (input "jj git push"))))))
            (is (some? (seats/gate-verdict (input "gh pr create --title 'Do it'"))))
            (is (nil? (seats/gate-verdict (input "gh pr create --title '[canvas.strata/ledger 2/3] Do it'"))))
            (is (some? (seats/gate-verdict (input "gh pr create --title '[canvas.strata/seats 2/3] Do it'")))
                "another seat's name is not this one's")))
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
              (is (= #{:occupied {:seat "canvas.strata/ledger" :session (str id "--ledger-g1")}}
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
              (failing #(seats/conductor! :brian id))
              (is (= {:session (str id "--conductor")} (seats/conductor! :brian id)) "started on the record left")
              (is (= :conducted (:reason (ex-data (try (seats/conductor! :brian id) (catch Exception e e)))))))
            (testing "a seat"
              (failing #(seats/occupy! :brian id (embodied/embodiment) "canvas.strata/ledger"))
              (is (= {:seat "canvas.strata/ledger" :session (str id "--ledger-g1")}
                     (seats/occupy! :brian id (embodied/embodiment) "canvas.strata/ledger")))
              (is (= :occupied (:reason (ex-data (try (seats/occupy! :brian id (embodied/embodiment) "canvas.strata/ledger")
                                                      (catch Exception e e)))))))
            (is (= [(str id "--conductor") (str id "--ledger-g1")] (mapv first @launched)))))))))

(deftest a-writer-in-another-projects-session-is-not-in-the-ensemble
  (with-tmp
    (fn []
      (let [[id] (ledger strata)]
        (launching [_ _] (embodied/carry! :brian id))
        (with-redefs [lifecycle/session-from-cwd (fn [_] {:project "other" :session (str id "--ledger-g1")})
                      lifecycle/session-home-coords-from-cwd (fn [_] nil)]
          (is (= :not-in-ensemble
                 (:reason (ex-data (try (seats/record! :brian id {:format :tension :to :conductor
                                                                  :about ["agg"] :statement "s"})
                                        (catch Exception e e))))))
          (is (= {:as :session :session (str id "--ledger-g1")}
                 (:by (seats/record! :brian id {:format :recut :ids strata :why "w"})))))))))

(deftest a-brief-carries-the-accords-and-what-the-generation-inherited
  (with-tmp
    (fn []
      (let [[id add] (ledger strata)
            ledger-s (str id "--ledger-g1")]
        (launching [launched _retired]
          (embodied/carry! :brian id)
          (seats/conductor! :brian id)
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
        (in-session (str id "--seats-g1")
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
          (seats/conductor! :brian id)
          (is (= [(str "/wt/" id "--ledger-g1") (str "/wt/" id "--conductor")] (mapv #(nth % 2) @launched))))))))

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
            (is (= :live (:substrate (session/read-session :brian id (str id "--ledger-g1")))) "left running")
            (add :design (a-design strata :supersedes {:seq 4 :why "re-cut"}))
            (reset! launched [])
            (embodied/carry! :brian id)
            (is (= #{(str id "--ledger-g1") (str id "--seats-g1")} (set @retired)) "retired before seating")
            (is (= :archived (:substrate (session/read-session :brian id (str id "--seats-g1")))))
            (is (= [(str id "--ledger-g2") (str id "--seats-g2")] (mapv first @launched)))))))))
