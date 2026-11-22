(ns tasks.nido-coordinator
  "Bb task entry points for what remains of coordinator control once the core coordinates: the
   status of the supervised services and the sources, and the halt that stops intake.

   Usage:
     bb nido:coordinator:status
     bb nido:halt [:note \"...\"] / bb nido:coordinator:resume"
  (:require
   [nido.coordinator.daemon.halt :as halt]
   [nido.coordinator.daemon.supervisor :as sup]
   [nido.coordinator.source.state :as sst]
   [nido.coordinator.work :as work]
   [nido.coordinator.work.core :as wcore]
   [nido.platform.task-args :as task-args]))

;; The poll's own state says only whether the source is healthy, and a held sweep is
;; healthy: it polls on time and emits nothing, which reads "OK" exactly like a
;; discharged day. A hold whose session died is released by nothing but a close,
;; so on the one surface a person reads, a hold says what holds it and whether
;; anyone is still working on it.
(defn- source-health [s]
  (cond
    (= :open (:breaker s)) (str "breaker OPEN: "
                                (name (or (-> s :last-poll-result :error) :unknown)))
    (:held-by s)           (str "held by " (:held-by s) " at last poll")
    (and (:connection s) (not= :open (-> s :connection :state)))
    (let [{:keys [state since last-connected-at]} (:connection s)]
      (str "not connected (" (name state) ") since " since
           "; last connected " (or last-connected-at "never")))
    :else                  "OK"))

(defn ^{:malli/schema [:=> [:cat [:maybe [:vector :map]]] [:vector :string]]}
  sweep-hold-lines
  "The status lines for what holds each improvement sweep, from
   `work/improvement-holds`; empty when nothing does. A `:stuck` hold names the
   close that releases it, because no session will ever write that close. nil
   (the holds could not be read) is said, not read as no hold."
  [holds]
  (cond
    (nil? holds) ["Improvement sweep: holds could not be read"]
    :else
    (vec (for [{:keys [project ws-id address state started-at ended-at]} holds
               line (case state
                      :stuck
                      [(format "⚠ Improvement sweep (%s) STUCK: %s holds it (claim %s, since %s) and no session is working on it%s"
                               project ws-id address started-at
                               (if ended-at (str " — its last session ended " ended-at) ""))
                       (format "    nothing will release it but a close: bb nido:workstream:close :project %s :ws-id %s :outcome vetoed"
                               project ws-id)]
                      :waiting-on-you
                      [(format "Improvement sweep (%s): held by %s (claim %s) — its session is parked at a gate for you"
                               project ws-id address)]
                      [(format "Improvement sweep (%s): held by %s (claim %s) — a session is working on it"
                               project ws-id address)])]
           line))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  status [& _args]
  (doseq [k [:core :intake :dashboard]]
    (let [{:keys [manager installed? running?]} (sup/with-service k sup/status)]
      (println (format "%-11s %s: %s" (str (name k) ":") (name manager)
                       (cond (and installed? running?) "installed (running)"
                             installed?                "installed (not running)"
                             :else                     "not installed")))))
  (println "Core socket:" (if (try (wcore/request! {:op :state :ws "status/probe"}) true
                                   (catch Exception _ false))
                            "answering" "NOT answering"))
  (when-let [h (halt/read-halt-info)]
    (println "Halted:     " (name (:source h)) "—" (or (:note h) "(no note)") "— intake stopped")
    (println "Halted at:  " (:halted-at h)))
  (let [hashes (sst/list-state-hashes)
        states (map (fn [h] [h (sst/read-state h)]) hashes)
        open   (filter (fn [[_ s]] (= :open (:breaker s))) states)]
    (when (seq hashes)
      (when (seq open)
        (println (format "⚠ %d source breaker(s) OPEN — auto-recovers on cooldown; force now: bb nido:coordinator:source:reset"
                         (count open))))
      (println "Sources:")
      (doseq [[h s] states]
        (println (format "  %s %s  (%s, %s)" (name (or (:type s) :unknown)) h (source-health s)
                         (or (:last-polled-at s) "never polled"))))))
  (run! println (sweep-hold-lines (try (work/improvement-holds) (catch Exception _ nil)))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  halt
  "bb nido:halt [:note \"...\"] — stops intake: no source is polled and no arrival opens anything."
  [& args]
  (let [[_ opts] (task-args/split-args args)]
    (halt/halt! {:source :user :note (some-> (:note opts) str)})
    (println "Coordinator: halted (user). Resume with: bb nido:coordinator:resume")))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  resume
  "bb nido:coordinator:resume — clears halted.edn so intake picks back up."
  [& _args]
  (halt/resume!)
  (println "Coordinator: resumed (halted.edn removed)."))
