(ns nido.ui.server
  "HTTP server for the nido dashboard."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nido.notion.client :as client]
            [nido.coordinator.view.host :as host-view]
            [nido.platform.core :as core]
            [nido.platform.host :as host]
            [nido.platform.process :as proc]
            [nido.platform.project :as project]
            [nido.session.fleet :as fleet]
            [nido.ui.dev :as dev]
            [nido.coordinator.control :as control]
            [nido.libraries.ui.wire :as wire]
            [nido.ui.catalog.chat :as catalog-chat]
            [nido.ui.side-chat :as side-chat]
            [nido.ui.presentation :as presentation]
            [nido.ui.structure :as structure]
            [nido.libraries.ui.block :as block]
            [nido.ui.components.ask :as ask]
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

(defn- page-response
  "A full page under the chrome: 200, remembering `focus` so the next bare URL
   opens on the same project."
  [focus body]
  (assoc-in (html-response 200 body) [:headers "Set-Cookie"]
            (str view-state/focus-cookie "=" (java.net.URLEncoder/encode (str focus) "UTF-8")
                 "; Path=/; Max-Age=31536000; SameSite=Lax")))

(defn- sse-fragment
  "One event patching `html-fragment` into the page by its ids."
  [html-fragment]
  (wire/patch-elements-event html-fragment))

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

(defn- refresh-for!
  "Bring the held rows up to what `view-state`'s render shows (`work/refresh-list!`,
   `work/refresh-selected!`): a Workstreams view with a selection refreshes the selected
   workstream alone and draws the list beside it as held; the Operations pages refresh
   nothing, since the rail is all they draw from the rows; every other render — the gate
   inbox, the overview without a selection, their polls — makes a list refresh."
  [{:keys [surface selection]}]
  (cond
    (= :operations surface)                     nil
    (and selection (= :workstreams surface))    (work/refresh-selected! (:project selection) (:ws-id selection))
    :else                                       (work/refresh-list!)))

