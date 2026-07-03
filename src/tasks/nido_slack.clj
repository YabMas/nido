(ns tasks.nido-slack
  "Bb task entry points for Slack auth + write actions. The token is an
   xoxb- bot token with channels:history (public) or groups:history
   (private) scope, and the bot must be a member of the bug channel."
  (:require
   [clojure.string :as str]
   [nido.slack.client :as slack]
   [nido.task-args :as task-args]))

(def ^:private react-raw-keys
  "Kwarg keys for `react` whose values must be passed through verbatim. Slack
   timestamps (`:ts`) look like `1718000000.000123` — read-string would parse
   that as a double, and `(str ...)` on the double renders scientific
   notation and drops trailing-zero microseconds, corrupting the value so it
   never matches the real Slack message ts."
  #{:ts})

(def ^:private reply-raw-keys
  "Kwarg keys for `reply` whose values must be passed through verbatim.
   `:thread-ts` is a Slack timestamp — corrupted by read-string the same way
   as `react`'s `:ts` (see `react-raw-keys`). `:text` may contain
   EDN-significant characters (e.g. punctuation, `[...]`) that read-string
   would otherwise mangle."
  #{:thread-ts :text})

(defn- already-reacted?
  "Slack's response to re-reacting a message it already reacted to. Benign
   and idempotent from our POV — not a real failure."
  [res]
  (= "already_reacted" (:detail res)))

(defn auth-set
  "bb nido:slack:auth:set — read a bot token from stdin, store in macOS Keychain.
   Stdin is echoed (Babashka can't trivially disable terminal echo); the user
   should clear their terminal scrollback after running this."
  [& _args]
  (println "Paste your Slack bot token (xoxb-...) (input is echoed; clear terminal afterwards):")
  (let [token (read-line)]
    (cond
      (or (nil? token) (str/blank? token))
      (do (println "Empty token; aborted.") (System/exit 1))

      :else
      (let [{:keys [exit err]} (slack/keychain-set! token)]
        (if (zero? exit)
          (println "Token stored. Run `bb nido:slack:auth:check` to verify, then `bb nido:coordinator:restart`.")
          (do (println "security failed (exit" exit ").")
              (println err)
              (System/exit exit)))))))

(defn auth-check
  "bb nido:slack:auth:check — print whether the keychain has a token."
  [& _args]
  (let [token (slack/keychain-token)]
    (cond
      (nil? token)
      (do (println "No Slack token in keychain. Run `bb nido:slack:auth:set`.")
          (System/exit 1))

      (str/blank? token)
      (do (println "Keychain entry is empty.") (System/exit 1))

      :else
      (println "Slack token present in keychain (length" (count token) ")."))))

(defn react
  "bb nido:slack:react :channel <C> :ts <ts> [:name eyes]"
  [& args]
  (let [[_ o]   (task-args/split-args args react-raw-keys)
        channel (str (:channel o))
        ts      (str (:ts o))
        name    (str (or (:name o) "eyes"))
        res     (slack/add-reaction channel ts (slack/keychain-token) name)]
    (cond
      (:ok res)
      (println "reacted" name "on" channel ts)

      (already-reacted? res)
      (println "already reacted (benign):" channel ts)

      :else
      (do (binding [*out* *err*] (println "slack react failed:" res)) (System/exit 1)))))

(defn reply
  "bb nido:slack:reply :channel <C> :thread-ts <ts> :text \"...\""
  [& args]
  (let [[_ o]      (task-args/split-args args reply-raw-keys)
        channel    (str (:channel o))
        thread-ts  (str (:thread-ts o))
        text       (str (:text o))
        res        (slack/post-message channel (slack/keychain-token)
                                       {:text text :thread-ts thread-ts})]
    (if (:ok res)
      (println "replied on" channel thread-ts)
      (do (binding [*out* *err*] (println "slack reply failed:" res)) (System/exit 1)))))
