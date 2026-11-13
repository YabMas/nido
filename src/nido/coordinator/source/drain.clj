(ns nido.coordinator.source.drain
  "The :drain source: polls a project's open drains, takes their census readings, and fires at
   most one thing per drain per poll.

   THE COUNTER'S OUTPUT IS TRUSTED HERE AND NOWHERE ELSE. A recipe's counter is a shell command
   run in the project's main checkout; what it prints is parsed once, by `read-counter`, into
   {:remaining n :groups [{:group s :n k} …]}, and a reading that is not exactly that is a
   failure naming its cause — :counter-failed (it exited non-zero or could not run), :unreadable
   (it printed no EDN) or :invalid (EDN of another shape). A failure appends no :census.

   A reading is appended only when it differs from the drain's newest one under the same recipe,
   so a drain nobody has touched does not grow a census entry every poll; a new recipe always
   gets its first reading.

   It PLANS NOTHING. The coordinator plans; this source schedules what was planned: the oldest open
   plan whose rested-on slices stand as its plan asks (started, for :on-top; landed, for
   :after-landing), while fewer of the drain's slices are in flight than the source's cap. Or,
   when the census reading failed, that failure. Each fire is a broadcast like every source's,
   {:adapter :drain :id … :project :drain :what :start-slice|:census-failed …}, consumed by
   lane-drain; repeating a fire starts nothing twice, because a consumed plan starts no slice."
  (:require
   [babashka.process :as process]
   [clojure.edn :as edn]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.drain :as drain]
   [nido.coordinator.record.workstream :as ws]
   [nido.coordinator.source.registry :as sources]
   [nido.coordinator.source.state :as sst]
   [nido.platform.project :as project]))

(def adapter
  "The external-ref adapter a drain's fires carry."
  :drain)

(def ^:private default-cap
  "How many of a drain's slices may be in flight — started and not landed — when the source config
   names no :max-in-flight."
  2)

(def ^:private counter-timeout-ms
  "How long a recipe's counter may run before its reading is a :counter-failed."
  (* 10 60 1000))

;; ── reading a counter ───────────────────────────────────────────────────────

(def ^:private reading-shape?
  (every-pred map?
              #(nat-int? (:remaining %))
              #(sequential? (:groups % []))
              #(every? (fn [g] (and (map? g) (string? (:group g)) (nat-int? (:n g)))) (:groups % []))))

(defn ^{:malli/schema [:=> [:cat :any] :any]}
  read-counter
  "A counter's printed `out` as a census reading {:remaining n :groups [...]}, or {:failed cause}
   with cause :unreadable (not EDN) or :invalid (EDN that is not a reading). `:groups` may be left
   out and reads as []."
  [out]
  (let [v (try (edn/read-string (str out)) (catch Exception _ ::unreadable))]
    (cond
      (= ::unreadable v)   {:failed :unreadable}
      (reading-shape? v)   {:remaining (:remaining v) :groups (vec (:groups v []))}
      :else                {:failed :invalid})))

(defn- main-checkout
  "The directory the project's main checkout lives in, or nil."
  [project]
  (get-in (project/list-projects) [(name project) :directory]))

(defn ^{:malli/schema [:=> [:cat :any :any] :any]}
  run-counter
  "Run `command` in `dir` under bash and read what it printed: a reading, or {:failed cause} with
   :counter-failed when it could not run, exited non-zero or ran past the timeout."
  [dir command]
  (try
    (let [proc (process/process ["bash" "-lc" command] {:dir dir :out :string :err :string})
          done (deref proc counter-timeout-ms ::timeout)]
      (cond
        (= ::timeout done)         (do (process/destroy-tree proc) {:failed :counter-failed :detail "timed out"})
        (not (zero? (:exit done))) {:failed :counter-failed :detail (str "exit " (:exit done))}
        :else                      (read-counter (:out done))))
    (catch Exception e
      {:failed :counter-failed :detail (ex-message e)})))

(defn ^{:malli/schema [:function [:=> [:cat :ProjectName :WorkstreamId] :any] [:=> [:cat :ProjectName :WorkstreamId :any] :any]]}
  take-census!
  "Run drain `ws-id`'s current recipe's counter against the project's main checkout and append the
   reading, citing the recipe — unless it reads the same as the newest under that recipe. `run`
   (default `run-counter`) is the injection seam a test substitutes. Answers {:census seq} for an
   appended reading, {:unchanged seq} for a repeated one, {:failed cause} for a failed one, and
   {:failed :no-recipe} for a drain with none."
  ([project ws-id] (take-census! project ws-id run-counter))
  ([project ws-id run]
   (let [w (ws/read-ws project ws-id)
         v (drain/version w)]
     (if-not v
       {:failed :no-recipe}
       (let [reading (run (main-checkout project) (get-in (drain/recipe w) [:recipe :counter :command]))
             newest  (drain/newest-census w)]
         (cond
           (:failed reading) reading
           (and newest (= (select-keys newest [:remaining :groups]) reading)) {:unchanged (:seq newest)}
           :else {:census (drain/record-census! project ws-id (assoc reading :recipe v))}))))))

;; ── what a poll fires ───────────────────────────────────────────────────────

(defn ^{:malli/schema [:=> [:cat :ProjectName :any] :any]}
  landed?
  "Whether slice workstream `slice-ws-id` has landed: its ledger records a :merged, or it was
   closed done. What the source's scheduling, lane-drain's closing and the drains page read as
   landed."
  [project slice-ws-id]
  (let [w (ws/read-ws project slice-ws-id)]
    (boolean (or (seq (ws/entries-of project slice-ws-id :merged))
                 (= :done (:outcome (ws/closure w)))))))

(defn- in-flight
  "The drain's slices started and not landed, nor closed some other way."
  [project w]
  (filterv #(let [sw (ws/read-ws project (:ws-id %))]
              (and sw (not (landed? project (:ws-id %))) (nil? (ws/closure sw))))
           (drain/slices w)))

(defn- rests-satisfied?
  "Whether every plan `plan` rests on stands as its :start asks: started, for :on-top; started and
   landed, for :after-landing."
  [project by-seq plan]
  (every? (fn [r]
            (when-let [slice (:slice (by-seq r))]
              (or (= :on-top (:start plan)) (landed? project slice))))
          (:rests-on plan)))

(defn- next-start
  "The open plan of drain `w` a slice should start from now, or nil: the oldest whose rested-on
   plans stand as it asks, while fewer than `cap` of the drain's slices are in flight and the drain
   could start one at all (`drain/startable`)."
  [project w cap]
  (let [by-seq (into {} (map (juxt :seq identity)) (drain/plans w))]
    (when (< (count (in-flight project w)) cap)
      (first (filter #(and (nil? (drain/startable w (:seq %)))
                           (rests-satisfied? project by-seq %))
                     (drain/open-plans w))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName] :any]}
  open-drains
  "The ids of `project`'s drains that are not closed."
  [project]
  (filterv #(let [w (ws/read-ws project %)] (and w (drain/drain? w) (nil? (ws/closure w))))
           (ws/list-ids project)))

