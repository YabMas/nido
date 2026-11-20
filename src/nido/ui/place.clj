(ns nido.ui.place
  "Where the person is in the dashboard, as one value every chat reads.

   HIDES which surface declares which subject — `table`, one entry per surface a ViewState can
   name, saying what a page on it shows that a person could mean by 'this' — how the location of
   the page a request came from is read, and how a place is worded for an agent.

   A place is one of two alternatives. Known: `{:known? true :page {:surface :title :path}
   :subject …}`, the subject nil when the page has none open (or declares none). Unknown:
   `{:known? false}`, when the request states no location that reads as a path — never a known page
   with no subject. It is derived, never stored: read from the page's location and the work plane
   at the moment it is asked for, so it is never older than the message it rides on.

   A send's location is its Referer, which the browser states on every same-origin fetch; a page's
   is its own path and query. Either is untrusted shape, read here into a path and query and then
   by `view-state/parse` alone; a location that does not read is the unknown place, not an error."
  (:require [clojure.string :as str]
            [nido.coordinator.work :as work]
            [nido.ui.view-state :as view-state]))

(defn- workstream-subject
  "The selected workstream as a subject: what it is, where it stands, the gate open on it and
   the ledger entry open in its pane. Every part, its project and id included, is the workstream
   the work plane resolved — a page id in the location reads as the workstream that took the
   page. nil when there is none nido can read: an id it does not know, or a ticket no workstream
   covers yet."
  [{:keys [selection entry]}]
  (when-let [{:keys [project ws-id]} selection]
    (when-let [ws (work/workstream project ws-id entry)]
      (when-not (:bare? ws)
        (let [open (when-let [s (:selected-seq ws)] (some #(when (= s (:seq %)) %) (:entries ws)))]
          {:kind    :workstream
           :project (:project ws)
           :ws-id   (:ws-id ws)
           :title   (:label ws)
           :stage   (:stage ws)
           :gate    (when-let [g (:gate ws)]
                      {:question (get-in g [:ask :question])
                       :actions  (vec (keep :label (:actions g)))})
           :entry   (when open {:seq (:seq open) :kind (:kind open) :title (:title open)})})))))

(defn- operations-title [path]
  (case (second (remove str/blank? (str/split path #"/")))
    "improvements" "Operations · Improvements"
    "recovery"     "Operations · Recovery"
    "review-queue" "Operations · Review queue"
    "Operations"))

(defn- structure-title [path]
  (case (second (remove str/blank? (str/split path #"/")))
    "drains" "Structure · Drains"
    "Structure"))

(def ^:private table
  "Every surface a ViewState can name, and what a page on it says: its title (a string, or a
   function of the path for a surface whose pages share it) and its subject (a function of the
   view-state, or nil for a page that declares none). A surface missing here is not a page a chat
   can be on; one whose :subject is nil names the page alone, by declaration."
  {:needs         {:title "Needs you"     :subject workstream-subject}
   :workstreams   {:title "Workstreams"   :subject workstream-subject}
   :domain        {:title "Domain"        :subject nil}
   :functionality {:title "Functionality" :subject nil}
   :structure     {:title structure-title :subject nil}
   :presentation  {:title "Presentation"  :subject nil}
   :operations    {:title operations-title :subject nil}
   :development   {:title "Development"   :subject nil}
   :other         {:title "A page the dashboard does not name" :subject nil}})

(defn- referer-location
  "The Referer as {:uri :query-string}, or nil when there is none or it does not read as a URL
   with a path."
  [req]
  (when-let [ref (not-empty (get-in req [:headers "referer"]))]
    (try
      (let [u (java.net.URI. ref)]
        (when-let [path (not-empty (.getRawPath u))]
          {:uri path :query-string (.getRawQuery u)}))
      (catch Exception _ nil))))

(defn- place-at
  "The place at location `loc` ({:uri :query-string}, or nil for none) as read with the request
   headers `headers` — their cookies, so the focus reads as the page's did — and the work plane
   now. The one reading both `place` and `page-place` are."
  [loc headers]
  (if-let [{:keys [uri]} loc]
    (let [vs (view-state/parse (assoc loc :headers headers))
          {:keys [title subject]} (get table (:surface vs) (:other table))]
      {:known?  true
       :page    {:surface (:surface vs)
                 :title   (if (fn? title) (title uri) title)
                 :path    (str uri (some->> (:query-string loc) (str "?")))}
       :subject (when subject (subject vs))})
    {:known? false}))

(defn ^{:malli/schema [:=> [:cat :map] :Place]}
  place
  "The place of the page the request `req` was sent from — a send, whose page is the location it
   states as its Referer. Unknown when it states none that reads."
  [req]
  (place-at (referer-location req) (:headers req)))

(defn ^{:malli/schema [:=> [:cat :map] :Place]}
  page-place
  "The place of the page the request `req` draws: its own path and query. Not its Referer, which
   is the page it was reached from — a chat chosen by that would be the previous page's."
  [{:keys [uri query-string headers]}]
  (place-at (when (seq uri) {:uri uri :query-string query-string}) headers))

(defn ^{:malli/schema [:=> [:cat :Place] [:sequential :map]]}
  references
  "A place as the references a message carries: the page, then each part of its subject the page
   has — the workstream, its gate, its open entry — each a label and its detail. An unknown place
   is one reference saying so, worded apart from a known page with nothing open."
  [{:keys [known? page subject]}]
  (if-not known?
    [{:label  "place · unknown"
      :detail (str "Sent from nido's dashboard, but the request did not say which page it was on: "
                   "no page, workstream or gate is known for this message.")}]
    (let [{:keys [project ws-id title stage gate entry]} subject]
      (cond-> [{:label  (str "page · " (:title page))
                :detail (str "Sent from nido's dashboard, on " (:title page) " — " (:path page)
                             (when-not subject " — nothing is open on it."))}]
        subject
        (conj {:label  (str "workstream · " project "/" ws-id)
               :detail (str "The workstream open on this page: " title " — " project " " ws-id
                            ", at stage " (some-> stage name) ".")})
        gate
        (conj {:label  "gate · open"
               :detail (str "The gate open on it asks: " (:question gate)
                            (when (seq (:actions gate))
                              (str "\nIts answers: " (str/join ", " (:actions gate)))))})
        entry
        (conj {:label  (str "entry · " (:seq entry))
               :detail (str "Open in its pane: ledger entry " (:seq entry) ", " (some-> (:kind entry) name)
                            " — " (:title entry))})))))
