(ns nido.ui.server
  "HTTP server for the nido dashboard."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nido.notion.client :as client]
            [nido.platform.process :as proc]
            [nido.platform.project :as project]
            [nido.session.fleet :as fleet]
            [nido.ui.dev :as dev]
            [nido.coordinator.control :as control]
            [nido.ui.views :as views]
            [nido.ui.view-state :as view-state]
            [nido.coordinator.work :as work]
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

(defn ^{:malli/schema [:=> [:cat] :map]}
  read-rail-daemon "Seam over health for stubbing in tests." [] (control/read-daemon-health))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:maybe :keyword]]}
  read-pickup-blocker
  "Seam over health for stubbing in tests. What blocks the leg a pickup fires,
   or nil when nothing does — deliberately NOT read-rail-daemon, whose :state is
   a severity ladder for the dot rather than a go/no-go for one envelope."
  [project]
  (control/read-queue-blocker (keyword project) work/pickup-trigger))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:maybe :keyword]]}
  read-intent-blocker
  "Seam over health for stubbing in tests. What blocks the leg a described intent
   fires, or nil when nothing does. Same question as read-pickup-blocker, asked
   about the other leg — a breaker is per-trigger, so the two answers differ."
  [project]
  (control/read-queue-blocker (keyword project) work/start-intent-trigger))