(defn- fire-for
  "What one poll fires for drain `ws-id` given its census result `taken`: the census failure, when
   the reading failed; else the start of `next-start`'s plan; else nil."
  [project ws-id taken cap]
  (let [w (ws/read-ws project ws-id)]
    (cond
      (and (:failed taken) (not= :no-recipe (:failed taken)))
      {:adapter adapter :id (str ws-id "/census-failed/" (or (some-> (drain/newest-census w) :seq) 0))
       :project (name project) :drain ws-id :what :census-failed
       :cause (:failed taken) :detail (str (:detail taken))}

      :else
      (when-let [p (next-start project w cap)]
        {:adapter adapter :id (str ws-id "/start/" (:seq p))
         :project (name project) :drain ws-id :what :start-slice :plan (:seq p)}))))

(defn ^{:malli/schema [:function [:=> [:cat :any :any] :any] [:=> [:cat :any :any :any] :any]]}
  poll-once!
  "One poll over the project's open drains: take each one's census, fire at most one thing for
   it, and answer the state to persist. A drain whose poll throws is logged and skipped."
  ([source-config emit-fn] (poll-once! source-config emit-fn run-counter))
  ([source-config emit-fn run]
   (let [project (:project source-config)
         cap     (or (:max-in-flight source-config) default-cap)
         results (vec (for [d (open-drains project)]
                        (try
                          (let [taken (take-census! project d run)
                                fire  (fire-for project d taken cap)]
                            (when fire (emit-fn fire))
                            {:drain d :census taken :fired (:id fire)})
                          (catch Throwable t
                            (binding [*out* *err*]
                              (println (str "nido drain source: polling " d " failed — " (ex-message t))))
                            {:drain d :error (ex-message t)}))))]
     {:type             :drain
      :source-config    source-config
      :last-polled-at   (clock/now-iso)
      :last-poll-result :ok
      :drains           results})))

(defn- instance-key
  "The key a drain source instance keeps its running poll and its state file under: its type and
   its config-hash, since `config-hash` leaves :type out and another source type with the same
   project and poll would otherwise share the drain source's state file."
  [source-config]
  (str "drain-" (sources/config-hash source-config)))

(defonce ^:private !running
  ;; instance-key → the future of that source's poll in progress. Kept by key, not per instance,
  ;; so an instance stopped and started again under the same config never overlaps its own poll.
  (atom {}))

(defn- start-instance!
  "A drain source instance. Its `:poll!` never blocks the daemon's tick on a counter: it starts
   the poll on a future of its own and returns, and while that poll is still running a `:poll!`
   starts nothing — so a slow counter delays only its own drains' next reading. `run` (default
   `run-counter`) is the seam a test substitutes."
  ([source-config emit-fn] (start-instance! source-config emit-fn run-counter))
  ([source-config emit-fn run]
   (let [hash (instance-key source-config)
         emit (fn [p] (emit-fn {:type :drain :source-config source-config :payload p}))]
     {:poll! (fn []
               (when-not (some-> (get @!running hash) realized? not)
                 (swap! !running assoc hash
                        (future
                          (try
                            (sst/write-state! hash (poll-once! source-config emit run))
                            (catch Throwable t
                              (binding [*out* *err*]
                                (println (str "nido drain source: poll of " (name (:project source-config))
                                              " failed — " (ex-message t)))))))))
               nil)
      :stop! (fn [] nil)})))

(defn ^{:malli/schema [:=> [:cat] :any]}
  register! []
  (sources/register-source!
   {:type   :drain
    :schema [:map
             [:type          [:= :drain]]
             [:project       keyword?]
             [:poll          {:optional true} string?]
             [:max-in-flight {:optional true} pos-int?]]
    :events [:map [:adapter [:= adapter]] [:id string?] [:what [:enum :start-slice :census-failed]]]
    :start! start-instance!}))
