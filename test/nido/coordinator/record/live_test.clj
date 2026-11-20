(ns nido.coordinator.record.live-test
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.coordinator.record.drain :as drain]
   [nido.coordinator.record.live :as live]
   [nido.coordinator.record.session :as session]
   [nido.coordinator.record.session-events :as events]
   [nido.coordinator.record.workstream :as ws]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))]
           (f tmp))
         (finally (live/stop!) (fs/delete-tree tmp)))))

(defn- new-ws [] (:id (ws/create! :p {:external-refs []})))

(defn- facet [id]
  {:session-id id :bg-id nil :origin :nido :driver :nido :pending nil :queued nil :own-work? false
   :policy {:auto-respawn? false} :driving-since nil :announced-blocked? false :recovered-boot nil})

(defn- settle!
  "Run censuses until start's everything-moved has been passed on and nothing more moves."
  []
  (live/census!) (live/census!))

(defn- tick-on!
  "Let the file system's clock move on, so a write lands with a stamp of its own."
  []
  (Thread/sleep 15))

(deftest unstarted-it-answers-every-record-from-disk
  (with-tmp
    (fn [_]
      (let [a (new-ws) b (new-ws)]
        (is (= (set [a b]) (set (live/subjects :p))))
        (is (nil? (live/census!)))
        (is (true? (live/moved?)))))))

(deftest the-first-pass-after-a-start-visits-everything-and-then-only-subjects
  (with-tmp
    (fn [_]
      (let [quiet  (new-ws)
            active (new-ws)]
        (ws/activate! :p active nil)
        (live/start! [:p])
        (live/census!)
        (is (= #{quiet active} (set (live/subjects :p)))
            "what happened while no daemon ran is acted on once")
        (live/census!)
        (is (= [active] (live/subjects :p)) "an activated workstream is a subject; a quiet one is not")
        (is (false? (live/moved?)))))))

(deftest a-write-to-a-non-subject-moves-it-into-the-next-pass
  (with-tmp
    (fn [_]
      (let [quiet (new-ws) other (new-ws)]
        (with-redefs [live/full-census-ms 0]
          (live/start! [:p])
          (settle!)
          (is (= [] (live/subjects :p)))
          (tick-on!)
          (ws/append-entry! :p quiet {:kind :intent} (pr-str {:format :intent :goal "g" :done-when ["d"]}))
          (is (= #{[:p quiet]} (:ws (live/census!))))
          (is (= [quiet] (live/subjects :p)) "the written one, and no other non-subject")
          (is (not-any? #{other} (live/subjects :p)))
          (live/census!)
          (is (= [] (live/subjects :p)) "moved for one pass only"))))))

(deftest a-session-record-rewrite-moves-its-workstream
  (with-tmp
    (fn [_]
      (let [w (new-ws)]
        (session/create! :p w {:name "s" :weight :light :autonomy nil})
        (with-redefs [live/full-census-ms 0]
          (live/start! [:p])
          (settle!)
          (tick-on!)
          (session/update-claude! :p w "s" (constantly (facet "conv-0")))
          (is (contains? (:ws (live/census!)) [:p w])))))))

(deftest a-new-workstream-is-found-without-waiting-for-a-full-census
  (with-tmp
    (fn [_]
      (new-ws)
      (live/start! [:p])
      (settle!)
      (tick-on!)
      (let [fresh (new-ws)]
        (is (contains? (:ws (live/census!)) [:p fresh]))
        (is (some #{fresh} (live/subjects :p)))))))

(deftest a-written-conversation-moves-its-workstream-and-a-listed-one-keeps-it-a-subject
  (with-tmp
    (fn [_]
      (let [w (new-ws)]
        (session/create! :p w {:name "s" :weight :light :autonomy nil})
        (session/update-claude! :p w "s" (constantly (facet "conv-1")))
        (live/start! [:p])
        (settle!)
        (is (= [] (live/subjects :p)) "no home, not listed: nothing keeps it live")
        (tick-on!)
        (events/append! "conv-1" {:event :prompt :cwd "/x"})
        (let [moved (live/census!)]
          (is (contains? (:logs moved) "conv-1") "a log's first event moves the events directory")
          (is (contains? (:ws moved) [:p w]) "its log was written, so the pass reads it"))
        (live/census!)
        (is (= [] (live/subjects :p)) "written once is moved once, not a subject")
        (live/listed! #{"conv-1"})
        (is (= [w] (live/subjects :p)) "a conversation Claude Code lists keeps it a subject")))))

(deftest an-activation-a-close-ended-is-no-subject
  (with-tmp
    (fn [_]
      (let [w (new-ws)]
        (ws/activate! :p w nil)
        (ws/close! :p w :done)
        (live/start! [:p])
        (settle!)
        (is (= [] (live/subjects :p)))))))

(deftest an-open-drain-and-its-slices-are-subjects-and-a-moved-slice-moves-its-drain
  (with-tmp
    (fn [_]
      (let [d    (drain/start-drain! :p {:established-by {:ws-id "ws-pattern"} :exit "gone"})
            prop (drain/propose-recipe! :p d {:reason "r" :recipe {:steps ["s"] :ready-gates ["g"]
                                                                   :standing-orders [] :counter {:command "c"}}})
            v    (drain/approve-recipe! :p d prop)
            c    (drain/record-census! :p d {:recipe v :remaining 5 :groups []})
            plan (drain/plan-slice! :p d {:sites ["x"] :rests-on [] :census c})
            sl   (drain/start-slice! :p d plan :nido)]
        (with-redefs [live/full-census-ms 0]
          (live/start! [:p])
          (settle!)
          (is (= #{d sl} (set (live/subjects :p))))
          (tick-on!)
          (ws/append-entry! :p sl {:kind :note} "landed")
          (is (contains? (:ws (live/census!)) [:p d]) "the drain moves with its slice"))))))

(deftest the-event-log-index-answers-for-the-directory-while-started
  (with-tmp
    (fn [_]
      (events/append! "c" {:event :prompt :cwd "/x" :prompt "hi" :at "2026-11-20T10:00:00Z"})
      (let [from-disk (events/conversations 0)]
        (live/start! [:p])
        (is (= from-disk (events/conversations 0)))
        (is (= ["c"] (live/logs-written-since 0)))
        (live/stop!)
        (is (nil? (live/logs-written-since 0)))))))
