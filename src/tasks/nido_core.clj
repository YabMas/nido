(ns tasks.nido-core
  "`bb nido:core:*` — the core daemon's supervised service and its releases.

     bb nido:core:install            install this checkout as a release and the supervised service
     bb nido:core:upgrade            make this checkout's code the running release, or restore the last
     bb nido:core:status             which release runs, and whether it answers
     bb nido:core:start / stop       the supervised service

   The service runs `clojure -M -m nido.core.release run <nido-dir>` in the nido home's
   `core/current`, so an upgrade only moves that link and restarts it."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [nido.coordinator.daemon.supervisor :as supervisor]
            [nido.core.release :as release]))

(defn- nido-dir [] (str (fs/canonicalize (System/getProperty "user.dir"))))

(defn ^{:malli/schema [:=> [:cat :string] :string]} release-name
  "The name a release of checkout `dir` is installed under: its working-copy commit, and the time,
   so the same commit installed twice is told apart."
  [dir]
  (let [commit (try (str/trim (:out (p/sh {:dir dir} "jj" "log" "--no-graph" "-r" "@" "-T" "commit_id.short(12)")))
                    (catch Exception _ ""))
        stamp (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
                       (java.time.LocalDateTime/now))]
    (str stamp (when (seq commit) (str "-" commit)))))

(defn ^{:malli/schema [:=> [:cat :string] :map]} service-spec
  "The core's supervised service, for the nido checkout `dir`."
  [dir]
  (let [ps (release/paths)
        clj (or (some-> (fs/which "clojure") str) "clojure")]
    {:bb-path (str (fs/which "bb")) :nido-dir (:current ps)
     :log-path (str (:core ps) "/core.log") :path-env (System/getenv "PATH")
     :nido-home (System/getenv "NIDO_HOME")
     :description "nido core"
     :argv [clj "-J--enable-native-access=ALL-UNNAMED" "-M" "-m" "nido.core.release" "run" dir]}))

(defn- service! [verb] (supervisor/with-service :core verb))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]} install
  "Install this checkout as a release, point `current` at it, and install and start the core's
   supervised service."
  [& _]
  (let [ps (release/paths) dir (nido-dir) rev (release-name dir)
        rdir (release/install-release! ps dir rev)]
    (fs/create-dirs (:core ps))
    (fs/delete-if-exists (:current ps))
    (fs/create-sym-link (:current ps) rdir)
    (let [r (service! #(supervisor/install! (service-spec dir)))]
      (println (if (:ok? r) (str "core installed: release " rev) (str "core NOT installed — " (:err r))))
      (when (and (:ok? r) (release/serving? (assoc ps :rev rev) 180000))
        (println "core serving on" (:socket ps))))))

(defn- rebuild! [release-dir]
  (let [r (p/sh {:dir release-dir} "clojure" "-M" "-m" "nido.core.release" "rebuild")]
    (when-not (zero? (:exit r)) (throw (ex-info (str "rebuild failed: " (:err r)) {:dir release-dir})))
    (println " " (str/trim (:out r)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]} upgrade
  "Make this checkout's code the running release through a gated restart; when it does not bind
   the socket, the previous release is restored and started. Exits 1 when it was restored."
  [& _]
  (let [dir (nido-dir) rev (release-name dir)
        r (release/upgrade! {:from-dir dir :rev rev
                             :stop! #(service! supervisor/stop!)
                             :start! #(service! supervisor/start!)
                             :rebuild! rebuild!})]
    (if (:live r)
      (println "core upgraded: release" (:live r) "is live")
      (do (println "core upgrade REFUSED —" (:why r) "— restored" (:restored r))
          (System/exit 1)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]} status
  "Which release the core runs, and whether it answers on its socket."
  [& _]
  (let [ps (release/paths)
        rel (try (edn/read-string (slurp (:release-file ps))) (catch Exception _ nil))]
    (println "current: " (or (some-> (release/current-release ps) fs/file-name) "none"))
    (println "running: " (or (:rev rel) "unknown") (when-let [pid (:pid rel)] (str "(pid " pid ")")))
    (println "serving: " (if (release/serving? ps 2000) "yes" "NO"))
    (println "service: " (pr-str (service! supervisor/status)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]} start [& _] (prn (service! supervisor/start!)))
(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]} stop [& _] (prn (service! supervisor/stop!)))
