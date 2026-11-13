(ns nido.session.preview
  "A session's code served as a dashboard, by a process of its own: how a person sees what an
   agent changed before it lands.

   HIDES how a preview is built and kept: its classpath — the `src` and `resources` of the
   session's runtime checkout, then the dependency jars of the process asking — the port it holds
   for as long as this process lives, and that a refresh is a sync of the checkout followed by a
   restart of the process.

   A restart rather than a reload: a fresh process reads the checkout as it is, so what the preview
   shows never depends on what an earlier version of the code left loaded. It costs under a second,
   and it is why the preview cannot hold anything that must outlive a turn — a conversation lives
   in `nido.session.chat`, in the long-lived process, for exactly that reason.

   Only nido can be previewed this way: the process runs nido's own dashboard task out of the
   checkout. A preview whose process dies is started again by the next `preview-url`. Its pid and
   port are written under nido's home, so a process left by a dashboard that has since restarted is
   stopped, and its port reused, rather than leaked."
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [nido.platform.core :as core]
            [nido.session.lifecycle :as lifecycle])
  (:import (java.net ServerSocket Socket)))

(defonce ^:private previews
  ;; [project session] -> {:port int :proc process :code dir}
  (atom {}))

(def ^:private start-timeout-ms 20000)

(defn- free-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- listening? [port]
  (try (with-open [_ (Socket. "127.0.0.1" (int port))] true) (catch Exception _ false)))

(defn- classpath
  "The checkout's own paths, then every jar on this process's classpath — the dependencies a
   nido process was started with, which the checkout shares unless it changed `bb.edn`."
  [code-dir]
  (str/join ":" (concat [(str (fs/path code-dir "src")) (str (fs/path code-dir "resources"))]
                        (filter #(str/ends-with? % ".jar")
                                (str/split (or (cp/get-classpath) "") #":")))))

(defn- code-dir
  "Where a session's code is read from: its runtime's checkout after a sync, or its worktree when
   the runtime runs out of one."
  [project session]
  (let [opts {:project (name project)}
        wt   (:wt-path (lifecycle/session-coords session opts))]
    (or (lifecycle/sync! wt) wt)))

(defn- pid-file [[project session]]
  (str (fs/path (core/nido-home) "previews" (str project "--" session ".edn"))))

(defn- stop-recorded!
  "Stop the process a previous dashboard recorded for `k`, if it is still running; the port it
   held, or nil."
  [k]
  (let [f (pid-file k)]
    (when (fs/exists? f)
      (let [{:keys [pid port]} (try (edn/read-string (slurp f)) (catch Exception _ nil))]
        (when-let [h (some-> pid long java.lang.ProcessHandle/of (.orElse nil))]
          (when (.isAlive h) (.destroy h)))
        (fs/delete-if-exists f)
        port))))

(defn- start! [k dir port]
  (let [proc (p/process ["bb" "--classpath" (classpath dir) "-e"
                         (str "(require 'tasks.nido-ui) (tasks.nido-ui/start \":port\" \"" port "\")")]
                        {:dir dir :out :discard :err :discard :shutdown nil})
        deadline (+ (System/currentTimeMillis) start-timeout-ms)]
    (loop []
      (cond
        (listening? port) nil
        (not (p/alive? proc)) (throw (ex-info "The preview process exited while starting"
                                              {:dir dir :port port}))
        (> (System/currentTimeMillis) deadline) (do (p/destroy-tree proc)
                                                    (throw (ex-info "The preview did not start"
                                                                    {:dir dir :port port})))
        :else (do (Thread/sleep 100) (recur))))
    (swap! previews assoc k {:port port :proc proc :code dir})
    (fs/create-dirs (fs/parent (pid-file k)))
    (spit (pid-file k) (pr-str {:pid (.pid ^java.lang.Process (:proc proc)) :port port}))
    (str "http://localhost:" port)))

(defn- stop! [k]
  (when-let [{:keys [proc]} (get @previews k)]
    (p/destroy-tree proc)
    (deref proc 5000 nil)))

(defn ^{:malli/schema [:=> [:cat :any :string] :string]}
  preview-url
  "The URL of `session`'s preview, starting it — after a sync of its runtime checkout — when it is
   not running."
  [project session]
  (let [k [(name project) session]
        {:keys [port proc]} (get @previews k)]
    (locking previews
      (if (and proc (p/alive? proc) (listening? port))
        (str "http://localhost:" port)
        (let [old-port (stop-recorded! k)]
          (start! k (code-dir project session) (or port old-port (free-port))))))))

(defn ^{:malli/schema [:=> [:cat :any :string] :string]}
  refresh!
  "Sync `session`'s runtime checkout and restart its preview on the same port, returning once the
   new process answers. Throws when the sync refuses — the members' working copies conflict —
   leaving the running preview as it was, since nothing is stopped before the sync; and throws
   when the new process does not start, leaving none running."
  [project session]
  (let [k [(name project) session]]
    (locking previews
      (let [dir  (code-dir project session)
            port (or (:port (get @previews k)) (free-port))]
        (stop! k)
        (start! k dir port)))))
