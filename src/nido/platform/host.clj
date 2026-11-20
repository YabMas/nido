(ns nido.platform.host
  "One reading of the machine nido runs on: cores, load, memory, the volume a path lives on, and
   the processes the reading user owns.

   Read from the kernel's own files and the JVM, never by starting a process: the dashboard reads
   this on its poll, and a `ps` per render is the cost this exists to avoid. Load, memory and
   processes come from /proc, so a host without it (macOS) reads them as nil; cores and disk come
   from the JVM and are read everywhere. Nothing here throws — every caller is observability, and
   a reading that can break its page is worse than none."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]))

(def ^:private clock-ticks-per-second
  "USER_HZ: the unit of /proc/<pid>/stat's utime and stime. The kernel fixes it at 100 for every
   architecture userspace sees, whatever the internal tick rate, and there is no file to read it
   from without starting a process (getconf), so it is stated rather than read."
  100.0)

(defn- slurp-proc
  "A /proc file's contents, or nil where it is absent or vanished mid-read. Read as bytes: a /proc
   file reports size 0, and the stream slurp opens on it throws under babashka."
  [path]
  (try (String. ^bytes (fs/read-all-bytes path) "UTF-8") (catch Exception _ nil)))

(defn- field [text re]
  (some-> (re-find re text) second))

(defn- kib->bytes [s] (some-> s parse-long (* 1024)))

(defn- read-load [root]
  (when-let [s (slurp-proc (str root "/loadavg"))]
    (let [[one five fifteen] (map parse-double (take 3 (str/split (str/trim s) #"\s+")))]
      (when (and one five fifteen) {:one one :five five :fifteen fifteen}))))

(defn- read-memory [root]
  (when-let [s (slurp-proc (str root "/meminfo"))]
    (let [k (fn [label] (kib->bytes (field s (re-pattern (str "(?m)^" label ":\\s+(\\d+) kB")))))
          m {:total-bytes      (k "MemTotal")
             :available-bytes  (k "MemAvailable")
             :swap-total-bytes (or (k "SwapTotal") 0)
             :swap-free-bytes  (or (k "SwapFree") 0)}]
      (when (and (:total-bytes m) (:available-bytes m)) m))))

(defn- read-disk [path]
  (try
    (let [f (java.io.File. ^String path)
          total (.getTotalSpace f)]
      ;; A path that does not exist reads 0 for both rather than throwing.
      (when (pos? total)
        {:path path :total-bytes total :free-bytes (.getUsableSpace f)}))
    (catch Exception _ nil)))

(defn- cpu-seconds
  "utime+stime of one pid, in seconds. The fields are counted after the LAST `)`: the command name
   before it is free text and may hold spaces and parentheses of its own."
  [root pid]
  (when-let [s (slurp-proc (str root "/" pid "/stat"))]
    (let [after (subs s (inc (str/last-index-of s ")")))
          ;; fields 3.. of stat(5); utime and stime are fields 14 and 15
          fs    (str/split (str/trim after) #"\s+")
          ut    (some-> (get fs 11) parse-long)
          st    (some-> (get fs 12) parse-long)]
      (when (and ut st) (/ (+ ut st) clock-ticks-per-second)))))

(defn- read-process [root uid cpu-pids pid]
  (when-let [status (slurp-proc (str root "/" pid "/status"))]
    (when (= uid (field status #"(?m)^Uid:\s+(\d+)"))
      (let [ppid (some-> (field status #"(?m)^PPid:\s+(\d+)") parse-long)
            argv (some-> (slurp-proc (str root "/" pid "/cmdline"))
                         (str/split #"\u0000"))
            ;; A kernel thread or a zombie has an empty cmdline; its Name is all there is.
            argv (if (seq (remove str/blank? argv))
                   (vec (remove str/blank? argv))
                   [(or (field status #"(?m)^Name:\s+(.+)$") "?")])
            proc {:pid       pid
                  :ppid      (or ppid 0)
                  :argv      argv
                  :rss-bytes (kib->bytes (field status #"(?m)^VmRSS:\s+(\d+) kB"))}]
        (if-let [cpu (and (contains? cpu-pids pid) (cpu-seconds root pid))]
          (assoc proc :cpu-seconds cpu)
          proc)))))

(defn- read-processes [root cpu-pids]
  (when-let [uid (some-> (slurp-proc (str root "/self/status")) (field #"(?m)^Uid:\s+(\d+)"))]
    (let [dir (java.io.File. (str root))]
      (when-let [names (.list dir)]
        (into []
              (keep (fn [n] (when (re-matches #"\d+" n)
                              ;; A process may exit between the listing and its read: nil, skipped.
                              (try (read-process root uid cpu-pids (parse-long n))
                                   (catch Exception _ nil)))))
              (sort-by #(or (parse-long %) 0) names))))))

(defn ^{:malli/schema [:=> [:cat [:map [:path :string] [:cpu-pids {:optional true} [:set :int]]]] :HostReading]}
  read-host
  "The host now, as a HostReading: `:cores`, and `:load`, `:memory`, `:disk` (the volume `:path`
   lives on) and `:processes` (every process the reading user owns, with `:cpu-seconds` on the
   `:cpu-pids` asked) — each nil where this host will not give it. Never throws.

   `:proc-root` points the /proc reads elsewhere; tests pass a fixture directory."
  [{:keys [path cpu-pids proc-root] :or {cpu-pids #{} proc-root "/proc"}}]
  (let [safe (fn [f] (try (f) (catch Throwable _ nil)))
        proc? (fs/exists? (str proc-root "/self/status"))]
    {:at-ms     (System/currentTimeMillis)
     :cores     (.availableProcessors (Runtime/getRuntime))
     :load      (when proc? (safe #(read-load proc-root)))
     :memory    (when proc? (safe #(read-memory proc-root)))
     :disk      (safe #(read-disk path))
     :processes (when proc? (safe #(read-processes proc-root (set cpu-pids))))}))
