(ns nido.core.release
  "The core daemon as it runs: one release of the code, assembled from the core's modules, started
   by the supervisor, and replaced only through a gated restart.

   Where it lives, under the nido home's `core/`:
     core.db               the store
     core.sock             the command socket
     turns/                the turns' run directories
     workstreams/<ws>/     a conductor turn's working directory
     releases/<rev>/       one release: deps.edn, src/, resources/
     current, previous     links to the release running and the one before it
     release.edn           {:rev :pid :started-at}, written once the socket is bound

   A START is the only recovery path (see `run!`): open the store, pass the health gate, take back
   every turn recorded running, hand the rules and the provisioner every workstream not closed,
   and only then bind the socket. Nothing is committed before the gate passes, and nothing needs a
   shutdown step.

   An UPGRADE (`upgrade!`) is a restart onto a new release that keeps the previous one: the new
   release goes live only once its daemon binds the socket, and when it does not the previous
   release is restored and started. A release that changes the fold has the stored state rebuilt
   under the new fold while neither release serves, and rebuilt under the old fold again before
   the old release is restored.

   This namespace loads under bb, for the upgrade verb; the daemon's parts are required only when
   it runs."
  (:refer-clojure :exclude [run!])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ── where it lives ───────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat] :string]} nido-home
  "The nido home: NIDO_HOME, else ~/.nido."
  []
  (or (not-empty (System/getenv "NIDO_HOME")) (str (System/getProperty "user.home") "/.nido")))

(defn ^{:malli/schema [:=> [:cat [:? :string]] :map]} paths
  "The core's paths under nido home `home`."
  ([] (paths (nido-home)))
  ([home]
   (let [core (str home "/core")]
     {:home home :core core :db (str core "/core.db") :socket (str core "/core.sock")
      :turns (str core "/turns") :workstreams (str core "/workstreams")
      :releases (str core "/releases") :current (str core "/current") :previous (str core "/previous")
      :release-file (str core "/release.edn")})))

(defn- link-target [link]
  (let [p (.toPath (io/file link))]
    (when (java.nio.file.Files/isSymbolicLink p)
      (str (java.nio.file.Files/readSymbolicLink p)))))

(defn- relink! [link target]
  (let [p (.toPath (io/file link))]
    (java.nio.file.Files/deleteIfExists p)
    (when target
      (java.nio.file.Files/createSymbolicLink p (.toPath (io/file target))
                                              (make-array java.nio.file.attribute.FileAttribute 0)))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :string]]} current-release
  "The directory of the release `current` points at, or nil."
  [ps]
  (link-target (:current ps)))

;; ── the health gate ──────────────────────────────────────────────────────────

(defn- answers? [argv]
  (try (let [p (.start (doto (ProcessBuilder. ^java.util.List argv) (.redirectErrorStream true)))]
         (future (slurp (.getInputStream p)))
         (and (.waitFor p 120 java.util.concurrent.TimeUnit/SECONDS) (zero? (.exitValue p))))
       (catch Exception _ false)))

