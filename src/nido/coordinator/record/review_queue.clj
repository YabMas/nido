(ns nido.coordinator.record.review-queue
  "The review-queue grooming records: the plan a `/review-queue` run proposes, the
   decision a person made on each of its items, and what the apply run did with
   each one.

   All three live in the PLAN run's artifacts dir, keyed by the plan's own item
   numbers. The plan run writes the plan; the dashboard writes the decisions; the
   apply run — a separate Run, fired once a person is done deciding — writes the
   results there too, so one directory is the whole history of one grooming.

   The item number is the identity. It is what a person approved, and the apply
   run is held to it: it carries out exactly the items whose decision reads
   `:approved`, and nothing it drafts afresh."
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nido.coordinator.record.clock :as clock]
   [nido.coordinator.record.runs :as runs]
   [nido.coordinator.record.state :as cstate]
   [nido.platform.io :as io]))

(def plan-trigger
  "The trigger whose runs draft a plan. Its runs are found by id: a run id carries
   its trigger's name (`<date>-<project>-review-queue-<suffix>`)."
  :review-queue)

(def apply-trigger
  "The trigger whose runs carry out a plan's approved items."
  :review-queue-apply)

(defn- artifact [run-id file]
  (str (fs/path (cstate/run-artifacts-dir run-id) file)))

(defn ^{:malli/schema [:=> [:cat :RunId] :Path]}
  plan-path [run-id] (artifact run-id "review-queue-plan.edn"))

(defn ^{:malli/schema [:=> [:cat :RunId] :Path]}
  decisions-path [run-id] (artifact run-id "review-queue-decisions.edn"))

(defn ^{:malli/schema [:=> [:cat :RunId] :Path]}
  results-path [run-id] (artifact run-id "review-queue-results.edn"))

(defn- read-quietly
  "The EDN at `path`, or nil when it is absent or unreadable. A plan an agent is
   still writing, or wrote badly, must read as no plan rather than take the page
   down."
  [path]
  (try (io/read-edn path) (catch Exception _ nil)))

(defn ^{:malli/schema [:=> [:cat :RunId] [:maybe :map]]}
  read-plan
  "The plan run `run-id` drafted: `{:tickets [..] :items [..] :flags [..]}`, or nil
   while it has drafted none. See the /review-queue skill for its shape."
  [run-id]
  (let [p (read-quietly (plan-path run-id))]
    (when (and (map? p) (vector? (:items p))) p)))

(defn ^{:malli/schema [:=> [:cat :RunId] :map]}
  read-decisions
  "`{:decisions {n :approved|:skipped} :applying {:at iso :fired-at iso}}` — :applying
   present once someone asked for the plan to be carried out, after which no decision may
   change; :fired-at once its apply trigger was queued."
  [run-id]
  (let [d (read-quietly (decisions-path run-id))]
    (if (map? d) d {:decisions {}})))

(defn ^{:malli/schema [:=> [:cat :RunId] :map]}
  read-results
  "`{n {:outcome :applied|:skipped|:failed :note str}}`, written by the apply run
   item by item — so a partial map is an apply still in progress, or one that
   stopped."
  [run-id]
  (let [r (read-quietly (results-path run-id))]
    (if (map? r) r {})))

(defn ^{:malli/schema [:=> [:cat :RunId :int [:enum :approved :skipped]] :map]}
  decide!
  "Record `verdict` on plan item `n`. Returns `{:decision :recorded}`, or refuses
   without writing: `:no-item` when the plan has no such item, `:locked` once the
   plan is being applied — a decision changed after that would not be the one
   carried out."
  [run-id n verdict]
  (let [path (decisions-path run-id)]
    (io/with-file-lock
      (io/lock-path-for path)
      (fn []
        (let [d (read-decisions run-id)]
          (cond
            (not (some #(= n (:n %)) (:items (read-plan run-id)))) {:decision :no-item}
            (:applying d)                                         {:decision :locked}
            :else (do (io/write-edn! path (assoc-in d [:decisions n] verdict))
                      {:decision :recorded})))))))

(defn ^{:malli/schema [:=> [:cat :RunId [:=> [:cat] :any]] :map]}
  apply!
  "Freeze plan `run-id`'s decisions and call `fire` to queue its apply, all under the plan's
   lock. Returns `{:decision :begun :approved [n ..]}`, or refuses without firing:
   `:undecided` (with `:items`) while any item holds no decision, `:nothing-approved`, or
   `:already-applying` once a fire is recorded.

   The fire is recorded only after `fire` returns. A `fire` that throws leaves the plan frozen
   and unfired, and the next call fires it again with the same decisions — so a plan is never
   stranded. A stop between `fire` returning and the record being written leaves a plan that
   looks unfired; the next call fires again, and `fire` must answer that without queueing a
   second envelope — which is why the caller keys it by the plan."
  [run-id fire]
  (let [path (decisions-path run-id)]
    (io/with-file-lock
      (io/lock-path-for path)
      (fn []
        (let [d         (read-decisions run-id)
              ds        (:decisions d)
              items     (map :n (:items (read-plan run-id)))
              undecided (vec (remove #(contains? ds %) items))
              approved  (->> ds (filter (comp #{:approved} val)) (map key) sort vec)]
          (cond
            (get-in d [:applying :fired-at]) {:decision :already-applying}
            (seq undecided)                  {:decision :undecided :items undecided}
            (empty? approved)                {:decision :nothing-approved}
            :else
            (let [frozen (if (:applying d) d (assoc d :applying {:at (clock/now-iso)}))]
              (io/write-edn! path frozen)
              (fire)
              (io/write-edn! path (assoc-in frozen [:applying :fired-at] (clock/now-iso)))
              {:decision :begun :approved approved})))))))

(defn- runs-of
  "The runs of `trigger` for `project`, newest first, found by id rather than by
   reading every run record: an id is `<date>-<project>-<trigger>-<suffix>` and the
   suffix is eight characters, so the trigger is what sits between them."
  [project trigger]
  (let [prefix-free (str "-" (name project) "-" (name trigger) "-")]
    (->> (runs/list-run-ids)
         (filter #(let [i (str/index-of % prefix-free)]
                    (and i (= (+ i (count prefix-free) 8) (count %)))))
         (keep #(try (runs/read-run %) (catch Exception _ nil)))
         (sort-by (fn [r] (some-> r :state-history first :at)) #(compare %2 %1))
         vec)))

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:maybe :Run]]}
  latest-plan-run
  "The newest plan run for `project`, or nil when it has never been groomed."
  [project]
  (first (runs-of project plan-trigger)))

(defn ^{:malli/schema [:=> [:cat :ProjectName :RunId] [:maybe :Run]]}
  apply-run-for
  "The newest apply run fired for plan run `plan-run-id`, or nil."
  [project plan-run-id]
  (->> (runs-of project apply-trigger)
       (filter #(= plan-run-id (get-in % [:event-payload :plan-run])))
       first))
