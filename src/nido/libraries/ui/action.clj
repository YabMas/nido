(ns nido.libraries.ui.action
  "Actions as data, and the decoder each one implies.

   An action is `[method name fields]`: `method` :get or :post, `name` the keyword the caller's
   router turns into a URL, and `fields` a map from each key the handler receives to where its
   value comes from — `[:signal k schema]`, a signal in the instance's namespace, or
   `[:event k schema]`, a property of the DOM event, `k` its path (:target.value) — with the malli
   schema the value must satisfy, and optionally a fourth element `{:optional true}` for a value
   the request may leave out. That map is the one place a posted name is spelled.

   HIDES how an action becomes a Datastar expression and how a posted body is read. The expression
   sends only the instance's own signals, an event's value travelling as one of them, so the
   decoder reads one subtree; and the decoder is derived from the same declaration, so an
   untrusted body is parsed by nothing else and a handler sees only declared, validated fields."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [nido.libraries.ui.names :as names]))

(def Action
  "An action, as malli: its method, its name, and each posted field's placeholder — the source,
   where in it the value is, the schema it must satisfy, and optionally `{:optional true}`. What
   `nido.libraries.ui.block/block` checks every declared action against."
  (m/schema [:tuple [:enum :get :post] :keyword [:map-of :keyword [:or [:cat [:= :signal] [:or :keyword [:vector :keyword]] :some [:? [:map [:optional :boolean]]]] [:cat [:= :event] :keyword :some [:? [:map [:optional :boolean]]]]]]]))

(def Decoded
  "What `decode` answers, one arm per outcome, each carrying exactly what that outcome owes: the
   fields; :unparseable; :missing with the absent field's `:key`; :invalid with the `:key` and
   `:why` its schema refused the value."
  (m/schema [:or [:map {:closed true} [:ok :map]] [:map {:closed true} [:failure [:= :unparseable]]] [:map {:closed true} [:failure [:= :missing]] [:key :keyword]] [:map {:closed true} [:failure [:= :invalid]] [:key :keyword] [:why :some]]]))

(def ^:private event-ns
  "Where an event field's value is put before the request: under the instance, beside its own
   signals, so the body carries it like any other."
  :ev)

(defn- field-path
  "The signal path, inside the instance, that field `[source k _]` is read from."
  [[source k]]
  (case source
    :signal (if (keyword? k) [k] (vec k))
    :event  [event-ns (keyword (str/replace (name k) "." "_"))]))

(defn- js-string [s]
  (str "'" (str/replace (str s) #"(['\\])" "\\\\$1") "'"))

(defn- url-with-query [url query]
  (if (empty? query)
    url
    (str url (if (str/includes? url "?") "&" "?")
         (str/join "&" (for [[k v] (sort-by key query)]
                         (str (java.net.URLEncoder/encode (name k) "UTF-8") "="
                              (java.net.URLEncoder/encode (str v) "UTF-8")))))))

(defn ^{:malli/schema [:=> [:cat :any :any [:? :map]] :string]}
  expression
  "The Datastar expression performing `action` for one instance. `router` is a function of the
   action's name and `(:params opts)` returning its URL; `opts` carries the instance — `:scope`
   and `:id` — and may carry `:params` for the router and `:query`, a map appended to the URL as
   its query string, and `:cleanup?`, true for a request that ends when the element issuing it
   leaves the page — a stream whose panel is replaced, which would otherwise hold its connection
   open for good. Event fields are copied into the instance's namespace first, so the request
   sends the instance's signals and nothing else."
  ([action router] (expression action router {}))
  ([[method action-name fields] router {:keys [scope id params query cleanup?]}]
   (let [url     (url-with-query (router action-name params) query)
         prefix  (if scope (names/signal-root scope id) [])
         copies  (for [[_ [source k :as field]] fields
                       :when (= :event source)]
                   (str "$" (str/join "." (concat prefix (map name (field-path field))))
                        " = evt." (name k)))
         root    (str/join "\\." prefix)
         options (cond-> []
                   (and scope id) (conj (str "filterSignals: {include: /^" root "(\\.|$)/}"))
                   cleanup?       (conj "requestCancellation: 'cleanup'"))
         request (str "@" (name method) "(" (js-string url)
                      (when (seq options) (str ", {" (str/join ", " options) "}"))
                      ")")]
     (str/join "; " (concat copies [request])))))

(defn ^{:malli/schema [:=> [:cat :any [:maybe :string] [:? :map]]
                       [:or [:map {:closed true} [:ok :map]] [:map {:closed true} [:failure [:= :unparseable]]] [:map {:closed true} [:failure [:= :missing]] [:key :keyword]] [:map {:closed true} [:failure [:= :invalid]] [:key :keyword] [:why :some]]]]}
  decode
  "`body` — the JSON signals a request posted, or a GET's `datastar` query value — read against
   `action`'s fields for the instance `opts` names (`:scope`, `:id`). Answers one arm of `Decoded`:
   `{:ok fields}`, each declared field present and satisfying its schema, or `{:failure kind}` —
   :unparseable when the body is not a JSON object, :missing with the absent `:key` (an absent
   body carries no field), :invalid with the `:key` and the `:why`. An optional field left out is absent from the fields. An action declaring no fields
   reads nothing and answers `{:ok {}}` whatever the body. Keys the action does not declare are
   never read."
  ([action body] (decode action body {}))
  ([[_ _ fields] body {:keys [scope id]}]
   (let [parsed (when (seq fields)
                  (if (str/blank? body)
                    {}
                    (try (json/parse-string body true) (catch Exception _ ::bad))))]
     (cond
       (empty? fields)    {:ok {}}
       (not (map? parsed)) {:failure :unparseable}
       :else
       (let [root (if scope (get-in parsed (map keyword (names/signal-root scope id))) parsed)]
         (reduce
          (fn [acc [k [_ _ schema opts :as field]]]
            (let [v (get-in root (field-path field) ::absent)]
              (cond
                (and (= ::absent v) (:optional opts)) acc
                (= ::absent v)          (reduced {:failure :missing :key k})
                (not (m/validate schema v))
                (reduced {:failure :invalid :key k
                          :why (me/humanize (m/explain schema v))})
                :else (assoc-in acc [:ok k] v))))
          {:ok {}}
          fields))))))
