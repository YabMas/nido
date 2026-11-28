(ns nido.github.client
  "Thin wrappers over the `gh` CLI. `sh!` is a redef seam so tests stub
   invocations. Uses the machine's existing `gh` auth (no nido-owned token)."
  (:require
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]))

(defn ^{:malli/schema [:=> [:cat :any] :map]}
  sh!
  "Shell out to gh. Returns {:exit :out :err}. Wrapped so tests can stub."
  [args]
  (p/sh args))

(defn- auth-error? [err]
  (boolean (and err (re-find #"(?i)auth" err))))

(defn ^{:malli/schema [:=> [:cat :string [:? :int]] :map]}
  list-merged-prs
  "Most-recent merged PRs for a repo (newest first), capped at `limit`
   (default 50). Returns {:status :ok :prs [{:number :url :title :merged-at :base}]}
   or {:error :auth|:gh}.

   `:base` is the branch the PR merged INTO, and it is not decoration: `gh pr
   list --state merged` reports every merge in the repo, including a stack's
   internal merge of one layer into the layer beneath it. Such a merge lands
   nothing on the default branch, so a caller reacting to \"merged\" without
   reading this closes work that never shipped."
  ([repo] (list-merged-prs repo 50))
  ([repo limit]
   (let [{:keys [exit out err]}
         (sh! ["gh" "pr" "list" "--repo" repo
               "--state" "merged"
               "--json" "number,url,title,mergedAt,baseRefName"
               "--limit" (str limit)])]
     (if (zero? exit)
       {:status :ok
        :prs    (->> (json/parse-string out true)
                     (mapv (fn [m] {:number    (:number m)
                                    :url       (:url m)
                                    :title     (:title m)
                                    :merged-at (:mergedAt m)
                                    :base      (:baseRefName m)})))}
       {:error (if (auth-error? err) :auth :gh) :detail (str/trim (or err ""))}))))

(defn ^{:malli/schema [:=> [:cat :string :string [:? :int]] :map]}
  list-assigned-issues
  "Open issues in `repo` assigned to `assignee` (e.g. \"@me\"), capped at `limit`
   (default 100). Returns {:status :ok :issues [{:number :url :title}]}
   or {:error :auth|:gh}."
  ([repo assignee] (list-assigned-issues repo assignee 100))
  ([repo assignee limit]
   (let [{:keys [exit out err]}
         (sh! ["gh" "issue" "list" "--repo" repo
               "--assignee" assignee
               "--state" "open"
               "--json" "number,url,title"
               "--limit" (str limit)])]
     (if (zero? exit)
       {:status :ok
        :issues (->> (json/parse-string out true)
                     (mapv (fn [m] {:number (:number m)
                                    :url    (:url m)
                                    :title  (:title m)})))}
       {:error (if (auth-error? err) :auth :gh) :detail (str/trim (or err ""))}))))

(defn ^{:malli/schema [:=> [:cat :string :any] :map]}
  view-issue
  "Fetch one issue's metadata + body. Returns
   {:status :ok :issue {:number :url :title :body}} or {:error :auth|:gh}."
  [repo number]
  (let [{:keys [exit out err]}
        (sh! ["gh" "issue" "view" (str number) "--repo" repo
              "--json" "number,url,title,body"])]
    (if (zero? exit)
      {:status :ok
       :issue  (let [m (json/parse-string out true)]
                 {:number (:number m) :url (:url m) :title (:title m) :body (:body m)})}
      {:error (if (auth-error? err) :auth :gh) :detail (str/trim (or err ""))})))

(defn ^{:malli/schema [:=> [:cat :string :int] :map]}
  squash-pr!
  "Squash-merge pull request `number` of `repo` into its base branch now, marking it ready first
   when it is a draft — gh refuses to merge a draft. {:status :ok :via :squash} or
   {:error :auth|:gh :detail}, :detail carrying gh's refusal in its own words."
  [repo number]
  (let [fail  (fn [{:keys [err out]}]
                (let [msg (str/trim (str err (when (str/blank? err) out)))]
                  {:error (if (auth-error? msg) :auth :gh) :detail msg}))
        draft (sh! ["gh" "pr" "view" (str number) "--repo" repo "--json" "isDraft" "--jq" ".isDraft"])
        ready (when (and (zero? (:exit draft)) (= "true" (str/trim (str (:out draft)))))
                (sh! ["gh" "pr" "ready" (str number) "--repo" repo]))]
    (cond
      (not (zero? (:exit draft)))           (fail draft)
      (and ready (not (zero? (:exit ready)))) (fail ready)
      :else
      (let [m (sh! ["gh" "pr" "merge" (str number) "--repo" repo "--squash"])]
        (if (zero? (:exit m)) {:status :ok :via :squash} (fail m))))))

(def ^:private auto-merge-refused
  "What `gh pr merge --auto` answers where it cannot put a PR on auto-merge: GitHub's refusal on a
   repository that disables auto-merge, or gh's own demand for a merge method, which it makes only
   when the PR's base has no merge queue. Either way the repository's setup decides what follows."
  #"(?i)auto.?merge is not allowed|enablePullRequestAutoMerge|--merge, --rebase, or --squash required")

(def ^:private merge-route-query
  "Whether a repository allows auto-merge and squash merges, and the pull request's node id and
   whether its base branch has a merge queue — everything land-pr! picks its route by, in one read."
  (str "query($owner:String!,$name:String!,$number:Int!){repository(owner:$owner,name:$name){"
       "autoMergeAllowed squashMergeAllowed pullRequest(number:$number){id isMergeQueueEnabled}}}"))

(defn ^{:malli/schema [:=> [:cat :string :int] :map]}
  land-pr!
  "Put pull request `number` of `repo` on its way to its base branch: `gh pr merge --auto`, which
   on a merge-queue branch enqueues it once its checks allow. Where gh refuses that, the
   repository's setup picks the route: a base with a merge queue gets an `enqueuePullRequest`, and
   a repository with neither a queue nor auto-merge is squash-merged now (squash-pr!) — nothing
   else would ever merge it. No merge strategy is passed to the queue: its own decides and refuses
   one. {:status :ok :via :auto|:queue|:squash} or {:error :auth|:gh :detail}, :detail carrying
   gh's refusal in its own words, since a queue's refusal names the protection to satisfy."
  [repo number]
  (let [fail (fn [{:keys [err out]}]
               (let [msg (str/trim (str err (when (str/blank? err) out)))]
                 {:error (if (auth-error? msg) :auth :gh) :detail msg}))
        auto (sh! ["gh" "pr" "merge" (str number) "--repo" repo "--auto"])]
    (cond
      (zero? (:exit auto))
      {:status :ok :via :auto}

      (not (re-find auto-merge-refused (str (:err auto) (:out auto))))
      (fail auto)

      :else
      (let [[owner nm] (str/split repo #"/" 2)
            view  (sh! ["gh" "api" "graphql" "-f" (str "query=" merge-route-query)
                        "-f" (str "owner=" owner) "-f" (str "name=" nm) "-F" (str "number=" number)])
            route (when (zero? (:exit view))
                    (some-> (try (json/parse-string (:out view) true) (catch Exception _ nil))
                            :data :repository))
            {:keys [id isMergeQueueEnabled]} (:pullRequest route)]
        (cond
          (or (nil? route) (str/blank? (str id)))
          (if (zero? (:exit view))
            {:error :gh :detail (str "could not read how " repo " merges pull request " number ": "
                                     (str/trim (str (:out view))))}
            (fail view))

          isMergeQueueEnabled
          (let [q (sh! ["gh" "api" "graphql"
                        "-f" "query=mutation($id:ID!){enqueuePullRequest(input:{pullRequestId:$id}){mergeQueueEntry{position state}}}"
                        "-f" (str "id=" id)])]
            (if (zero? (:exit q)) {:status :ok :via :queue} (fail q)))

          (:autoMergeAllowed route)
          (fail auto)

          (:squashMergeAllowed route)
          (squash-pr! repo number)

          :else
          {:error :gh
           :detail (str repo " has no merge queue, disables auto-merge and disallows squash merges, "
                        "so nido has no merge it can run for pull request " number)})))))

(def ^:private queue-timeline-query
  "A pull request's merged flag and one page of its merge-queue adds and removals, oldest first,
   from `$cursor` on (from the start when it is absent)."
  (str "query($owner:String!,$name:String!,$number:Int!,$cursor:String){repository(owner:$owner,name:$name){"
       "pullRequest(number:$number){merged timelineItems(first:100,after:$cursor,itemTypes:"
       "[ADDED_TO_MERGE_QUEUE_EVENT,REMOVED_FROM_MERGE_QUEUE_EVENT]){pageInfo{hasNextPage endCursor} "
       "nodes{__typename "
       "... on AddedToMergeQueueEvent{id createdAt} "
       "... on RemovedFromMergeQueueEvent{id createdAt reason}}}}}}"))

(defn ^{:malli/schema [:=> [:cat :string :int] :map]}
  merge-queue-timeline
  "Pull request `number` of `repo`: whether it is merged, and its whole merge-queue timeline,
   oldest first — {:status :ok :merged? bool :events [{:type :added|:removed :id :at
   (:reason)}]} or {:error :auth|:gh :detail}. The timeline is read page by page from its start;
   :merged? is the flag of the last page's request, which was made after every event read, so a
   caller reading a removal knows whether the PR had merged by the time it was seen."
  [repo number]
  (let [[owner name] (str/split repo #"/" 2)
        page (fn [cursor]
               (sh! (cond-> ["gh" "api" "graphql"
                             "-f" (str "query=" queue-timeline-query)
                             "-f" (str "owner=" owner) "-f" (str "name=" name)
                             "-F" (str "number=" number)]
                      cursor (conj "-f" (str "cursor=" cursor)))))
        event (fn [{:keys [__typename id createdAt reason]}]
                (when-let [t (case __typename
                               "AddedToMergeQueueEvent"     :added
                               "RemovedFromMergeQueueEvent" :removed
                               nil)]
                  (cond-> {:type t :id id :at createdAt}
                    (not (str/blank? reason)) (assoc :reason reason))))]
    (loop [cursor nil events []]
      (let [{:keys [exit out err]} (page cursor)]
        (if-not (zero? exit)
          {:error (if (auth-error? err) :auth :gh) :detail (str/trim (or err ""))}
          (let [pr     (get-in (json/parse-string out true) [:data :repository :pullRequest])
                items  (:timelineItems pr)
                events (into events (keep event) (:nodes items))
                {:keys [hasNextPage endCursor]} (:pageInfo items)]
            (if (and hasNextPage (not (str/blank? endCursor)))
              (recur endCursor events)
              {:status :ok :merged? (boolean (:merged pr)) :events events})))))))
