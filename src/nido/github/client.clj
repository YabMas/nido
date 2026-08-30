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
   (default 50). Returns {:status :ok :prs [{:number :url :title :merged-at}]}
   or {:error :auth|:gh}."
  ([repo] (list-merged-prs repo 50))
  ([repo limit]
   (let [{:keys [exit out err]}
         (sh! ["gh" "pr" "list" "--repo" repo
               "--state" "merged"
               "--json" "number,url,title,mergedAt"
               "--limit" (str limit)])]
     (if (zero? exit)
       {:status :ok
        :prs    (->> (json/parse-string out true)
                     (mapv (fn [m] {:number    (:number m)
                                    :url       (:url m)
                                    :title     (:title m)
                                    :merged-at (:mergedAt m)})))}
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
