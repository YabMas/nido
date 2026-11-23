(ns nido.coordinator.record.history
  "Test fixtures for ledger history nothing may write any more.

   A :design-approved is refused on append, but live ledgers hold grants written before the
   refusal, and everything that reads a ledger has to go on placing them as it did. A fixture for
   one is therefore planted in the test's in-process core the way history left it — the entry and
   its index row, asking none of the append's questions — rather than through a door that is
   shut."
  (:require
   [babashka.fs :as fs]
   [nido.coordinator.record.entry :as entry]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.workstream :as ws]
   [nido.core.client :as client]
   [nido.core.local :as local]))

(def ^:private stored-status-keys #{:stage :stage-history :closed :findings})

(defn write!
  "Set workstream `w` — a record as `ws/read-ws` answers one — as the test's core holds it: its
   identity and its index rows exactly as given (a row's :ext read off a legacy :file, else
   EDN), with `contents` (seq → text) as entries' content. Status fields records once stored are
   dropped, as the ledger never read them. Returns the record as read back."
  ([w] (write! w {}))
  ([w contents]
   (fs/create-dirs (cstate/workstream-dir (:project w) (:id w)))
   (local/put! (client/ws-key (:project w) (:id w))
               (fn [st]
                 (assoc st
                        :identity (apply dissoc w :id :entries stored-status-keys)
                        :ledger (mapv (fn [row]
                                        (-> row
                                            (assoc :ext (or (:ext row)
                                                            (some->> (:file row) (re-find #"\.([a-z]+)$") second)
                                                            "edn"))
                                            (dissoc :file)))
                                      (:entries w))))
               contents)
   (ws/read-ws (:project w) (:id w))))

(defn append-past-the-refusal!
  "Append `record` as an entry of `kind` to workstream `ws-id` of `project` without validating it
   or running any cross-record check. Returns the :seq it was given, numbered as the ledger
   numbers its next entry. `at` is the time it was written at."
  ([project ws-id kind record] (append-past-the-refusal! project ws-id kind record "2026-08-21T00:00:00Z"))
  ([project ws-id kind record at]
   (let [w     (ws/read-ws project ws-id)
         seq-n (inc (or (:seq (peek (:entries w))) 0))]
     (write! (update w :entries conj {:kind kind :seq seq-n :at at :ext "edn"})
             {seq-n (pr-str record)})
     seq-n)))

(defn grant!
  "A person's grant of the design at `design-seq`, answering the decision at `at-seq`, as an older
   ledger holds one. `extra` merges into the record — :note, :overrules, :carried. Returns its seq."
  ([project ws-id design-seq at-seq] (grant! project ws-id design-seq at-seq nil))
  ([project ws-id design-seq at-seq extra]
   (append-past-the-refusal! project ws-id :design-approved
                             (merge {:format :design-approved :design {:seq design-seq}
                                     :at-seq at-seq}
                                    extra))))

(defn rewrite!
  "Replace the content of the entry at `seq-n` on workstream `ws-id` with `text` — what no writer
   can do, for a test of a reader that meets an entry it cannot read, or one written by a newer
   writer. Forgets every entry this process has read, so the next read sees it."
  [project ws-id seq-n text]
  (local/put! (client/ws-key project ws-id) identity {seq-n text})
  (reset! @#'entry/texts {})
  (reset! @#'entry/parsed-cache {})
  nil)

