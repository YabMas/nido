(ns nido.platform.secrets
  "Named credentials — the Notion and Slack tokens — kept where the host keeps such things.

   On macOS that is the login Keychain, as a generic password under the service name and the
   user's account. On a host without one (Linux) it is one file per service under
   <nido-home>/secrets, readable by its owner alone: the directory is 0700 and each file 0600,
   written so no other mode is ever on disk."
  (:require
   [babashka.fs :as fs]
   [babashka.process :as p]
   [clojure.string :as str]
   [nido.platform.core :as core]))

(defn ^{:malli/schema [:=> [:cat :any] :map]}
  sh!
  "Wrapped shell-out so tests can stub the `security` calls."
  [args]
  (p/sh args))

(defn ^{:malli/schema [:=> [:cat] :boolean]}
  keychain?
  "True on macOS, where `security` and the login Keychain exist."
  []
  (str/starts-with? (str/lower-case (System/getProperty "os.name" "")) "mac"))

(defn- whoami []
  (str/trim (:out (p/sh ["whoami"]))))

(defn- secrets-dir [] (str (fs/path (core/nido-root) "secrets")))

(defn- secret-file [service] (str (fs/path (secrets-dir) service)))

(def ^:private owner-only-dir  (fs/str->posix "rwx------"))
(def ^:private owner-only-file (fs/str->posix "rw-------"))

(defn ^{:malli/schema [:=> [:cat :string] [:maybe :string]]}
  read-secret
  "The credential stored under `service`, trimmed, or nil when none is stored or it is blank."
  [service]
  (let [raw (if (keychain?)
              (let [{:keys [exit out]} (sh! ["security" "find-generic-password"
                                             "-s" service "-a" (whoami) "-w"])]
                (when (zero? exit) out))
              (let [f (secret-file service)]
                (when (fs/exists? f) (slurp f))))]
    (when raw
      (let [t (str/trim raw)] (when-not (str/blank? t) t)))))

(defn ^{:malli/schema [:=> [:cat :string :string] :map]}
  store-secret!
  "Store `value` under `service`, replacing any value already there. Answers {:exit :err}, as
   the Keychain's `security` does; a non-zero exit means nothing was stored."
  [service value]
  (if (keychain?)
    (sh! ["security" "add-generic-password" "-s" service "-a" (whoami) "-U" "-w" value])
    (try
      (let [dir (secrets-dir)
            f   (secret-file service)
            tmp (str f ".tmp")]
        (when-not (fs/exists? dir)
          (fs/create-dirs dir {:posix-file-permissions owner-only-dir}))
        (fs/set-posix-file-permissions dir owner-only-dir)
        (fs/delete-if-exists tmp)
        (fs/create-file tmp {:posix-file-permissions owner-only-file})
        (spit tmp value)
        (fs/move tmp f {:replace-existing true :atomic-move true})
        {:exit 0 :err ""})
      (catch Exception e
        {:exit 1 :err (ex-message e)}))))
