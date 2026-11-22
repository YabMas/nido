(ns nido.core.conducting-test
  "The conductor end to end: writer, runner, rules and the api the conductor's tools speak to, with
   fake executors whose runs are shell scripts — a conductor's script acts through the real tools."
  (:require [clojure.data.json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nido.core.api :as api]
            [nido.core.buffer :as buffer]
            [nido.core.feed :as feed]
            [nido.core.store :as store]
            [nido.core.turn :as turn]
            [nido.core.writer :as writer])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def fake
  {:launch  (fn [{:keys [run spec]}]
              {:argv ["sh" "-c" (if (= 1 run) (:instructions spec) (:on-resume spec "echo '{:outcome :completed}'"))]})
   :outcome (fn [rdir]
              (let [f (io/file rdir "out")]
                (when (.exists f)
                  (let [x (try (edn/read-string (last (str/split-lines (slurp f)))) (catch Exception _ nil))]
                    (when (map? x) x)))))})

(defn- await-until [f ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop [] (or (f) (when (< (System/currentTimeMillis) end) (Thread/sleep 50) (recur))))))

(defn- sh-quote [s] (str "'" (str/replace s "'" "'\\''") "'"))

(defn- tool-line [sock ws turn tool args]
  (str "bb --classpath " (sh-quote (str (System/getProperty "user.dir") "/src")) " -m nido.core.buffer tool " (sh-quote sock) " " (sh-quote ws) " " (sh-quote turn) " "
       tool " " (sh-quote (clojure.data.json/write-str args)) " >&2"))

(defn- run-core
  "Run `body-fn` against a whole core whose conductor turns are `conductor-script` (fn [sock ws
   turn state items] -> shell). `body-fn` gets {:s :cmd!}."
  [conductor-script body-fn]
  (let [dir (str (Files/createTempDirectory "nido-cond-" (make-array FileAttribute 0)))
        db (str dir "/core.db")
        sock (str "/tmp/nido-cond-" (System/nanoTime) ".sock")
        s (store/open db)
        f (feed/create)
        parts (atom nil)
        w (writer/start s {:publish (fn [cs] (feed/publish! f cs)
                                      (turn/changed! (:runner @parts) cs)
                                      (buffer/changed! (:buffer @parts) cs))})
        srv (api/serve! {:socket sock :submit #(writer/submit! w %) :open-session #(feed/session f db)})
        r (turn/start {:root (str dir "/turns") :executors {:fake fake} :submit #(writer/submit! w %)})
        b (buffer/start {:submit #(writer/submit! w %)
                         :spec-fn (fn [ws t state items]
                                    {:executor :fake :instructions (conductor-script sock ws t state items)})})
        n (atom 0)]
    (reset! parts {:runner r :buffer b})
    (try
      (body-fn {:s s :cmd! #(deref (writer/submit! w {:key (str "person-" (swap! n inc)) :ws "ws" :command %}) 10000 nil)})
      (finally (buffer/stop! b) (turn/stop! r) (api/close! srv) (writer/stop! w) (store/close s)))))

(defn- d-awaiting [s] (filter #(= :open (:status %)) (vals (:operator-asks (store/state s "ws")))))

(defn- conductor-turns [s] (filter #(nil? (:unit %)) (vals (get-in (store/state s "ws") [:turns]))))

(deftest a-unit-s-ask-is-disposed-of-by-the-conductor-and-the-unit-finishes
  (run-core
   (fn [sock ws t _state items]
     (str/join "\n" (concat (for [i items :when (= :turn-asked (:kind i))]
                              (tool-line sock ws t "answer_call" {:turn (:turn i) :answer "use Postgres"}))
                            ["echo '{:outcome :completed}'"])))
   (fn [{:keys [s cmd!]}]
     (cmd! {:command :open-unit :unit "u" :project "p" :goal "pick a database" :session-cwd "/tmp"})
     (cmd! {:command :start-turn :turn "t" :unit "u"
            :spec {:executor :fake :instructions "echo '{:outcome :asked :call {:id \"c\" :question \"which db?\"}}'"
                   :on-resume "echo \"{:outcome :completed :detail {:summary \\\"done\\\"}}\""}})
     (is (await-until #(= :completed (get-in (store/state s "ws") [:turns "t" :status])) 30000)
         "the unit turn asked, the conductor answered, the rules resumed it, and it finished")
     (is (= {:answered "use Postgres"} (get-in (store/state s "ws") [:turns "t" :resolution])))
     (is (await-until #(and (empty? (:inbox (store/state s "ws")))
                            (every? (comp #{:completed} :status) (conductor-turns s))
                            (= 2 (count (conductor-turns s))))
                      30000)
         "the conductor heard the ask, then the finish, each once")
     (is (empty? (d-awaiting s)) "nothing was put to the person"))))

(deftest a-conductor-s-own-ask-goes-to-the-person
  (run-core
   (fn [_sock _ws _t _state _items]
     "echo '{:outcome :asked :call {:id \"c\" :question \"open unit b?\" :explored \"x\" :options [\"yes\" \"no\"] :recommendation \"yes\"}}'")
   (fn [{:keys [s cmd!]}]
     (cmd! {:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/tmp"})
     (cmd! {:command :start-turn :turn "t" :unit "u" :spec {:executor :fake :instructions "echo '{:outcome :completed}'"}})
     (is (await-until #(seq (d-awaiting s)) 30000) "the rules raised the conductor's ask to the person")
     (let [[a] (d-awaiting s)]
       (is (= "open unit b?" (get-in a [:content :question])))
       (cmd! {:command :answer-operator-ask :ask (:id a) :answer "yes"}))
     (is (await-until #(every? (comp #{:completed} :status) (conductor-turns s)) 30000)
         "the person's answer resumed the conductor turn, which finished"))))

(deftest a-conductor-that-keeps-failing-stops-being-woken
  (run-core
   (fn [& _] "echo 'not an outcome'")
   (fn [{:keys [s cmd!]}]
     (cmd! {:command :open-unit :unit "u" :project "p" :goal "g" :session-cwd "/tmp"})
     (cmd! {:command :start-turn :turn "t" :unit "u" :spec {:executor :fake :instructions "echo '{:outcome :completed}'"}})
     (is (await-until #(= 3 (:conductor-failures (store/state s "ws"))) 30000))
     (Thread/sleep 1000)
     (is (= 3 (count (conductor-turns s))) "no fourth wake")
     (is (seq (:inbox (store/state s "ws"))) "and nothing it was told is lost"))))
