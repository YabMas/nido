(ns nido.coordinator.record.activity
  "What is running against a workstream right now, and who is running it.

   THE LOCK IS THE CLAIM. `activity.lock` is a lock target and holds no content:
   a holder takes it and keeps it for the life of the activity, and the operating
   system drops it when that process dies. So there is no lease to tune, no pid
   to compare, no recovery path and no cleanup step — the three places a
   pid-in-a-file design fails. `nido.platform.lock` is that design, and it fails
   in both: its dead-holder recovery deletes then re-creates, and `release!`
   deletes by path without checking ownership, so two contenders that observe one
   dead pid can both come away holding it. Nothing here deletes a lock.

   THE PAYLOAD IS A SEPARATE FILE, `activity.edn`, written by atomic rename while
   the claim is held. Two files rather than two byte ranges of one, and that is a
   CHOICE — the publication guard it replaces would have worked. Closing one
   `FileLock` releases that range alone and leaves the claim standing: measured,
   both ranges taken on one channel and the claim still refused to another
   process after the guard was closed. (`.release` is unavailable under babashka;
   `.close` is not, and is what a guard would use.)

   What the rename buys is that there is no second lock to take, hold and drop
   around every write, and that a torn read stops being something to exclude and
   becomes something the filesystem cannot offer: a reader sees the previous
   bytes or the new ones because those are the only two states there are.

   The rule that IS forced is a different one, and it is what the holder table
   below exists for: closing any CHANNEL to the locked file drops every lock this
   process holds on that file — also measured. That is a fact about descriptors,
   not about lock ranges, and conflating the two is what once made this look like
   the only shape available.

   READERS ASK WITH SHARED LOCKS, and that is load-bearing rather than tidy. Two
   dashboards polling the same instant must not read each other as the holder,
   which is exactly what an exclusive probe does: it conflicts with the other
   probe, and both conclude somebody is here. A shared lock conflicts only with
   the holder's exclusive one, so a probe answers about the holder alone. It cuts
   the other way for a TAKER, whose lock has to be exclusive and so does collide
   with a probe — see `acquire`, which asks what it lost to rather than reporting
   a reader as a holder.

   A HOLDER NEVER PROBES ITS OWN CLAIM through the filesystem, and it is the
   descriptor rule above that says so: the probe would open a second descriptor to `activity.lock`, and
   closing it would release the claim this process is in the middle of holding.
   The holder table is what makes that impossible, so it is a correctness
   requirement rather than an optimisation — and the daemon reaches it in normal
   operation, being both a holder and the process that renders the dashboard. The
   table can only answer for a claim already published, so a per-path monitor
   covers the window while one is being taken and while one is being released:
   inside this JVM, everything that would open the lock file queues instead.

   The payload is what a reader cannot derive: which activity, which run, where
   its live report is. Liveness is never in it — that is the lock's answer, and a
   field claiming it would be the one thing a crash makes wrong."
  (:require
   [babashka.fs :as fs]
   [malli.core :as m]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.io :as io])
  (:import
   [java.nio.channels FileChannel]
   [java.nio.file Paths StandardOpenOption]))

(def kinds
  "The activities that can take a claim. Enumerated in one place on purpose: a
   surface renders the kind rather than a generic `busy`, so an absent claim
   reads as `none of the things that can say so are running` rather than as
   `nothing is running`. Daemon and merge work are NOT here — they are composed
   into the activity projection as reads of records they already write."
  #{:diff-review :baseline-round :design-round})

(def Claim
  [:map {:closed true}
   [:kind        (into [:enum] kinds)]
   ;; WHAT the activity is judging, in whatever terms its own command uses — the
   ;; baseline a round names by :seq, the revision a diff review takes as its
   ;; base. Opaque here on purpose: this module knows that two activities of one
   ;; kind can be about different things, and nothing more. A second caller
   ;; compares it to decide whether the holder is doing ITS work, and a kind
   ;; alone cannot answer that. Optional so a claim written before there was one
   ;; still reads.
   [:target      {:optional true} [:maybe :map]]
   [:run-id      string?]
   [:report-path [:maybe string?]]
   [:started-at  string?]
   ;; Display only. Never consulted for liveness — the lock answers that, and a
   ;; recycled pid would make this field say `alive` about a stranger.
   [:pid         int?]])

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  validate [c]
  (if (m/validate Claim c)
    c
    (throw (ex-info "Invalid activity Claim" {:errors (m/explain Claim c) :claim c}))))

;; ── Same-JVM holders ────────────────────────────────────────────────────────
;;
;; Consulted before any channel is opened. See the namespace docstring: a holder
;; that probed its own claim would open a second descriptor to the lock file and
;; release the claim by closing it.

(defonce ^:private local-holders (atom {}))

(defn- held-here [path] (get @local-holders path))

(defonce ^:private monitors (atom {}))

