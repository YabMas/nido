(ns nido.coordinator.record.session-events
  "What a Claude Code session reported about itself through nido's hooks, one append-only log
   per claude session id.

   The entries are FACTS — a session started, a prompt was submitted, a turn stopped, a
   permission was denied — never decisions. Whether a prompt was nido's or a person's, and what a
   stop means for a run, is read off them by the driver; nothing here knows."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [nido.coordinator.record.clock :as clock]
            [nido.coordinator.record.state :as cstate]
            [nido.platform.io :as io]))

(def kinds
  "The events a session reports, by the hook that reports each."
  #{:session-start :prompt :stop :permission-denied :notification})

(def SessionEvent
  [:map
   [:event (into [:enum] kinds)]
   [:at string?]
   [:cwd {:optional true} [:maybe string?]]
   [:permission-mode {:optional true} [:maybe string?]]])

(defn ^{:malli/schema [:=> [:cat :string :map] :boolean]}
  append!
  "Record one event for the claude session `session-id`, stamped with the time it was recorded
   when it carries none. Answers whether it was recorded.

   Never throws: its caller is a hook, and a hook that cannot record must still let the session
   proceed — a nido fault may cost an event, never a person's turn. Appends are serialised per
   session, so two hooks firing at once each land whole."
  [session-id event]
  (try
    (let [path (cstate/session-events-path session-id)
          line (str (pr-str (merge {:at (clock/now-iso)} event)) "\n")]
      (io/with-file-lock (io/lock-path-for path)
        (fn []
          (fs/create-dirs (fs/parent path))
          ;; A writer killed mid-append leaves a torn last line; end it first, so this event
          ;; lands on a line of its own instead of fusing with the torn one.
          (let [torn? (and (fs/exists? path) (pos? (fs/size path))
                           (with-open [f (java.io.RandomAccessFile. (str path) "r")]
                             (.seek f (dec (.length f)))
                             (not= (int \newline) (.read f))))]
            (spit path (if torn? (str "\n" line) line) :append true))))
      true)
    (catch Throwable _ false)))

(defn ^{:malli/schema [:=> [:cat :string [:maybe :int]] :map]}
  events-since
  "The events recorded for `session-id` after `cursor`, oldest first, as {:events [...] :cursor n}.
   A nil cursor reads from the start; the returned cursor is what to pass next. A session with no
   log has no events. A line that does not parse is skipped — and still counted, so a cursor never
   points back into it. Only lines ended by their newline are read or counted: this reads without
   the writers' lock, and a line still being appended is read whole on a later call, not skipped."
  [session-id cursor]
  (let [path  (cstate/session-events-path session-id)
        text  (if (fs/exists? path) (slurp path) "")
        whole (subs text 0 (or (some-> (str/last-index-of text "\n") inc) 0))
        lines (if (= "" whole) [] (str/split-lines whole))
        from  (or cursor 0)]
    {:events (into [] (keep #(try (edn/read-string %) (catch Exception _ nil)))
                   (drop from lines))
     :cursor (max from (count lines))}))

(defn ^{:malli/schema [:=> [:cat] [:vector :map]]}
  conversations
  "Every recorded conversation with the cwd and time of its last event that carried a cwd, as
   {:id :cwd :last-at-ms} — what ties a conversation nido did not drive to the session it ran in.
   A log with no such event, or whose time does not parse, names no conversation."
  []
  (let [dir (fs/parent (cstate/session-events-path "_"))]
    (if-not (fs/directory? dir)
      []
      (vec (for [f     (fs/glob dir "*.edn")
                 :let  [id   (str/replace (str (fs/file-name f)) #"\.edn$" "")
                        ev   (->> (:events (events-since id nil)) (filter :cwd) last)
                        ms   (try (.toEpochMilli (java.time.Instant/parse (:at ev)))
                                  (catch Exception _ nil))]
                 :when ms]
             {:id id :cwd (:cwd ev) :last-at-ms ms})))))
