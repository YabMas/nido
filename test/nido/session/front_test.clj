(ns nido.session.front-test
  "The on-demand front, driven in-process: a fake JVM — an echo server on a filesystem socket —
   stands in for the one `process/start-jvm!` would start, so what is checked is the front's own
   rule: what starts the JVM, how often, what reaches it, and what stops it."
  (:require
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [nido.platform.core :as core]
   [nido.session.cold-starts :as cold-starts]
   [nido.session.failure :as failure]
   [nido.session.front :as front]
   [nido.session.lifecycle :as lifecycle]
   [nido.session.services.eval :as eval-svc]
   [nido.session.services.process :as process]
   [nido.session.state :as state])
  (:import
   [java.net ServerSocket Socket UnixDomainSocketAddress StandardProtocolFamily]
   [java.nio ByteBuffer]
   [java.nio.channels ServerSocketChannel]))

(defn- echo-server!
  "An echo server on filesystem socket `path`, standing in for a JVM's nREPL. Returns a fn that
   stops it."
  [path]
  (fs/delete-if-exists path)
  (let [ss (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
    (.bind ss (UnixDomainSocketAddress/of (str path)))
    (future
      (try
        (loop []
          (let [c (.accept ss)]
            (future
              (try
                (let [b (ByteBuffer/allocate 4096)]
                  (loop []
                    (.clear b)
                    (let [n (.read c b)]
                      (when (pos? n)
                        (.flip b)
                        (while (.hasRemaining b) (.write c b))
                        (recur)))))
                (catch Exception _ nil)
                (finally (.close c))))
            (recur)))
        (catch Exception _ nil)))
    (fn [] (.close ss) (fs/delete-if-exists path))))

(defn- free-port [] (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- fixture
  "A front over a fake runtime, its listener, and a log of what it did."
  [{:keys [idle-ms start-fails? start-ms] :or {idle-ms 60000 start-ms 0}}]
  (let [dir    (str (fs/create-temp-dir))
        sock   (str (fs/path dir "repl.sock"))
        cfg    {:instance-id "proj--t" :service :repl :project-name "proj" :project-dir dir
                :nrepl-port (free-port) :apps [] :socket sock :port-file ".nrepl-port"
                :idle-ms idle-ms}
        log    (atom [])
        server (atom nil)
        fr     (atom {:cfg cfg :phase :absent :conns 0 :last-traffic (System/currentTimeMillis)})
        ss     (#'front/listen! {:kind :repl :port (:nrepl-port cfg)})]
    (future (loop [] (let [c (.accept ss)] (future (#'front/serve! fr {:kind :repl :port (:nrepl-port cfg)} c)) (recur))))
    {:cfg cfg :front fr :log log :server server :ss ss :dir dir
     :redefs {#'process/start-jvm!         (fn [_] (swap! log conj :start)
                                             (Thread/sleep start-ms)
                                             (when start-fails? (throw (ex-info "boom: classpath" {})))
                                             (reset! server (echo-server! sock))
                                             4242)
              #'process/stop-jvm!          (fn [_] (swap! log conj :stop)
                                             (when-let [f @server] (f) (reset! server nil)))
              #'lifecycle/with-runtime-lock (fn [_ _ f] (f))
              #'state/read-session         (fn [_] {:name "t" :service-defs [] :service-states {}})
              #'state/write-session!       (fn [& _] nil)
              #'state/update-runtime-entries! (fn [_ f] (swap! log conj [:registry (f {})]))
              #'cold-starts/record!        (fn [e] (swap! log conj [:record e]) e)
              #'failure/record!            (fn [a e] (swap! log conj [:failure (:origin a) (ex-message e)]) {:id "f"})
              #'core/log-step              (constantly nil)}}))

(defn- call
  "Send `s` on a fresh connection to `port` and read the reply, `timeout-ms` at most; nil when
   none comes."
  [port s timeout-ms]
  (with-open [c (Socket. "127.0.0.1" (int port))]
    (.setSoTimeout c timeout-ms)
    (.write (.getOutputStream c) (.getBytes ^String s))
    (.flush (.getOutputStream c))
    (let [b (byte-array 4096)
          n (try (.read (.getInputStream c) b) (catch java.net.SocketTimeoutException _ -1))]
      (when (pos? n) (String. b 0 n)))))

(defn- cleanup [{:keys [ss server dir]}]
  (.close ^ServerSocket ss)
  (when-let [f @server] (f))
  (fs/delete-tree dir))

(defn- await-phase [fr phase]
  (loop [n 0] (when (and (< n 100) (not= phase (:phase @fr))) (Thread/sleep 50) (recur (inc n)))))

(deftest a-connection-that-sends-nothing-starts-no-jvm
  (let [{:keys [cfg log redefs] :as fx} (fixture {})]
    (try
      (with-redefs-fn redefs
        (fn []
          (with-open [_ (Socket. "127.0.0.1" (int (:nrepl-port cfg)))])
          (Thread/sleep 300)
          (is (empty? @log) "a probe that opens and closes starts nothing")))
      (finally (cleanup fx)))))

(deftest the-first-byte-starts-the-jvm-once-and-the-call-is-relayed
  (let [{:keys [cfg log redefs front] :as fx} (fixture {:start-ms 400})]
    (try
      (with-redefs-fn redefs
        (fn []
          (let [calls (doall (for [i (range 4)] (future (call (:nrepl-port cfg) (str "ping" i) 5000))))]
            (is (= #{"ping0" "ping1" "ping2" "ping3"} (set (map deref calls)))
                "every client's bytes, held through the start, reach the JVM and its reply comes back"))
          (is (= 1 (count (filter #{:start} @log))) "four clients at once start one JVM")
          (await-phase front :running)
          (let [[_ rec] (some #(when (and (vector? %) (= :record (first %))) %) @log)]
            (is (= :ready (:outcome rec)))
            (is (= :repl (:port rec)))
            (is (string? (:requested-at rec)))
            (is (string? (:ended-at rec)))
            (is (not (contains? rec :duration-ms)) "the record stores the instants, never a duration"))
          (is (some #{[:registry {:repl-pid 4242}]} @log) "the registry names the JVM's pid while it runs")
          (is (= (str (:nrepl-port cfg)) (slurp (str (fs/path (:project-dir cfg) ".nrepl-port"))))
              ".nrepl-port names the front's port")
          (is (= "again" (call (:nrepl-port cfg) "again" 2000)) "a later call is relayed to the same JVM")
          (is (= 1 (count (filter #{:start} @log))))))
      (finally (cleanup fx)))))

(deftest idle-stops-only-with-no-traffic-and-no-open-connection
  (let [{:keys [cfg log redefs front] :as fx} (fixture {:idle-ms 200})]
    (try
      (with-redefs-fn redefs
        (fn []
          (is (= "x" (call (:nrepl-port cfg) "x" 3000)))
          (await-phase front :running)
          (testing "an open connection holds the JVM however long it is quiet"
            (with-open [c (Socket. "127.0.0.1" (int (:nrepl-port cfg)))]
              (Thread/sleep 100)
              (Thread/sleep 400)
              (#'front/stop-idle! front)
              (is (not-any? #{:stop} @log))
              (is (= :running (:phase @front)))
              (.close c)))
          (Thread/sleep 300)
          (#'front/stop-idle! front)
          (is (= 1 (count (filter #{:stop} @log))) "idle and unconnected, the JVM is stopped")
          (is (= :absent (:phase @front)))
          (is (some #{[:registry {}]} @log) "and the registry no longer names its pid")
          (testing "the next byte starts it again"
            (is (= "y" (call (:nrepl-port cfg) "y" 3000)))
            (is (= 2 (count (filter #{:start} @log)))))))
      (finally (cleanup fx)))))

(deftest a-failed-start-is-kept-recorded-and-closes-its-client
  (let [{:keys [cfg log redefs front] :as fx} (fixture {:start-fails? true})]
    (try
      (with-redefs-fn redefs
        (fn []
          (is (nil? (call (:nrepl-port cfg) "x" 3000)) "the held connection is closed, no reply")
          (await-phase front :absent)
          (Thread/sleep 100)
          (is (some #(and (vector? %) (= :failure (first %)) (= {:kind :on-demand :port :repl} (second %))) @log)
              "the failure is kept, its origin the on-demand trigger")
          (let [[_ rec] (some #(when (and (vector? %) (= :record (first %))) %) @log)]
            (is (= :failed (:outcome rec)))
            (is (= "boom: classpath" (:cause rec))))
          (is (= :absent (:phase @front)) "and the next byte may try again")))
      (finally (cleanup fx)))))

(deftest a-start-that-outlasts-its-first-client-is-recorded-as-given-up-on
  (let [{:keys [cfg log redefs front] :as fx} (fixture {:start-ms 800})]
    (try
      (with-redefs-fn redefs
        (fn []
          (is (nil? (call (:nrepl-port cfg) "x" 200)) "the client gives up before the JVM is ready")
          (await-phase front :running)
          (let [[_ rec] (some #(when (and (vector? %) (= :record (first %))) %) @log)]
            (is (= :ready-after-give-up (:outcome rec))))
          (is (= "z" (call (:nrepl-port cfg) "z" 2000)) "its retry is relayed like any call")))
      (finally (cleanup fx)))))

(deftest the-cold-start-log-derives-duration-and-survives-by-project
  (let [entry {:project "p" :session "s" :instance-id "p--s" :port :repl
               :requested-at "2026-11-27T10:00:00Z" :ended-at "2026-11-27T10:01:05.500Z"
               :outcome :ready}]
   (with-redefs [core/nido-home (constantly (str (fs/create-temp-dir)))]
    (cold-starts/record! entry)
    (cold-starts/record! (assoc entry :session "other"))
    (is (= [entry] (cold-starts/starts "p" "s")))
    (is (= 2 (count (cold-starts/starts "p" nil))))
    (is (= 65500 (cold-starts/duration-ms entry)))
    (is (thrown? clojure.lang.ExceptionInfo (cold-starts/record! (assoc entry :outcome :failed)))
        "a failed start with no cause is no record")
    (is (not (fs/starts-with? (cold-starts/log-path "p") (state/state-dir)))
        "kept outside every instance's state, so a destroy keeps it"))))

(deftest eval-on-demand-reserves-ports-and-starts-no-app
  (let [started (atom 0)]
    (with-redefs [eval-svc/start-app! (fn [& _] (swap! started inc) true)
                  core/log-step       (constantly nil)]
      (let [{:keys [state context]}
            (nido.session.service/start-service!
             {:type :eval :name :app :repl-service :repl :start-form "(start)"}
             {:session {:project-dir "/tmp/x" :project-name "brian" :instance-id "brian--x"}
              :repl {:port 5200 :socket "/tmp/x.sock" :on-demand true}
              :app {:port 3300 :bind-port 3301}}
             {})]
        (is (zero? @started) "no JVM runs, so no app is started")
        (is (= 3300 (:port context)) "the session's app port is the front's")
        (is (= 3301 (:bind-port context)) "and the app binds another, behind it")
        (is (= "/tmp/x.sock" (:socket state)) "its stop reaches the JVM's socket, never the front")))))

(deftest a-jvm-that-exits-on-its-own-is-started-again-by-the-next-call
  (let [{:keys [cfg log redefs front server] :as fx} (fixture {})]
    (try
      (with-redefs-fn (assoc redefs #'nido.platform.process/process-alive? (fn [_] (some? @server)))
        (fn []
          (is (= "a" (call (:nrepl-port cfg) "a" 3000)))
          (await-phase front :running)
          ;; The JVM dies: its socket goes with it.
          (@server) (reset! server nil)
          (#'front/forget-dead-jvm! front)
          (is (= :absent (:phase @front)))
          (is (= "b" (call (:nrepl-port cfg) "b" 3000)) "the next call starts it again")
          (is (= 2 (count (filter #{:start} @log))))))
      (finally (cleanup fx)))))

(defn- half-closed-call
  "Send `s` on a fresh connection to `port`, close the sending side, and read the reply until the
   front closes — `timeout-ms` at most; nil when none comes."
  [port s timeout-ms]
  (with-open [c (Socket. "127.0.0.1" (int port))]
    (.setSoTimeout c timeout-ms)
    (.write (.getOutputStream c) (.getBytes ^String s))
    (.flush (.getOutputStream c))
    (.shutdownOutput c)
    (let [r (try (slurp (.getInputStream c)) (catch java.net.SocketTimeoutException _ nil))]
      (when (seq r) r))))

(deftest a-client-that-half-closes-still-gets-its-reply
  (let [{:keys [cfg redefs front] :as fx} (fixture {:start-ms 300})]
    (try
      (with-redefs-fn redefs
        (fn []
          (is (= "held" (half-closed-call (:nrepl-port cfg) "held" 3000))
              "sent and half-closed while the JVM starts, the call still gets its reply")
          (await-phase front :running)
          (is (= "live" (half-closed-call (:nrepl-port cfg) "live" 3000))
              "and so does one half-closed while it runs")
          (Thread/sleep 200)
          (is (zero? (:conns @front)) "once both directions end, the connection is no longer open")))
      (finally (cleanup fx)))))

(deftest a-failed-start-is-rolled-back-under-the-runtime-lock
  (let [{:keys [cfg log redefs front] :as fx} (fixture {:start-fails? true})
        held? (atom false)]
    (try
      (with-redefs-fn (assoc redefs
                             #'lifecycle/with-runtime-lock (fn [_ _ f]
                                                             (reset! held? true)
                                                             (try (f) (finally (reset! held? false))))
                             #'process/stop-jvm! (fn [_] (swap! log conj [:stop-held? @held?]))
                             #'state/update-runtime-entries! (fn [_ f]
                                                               (swap! log conj [:registry-held? @held? (f {})])))
        (fn []
          (call (:nrepl-port cfg) "x" 3000)
          (await-phase front :absent)
          (Thread/sleep 100)
          (is (= [[:stop-held? true]] (filter #(and (vector? %) (= :stop-held? (first %))) @log))
              "the failed JVM is stopped while the lock is still held")
          (is (every? #(second %) (filter #(and (vector? %) (= :registry-held? (first %))) @log))
              "and its pid cleared from the registry under it")))
      (finally (cleanup fx)))))

(deftest every-cold-start-record-says-its-admission-was-bypassed
  (doseq [opts [{} {:start-fails? true} {:start-ms 600}]]
    (let [{:keys [cfg log redefs front] :as fx} (fixture opts)]
      (try
        (with-redefs-fn redefs
          (fn []
            (call (:nrepl-port cfg) "x" (if (:start-ms opts) 100 3000))
            (loop [n 0]
              (when (and (< n 100) (not-any? #(and (vector? %) (= :record (first %))) @log))
                (Thread/sleep 50) (recur (inc n))))
            (let [[_ rec] (some #(when (and (vector? %) (= :record (first %))) %) @log)]
              (is (= :bypassed (:admission rec)) (str (:outcome rec) " records its admission bypassed")))))
        (finally (cleanup fx))))))

(deftest an-nrepl-triggered-start-is-recorded-ready-when-the-jvm-answers
  (let [{:keys [cfg log redefs front] :as fx} (fixture {})
        cfg  (assoc cfg :apps [{:service :app :port 1 :bind-port 2}])
        _    (swap! front assoc :cfg cfg)
        jvm-ready (atom nil)]
    (try
      (with-redefs-fn (assoc redefs
                             #'state/read-session (fn [_] {:name "t"
                                                           :service-defs [{:type :eval :name :app}]
                                                           :service-states {:app {:bind-port 2}}})
                             #'process/start-jvm! (let [f (get redefs #'process/start-jvm!)]
                                                    (fn [c] (let [pid (f c)] (reset! jvm-ready (System/currentTimeMillis)) pid)))
                             #'eval-svc/start-app! (fn [& _] (Thread/sleep 800) false))
        (fn []
          (is (= "x" (call (:nrepl-port cfg) "x" 3000)) "the nREPL call is answered once the JVM is")
          (await-phase front :absent)
          (Thread/sleep 100)
          (let [recs (keep #(when (and (vector? %) (= :record (first %))) (second %)) @log)
                rec  (first recs)]
            (is (= 1 (count recs)) (str "one start, one record: " (pr-str recs)))
            (is (= :ready (:outcome rec)) "the app failing after the JVM answered does not fail the nREPL's start")
            (is (< (- (.toEpochMilli (java.time.Instant/parse (:ended-at rec))) @jvm-ready) 500)
                "and it ended when the JVM answered, not when the app gave up"))
          (is (some #(and (vector? %) (= :failure (first %))) @log) "the app's failure is kept all the same")))
      (finally (cleanup fx)))))

(deftest a-client-sending-during-a-start-is-held-only-up-to-the-limit
  (let [{:keys [cfg redefs front] :as fx} (fixture {:start-ms 1500})]
    (try
      (with-redefs-fn redefs
        (fn []
          (with-open [c (Socket. "127.0.0.1" (int (:nrepl-port cfg)))]
            (let [out  (.getOutputStream c)
                  sent (atom 0)
                  w    (future (try (let [b (byte-array 65536)]
                                      (dotimes [_ 1024] (.write out b) (swap! sent + 65536)))
                                    (catch Exception _ nil)))]
              (Thread/sleep 1000)
              (is (= :starting (:phase @front)))
              (is (< @sent (* 16 1024 1024))
                  "the front stops reading the client once it holds its limit, so the client's writes block")
              (future-cancel w)))
          ;; Its start runs on after the client goes; left running, it would land in the next
          ;; test's redefinitions.
          (await-phase front :running)))
      (finally (cleanup fx)))))

(defn- answers-first-server!
  "A server on filesystem socket `path` standing in for a JVM that answers before it has read what
   it was sent: it reads one byte, writes `reply-bytes`, then reads to the end. Returns a fn that
   stops it."
  [path reply-bytes]
  (fs/delete-if-exists path)
  (let [ss (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
    (.bind ss (UnixDomainSocketAddress/of (str path)))
    (future
      (try
        (let [c (.accept ss)]
          (.read c (ByteBuffer/allocate 1))
          (let [b (ByteBuffer/wrap (byte-array reply-bytes))]
            (while (.hasRemaining b) (.write c b)))
          (let [b (ByteBuffer/allocate 65536)]
            (loop [] (.clear b) (when (pos? (.read c b)) (recur))))
          (.close c))
        (catch Exception _ nil)))
    (fn [] (.close ss) (fs/delete-if-exists path))))

(deftest a-reply-is-read-while-the-held-bytes-are-still-being-written
  (let [{:keys [cfg redefs server] :as fx} (fixture {})
        reply (* 8 1024 1024)]
    (try
      (with-redefs-fn (assoc redefs #'process/start-jvm!
                             (fn [c] (Thread/sleep 300)
                               (reset! server (answers-first-server! (:socket c) reply))
                               4242))
        (fn []
          (with-open [c (Socket. "127.0.0.1" (int (:nrepl-port cfg)))]
            (.setSoTimeout c 5000)
            (let [got (future (let [in (.getInputStream c) b (byte-array 65536)]
                                (loop [n 0]
                                  (let [r (try (.read in b) (catch Exception _ -1))]
                                    (if (or (neg? r) (>= (+ n r) reply)) (+ n (max r 0)) (recur (+ n r)))))))]
              ;; Sent while the JVM starts, so it is held and flushed once it answers.
              (try (.write (.getOutputStream c) (byte-array (* 1024 1024)))
                   (catch Exception _ nil))
              (is (= reply (deref got 6000 :stuck))
                  "the whole reply arrives although the target answers before reading its input")))))
      (finally (cleanup fx)))))

(deftest a-failed-start-is-kept-and-recorded-under-the-runtime-lock
  (let [{:keys [cfg log redefs front] :as fx} (fixture {:start-fails? true})
        held? (atom false)]
    (try
      (with-redefs-fn (assoc redefs
                             #'lifecycle/with-runtime-lock (fn [_ _ f]
                                                             (reset! held? true)
                                                             (try (f) (finally (reset! held? false))))
                             #'failure/record! (fn [_ _] (swap! log conj [:failure-held? @held?]) {:id "f"})
                             #'cold-starts/record! (fn [e] (swap! log conj [:record-held? @held? (:outcome e)]) e))
        (fn []
          (call (:nrepl-port cfg) "x" 3000)
          (await-phase front :absent)
          (Thread/sleep 100)
          (is (some #{[:failure-held? true]} @log) "the failure is kept before the lock is let go")
          (is (some #{[:record-held? true :failed]} @log) "and the start recorded failed under it")))
      (finally (cleanup fx)))))