(defn- monitor-for
  "The one object every thread in this JVM takes before it goes near `path`'s
   lock file. Interned per path — two threads locking two fresh objects
   synchronise on nothing.

   `held-here` cannot be a sound guard on its own, and this is what makes it one:
   a winner is not in the holder table until its payload is published, so in that
   window it is invisible here and any other thread opens its own descriptor to
   the file — which releases the claim this process holds the moment it closes.
   The same window is what lets two takers race for the lock, and it is why two
   readers cannot be left to overlap either: the JVM refuses a second lock on a
   region it already holds whether either is shared or exclusive, so same-JVM
   probes that met would read each other as the holder.

   Held across the channel work only, never across the activity itself — a claim
   that held it would block every reader for as long as it ran."
  [path]
  (or (get @monitors path)
      (get (swap! monitors update path #(or % (Object.))) path)))

;; ── Lock primitives ─────────────────────────────────────────────────────────

(def ^:private claim-pos
  "The one byte the claim is taken on. A byte rather than the whole file so the
   lock is a fixed target whatever the file's length; nothing is ever written to
   activity.lock, so the choice is arbitrary and only has to be agreed."
  0)

(defn- open-channel ^FileChannel [path]
  (when-let [parent (fs/parent path)] (fs/create-dirs parent))
  (FileChannel/open (Paths/get (str path) (into-array String []))
                    (into-array StandardOpenOption
                                [StandardOpenOption/CREATE
                                 StandardOpenOption/WRITE
                                 StandardOpenOption/READ])))

(def ^:private overlapping-lock
  "Matched by NAME rather than caught as a class: babashka admits neither
   java.nio.channels.OverlappingFileLockException in an :import nor any method
   on FileLockImpl — which is also why every release here is a channel close.
   Any other exception is a real failure and is rethrown."
  "java.nio.channels.OverlappingFileLockException")

(defn- try-claim
  "Take the claim byte of `ch` and return the lock, or nil when someone else
   holds it. `shared?` must be true for a reader, or two readers conflict with
   each other and both conclude a holder exists."
  [^FileChannel ch shared?]
  (try (.tryLock ch (long claim-pos) 1 (boolean shared?))
       (catch Exception e
         (if (= overlapping-lock (.getName (class e)))
           nil
           (throw e)))))

;; ── Reading ─────────────────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] [:maybe :map]]}
  read-live
  "The claim held against this workstream right now, or nil when none is.

   Probe, read, re-probe — and the third step is what makes the answer sound.
   Between finding the lock held and reading the payload the holder can exit, and
   without the re-probe a reader hands back a dead holder's bytes as a live
   claim. Re-probing fails SAFE: if the lock is free by then the answer is nil,
   so the reader under-reports a live claim for one poll rather than over-
   reporting a dead one, and the next poll corrects it.

   One window remains and is accepted: if the holder ends and a new one takes the
   claim while this reads, the re-probe is refused by the NEW holder and the
   previous payload is returned. A holder does exist, the reading is one activity
   stale, and the following poll is correct. Closing it would need the payload
   and the lock read as ONE atomic act, which nothing here offers: the payload is
   a second file, and reading it is a second step whatever guards it."
  [project ws-id]
  (let [lock-path (cstate/activity-lock-path project ws-id)]
    (or (held-here lock-path)
        ;; Interned per path by monitor-for, not local to this scope.
        #_{:clj-kondo/ignore [:locking-suspicious-lock]}
        (locking (monitor-for lock-path)
          (or (held-here lock-path)                     ; published while we waited
              (when (fs/exists? lock-path)
                (with-open [ch (open-channel lock-path)]
                  (when-not (try-claim ch true)         ; free ⇒ nobody holds it
                    (let [payload (io/read-edn (cstate/activity-path project ws-id))]
                      (when-not (try-claim ch true)     ; still held ⇒ trust it
                        payload))))))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId] :boolean]}
  held?
  "Whether anything holds this workstream's claim. Distinct from `read-live`
   returning nil: a holder that has taken the lock and not yet renamed its
   payload into place is held with nothing to report."
  [project ws-id]
  (let [lock-path (cstate/activity-lock-path project ws-id)]
    (boolean
     (or (held-here lock-path)
         #_{:clj-kondo/ignore [:locking-suspicious-lock]}
         (locking (monitor-for lock-path)
           (or (held-here lock-path)
               (and (fs/exists? lock-path)
                    (with-open [ch (open-channel lock-path)]
                      (nil? (try-claim ch true))))))))))

;; ── Taking ──────────────────────────────────────────────────────────────────

;; A reader holds its shared lock for one probe; a holder holds its exclusive one
;; for a whole activity. So a short bounded retry is all it takes to tell a
;; collision with the first from a refusal by the second.
(def ^:private reader-retry-attempts 40)
(def ^:private reader-retry-wait-ms  5)

