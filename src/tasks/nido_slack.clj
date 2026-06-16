(ns tasks.nido-slack
  "Bb task entry points for Slack auth. The token is an xoxb- bot token with
   channels:history (public) or groups:history (private) scope, and the bot
   must be a member of the bug channel."
  (:require
   [clojure.string :as str]
   [nido.slack.client :as slack]))

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
