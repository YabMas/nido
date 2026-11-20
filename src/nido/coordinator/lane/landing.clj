(ns nido.coordinator.lane.landing
  "Land a workstream's change on its project's main by the procedure the project declares.

   HIDES which procedure a project lands by and what each one runs. Everything above asks for a
   landing — or only whether a landing names a pull request — and never names a merge strategy,
   a merge queue or a push, which is what lets the gate, the seat's one landing verb and the
   commit gate stay the same for every project. The declaration is read through
   `project/landing` and nowhere else."
  (:require
   [babashka.process :as p]
   [clojure.string :as str]
   [nido.github.client :as github]
   [nido.platform.project :as project]))

(defn- refusal [detail] {:error :refused :detail detail})

(defn ^{:malli/schema [:=> [:cat :ProjectName] [:or :boolean [:map [:error :keyword]]]]}
  names-pr?
  "Whether `project`'s declared landing lands a pull request — what a caller must hand land! as
   :pr, and what it must check is the workstream's own — without saying which procedure it is:
   true for :pr-auto and :pr-squash, false for :push-main. A declaration `project/landing`
   refuses is answered as that refusal, so a caller cannot read it as either."
  [project]
  (let [l (project/landing project)]
    (if (map? l) l (not= :push-main l))))

(defn- push-main!
  "Run `bb nido:land` in `dir` — the worktree's own verb, so the gate that runs is the one the
   change carries — answering its result. Its output is passed through as it prints."
  [dir]
  (if (str/blank? (str dir))
    (refusal "a :push-main landing needs the workstream's worktree, and none was given")
    (let [{:keys [exit]} @(p/process ["bb" "nido:land"] {:dir (str dir) :inherit true})]
      (if (zero? exit)
        {:status :ok :via :push}
        (refusal (str "bb nido:land exited " exit " — its own output says why"))))))

(defn ^{:malli/schema [:=> [:cat :ProjectName :WorkstreamId :map] :map]}
  land!
  "Land workstream `ws-id` of `project` by the procedure the project declares (`project/landing`):
   :pr-auto puts the PR :pr names — `owner/repo#n` — on auto-merge or its merge queue, :pr-squash
   squash-merges it now after marking a draft ready, :push-main runs `bb nido:land` in :dir.
   A PR procedure without a well-formed :pr is refused before anything runs, and so is a
   declaration `project/landing` refuses. The caller has already checked :pr is the workstream's
   own. {:status :ok :procedure … :via …}, or {:error … :detail …} with the procedure's refusal in
   its own words."
  [project _ws-id {:keys [pr dir]}]
  (let [l (project/landing project)]
    (if (map? l)
      (refusal (str "project " (name project) " declares :landing " (pr-str (:value l))
                    ", which is none of :pr-auto, :pr-squash or :push-main"))
      (let [[_ repo n] (some->> pr str (re-find #"^(.+)#(\d+)$"))
            r (case l
                :push-main (push-main! dir)
                (if-not repo
                  (refusal (str "a " (name l) " landing needs the workstream's own PR as owner/repo#n; got "
                                (pr-str pr)))
                  (if (= :pr-squash l)
                    (github/squash-pr! repo (parse-long n))
                    (github/land-pr! repo (parse-long n)))))]
        (cond-> (assoc r :procedure l) (string? pr) (assoc :pr pr))))))
