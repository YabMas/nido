(ns tasks.nido-test
  "Run unit tests under test/. Optional :only <ns-prefix> filter.

   Every run is against a nido home of its own. A test that reaches the home it
   was given — a coordinator tick, a session lookup, a registry prune — acts on
   whatever sessions that home names, and the real ~/.nido names live ones: a
   test that stopped a session there stops a person's REPL. So the suite never
   runs in the caller's process. `run` makes a fresh temporary home and runs the
   suite in a child bb whose NIDO_HOME is that home, which every nido-home read
   in the child — and every bb the child starts — resolves to."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [clojure.test :as t]
   [nido.platform.core :as core]
   [nido.platform.task-args :as task-args]))

(def ^:private test-home-env
  "Names the home the parent made for this run. Its presence is what tells the
   child it is the child; its value is what the child checks NIDO_HOME against."
  "NIDO_TEST_HOME")

(defn- discover-test-namespaces
  "Find test namespaces under test/, optionally filtered by prefix."
  [prefix]
  (->> (fs/glob "test" "**/*_test.clj")
       (map str)
       (map #(-> %
                 (str/replace #"^test/" "")
                 (str/replace #"\.clj$" "")
                 (str/replace "_" "-")
                 (str/replace "/" ".")
                 symbol))
       (filter (fn [ns-sym]
                 (or (str/blank? prefix)
                     (str/starts-with? (str ns-sym) prefix))))
       sort))

(def ^:private marker
  "The file `run` writes into every home it makes. The real ~/.nido never holds
   one, so a home without it is somebody's, whatever NIDO_HOME says."
  ".nido-test-home")

(def ^:private fixture-projects
  "The projects every test may assume are registered. Each one's checkout and
   worktrees directory sit inside the test home and start empty, so a test that
   lists a project's sessions finds none rather than a person's."
  ["brian" "nido"])

(defn- seed-home!
  "Make `home` a test home: the marker, and a registry of the fixture projects."
  [home]
  (spit (str (fs/path home marker)) "made by bb nido:test\n")
  (spit (str (fs/path home "projects.edn"))
        (pr-str (into {} (for [p fixture-projects]
                           [p {:directory (str (fs/create-dirs (fs/path home "checkouts" p)))}])))))

(defn ^{:malli/schema [:=> [:cat [:maybe :string] :string] [:maybe :string]]}
  home-refusal
  "Why the suite must not run against `home`, or nil when it may. `expected` is
   the home the parent made (NIDO_TEST_HOME), nil when there was none.

   The only home the tests may use is the one `run` made for this run: the one
   NIDO_TEST_HOME names, carrying the marker `run` writes. The real ~/.nido is
   refused by name as well, so no marker copied into it can admit it."
  [expected home]
  (cond
    (str/blank? expected)
    "no test home was made for this run — start the suite with `bb nido:test`"

    (not= (str (fs/canonicalize expected)) (str (fs/canonicalize home)))
    (str "the nido home the tests would see (" home ") is not the one made for this run ("
         expected ")")

    (= (str (fs/canonicalize home))
       (str (fs/canonicalize (fs/path (System/getProperty "user.home") ".nido"))))
    (str "the nido home the tests would see is the real one (" home ")")

    (not (fs/exists? (fs/path home marker)))
    (str "the nido home the tests would see (" home ") was not made by bb nido:test")))

(defn- run-tests!
  "The child: refuse unless the home is the fresh one made for this run, then
   run the suite in this process. Exits 2 on a refusal, so it never reads as a
   test failure, and never as a pass."
  [opts]
  (when-let [why (home-refusal (System/getenv test-home-env) (core/nido-home))]
    (binding [*out* *err*]
      (println (str "bb nido:test refused: " why)))
    (System/exit 2))
  (let [only (some-> (:only opts) str)
        nses (discover-test-namespaces only)]
    (when (empty? nses)
      (println "No test namespaces found." (when only (str "filter: " only)))
      (System/exit 0))
    (doseq [ns-sym nses]
      (require ns-sym))
    (let [{:keys [fail error]} (apply t/run-tests nses)]
      (System/exit (if (zero? (+ (or fail 0) (or error 0))) 0 1)))))

(defn- run-in-fresh-home!
  "The parent: make a fresh home, run the suite in a child bb pointed at it,
   remove the home, and exit with the child's code.

   Two more roots move into the home with NIDO_HOME. CODEX_HOME, because the
   session registry merges legacy files under it (nido.session.state), so a
   real one there would hand tests real session ports. The temp dir, so the
   private homes tests make for themselves are inside this one and go with it.

   The home is made under /tmp, not the system temp dir: a Unix socket path is
   capped near 104 bytes, macOS's temp dir alone leaves no room for one, and
   tests bind sockets in temp dirs nested two levels below this home."
  [args]
  (let [home (str (fs/create-temp-dir {:dir "/tmp" :prefix "nt-"}))
        tmp  (str (fs/path home "tmp"))
        root (core/nido-source-dir)
        exit (try
               (seed-home! home)
               (fs/create-dirs tmp)
               (:exit (apply p/shell {:continue true
                                      :dir root
                                      :extra-env {"NIDO_HOME"   home
                                                  test-home-env home
                                                  "CODEX_HOME"  (str (fs/path home "codex"))
                                                  "TMPDIR"      tmp}}
                             "bb" (str "-Djava.io.tmpdir=" tmp)
                             "--config" (str (fs/path root "bb.edn")) "nido:test"
                             (map str args)))
               (finally (fs/delete-tree home)))]
    (System/exit exit)))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  run [& args]
  (if (System/getenv test-home-env)
    (run-tests! (second (task-args/split-args args)))
    (run-in-fresh-home! args)))
