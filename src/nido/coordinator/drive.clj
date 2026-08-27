;; src/nido/coordinator/drive.clj
(ns nido.coordinator.drive
  "Where a driven workstream stops.

   The driver itself is the layer above this one. This is deliberately the
   earlier half: a stage the driver fires can reach :escalate on its very first
   run, so the place it stops has to exist before anything can stop there. A
   driver that could fire but not park would strand a workstream mid-pipeline
   with nothing on the ledger saying why.

   Parking is two facts and no cleverness. The ledger gains a halt saying what
   was needed and what had already been tried, and the session — if one is alive
   — goes to :parked so the gate surface offers it. Neither is derivable from the
   other: the ledger entry is what the NEXT session reads, and the phase is what
   puts the row in front of a human now."
  (:require
   [nido.coordinator.report :as report]
   [nido.coordinator.session :as session]
   [nido.coordinator.workstream :as ws]
   [nido.pipeline :as pipeline]))

(defn attempt
  "One entry for a halt's :tried — what a stage did and what it ended on.

   The terminal keyword is carried verbatim rather than described, so the record
   a human reads and the value `pipeline/disposition` classified are the same
   thing."
  [{:keys [stage outcome rounds detail]}]
  (cond-> {:stage stage :outcome outcome}
    (and rounds (pos? rounds)) (assoc :rounds rounds)
    detail                     (assoc :detail detail)))

(defn halt-for
  "The halt record for a stage that reached `outcome`, as a value.

   Pure, and separate from writing it, because what a halt SAYS is the part worth
   testing and the part a reader will argue with. The prose is deliberately thin:
   the outcome names itself, the attempt says what produced it, and inventing a
   fuller explanation here would be this module guessing at a judgement the round
   already made and recorded.

   `needs` is the caller's, because only the stage that ran knows what it could
   not settle. Absent, the question falls back to the honest general one — a
   reader is better served by `the driver cannot take this further` than by a
   confident sentence nobody derived."
  [{:keys [stage outcome needs summary tried options]}]
  (cond-> {:format  :blocker
           :summary (or summary
                        (str "the " (name stage) " stage stopped at "
                             (name outcome) " and the driver cannot take it further"))
           :needs   (or needs
                        (str "a decision about " (name stage)
                             " — everything derivable has been derived"))}
    (seq tried)   (assoc :tried (mapv attempt tried))
    (seq options) (assoc :options (vec options))))

(defn park!
  "Stop this workstream and record why. Returns {:parked <session-or-nil>
   :seq <ledger-seq>}, or {:refused <reason>} when there is nothing to write.

   Refuses rather than writing a malformed halt: a park that threw would leave
   the workstream running with a stage already finished, which is worse than one
   that stopped and said it could not explain itself.

   Best-effort on the PHASE and strict on the LEDGER, and the asymmetry is
   deliberate. The ledger entry is the durable half — it is what the next session
   reads and what the gate renders — while a session to park may simply not exist
   (a mechanical stage runs as a task, not as an agent, so there is often nobody
   to put to sleep). Failing the whole park because there was no session would
   throw away the record that matters."
  [project ws-id halt]
  (let [record (halt-for halt)]
    (if-not (try (report/validate-event :blocker record) (catch Throwable _ nil))
      {:refused :invalid-halt :record record}
      (let [path (ws/append-entry! project ws-id {:kind :blocker} (pr-str record))
            seq-n (some->> path (re-find #"/(\d+)-blocker\.edn$") second parse-long)
            s    (->> (session/list-sessions project ws-id)
                      (remove session/parked?)
                      (filter :autonomy)
                      first)]
        (when s
          (try (session/set-phase! project ws-id (:name s) :parked)
               (catch Throwable _ nil)))
        {:parked (:name s) :seq seq-n}))))

(defn park-on-escalate!
  "Park iff this outcome's disposition is :escalate; otherwise do nothing and say
   so.

   The gate between a terminal and a halt lives here rather than at the call
   site, so every driven stage asks the question the same way and none of them
   carries its own opinion about which statuses are a person's business."
  [project ws-id {:keys [outcome] :as halt}]
  (let [d (pipeline/disposition outcome)]
    (if (= :escalate d)
      (assoc (park! project ws-id halt) :disposition d)
      {:disposition d})))
