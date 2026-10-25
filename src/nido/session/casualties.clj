(ns nido.session.casualties
  "Which sessions were up when the host went down, kept past the housekeeping that clears them.

   A registry entry registered before the current host boot died with the host, whatever else is
   true of it: nothing registered then can still be running. An entry with no readable
   registration time is taken as registered before it, as the prune already takes it as old. That
   test is the one thing this module decides, and it is what the prune never drew — to the prune a
   boot reads as a crash. The record is kept apart from the per-instance state root, so the reclaim
   that deletes an instance's state cannot delete the account of it.

   What it stores is only what cannot be recomputed: the entries as they were, and which of them a
   revive has settled. Whether a casualty is still unsettled is derived each time it is asked — its
   worktree still exists, no revive settled it, and no registry entry registered after its boot
   names its instance."
  (:require
   [babashka.fs :as fs]
   [nido.platform.io :as io]
   [nido.platform.process :as process]
   [nido.session.state :as sstate]))

(defn- instant [ts]
  (try (java.time.Instant/parse ts) (catch Exception _ nil)))

(defn ^{:malli/schema [:=> [:cat :map [:maybe :map]] :boolean]}
  pre-boot?
  "Whether a registry entry was registered before the host boot. False when the boot is unknown —
   nothing can be classified against it."
  [entry boot]
  (boolean
   (when-let [began (some-> (:began-at boot) instant)]
     (let [at (some-> (:created-at entry) str instant)]
       (or (nil? at) (.isBefore ^java.time.Instant at began))))))

(defn- record-path [boot-id]
  (str (fs/path (sstate/casualties-dir) (str boot-id ".edn"))))

(defn- parse-casualty
  "One held entry as a Casualty, or nil when it does not carry what a Casualty needs."
  [boot-id {:keys [instance-id entry]}]
  (let [{:keys [project-name project-dir created-at]} entry]
    (when (and (string? instance-id) (string? project-name) (string? project-dir))
      {:boot-id       boot-id
       :instance-id   instance-id
       :project       project-name
       :worktree      project-dir
       :registered-at (when (string? created-at) created-at)})))

(defn ^{:malli/schema [:=> [:cat [:sequential :map]] :Holding]}
  record!
  "Hold every pre-boot entry among those given in the current boot's record. Idempotent per
   instance and boot: an instance already held keeps the entry it was first held with. An entry
   that names no instance, project or worktree is no session anything could bring back, and is
   not held."
  [entries]
  (if-let [boot (process/host-boot)]
    (let [held (->> entries
                    (filter #(pre-boot? % boot))
                    (keep (fn [e] (let [h {:instance-id (:instance-id e) :entry e}]
                                    (when (parse-casualty (:id boot) h) h))))
                    (reduce (fn [acc h] (if (some #(= (:instance-id h) (:instance-id %)) acc) acc (conj acc h)))
                            []))]
      (try
        (when (seq held)
          (io/update-edn! (record-path (:id boot))
                          (fn [rec]
                            (let [have (into #{} (map :instance-id) (:casualties rec))]
                              {:boot       boot
                               :casualties (into (vec (:casualties rec))
                                                 (remove #(have (:instance-id %)))
                                                 held)
                               :settled    (or (:settled rec) #{})}))))
        {:outcome :held :held (vec (keep #(parse-casualty (:id boot) %) held))}
        (catch Exception e
          {:outcome :failed :error (or (ex-message e) (str e))})))
    {:outcome :no-boot}))

(defn- registered-since?
  "Whether the registry names `instance-id` in an entry registered after `began-at` — the session
   came back up after the boot it died at."
  [registry instance-id began-at]
  (let [began (instant began-at)]
    (boolean
     (some (fn [entry]
             (and (= instance-id (:instance-id entry))
                  (when-let [at (some-> (:created-at entry) str instant)]
                    (and began (.isAfter ^java.time.Instant at began)))))
           (vals registry)))))

(defn- read-record
  "A record file as {:boot :casualties [Casualty …] :settled #{…} :whole? bool}, or nil when the
   file does not parse. An entry that does not parse to a Casualty is dropped and the record
   marked not whole — reported, never silently taken as empty, and never costing the entries
   beside it."
  [path]
  (try
    (let [{:keys [boot casualties settled] :as rec} (io/read-edn path)
          parsed (mapv #(parse-casualty (:id boot) %) casualties)]
      (when (and (map? rec) (string? (:id boot)) (string? (:began-at boot)))
        {:boot boot :casualties (filterv some? parsed) :settled (set settled)
         :whole? (every? some? parsed)}))
    (catch Exception _ nil)))

(defn ^{:malli/schema [:=> [:cat] :Unsettled]}
  unsettled
  "Every casualty of every boot that is still owed a revive: its worktree still exists, no revive
   settled it, and no registry entry registered after its boot names its instance. A session that
   died in two boots is owed once, at the newer."
  []
  (let [dir      (sstate/casualties-dir)
        files    (if (fs/directory? dir) (sort (map str (fs/glob dir "*.edn"))) [])
        registry (sstate/read-registry)
        read     (map (juxt identity read-record) files)
        owed     (for [[_ {:keys [boot casualties settled]}] read
                       :when boot
                       c casualties
                       :when (and (not (contains? settled (:instance-id c)))
                                  (fs/directory? (:worktree c))
                                  (not (registered-since? registry (:instance-id c) (:began-at boot))))]
                   [(:began-at boot) c])]
    {:casualties (->> owed
                      (sort-by first)
                      (reduce (fn [m [_ c]] (assoc m (:instance-id c) c)) {})
                      vals
                      (sort-by :instance-id)
                      vec)
     :unreadable (vec (keep (fn [[f rec]] (when-not (:whole? rec) f)) read))}))

(defn ^{:malli/schema [:=> [:cat [:sequential :Casualty]] :boolean]}
  settle!
  "Mark casualties settled — brought up or reopened by a revive, or dismissed by a person —
   answering whether every mark was written."
  [casualties]
  (try
    (doseq [[boot-id cs] (group-by :boot-id casualties)]
      (io/update-edn! (record-path boot-id)
                      (fn [rec]
                        (when-not rec
                          (throw (ex-info "no casualty record for that boot" {:boot-id boot-id})))
                        (update rec :settled (fnil into #{}) (map :instance-id cs)))))
    true
    (catch Exception _ false)))
