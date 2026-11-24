(ns nido.core.api
  "How anything outside the daemon changes or reads nido: requests over a Unix domain socket.

   The wire is one EDN map per line each way. A request is one of
     {:op :command :key k :ws ws :command {...} :input? bool}
         -> the outcome committed under k (see `nido.core.writer/submit!`)
     {:op :state :ws ws}            -> {:state ...}, read from committed state
     {:op :states :prefix p}        -> {:states {ws state}}, every workstream whose key starts with p
     {:op :entries :ws ws :seqs [n]} -> {:entries {n content}}, the content of ledger entries
     {:op :workstreams}             -> {:workstreams [...]}
     {:op :subscribe :from pos}     -> a stream of feed messages, one per line: the catch-up,
                                       then {:caught-up true :pos p}, then live, ending in
                                       {:cut-off true :resume-from pos} if the reader falls behind
   and anything else is answered {:error ...}. The text is only ever read as EDN data.

   The server is given what it serves as functions, and requires nothing of the daemon:
     :submit        (fn [envelope]) -> a promise of the outcome (`nido.core.writer/submit!`)
     :open-session  (fn []) -> {:state (fn [ws]) :states (fn [prefix]) :entries (fn [ws seqs])
                     :workstreams (fn []) :subscribe (fn [from]) :close (fn [])}, one per connection, opened on its first read; :subscribe
                     answers {:next (fn [timeout-ms]) -> message, nil or a cut-off; :close (fn [])}
   Reads never go through the writer: a session reads committed state through a read-only handle
   of its own, so a read never waits on a commit. Each connection is served by a virtual thread
   that blocks on its socket, so an idle connection costs nothing.

   So this namespace, which holds the wire format, loads under bb too, and the client below is
   what bb tasks and agents' tools use."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels ServerSocketChannel SocketChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]))

(defn- read-request [^String line]
  (try (let [r (edn/read-string line)] (if (map? r) r {:op ::malformed}))
       (catch Exception _ {:op ::malformed})))

(defn- send! [^BufferedWriter w x]
  (.write w (pr-str x)) (.newLine w) (.flush w))

(def ^:private command-timeout-ms
  "How long a connection waits for a command's outcome before answering {:error :timeout}. The
   command may still commit; resending it under the same key answers its outcome."
  60000)

(defn- stream-feed! [session ^BufferedWriter w from]
  (let [{:keys [next close]} ((:subscribe session) (or from 0))]
    (try
      (loop []
        ;; No heartbeat: a client that went away is found by the write that fails, or, while
        ;; nothing is written to it, costs one parked virtual thread until its buffer fills.
        (let [m (next Integer/MAX_VALUE)]
          (cond
            (nil? m)        (recur)
            (:cut-off m)    (send! w m)
            :else           (do (send! w m) (recur)))))
      (finally (close)))))

(defn- answer [{:keys [submit]} session req]
  (case (:op req)
    :command     (deref (submit (select-keys req [:key :ws :command :input?]))
                        command-timeout-ms {:error :timeout})
    :state       (if (string? (:ws req)) {:state ((:state @session) (:ws req))} {:error :workstream-missing})
    :states      (if (string? (:prefix req))
                   {:states ((:states @session) (:prefix req))}
                   {:error :prefix-missing})
    :entries     (if (and (string? (:ws req)) (sequential? (:seqs req)) (every? int? (:seqs req)))
                   {:entries ((:entries @session) (:ws req) (:seqs req))}
                   {:error :entries-need-a-workstream-and-seqs})
    :workstreams {:workstreams ((:workstreams @session))}
    {:error :unknown-op :op (:op req)}))

(defn- serve-connection!
  "Serve one connection until its client hangs up. A client that hangs up first is the ordinary end
   of a connection, wherever it is noticed — reading, writing, or the writer's flush as it closes."
  [{:keys [open-session] :as handlers} ^SocketChannel ch]
  (let [session (delay (open-session))]
    (try
      (with-open [ch ch
                  r (BufferedReader. (InputStreamReader. (Channels/newInputStream ch) StandardCharsets/UTF_8))
                  w (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch) StandardCharsets/UTF_8))]
        (loop []
          (when-let [line (.readLine r)]
            (let [req (read-request line)]
              (if (= :subscribe (:op req))
                (stream-feed! @session w (:from req))
                (do (send! w (if (= ::malformed (:op req))
                               {:error :malformed}
                               (answer handlers session req)))
                    (recur)))))))
      (catch java.io.IOException _ nil)
      (finally (when (realized? session) ((:close @session)))))))

(defn ^{:malli/schema [:=> [:cat :map] :map]} serve!
  "Listen on the Unix socket at `:socket` (a stale file there is replaced) and serve each
   connection on a virtual thread with `:submit` and `:open-session` (see the namespace doc).
   Answers the server; `close!` stops it."
  [{:keys [socket] :as handlers}]
  (let [path (Path/of ^String socket (make-array String 0))
        _    (io/make-parents (io/file socket))
        _    (Files/deleteIfExists path)
        srv  (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
               (.bind (UnixDomainSocketAddress/of path)))
        acceptor (Thread/startVirtualThread
                  (fn []
                    (try
                      (loop []
                        (let [ch (.accept srv)]
                          (Thread/startVirtualThread #(serve-connection! handlers ch))
                          (recur)))
                      (catch java.nio.channels.ClosedChannelException _ nil))))]
    {:server srv :acceptor acceptor :path path}))

(defn ^{:malli/schema [:=> [:cat :map] :nil]} close!
  "Stop accepting connections and remove the socket file. Connections already open run on until
   their client closes them."
  [{:keys [^ServerSocketChannel server ^Path path]}]
  (.close server)
  (Files/deleteIfExists path)
  nil)

;; ── a client, for JVM callers and tests ──────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :string] :map]} connect
  "Open a connection to the daemon listening at `socket`."
  [socket]
  (let [ch (SocketChannel/open (UnixDomainSocketAddress/of ^String socket))]
    {:channel ch
     :reader (BufferedReader. (InputStreamReader. (Channels/newInputStream ch) StandardCharsets/UTF_8))
     :writer (BufferedWriter. (OutputStreamWriter. (Channels/newOutputStream ch) StandardCharsets/UTF_8))}))

(defn ^{:malli/schema [:=> [:cat :map :map] :any]} request
  "Send `req` on connection `conn` and answer the reply."
  [conn req]
  (send! (:writer conn) req)
  (some-> (.readLine ^BufferedReader (:reader conn)) edn/read-string))

(defn ^{:malli/schema [:=> [:cat :map :int] :nil]} subscribe
  "Turn connection `conn` into a subscription from position `from`; read it with `next-message`."
  [conn from]
  (send! (:writer conn) {:op :subscribe :from from})
  nil)

(defn ^{:malli/schema [:=> [:cat :map] :any]} next-message
  "The next line of a subscription stream on `conn`, read as EDN; nil once the stream has ended."
  [conn]
  (some-> (.readLine ^BufferedReader (:reader conn)) edn/read-string))

(defn ^{:malli/schema [:=> [:cat :map] :nil]} disconnect
  "Close a connection."
  [conn]
  (.close ^SocketChannel (:channel conn))
  nil)
