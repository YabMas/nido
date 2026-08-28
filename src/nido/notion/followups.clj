(ns nido.notion.followups
  "The personal follow-up database — the horizontal destination of the shipping
   doctrine (see the `/spin-out` skill). Work that leaves a branch lands here
   with the reasoning that sent it out; work that stays never touches this ns.

   Config lives at ~/.nido/coordinator/config.edn under `:followups`, NOT in a
   project's notion-views.edn: the DB is personal and cross-project, and
   notion-views.edn is per-project by construction.

     {:followups
      {:database   \"<notion-db-id>\"
       :properties {:title \"Title\"   :origin     \"Origin\"
                    :kind  \"Kind\"    :reason     \"Reason\"
                    :decay \"Decay\"   :cold-start \"Cold start\"
                    :effort \"Effort\" :status     \"Status\"
                    :project \"Project\"}}}

   Property *display-names* belong to whoever owns the DB and live in config.
   Property *types* and the closed value vocabularies are nido's, and live
   here — so a typo fails locally against the vocabulary instead of arriving as
   a Notion 400, and renaming a Notion property is a config edit rather than a
   code change. `check-config` cross-checks both against the live schema."
  (:require
   [clojure.string :as str]
   [nido.coordinator.state :as cstate]
   [nido.platform.io :as io]
   [nido.notion.client :as client]))

(def field-types
  "Semantic field → the Notion property type nido writes for it."
  {:title      :title
   :origin     :rich-text
   :kind       :select
   :reason     :rich-text
   :decay      :select
   :cold-start :select
   :effort     :select
   :status     :select
   :project    :select})

(def vocabularies
  "Closed value sets for the select fields nido writes. A value outside these
   is rejected before any HTTP call — a spin-out that fails at the API is a
   spin-out that silently didn't happen."
  {:kind       #{"cleanup" "bug-found-not-caused" "test-debt"
                 "migration-remainder" "question" "perf"}
   :decay      #{"compounding" "flat" "cheaper-later"}
   :cold-start #{"cheap" "needs-context"}
   :effort     #{"XS" "S" "M" "L" "XL" "squirrel"}
   :status     #{"Open" "Promoted" "Declined" "Done"}})

(def required-fields
  "Fields with no defensible default. `:reason` is required for the same reason
   triage's `:squirrel` requires a `:defer-note`: a deferral that doesn't carry
   why it was deferred is not a deferral, it's a shrug. `:decay` and
   `:cold-start` are required because they are what makes the DB drainable —
   without them the list can only be ordered by date, which is how a backlog
   becomes a graveyard."
  [:title :reason :kind :decay :cold-start])

(def field-defaults
  {:status "Open"
   :effort "squirrel"})

(def decay-rank
  "Drain order: what gets worse fastest comes first."
  {"compounding" 0 "flat" 1 "cheaper-later" 2})

(defn config
  "The `:followups` config map, or nil when unconfigured (io/read-edn already
   returns nil for a missing file)."
  []
  (:followups (io/read-edn (cstate/config-path))))

(defn config!
  "Like `config`, but throws with a setup hint rather than returning nil —
   for the write paths, where silently doing nothing is the worst outcome."
  []
  (let [{:keys [database properties] :as cfg} (config)]
    (when-not (and database (seq properties))
      (throw (ex-info "No follow-up database configured"
                      {:hint (str "Add :followups {:database \"<notion-db-id>\" "
                                  ":properties {...}} to " (cstate/config-path)
                                  " — see nido.notion.followups for the shape.")})))
    cfg))

(defn- prop-name
  "The Notion display-name configured for `field`. Throws rather than guessing:
   a wrong name writes nothing and reports success."
  [cfg field]
  (or (get-in cfg [:properties field])
      (throw (ex-info (str "No Notion property name configured for " field)
                      {:field field
                       :configured (vec (keys (:properties cfg)))
                       :hint (str "Add it under :followups :properties in "
                                  (cstate/config-path))}))))

(defn validate
  "Return a vector of human-readable problems with `entry` (empty when it is
   fileable). Checks required fields and every closed vocabulary."
  [entry]
  (vec
   (concat
    (for [f required-fields
          :when (str/blank? (str (get entry f)))]
      (str "missing required field " f))
    (for [[f allowed] vocabularies
          :let [v (get entry f)]
          :when (and (some? v) (not (contains? allowed (str v))))]
      (str f " must be one of " (str/join " | " (sort allowed))
           " (got " (pr-str v) ")")))))

(defn ->properties
  "Build the Notion properties payload for `entry` against the configured
   display-names. Only fields present after defaults are written, so a DB
   without an optional property simply never receives it."
  [cfg entry]
  (let [entry (merge field-defaults entry)]
    (into {}
          (for [[field v] entry
                :let [t (get field-types field)]
                :when (and t (not (str/blank? (str v))))]
            [(prop-name cfg field)
             (case t
               :title     {:title [{:text {:content (str v)}}]}
               :rich-text {:rich_text [{:text {:content (str v)}}]}
               :select    {:select {:name (str v)}})]))))

(defn create!
  "File `entry` in the follow-up DB. `entry` keys are the semantic fields of
   `field-types`; `:description` (optional) becomes the page body. Returns the
   created page ({:id \"FU-##\" :url … :page-id …}) or {:error :kw}. Throws only
   on misconfiguration — an unconfigured DB is a setup bug, not a runtime state."
  [{:keys [description] :as entry}]
  (let [cfg    (config!)
        errors (validate entry)]
    (if (seq errors)
      {:error :invalid :problems errors}
      (let [token (or (client/keychain-token)
                      (throw (ex-info "No Notion token in keychain"
                                      {:hint "Run bb nido:notion:auth:set"})))
            ds    (client/resolve-data-source-id (:database cfg) token)]
        (client/create-page-with-properties!
         ds token
         (->properties cfg (dissoc entry :description))
         (or description ""))))))

(defn list-entries
  "Open follow-ups, ordered by decay pressure then effort — what rots fastest
   first. `status` defaults to \"Open\". Returns a vector of normalised pages or
   {:error :kw}."
  ([] (list-entries "Open"))
  ([status]
   (let [cfg   (config!)
         token (or (client/keychain-token)
                   (throw (ex-info "No Notion token in keychain"
                                   {:hint "Run bb nido:notion:auth:set"})))
         ds    (client/resolve-data-source-id (:database cfg) token)
         res   (client/data-source-query
                ds token
                {:filter {:property (prop-name cfg :status)
                          :select   {:equals status}}})]
     (if (:error res)
       {:error (:error res)}
       (->> (:results res)
            (mapv client/normalise-page)
            (sort-by (juxt #(get decay-rank (:decay %) 99) :effort))
            vec)))))

(defn check-config
  "Cross-check the configured display-names and nido's vocabularies against the
   live data source: every configured property must exist, and every select
   field's vocabulary must be a subset of that property's options. Returns
   {:status :ok} or {:status :error :errors [{:message …} …]} — the same shape
   as nido.notion.views-check, so callers surface both the same way."
  [token]
  (let [cfg      (config!)
        ds-id    (client/resolve-data-source-id (:database cfg) token)
        ds       (client/retrieve-data-source ds-id token)
        db-props (:properties ds)
        prop-of  (fn [n] (or (get db-props n) (get db-props (keyword n))))
        missing  (for [[field n] (:properties cfg)
                       :when (and (get field-types field) (nil? (prop-of n)))]
                   {:message (str "Property '" n "' (" field ") not found on database "
                                  (:database cfg))})
        bad-opts (for [[field allowed] vocabularies
                       :let  [n    (get-in cfg [:properties field])
                              opts (some-> (prop-of n) :select :options)]
                       :when (and n opts)
                       v     (sort allowed)
                       :when (not (some #(= v (:name %)) opts))]
                   {:message (str "Property '" n "' (" field ") has no option '" v "'")})
        errors   (concat missing bad-opts)]
    (if (seq errors)
      {:status :error :errors (vec errors)}
      {:status :ok})))