(defn ^{:malli/schema [:=> [:cat :any [:? :map]] :Screen]}
  derive-screen
  "Impure wiring: gather what only IO can produce (grouped rows, gates, in-flight
   resolve keys), hand off to the pure work/screen, then attach the selection
   detail. Selection detail is attached HERE (not in work) because it needs the
   dev layer, which work must not depend on. Every /workstreams + / render route
   runs through this one function, so no two render sites disagree.

   `{:board? false}` is a screen with no board: no refresh and no bands, only the gates, which
   the core answers (`work/read-gates`), and the release candidates among rows already held
   (`work/held-candidates`) — what Needs you and every page's rail badge render, so neither waits
   on held rows being built."
  ([view-state] (derive-screen view-state {}))
  ([view-state {:keys [board?] :or {board? true}}]
  (when board? (refresh-for! view-state))
  (let [{:keys [gates errors unavailable]} (work/read-gates)
        groups (if board? (work/all-grouped) [])
        screen (work/screen view-state
                            {:groups           groups
                             :gates            gates
                             :errors           errors
                             :unavailable      unavailable
                             :pending          (dev/pending-resolve-keys)
                             :winddown-pending (dev/pending-winddown-keys)})
        sel (:selection view-state)
        ws-errors (dev/failed-ws-errors)]
    (-> (cond-> screen (not board?) (assoc :candidates (work/held-candidates)))
        ;; The workstreams whose rows carry the side chat's mark: a chat pinned there has news.
        (assoc :chats-unseen (side-chat/unseen))
        (assoc :selection
               (when sel
                 ;; The two pages that draw a pane beside their list; every other render with a
                 ;; selection (the list polls among them) only needs to know which row is open.
                 (let [ws (when (#{:needs :workstreams} (:surface view-state))
                            (work/workstream (:project sel) (:ws-id sel) (:entry view-state)))]
                   (cond-> {:project (:project sel) :ws-id (:ws-id sel)}
                     ws (assoc :ws (cond-> (assoc ws :open-rounds (:rounds view-state)
                                                     :open-stage  (:stage view-state)
                                                     :history?    (:history? view-state)
                                                     :pending?    (contains? (dev/pending-resolve-keys)
                                                                             (str (:project sel) "/" (:ws-id sel))))
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
        (update :errors
                (fn [errors]
                  (mapv (fn [e]
                          (let [err (get ws-errors (str (:project e) "/" (:ws-id e)))]
                            (cond-> e
                              (and err (not (:pending? e))) (assoc :error-msg err))))
                        errors)))
        (update :groups
                (fn [groups]
                  (mapv (fn [{:keys [project] :as g}]
                          (update-in g [:grouped :winding-down]
                                     (fn [rows]
                                       (mapv (fn [row]
                                               (let [facts (work/machine-facts project (:sessions row))
                                                     ;; One row per runtime: a workstream's
                                                     ;; sessions share one, on one app port.
                                                     total (->> (vals facts)
                                                                (filter :live?)
                                                                (map (juxt :app-port identity))
                                                                (into {})
                                                                vals
                                                                (mapcat (juxt :repl-rss :pg-rss))
                                                                (remove nil?)
                                                                (reduce + 0))
                                                     err   (get ws-errors
                                                                (str project "/" (:ws-id row)))]
                                                 (cond-> row
                                                   (pos? total) (assoc :rss-str (proc/human-bytes total))
                                                   (and err (not (:pending? row))) (assoc :error-msg err))))
                                             rows))))
                        groups)))))))

(defn- project-names [] (vec (sort (map (comp name key) (project/list-projects)))))

(defn- view-state
  "The request's view-state with its focus settled on a project nido knows: the
   one the request names (view-state/parse), or the first project when it names
   none or one since removed. Every view has a focused project."
  [req]
  (let [vs    (view-state/parse req)
        known (project-names)]
    (assoc vs :scope (if (some #{(:scope vs)} known) (:scope vs) (first known)))))

(defn- needs-screen
  "The screen Needs you renders: every project's gates, whatever is focused — read from the
   core, with no board behind them."
  [vs]
  (derive-screen (assoc vs :scope "all") {:board? false}))

(defn- rail-ctx
  "Chrome context: the slice or page that is `active`, the focused project, the
   Needs-you badge — the screen's needs-count, which counts every project's
   gates, so the badge and the Needs-you list always agree — and the chat's side
   panel, open or closed as the request's page last left it."
  [req active focus screen]
  {:side-chat   (side-chat/panel req (side-chat/open? req))
   :active      active
   :scope       focus
   :needs-count (:needs-count screen)
   :error-count (:error-count screen)
   :candidates-count (count (:candidates screen))
   :needs-unread (:unavailable screen)
   :daemon      (read-rail-daemon)
   :projects    (project-names)})

(defn- needs-fragment-response [screen]
  (sse-response
   (sse-fragment
    (str (views/needs-fragment screen)
         (views/rail-status-fragment {:needs-count (:needs-count screen)
                                      :error-count (:error-count screen)
                                      :candidates-count (count (:candidates screen))
                                      :needs-unread (:unavailable screen)
                                      :daemon (read-rail-daemon)})))))

(defn- workstreams-fragment-response [screen]
  (sse-response
   (sse-fragment
    (str (views/workstreams-fragment screen)
         (views/rail-status-fragment {:needs-count (:needs-count screen)
                                      :error-count (:error-count screen)
                                      :candidates-count (count (:candidates screen))
                                      :needs-unread (:unavailable screen)
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

(defn- feed-position
  "The recovery feed position a request names in ?feed=, or nil for the newest."
  [req]
  (not-empty (or (get-in req [:params "feed"])
                 (some-> (re-find #"(?:^|&)feed=([^&]*)" (str (:query-string req))) second
                         (java.net.URLDecoder/decode "UTF-8")))))

(defn- session-recovery
  "The recovery overview at a feed position, or nil when it could not be read — a
   broken recovery reading must not take the improvement backlog down with it."
  [feed-from]
  (try (work/session-recovery {:feed-from feed-from})
       (catch Throwable t
         (binding [*out* *err*]
           (println (str "[nido] reading session recovery failed: " (ex-message t))))
         nil)))

(defn- improvement-holds
  "What holds each improvement sweep, or nil when it could not be read — like
   recovery, a broken reading must not take the backlog down with it."
  []
  (try (work/improvement-holds)
       (catch Throwable t
         (binding [*out* *err*]
           (println (str "[nido] reading improvement holds failed: " (ex-message t))))
         nil)))

(defn- review-queues
  "Every project's latest review-queue grooming, or nil when it could not be read
   — like recovery, a broken reading must not take the rest of Operations down."
  []
  (try (work/review-queues)
       (catch Throwable t
         (binding [*out* *err*]
           (println (str "[nido] reading the review queue failed: " (ex-message t))))
         nil)))

(defonce ^:private review-queue-requested
  ;; project → the plan run that was newest when a run was asked for (nil when
  ;; there was none). A request stands until a different run is newest: the
  ;; envelope sits on the queue for a few seconds before the daemon makes a run
  ;; of it, and offering the button again in that window queues a second one.
  (atom {}))

(defn- requested-projects [queues]
  (let [newest (into {} (map (juxt :project :run-id)) queues)]
    (swap! review-queue-requested
           (fn [m] (into {} (filter (fn [[p before]] (= before (get newest p)))) m)))
    (set (keys @review-queue-requested))))

(defn- review-queue-fragment-response []
  (let [qs (review-queues)]
    (sse-response (sse-fragment (views/review-queue-fragment qs (requested-projects qs))))))

;; ── the host ─────────────────────────────────────────────────────────────────

(def ^:private host-max-age-ms
  "How old a host reading may be when a request is answered from it. Under the home's 5 s poll, so
   each poll sees a fresh reading, and every viewer and the page within it share one."
  4000)

(def ^:private host-rate-window-ms
  "How old the previous reading may be and still measure the coordinator's CPU share against. A
   page opened after an hour unwatched would otherwise show the hour's average as now."
  30000)

(defonce ^:private host-readings
  ;; {:read-ns <System/nanoTime of the last attempt> :current <HostReading or nil> :previous <HostReading>}
  ;; — the last attempt and the last two reads, kept only because a CPU share is a rate. The attempt
  ;; is timed apart from the reading, so a read that failed is served as unreadable until the
  ;; interval passes rather than retried on every request; it is timed on the monotonic clock, so a
  ;; wall clock set back cannot keep a reading fresh. Read on the request path, never on the
  ;; coordinator's tick.
  (atom nil))

(defn- host-model
  "The host card's model, from a read attempted no more than `host-max-age-ms` before. A read that
   throws costs the card its figures, never the page."
  []
  (let [pid (try (control/coordinator-pid) (catch Throwable _ nil))
        {:keys [current previous]}
        (locking host-readings
          (let [{:keys [read-ns current] :as held} @host-readings
                now-ns (System/nanoTime)
                age-ms (when read-ns (quot (- now-ns read-ns) 1000000))]
            (if (and age-ms (< age-ms host-max-age-ms))
              held
              (let [r (try (host/read-host {:path (core/nido-home) :cpu-pids (if pid #{pid} #{})})
                           (catch Throwable _ nil))]
                (reset! host-readings
                        {:read-ns  now-ns
                         :current  r
                         :previous (when (and current (< age-ms host-rate-window-ms))
                                     current)})))))]
    (try (host-view/host-model current previous pid)
         (catch Throwable _ {:unreadable :host}))))

(defn- operations-home-cards []
  ;; Notices are no workstream's question, so they are here rather than in the needs-you list. An
  ;; unreadable record costs its notice, never the page.
  (views/operations-home-cards (all-proposals) (session-recovery nil) (improvement-holds)
                               (review-queues)
                               {:casualties (try (:casualties (work/shown-revivals)) (catch Throwable _ []))}
                               (host-model)))

(defn- operations-home-fragment-response []
  (sse-response (sse-fragment (views/operations-home-fragment (operations-home-cards)))))

(defn- improvements-fragment-response []
  (sse-response (sse-fragment (str (views/sweep-fragment (improvement-holds))
                                   (views/operations-fragment (all-proposals))))))

(defn- recovery-fragment-response [feed-from]
  (sse-response (sse-fragment (views/recovery-fragment (session-recovery feed-from)))))

(defn- ops-fragment-response
  "The ops panel, with the top bar's live bits: the Needs-you badge counts every
   project's gates, as it does on every page."
  []
  (sse-response
   (sse-fragment
    (str (views/ops-panel-fragment (ops-context))
         (let [{:keys [gates errors unavailable]} (work/read-gates)]
           (views/rail-status-fragment
            {:needs-count  (count gates)
             :error-count  (count errors)
             :needs-unread unavailable
             :daemon       (read-rail-daemon)}))))))

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
   (work/refresh-selected! project ws-id)
   (let [ws  (work/workstream project ws-id entry)
         k   (str project "/" ws-id)
         err (get (dev/failed-ws-errors) k)]
     (sse-response
      (sse-fragment
       (views/workstream-pane (cond-> ws
                                rounds   (assoc :open-rounds rounds)
                                stage    (assoc :open-stage stage)
                                history? (assoc :history? true)
                                err      (assoc :error-msg err)
                                (contains? (dev/pending-resolve-keys) k) (assoc :pending? true))
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
    ;; Nothing was written — not to Notion, not to the ticket, not to the ledger —
    ;; so the sentence has to say so: a reader who left it there would believe a
    ;; ticket had been routed.
    :triage-stale           (str "Nothing was applied — the ledger moved on since this "
                                 "page rendered, so this would have accepted a verdict "
                                 "you were not looking at. Re-read the gate.")
    ;; A gate that will not open writes nothing; the reason is the ledger's own.
    :phase-refused          (str "The gate did not open — " because)
    :core-refused           (str "The core refused it — " because)
    :between-phases         (str "Not restored — this workstream is between phases, not "
                                 "dismissed. Assert its gate to open the next one.")
    :nothing-to-land        (str "Nothing to land — this workstream no longer owes a landing. "
                                 "It may have merged already.")
    :land-stale             (str "Not granted — the ledger moved on since this page rendered. "
                                 "Re-read the gate.")
    :not-landable           (str "Not granted — only an activated workstream at a published PR "
                                 "has a seat to land it. Land it from its session, or activate it.")
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

(defn- card-answers
  "The question card's `answers` signal — {:q0 \"A\" :q1 \"\" …} — as one letter or nil per
   question, in question order. A gap in the indices is a question with no answer sent, so it
   becomes nil rather than closing up and shifting every later answer onto the wrong question."
  [answers]
  (let [idx (into {} (keep (fn [[k v]]
                             (when-let [[_ n] (re-matches #"q(\d+)" (name k))]
                               [(parse-long n) (when-not (str/blank? (str v)) (str v))])))
                  answers)]
    (if (empty? idx) [] (mapv idx (range (inc (apply max (keys idx))))))))

(defn- card-picks
  "A question card's Send answers post — its `answers` letters, its `notes`, and its `words` on a
   card whose submit records words — as one pick per question in question order: a map holding a
   :letter, a :note, both, or neither for one left open. A note is the trimmed words the person
   wrote about the question, its note field's or else its words field's; blank words are no note.
   Indexed like `card-answers`, so a question with nothing sent stays in its place."
  [{:keys [answers notes words]}]
  (let [letters (card-answers answers)
        noted   (card-answers notes)
        said    (card-answers words)
        note-of (fn [i] (some #(some-> (get % i) str/trim not-empty) [noted said]))]
    (mapv (fn [i] (let [l (get letters i) n (note-of i)]
                    (cond-> {} l (assoc :letter l) n (assoc :note n))))
          (range (max (count letters) (count noted) (count said))))))

(defn- gate-decoding
  "Which of the ask blocks' declared actions a gate click on `action-id` was sent by, as the
   block and the action's key — what its body is decoded against."
  [action-id]
  (cond
    (= :reply action-id)                             [ask/bar :reply]
    (= :assert-gate action-id)                       [ask/bar :evidence]
    (#{:answer-questions :apply} action-id)          [ask/card :submit]
    :else                                            [ask/card :click]))

(defn- click-payload
  "What a gate click carries besides its action id, from the fields `decoded` — the body read
   through the decoder of the block action that sent it — and the request's ?entry=: the reply's
   text for :reply, the evidence's for :assert-gate, and for every button that answers a ledger
   question the :seq of the report it was rendered from, the same reading position every other
   surface rides, so the resolver can refuse an answer whose question the ledger has since moved
   past. nil for every other action: those resolve entirely nido-side.

   Which buttons those are is asked of the descriptor, not listed here. Every
   position-carrying button is rendered by the same branch of `action-button`
   (the one that appends ?entry= when a descriptor has a :seq), so a second list
   of ids kept in this namespace is a list that can disagree with it, and a
   button rendered with its position but read back without one resolves as
   stale every time."
  [req action-id decoded]
  (cond
    (#{:reply :assert-gate} action-id)       (:text decoded)
    ;; A card's questions are sent together, each as its letter, its note, both or neither.
    (= :answer-questions action-id)
    {:seq     (:entry (view-state/parse req))
     :answers (card-picks decoded)}
    ;; An Accept that submits questions also carries the card's choices, one `q<i>` per
    ;; question, as the answers in order, a blank for a question left open. What each letter
    ;; means is the resolver's to read off the ledger; this only keeps the order. An Accept
    ;; from a card with no questions to submit carries none, and resolves on its position.
    (and (= :apply action-id) (:answers decoded))
    {:seq     (:entry (view-state/parse req))
     :answers (card-answers (:answers decoded))}
    (work/position-carrying-action? action-id) (:entry (view-state/parse req))))

(defn- decode-failure-msg
  "Why a gate click's body was refused, in a sentence the pane can show."
  [{:keys [failure key]}]
  (case failure
    :unparseable "the click carried no readable page state; reload the page and try again"
    :missing     (str "the click did not carry its " (name key) "; reload the page and try again")
    :invalid     (str "the click's " (name key) " was not what this action takes")))

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
      ;; POST /workstreams/:project/:ws-id/gate/:action — resolve a gate action from
      ;; the overview/detail pane (the stage-appropriate action bar below the reader).
      ;; :reply carries the reply textarea's input.
      ;; The confirmation patches #ws-pane, the list's 5s poll drops the resolved row.
      (and (= 5 (count segs)) (= "workstreams" (first segs)) (= "gate" (nth segs 3)))
      (let [project   (nth segs 1)
            ws-id     (nth segs 2)
            action-id (keyword (nth segs 4))
            [blk k]   (gate-decoding action-id)
            ;; Read once: the body is a stream, and a second slurp of it is empty.
            decoded   (block/decode-action blk k (some-> body slurp) ws-id)]
        (sse-response
         (sse-fragment
          (if-let [fields (:ok decoded)]
            (gate-action-response-fragment
             (gate-resolve! project ws-id action-id (click-payload req action-id fields))
             action-id project ws-id "ws-pane")
            (views/gate-action-skip-fragment (decode-failure-msg decoded) project ws-id "ws-pane")))))

      ;; POST /workstreams/:project/:ws-id/sessions/:session/dev/:action
      (and (= 7 (count segs)) (= "workstreams" (first segs))
           (= "sessions" (nth segs 3)) (= "dev" (nth segs 5)))
      (let [project (nth segs 1) ws-id (nth segs 2)
            session (java.net.URLDecoder/decode (nth segs 4) "UTF-8")
            action  (nth segs 6)]
        (dev/dev-action! project ws-id session action)
        (ws-pane-fragment-response project ws-id))

      ;; POST /workstreams/pickup/:project — resolve a pasted Notion ref, start its ticket,
      ;; and patch #pickup-result with the continuing/new report.
      (and (= 3 (count segs)) (= "workstreams" (first segs)) (= "pickup" (nth segs 1)))
      (let [project (nth segs 2)
            input   (str/trim (str (:pickup (parse-json-body body))))
            opts    {:project project}]
        (if (str/blank? input)
          (sse-response
           (sse-fragment
            (views/pickup-result-fragment {:decision :unresolved :error :unrecognized-input}
                                          opts)))
          (let [result (work/pickup! (keyword project) input (client/keychain-token))]
            (sse-response
             (sse-fragment
              (views/pickup-result-fragment result opts))))))

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

      ;; POST /workstreams/:project/:ws-id/pause|resume — the person's pause, from the pane.
      (and (= 4 (count segs)) (= "workstreams" (first segs)) (#{"pause" "resume"} (nth segs 3)))
      (let [project (nth segs 1) ws-id (nth segs 2)]
        (if (= "pause" (nth segs 3))
          (work/pause! project ws-id nil)
          (work/resume! project ws-id))
        (ws-pane-fragment-response project ws-id))

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
        (workstreams-fragment-response (derive-screen (view-state req))))

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

      ;; POST /operations/review-queue/:project/run — fire a grooming. The plan run
      ;; reads the queue and drafts; nothing it does writes to Notion.
      (and (= 4 (count segs)) (= ["operations" "review-queue"] (subvec segs 0 2))
           (= "run" (nth segs 3)))
      (let [project (keyword (nth segs 2))
            qs      (review-queues)]
        (when-not (contains? (requested-projects qs) project)
          (swap! review-queue-requested assoc project
                 (:run-id (first (filter #(= project (:project %)) qs))))
          (control/fire! project :review-queue
                         {:id (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")
                                       (java.time.LocalDateTime/now))}))
        (review-queue-fragment-response))

      ;; POST /operations/review-queue/:project/:run-id/items/:n?verdict= — decide one write
      (and (= 6 (count segs)) (= ["operations" "review-queue"] (subvec segs 0 2))
           (= "items" (nth segs 4)))
      (let [verdict (keyword (or (get-in req [:params "verdict"])
                                 (second (re-find #"verdict=([a-z]+)" (str (:query-string req))))))]
        (work/decide-review-item! (nth segs 3) (or (parse-long (nth segs 5)) -1) verdict)
        (review-queue-fragment-response))

      ;; POST /operations/review-queue/:project/:run-id/approve-all — approve every undecided write
      (and (= 5 (count segs)) (= ["operations" "review-queue"] (subvec segs 0 2))
           (= "approve-all" (nth segs 4)))
      (let [run-id (nth segs 3)
            q      (first (filter #(= run-id (:run-id %)) (review-queues)))]
        (doseq [it (concat (mapcat :items (:tickets q)) (:general q))
                :when (nil? (:decision it))]
          (work/decide-review-item! run-id (:n it) :approved))
        (review-queue-fragment-response))

      ;; POST /operations/review-queue/:project/:run-id/apply — freeze the decisions and
      ;; fire the apply run, which carries out exactly the approved writes.
      (and (= 5 (count segs)) (= ["operations" "review-queue"] (subvec segs 0 2))
           (= "apply" (nth segs 4)))
      (let [project (keyword (nth segs 2))]
        (try (work/begin-review-apply! (nth segs 3) #(control/fire! project %1 %2 %3))
             (catch Throwable t
               ;; The plan stays frozen and unfired; the page offers Apply again.
               (binding [*out* *err*]
                 (println (str "[nido] firing the review-queue apply failed: " (ex-message t))))))
        (review-queue-fragment-response))

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

      ;; POST /drains/start — the person starts a drain: the exit criterion and what established
      ;; the pattern ride the JSON signal body; the project is the focused one, or the one the
      ;; person picked when nothing is focused.
      ;; Every drains POST carries ?scope= and answers the list under that scope.
      (= ["drains" "start"] segs)
      (let [scope (:scope (view-state/parse req))
            b     (parse-json-body body)
            {:keys [refused]} (work/start-drain!
                               (or scope (not-empty (str (:drain_project b))))
                               {:exit                  (:drain_exit b)
                                :established-by-ws     (:drain_established_ws b)
                                :established-by-commit (:drain_established_commit b)})]
        (sse-response (sse-fragment (views/drains-fragment (work/drains scope) refused scope))))

      ;; POST /drains/:project/:ws-id/:act[/:arg] — the person's acts on a drain: approve a
      ;; recipe proposal, land a slice, close the drain. Answers the refreshed list under the
      ;; scope the page was requested from, naming a refusal when the records did not support it.
      (and (#{4 5} (count segs)) (= "drains" (first segs)))
      (let [[_ project ws-id act arg] segs
            scope (:scope (view-state/parse req))
            {:keys [refused]} (work/drain-act! project ws-id (keyword act) arg)]
        (sse-response (sse-fragment (views/drains-fragment (work/drains scope) refused scope))))

      ;; POST /ops/... — ambient ops levers (halt/resume, breaker clear, fire).
      ;; Every lever responds with the refreshed ops fragment + rail status.
      (= "ops" (first segs))
      (do
        (cond
          (= ["ops" "halt"] segs)   (control/halt! {:source :user :note "from dashboard"})
          (= ["ops" "resume"] segs) (control/resume!)
          ;; /ops/breakers/:project/:trigger/clear — an automatic trip only
          (and (= 5 (count segs)) (= "breakers" (second segs)) (= "clear" (nth segs 4)))
          (control/clear-breaker! (keyword (nth segs 2)) (keyword (nth segs 3)))
          ;; /ops/breakers/:project/:trigger/resume — a person's pause
          (and (= 5 (count segs)) (= "breakers" (second segs)) (= "resume" (nth segs 4)))
          (control/resume-trigger! (keyword (nth segs 2)) (keyword (nth segs 3)))
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
        (ops-fragment-response))

      ;; POST /_catalog/nowhere/… — where a catalog scene's buttons post. Every control a
      ;; block renders is present and shaped exactly as it is on a real page; this is what
      ;; makes clicking one change nothing. Nothing is read and nothing is written.
      (and (= "_catalog" (first segs)) (= "nowhere" (second segs)))
      {:status 204 :headers {}}

      :else
      (html-response 404 (views/not-found-page)))))

(defn- ws-redirect
  "302 to the workstream's own page — where a gate is answered."
  [project ws-id]
  {:status 302 :headers {"Location" (str "/workstreams/" project "/" ws-id)}})

(defn- handle-get [{:keys [uri] :as req}]
  (let [segments (parse-path uri)]
    (case segments
      ;; GET /favicon.{png,ico} — the nido icon (browsers auto-request .ico)
      ["favicon.png"] (png-resource-response "favicon.png")
      ["favicon.ico"] (png-resource-response "favicon.png")
      ;; GET /nido-logo.png — looser circular mark for the rail home button
      ["nido-logo.png"] (png-resource-response "nido-logo.png")

      ;; GET / — Needs you (home): the list, and beside it the pane of the workstream ?sel=
      ;; names, so a person works the list without leaving it.
      []
      (let [vs     (view-state req)
            screen (needs-screen vs)]
        (page-response (:scope vs) (views/needs-page (rail-ctx req :needs (:scope vs) screen) screen)))

      ;; GET /_catalog — the component catalog and its chat. Linked from nothing: a dev surface
      ;; a person reaches by typing it, served on every dashboard rather than behind a flag that
      ;; would make two dashboards differ. Its scene page reads nothing but its query string; the
      ;; chat beside it is nido.ui.catalog.chat's.
      ["_catalog"]
      (html-response 200 (catalog-chat/page (:query-string req)
                                            ;; The catalog's own chat is the panel here; the
                                            ;; dashboard's would be a second one beside it.
                                            #(let [vs (view-state req)]
                                               (dissoc (rail-ctx req :development (:scope vs) (needs-screen vs))
                                                       :side-chat))))

      ;; GET /development — pages for working on the dashboard itself, one tile each
      ["development"]
      (let [vs (view-state req)]
        (page-response (:scope vs) (views/development-page
                                    (rail-ctx req :development (:scope vs) (needs-screen vs)))))

      ;; GET /structure — the focused project's declared design on main, in fukan's explorer
      ["structure"]
      (let [vs (view-state req)]
        (page-response (:scope vs) (views/structure-page
                                    (rail-ctx req :structure (:scope vs) (needs-screen vs))
                                    (structure/ensure! (:scope vs))
                                    structure/default-view)))

      ;; GET /presentation — the focused project's component catalog, as its own app serves it
      ["presentation"]
      (let [vs (view-state req)]
        (page-response (:scope vs) (views/presentation-page
                                    (rail-ctx req :presentation (:scope vs) (needs-screen vs))
                                    (presentation/target (:scope vs)))))

      ;; GET /domain, /functionality — the focused project's other slices.
      ;; Nothing populates them yet; the board (/workstreams) holds the work meanwhile.
      (["domain"] ["functionality"])
      (let [vs (view-state req)]
        (page-response (:scope vs) (views/slice-page
                                    (rail-ctx req (:surface vs) (:scope vs) (needs-screen vs))
                                    (:surface vs))))

      ;; GET /system — dissolved; the rail lost the destination, redirect callers on.
      ["system"]
      {:status 302 :headers {"Location" "/workstreams"}}

      ;; GET /workstreams — overview (selection, if any, comes from ?sel=)
      ["workstreams"]
      (let [vs     (view-state req)
            screen (derive-screen vs)]
        (page-response (:scope vs) (views/workstreams-page (rail-ctx req :workstreams (:scope vs) screen) screen)))

      ;; GET /structure/drains — the Structure slice's drains: each open drain of the focused
      ;; project with its slices beneath it
      ["structure" "drains"]
      (let [vs (view-state req)]
        (page-response (:scope vs) (views/drains-page
                                    (rail-ctx req :structure (:scope vs) (needs-screen vs))
                                    (work/drains (:scope vs)))))

      ;; GET /drains — where the drains page lived before it joined the Structure slice
      ["drains"]
      {:status 302 :headers {"Location" (str "/structure/drains"
                                             (when-let [q (:query-string req)] (str "?" q)))}}

      ["_fragment" "drains"]
      (let [scope (:scope (view-state/parse req))]
        (sse-response (sse-fragment (views/drains-fragment (work/drains scope) nil scope))))

      ;; GET /_fragment/workstreams — SSE workstreams refresh
      ["_fragment" "workstreams"]
      (workstreams-fragment-response (derive-screen (view-state req)))

      ;; GET /_fragment/needs — queue + badge, across every project
      ["_fragment" "needs"]
      (needs-fragment-response (needs-screen (view-state req)))

      ;; GET /operations — the home: one card per operational concern
      ["operations"]
      (html-response 200 (views/operations-home-page
                          (let [vs (view-state req)] (rail-ctx req :operations (:scope vs) (needs-screen vs)))
                          (operations-home-cards)))

      ;; GET /_fragment/operations — SSE card-grid refresh
      ["_fragment" "operations"]
      (operations-home-fragment-response)

      ;; GET /operations/improvements — nido's own improvement backlog, one row per proposal
      ["operations" "improvements"]
      (html-response 200 (views/improvements-page
                          (let [vs (view-state req)] (rail-ctx req :operations (:scope vs) (needs-screen vs)))
                          (all-proposals)
                          (improvement-holds)))

      ["_fragment" "operations" "improvements"]
      (improvements-fragment-response)

      ;; GET /operations/host — the host card's page, down to each process
      ["operations" "host"]
      (html-response 200 (views/host-page
                          (let [vs (view-state req)] (rail-ctx req :operations (:scope vs) (needs-screen vs)))
                          (host-model)))

      ["_fragment" "operations" "host"]
      (sse-response (sse-fragment (views/host-fragment (host-model))))

      ;; GET /operations/recovery — session recovery and its activity feed
      ["operations" "recovery"]
      (html-response 200 (views/recovery-page
                          (let [vs (view-state req)] (rail-ctx req :operations (:scope vs) (needs-screen vs)))
                          (session-recovery (feed-position req))))

      ["_fragment" "operations" "recovery"]
      (recovery-fragment-response (feed-position req))

      ;; GET /operations/review-queue — run the grooming and go through its plan
      ["operations" "review-queue"]
      (let [qs (review-queues)]
        (html-response 200 (views/review-queue-page
                            (let [vs (view-state req)] (rail-ctx req :operations (:scope vs) (needs-screen vs)))
                            qs (requested-projects qs))))

      ["_fragment" "operations" "review-queue"]
      (review-queue-fragment-response)

      ;; GET /_fragment/ops — SSE ops-panel refresh (patches #ops-panel + rail).
      ;; Scope rides ?scope=, parsed the same way every other view-state is —
      ;; so the shell's scoped poll (see views/shell) gets a scoped badge count
      ;; instead of the unscoped total clobbering it every 5s.
      ["_fragment" "ops"]
      (ops-fragment-response)

      ;; Otherwise, dispatch on structure
      (cond
        ;; GET /structure/explorer/:project/… — the explorer the Structure slice frames. Its
        ;; requests are relative, so its root needs the trailing slash to resolve under it.
        (and (<= 3 (count segments)) (= ["structure" "explorer"] (subvec segments 0 2)))
        (let [project (nth segments 2)]
          (if (and (= 3 (count segments)) (not (str/ends-with? uri "/")))
            {:status 302 :headers {"Location" (str uri "/" (some->> (:query-string req) (str "?")))}}
            (structure/forward project (str/join "/" (drop 3 segments)) (:query-string req))))

        ;; GET /_fragment/workstream/:project/:ws-id — SSE pane refresh (patches #ws-pane)
        (and (= 4 (count segments)) (= "_fragment" (first segments)) (= "workstream" (nth segments 1)))
        (ws-pane-fragment-response (nth segments 2) (nth segments 3) (view-state req))

        ;; GET /workstreams/:project/:ws-id — the workstream's own page: its pane alone. The
        ;; workstream it shows is the selection view-state/parse reads off the path, the one
        ;; the chat beside it reads; the focus follows that workstream's project. The screen
        ;; is the board's with this workstream selected, so the pane is drawn from the same
        ;; value.
        (and (= 3 (count segments)) (= "workstreams" (first segments)))
        (let [parsed  (view-state req)
              project (get-in parsed [:selection :project])
              vs      (assoc parsed :scope project)
              screen  (derive-screen vs)]
          (page-response project (views/workstream-page (rail-ctx req :workstreams project screen) screen)))

        ;; GET /gate/:project/:ws-id — legacy deep link → the workstream it names
        (and (= 3 (count segments)) (= "gate" (first segments)))
        (ws-redirect (nth segments 1) (nth segments 2))

        :else
        (html-response 404 (views/not-found-page))))))

(defn- presentation-forward
  "A request under /presentation/app/<project>/, whatever its method, answered by that project's
   catalog app; nil for any other. The bare prefix redirects to its slash, because a catalog's
   requests are relative and resolve under it only then."
  [{:keys [uri request-method query-string body headers]}]
  (let [segs (parse-path uri)]
    (when (and (<= 3 (count segs)) (= ["presentation" "app"] (subvec segs 0 2)))
      (cond
        (and (= 3 (count segs)) (not (str/ends-with? uri "/")))
        {:status 302 :headers {"Location" (str uri "/" (some->> query-string (str "?")))}}

        (not (#{:get :post} request-method))
        {:status 405 :headers {"Allow" "GET, POST"} :body ""}

        :else
        (presentation/forward (java.net.URLDecoder/decode ^String (nth segs 2) "UTF-8")
                              {:method       request-method
                               :path         (str/join "/" (drop 3 segs))
                               :query-string query-string
                               :body         (some-> body slurp)
                               :content-type (get headers "content-type")})))))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  handle-request [{:keys [request-method] :as req}]
  ;; The chats' requests first: their streams are the responses that stay open.
  (or (catalog-chat/handle! req)
      (side-chat/handle! req)
      (presentation-forward req)
      (case request-method
        :post (handle-post req)
        (handle-get req))))

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
  "Start the dashboard server on `:port` of the loopback interface only. From
   another machine it is reached through the port a desk forwards over its ssh
   connection, never over the network directly."
  [{:keys [port] :or {port 8800}}]
  (when-let [old @server-atom]
    (old))
  ;; The dashboard renders the board on every poll, so it holds its rows (`work/hold!`).
  (work/hold!)
  (let [stop-fn (http/run-server (wrap-slow-request-log handle-request) {:ip "127.0.0.1" :port port})]
    (reset! server-atom stop-fn)
    (println (str "[nido] Dashboard running at http://localhost:" port))
    stop-fn))

(defn ^{:malli/schema [:=> [:cat] :any]}
  stop! []
  (when-let [stop-fn @server-atom]
    (stop-fn)
    (reset! server-atom nil)
    (work/release!)
    (println "[nido] Dashboard stopped")))
