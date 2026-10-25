(ns nido.session.revive
  "The order sessions come back in after a host boot, and how many of them fit.

   PURE. Handed the casualties and every fact it decides on — each one's newest conversation,
   whether its worktree is gone or conflicted, the machine's memory — it answers a plan and reads
   and starts nothing. The facts are gathered at the moment of reviving rather than stored,
   because every one of them is stale by the time anyone revives.

   Newest activity first, because the session somebody touched last is the one they want back
   first. Admitted against the fleet's own budget, in rank order, each one that still fits beside
   those admitted before it, because restoring a whole fleet at once is what overran the host the
   first time. A fact
   it cannot read never admits a session: unreadable memory leaves every casualty resumable
   unless :all, and an unanswerable conflict check keeps its services down."
  (:require
   [nido.session.fleet :as fleet]))

(def ^:private default-session-bytes
  "What a session is taken to cost when the live fleet holds too few of its project's sessions to
   say — the ~4 GB a brian JVM settles at, so an unknown project is never treated as free."
  (* 4 1024 1024 1024))

(defn ^{:malli/schema [:=> [:cat :Casualty :MachineFacts] :int]}
  estimated-bytes
  "What one casualty is expected to cost once it is up: its project's typical cost in the live
   fleet, or the default where the fleet cannot say."
  [casualty machine]
  (or (get-in machine [:typical (:project casualty)]) default-session-bytes))

(defn- ranked
  "Newest activity first; a casualty with no recorded conversation after every one that has, by
   registration time, newest first — an ISO instant, so it orders as a string."
  [facts]
  (let [{seen true unseen false} (group-by (comp some? :conversation) facts)]
    (concat (sort-by #(- (get-in % [:conversation :last-at-ms])) seen)
            (sort-by #(str (get-in % [:casualty :registered-at])) #(compare %2 %1) unseen))))

(defn ^{:malli/schema [:=> [:cat :ReviveInput] [:vector :PlanRow]]}
  plan
  "The rows to revive, to reopen only, to leave resumable, and gone — ranked, then admitted.

   :only restricts the casualties considered; :all? admits every one whatever the budget. A gone
   worktree is :gone; a conflicted one, or one jj could not answer for, is :conversation-only and
   costs nothing."
  [{:keys [facts machine only all?]}]
  (let [{:keys [physical committed]} machine
        known?  (and physical committed (pos? physical))
        ceiling (when known? (* fleet/budget-fraction physical))]
    (loop [[f & more] (ranked (cond->> facts only (filter #(contains? only (get-in % [:casualty :instance-id])))))
           spent    (or committed 0)
           rows     []]
      (if-not f
        rows
        (let [cost (estimated-bytes (:casualty f) machine)]
          (cond
            (= :gone (:worktree f))
            (recur more spent (conj rows {:row :gone :facts f}))

            (not= :none (:conflicts f))
            (recur more spent
                   (conj rows {:row :conversation-only :facts f
                               :why (if (= :conflicted (:conflicts f)) :conflicted :conflicts-unknown)}))

            (or all? (and known? (<= (+ spent cost) ceiling)))
            (recur more (+ spent cost) (conj rows {:row :revive :facts f :estimated-bytes cost}))

            (not known?)
            (recur more spent (conj rows {:row :resumable :facts f :why :memory-unknown}))

            :else
            (recur more spent (conj rows {:row :resumable :facts f :why :over-budget}))))))))
