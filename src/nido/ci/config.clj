(ns nido.ci.config
  "Load and validate .nido/ci.edn for a project. The file lives at
   ~/.nido/projects/<project-name>/ci.edn alongside session.edn.

   See specs/local_ci.allium for the semantic model. This ns only handles
   structural validation; dependency-graph validation lives in
   nido.ci.services."
  (:require
   [babashka.fs :as fs]
   [nido.core :as core]
   [nido.io :as io]))

(def default-defaults
  "Defaults applied when ci.edn omits :defaults or leaves keys unset.
   Values mirror specs/local_ci.allium config section."
  {:healthcheck-timeout-ms 60000
   :healthcheck-interval-ms 1000
   :consecutive-successes 2
   :step-run-timeout-ms (* 30 60 1000)
   :default-viewer :none})

(defn- config-path [project-name]
  (str (fs/path (core/nido-home) "projects" project-name "ci.edn")))

(defn- validate-service! [step-name svc]
  (let [{:keys [name start healthcheck depends-on]} svc]
    (when-not (keyword? name)
      (throw (ex-info "Service :name must be a keyword"
                      {:step step-name :service svc})))
    (when-not (and (string? start) (seq start))
      (throw (ex-info "Service :start must be a non-empty string"
                      {:step step-name :service name})))
    (when (and depends-on (not (every? keyword? depends-on)))
      (throw (ex-info "Service :depends-on must be a seq of keywords"
                      {:step step-name :service name})))
    (when healthcheck
      (let [{:keys [kind port port-from-env command]} healthcheck]
        (when-not (#{:port-ready :command} kind)
          (throw (ex-info "Healthcheck :kind must be :port-ready or :command"
                          {:step step-name :service name :kind kind})))
        (when (= :port-ready kind)
          (when-not (or (pos-int? port) (string? port-from-env))
            (throw (ex-info "Port-ready healthcheck needs :port or :port-from-env"
                            {:step step-name :service name}))))
        (when (= :command kind)
          (when-not (and (string? command) (seq command))
            (throw (ex-info "Command healthcheck needs a non-empty :command"
                            {:step step-name :service name})))))))
  nil)

(defn- validate-step! [step-name step]
  (when-not (map? step)
    (throw (ex-info "Step must be a map" {:step step-name :value step})))
  (let [{:keys [command services isolated-pg? isolated-app? local-edn-overrides]} step]
    (when-not (and (string? command) (seq command))
      (throw (ex-info "Step :command must be a non-empty string"
                      {:step step-name})))
    (when (and (some? isolated-pg?) (not (boolean? isolated-pg?)))
      (throw (ex-info "Step :isolated-pg? must be a boolean"
                      {:step step-name :got isolated-pg?})))
    (when (and (some? isolated-app?) (not (boolean? isolated-app?)))
      (throw (ex-info "Step :isolated-app? must be a boolean"
                      {:step step-name :got isolated-app?})))
    (when (and (some? local-edn-overrides) (not (map? local-edn-overrides)))
      (throw (ex-info "Step :local-edn-overrides must be a map"
                      {:step step-name :got local-edn-overrides})))
    (when services
      (when-not (sequential? services)
        (throw (ex-info "Step :services must be a sequence"
                        {:step step-name})))
      (let [names (map :name services)]
        (when (not= (count names) (count (set names)))
          (throw (ex-info "Step has duplicate service names"
                          {:step step-name :services (vec names)}))))
      (doseq [svc services]
        (validate-service! step-name svc)))))

(defn- validate-profiles! [profiles step-keys project-name]
  (when (some? profiles)
    (when-not (map? profiles)
      (throw (ex-info "ci.edn :profiles must be a map of profile-key → step-key seq"
                      {:project project-name :got profiles})))
    (doseq [[pname pdef] profiles]
      (when-not (keyword? pname)
        (throw (ex-info "ci.edn :profiles keys must be keywords"
                        {:project project-name :got pname})))
      (when-not (sequential? pdef)
        (throw (ex-info (str "ci.edn :profiles " pname
                             " value must be a seq of step keywords")
                        {:project project-name :profile pname :got pdef})))
      (doseq [step-kw pdef]
        (when-not (contains? step-keys step-kw)
          (throw (ex-info (str "ci.edn :profiles " pname
                               " references unknown step '" step-kw "'")
                          {:project project-name :profile pname
                           :step step-kw :known-steps (vec step-keys)})))))))

(defn- validate-config! [config project-name]
  (when-not (map? config)
    (throw (ex-info "ci.edn must be a map"
                    {:project project-name :path (config-path project-name)})))
  (let [{:keys [schema-version steps profiles]} config]
    (when-not (= 1 schema-version)
      (throw (ex-info "ci.edn :schema-version must be 1"
                      {:got schema-version :project project-name})))
    (when-not (and (map? steps) (seq steps))
      (throw (ex-info "ci.edn :steps must be a non-empty map"
                      {:project project-name})))
    (doseq [[step-name step] steps]
      (validate-step! step-name step))
    (validate-profiles! profiles (set (keys steps)) project-name)))

(defn load-config
  "Load, validate, and return the ci.edn config for a project. Throws with
   an actionable hint if the file is missing or malformed."
  [project-name]
  (let [path (config-path project-name)]
    (when-not (fs/exists? path)
      (throw (ex-info (str "No ci.edn found for project '" project-name "'")
                      {:path path
                       :hint (str "Create a ci.edn at "
                                  "~/.nido/projects/" project-name "/ci.edn")})))
    (let [config (io/read-edn path)]
      (validate-config! config project-name)
      (update config :defaults #(merge default-defaults %)))))

(defn resolve-step
  "Return the step definition for step-name in config, or throw if unknown."
  [config step-name]
  (let [step-kw (if (keyword? step-name) step-name (keyword step-name))]
    (or (get-in config [:steps step-kw])
        (throw (ex-info (str "No step '" (name step-kw) "' in ci.edn")
                        {:step step-kw
                         :available (vec (keys (:steps config)))})))))
