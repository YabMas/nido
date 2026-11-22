(ns nido.core.release-test
  "A unit's session provisioned before its first turn, an attempt a kill left made again at start,
   and an upgrade that restores the previous release when the new one does not serve."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [nido.core.api :as api]
            [nido.core.buffer :as buffer]
            [nido.core.feed :as feed]
            [nido.core.provision :as provision]
            [nido.core.release :as release]
            [nido.core.store :as store]
            [nido.core.writer :as writer])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tmp [] (str (Files/createTempDirectory "nido-release-" (make-array FileAttribute 0))))

(defn- await-until [f ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop [] (or (f) (when (< (System/currentTimeMillis) end) (Thread/sleep 50) (recur))))))

(defn- fake-verb [calls]
  (fn [argv _timeout]
    (swap! calls conj argv)
    (let [want (second (drop-while #(not= ":want" %) argv))]
      {:exit 0 :out (str "booting…\n" (pr-str (if (= "up" want) {:cwd (str "/wt/" (last argv))} {:stopped true})))})))

(deftest a-unit-s-session-is-up-before-its-first-turn
  (let [dir (tmp) s (store/open (str dir "/core.db")) calls (atom [])
        parts (atom nil)
        w (writer/start s {:publish (fn [cs] (buffer/changed! (:b @parts) cs) (provision/changed! (:p @parts) cs))})
        submit #(writer/submit! w %)
        b (buffer/start {:submit submit :spec-fn (fn [& _] {:executor :fake :instructions "c"})})
        p (provision/start {:submit submit :nido-dir "/nido" :run-verb (fake-verb calls)})]
    (reset! parts {:b b :p p})
    (try
      @(submit {:key "k1" :ws "ws" :command {:command :open-unit :unit "u1" :project "brian" :goal "g"}})
      (is (await-until #(= :ready (get-in (store/state s "ws") [:units "u1" :session :status])) 5000))
      (is (= "/wt/u1" (get-in (store/state s "ws") [:units "u1" :session :cwd])))
      (is (= ["bb" "--config" "/nido/bb.edn" "nido:session:provision" ":project" "brian" ":want" "up" "u1"]
             (first @calls)))
      (testing "the unit's turn runs in its session"
        (let [o @(submit {:key "k2" :ws "ws" :command {:command :start-turn :turn "t" :unit "u1"
                                                       :spec {:executor :fake :instructions "i" :cwd "/nowhere"}}})]
          (is (= "/wt/u1" (get-in o [:events 0 :spec :cwd])))))
      (finally (buffer/stop! b) (writer/stop! w) (store/close s)))))

(deftest an-attempt-a-kill-left-is-made-again-at-start
  (let [dir (tmp) db (str dir "/core.db") calls (atom [])]
    (let [s (store/open db) w (writer/start s {:publish (fn [_])})]
      @(writer/submit! w {:key "k1" :ws "ws" :command {:command :open-unit :unit "u1" :project "p" :goal "g"}})
      @(writer/submit! w {:key "k2" :ws "ws" :command {:command :record-provision-attempt :unit "u1" :want :up :attempt "a1"}})
      (writer/stop! w) (store/close s))
    (let [s (store/open db)
          w (writer/start s {:publish (fn [_])})
          p (provision/start {:submit #(writer/submit! w %) :nido-dir "/n" :run-verb (fake-verb calls)})]
      (try
        (provision/changed! p (store/changed-since s 0))
        (is (await-until #(= :ready (get-in (store/state s "ws") [:units "u1" :session :status])) 5000))
        (is (= 1 (count @calls)))
        (finally (writer/stop! w) (store/close s))))))

(deftest a-failed-bring-up-reaches-the-conductor
  (let [dir (tmp) s (store/open (str dir "/core.db")) w (writer/start s {:publish (fn [_])})
        p (provision/start {:submit #(writer/submit! w %) :nido-dir "/n"
                            :run-verb (fn [_ _] {:exit 1 :out "{:error \"no such project\"}"})})]
    (try
      @(writer/submit! w {:key "k1" :ws "ws" :command {:command :open-unit :unit "u1" :project "p" :goal "g"}})
      @(writer/submit! w {:key "k2" :ws "ws" :command {:command :record-provision-attempt :unit "u1" :want :up :attempt "a1"}})
      (provision/changed! p (store/changed-since s 0))
      (is (await-until #(= :failed (get-in (store/state s "ws") [:units "u1" :session :status])) 5000))
      (is (= [{:kind :session-failed :unit "u1" :want :up :detail "no such project"}]
             (map #(dissoc % :id) (vals (:inbox (store/state s "ws"))))))
      (finally (writer/stop! w) (store/close s)))))

(defn- checkout [dir domain]
  (io/make-parents (io/file dir "src/nido/core/domain.clj"))
  (spit (io/file dir "src/nido/core/domain.clj") domain)
  (spit (io/file dir "deps.edn") "{}")
  dir)

(deftest an-upgrade-whose-release-does-not-serve-restores-the-previous
  (let [home (tmp) ps (release/paths home)
        servers (atom nil) rebuilt (atom [])
        serve! (fn [rev]
                 (spit (:release-file ps) (pr-str {:rev rev}))
                 (let [f (feed/create)
                       _ (io/make-parents (io/file (:db ps)))
                       s (store/open (:db ps))]
                   (reset! servers {:s s :api (api/serve! {:socket (:socket ps) :submit (fn [_] (promise))
                                                           :open-session #(feed/session f (:db ps))})})))
        stop! #(when-let [{:keys [s api]} @servers] (api/close! api) (store/close s) (reset! servers nil))
        ;; the supervisor runs whatever `current` points at; a release named bad never serves
        start! #(let [cur (release/current-release ps)]
                  (when-not (.endsWith (str cur) "bad") (serve! (.getName (io/file cur)))))
        opts {:home home :stop! stop! :start! start! :rebuild! #(swap! rebuilt conj (.getName (io/file %)))
              :timeout-ms 2000}]
    (try
      (is (= {:live "r1"} (release/upgrade! (assoc opts :from-dir (checkout (tmp) "fold 1") :rev "r1"))))
      (testing "a release that serves goes live, and its fold rebuilt first when it changed"
        (is (= {:live "r2"} (release/upgrade! (assoc opts :from-dir (checkout (tmp) "fold 2") :rev "r2"))))
        (is (= ["r1" "r2"] @rebuilt)))
      (testing "one that does not is undone: the old fold rebuilt, the old release restored"
        (let [r (release/upgrade! (assoc opts :from-dir (checkout (tmp) "fold 3") :rev "bad"))]
          (is (= "r2" (:restored r)))
          (is (= ["r1" "r2" "bad" "r2"] @rebuilt))
          (is (= "r2" (.getName (io/file (release/current-release ps)))))
          (is (release/serving? (assoc ps :rev "r2") 2000))))
      (finally (stop!)))))