(defn- acquire
  "A channel holding `lock-path`'s claim exclusively, or nil when someone else's
   claim is on it. Released by closing the channel, never by `.release` — see
   `overlapping-lock`.

   A claim's lock has to be exclusive, so it conflicts with a READER's shared
   probe as surely as with another claim, and a losing attempt has to ask which
   it lost to: a shared probe of our own succeeds against readers and fails only
   against a holder. Losing to a reader is retried rather than refused, because
   the refusal would say a workstream is busy that nothing is running against —
   and by the time the caller looked, the reader would be gone and there would be
   no live claim to hand back, leaving that refusal indistinguishable from a run.

   Exhausting the retries refuses, which is the safe direction: under-reporting
   what is free never runs two activities against one workstream."
  ^FileChannel [lock-path]
  (loop [attempts reader-retry-attempts]
    (let [ch (open-channel lock-path)]
      (if (try-claim ch false)
        ch
        (let [holder? (nil? (try-claim ch true))]
          (.close ch)
          (when-not (or holder? (zero? attempts))
            (Thread/sleep reader-retry-wait-ms)
            (recur (dec attempts))))))))

(defn- take-claim!
  "Take the claim and publish `payload` as ONE step, returning the channel it
   rides on, or nil when someone else holds it.

   Both halves under the path's monitor, because between them is precisely the
   window `held-here` cannot cover: this process holds the lock and no entry in
   the holder table accounts for it, so another thread here opens the file and
   drops the claim by closing its own descriptor. Two takers that both read no
   local holder are the same window seen from the other side."
  ^FileChannel [lock-path payload-path payload]
  #_{:clj-kondo/ignore [:locking-suspicious-lock]}
  (locking (monitor-for lock-path)
    (when-not (held-here lock-path)
      (when-let [ch (acquire lock-path)]
        (try
          ;; Atomic rename, so a reader sees the previous payload or this one
          ;; and never a mix — what the publication guard was for.
          (io/write-edn! payload-path payload)
          (swap! local-holders assoc lock-path payload)
          ch
          (catch Throwable t (.close ch) (throw t)))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map [:=> [:cat] :any]] :any]}
  with-claim
  "Run `f` holding this workstream's activity claim, or refuse without running it
   when someone else holds it.

   A refusal is `{::refused true ::holder <the live claim, or nil>}`, and the two
   keys are separate because they come apart: the lock attempt can lose and the
   read still answer nil, when the holder exits in between. Ask `refused?`, never
   whether a holder came back — a caller that inferred the refusal from the
   holder reads that case as `f` having run and returned nil, which is the
   misreading with no symptom, since nothing ran.

   Refusing rather than waiting is the point, and it is what makes this a
   different exclusion from every other one in nido: `platform.lock` and
   `platform.io/with-file-lock` both block their loser and tell it nothing, and a
   loser HERE has somewhere better to be — the holder's own live report. So the
   refusal carries the holder's claim rather than a boolean.

   `base` is the payload minus what this knows: :started-at and :pid. It is
   validated before the lock is taken, so a claim nothing could render never
   excludes anybody.

   Released by closing the channel on every exit path including a throw. Never by
   deleting: the lock file stays, and the OS drops the lock if this process dies
   mid-activity. The payload is left behind too — it is debris the next reader's
   probe will refuse to trust, and rewriting it on release would be a second
   thing that could fail."
  [project ws-id base f]
  (let [lock-path (cstate/activity-lock-path project ws-id)
        payload   (validate (assoc base
                                   :started-at (clock/now-iso)
                                   :pid (.pid (java.lang.ProcessHandle/current))))]
    (if-let [ch (take-claim! lock-path (cstate/activity-path project ws-id) payload)]
      (try
        (f)
        (finally
          ;; Under the monitor for the same reason taking it is: between the two
          ;; steps this process holds a lock the holder table no longer accounts
          ;; for, and a reader that slipped in would end the claim by closing.
          #_{:clj-kondo/ignore [:locking-suspicious-lock]}
          (locking (monitor-for lock-path)
            (swap! local-holders dissoc lock-path)
            (.close ch))))
      ;; The refusal is a fact of its own, carried by ::refused, and the holder
      ;; rides beside it in ::holder. One key holding the claim could not say
      ;; both: a holder that exits before this read leaves nothing to return,
      ;; and `refused nil` would be indistinguishable from `f ran and gave nil`
      ;; — which is the misreading with no symptom, since nothing ran.
      {::refused true ::holder (read-live project ws-id)})))

(defn ^{:malli/schema [:=> [:cat :any] :boolean]}
  refused?
  "Whether a `with-claim` call refused. THE question a caller asks first, and it
   is answered by the refusal itself rather than by what the refusal carries:
   the lock attempt can lose and the read still come back empty, when the holder
   exits in between, and a caller that inferred the refusal from the holder
   would read that as `f` having run and returned nil."
  [result]
  (boolean (and (map? result) (::refused result))))

(defn ^{:malli/schema [:=> [:cat :any] [:maybe :map]]}
  refused
  "The live claim a `with-claim` call refused to compete with — nil when it ran,
   and nil ALSO when it refused and the holder was gone before it could be read.
   Ask `refused?` first; this only says who, never whether. Callers branch on
   these two rather than on the keys, so the sentinel stays this namespace's
   business."
  [result]
  (when (map? result) (::holder result)))
