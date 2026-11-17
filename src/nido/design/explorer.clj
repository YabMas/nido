(ns nido.design.explorer
  "fukan's explorer over a project's declared design, run as a process nido owns.

   The explorer is fukan's: it draws the declaration, and the viewer picks what to nest and what to
   link in the page. This namespace only starts one over a directory holding a declaration, says
   where it is listening once it is, and stops it. Like `nido.design.check` it shells to the
   project's fukan command and never reads the model itself.

   A directory rather than a revision, for the reason `nido.design.check/diff` takes one: a
   revision is a VCS question this band may not reach, so the caller materializes one and hands
   the result down."
  (:require [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nido.design.check :as design]))

(def ^:private announcement
  "The one line `fukan.cli explore` prints to stdout once it is listening."
  #"fukan explorer: http://localhost:(\d+)/")

(defn ^{:malli/schema [:=> [:cat :ProjectName :string] [:maybe :map]]}
  start!
  "Start fukan's explorer over the declaration in `dir`, read the way `project-name` configures,
   and answer the handle `address` and `stop!` take — nil when `dir` declares no design.

   Returns at once: the process takes seconds to build the model, and until it announces its port
   `address` answers `:starting`. It is given port 0 and reports the one it got, so two projects'
   explorers never contend for a port. The process dies with this one."
  [project-name dir]
  (when-let [{:keys [cmd spec-dirs]} (design/design-of project-name dir)]
    (let [proc   (process/process (concat cmd ["explore" "--spec-dirs" (str/join "," spec-dirs)
                                               "--port" "0"])
                                  {:dir (str dir) :err :string :shutdown process/destroy})
          status (atom {:status :starting})]
      ;; stdout carries the announcement and nothing else; stderr is kept for when it never comes
      (future
        ;; the reader is left open: closing stdout under a live server would fail its next write
        (let [line (some #(when (re-find announcement %) %) (line-seq (io/reader (:out proc))))]
          (if-let [[_ port] (some->> line (re-find announcement))]
            (reset! status {:status :serving :port (parse-long port)})
            (let [{:keys [exit err]} @proc]
              (reset! status {:status :failed
                              :error  (let [e (str/trim (str err))]
                                        (if (str/blank? e) (str "fukan exited " exit) e))})))))
      {:proc proc :status status})))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :map]}
  address
  "Where an explorer `start!` returned is, as far as it has said: `{:status :starting}`,
   `{:status :serving :port p}`, or `{:status :failed :error e}` when it exited without serving.
   An explorer that dies after serving keeps answering `:serving`; a request to it is refused, and
   the caller learns it there."
  [handle]
  (if handle @(:status handle) {:status :failed :error "no explorer was started"}))

(defn ^{:malli/schema [:=> [:cat [:maybe :map]] :nil]}
  stop!
  "Stop an explorer `start!` returned. Stopping one that already exited is a no-op."
  [handle]
  (when handle (process/destroy (:proc handle)))
  nil)
