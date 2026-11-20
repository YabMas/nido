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

(def ^:private auto-merge-off
  "What GitHub answers `gh pr merge --auto` with on a repository that disables auto-merge: the call
   never reaches the merge queue, and an enqueue is the queue's own entry point."
  #"(?i)auto.?merge is not allowed|enablePullRequestAutoMerge")

(defn ^{:malli/schema [:=> [:cat :string :int] :map]}
  land-pr!
  "Put pull request `number` of `repo` on its way to its base branch: `gh pr merge --auto`, which
   on a merge-queue branch enqueues it once its checks allow; where the repository refuses
   auto-merge, an `enqueuePullRequest` by the node id read here. No merge strategy is passed: a
   queue's own decides and refuses one. {:status :ok :via :auto|:queue} or
   {:error :auth|:gh :detail}, :detail carrying gh's refusal in its own words, since a queue's
   refusal names the protection to satisfy."
  [repo number]
  (let [fail (fn [{:keys [err out]}]
               (let [msg (str/trim (str err (when (str/blank? err) out)))]
                 {:error (if (auth-error? msg) :auth :gh) :detail msg}))
        auto (sh! ["gh" "pr" "merge" (str number) "--repo" repo "--auto"])]
    (cond
      (zero? (:exit auto))
      {:status :ok :via :auto}

      (not (re-find auto-merge-off (str (:err auto) (:out auto))))
      (fail auto)

      :else
      (let [view (sh! ["gh" "pr" "view" (str number) "--repo" repo "--json" "id" "--jq" ".id"])
            id   (str/trim (str (:out view)))]
        (if (or (not (zero? (:exit view))) (str/blank? id))
          (fail view)
          (let [q (sh! ["gh" "api" "graphql"
                        "-f" "query=mutation($id:ID!){enqueuePullRequest(input:{pullRequestId:$id}){mergeQueueEntry{position state}}}"
                        "-f" (str "id=" id)])]
            (if (zero? (:exit q)) {:status :ok :via :queue} (fail q))))))))
