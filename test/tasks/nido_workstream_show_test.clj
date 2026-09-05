(ns tasks.nido-workstream-show-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [nido.platform.core :as core]
   [nido.coordinator.lane.findings :as findings]
   [nido.coordinator.source.queue :as queue]
   [nido.coordinator.record.state :as cstate]
   [nido.coordinator.record.tickets :as tickets]
   [nido.coordinator.record.workstream :as ws]
   [tasks.nido-workstream :as t]))

(defn- with-tmp [f]
  (let [tmp (fs/create-temp-dir)]
    (try (with-redefs [core/nido-root (constantly (str tmp))]
           (cstate/ensure-dirs!) (f))
         (finally (fs/delete-tree tmp)))))

(deftest show-surfaces-findings-from-the-active-ledger
  (with-tmp
    (fn []
      (with-redefs [queue/enqueue! (fn [_] "/q/x.edn")]
        (tickets/open! :brian "BR-9" {:title "T" :url "u"})
        (let [w (ws/create! :brian {:stage :in-progress
                                    :external-refs [{:adapter :notion :id "BR-9"}]})]
          ;; prior own-entry → active ledger is the workstream's own entries.
          ;; :note is unregistered (freeform md), so it skips :review's EDN-schema
          ;; validation — we only need SOME prior own-entry here, not a real review.
          (ws/append-entry! :brian (:id w) {:kind :note} "prior review")
          (ws/close! :brian (:id w) :done)
          (findings/file! :brian (:id w)
                          {:items [{:summary "Save button 500s" :severity :blocker}]})
          (let [out (with-out-str (t/show* {:project "brian" :ws-id (:id w)}))]
            (is (str/includes? out (:id w)))                ; prints the ws-id
            (is (str/includes? out "Findings round 1"))      ; renders the findings event
            (is (str/includes? out "Save button 500s"))))))))

(deftest show-names-the-unindexed-entries-and-stays-quiet-about-gaps
  (with-tmp
    (fn []
      (let [w   (ws/create! :brian {:stage :in-progress :external-refs []})
            _   (ws/append-entry! :brian (:id w) {:kind :note} "one")
            dir (fs/path (cstate/workstream-dir :brian (:id w)) "entries")]
        ;; An interrupted append: the payload reached the disk, the index never
        ;; learned of it, and every line show prints below comes off the index.
        (spit (str (fs/path dir "0002-note.md")) "the lost append")
        (let [out (with-out-str (t/show* {:project "brian" :ws-id (:id w)}))]
          (is (str/includes? out "LEDGER INDEX IS BEHIND THE DISK"))
          (is (str/includes? out "entries/0002-note.md")
              "a reader told the ledger is untrustworthy needs the path to open"))
        ;; Numbering past the orphan and clearing it leaves a permanent gap in
        ;; the seq sequence — the state every long-lived ledger reaches, and one
        ;; a reader must not be warned about.
        (ws/append-entry! :brian (:id w) {:kind :note} "three")
        (fs/delete (fs/path dir "0002-note.md"))
        (let [out (with-out-str (t/show* {:project "brian" :ws-id (:id w)}))]
          (is (not (str/includes? out "BEHIND THE DISK"))
              "an intact ledger is reported by silence, or the warning means nothing"))))))
