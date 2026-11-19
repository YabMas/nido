(ns nido.platform.desk
  "The person's machine, as a boundary. Whatever decides a person-facing effect
   — open a terminal at a directory running a command, open a URL, post a
   notification — asks for it with `request!` and never performs it itself.

   Where the person is, relative to nido's host, is this module's secret:

     here       the default. `request!` performs the effect in place, through
                nido.platform.desktop, exactly as nido always has.
     elsewhere  declared by `<nido-home>/desk/desk.edn` holding
                {:person :elsewhere}. `request!` records the effect in
                `<nido-home>/desk/effects.edn`, and a desk on the person's
                machine (`run!`) reads the records over ssh — through `stream!`
                on this host — and performs them there.

   The desk reads no file of nido's host itself: everything it performs arrives
   as a line `stream!` printed. A line becomes an effect only by parsing as one
   (`parse`); anything else is dropped and logged."
  (:refer-clojure :exclude [run!])
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.java.io :as jio]
   [clojure.string :as str]
   [malli.core :as m]
   [nido.platform.core :as core]
   [nido.platform.desktop :as desktop]
   [nido.platform.io :as io]))

(def ^:private http-url #"(?i)^https?://\S+$")

(def ^:private effect-schema
  (m/schema
   [:multi {:dispatch :kind}
    [:terminal [:map {:closed true}
                [:kind [:= :terminal]]
                [:dir [:and :string [:fn #(str/starts-with? % "/")]]]
                [:command {:optional true} [:maybe :string]]
                [:title {:optional true} [:maybe :string]]]]
    [:url [:map {:closed true}
           [:kind [:= :url]]
           [:url [:and :string [:re http-url]]]]]
    [:notify [:map {:closed true}
              [:kind [:= :notify]]
              [:message :string]
              [:title {:optional true} [:maybe :string]]
              [:subtitle {:optional true} [:maybe :string]]
              [:sound {:optional true} [:maybe :string]]]]]))

(def ^:private kept
  "How many records the effects file keeps. A desk reads within a second of a
   record landing; the tail is only for a desk reconnecting mid-burst."
  200)

(defn- desk-dir [] (str (fs/path (core/nido-root) "desk")))
(defn- effects-file [] (str (fs/path (desk-dir) "effects.edn")))
(defn- config-file [] (str (fs/path (desk-dir) "desk.edn")))

(defn- log! [& parts]
  (binding [*out* *err*] (println (apply str "[desk] " parts))))

(defn ^{:malli/schema [:=> [:cat :any] [:maybe :Effect]]}
  parse
  "`x` as an Effect, or nil when it is not one. The only door from an untrusted
   value — a line read off the wire, a caller's map — to something performed."
  [x]
  (when (m/validate effect-schema x) x))

(defn ^{:malli/schema [:=> [:cat] :boolean]}
  person-elsewhere?
  "Whether the person sits at another machine than this one, as declared in
   this host's desk config. Absent or unreadable config means they are here."
  []
  (= :elsewhere (:person (try (io/read-edn (config-file)) (catch Exception _ nil)))))

(defn ^{:malli/schema [:=> [:cat [:enum :here :elsewhere]] :any]}
  set-person!
  "Declare where the person sits relative to this host from now on: `:here`
   performs effects in place, `:elsewhere` records them for their desk."
  [where]
  (io/write-edn! (config-file) {:person where}))

(defn ^{:malli/schema [:=> [:cat] :boolean]}
  opens-terminals?
  "Whether a terminal effect will open a tab for the person: always when they
   are elsewhere (their desk opens it), and here only inside Warp, the one
   terminal whose URI scheme opens a tab in the current window."
  []
  (or (person-elsewhere?)
      (= "WarpTerminal" (System/getenv "TERM_PROGRAM"))))

(defn ^{:malli/schema [:=> [:cat :map] :boolean]}
  perform!
  "Parse `effect` and carry it out on this machine; false when it does not
   parse or the machine refused it. Never throws."
  [effect]
  (if-let [e (parse effect)]
    (try
      (boolean
       (case (:kind e)
         :terminal (desktop/open-terminal! e)
         :url      (desktop/open-url! (:url e))
         :notify   (desktop/notify! (dissoc e :kind))))
      (catch Exception ex
        (log! "could not perform " (:kind e) ": " (ex-message ex))
        false))
    (do (log! "dropped a request that is not an Effect: " (pr-str effect))
        false)))

(defn- record!
  [e]
  (io/update-edn! (effects-file)
                  (fn [{:keys [next-seq effects] :or {next-seq 0}}]
                    {:next-seq (inc next-seq)
                     :effects  (->> (conj (vec effects) {:seq next-seq :at (core/now-iso) :effect e})
                                    (take-last kept)
                                    vec)})))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  request!
  "Ask for one effect. Performed in place when the person is at this host,
   recorded for their desk otherwise. A request that does not parse as an
   Effect is dropped and logged. Never throws: an effect is never worth failing
   its caller over."
  [effect]
  (try
    (cond
      (nil? (parse effect))  (log! "dropped a request that is not an Effect: " (pr-str effect))
      (person-elsewhere?)    (do (record! effect) true)
      :else                  (perform! effect))
    (catch Exception ex
      (log! "request failed: " (ex-message ex))
      nil)))

(defn ^{:malli/schema [:=> [:cat [:maybe :int]] :map]}
  requests-since
  "The records after `cursor`, oldest first, as {:effects [{:seq :at :effect}]
   :cursor <next>}. A nil cursor reads nothing and answers where the log ends
   now — what a desk that just connected starts from, so it never performs
   what was asked before it was there."
  [cursor]
  (let [{:keys [next-seq effects] :or {next-seq 0}}
        (try (io/read-edn (effects-file)) (catch Exception _ nil))]
    (if (nil? cursor)
      {:effects [] :cursor next-seq}
      {:effects (filterv #(>= (:seq %) cursor) effects)
       :cursor  next-seq})))

(defn- forward-line
  "The stream's own line announcing the ports the desk should forward now."
  [ports]
  (str ";; forward" (apply str (map #(str " " %) (sort ports)))))

(defn- read-forward-line
  "The port set a `forward-line` announces, or nil for any other line."
  [line]
  (when-let [[_ ps] (re-matches #";; forward((?: \d+)*)" (str/trim line))]
    (into #{} (map parse-long) (remove str/blank? (str/split ps #" ")))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  stream!
  "Run on nido's host, under the desk's ssh connection: print every effect
   recorded from now on, one EDN map per line, until stdout closes. Starts at
   the log's current end — what was asked while no desk listened is not
   replayed — and prints a heartbeat comment every 15s so a dead connection is
   noticed from either side.

   With `{:ports f}`, `f` answers the set of this host's loopback ports the
   person's browser should reach — a session's app, say, whose URL names
   localhost. It is asked every 5s, and the set announced as a `;; forward`
   line whenever it differs from the last one announced; a throw keeps the
   last set. Comment lines are the stream's own, so an older desk skips them."
  [& [{:keys [ports]}]]
  (loop [cursor    (:cursor (requests-since nil))
         beat      (System/currentTimeMillis)
         announced nil
         asked     0]
    (let [{:keys [effects cursor]} (requests-since cursor)
          now  (System/currentTimeMillis)
          ask? (and ports (>= (- now asked) 5000))
          want (if ask? (try (set (ports)) (catch Exception _ announced)) announced)]
      ;; A write to a closed connection throws; the desk is gone, so is this.
      (try
        (doseq [{:keys [effect]} effects]
          (println (pr-str effect)))
        (when (and want (not= want announced)) (println (forward-line want)))
        (when (>= (- now beat) 15000) (println ";; beat"))
        (flush)
        (catch java.io.IOException _ (System/exit 0)))
      (Thread/sleep 500)
      (recur cursor (if (>= (- now beat) 15000) now beat) want (if ask? now asked)))))

(defn- sh-quote [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn ^{:malli/schema [:=> [:cat :string :Effect] :Effect]}
  localize
  "`e` as the person's machine performs it when nido runs on `host`: a terminal
   effect becomes a local tab running `ssh -t host` into the requested
   directory and command, so the command runs on nido's host and never in a
   local shell. It runs under the host user's login shell, because the PATH a
   person's tools are on (~/.local/bin, runtime shims) is set up at login and a
   bare ssh command never sees it. Other kinds are performed as they are."
  [host e]
  (if (= :terminal (:kind e))
    (let [remote (str "cd " (sh-quote (:dir e)) " && "
                      (if (str/blank? (:command e)) "exec $SHELL -l" (:command e)))]
      (cond-> {:kind    :terminal
               :dir     (str (fs/home))
               :command (str "ssh -t " (sh-quote host) " "
                             (sh-quote (str "exec $SHELL -lc " (sh-quote remote))))}
        (:title e) (assoc :title (:title e))))
    e))

(defn- read-line-effect
  "One line off the stream as an Effect, or nil. Comments and blank lines are
   the stream's own (heartbeats) and are skipped silently."
  [line]
  (let [s (str/trim line)]
    (when-not (or (str/blank? s) (str/starts-with? s ";"))
      (or (parse (try (edn/read-string s) (catch Exception _ ::unreadable)))
          (do (log! "dropped a line that is not an Effect: " s) nil)))))

(defn- forward!
  "Add (`op` \"forward\") or remove (\"cancel\") a forward of `port` on this
   machine's loopback to the same port on the host's, over the connection whose
   control socket is `ctl`. Answers whether ssh accepted it — false when the
   port is taken here."
  [ctl host op port]
  (let [spec (str "127.0.0.1:" port ":127.0.0.1:" port)]
    (zero? (:exit (p/shell {:continue true :out :string :err :string :in ""}
                           "ssh" "-S" ctl "-O" op "-L" spec host)))))

(defn- apply-forwards!
  "Make the connection forward exactly `want` (less `keep-out`, the ports the
   connection holds for itself), from `have`; answers what is forwarded now. A
   port that cannot be forwarded is logged and asked for again at the next
   announcement."
  [ctl host have want keep-out]
  (let [want (apply disj want keep-out)
        have (reduce (fn [h p] (if (forward! ctl host "cancel" p) (disj h p) h))
                     have (remove want have))]
    (reduce (fn [h p]
              (if (forward! ctl host "forward" p)
                (do (log! "forwarding localhost:" p) (conj h p))
                (do (log! "could not forward localhost:" p " — is it in use here?") h)))
            have (remove have want))))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  run!
  "Run the desk on the person's machine: hold an ssh connection to nido's
   `:host`, read the effects `stream!` prints there and perform each here, and
   forward `:dashboard-port` of the host's loopback (default 8800) to
   `:local-port` here (default the same). Every port set the stream announces
   is forwarded too, each to the same port here, so a localhost URL of the
   host's opens in a browser on this machine. `:remote-dir` is nido's checkout
   on the host (default ~/Code/nido) and `:remote-home` its NIDO_HOME when not
   the default. Reconnects after a lost connection, waiting up to 30s between
   attempts; returns only when interrupted."
  [{:keys [host remote-dir remote-home dashboard-port local-port]
    :or   {remote-dir "~/Code/nido" dashboard-port 8800}}]
  (let [remote (str "cd " remote-dir " && "
                    (when remote-home (str "NIDO_HOME=" (sh-quote remote-home) " "))
                    "bash -lc 'bb nido:desk:stream'")
        local  (or local-port dashboard-port)
        ;; A control socket, so forwards can be added to the live connection.
        ;; Short and under the home: macOS caps a socket path near 104 bytes.
        ctl    (str (fs/path (fs/home) ".nido" "desk" (str "ctl-" (subs (str (random-uuid)) 0 8))))
        cmd    ["ssh" "-o" "ServerAliveInterval=15" "-o" "ServerAliveCountMax=2"
                "-o" "ExitOnForwardFailure=no"
                "-o" "ControlMaster=yes" "-o" (str "ControlPath=" ctl)
                "-L" (str "127.0.0.1:" local ":127.0.0.1:" dashboard-port)
                host remote]]
    (fs/create-dirs (fs/parent ctl))
    (loop [wait 1000]
      (log! "connecting to " host " (dashboard on http://localhost:" local ")")
      (let [started (System/currentTimeMillis)
            ;; :shutdown, so a desk that is stopped takes its connection and the
            ;; port it forwards down with it instead of leaving an orphan holding both.
            proc    (p/process cmd {:err :inherit :in :inherit :shutdown p/destroy-tree})
            fwd     (atom #{})]
        (with-open [r (jio/reader (:out proc))]
          (doseq [line (line-seq r)]
            (if-let [want (read-forward-line line)]
              (swap! fwd #(apply-forwards! ctl host % want #{local}))
              (when-let [e (read-line-effect line)]
                (log! (if (perform! (localize host e)) "performed " "refused by this machine: ")
                      (name (:kind e)))))))
        (let [exit (:exit @proc)
              wait (if (> (- (System/currentTimeMillis) started) 60000) 1000 (min 30000 (* 2 wait)))]
          (log! "connection to " host " ended (exit " exit "); retrying in " (quot wait 1000) "s")
          (Thread/sleep wait)
          (recur wait))))))
