(ns nido.coordinator.daemon.preprocess
  "Pre-Run preprocessor dispatch. Runs configured preprocessors between
   envelope dequeue and session-spawn. Currently one entry:
   `:notion-ticket` → shells out to `bb nido:notion:preprocess-ticket`.

   Failures here abort the Run before claude launches."
  (:refer-clojure :exclude [run!])
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [nido.coordinator.record.state :as cstate]))

(defn ^{:malli/schema [:=> [:cat :any] :map]}
  shell-bb-task
  "Shell-out to a bb task. Returns {:exit :out :err}. Redef seam."
  [args]
  (let [proc @(p/process args {:out :string :err :string})]
    {:exit (:exit proc)
     :out  (str (:out proc))
     :err  (str (:err proc))}))

(defn- parse-budget-s
  "Parse a duration string. Same accepted forms as :limits.budget."
  [s]
  (cond
    (nil? s)                 600  ;; default 10m
    (re-matches #"\d+s?" s)  (Integer/parseInt (str/replace s #"s$" ""))
    (re-matches #"\d+m" s)   (* 60 (Integer/parseInt (str/replace s #"m$" "")))
    (re-matches #"\d+h" s)   (* 3600 (Integer/parseInt (str/replace s #"h$" "")))
    :else                    600))

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  invoke-notion-ticket!
  "Shell out to `bb nido:notion:preprocess-ticket`. Returns the registry
   entry contract: {:ok? true} or {:ok? false :error {...}}."
  [{:keys [run budget-s out-dir]}]
  (let [page-id (some-> run :event-payload :page-id)]
    (cond
      (str/blank? page-id)
      {:ok? false :error {:reason :missing-page-id
                          :detail {:event-payload-keys (vec (keys (:event-payload run)))}}}

      :else
      (let [args ["bb" "nido:notion:preprocess-ticket"
                  ":page" page-id
                  ":out"  out-dir
                  ":budget" (str budget-s "s")]
            log  (str (fs/path out-dir "notion-ticket.log"))
            _    (fs/create-dirs out-dir)
            {:keys [exit out err]} (shell-bb-task args)]
        (spit log (str "STDOUT:\n" out "\nSTDERR:\n" err))
        (if (zero? exit)
          {:ok? true}
          ;; bb's :init writes a banner to stderr; the EDN error is the
          ;; LAST non-blank line starting with `{`.
          (let [last-edn-line (->> (str/split-lines err)
                                   (filter (fn [l] (str/starts-with? (str/triml l) "{")))
                                   last)
                parsed (try (edn/read-string (str/trim (or last-edn-line err)))
                            (catch Exception _
                              {:reason :unknown :detail {:stderr err}}))]
            {:ok? false :error parsed}))))))

(def default-registry
  "Single-entry registry for v1."
  {:notion-ticket invoke-notion-ticket!})

(defn ^{:malli/schema [:=> [:cat :map] :map]}
  run!
  "Run configured preprocessors for a Run before claude is spawned.
   Returns {:ok? true} or {:ok? false :error {:reason :preprocessor :detail}}.
   Stops at the first failing preprocessor."
  [{:keys [run registry]
    :or   {registry default-registry}}]
  (let [names (or (:preprocess run) [])]
    (if (empty? names)
      {:ok? true}
      (let [out-dir  (str (fs/path (cstate/run-dir (:id run)) "preprocess"))
            budget-s (parse-budget-s (some-> run :limits :preprocess-budget))]
        (loop [[n & more] names]
          (cond
            (nil? n) {:ok? true}

            (not (contains? registry n))
            {:ok? false :error {:reason :preprocess-unknown
                                :preprocessor n}}

            :else
            (let [impl  (get registry n)
                  r     (impl {:run run :budget-s budget-s :out-dir out-dir})]
              (if (:ok? r)
                (recur more)
                ;; Pre-shell validation failures (e.g. :missing-page-id) are
                ;; propagated directly so callers see the specific reason.
                ;; All other failures (shell exit != 0) are wrapped with
                ;; :preprocess-failed + :preprocessor for traceability.
                (let [inner  (:error r)
                      direct? (= :missing-page-id (:reason inner))
                      error  (if direct?
                               inner
                               (assoc {:reason      :preprocess-failed
                                       :preprocessor n}
                                      :detail inner))]
                  {:ok? false :error error})))))))))
