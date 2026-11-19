(ns canvas.platform.secrets
  "Self-spec: `nido.platform.secrets` — where a credential is kept.

   The macOS Keychain on a Mac. A host without one — a headless Linux box has no unlocked
   keyring for a daemon to read — keeps them in an owner-only file under nido-home."
  (:require [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]))

(Module platform-secrets
  "Where a credential is kept."
  (Operation sh!
    "Shell out — a redef seam, so the Keychain verbs can be tested without a Keychain."
    {:signature [:=> [:catn [:args :any]] :map]})
  (Operation keychain?
    "Whether this host keeps credentials in the macOS Keychain. Wrapped so a test can pick."
    {:signature [:=> [:catn] :boolean]})
  (Operation read-secret
    "The credential stored under `service`, or nil when there is none."
    {:signature [:=> [:catn [:service :string]] [:maybe :string]]})
  (Operation store-secret!
    "Store `value` under `service`, replacing what was there, as {:exit :err}."
    {:signature [:=> [:catn [:service :string] [:value :string]] :map]}))
