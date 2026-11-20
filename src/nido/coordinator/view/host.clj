(ns nido.coordinator.view.host
  "The host as the Operations card and its page read it: load against cores, memory, the volume
   ~/.nido lives on, the coordinator's CPU share, and the processes grouped the way a person thinks
   of them — and whether any of it needs a person.

   A pure read model: handed two host readings (`nido.platform.host/read-host`) and the
   coordinator's pid, it derives. It reads nothing and keeps nothing; the server holds the two
   readings, because a CPU share is a rate and a rate needs the reading before.

   The groups are the reading user's processes, not provably nido's: session REPLs and postgres
   are reparented to init, and Claude Code's own daemon keeps spare claude processes nido never
   started, so ownership cannot be read cheaply. What no kind claims lands in :other, which is
   where a pile-up of stray shells shows."
  (:require [clojure.string :as str]))

(def memory-available-floor
  "Below this fraction of memory available, the host needs a person: the next session start, or a
   build, is what tips it into swap or the OOM killer."
  0.10)

(def disk-free-floor
  "Below this fraction of the ~/.nido volume free, the host needs a person: run logs, PGDATA clones
   and transcripts all grow there and nothing prunes them in time."
  0.10)

(def kinds
  "The groups, in the order the card shows them."
  [:claude :mcp :repl :postgres :other])

(defn- basename [s] (last (str/split (str s) #"/")))

(defn- own-kind
  "The kind a process is by its own command line, or nil when it is none of them. The first token
   of argv0 rather than argv0 whole: npm rewrites its title to `npm exec <package>` in one string,
   and a postgres backend to `postgres: checkpointer`."
  [{:keys [argv]}]
  (let [a0  (str (first argv))
        exe (basename (first (str/split a0 #"\s+")))]
    (cond
      (or (= "claude" exe) (str/includes? a0 "/claude/versions/")) :claude
      (#{"node" "npm" "npx"} exe)                                  :mcp
      (or (= "java" exe)
          (and (= "bb" exe) (some #(str/includes? (str %) "nrepl") (rest argv)))) :repl
      (or (#{"postgres" "postmaster"} exe) (str/starts-with? a0 "postgres:")) :postgres
      :else nil)))

(defn ^{:malli/schema [:=> [:cat :map :int] :ProcessKind]}
  process-kind
  "The kind the process `pid` is counted under: its own, else its nearest classified ancestor's,
   else :other. `by-pid` maps pid → process. An ancestor walk, so a shell a Claude session started
   costs that session and the `sh -c` an npm MCP launcher wraps costs the MCP server. The walk
   stops at a pid it has seen, so a malformed parent chain cannot loop."
  [by-pid pid]
  (loop [p pid seen #{}]
    (if-let [proc (and (not (seen p)) (by-pid p))]
      (or (own-kind proc) (recur (:ppid proc) (conj seen p)))
      :other)))

(defn- memory-model [{:keys [total-bytes available-bytes swap-total-bytes swap-free-bytes]}]
  {:total-bytes     total-bytes
   :used-bytes      (- total-bytes available-bytes)
   :available-bytes available-bytes
   :swap-used-bytes (max 0 (- swap-total-bytes swap-free-bytes))})

(defn- disk-model [{:keys [path total-bytes free-bytes]}]
  {:path path :total-bytes total-bytes :used-bytes (- total-bytes free-bytes) :free-bytes free-bytes})

(defn- cpu-of [reading pid]
  (some #(when (= pid (:pid %)) %) (:processes reading)))

(defn- coordinator-model
  "The coordinator's line, or nil when its pid is unknown. The share is the CPU seconds it spent
   between the two readings over the wall time between them — a fraction of one core, nil while
   there is no earlier reading of the same pid to measure from."
  [reading previous pid]
  (when pid
    (let [now  (cpu-of reading pid)
          then (cpu-of previous pid)
          dt   (when previous (/ (- (:at-ms reading) (:at-ms previous)) 1000.0))]
      {:pid       pid
       :rss-bytes (:rss-bytes now)
       :cpu-share (when (and (:cpu-seconds now) (:cpu-seconds then) dt (pos? dt))
                    (max 0.0 (/ (- (:cpu-seconds now) (:cpu-seconds then)) dt)))})))

(defn- kinds-model [processes coordinator-pid]
  (let [by-pid  (into {} (map (juxt :pid identity)) processes)
        counted (remove #(= coordinator-pid (:pid %)) processes)
        grouped (group-by #(process-kind by-pid (:pid %)) counted)]
    (vec (for [k kinds
               :let [ps (get grouped k [])]]
           {:kind      k
            :count     (count ps)
            :rss-bytes (reduce + 0 (keep :rss-bytes ps))
            :processes (->> ps
                            (sort-by #(or (:rss-bytes %) 0) >)
                            (mapv (fn [p] {:pid       (:pid p)
                                           :command   (str/join " " (:argv p))
                                           :rss-bytes (:rss-bytes p)})))}))))

(defn- need
  "Why the host needs a person, or nil when it does not. Persistent overload is the 15-minute
   average over the cores; a 1-minute spike is a figure on the card, not a reason."
  [{:keys [cores load memory disk]}]
  (let [frac    (fn [a b] (when (and a b (pos? b)) (/ (double a) b)))
        mem     (frac (:available-bytes memory) (:total-bytes memory))
        free    (frac (:free-bytes disk) (:total-bytes disk))
        reasons (cond-> []
                  (and mem (< mem memory-available-floor))
                  (conj (format "memory available under %d%%" (long (* 100 memory-available-floor))))
                  (and free (< free disk-free-floor))
                  (conj (format "disk free under %d%%" (long (* 100 disk-free-floor))))
                  (and load cores (> (:fifteen load) cores))
                  (conj (format "15-minute load %.1f above %d cores" (:fifteen load) cores)))]
    (when (seq reasons) {:tone :you :reasons reasons})))

(defn ^{:malli/schema [:=> [:cat [:maybe :HostReading] [:maybe :HostReading] [:maybe :int]] :HostModel]}
  host-model
  "The card and page model: `{:unreadable :host}` when there is no reading, else the figures the
   reading held — each nil where the host did not give it — with `:need` nil, or `{:tone :you
   :reasons [..]}` past a threshold. `previous` is the reading before (nil on the first);
   `coordinator-pid` nil when no coordinator runs."
  [reading previous coordinator-pid]
  (if (nil? reading)
    {:unreadable :host}
    (let [{:keys [cores load memory disk processes]} reading
          m {:cores       cores
             :load        load
             :memory      (some-> memory memory-model)
             :disk        (some-> disk disk-model)
             :coordinator (coordinator-model reading previous coordinator-pid)
             :kinds       (some-> processes (kinds-model coordinator-pid))}]
      (assoc m :need (need m)))))
