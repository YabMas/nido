(ns nido.acp.client
  "One Agent Client Protocol connection: an agent adapter's process, spoken to in JSON-RPC 2.0 over
   its stdio, one JSON message per line.

   HIDES the process, the framing, request-id correlation, and which ACP methods and protocol
   version are spoken. A caller creates or loads sessions and prompts them. Every notification and
   every request the agent makes of the client goes to the one `:on-message` handler the caller
   supplied, as the decoded message with keyword keys. That handler runs on the reader thread, so it
   must not block on a request to the same connection.

   Speaks ACP protocol version 1, and advertises no client file-system or terminal capability: the
   agent edits and runs commands with its own tools, in the directory its session was created in.

   A request answers with the decoded `result`, or throws ex-info carrying the agent's `error`. When
   the process exits, every request still waiting throws, and the handler receives one
   `{:method \"_nido/exited\"}`."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(def ^:private protocol-version 1)

(def ^:private request-timeout-ms
  "How long a request other than a prompt waits for its answer. A session load replays the whole
   history before it answers, which is the slowest of them."
  120000)

(defn- open-process
  "The agent's process, as `{:in OutputStream :out InputStream :destroy fn}`. A seam, so tests can
   speak the protocol over pipes."
  [{:keys [command dir env]}]
  (let [proc (p/process command (cond-> {:err :inherit :shutdown nil}
                                  dir (assoc :dir dir)
                                  env (assoc :extra-env env)))]
    {:in (:in proc) :out (:out proc) :destroy #(p/destroy-tree proc)}))

(defn- write! [{:keys [writer]} msg]
  (locking writer
    (.write writer (str (json/generate-string (assoc msg :jsonrpc "2.0")) "\n"))
    (.flush writer)))

(defn- fail-pending! [pending why]
  (doseq [[_ pr] (first (reset-vals! pending {}))]
    (deliver pr {:error {:message why}})))

(defn- read-loop
  "Read the agent's stdout until it closes: a response delivers its request's promise, anything
   else goes to the handler. A line that is not JSON is skipped — an adapter's stray log line must
   not end the conversation."
  [{:keys [reader pending]} on-message]
  (try
    (doseq [line (line-seq reader)]
      (when-let [msg (try (json/parse-string line true) (catch Exception _ nil))]
        (if (and (contains? msg :id) (not (:method msg)))
          (when-let [pr (get @pending (:id msg))]
            (swap! pending dissoc (:id msg))
            (deliver pr msg))
          (try (on-message msg) (catch Throwable _ nil)))))
    (catch Throwable _ nil))
  (fail-pending! pending "the agent process exited")
  (try (on-message {:method "_nido/exited"}) (catch Throwable _ nil)))

(defn- request
  "Send a request and wait for its answer, up to `timeout-ms` (nil waits for as long as it takes)."
  [{:keys [pending next-id] :as conn} method params timeout-ms]
  (let [id (swap! next-id inc)
        pr (promise)]
    (swap! pending assoc id pr)
    (write! conn {:id id :method method :params params})
    (let [resp (if timeout-ms (deref pr timeout-ms ::timeout) @pr)]
      (cond
        (= ::timeout resp) (do (swap! pending dissoc id)
                               (throw (ex-info (str "ACP " method " timed out") {:method method})))
        (:error resp)      (throw (ex-info (str "ACP " method ": " (get-in resp [:error :message]))
                                           {:method method :error (:error resp)}))
        :else              (:result resp)))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  connect!
  "Start the agent `:command` (a vector) in `:dir` with `:env` added, and initialize it. Returns
   the connection. `:on-message` receives every notification and agent request."
  [{:keys [on-message] :as opts}]
  (let [{:keys [in out destroy]} (open-process opts)
        conn {:writer  (io/writer in)
              :reader  (io/reader out)
              :pending (atom {})
              :next-id (atom 0)
              :destroy destroy}]
    (doto (Thread. #(read-loop conn (or on-message (fn [_]))) "acp-reader")
      (.setDaemon true)
      (.start))
    (request conn "initialize"
             {:protocolVersion    protocol-version
              :clientCapabilities {:fs {:readTextFile false :writeTextFile false} :terminal false}}
             request-timeout-ms)
    conn))

(defn ^{:malli/schema [:=> [:cat :map :map] :string]}
  new-session!
  "Create a session in `:cwd`. `:meta` is the adapter-specific `_meta` (claude-agent-acp reads
   Claude Code options under `:claudeCode`). Its session id."
  [conn {:keys [cwd meta]}]
  (:sessionId (request conn "session/new"
                       (cond-> {:cwd cwd :mcpServers []} meta (assoc :_meta meta))
                       request-timeout-ms)))

(defn ^{:malli/schema [:=> [:cat :map :string :map] :any]}
  load-session!
  "Load session `session-id`, which replays its history to the handler as session updates before
   this returns. Takes the same `:cwd` and `:meta` as `new-session!`."
  [conn session-id {:keys [cwd meta]}]
  (request conn "session/load"
           (cond-> {:sessionId session-id :cwd cwd :mcpServers []} meta (assoc :_meta meta))
           request-timeout-ms))

(defn ^{:malli/schema [:=> [:cat :map :string :string] :any]}
  set-mode!
  "Set a session's mode — for claude-agent-acp, its permission mode, e.g. \"bypassPermissions\"."
  [conn session-id mode]
  (request conn "session/set_mode" {:sessionId session-id :modeId mode} request-timeout-ms))

(defn ^{:malli/schema [:=> [:cat :map :string :string] :any]}
  prompt!
  "Send `text` as a prompt. Returns a future of the turn's result (`:stopReason` among it), which
   throws when the turn fails. Updates arrive at the handler while it runs; a turn has no time limit
   here, and `cancel!` is how one is stopped."
  [conn session-id text]
  (future (request conn "session/prompt"
                   {:sessionId session-id :prompt [{:type "text" :text text}]}
                   nil)))

(defn ^{:malli/schema [:=> [:cat :map :string] :any]}
  cancel!
  "Ask the agent to stop a session's running turn. The turn's prompt then ends with stop reason
   \"cancelled\"."
  [conn session-id]
  (write! conn {:method "session/cancel" :params {:sessionId session-id}}))

(defn ^{:malli/schema [:=> [:cat :map :any :any] :any]}
  respond!
  "Answer the agent's request `request-id` with `result`."
  [conn request-id result]
  (write! conn {:id request-id :result result}))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  close!
  "Stop the agent process. Requests still waiting throw."
  [{:keys [destroy pending]}]
  (try (destroy) (catch Throwable _ nil))
  (fail-pending! pending "the connection was closed"))