(defn ^{:malli/schema [:=> [:cat :map :map] [:sequential :map]]} gate
  "Why store `s` and this release may not serve, as data; empty when they may. The store opened at
   its schema (it is open), a sample of workstreams folds to the state stored for them, and each
   executor and the session verb answer. `checks` names the executors' and verb's argv."
  [s {:keys [sample probes] :or {sample 50}}]
  (let [store-state (requiring-resolve 'nido.core.store/state)
        store-events (requiring-resolve 'nido.core.store/events)
        wss (take-last sample ((requiring-resolve 'nido.core.store/workstreams) s))
        fold (requiring-resolve 'nido.core.domain/fold)]
    (vec (concat
          (for [ws wss :when (not= (fold (store-events s ws)) (store-state s ws))]
            {:problem :fold-differs :ws ws})
          (for [[what argv] probes :when (not (answers? argv))]
            {:problem :does-not-answer :what what})))))

;; ── a start ──────────────────────────────────────────────────────────────────

(defn- live-pid? [f]
  (let [pid (try (parse-long (str/trim (slurp f))) (catch Exception _ nil))
        h   (when pid (java.lang.ProcessHandle/of pid))]
    (boolean (and h (.isPresent h) (.isAlive ^java.lang.ProcessHandle (.get h))))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :keyword]]} alongside
  "Why the core may not start over the nido home of `ps` beside the old coordinator, or nil: the
   cut-over that stopped and disabled it has not run there (its marker is absent), or the old
   daemon's pid file names a live process."
  [ps]
  (cond
    (not (.exists (io/file (:core ps) "cut-over.edn")))              :not-cut-over
    (live-pid? (io/file (:home ps) "coordinator" "coordinator.pid")) :old-coordinator-running))

(defn- log [& xs] (binding [*out* *err*] (apply println "nido.core.release:" xs) (flush)))

(defn ^{:malli/schema [:=> [:cat :map] :any]} run!
  "Run the core daemon until the process is killed. Options:
     :home       the nido home (default `nido-home`)
     :rev        this release's name, written to release.edn
     :nido-dir   the nido checkout whose bb.edn holds the session verb and the conductor tools
     :bb-bin     bb's path
     :executors  {kw executor}; default {:claude (nido.executor.claude/executor {})}
     :probes     {what argv} the gate runs; default the claude CLI and the session verb
     :wake-cap   see nido.core.buffer/start
   Exits 3 without binding the socket when the gate refuses, or when the old coordinator could
   run beside it (`alongside`)."
  [{:keys [home rev nido-dir bb-bin executors probes wake-cap] :as opts}]
  (let [ps (paths (or home (nido-home)))
        req #(requiring-resolve %)
        _ (when-let [why (alongside ps)]
            (log "refused to start:" (name why))
            (System/exit 3))
        s ((req 'nido.core.store/open) (:db ps))
        probes (or probes {:claude ["claude" "--version"]
                           :session-verb [(or bb-bin "bb") "--config" (str nido-dir "/bb.edn")
                                          "nido:session:provision" ":check" "true"]})
        refused (gate s {:probes probes})]
    (when (seq refused)
      (log "gate refused:" (pr-str refused))
      ((req 'nido.core.store/close) s)
      (System/exit 3))
    (let [f ((req 'nido.core.feed/create))
          parts (atom nil)
          publish (fn [cs]
                    ((req 'nido.core.feed/publish!) f cs)
                    ((req 'nido.core.turn/changed!) (:runner @parts) cs)
                    ((req 'nido.core.buffer/changed!) (:buffer @parts) cs)
                    ((req 'nido.core.provision/changed!) (:provision @parts) cs))
          w ((req 'nido.core.writer/start) s {:publish publish})
          submit #((req 'nido.core.writer/submit!) w %)
          executors (or executors {:claude ((req 'nido.executor.claude/executor) {})})
          runner ((req 'nido.core.turn/start) {:root (:turns ps) :executors executors :submit submit})
          buffer ((req 'nido.core.buffer/start)
                  {:submit submit :wake-cap (or wake-cap 4)
                   :spec-fn ((req 'nido.core.buffer/conductor-spec-fn)
                             {:socket (:socket ps) :bb-bin bb-bin :src (some-> nido-dir (str "/src"))
                              :ws-dir (fn [ws] (let [d (io/file (:workstreams ps) ws)] (.mkdirs d) (str d)))})})
          prov ((req 'nido.core.provision/start) {:submit submit :nido-dir nido-dir :bb-bin bb-bin})
          ;; Each at its own last commit's position, so a rule's key comes out as it did then.
          ;; A closed one too while its sessions are still to be destroyed.
          open-wss (filter #(let [st (:state %)]
                              (or (not (:closed? st))
                                  (seq ((req 'nido.core.domain/sessions-owed) st))
                                  (seq ((req 'nido.core.domain/attempts-pending) st))))
                           ((req 'nido.core.store/changed-since) s 0))]
      (reset! parts {:runner runner :buffer buffer :provision prov})
      ;; Take back every turn recorded running, then hand the rules and the provisioner each open
      ;; workstream as a commit would: what they owed at a kill is issued again, keyed.
      (doseq [{:keys [ws state]} open-wss]
        ((req 'nido.core.turn/take-back!) runner ws state))
      ((req 'nido.core.buffer/changed!) buffer open-wss)
      ((req 'nido.core.provision/changed!) prov open-wss)
      ((req 'nido.core.api/serve!) {:socket (:socket ps) :submit submit
                                   :open-session #((req 'nido.core.feed/session) f (:db ps))})
      (spit (:release-file ps) (pr-str {:rev rev :pid (.pid (java.lang.ProcessHandle/current))
                                        :started-at (str (java.time.Instant/now))}))
      (log "serving" (:socket ps) "release" rev "with" (count open-wss) "open workstreams")
      @(promise))))

;; ── releases and the upgrade ─────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :map :string :string] :string]} install-release!
  "Copy checkout `from-dir`'s deps.edn, src/ and resources/ into release `rev`, and answer its
   directory. A release already installed is left as it is."
  [ps from-dir rev]
  (let [dest (io/file (:releases ps) rev)]
    (when-not (.exists dest)
      (let [tmp (io/file (:releases ps) (str "." rev ".tmp"))]
        (doseq [part ["deps.edn" "src" "resources"]
                :let [src (io/file from-dir part)]
                :when (.exists src)]
          (doseq [^java.io.File f (file-seq src) :when (.isFile f)]
            (let [rel (subs (.getPath f) (count (str from-dir)))
                  out (io/file tmp (str/replace rel #"^/" ""))]
              (io/make-parents out)
              (io/copy f out))))
        (.renameTo tmp dest)))
    (str dest)))

(defn ^{:malli/schema [:=> [:cat [:maybe :string] :string] :boolean]} fold-changed?
  "Whether release `b` folds events differently from release `a`: their domains differ."
  [a b]
  (let [dom #(let [f (io/file % "src/nido/core/domain.clj")] (when (.exists f) (slurp f)))]
    (not= (some-> a dom) (dom b))))

(defn ^{:malli/schema [:=> [:cat :map :int] :boolean]} serving?
  "Whether the daemon answers on the socket within `timeout-ms`, as release `rev` when given."
  [{:keys [socket release-file rev]} timeout-ms]
  (let [end (+ (System/currentTimeMillis) timeout-ms)
        connect (requiring-resolve 'nido.core.api/connect)
        request (requiring-resolve 'nido.core.api/request)
        disconnect (requiring-resolve 'nido.core.api/disconnect)]
    (loop []
      (let [ok (try (let [c (connect socket)]
                      (try (and (:workstreams (request c {:op :workstreams}))
                                (or (nil? rev)
                                    (= rev (:rev (edn/read-string (slurp release-file))))))
                           (finally (disconnect c))))
                    (catch Exception _ false))]
        (cond ok true
              (< (System/currentTimeMillis) end) (do (Thread/sleep 500) (recur))
              :else false)))))

(defn ^{:malli/schema [:=> [:cat :map] :map]} upgrade!
  "Make release `rev` of checkout `from-dir` live, or restore the one that ran. Options:
     :home :from-dir :rev
     :stop!       (fn []) stop the supervised daemon and wait for it to exit
     :start!      (fn []) start it again; it runs whatever `current` points at
     :rebuild!    (fn [release-dir]) rebuild the stored state under that release's fold
     :timeout-ms  how long the new release has to bind the socket (default 3 minutes)
   Answers {:live rev} or {:restored previous-rev :why ...}."
  [{:keys [home from-dir rev stop! start! rebuild! timeout-ms] :or {timeout-ms 180000}}]
  (let [ps (paths (or home (nido-home)))
        old (current-release ps)
        new (install-release! ps from-dir rev)
        rebuild? (fold-changed? old new)]
    (stop!)
    (when rebuild? (rebuild! new))
    (relink! (:previous ps) old)
    (relink! (:current ps) new)
    (start!)
    (if (serving? (assoc ps :rev rev) timeout-ms)
      {:live rev}
      (do (stop!)
          (when (and rebuild? old) (rebuild! old))
          (relink! (:current ps) old)
          (when old (start!))
          {:restored (some-> old io/file .getName)
           :why (str "release " rev " did not bind the socket within " (quot timeout-ms 1000) "s")}))))

(defn ^:export -main
  "`run <nido-dir>`: run the daemon, as the release it runs from (its working directory's real
   name — the supervisor runs it in `current`). `rebuild`: rebuild the stored state under this
   release's fold. `gate <nido-dir>`: print what the gate would refuse."
  [& [cmd a]]
  (case cmd
    "run"     (run! {:rev (str (.getFileName (.toRealPath (.toPath (io/file (System/getProperty "user.dir")))
                                                          (make-array java.nio.file.LinkOption 0))))
                     :nido-dir a})
    "rebuild" (let [s ((requiring-resolve 'nido.core.store/open) (:db (paths)))]
                (println "rebuilt" ((requiring-resolve 'nido.core.store/rebuild!) s) "workstreams")
                ((requiring-resolve 'nido.core.store/close) s))
    "gate"    (let [s ((requiring-resolve 'nido.core.store/open) (:db (paths)))]
                (prn (gate s {:probes {:session-verb ["bb" "--config" (str a "/bb.edn") "nido:session:provision" ":check" "true"]}}))
                ((requiring-resolve 'nido.core.store/close) s))))
