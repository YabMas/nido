(ns tasks.nido-notion
  "Bb task entry points for Notion auth (Stage 5). Recommended token type is
   a Notion Personal Access Token (PAT) — created in the Developer Portal,
   inherits the creator's read access without per-database Connection approval."
  (:require
   [clojure.string :as str]
   [nido.notion.client :as notion]))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  auth-set
  "bb nido:notion:auth:set — read a token from stdin, store in macOS Keychain.
   Stdin is echoed (Babashka can't trivially disable terminal echo); the user
   should clear their terminal scrollback after running this."
  [& _args]
  (println "Paste your Notion Personal Access Token (input is echoed; clear terminal afterwards):")
  (let [token (read-line)]
    (cond
      (or (nil? token) (str/blank? token))
      (do (println "Empty token; aborted.") (System/exit 1))

      :else
      (let [{:keys [exit err]} (notion/keychain-set! token)]
        (if (zero? exit)
          (println "Token stored. Run `bb nido:notion:auth:check` to verify, then `bb nido:coordinator:restart`.")
          (do (println "security failed (exit" exit ").")
              (println err)
              (System/exit exit)))))))

(defn ^{:malli/schema [:=> [:cat [:* :any]] :any]}
  auth-check
  "bb nido:notion:auth:check — print whether the keychain has a token."
  [& _args]
  (let [token (notion/keychain-token)]
    (cond
      (nil? token)
      (do (println "No Notion token in keychain. Run `bb nido:notion:auth:set`.")
          (System/exit 1))

      (str/blank? token)
      (do (println "Keychain entry is empty.") (System/exit 1))

      :else
      (println "Notion token present in keychain (length" (count token) ")."))))
