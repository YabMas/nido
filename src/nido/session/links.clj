(ns nido.session.links
  "Per-session relevant-link tracker. Stores notion tickets, GitHub PRs,
   slack threads, and other context-worth URLs that the agent should
   carry across sessions.

   Persistent at:
     ~/.nido/links/<instance-id>.edn   {:links [{:type ... :url ... :title ...} ...]}

   Survives `down`/`up`; removed only by `destroy`.

   In its OWN root rather than beside the session's machine state, and the
   distinction is the whole point: `~/.nido/state/<instance-id>/` holds what can
   be rebuilt — PGDATA, logs, session.edn — and reclaim exists to delete it once
   no registry entry claims it. `down` deregisters, so an hour later the sweep
   took the links with the cluster. The session-home is no better: it is
   regenerated on every `up`.

   Links are the one thing here that cannot be rebuilt — a person decided this
   ticket and that PR belong to this work — so they live somewhere nothing
   sweeps. `destroy` deletes this file explicitly (see lifecycle/destroy!),
   because it no longer falls out of dropping the state-dir."
  (:require
   [babashka.fs :as fs]
   [nido.platform.core :as core]
   [nido.platform.io :as io]
   [nido.session.state :as state]))

(def known-types
  "Recognised link types. The :other bucket is the escape hatch for URLs
   that don't fit a known category."
  #{:notion-ticket :pr :gh-issue :slack-thread :other})

(def display-order
  "Order types render in for the briefing and the TUI info panel.
   Anything not in this list falls under :other."
  [:notion-ticket :pr :gh-issue :slack-thread :other])

(def display-labels
  {:notion-ticket "notion ticket"
   :pr            "PR"
   :gh-issue      "GitHub issue"
   :slack-thread  "slack thread"
   :other         "other"})

(defn ^{:malli/schema [:=> [:cat :InstanceId] :Path]}
  links-path
  "Path to a session's links file, under the durable links root."
  [instance-id]
  (str (fs/path (core/nido-home) "links" (str instance-id ".edn"))))

(defn- legacy-links-path
  "Where links lived before they moved out of the reclaimable state-dir.

   MIGRATION SHIM. Delete it, and `migrate-legacy!`, once no
   `~/.nido/state/*/links.edn` remain on any machine — after which it can never
   fire again."
  [instance-id]
  (str (fs/path (state/instance-state-dir instance-id) "links.edn")))

(defn- migrate-legacy!
  "Move a session's links out of the old state-dir location, once. No-op when
   there is nothing to move or the new file already exists.

   Called before every read and every update rather than at some one-off
   upgrade point: the old file is deleted by a sweep that runs on its own
   schedule, so the only reliable moment to rescue it is the next time anyone
   asks about these links at all."
  [instance-id]
  (let [new-path (links-path instance-id)
        old-path (legacy-links-path instance-id)]
    (when (and (fs/exists? old-path) (not (fs/exists? new-path)))
      (fs/create-dirs (fs/parent new-path))
      (fs/move old-path new-path)
      new-path)))

(defn- links-of
  "The links vector inside a raw links.edn value; [] when the file was missing
   or holds something else. Separate from `read-links` because the locked
   update sees the parsed value rather than the path, and both have to agree on
   what a malformed file means."
  [data]
  (if (and (map? data) (vector? (:links data)))
    (:links data)
    []))

(defn ^{:malli/schema [:=> [:cat :InstanceId] [:vector :map]]}
  read-links
  "Read the links vector for an instance. Empty vector if the file is
   missing or malformed."
  [instance-id]
  (migrate-legacy! instance-id)
  (links-of (io/read-edn (links-path instance-id))))

(defn- normalize-type [t]
  (cond
    (keyword? t) t
    (string? t)  (keyword (if (.startsWith ^String t ":")
                            (subs t 1)
                            t))
    (symbol? t)  (keyword (name t))
    :else (throw (ex-info (str "Invalid link :type " (pr-str t))
                          {:value t :valid known-types}))))

(defn- normalize-url
  "Stringify whatever the CLI parser handed us — bb's edn-based arg
   parser turns URLs like https://notion.so/foo into symbols when they
   happen to read as one. Empty/nil rejected."
  [url]
  (let [s (cond
            (string? url) url
            (or (symbol? url) (keyword? url)) (str url)
            (nil? url) nil
            :else (str url))]
    (when-not (and s (seq s))
      (throw (ex-info "Link :url must be a non-empty string"
                      {:url url})))
    s))

(defn- coerce-link
  "Validate and normalise a link map. Required: :type, :url. Optional :title."
  [{:keys [type url title]}]
  (let [t (normalize-type type)]
    (when-not (contains? known-types t)
      (throw (ex-info (str "Unknown link :type " (pr-str t))
                      {:value t :valid known-types
                       :hint  "Use one of :notion-ticket :pr :gh-issue :slack-thread :other"})))
    (cond-> {:type t :url (normalize-url url)}
      (and title (seq (str title))) (assoc :title (str title)))))

(defn ^{:malli/schema [:=> [:cat :InstanceId :map] [:vector :map]]}
  add!
  "Append a link, deduping on :url. If a link with the same :url already
   exists, replace it (in place — order preserved). Returns the updated
   links vector."
  [instance-id link-input]
  ;; Coerced OUTSIDE the lock: a bad :type or :url is the caller's error, and
  ;; rejecting it before anyone queues keeps the critical section to a read and
  ;; a write.
  (let [link (coerce-link link-input)]
    (migrate-legacy! instance-id)
    (links-of
     (io/update-edn!
      (links-path instance-id)
      (fn [data]
        (let [current (links-of data)
              idx     (->> current
                           (map-indexed vector)
                           (some (fn [[i l]] (when (= (:url l) (:url link)) i))))]
          {:links (if idx
                    (assoc current idx link)
                    (conj current link))}))))))

(defn ^{:malli/schema [:=> [:cat :InstanceId :string] [:vector :map]]}
  remove-by-url!
  "Drop the first link whose :url equals `url`. Returns the updated
   vector. Throws if no match."
  [instance-id url]
  (let [target (normalize-url url)]
    (migrate-legacy! instance-id)
    (links-of
     (io/update-edn!
      (links-path instance-id)
      (fn [data]
        (let [current (links-of data)
              next    (vec (remove #(= (:url %) target) current))]
          ;; Thrown INSIDE the update, so the no-match case writes nothing at
          ;; all rather than rewriting the file with what it already held.
          (when (= (count current) (count next))
            (throw (ex-info (str "No link with :url " (pr-str target))
                            {:url target
                             :existing (mapv :url current)})))
          {:links next}))))))

(defn ^{:malli/schema [:=> [:cat :InstanceId] :any]}
  delete-links!
  "Drop a session's links. Called by `destroy!` — the only verb that ends a
   session's claim on them. Removes the legacy copy too, so a destroy cannot
   leave one behind for a later same-named session to inherit."
  [instance-id]
  (fs/delete-if-exists (links-path instance-id))
  (fs/delete-if-exists (legacy-links-path instance-id)))

(defn ^{:malli/schema [:=> [:cat [:vector :map]] :any]}
  group-by-type
  "Group `links` by :type, preserving display-order. Returns a seq of
   [type [link ...]] tuples for non-empty groups only."
  [links]
  (let [by-type (group-by :type links)
        known   (for [t display-order
                      :let [ls (get by-type t)]
                      :when (seq ls)]
                  [t (vec ls)])
        unknown (->> by-type
                     (remove (fn [[t _]] (contains? known-types t)))
                     (sort-by (comp str key))
                     (mapv (fn [[t ls]] [t (vec ls)])))]
    (concat known unknown)))
