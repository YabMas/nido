(ns nido.session.cold-starts
  "The record of every on-demand JVM start: one line per start, per project, under
   ~/.nido/cold-starts/ — outside every instance's state directory, so destroying a session keeps
   its starts.

   A start is recorded by its two instants and how it ended, never by a duration: the time it
   took is read from the instants whenever it is asked, so the two can never disagree. That is
   what a later decision about a warm pool reads.

   One entry:
     {:project :session :instance-id
      :port         :repl | :app      — which of the front's ports the first byte came to
      :requested-at <iso instant>     — when that first byte arrived
      :ended-at     <iso instant>     — when the triggering port was ready (the JVM's nREPL for
                                        :repl, the app for :app), or when the start failed
      :outcome      :ready | :ready-after-give-up | :failed
      :cause        <string>          — a failed start's, only
      :admission    :bypassed}        — every start's: none is admitted against the fleet's
                                        memory budget, and the record says so

   :ready-after-give-up is a start that came up after the client whose byte started it had
   closed its connection — a cold start that outlasted that client's own timeout."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [nido.platform.core :as core]
   [nido.platform.io :as io]))

(defn ^{:malli/schema [:=> [:cat :string] :string]}
  log-path
  "Where project `project-name`'s cold starts are kept: ~/.nido/cold-starts/<project>.edn."
  [project-name]
  (str (fs/path (core/nido-home) "cold-starts" (str project-name ".edn"))))

(def ^:private outcomes #{:ready :ready-after-give-up :failed})

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  record!
  "Append cold start `entry` to its project's log and return it. Throws on an entry that is not
   one: no :project, an instant missing, an outcome it does not know, or a failed start with no
   cause — a record that says less than a start did is worse than none."
  [{:keys [project requested-at ended-at outcome cause] :as entry}]
  (when-not (and (string? project) (string? requested-at) (string? ended-at)
                 (contains? outcomes outcome)
                 (or (not= :failed outcome) (string? cause)))
    (throw (ex-info "Not a cold start record" {:entry entry})))
  (let [path (log-path project)]
    (fs/create-dirs (fs/parent path))
    ;; Every front of the project appends here, each its own process: under the file's lock, so
    ;; each record's append is whole across them as across this process's threads.
    (io/with-file-lock (io/lock-path-for path)
      #(spit path (str (pr-str (into (sorted-map) entry)) "\n") :append true))
    entry))

(defn ^{:malli/schema [:=> [:cat :string [:maybe :string]] [:vector :map]]}
  starts
  "Project `project-name`'s cold starts, oldest first — only session `session-name`'s when it is
   given. A line that does not read is skipped."
  [project-name session-name]
  (let [path (log-path project-name)]
    (if-not (fs/exists? path)
      []
      (into []
            (comp (remove str/blank?)
                  (keep #(try (edn/read-string %) (catch Exception _ nil)))
                  (filter map?)
                  (filter #(or (nil? session-name) (= session-name (:session %)))))
            (str/split-lines (slurp path))))))

(defn ^{:malli/schema [:=> [:cat :map] [:maybe :int]]}
  duration-ms
  "How long start `entry` took, from its first byte to its end — derived from the two instants
   it records; nil when either does not read."
  [{:keys [requested-at ended-at]}]
  (try (- (.toEpochMilli (java.time.Instant/parse ended-at))
          (.toEpochMilli (java.time.Instant/parse requested-at)))
       (catch Exception _ nil)))
