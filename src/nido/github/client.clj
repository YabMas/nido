(ns nido.github.client
  "Thin wrappers over the `gh` CLI. `sh!` is a redef seam so tests stub
   invocations. Uses the machine's existing `gh` auth (no nido-owned token)."
  (:require
   [babashka.process :as p]
   [cheshire.core :as json]
   [clojure.string :as str]))

(defn sh!
  "Shell out to gh. Returns {:exit :out :err}. Wrapped so tests can stub."
  [args]
  (p/sh args))

(defn- auth-error? [err]
  (boolean (and err (re-find #"(?i)auth" err))))

(defn list-merged-prs
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
