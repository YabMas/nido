(ns nido.session.links
  "Per-session relevant-link tracker. Stores notion tickets, GitHub PRs,
   slack threads, and other context-worth URLs that the agent should
   carry across sessions.

   Persistent at:
     ~/.nido/state/<instance-id>/links.edn   {:links [{:type ... :url ... :title ...} ...]}

   Same lifetime as session.edn next to it: survives `down`/`up`,
   removed by `destroy`. Links live in the instance state-dir (not the
   session-home) because the session-home is regenerated on every `up`."
  (:require
   [babashka.fs :as fs]
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
  "Path to the per-instance links file."
  [instance-id]
  (str (fs/path (state/instance-state-dir instance-id) "links.edn")))

(defn ^{:malli/schema [:=> [:cat :InstanceId] [:vector :map]]}
  read-links
  "Read the links vector for an instance. Empty vector if the file is
   missing or malformed."
  [instance-id]
  (let [data (io/read-edn (links-path instance-id))]
    (if (and (map? data) (vector? (:links data)))
      (:links data)
      [])))

(defn ^{:malli/schema [:=> [:cat :InstanceId [:vector :map]] :any]}
  write-links!
  "Persist the links vector for an instance."
  [instance-id links]
  (io/write-edn! (links-path instance-id) {:links (vec links)}))

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
  (let [link    (coerce-link link-input)
        current (read-links instance-id)
        idx     (->> current
                     (map-indexed vector)
                     (some (fn [[i l]] (when (= (:url l) (:url link)) i))))
        next    (if idx
                  (assoc current idx link)
                  (conj current link))]
    (write-links! instance-id next)
    next))

(defn ^{:malli/schema [:=> [:cat :InstanceId :string] [:vector :map]]}
  remove-by-url!
  "Drop the first link whose :url equals `url`. Returns the updated
   vector. Throws if no match."
  [instance-id url]
  (let [target  (normalize-url url)
        current (read-links instance-id)
        next    (vec (remove #(= (:url %) target) current))]
    (when (= (count current) (count next))
      (throw (ex-info (str "No link with :url " (pr-str target))
                      {:url target
                       :existing (mapv :url current)})))
    (write-links! instance-id next)
    next))

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