(defn ^{:malli/schema [:=> [:cat :any] :Screen]}
  derive-screen
  "Impure wiring: gather what only IO can produce (grouped rows, gates, in-flight
   resolve keys), hand off to the pure work/screen, then attach the selection
   detail. Selection detail is attached HERE (not in work) because it needs the
   dev layer, which work must not depend on. Every /workstreams + / render route
   runs through this one function, so no two render sites disagree."
  [view-state]
  (let [{:keys [groups gates]}
        ;; Both inside ONE with-shared-rows: they are two folds of the same
        ;; per-project rows, and sharing is what stops the render reading every
        ;; ledger twice.
        (work/with-shared-rows
          (fn [] {:groups (work/all-grouped) :gates (work/all-gates)}))
        screen (work/screen view-state
                            {:groups           groups
                             :gates            gates
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
                     ws (assoc :ws (cond-> (assoc ws :open-rounds (:rounds view-state)
                                                     :open-stage  (:stage view-state)
                                                     :history?    (:history? view-state))
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

(defn- fleet-context
  "The fleet card's facts, or nil if they could not be gathered.

   Wrapped because this is the only entry in the ops context that shells out
   (`lsof` twice, `ps` once, ~150ms). The panel it rides in carries halt/resume
   and breaker-clear — the levers you reach for when something is already
   wrong — so a memory readout that threw would take the emergency controls
   down with it. Failing to nil costs one card.

   `totals` is asked for no project on purpose: its `:typical` estimates the
   cost of an INCOMING session, and this card is not about one. Without it
   `over-budget?` reads as \"the machine is already past the line\", which is
   what ambient chrome should say."
  []
  (try
    (let [rows (fleet/snapshot)
          t    (fleet/totals rows nil)]
      (assoc (select-keys t [:sessions :fleet :in-use :machine])
             :over?       (fleet/over-budget? t)
             :signals-ok? (fleet/signals-ok? rows)
             ;; Each candidate carries whatever its own stop is doing. Resolved at
             ;; RENDER rather than on click: a row that cannot be acted on has to
             ;; say so before it is pressed, not after.
             :candidates  (mapv (fn [c]
                                  (let [{:keys [state error-msg]}
                                        (dev/current-app-state (:instance-id c))]
                                    (cond-> c
                                      (= :stopping state) (assoc :pending? true)
                                      (= :failed state)   (assoc :error-msg error-msg))))
                                (fleet/candidates rows))))
    (catch Throwable _ nil)))

(defn- ops-context []
  {:daemon   (read-rail-daemon)
   :halt     (control/halt-info)
   :fleet    (fleet-context)
   :breakers (control/tripped-triggers)
   :triggers (into {}
                   (for [[pname _] (project/list-projects)]
                     [(keyword pname)
                      (->> (control/triggers-for (keyword pname))
                           (filter #(= :manual (-> % :source :type)))
                           vec)]))})

(defn- all-proposals
  "Every proposal, from every project, unfiltered.

   Deliberately not scoped. Operating nido is a cross-project concern: an
   analysis runs nido-side whatever it reviewed, so scoping by the project that
   HOLDS a proposal answers a question nobody asks — it would hide every row
   from the reviewed project's own scope, which is the scope a reader is most
   likely to be in.

   Reads the ledger on each call rather than caching: the list is derived, and a
   decision made in another tab must show up on the next poll rather than when
   something invalidates."
  []
  (vec (mapcat (fn [[pname _]]
                 (try (work/proposals pname) (catch Throwable _ [])))
               (project/list-projects))))

(defn- operations-fragment-response []
  (sse-response (sse-fragment (views/operations-fragment (all-proposals)))))

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
   per-session dev-env state). `pos` is the reader's position in the pane, straight
   off the query string: `:entry` is the ledger entry open in the viewer (nil — the
   default — opens nothing), `:rounds` the review rounds unfolded inside it,
   `:stage` the arc stage expanded, `:history?` whether the raw ledger index is
   showing. A
   failed gate action rides along as :error-msg — this poll is what replaces the
   optimistic confirm fragment, so it has to carry the bad news too."
  ([project ws-id] (ws-pane-fragment-response project ws-id nil))
  ([project ws-id {:keys [entry rounds stage history?]}]
   (let [ws  (work/workstream project ws-id entry)
         err (get (dev/failed-ws-errors) (str project "/" ws-id))]
     (sse-response
      (sse-fragment
       (views/workstream-pane (cond-> ws
                                rounds   (assoc :open-rounds rounds)
                                stage    (assoc :open-stage stage)
                                history? (assoc :history? true)
                                err      (assoc :error-msg err))
                              (dev/ws-session-dev-states project ws)
                              (work/machine-facts project (map :name (:sessions ws)))))))))

(defn- parse-json-body
  "Read a Datastar JSON signal body into a map, or {} when absent/unparseable."
  [body]
  (try
    (if body (json/parse-string (slurp body) true) {})
    (catch Exception _ {})))

(def ^:private valid-severities #{:blocker :tweak :nice-to-have})

(defn ^{:malli/schema [:=> [:cat :string] :any]}
  parse-findings-lines
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

(defn ^{:malli/schema [:=> [:cat :map] :string]}
  resolve-failure-msg
  "Error message for a work/resolve-gate! result that did NOT do what the
   optimistic confirmation toast promised, or nil when it settled fine. These
   resolvers signal failure by VALUE (a :decision), not by throwing, so this is
   the only place the difference is drawn.

   :no-workstream counts as a failure: the resolver matched no workstream and no
   recoverable ticket, so the click did literally nothing — clearing the state on
   that leaves the '✓ Restored'/'✓ Dismissed' toast standing as the last word.
   :already-in-flight counts as a failure for the same reason — the click added
   nothing, so the '✓' toast must not stand as the last word."
  [{:keys [decision error status because]}]
  (case decision
    :no-workstream          "Nothing happened — no workstream or ticket behind this row."
    :no-trigger             "No triage trigger configured for this project."
    :already-in-flight      "Skipped — a session for this ticket is already in flight."
    :option-stale           (str "That option is no longer on the table — the report "
                                 "moved on since this page rendered. Re-read the gate.")
    :no-design              "Nothing to approve — this workstream holds no design."
    :approval-stale         (str "Not approved — the ledger moved on since this page "
                                 "rendered, so this would have granted a design you "
                                 "were not looking at. Re-read the gate.")
    ;; The premise went while it was being read. Naming the entry is the whole
    ;; of what a reader can act on, and `because` carries it up from standing.
    :approval-refused       (str "Not approved — the design no longer stands"
                                 (when-let [d (:detail because)] (str ": " d)))
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
        (dev/set-app-state! k (if (or (= :reply action-id) (work/option-action? action-id))
                                :resuming :resolving))
        (future
          (try
            (if-let [msg (resolve-failure-msg (work/resolve-gate! project ws-id action-id input))]
              (dev/set-app-state! k :failed msg)
              (dev/clear-app-state! k))
            (catch Exception e
              (dev/set-app-state! k :failed (or (:reason (ex-data e)) (ex-message e))))))
        true))))

(defn- click-payload
  "What a gate click carries besides its action id, which depends on the action:
   the reply textarea's text for :reply, and for every button that answers a
   ledger question the :seq of the report it was rendered from — posted as
   ?entry=, the same reading position every other surface rides, so the resolver
   can refuse an answer whose question the ledger has since moved past. nil for
   every other action: those resolve entirely nido-side.

   Which buttons those are is asked of the descriptor, not listed here. Every
   position-carrying button is rendered by the same branch of `action-button`
   (the one that appends ?entry= when a descriptor has a :seq), so a second list
   of ids kept in this namespace is a list that can disagree with it — and did:
   :approve was rendered with its position and read back without one, so
   `work/approve!` saw nil, took its stale branch, and no design was ever
   approved from the web."
  [{:keys [body] :as req} action-id]
  (cond
    (= :reply action-id)                     (:reply (parse-json-body body))
    (work/position-carrying-action? action-id) (:entry (view-state/parse req))))

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
            started?  (gate-resolve! project ws-id action-id (click-payload req action-id))]
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
            started?  (gate-resolve! project ws-id action-id (click-payload req action-id))]
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
            ;; Ask what blocks THIS envelope, not what color the rail dot is.
            ;; The dot ranks :breaker above :up, so reading it as go/no-go
            ;; reported a healthy daemon as down whenever any unrelated
            ;; trigger was tripped.
            blocked (read-pickup-blocker project)
            opts    {:project project :blocked-by blocked :trigger work/pickup-trigger}]
        (if (str/blank? input)
          (sse-response
           (sse-fragment
            (views/pickup-result-fragment {:decision :unresolved :error :unrecognized-input}
                                          opts)))
          (let [result (work/pickup! (keyword project) input (client/keychain-token))]
            (sse-response
             (sse-fragment
              (views/pickup-result-fragment result opts))))))

      ;; POST /workstreams/intent/:project — hand a typed description to the work
      ;; plane, which enqueues it at the project's :start-intent leg or refuses
      ;; naming that leg. Patches #intent-result either way: the refusal is the
      ;; ordinary answer until a project declares the leg, not an error.
      (and (= 3 (count segs)) (= "workstreams" (first segs)) (= "intent" (nth segs 1)))
      (let [project (nth segs 2)
            input   (str (:intent (parse-json-body body)))
            ;; Ask what blocks THIS leg BEFORE writing anything. An open breaker is not
            ;; a "queued, but…" here the way it is for a pickup: the daemon deletes the
            ;; envelope on drain and only then skips it, and the description is the only
            ;; copy of the request — clearing the breaker cannot bring it back. So refuse
            ;; and say so, leaving the typed text in the textarea to resubmit. A down or
            ;; halted daemon never drains, so those stay queued-with-a-note.
            blocked (read-intent-blocker project)
            result  (if (and (= :breaker blocked) (not (str/blank? input)))
                      {:decision :breaker :trigger work/start-intent-trigger}
                      (work/start-intent! (keyword project) input))]
        (sse-response
         (sse-fragment
          (views/intent-result-fragment result {:project project :blocked-by blocked}))))

      ;; POST /ops/fleet/:project/:session/down — down ONE idle session from the
      ;; fleet card. Session-scoped on purpose: work/bring-down! is the workstream
      ;; fan-out and would take this session's siblings with it. The session name
      ;; is URL-encoded because branch-prefixed names carry slashes.
      (and (= 5 (count segs)) (= "ops" (first segs)) (= "fleet" (nth segs 1))
           (= "down" (nth segs 4)))
      (let [project (nth segs 2)
            session (java.net.URLDecoder/decode (nth segs 3) "UTF-8")]
        (dev/stop-session! project session)
        (ops-fragment-response))

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
            (work/file-findings! (keyword project) ws-id
                                 {:items items :staging-ref (not-empty (:staging b))})
            (catch Exception e
              (println "[nido ui] work/file-findings! failed:" (ex-message e)))))
        (ws-pane-fragment-response project ws-id))

      ;; POST /operations/:project/:ws-id/:analysis-seq/:observation — decide one
      ;; proposal. ?entry= is the ledger position the row was rendered from and
      ;; ?verdict= is which button; both ride the query string the same way every
      ;; other position-carrying click does.
      (and (= 5 (count segs)) (= "operations" (first segs)))
      (let [project (nth segs 1)
            ws-id   (nth segs 2)
            vs      (view-state/parse req)
            verdict (keyword (or (get-in req [:params "verdict"])
                                 (second (re-find #"verdict=([a-z]+)" (str (:query-string req))))))
            result  (if (contains? #{:approved :declined} verdict)
                      (work/decide-proposal!
                       project ws-id
                       {:analysis-seq (parse-long (nth segs 3))
                        :observation  (parse-long (nth segs 4))
                        :verdict      verdict
                        :at-seq       (:entry vs)})
                      {:decision :stale :latest nil})]
        (sse-response (sse-fragment
                       (views/proposal-result-fragment result (all-proposals)))))

      ;; POST /ops/... — ambient ops levers (halt/resume, breaker clear, fire).
      ;; Every lever responds with the refreshed ops fragment + rail status.
      (= "ops" (first segs))
      (do
        (cond
          (= ["ops" "halt"] segs)   (control/halt! {:source :user :note "from dashboard"})
          (= ["ops" "resume"] segs) (control/resume!)
          ;; /ops/breakers/:project/:trigger/clear
          (and (= 5 (count segs)) (= "breakers" (second segs)) (= "clear" (nth segs 4)))
          (control/clear-breaker! (keyword (nth segs 2)) (keyword (nth segs 3)))
          ;; /ops/fire/:project/:trigger — placeholder values ride the JSON signal body
          (and (= 4 (count segs)) (= "fire" (second segs)))
          (let [project (keyword (nth segs 2))
                tname   (keyword (nth segs 3))
                trig    (->> (control/triggers-for project)
                             (filter #(= tname (:name %))) first)
                ks      (control/trigger-placeholders (or (:payload trig) "{}"))
                body*   (parse-json-body body)
                payload (into {} (for [k ks]
                                   [k (str (get body* (keyword (views/fire-signal tname k)) ""))]))]
            (control/fire! project tname payload))
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

      ;; GET /operations — nido's own improvement backlog, one row per proposal
      ["operations"]
      (html-response 200 (views/operations-page
                          (rail-ctx :operations (derive-screen (view-state/parse req)))
                          (all-proposals)))

      ;; GET /_fragment/operations — SSE proposal-list refresh
      ["_fragment" "operations"]
      (operations-fragment-response)

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
        (ws-pane-fragment-response (nth segments 2) (nth segments 3) (view-state/parse req))

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

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  handle-request [{:keys [request-method] :as req}]
  (case request-method
    :post (handle-post req)
    (handle-get req)))

;; ---------------------------------------------------------------------------
;; Slow-request log

(def ^:private slow-request-ms
  "Wall-clock threshold, in milliseconds, above which a request is logged.

   NIDO_SLOW_REQUEST_MS overrides it; 0 logs every request, which is how you
   turn this into a plain access log for a session. The default sits above a
   healthy render (~300ms on an unloaded machine) and below the latency a person
   actually notices, so a quiet log is a real statement that the dashboard was
   fast rather than an artefact of a threshold nothing could cross."
  (or (some-> (System/getenv "NIDO_SLOW_REQUEST_MS") parse-long) 750))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  wrap-slow-request-log
  "Wrap a handler so requests slower than `slow-request-ms` print one line to the
   daemon log: when, what, how long, and the load average at the time.

   Timed around the handler alone, so the number is the server's own think-time
   and excludes the queueing and transfer a browser also sees. That is the point
   of measuring here rather than in the browser: a page that is slow in Chrome
   and fast in this log is slow for a reason outside this process, and the two
   measurements together say which.

   A failing handler is timed and logged too, then rethrown — an exception after
   a two-second stall is exactly the event worth having a line for, and swallowing
   it here would change what the server returns."
  [handler]
  (fn [req]
    (let [start (System/nanoTime)
          log!  (fn [_outcome]
                  ;; Nothing this does may reach the caller: a logger that can
                  ;; throw turns an observed request into a failed one, which is
                  ;; the one thing instrumentation must never do.
                  (try
                    (let [ms (/ (- (System/nanoTime) start) 1e6)]
                      (when (>= ms slow-request-ms)
                        (println (format "[nido] slow %-4s %6.0fms load=%s %s%s"
                                         (name (:request-method req :get))
                                         ms
                                         (if-let [l (proc/load-average)] (format "%.2f" l) "?")
                                         (:uri req)
                                         (if-let [q (:query-string req)] (str "?" q) "")))))
                    (catch Throwable _ nil)))]
      (try
        (let [resp (handler req)]
          (log! :ok)
          resp)
        (catch Throwable t
          (log! :error)
          (throw t))))))

;; ---------------------------------------------------------------------------
;; Server lifecycle

(defonce ^:private server-atom (atom nil))

(defn ^{:malli/schema [:=> [:cat :map] :any]}
  start!
  "Start the dashboard server."
  [{:keys [port] :or {port 8800}}]
  (when-let [old @server-atom]
    (old))
  (let [stop-fn (http/run-server (wrap-slow-request-log handle-request) {:port port})]
    (reset! server-atom stop-fn)
    (println (str "[nido] Dashboard running at http://localhost:" port))
    stop-fn))

(defn ^{:malli/schema [:=> [:cat] :any]}
  stop! []
  (when-let [stop-fn @server-atom]
    (stop-fn)
    (reset! server-atom nil)
    (println "[nido] Dashboard stopped")))
