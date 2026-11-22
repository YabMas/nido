(ns nido.core.claude-it-test
  "A real Claude Code turn through the whole outside stratum: it asks, the ask ends the run, the
   answer resumes it, and it finishes using the answer. Opt-in, because it spends tokens: run with
   NIDO_CORE_CLAUDE_IT=1."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [nido.core.store :as store]
            [nido.core.turn :as turn]
            [nido.core.writer :as writer]
            [nido.executor.claude :as claude])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- await-until [f ms]
  (let [end (+ (System/currentTimeMillis) ms)]
    (loop [] (or (f) (when (< (System/currentTimeMillis) end) (Thread/sleep 250) (recur))))))

(deftest a-claude-turn-asks-is-answered-and-finishes
  (when (System/getenv "NIDO_CORE_CLAUDE_IT")
    (let [dir (str (Files/createTempDirectory "nido-claude-it-" (make-array FileAttribute 0)))
          work (doto (io/file dir "work") .mkdirs)
          s (store/open (str dir "/core.db"))
          rr (atom nil)
          w (writer/start s {:publish #(turn/changed! @rr %)})
          r (reset! rr (turn/start {:root (str dir "/turns") :executors {:claude (claude/executor {})}
                                    :submit #(writer/submit! w %)}))
          n (atom 0)
          cmd! #(deref (writer/submit! w {:key (str "k" (swap! n inc)) :ws "ws" :command %}) 10000 nil)
          t #(get-in (store/state s "ws") [:turns "t"])]
      (try
        (cmd! {:command :open-unit :unit "u" :project "p" :goal "name the project"})
        (cmd! {:command :start-turn :turn "t" :unit "u"
               :spec {:executor :claude :model "sonnet" :cwd (str work) :deadline-ms 600000
                      :instructions (str "Use nido's ask tool (mcp__nido__ask) to ask whether this project "
                                         "should be named alpha or beta; do not choose yourself. Once you have "
                                         "the answer, write the chosen name alone into name.txt in the current "
                                         "directory, then stop.")}})
        (is (await-until #(= :asked (:status (t))) 300000) (pr-str (t)))
        (is (re-find #"(?i)alpha|beta" (str (get-in (t) [:call :question]))))
        (cmd! {:command :answer-call :turn "t" :answer "beta"})
        (cmd! {:command :resume-turn :turn "t"})
        (is (await-until #(= :completed (:status (t))) 300000) (pr-str (t)))
        (is (= "beta" (some-> (io/file work "name.txt") (#(when (.exists %) (str/trim (slurp %)))))))
        (finally (turn/stop! r) (writer/stop! w) (store/close s))))))
