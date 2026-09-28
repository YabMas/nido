(ns nido.coordinator.view.review-queue
  "The review-queue grooming as the dashboard shows it: where the latest grooming
   stands, and each of its plan items with what was decided and what was done.

   Pure over what the records hold — the plan run, its plan, decisions and
   results, and the apply run if one was fired. No reads here; the work plane
   gathers them.")

(defn- run-state [run] (some-> run :state))

(defn ^{:malli/schema [:=> [:cat :map] :keyword]}
  stage
  "Where one grooming stands, as one of:

   `:none`      never run
   `:planning`  the plan run is queued or working
   `:failed`    the plan run ended without a plan
   `:deciding`  a plan is ready and nobody has asked for it to be applied
   `:unfired`   the decisions are frozen and the apply trigger was never queued — its
                fire failed, and Apply fires it again with the same decisions
   `:applying`  the apply trigger was queued and its run has not finished
   `:applied`   the apply run finished

   An apply queued whose run has not been created yet reads `:applying` — the
   envelope is on the queue, and offering Apply again would queue another."
  [{:keys [plan-run plan decisions apply-run]}]
  (let [ps (run-state plan-run)]
    (cond
      (nil? plan-run)                                   :none
      (:applying decisions)
      (cond (#{:done :failed :halted} (run-state apply-run)) :applied
            (or apply-run (get-in decisions [:applying :fired-at])) :applying
            :else                                         :unfired)
      plan                                              :deciding
      (#{:queued :preprocessing :running} ps)           :planning
      :else                                             :failed)))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  overview
  "One grooming for the dashboard: `:stage` (see `stage`), the plan run's id and
   when it started, the plan's tickets in rank order each carrying its own items,
   the items with no ticket (schema changes, clearing ranks) under `:general`, the
   flags, and `:counts` of decisions and outcomes.

   Every item carries `:decision` (nil when undecided) and `:result` (nil until
   the apply run reaches it)."
  [{:keys [project plan-run plan decisions results apply-run] :as in}]
  (let [ds       (:decisions decisions {})
        item     (fn [it] (assoc it
                                 :decision (get ds (:n it))
                                 :result   (get results (:n it))))
        items    (mapv item (:items plan))
        by-br    (group-by :br items)
        tickets  (->> (:tickets plan)
                      (sort-by #(or (:rank %) Long/MAX_VALUE))
                      (mapv #(assoc % :items (vec (get by-br (:br %))))))
        outcomes (frequencies (keep (comp :outcome :result) items))]
    {:project    project
     :stage      (stage in)
     :run-id     (:id plan-run)
     :started-at (some-> plan-run :state-history first :at)
     :apply-run  (:id apply-run)
     :tickets    tickets
     :general    (vec (get by-br nil))
     :flags      (vec (:flags plan))
     :counts     {:items      (count items)
                  :approved   (count (filter #(= :approved (:decision %)) items))
                  :skipped    (count (filter #(= :skipped (:decision %)) items))
                  :undecided  (count (remove :decision items))
                  :applied    (get outcomes :applied 0)
                  :failed     (get outcomes :failed 0)}}))
