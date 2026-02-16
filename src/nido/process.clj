(ns nido.process
  (:require
   [babashka.fs :as fs]
   [babashka.process :refer [shell]]
   [clojure.string :as str]))

(defn process-alive? [pid]
  (zero? (:exit (shell {:continue true :out :string :err :string}
                       "kill" "-0" (str pid)))))

(defn stop-process! [pid]
  (when (process-alive? pid)
    (shell {:continue true} "kill" (str pid))
    (loop [attempt 0]
      (cond
        (not (process-alive? pid)) :stopped
        (>= attempt 20) (do (shell {:continue true} "kill" "-9" (str pid)) :killed)
        :else (do (Thread/sleep 100) (recur (inc attempt)))))))

(defn port-free? [port]
  (try
    (with-open [socket (java.net.ServerSocket.)]
      (.setReuseAddress socket false)
      (.bind socket (java.net.InetSocketAddress. "127.0.0.1" port))
      true)
    (catch Exception _
      false)))

(defn tcp-open? [port]
  (try
    (with-open [socket (java.net.Socket.)]
      (.connect socket (java.net.InetSocketAddress. "127.0.0.1" port) 500)
      true)
    (catch Exception _
      false)))

(defn deterministic-port
  "Compute a deterministic port from a seed string within [low, high)."
  [seed low high]
  (let [h (-> seed hash long (bit-and 0x7fffffff))]
    (+ low (mod h (- high low)))))

(defn find-available-port [preferred-port max-attempts]
  (loop [port preferred-port
         attempts 0]
    (cond
      (port-free? port) port
      (>= attempts max-attempts)
      (throw (ex-info "Could not find free port"
                      {:preferred-port preferred-port :attempts max-attempts}))
      :else (recur (inc port) (inc attempts)))))

(defn quoted [s]
  (str "'" (str/replace s "'" "'\"'\"'") "'"))

(defn log-tail [path lines]
  (if-not (fs/exists? path)
    ""
    (->> (slurp path)
         str/split-lines
         (take-last lines)
         (str/join "\n"))))
