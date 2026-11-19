(ns nido.coordinator.record.history
  "Test fixtures for ledger history nothing may write any more.

   A :design-approved is refused on append, but live ledgers hold grants written before the
   refusal, and everything that reads a ledger has to go on placing them as it did. A fixture for
   one is therefore built the way history built it — the entry file and the index row written
   directly, asking none of the append's questions — rather than through a door that is shut."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.platform.io :as pio]))

(defn append-past-the-refusal!
  "Append `record` as an entry of `kind` to workstream `ws-id` of `project` without validating it
   or running any cross-record check. Returns the :seq it was given, numbered as the ledger
   numbers its next entry."
  [project ws-id kind record]
  (let [w     (ws/read-ws project ws-id)
        seq-n (inc (count (:entries w)))
        rel   (format "entries/%04d-%s.edn" seq-n (name kind))]
    (pio/write-text! (str (fs/path (cstate/workstream-dir project ws-id) rel))
                     (pr-str record))
    (ws/write! (update w :entries conj
                       {:kind kind :seq seq-n :at "2026-08-21T00:00:00Z" :file rel}))
    seq-n))

(defn grant!
  "A person's grant of the design at `design-seq`, answering the decision at `at-seq`, as an older
   ledger holds one. `extra` merges into the record — :note, :overrules, :carried. Returns its seq."
  ([project ws-id design-seq at-seq] (grant! project ws-id design-seq at-seq nil))
  ([project ws-id design-seq at-seq extra]
   (append-past-the-refusal! project ws-id :design-approved
                             (merge {:format :design-approved :design {:seq design-seq}
                                     :at-seq at-seq}
                                    extra))))
