(ns nido.ui.server
  "HTTP server for the nido dashboard."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nido.coordinator.breakers :as breakers]
            [nido.coordinator.findings :as findings]
            [nido.coordinator.halt :as halt]
            [nido.coordinator.pickup :as pickup]
            [nido.coordinator.queue :as queue]
            [nido.coordinator.triggers :as triggers]
            [nido.notion.client :as client]
            [nido.process :as proc]
            [nido.project :as project]
            [nido.session.dev :as dev]
            [nido.ui.health :as health]
            [nido.ui.views :as views]
            [nido.ui.view-state :as view-state]
            [nido.work :as work]
            [org.httpkit.server :as http]))

;; ---------------------------------------------------------------------------
;; Response helpers

(defn- html-response [status body]
  {:status status
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body body})

(defn- sse-fragment
  "Format an SSE event that patches elements via datastar."
  [html-fragment]
  (let [lines (str/split-lines html-fragment)]
    (str "event: datastar-patch-elements\n"
         (str/join "\n" (map #(str "data: elements " %) lines))
         "\n\n")))

(defn- sse-response [body]
  {:status 200
   :headers {"Content-Type" "text/event-stream"
             "Cache-Control" "no-cache"
             "Connection" "keep-alive"}
   :body body})

(defn- png-resource-response
  "Serve a classpath PNG resource. Both circular crops of resources/nido-icon.png
   are cut by `clojure -M scripts/gen-favicon.clj`: `favicon.png` is zoomed hard
   to fill the disc at tab size; `nido-logo.png` is looser (keeps the gold ring)
   for the larger rail home-button mark."
  [resource-name]
  (if-let [res (io/resource resource-name)]
    {:status 200
     :headers {"Content-Type" "image/png"
               "Cache-Control" "public, max-age=86400"}
     :body (io/input-stream res)}
    {:status 404 :body ""}))

;; ---------------------------------------------------------------------------
;; Routing

(defn- parse-path
  "Parse a URI path into segments, ignoring empty strings."
  [uri]
  (vec (remove str/blank? (str/split uri #"/"))))

;; ---------------------------------------------------------------------------
;; Rail seam + context

(defn read-rail-daemon "Seam over health for stubbing in tests." [] (health/read-daemon-health))

(defn derive-screen
  "Impure wiring: gather what only IO can produce (grouped rows, gates, in-flight
   resolve keys), hand off to the pure work/screen, then attach the selection
   detail. Selection detail is attached HERE (not in work) because it needs the
   dev layer, which work must not depend on. Every /workstreams + / render route
   runs through this one function, so no two render sites disagree."
  [view-state]
  (let [screen (work/screen view-state
                            {:groups           (work/all-grouped)
                             :gates            (work/all-gates)
                             :pending          (dev/pending-resolve-keys)
                             :winddown-pending (dev/pending-winddown-keys)})
        sel (:selection view-state)
        ws-errors (dev/failed-ws-errors)]
    (-> screen
        (assoc :selection
               (when sel
                 (let [ws (when (= :workstreams (:surface view-state))
                            (work/workstream (:project sel) (:ws-id sel) (:entry view-state)))]
                   (cond-> {:project (:project sel) :ws-id (:ws-id sel)}
                     ws (assoc :ws (cond-> ws
                                     (get ws-errors (str (:project sel) "/" (:ws-id sel)))
                                     (assoc :error-msg (get ws-errors (str (:project sel) "/" (:ws-id sel)))))
                               :dev-states (dev/ws-session-dev-states (:project sel) ws)
                               :machine (work/machine-facts (:project sel) (map :name (:sessions ws))))))))
        ;; A gate whose Apply/Reply failed keeps its buttons (it stays retryable) and
        ;; carries the reason — the resolve is async, so this is the only place the
        ;; user ever learns the click did something and that something went wrong.
        (update :gates
                (fn [gates]
                  (mapv (fn [g]
                          (let [err (get ws-errors (str (:project g) "/" (:ws-id g)))]
                            (cond-> g
                              (and err (not (:pending? g))) (assoc :error-msg err))))
                        gates)))
        (update :groups
                (fn [groups]
                  (mapv (fn [{:keys [project] :as g}]
                          (update-in g [:grouped :winding-down]
                                     (fn [rows]
                                       (mapv (fn [row]
                                               (let [facts (work/machine-facts project (:sessions row))
                                                     total (->> (vals facts)
                                                                (mapcat (juxt :repl-rss :pg-rss))
                                                                (remove nil?)
                                                                (reduce + 0))
                                                     err   (get ws-errors
                                                                (str project "/" (:ws-id row)))]
                                                 (cond-> row
                                                   (pos? total) (assoc :rss-str (proc/human-bytes total))
                                                   (and err (not (:pending? row))) (assoc :error-msg err))))
                                             rows))))
                        groups))))))

(defn- rail-ctx
  "Rail context for the screen-based surfaces (needs, workstreams). The badge
   count is the screen's own needs-count, so rail + inbox always agree."
  [active screen]
  {:active      active
   :scope       (:scope screen)
   :tab         (:tab screen)
   :needs-count (:needs-count screen)
   :daemon      (read-rail-daemon)
   :projects    (mapv (comp name key) (project/list-projects))})

(defn- needs-fragment-response [screen]
  (sse-response
   (sse-fragment
    (str (views/needs-fragment screen)
         (views/rail-status-fragment {:needs-count (:needs-count screen)
                                      :daemon (read-rail-daemon)})))))

(defn- workstreams-fragment-response [screen]
  (sse-response
   (sse-fragment
    (str (views/workstreams-fragment screen)
         (views/rail-status-fragment {:needs-count (:needs-count screen)
                                      :daemon (read-rail-daemon)})))))

(defn- ops-context []
  {:daemon   (read-rail-daemon)
   :halt     (halt/read-halt-info)
   :breakers (breakers/tripped-triggers)
   :triggers (into {}
                   (for [[pname _] (project/list-projects)]
                     [(keyword pname)
                      (->> (triggers/load-for-project (keyword pname))
                           (filter #(= :manual (-> % :source :type)))
                           vec)]))})

(defn- ops-fragment-response
  "`scope` filters the badge count to one project's gates (string :project on
   each gate); \"all\" (or omitted) counts every gate, matching prior behavior."
  ([] (ops-fragment-response "all"))
  ([scope]
   (sse-response
    (sse-fragment
     (str (views/ops-panel-fragment (ops-context))
          (views/rail-status-fragment
           {:needs-count (->> (work/all-gates)
                              (filter #(or (= "all" scope) (= scope (:project %))))
                              count)
            :daemon (read-rail-daemon)}))))))

;; ---------------------------------------------------------------------------
;; Routing

(defn- ws-pane-fragment-response
  "SSE that patches #ws-pane with a freshly-rendered workstream pane (ws detail +
   per-session dev-env state). `entry` selects which ledger entry's report to show.
   A failed gate action rides along as :error-msg — this poll is what replaces the
   optimistic confirm fragment, so it has to carry the bad news too."
  ([project ws-id] (ws-pane-fragment-response project ws-id nil))
  ([project ws-id entry]
   (let [ws  (work/workstream project ws-id entry)
         err (get (dev/failed-ws-errors) (str project "/" ws-id))]
     (sse-response
      (sse-fragment
       (views/workstream-pane (cond-> ws err (assoc :error-msg err))
                              (dev/ws-session-dev-states project ws)
                              (work/machine-facts project (map :name (:sessions ws)))))))))

(defn- parse-json-body
  "Read a Datastar JSON signal body into a map, or {} when absent/unparseable."
  [body]
  (try
    (if body (json/parse-string (slurp body) true) {})
    (catch Exception _ {})))

(def ^:private valid-severities #{:blocker :tweak :nice-to-have})

(defn parse-findings-lines
  "Parse a textarea of findings, one per line `severity | area | summary`. Blank
   lines are skipped; a blank/unknown severity defaults to :tweak; a blank area is
   omitted. Returns a vector of {:summary :severity (:area)} maps."
  [s]
  (->> (str/split-lines (or s ""))
       (map str/trim)
       (remove str/blank?)
       (mapv (fn [line]
               (let [[sev area summary] (map str/trim (str/split line #"\|" 3))
                     sev-kw (keyword (str/lower-case (or sev "")))]
                 (cond-> {:summary  (or (not-empty summary) line)
                          :severity (if (valid-severities sev-kw) sev-kw :tweak)}
                   (not-empty area) (assoc :area area)))))))

(defn resolve-failure-msg
  "Error message for a work/resolve-gate! result that did NOT do what the
   optimistic confirmation toast promised, or nil when it settled fine. These
   resolvers signal failure by VALUE (a :decision), not by throwing, so this is
   the only place the difference is drawn.

   :no-workstream counts as a failure: the resolver matched no workstream and no
   recoverable ticket, so the click did literally nothing — clearing the state on
   that leaves the '✓ Restored'/'✓ Dismissed' toast standing as the last word.
   :already-in-flight counts as a failure for the same reason — the click added
   nothing, so the '✓' toast must not stand as the last word."
  [{:keys [decision error status]}]
  (case decision
    :no-workstream          "Nothing happened — no workstream or ticket behind this row."
    :no-trigger             "No triage trigger configured for this project."
    :already-in-flight      "Skipped — a session for this ticket is already in flight."
    :unresolved             (str "Couldn't resolve the ticket in Notion"
                                 (when error (str ": " (name error))))
    (:notion-failed :error) (str "Apply failed"
                                 (when error (str ": " (name error)))
                                 (when status (str " " status)))
    nil))

(defn- gate-resolve!
  "Run work/resolve-gate! on a background thread, tracking optimistic state per
   (project,ws-id) so the inbox/pane reflect 'working…' until it settles.
   Returns true if it actually started a resolve, false if the guard dropped it.

   A key already mid-flight is dropped rather than resolved again. This is what
   stops a double-clicked Start triage from spawning two triage sessions on one
   ticket: work/start-triage-page!'s own ref-dedup guard cannot fire on a bare
   row's first click (it resolves through ws/find-by-ref, and a bare row has no
   workstream), and spawn-records! persists the workstream before the session, so
   a second click inside that window would spawn again. Note the honest limit —
   set-app-state! is a plain atom write, not a lock, so this closes the
   human-double-click window (tens of ms), not a genuine concurrent race.

   The guard is keyed per WORKSTREAM, not per action — deliberately, since the
   key space is shared with bring-down! (session.dev/pending-winddown-keys reads
   the same \"<project>/<ws-id>\" shape). A caller that returns false must not
   render the per-action success toast: the click landed while a DIFFERENT
   action was in flight and this one never ran (see resolve-failure-msg's
   :already-in-flight case)."
  [project ws-id action-id input]
  (let [k (str project "/" ws-id)]
    (if (contains? (dev/pending-resolve-keys) k)
      false
      (do
        (dev/set-app-state! k (if (= :reply action-id) :resuming :resolving))
        (future
          (try
            (if-let [msg (resolve-failure-msg (work/resolve-gate! project ws-id action-id input))]
              (dev/set-app-state! k :failed msg)
              (dev/clear-app-state! k))
            (catch Exception e
              (dev/set-app-state! k :failed (or (:reason (ex-data e)) (ex-message e))))))
        true))))

(defn- gate-action-response-fragment
  "The pane/gate fragment for a POST'd gate action: the per-action success toast
   when `started?` (gate-resolve! actually kicked off the resolve), else the
   honest :already-in-flight copy — reusing resolve-failure-msg so that sentence
   has one source of truth, not a second copy written at the call site."
  [started? action-id project ws-id pane-id]
  (if started?
    (views/gate-action-confirm-fragment action-id project ws-id pane-id)
    (views/gate-action-skip-fragment
     (resolve-failure-msg {:decision :already-in-flight}) project ws-id pane-id)))

(defn- handle-post [{:keys [uri body] :as req}]
  (let [segs (parse-path uri)]
    (cond
      ;; POST /gate/:project/:ws-id/:action — resolve a gate follow-action
      (and (= 4 (count segs)) (= "gate" (first segs)))
      (let [project   (nth segs 1)
            ws-id     (nth segs 2)
            action-id (keyword (nth segs 3))
            input     (when (= :reply action-id) (:reply (parse-json-body body)))
            started?  (gate-resolve! project ws-id action-id input)]
        (sse-response (sse-fragment
                       (gate-action-response-fragment started? action-id project ws-id "gate-pane"))))

      ;; POST /workstreams/:project/:ws-id/gate/:action — resolve a gate action from
      ;; the overview/detail pane (the stage-appropriate action bar below the reader).
      ;; Reuses gate-resolve!; :reply carries the textarea input like the home route.
      ;; The confirmation patches #ws-pane, the list's 5s poll drops the resolved row.
      (and (= 5 (count segs)) (= "workstreams" (first segs)) (= "gate" (nth segs 3)))
      (let [project   (nth segs 1)
            ws-id     (nth segs 2)
            action-id (keyword (nth segs 4))
            input     (when (= :reply action-id) (:reply (parse-json-body body)))
            started?  (gate-resolve! project ws-id action-id input)]
        (sse-response (sse-fragment
                       (gate-action-response-fragment started? action-id project ws-id "ws-pane"))))

      ;; POST /workstreams/:project/:ws-id/sessions/:session/dev/:action
      (and (= 7 (count segs)) (= "workstreams" (first segs))
           (= "sessions" (nth segs 3)) (= "dev" (nth segs 5)))
      (let [project (nth segs 1) ws-id (nth segs 2)
            session (java.net.URLDecoder/decode (nth segs 4) "UTF-8")
            action  (nth segs 6)]
        (dev/dev-action! project ws-id session action)
        (ws-pane-fragment-response project ws-id))

      ;; POST /workstreams/pickup/:project — resolve a pasted Notion ref, enqueue
      ;; the :plan-bug leg, and patch #pickup-result with the continuing/new report.
      (and (= 3 (count segs)) (= "workstreams" (first segs)) (= "pickup" (nth segs 1)))
      (let [project (nth segs 2)
            input   (str/trim (str (:pickup (parse-json-body body))))
            ready?  (= :up (:state (read-rail-daemon)))]
        (if (str/blank? input)
          (sse-response
           (sse-fragment
            (views/pickup-result-fragment {:decision :unresolved :error :unrecognized-input}
                                          {:project project :daemon-ready? ready?})))
          (let [result (pickup/pickup! (keyword project) input (client/keychain-token))]
            (sse-response
             (sse-fragment
              (views/pickup-result-fragment result {:project project :daemon-ready? ready?}))))))

      ;; POST /workstreams/:project/:ws-id/winddown — bring a closed workstream's
      ;; leftover sessions down. Optimistic :stopping keyed "project/ws-id" (same
      ;; key-space as gate-resolve!) marks the row pending until down! settles.
      (and (= 4 (count segs)) (= "workstreams" (first segs)) (= "winddown" (nth segs 3)))
      (let [project (nth segs 1) ws-id (nth segs 2) k (str project "/" ws-id)]
        (dev/set-app-state! k :stopping)
        (future
          (try (work/bring-down! project ws-id)
               (dev/clear-app-state! k)
               (catch Exception e
                 (dev/set-app-state! k :failed (ex-message e)))))
        (workstreams-fragment-response (derive-screen (view-state/parse req))))

      ;; POST /workstreams/:project/:ws-id/findings — file a staging findings round
      (and (= 4 (count segs)) (= "workstreams" (first segs)) (= "findings" (nth segs 3)))
      (let [project (nth segs 1)
            ws-id   (nth segs 2)
            b       (parse-json-body body)
            items   (parse-findings-lines (:findings b))]
        (when (seq items)
          (try
            (findings/file! (keyword project) ws-id
                            {:items items :staging-ref (not-empty (:staging b))})
            (catch Exception e
              (println "[nido ui] findings/file! failed:" (ex-message e)))))
        (ws-pane-fragment-response project ws-id))

      ;; POST /ops/... — ambient ops levers (halt/resume, breaker clear, fire).
      ;; Every lever responds with the refreshed ops fragment + rail status.
      (= "ops" (first segs))
      (do
        (cond
          (= ["ops" "halt"] segs)   (halt/halt! {:source :user :note "from dashboard"})
          (= ["ops" "resume"] segs) (halt/resume!)
          ;; /ops/breakers/:project/:trigger/clear
          (and (= 5 (count segs)) (= "breakers" (second segs)) (= "clear" (nth segs 4)))
          (breakers/enable! (keyword (nth segs 2)) (keyword (nth segs 3)))
          ;; /ops/fire/:project/:trigger — placeholder values ride the JSON signal body
          (and (= 4 (count segs)) (= "fire" (second segs)))
          (let [project (keyword (nth segs 2))
                tname   (keyword (nth segs 3))
                trig    (->> (triggers/load-for-project project)
                             (filter #(= tname (:name %))) first)
                ks      (triggers/placeholder-keys (or (:payload trig) "{}"))
                body*   (parse-json-body body)
                payload (into {} (for [k ks]
                                   [k (str (get body* (keyword (views/fire-signal tname k)) ""))]))]
            (queue/enqueue! {:target {:project project :trigger tname} :payload payload}))
          :else nil)
        (ops-fragment-response (:scope (view-state/parse req))))

      :else
      (html-response 404 (views/not-found-page)))))

(defn- handle-get [{:keys [uri] :as req}]
  (let [segments (parse-path uri)]
    (case segments
      ;; GET /favicon.{png,ico} — the nido icon (browsers auto-request .ico)
      ["favicon.png"] (png-resource-response "favicon.png")
      ["favicon.ico"] (png-resource-response "favicon.png")
      ;; GET /nido-logo.png — looser circular mark for the rail home button
      ["nido-logo.png"] (png-resource-response "nido-logo.png")

      ;; GET / — Needs you (home)
      []
      (let [screen (derive-screen (view-state/parse req))]
        (html-response 200 (views/needs-page (rail-ctx :needs screen) screen)))

      ;; GET /system — dissolved; the rail lost the destination, redirect callers on.
      ["system"]
      {:status 302 :headers {"Location" "/workstreams"}}

      ;; GET /workstreams — overview (selection, if any, comes from ?sel=)
      ["workstreams"]
      (let [screen (derive-screen (view-state/parse req))]
        (html-response 200 (views/workstreams-page (rail-ctx :workstreams screen) screen)))

      ;; GET /_fragment/workstreams — SSE workstreams refresh
      ["_fragment" "workstreams"]
      (workstreams-fragment-response (derive-screen (view-state/parse req)))

      ;; GET /_fragment/needs — queue + rail
      ["_fragment" "needs"]
      (needs-fragment-response (derive-screen (view-state/parse req)))

      ;; GET /_fragment/ops — SSE ops-panel refresh (patches #ops-panel + rail).
      ;; Scope rides ?scope=, parsed the same way every other view-state is —
      ;; so the shell's scoped poll (see views/shell) gets a scoped badge count
      ;; instead of the unscoped total clobbering it every 5s.
      ["_fragment" "ops"]
      (ops-fragment-response (:scope (view-state/parse req)))

      ;; Otherwise, dispatch on structure
      (cond
        ;; GET /_fragment/workstream/:project/:ws-id — SSE pane refresh (patches #ws-pane)
        (and (= 4 (count segments)) (= "_fragment" (first segments)) (= "workstream" (nth segments 1)))
        (ws-pane-fragment-response (nth segments 2) (nth segments 3) (:entry (view-state/parse req)))

        ;; GET /workstreams/:project/:ws-id — legacy deep link → canonical selection
        (and (= 3 (count segments)) (= "workstreams" (first segments)))
        (let [vs     (assoc (view-state/parse req) :surface :workstreams
                            :selection {:project (nth segments 1) :ws-id (nth segments 2)})
              screen (derive-screen vs)]
          (html-response 200 (views/workstreams-page (rail-ctx :workstreams screen) screen)))

        ;; GET /gate/:project/:ws-id — legacy deep link → needs surface, gate selected
        (and (= 3 (count segments)) (= "gate" (first segments)))
        (let [vs     (assoc (view-state/parse req) :surface :needs
                            :selection {:project (nth segments 1) :ws-id (nth segments 2)})
              screen (derive-screen vs)]
          (html-response 200 (views/needs-page (rail-ctx :needs screen) screen)))

        :else
        (html-response 404 (views/not-found-page))))))

(defn handle-request [{:keys [request-method] :as req}]
  (case request-method
    :post (handle-post req)
    (handle-get req)))

;; ---------------------------------------------------------------------------
;; Server lifecycle

(defonce ^:private server-atom (atom nil))

(defn start!
  "Start the dashboard server."
  [{:keys [port] :or {port 8800}}]
  (when-let [old @server-atom]
    (old))
  (let [stop-fn (http/run-server handle-request {:port port})]
    (reset! server-atom stop-fn)
    (println (str "[nido] Dashboard running at http://localhost:" port))
    stop-fn))

(defn stop! []
  (when-let [stop-fn @server-atom]
    (stop-fn)
    (reset! server-atom nil)
    (println "[nido] Dashboard stopped")))
